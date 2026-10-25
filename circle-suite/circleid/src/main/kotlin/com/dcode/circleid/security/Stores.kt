package com.dcode.circleid.security

import android.content.Context
import android.content.pm.PackageManager
import com.dcode.circle.core.FaceStrictness
import com.dcode.circleid.ui.theme.ThemeMode
import com.dcode.crypto.Bytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val at: Long,
    val requester: String,
    val kind: String,
    val status: String,
    val assurance: Int,
    val factors: List<String>,
    val message: String = "",
)

/** Verification history, encrypted with a device-bound Keystore key so it can be written even on a failed attempt. */
class HistoryStore(context: Context, private val keystore: CircleKeystore) {
    private val file = File(context.noBackupFilesDir, "circle/history.bin")
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun load() {
        _entries.value = try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(ListSerializer(HistoryEntry.serializer()), keystore.logDecrypt(file.readBytes()).decodeToString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Synchronized
    fun add(e: HistoryEntry) {
        val list = (listOf(e) + _entries.value).take(200)
        _entries.value = list
        // Keystore encryption and disk I/O stay off the UI thread; a single writer keeps saves ordered.
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(keystore.logEncrypt(json.encodeToString(ListSerializer(HistoryEntry.serializer()), list).toByteArray()))
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }
}

/** Non-secret preferences and public metadata (what the home screen can show without unlocking). */
class CirclePrefs(context: Context) {
    private val sp = context.getSharedPreferences("circle", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun bump() { _changes.value++ }

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System)
        set(v) { sp.edit().putString("theme", v.name).apply(); bump() }

    var strictness: FaceStrictness
        get() = runCatching { FaceStrictness.valueOf(sp.getString("strictness", null) ?: "Strict") }.getOrDefault(FaceStrictness.Strict)
        set(v) { sp.edit().putString("strictness", v.name).apply(); bump() }

    var challengeCount: Int
        get() = sp.getInt("challenges", 2).coerceIn(1, 4)
        set(v) { sp.edit().putInt("challenges", v).apply(); bump() }

    var requirePulse: Boolean
        get() = sp.getBoolean("requirePulse", false)
        set(v) { sp.edit().putBoolean("requirePulse", v).apply(); bump() }

    var requireVascular: Boolean
        get() = sp.getBoolean("requireVascular", false)
        set(v) { sp.edit().putBoolean("requireVascular", v).apply(); bump() }

    var vascularEnrolled: Boolean
        get() = sp.getBoolean("vascularEnrolled", false)
        set(v) { sp.edit().putBoolean("vascularEnrolled", v).apply(); bump() }

    var circleId: String?
        get() = sp.getString("circleId", null)
        set(v) { sp.edit().putString("circleId", v).apply(); bump() }

    var enrolledAt: Long
        get() = sp.getLong("enrolledAt", 0)
        set(v) { sp.edit().putLong("enrolledAt", v).apply(); bump() }

    var pairedApps: List<PairedApp>
        get() = runCatching { Json.decodeFromString(ListSerializer(PairedApp.serializer()), sp.getString("paired", "[]")!!) }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("paired", Json.encodeToString(ListSerializer(PairedApp.serializer()), v)).apply(); bump() }

    fun clear() {
        sp.edit().clear().apply()
        bump()
    }
}

@Serializable
data class PairedApp(val packageName: String, val name: String, val pairedAt: Long, val lastUsed: Long = 0)

object CallerIdentity {
    /** SHA-256 of the caller's current APK signing certificate(s), hex, sorted and joined if several. */
    fun certSha256(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        signers.map { Bytes.hex(Bytes.sha256(it.toByteArray())) }.sorted().joinToString(":")
    } catch (_: Exception) {
        null
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }
}
