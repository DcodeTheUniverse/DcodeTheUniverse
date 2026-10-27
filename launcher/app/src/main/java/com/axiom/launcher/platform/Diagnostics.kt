package com.axiom.launcher.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes are written to the app's private storage before Android tears the process down, so the next
 * launch can show them and the user can choose to share them. Nothing is uploaded anywhere.
 */
object CrashLog {
    private const val DIR = "crashes"
    private const val KEEP = 5

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun write(context: Context, thread: String, e: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        File(dir, "crash-$now.txt").writeText(buildString {
            append("time: ").append(stamp(now)).append('\n')
            append("thread: ").append(thread).append('\n')
            append("app: ").append(appVersion(context)).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(e.stackTraceToString().take(32_000))
        })
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    fun recent(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()

    /** First line of the newest crash ("time … · Exception: message"), or null. */
    fun latestSummary(context: Context): String? {
        val f = recent(context).firstOrNull() ?: return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        val time = lines.firstOrNull()?.removePrefix("time: ")
        val error = lines.drop(4).firstOrNull()?.take(160)
        return "$time · $error"
    }

    fun clear(context: Context) { recent(context).forEach { it.delete() } }

    /**
     * Why the last processes ended, from Android itself (Android 11+): crashes, ANRs with their thread
     * dump, low-memory kills. The most useful thing to have when something froze.
     */
    fun exitReasons(context: Context, max: Int = 5): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return "unavailable"
        val list = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, max) }.getOrNull() ?: return "unavailable"
        if (list.isEmpty()) return "none recorded"
        return list.joinToString("\n\n") { info ->
            buildString {
                append(stamp(info.timestamp)).append(" · ").append(reason(info.reason))
                info.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(200)) }
                append(" · importance ").append(info.importance).append(" · pss ").append(info.pss / 1024).append(" MB")
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { info.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(80).joinToString("\n") } }
                        .getOrNull()?.let { append("\n").append(it) }
                }
            }
        }
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $r"
    }

    fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("?")

    fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}
