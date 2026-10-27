package com.axiom.launcher.solis

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.axiom.launcher.LauncherCore
import com.axiom.launcher.LockService
import com.axiom.launcher.data.NotificationWatcher
import com.axiom.launcher.platform.CrashLog
import com.axiom.launcher.platform.PerfMonitor
import com.axiom.launcher.platform.Recognizers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class CheckStatus { OK, WARN, FAIL, INFO }

/** Where a failed or optional check can be fixed. */
enum class CheckFix { DEFAULT_HOME, ASSISTANT, PROVIDERS, CODING, POST_NOTIFICATIONS, NOTIFICATION_ACCESS, CONTACTS, CALENDAR, FILES, ACCESSIBILITY, MICROPHONE, BATTERY, SHARE_REPORT }

data class SelfCheck(val area: String, val status: CheckStatus, val text: String, val fix: CheckFix? = null)

/**
 * Checks everything Axiom depends on, on this phone, right now: the launcher role, storage, each AI
 * provider (by listing its models, which costs nothing), GitHub, permissions, background restrictions and
 * the last crash. The report it can export never contains keys, memories, conversations, notifications
 * or contacts.
 */
class SelfTest(private val app: Application, private val core: LauncherCore, private val host: SolisHost) {

    suspend fun run(): List<SelfCheck> = withContext(Dispatchers.IO) {
        val out = ArrayList<SelfCheck>()
        fun add(area: String, status: CheckStatus, text: String, fix: CheckFix? = null) { out += SelfCheck(area, status, text, fix) }
        fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val d = host.device()

        guard(out, "Launcher") {
            val roles = app.getSystemService(RoleManager::class.java)
            if (roles?.isRoleHeld(RoleManager.ROLE_HOME) == true) add("Launcher", CheckStatus.OK, "Axiom is the default home app")
            else add("Launcher", CheckStatus.WARN, "Axiom isn't the default home app, so Home opens another launcher", CheckFix.DEFAULT_HOME)
            if (roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) {
                add("Launcher", CheckStatus.OK, "Axiom is the digital assistant: hold Home to ask about the screen")
                // Android routes other apps' speech recognition through the assistant, which hands it on.
                if (!granted(Manifest.permission.RECORD_AUDIO))
                    add("Launcher", CheckStatus.WARN, "Allow the microphone: as the assistant, Axiom passes other apps' voice input to the phone's recognizer", CheckFix.MICROPHONE)
                val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)
                if (!onDevice && Recognizers.other(app) == null)
                    add("Launcher", CheckStatus.WARN, "No speech recognizer is installed, so voice input in other apps won't work while Axiom is the assistant")
            } else add("Launcher", CheckStatus.INFO, "Not the digital assistant: holding Home won't share the screen with Solis", CheckFix.ASSISTANT)
        }

        guard(out, "Storage") {
            if (host.store.persistent) add("Storage", CheckStatus.OK, "Memory and tasks are saved on the phone")
            else add("Storage", CheckStatus.FAIL, "Using temporary storage: ${host.store.openError ?: "the database couldn't open"}. Nothing will survive a restart.")
        }

        guard(out, "AI") {
            val configured = host.registry.all().filter { it.isConfigured() }
            if (configured.isEmpty()) add("AI", CheckStatus.WARN, "No AI provider is set up; Solis can only run device commands", CheckFix.PROVIDERS)
            for (p in configured) {
                if (!p.isLocal && !d.online) { add("AI", CheckStatus.INFO, "${p.displayName}: not checked (offline)"); continue }
                runCatching { host.registry.refresh(p.id) }
                    .onSuccess { add("AI", CheckStatus.OK, "${p.displayName}: connected, ${it.models.size} models") }
                    .onFailure { add("AI", CheckStatus.FAIL, "${p.displayName}: ${it.message ?: it.javaClass.simpleName}", CheckFix.PROVIDERS) }
            }
            if (configured.any { it.id == SolisHost.OPENROUTER } && d.online) {
                host.openRouterKeyStatus()?.let { k ->
                    val left = k.limitRemaining ?: k.budgetRemaining
                    add("AI", if (left != null && left < 0.5) CheckStatus.WARN else CheckStatus.OK,
                        "OpenRouter key: today $" + "%.2f".format(k.usageDaily) + ", this month $" + "%.2f".format(k.usageMonthly) +
                            (left?.let { " · $" + "%.2f".format(it) + " left" } ?: ""))
                }
            }
            host.lastError.value?.let { add("AI", CheckStatus.WARN, "Last background error: ${it.take(160)}") }
        }

        guard(out, "Coding") {
            if (host.githubConnected) {
                if (!d.online) add("Coding", CheckStatus.INFO, "GitHub: not checked (offline)")
                else host.coding.checkSetup(null).forEach { line ->
                    val status = when { line.startsWith("✓") -> CheckStatus.OK; line.startsWith("✗") -> CheckStatus.FAIL; else -> CheckStatus.INFO }
                    add("Coding", status, line.removePrefix("✓ ").removePrefix("✗ ").removePrefix("• "), if (status == CheckStatus.FAIL) CheckFix.CODING else null)
                }
            } else add("Coding", CheckStatus.INFO, "GitHub isn't connected (only needed for Claude Code hand-offs)", CheckFix.CODING)
        }

        guard(out, "Permissions") {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                add("Permissions", CheckStatus.WARN, "Notifications are off: background task progress and coding updates can't be shown", CheckFix.POST_NOTIFICATIONS)
            if (NotificationWatcher.enabled(app)) add("Permissions", CheckStatus.OK, "Notification access (digest and app badges)")
            else add("Permissions", CheckStatus.INFO, "No notification access: no digest or app badges", CheckFix.NOTIFICATION_ACCESS)
            if (!granted(Manifest.permission.READ_CONTACTS)) add("Permissions", CheckStatus.INFO, "Contacts not allowed: \"call mom\" and texting by name won't work", CheckFix.CONTACTS)
            if (!granted(Manifest.permission.READ_CALENDAR)) add("Permissions", CheckStatus.INFO, "Calendar not allowed: no next event or agenda", CheckFix.CALENDAR)
            if (!runCatching { Environment.isExternalStorageManager() }.getOrDefault(false))
                add("Permissions", CheckStatus.INFO, "All-files access is off: file search covers media only", CheckFix.FILES)
            if (!granted(Manifest.permission.RECORD_AUDIO)) add("Permissions", CheckStatus.INFO, "Microphone not allowed: voice mode can't listen", CheckFix.MICROPHONE)
            if (!LockService.enabled()) add("Permissions", CheckStatus.INFO, "Accessibility service off: no double-tap lock or screen tile", CheckFix.ACCESSIBILITY)
        }

        guard(out, "System") {
            val am = app.getSystemService(ActivityManager::class.java)
            if (am?.isBackgroundRestricted == true) add("System", CheckStatus.WARN, "Android restricts Axiom in the background, so long tasks may stop", CheckFix.BATTERY)
            add("System", CheckStatus.INFO, if (!d.online) "Offline" else if (d.metered) "Online (metered)" else "Online")
            CrashLog.latestSummary(app)?.let { add("System", CheckStatus.WARN, "Last crash: $it", CheckFix.SHARE_REPORT) }
                ?: add("System", CheckStatus.OK, "No crashes recorded")
        }
        out
    }

    /** One area's checks; if something in it throws, the area reports that instead of ending the whole self-test. */
    private inline fun guard(out: MutableList<SelfCheck>, area: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            out += SelfCheck(area, CheckStatus.WARN, "Couldn't finish this check: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** A plain-text report for sharing. Contains no keys, memories, conversations, notifications or contacts. */
    suspend fun report(checks: List<SelfCheck>): String = withContext(Dispatchers.IO) {
        val p = core.prefs
        buildString {
            append("Axiom diagnostics · ").append(CrashLog.stamp(System.currentTimeMillis())).append('\n')
            append("app ").append(CrashLog.appVersion(app)).append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ")
                .append(Build.VERSION.SDK_INT).append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("\n== Self-test ==\n")
            checks.forEach { append(mark(it.status)).append(' ').append(it.area).append(": ").append(it.text).append('\n') }
            append("\n== Performance ==\n").append(PerfMonitor.report(app)).append('\n')
            append("\n== Solis ==\n").append(host.diagnostics()).append('\n')
            append("\n== Settings (no keys) ==\n")
            append("theme ").append(p.theme).append(" · mode ").append(p.activeMode).append(" · engine ").append(p.engine)
                .append(" · offline ").append(p.offlineMode).append(" · web ").append(p.webSearch).append('\n')
            append("caps day $").append(p.dailyCapUsd).append(" month $").append(p.monthlyCapUsd).append(" task $").append(p.threadCapUsd)
                .append(" narrator $").append(p.narratorCapUsd).append(" · unknown prices ").append(p.allowUnknownPrice).append('\n')
            append("concurrency ").append(p.maxConcurrent).append(" · auto-resume ").append(p.autoResume)
                .append(" · pinned models ").append(p.pinnedModels.keys.joinToString().ifEmpty { "none" }).append('\n')
            append("local runtime ").append(if (p.localBaseUrl.isBlank()) "none" else "set").append(" · coding ")
                .append(if (host.githubConnected) "connected" else "off").append('\n')
            append("\n== Crashes ==\n")
            val crashes = CrashLog.recent(app)
            if (crashes.isEmpty()) append("none\n") else crashes.take(3).forEach { f ->
                append("--- ").append(f.name).append('\n').append(runCatching { f.readText().take(12_000) }.getOrDefault("(unreadable)")).append('\n')
            }
            append("\n== Process exits (Android) ==\n").append(CrashLog.exitReasons(app)).append('\n')
        }
    }

    /** Writes [text] where the share sheet can read it and returns the file. */
    fun saveReport(text: String): File {
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val name = "axiom-diagnostics-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt"
        return File(dir, name).apply { writeText(text) }
    }

    companion object {
        fun mark(s: CheckStatus) = when (s) { CheckStatus.OK -> "✓"; CheckStatus.WARN -> "!"; CheckStatus.FAIL -> "✗"; CheckStatus.INFO -> "•" }
    }
}
