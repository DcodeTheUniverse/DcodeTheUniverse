package com.axiom.launcher.ui

import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.Attachment
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.AxiomTestKit.idleFor
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.AxiomTestKit.installFakeProvider
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.VoicePhase
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.SettingsBackup
import com.axiom.launcher.platform.HomeWidget
import com.axiom.launcher.solis.BatchJob
import com.axiom.launcher.solis.BatchState
import com.axiom.launcher.solis.CardAction
import com.axiom.launcher.solis.ProactiveCard
import com.axiom.launcher.solis.coding.CodingJob
import com.axiom.launcher.solis.coding.GhFile
import com.axiom.launcher.solis.coding.JobState
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.effects.LocalStillFrame
import com.axiom.launcher.ui.effects.MatrixRain
import com.axiom.launcher.ui.effects.scanlines
import com.axiom.launcher.ui.theme.LauncherTheme
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens added in 2.2: widgets and suggestion cards on home, voice mode, screen questions, receipts, diffs, batches, lock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class Screenshot22Test {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()

    private fun vm(withSolis: Boolean = true): LauncherViewModel {
        if (withSolis) app.installFakeProvider()
        val vm = LauncherViewModel(app)
        val user = Process.myUserHandle()
        val apps = listOf("Phone", "Messages", "Spotify", "Maps", "Camera", "Calendar").map { n ->
            AppInfo("k$n", n, n, ComponentName("pkg.${n.lowercase()}", "Main"), user, false, AppCategory.TOOLS)
        }
        vm.debugSetApps(apps, listOf("kPhone", "kMessages", "kSpotify", "kMaps"))
        vm.finishOnboarding()
        if (withSolis) idleUntil("providers configured") { vm.providersConfigured }
        return vm
    }

    @Composable
    private fun Frame(mode: ThemeMode, content: @Composable () -> Unit) {
        LauncherTheme(mode) {
            val p = LocalPalette.current
            CompositionLocalProvider(LocalVoice provides { }, LocalStillFrame provides true) {
                Box(Modifier.fillMaxSize().background(p.bg).scanlines(mode == ThemeMode.NETRUNNER)) {
                    if (mode == ThemeMode.NETRUNNER) MatrixRain(Modifier.fillMaxSize(), intensity = 0.13f, color = p.fg)
                    content()
                }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("build/screens/$name.png") { content() }

    /** Stand-ins for real widgets (Robolectric can't run other apps' widgets): plain Android views in the widget slots. */
    private fun fakeWidgets() {
        app.core.widgets.debugView = { ctx, w ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 24, 48, 24)
                background = GradientDrawable().apply { setColor(if (w.id == 1) 0xFF1E3A5F.toInt() else 0xFF2E4A2E.toInt()); cornerRadius = 48f }
                fun line(t: String, size: Float, bold: Boolean = false) = addView(TextView(ctx).apply {
                    text = t; textSize = size; setTextColor(Color.WHITE); if (bold) typeface = Typeface.DEFAULT_BOLD
                })
                if (w.id == 1) { line("Lisbon", 14f); line("19° Sunny", 30f, true); line("H 22° · L 14° · rain 0%", 13f) }
                else { line("Today", 13f); line("Standup 9:30 · Dentist 16:40", 16f, true); line("2 more tomorrow", 13f) }
            }
        }
        app.core.widgets.debugPlace(HomeWidget(1, "weather.app/.Now", 110))
        app.core.widgets.debugPlace(HomeWidget(2, "calendar.app/.Agenda", 110))
    }

    @Test
    fun homeWithWidgetsAndSuggestions() {
        val vm = vm()
        fakeWidgets()
        idleUntil("widgets mirrored") { vm.widgets.size == 2 }
        vm.debugCards(listOf(
            ProactiveCard("leave:1", "Leave soon for Dentist", "Rua Augusta 10 · 16:40", "Directions", CardAction.Navigate("Rua Augusta 10")),
            ProactiveCard("headphones:1", "Headphones connected", "Pick up where your music left off?", "Play", CardAction.PlayMedia),
        ))
        idleFor(200)
        listOf(ThemeMode.VOID, ThemeMode.PAPER, ThemeMode.NETRUNNER).forEach { mode ->
            shot("home_widgets_${mode.id}") { Frame(mode) { HomePage(vm, {}, {}, {}, {}, {}, {}) } }
        }
        shot("widget_menu_void") { Frame(ThemeMode.VOID) { WidgetMenu(vm, 1) {} } }
        shot("home_menu_paper") { Frame(ThemeMode.PAPER) { HomeMenu(vm, {}, {}) {} } }
        shot("widget_picker_void") { Frame(ThemeMode.VOID) { WidgetPicker(vm) {} } }
    }

    @Test
    fun voiceModeAndScreenQuestions() {
        val vm = vm()
        vm.debugVoice(VoicePhase.LISTENING, "what's the weather like in Lisbon tomorrow")
        idleFor(150)
        shot("solis_voice_void") { Frame(ThemeMode.VOID) { SolisPage(vm, true, {}, {}) } }
        vm.debugVoice(VoicePhase.SPEAKING, "what's the weather like in Lisbon tomorrow")
        shot("solis_voice_netrunner") { Frame(ThemeMode.NETRUNNER) { SolisPage(vm, true, {}, {}) } }
        vm.debugVoice(VoicePhase.OFF, "")

        vm.pendingAttachment = Attachment("Screen", "/cache/shared/screen-1.jpg", listOf(ContentPart.Text("<screen_text>Gate B7 · boarding 10:40</screen_text>")))
        vm.savePrompt("Plan my day around my calendar")
        idleFor(150)
        shot("solis_screen_paper") { Frame(ThemeMode.PAPER) { SolisPage(vm, true, {}, {}) } }
    }

    @Test
    fun receiptsDiffsAndBatches() {
        val vm = vm()
        val id = (vm.solis.submit("Wake me at 6:30 tomorrow and remind me to pack my boarding pass") as SubmitResult.Accepted).threadId
        idleUntil("task done") { vm.threads.firstOrNull { it.id == id }?.status == ThreadStatus.DONE }
        val t = vm.threads.first { it.id == id }
        shot("receipts_void") { Frame(ThemeMode.VOID) { ReceiptsDialog(vm, t) {} } }

        val now = System.currentTimeMillis()
        vm.solis.coding.debugPut(CodingJob("octo/app", 9, "Fix crash when opening settings", "https://github.com/octo/app/issues/9", null,
            now - 3_600_000, now - 120_000, JobState.CHECKS_FAILED, "PR #10 · 1 of 3 checks failed (unit tests)",
            branch = "claude/issue-9-20261026-0912", pr = 10, prUrl = "https://github.com/octo/app/pull/10", checks = "1 of 3 checks failed (unit tests)"))
        idleUntil("job mirrored") { vm.codingJobs.isNotEmpty() }
        val files = listOf(
            GhFile("app/src/main/java/octo/Settings.kt", "modified", 6, 2, "@@ -40,7 +40,11 @@ class Settings {\n     fun open() {\n-        val prefs = load()!!\n+        val prefs = load() ?: return showError()\n+        // A missing file used to crash here.\n         render(prefs)\n     }\n+\n+    private fun showError() = toast(\"Settings unavailable\")"),
            GhFile("app/src/test/java/octo/SettingsTest.kt", "added", 14, 0, "@@ -0,0 +1,14 @@\n+class SettingsTest {\n+    @Test fun opensWithoutFile() {\n+        Settings().open()\n+    }\n+}"),
        )
        shot("diff_void") { Frame(ThemeMode.VOID) { DiffViewer(vm, "octo/app#9", load = { Result.success(files) }) {} } }
        shot("tasks_coding_fix_paper") { Frame(ThemeMode.PAPER) { TasksScreen(vm, {}, {}) } }

        vm.solis.debugPutBatch(BatchJob("b1", "Write a 2-page summary of my Q3 notes", "Write a 2-page summary of my Q3 notes", "sys", "user",
            "claude-sonnet-x", "Claude Sonnet", "msgbatch_01", now - 600_000, Space.PERSONAL, "personal", 0.04))
        vm.solis.debugPutBatch(BatchJob("b2", "Draft a reading list on tides", "Draft a reading list on tides", "sys", "user",
            "claude-sonnet-x", "Claude Sonnet", "msgbatch_02", now - 7_200_000, Space.PERSONAL, "personal", 0.03, BatchState.DONE, threadId = id, costUsd = 0.011))
        idleUntil("batches mirrored") { vm.batchJobs.size == 2 }
        shot("tasks_batches_void") { Frame(ThemeMode.VOID) { TasksScreen(vm, {}, {}) } }
    }

    @Test
    fun privacyLockAndBackup() {
        val vm = vm()
        shot("locked_void") { Frame(ThemeMode.VOID) { LockedPanel(vm, "Solis") } }
        shot("locked_netrunner") { Frame(ThemeMode.NETRUNNER) { LockedPanel(vm, "Solis") } }
        vm.settingsSection = "privacy"
        shot("settings_privacy_paper") { Frame(ThemeMode.PAPER) { SettingsScreen(vm) {} } }
        vm.settingsSection = null
        val prefs = com.axiom.launcher.data.Prefs(app).also {
            it.dailyCapUsd = 2f; it.theme = "paper"; it.userName = "Dee"; it.favorites = listOf("kPhone", "kMaps"); it.showIcons = true
            it.notes = listOf(com.axiom.launcher.data.Note(1, "Pick up film", 10))
        }
        vm.importPreview = SettingsBackup.parse(SettingsBackup.export(prefs, "2.2.0"))
        shot("import_void") { Frame(ThemeMode.VOID) { ImportDialog(vm, vm.importPreview!!) } }
    }
}
