package com.axiom.launcher.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials (provider API keys) encrypted with a hardware-backed Android Keystore key and kept in their
 * own preferences file, which is excluded from backups — a restored ciphertext could never be decrypted on
 * another device anyway, and keys must never leave the phone in a backup.
 */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Decrypted values for this process only, so hot paths (routing checks) don't hit the Keystore. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun get(name: String): String? {
        cache[name]?.let { return it }
        val enc = sp.getString(name, null) ?: return null
        return runCatching { decrypt(enc) }.getOrNull()?.also { cache[name] = it }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            cache.remove(name)
            sp.edit().remove(name).apply()
        } else {
            sp.edit().putString(name, encrypt(value.trim())).apply()
            cache[name] = value.trim()
        }
    }

    fun has(name: String) = cache.containsKey(name) || sp.contains(name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val (iv, body) = enc.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(body, Base64.NO_WRAP)))
    }

    companion object {
        const val FILE = "axiom_secrets"
        private const val ALIAS = "axiom_secrets_v1"
        const val OPENROUTER = "openrouter_key"
        const val ANTHROPIC = "anthropic_key"
        const val LOCAL = "local_key"
    }
}

/**
 * Live battery, power-save, thermal and network state, event-driven (no polling). The Cortex reads it to
 * size concurrency and the UI reads it to scale animation.
 */
class DeviceConditions(private val context: Context, private val onChange: () -> Unit) {
    private val _state = MutableStateFlow(DeviceSnapshot())
    val state: StateFlow<DeviceSnapshot> = _state.asStateFlow()
    private val _reducedMotion = MutableStateFlow(false)
    val reducedMotion: StateFlow<Boolean> = _reducedMotion.asStateFlow()

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private var registered = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onAvailable(network: Network) = refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { refresh() }

    fun start() {
        if (registered) return
        registered = true
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, f)
        runCatching { pm.addThermalStatusListener(context.mainExecutor, thermalListener) }
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { pm.removeThermalStatusListener(thermalListener) }
    }

    fun refresh() {
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val next = DeviceSnapshot(
            online = online,
            metered = metered,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = pm.isPowerSaveMode,
            thermal = runCatching { pm.currentThermalStatus }.getOrDefault(0),
        )
        PerfMonitor.sampleBattery(next.batteryPct, next.charging)
        val animScale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        _reducedMotion.value = animScale == 0f || next.powerSave || next.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        if (next != _state.value) {
            _state.value = next
            onChange()
        }
    }
}

/** What kind of user profile apps come from. */
enum class ProfileKind(val label: String) { PERSONAL("Personal"), WORK("Work"), PRIVATE("Private space"), CLONE("Clone"), OTHER("Profile") }

data class ProfileInfo(
    val user: UserHandle,
    val kind: ProfileKind,
    /** Work profile turned off / private space locked: apps can't run and must look paused. */
    val paused: Boolean,
    val unlocked: Boolean,
)

/**
 * Profile awareness through supported APIs only (LauncherApps, UserManager). It never reaches into other
 * profiles' data; it only knows which profiles exist, their type and whether they're paused, and asks the
 * system (which enforces enterprise policy) to pause or resume them.
 */
class ProfileManager(private val context: Context, private val onChange: () -> Unit) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val _profiles = MutableStateFlow<List<ProfileInfo>>(emptyList())
    val profiles: StateFlow<List<ProfileInfo>> = _profiles.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { refresh(); onChange() }
    }

    fun start() {
        if (registered) return
        registered = true
        val f = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }
        // Profile broadcasts are protected (system-only), so a non-exported receiver still gets them.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, f)
        refresh()
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refresh() {
        val me = Process.myUserHandle()
        val users = runCatching { launcherApps.profiles }.getOrNull() ?: runCatching { userManager.userProfiles }.getOrDefault(listOf(me))
        _profiles.value = users.map { u -> ProfileInfo(u, kindOf(u, me), paused = isPaused(u), unlocked = runCatching { userManager.isUserUnlocked(u) }.getOrDefault(true)) }
    }

    fun info(user: UserHandle): ProfileInfo? = _profiles.value.firstOrNull { it.user == user }

    fun isPaused(user: UserHandle): Boolean = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)

    /**
     * Asks the system to pause or resume a profile. Returns false when the system needs the user's
     * credentials first (it then shows its own prompt) or policy forbids it.
     */
    fun setPaused(user: UserHandle, paused: Boolean): Boolean =
        runCatching { userManager.requestQuietModeEnabled(paused, user) }.getOrDefault(false).also { refresh() }

    private fun kindOf(u: UserHandle, me: UserHandle): ProfileKind {
        if (u == me) return ProfileKind.PERSONAL
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                val type = launcherApps.getLauncherUserInfo(u)?.userType
                return when (type) {
                    UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
                    UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
                    UserManager.USER_TYPE_PROFILE_CLONE -> ProfileKind.CLONE
                    else -> ProfileKind.OTHER
                }
            }
        }
        // Before Android 15: the system badges managed-profile labels ("Work …"); that's the supported signal.
        val badged = runCatching { context.packageManager.getUserBadgedLabel("x", u).toString() }.getOrDefault("x")
        return if (badged != "x") ProfileKind.WORK else ProfileKind.OTHER
    }

    companion object {
        private val ACTIONS = listOf(
            Intent.ACTION_MANAGED_PROFILE_ADDED, Intent.ACTION_MANAGED_PROFILE_REMOVED,
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE, Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_UNLOCKED,
            "android.intent.action.PROFILE_AVAILABLE", "android.intent.action.PROFILE_UNAVAILABLE",
            "android.intent.action.PROFILE_ACCESSIBLE", "android.intent.action.PROFILE_INACCESSIBLE",
            "android.intent.action.PROFILE_ADDED", "android.intent.action.PROFILE_REMOVED",
        )
    }
}
