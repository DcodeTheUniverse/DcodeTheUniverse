package com.dcode.vault.data

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.dcode.circle.protocol.RequestKind
import com.dcode.crypto.Argon2Params
import com.dcode.crypto.Bytes
import com.dcode.crypto.CryptoException
import com.dcode.vault.core.CirclePairing
import com.dcode.vault.core.DeviceSlot
import com.dcode.vault.core.DeviceSlotMode
import com.dcode.vault.core.KeySlots
import com.dcode.vault.core.VaultHeaderBody
import com.dcode.vault.core.VaultSession
import com.dcode.vault.core.VaultStore
import com.dcode.vault.security.CircleClient
import com.dcode.vault.security.VaultKeystore
import com.dcode.vault.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher

sealed interface VaultState {
    data object Loading : VaultState
    data object NoVault : VaultState
    data object Locked : VaultState
    class Unlocked(val session: VaultSession) : VaultState
}

class VaultPrefs(context: Context) {
    private val sp = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    private fun bump() { _changes.value++ }

    /** Seconds in the background before locking; 0 = immediately. */
    var autoLockSeconds: Int
        get() = sp.getInt("autoLock", 30)
        set(v) { sp.edit().putInt("autoLock", v).apply(); bump() }
    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System)
        set(v) { sp.edit().putString("theme", v.name).apply(); bump() }
    var sortByModified: Boolean
        get() = sp.getBoolean("sortModified", false)
        set(v) { sp.edit().putBoolean("sortModified", v).apply(); bump() }
    var monospaceEditor: Boolean
        get() = sp.getBoolean("mono", false)
        set(v) { sp.edit().putBoolean("mono", v).apply(); bump() }
    var clipboardClearSeconds: Int
        get() = sp.getInt("clipClear", 30)
        set(v) { sp.edit().putInt("clipClear", v).apply(); bump() }
}

/**
 * Owns the vault lifecycle. The DEK only ever exists inside an open [VaultSession]; locking closes the session,
 * which wipes the keys and drops all decrypted data from memory.
 */
class VaultManager(private val context: Context) {
    val store = VaultStore(File(context.noBackupFilesDir, "vault"))
    val keystore = VaultKeystore(context)
    val circle = CircleClient(context)
    val prefs = VaultPrefs(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<VaultState>(VaultState.Loading)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /**
     * Set while Vault deliberately hands focus to Circle ID or a file picker, so auto-lock doesn't fire mid-flow.
     * The hold expires after [HOLD_MAX_MS] so abandoning that flow can't leave the vault open indefinitely.
     */
    @Volatile
    var holdLock = false
        set(value) {
            field = value
            holdSince = SystemClock.elapsedRealtime()
        }
    private var holdSince = 0L
    private val holdActive: Boolean get() = holdLock && SystemClock.elapsedRealtime() - holdSince < HOLD_MAX_MS
    private var lockJob: kotlinx.coroutines.Job? = null

    /** The in-flight Circle ID request (with its one-time ECIES key). Process-scoped so it survives activity recreation. */
    @Volatile
    var pendingCircle: CircleClient.Pending? = null

    private var backgroundedAt = 0L

    /** Run synchronously just before the keys are wiped (e.g. the editor flushing unsaved text). */
    val lockHooks = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    init {
        _state.value = if (store.exists()) VaultState.Locked else VaultState.NoVault
    }

    val session: VaultSession? get() = (state.value as? VaultState.Unlocked)?.session

    fun header(): VaultHeaderBody? = runCatching { store.readHeaderBody() }.getOrNull()

    // ------------------------------------------------------------------ lifecycle

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        session?.let { s -> scope.launch { runCatching { s.flushIndex() } } }
        val limit = prefs.autoLockSeconds
        if (!holdActive && limit == 0) {
            lock()
            return
        }
        // Lock on a timer too, so the key doesn't sit in memory for the whole time Vault is in the background.
        lockJob?.cancel()
        lockJob = scope.launch {
            kotlinx.coroutines.delay(if (holdActive) HOLD_MAX_MS else limit * 1000L)
            kotlinx.coroutines.withContext(Dispatchers.Main) { holdLock = false; lock() }
        }
    }

    fun onForeground() {
        lockJob?.cancel()
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        val limit = prefs.autoLockSeconds
        if (!holdActive && backgroundedAt > 0 && away > limit * 1000L) lock()
        backgroundedAt = 0
    }

    fun lock() {
        val s = session ?: return
        lockHooks.forEach { runCatching { it() } }
        _state.value = VaultState.Locked
        scope.launch { s.close() }
    }

    private fun opened(dek: ByteArray) {
        val session = store.open(dek)
        Bytes.wipe(dek)
        _state.value = VaultState.Unlocked(session)
        scope.launch { runCatching { session.repairIndex() } }
    }

    // ------------------------------------------------------------------ setup

    suspend fun create(passphrase: CharArray): Unit = withContext(Dispatchers.Default) {
        val dek = store.create(passphrase, ARGON)
        Bytes.wipe(passphrase)
        opened(dek)
    }

    suspend fun restore(input: InputStream) = withContext(Dispatchers.IO) {
        store.importBackup(input)
        _state.value = VaultState.Locked
    }

    // ------------------------------------------------------------------ unlock paths

    /** @return null on success, otherwise a message. */
    suspend fun unlockWithPassphrase(passphrase: CharArray): String? = withContext(Dispatchers.Default) {
        try {
            val dek = store.unlockWithPassphrase(passphrase)
            opened(dek)
            null
        } catch (e: IllegalStateException) {
            "Too many attempts. Try again in ${store.passphraseLockoutRemainingMs() / 1000 + 1}s."
        } catch (e: CryptoException) {
            if (e is com.dcode.vault.core.VaultTamperedException) e.message else "Wrong passphrase"
        } finally {
            Bytes.wipe(passphrase)
        }
    }

    fun biometricUnlockCipher(): Cipher? {
        val slot = header()?.device?.takeIf { it.mode == DeviceSlotMode.BIOMETRIC } ?: return null
        return try {
            keystore.biometricDecryptCipher(Bytes.unb64(slot.iv))
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun completeBiometricUnlock(cipher: Cipher): String? = withContext(Dispatchers.Default) {
        try {
            val slot = header()?.device ?: return@withContext "No fingerprint slot"
            val dek = cipher.doFinal(Bytes.unb64(slot.wrapped))
            store.verifyHeader(dek)
            opened(dek)
            null
        } catch (e: Exception) {
            "Fingerprint unlock failed: ${e.message}"
        }
    }

    fun newCircleRequest(kind: RequestKind): CircleClient.Pending =
        circle.newRequest(kind, if (kind == RequestKind.PAIR) "Pair Vault so it can only be unlocked by you" else "Unlock your Vault")

    suspend fun completeCircleUnlock(p: CircleClient.Pending, response: ByteArray?): String? = withContext(Dispatchers.Default) {
        val header = header() ?: return@withContext "Vault header unreadable"
        val pairing = header.circle ?: return@withContext "Vault is not paired with Circle ID"
        val slot = header.device?.takeIf { it.mode == DeviceSlotMode.CIRCLE } ?: return@withContext "No Circle ID key slot"
        when (val r = circle.complete(p, response, Bytes.unb64(pairing.identityPublicKey), pairing.circleCertSha256)) {
            is CircleClient.Result.Denied -> r.message
            is CircleClient.Result.Released -> try {
                val inner = keystore.decryptDevice(slot.keyAlias, Bytes.unb64(slot.iv), Bytes.unb64(slot.wrapped))
                val dek = KeySlots.circleInnerUnwrap(inner, r.secret, header.vaultId)
                Bytes.wipe(inner, r.secret)
                store.verifyHeader(dek)
                opened(dek)
                null
            } catch (e: Exception) {
                Bytes.wipe(r.secret)
                "Circle ID verified you, but this device's key slot couldn't be opened (${e.javaClass.simpleName}). Use your passphrase, then re-pair."
            }
        }
    }

    // ------------------------------------------------------------------ slot management (requires an unlocked vault)

    suspend fun completeCirclePairing(p: CircleClient.Pending, response: ByteArray?): String? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext "Vault is locked"
        when (val r = circle.complete(p, response, pinnedKey = null, pinnedCert = null)) {
            is CircleClient.Result.Denied -> r.message
            is CircleClient.Result.Released -> {
                val dek = s.dekCopy()
                try {
                    val body = store.readHeaderBody()
                    val inner = KeySlots.circleInnerWrap(dek, r.secret, body.vaultId)
                    val oldAlias = store.readHeaderBody().device?.keyAlias
                    val (alias, iv, wrapped) = keystore.encryptDevice(inner)
                    Bytes.wipe(inner, r.secret)
                    store.updateHeader(dek) {
                        it.copy(
                            device = DeviceSlot(DeviceSlotMode.CIRCLE, alias, Bytes.b64(iv), Bytes.b64(wrapped)),
                            circle = CirclePairing(r.assertion.circleId, r.assertion.identityPublicKey, r.circleCert, System.currentTimeMillis()),
                        )
                    }
                    // Only now that the new slot is safely on disk is the previous key destroyed.
                    if (oldAlias != null && oldAlias != alias) keystore.delete(oldAlias)
                    keystore.delete(VaultKeystore.BIO_ALIAS)
                    null
                } catch (e: Exception) {
                    "Pairing failed: ${e.message}"
                } finally {
                    Bytes.wipe(dek)
                }
            }
        }
    }

    fun biometricSetupCipher(): Cipher = keystore.biometricEncryptCipher()

    suspend fun completeBiometricSetup(cipher: Cipher): String? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext "Vault is locked"
        val dek = s.dekCopy()
        try {
            val wrapped = cipher.doFinal(dek)
            store.updateHeader(dek) {
                it.copy(device = DeviceSlot(DeviceSlotMode.BIOMETRIC, VaultKeystore.BIO_ALIAS, Bytes.b64(cipher.iv), Bytes.b64(wrapped)), circle = null)
            }
            keystore.wipeCircleKeys()
            null
        } catch (e: Exception) {
            "Couldn't enable fingerprint unlock: ${e.message}"
        } finally {
            Bytes.wipe(dek)
        }
    }

    suspend fun removeDeviceSlot() = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext
        val dek = s.dekCopy()
        try {
            store.updateHeader(dek) { it.copy(device = null, circle = null) }
            keystore.wipe()
        } finally {
            Bytes.wipe(dek)
        }
    }

    suspend fun changePassphrase(current: CharArray, next: CharArray): String? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext "Vault is locked"
        try {
            val check = store.unlockWithPassphrase(current)
            Bytes.wipe(check)
            val dek = s.dekCopy()
            store.changePassphrase(dek, next, ARGON)
            Bytes.wipe(dek)
            null
        } catch (e: CryptoException) {
            "Current passphrase is wrong"
        } catch (e: IllegalStateException) {
            "Too many attempts — wait a moment"
        } finally {
            Bytes.wipe(current); Bytes.wipe(next)
        }
    }

    suspend fun exportBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        session?.flush()
        out.use { store.exportBackup(it) }
    }

    fun wipe() {
        session?.close()
        store.destroy()
        keystore.wipe()
        _state.value = VaultState.NoVault
    }

    /** Lock state the device slot is in, for the lock screen and settings. */
    fun unlockMode(): DeviceSlotMode? = header()?.device?.mode

    companion object {
        /** 64 MiB, 3 passes: ~1–2 s on a modern phone, prohibitively expensive to brute-force on GPUs. */
        val ARGON = Argon2Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 2)
        const val HOLD_MAX_MS = 5 * 60_000L
    }
}
