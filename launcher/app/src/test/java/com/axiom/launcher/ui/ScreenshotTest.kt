package com.axiom.launcher.ui

import android.content.ComponentName
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.AxiomTestKit.idleFor
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.AxiomTestKit.installFakeProvider
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.R
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.platform.ProfileKind
import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.effects.LocalStillFrame
import com.axiom.launcher.ui.effects.MatrixRain
import com.axiom.launcher.ui.effects.scanlines
import com.axiom.launcher.ui.solis.AxiomWordmark
import com.axiom.launcher.ui.solis.SolisFace
import com.axiom.launcher.ui.solis.caption
import com.axiom.launcher.ui.theme.LauncherTheme
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.axiom.launcher.ui.theme.lStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the launcher's screens in every theme to PNGs under build/screens for visual review. The Solis
 * screens are driven by the real app wiring: tasks run through the engine with a scripted local provider,
 * so transcripts, route explanations, task cards and the face show genuine engine state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()

    private fun vm(withSolis: Boolean = true): LauncherViewModel {
        if (withSolis) app.installFakeProvider()
        val vm = LauncherViewModel(app)
        val user = Process.myUserHandle()
        val names = listOf(
            "Phone" to AppCategory.TOOLS, "Messages" to AppCategory.SOCIAL, "Spotify" to AppCategory.MEDIA,
            "Camera" to AppCategory.MEDIA, "Maps" to AppCategory.TRAVEL, "Chrome" to AppCategory.TOOLS,
            "Gmail" to AppCategory.WORK, "Instagram" to AppCategory.SOCIAL, "Calendar" to AppCategory.WORK,
            "Clock" to AppCategory.TOOLS, "Notion" to AppCategory.WORK, "Signal" to AppCategory.SOCIAL,
            "YouTube" to AppCategory.MEDIA, "Uber" to AppCategory.TRAVEL, "Authenticator" to AppCategory.TOOLS,
            "Duolingo" to AppCategory.OTHER, "Photos" to AppCategory.MEDIA,
        )
        val apps = names.map { (n, c) -> AppInfo("k$n", n, n, ComponentName("pkg.${n.lowercase()}", "Main"), user, false, c) } +
            AppInfo("kSlack", "Slack", "Slack", ComponentName("com.slack", "Main"), user, true, AppCategory.WORK, profile = ProfileKind.WORK)
        vm.debugSetApps(apps.sortedBy { it.label }, listOf("kPhone", "kMessages", "kSpotify", "kMaps"))
        if (withSolis) idleUntil("providers configured") { vm.providersConfigured }
        return vm
    }

    /** Starts one finished and one still-running task, like a real afternoon. */
    private fun seedTasks(vm: LauncherViewModel): String {
        val done = (vm.solis.submit("Wake me at 6:30 tomorrow and remind me to pack my boarding pass") as SubmitResult.Accepted).threadId
        idleUntil("demo task done") { vm.threads.firstOrNull { it.id == done }?.status == ThreadStatus.DONE }
        val running = (vm.solis.submit("Plan a 3-day food trip to Lisbon for October") as SubmitResult.Accepted).threadId
        idleUntil("second task running") { vm.threads.firstOrNull { it.id == running }?.status == ThreadStatus.RUNNING && vm.live[running] != null }
        return done
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

    @Test
    fun home() {
        val vm = vm()
        vm.updateName("Dee")
        vm.finishOnboarding()
        vm.addNote("Pick up film from the lab")
        seedTasks(vm)
        idleFor(200)
        ThemeMode.entries.forEach { mode -> shot("home_${mode.id}") { Frame(mode) { HomePage(vm, {}, {}, {}, {}, {}, {}) } } }
    }

    @Test
    fun homeWorkMode() {
        val vm = vm()
        vm.finishOnboarding()
        vm.switchMode("work")
        idleUntil("work mode") { vm.mode.id == "work" }
        assertEquals(Space.WORK, vm.mode.space)
        shot("home_work_void") { Frame(ThemeMode.VOID) { HomePage(vm, {}, {}, {}, {}, {}, {}) } }
    }

    @Test
    fun solis() {
        val vm = vm()
        val done = seedTasks(vm)
        vm.selectedThread = done
        idleFor(200)
        ThemeMode.entries.forEach { mode -> shot("solis_${mode.id}") { Frame(mode) { SolisPage(vm, true, {}, {}) } } }
        vm.selectedThread = vm.threads.first { it.status == ThreadStatus.RUNNING }.id
        shot("solis_running_void") { Frame(ThemeMode.VOID) { SolisPage(vm, true, {}, {}) } }
    }

    @Test
    fun solisSetup() {
        val vm = vm(withSolis = false)
        idleFor(300)
        assertTrue(!vm.providersConfigured)
        shot("solis_setup_void") { Frame(ThemeMode.VOID) { SolisPage(vm, true, {}, {}) } }
    }

    @Test
    fun tasksAndMemory() {
        val vm = vm()
        seedTasks(vm)
        val mem = vm.solis.memory
        mem.remember("Prefers window seats and early flights", MemoryKind.PREFERENCE, Scope(Space.PERSONAL), "you", 0.95)
        mem.remember("Team standup is at 9:30 on weekdays", MemoryKind.FACT, Scope(Space.WORK), "you", 0.9)
        idleFor(300)
        listOf(ThemeMode.VOID, ThemeMode.NETRUNNER).forEach { mode ->
            shot("tasks_${mode.id}") { Frame(mode) { TasksScreen(vm, {}, {}) } }
        }
        listOf(ThemeMode.VOID, ThemeMode.PAPER).forEach { mode ->
            shot("memory_${mode.id}") { Frame(mode) { MemoryScreen(vm) {} } }
        }
    }

    @Test
    fun settings() {
        val vm = vm()
        shot("settings_void") { Frame(ThemeMode.VOID) { SettingsScreen(vm) {} } }
        listOf("providers", "cortex", "budget", "privacy", "modes").forEach { section ->
            vm.settingsSection = section
            shot("settings_${section}_void") { Frame(ThemeMode.VOID) { SettingsScreen(vm) {} } }
        }
        vm.settingsSection = null
        shot("settings_netrunner") { Frame(ThemeMode.NETRUNNER) { SettingsScreen(vm) {} } }
    }

    @Test
    fun search() {
        val vm = vm()
        ThemeMode.entries.forEach { mode ->
            vm.query = "timer 10 min"
            idleFor(300)
            shot("search_${mode.id}") { Frame(mode) { SearchOverlay(vm, {}, {}) } }
        }
        vm.query = ">"
        idleFor(300)
        assertTrue("palette lists commands", vm.results.any { it is com.axiom.launcher.Hit.Palette })
        shot("search_palette_void") { Frame(ThemeMode.VOID) { SearchOverlay(vm, {}, {}) } }
        vm.query = "wake me at 6:30 and pack"
        idleFor(300)
        assertTrue("compound request goes to Solis first", vm.results.first() is com.axiom.launcher.Hit.Ask)
    }

    @Test
    fun drawer() {
        val vm = vm()
        vm.updateAutoKeyboard(false)
        listOf(ThemeMode.VOID, ThemeMode.PAPER).forEach { mode -> shot("drawer_${mode.id}") { Frame(mode) { Drawer(vm, {}, {}) } } }
    }

    @Test
    fun files() = ThemeMode.entries.forEach { mode ->
        val vm = vm(withSolis = false)
        shot("files_${mode.id}") { Frame(mode) { FilesPage(vm) } }
    }

    @Test
    fun onboarding() {
        val vm = vm(withSolis = false)
        listOf(ThemeMode.VOID, ThemeMode.NETRUNNER).forEach { mode -> shot("onboarding_${mode.id}") { Frame(mode) { Onboarding(vm) } } }
    }

    /** Every face state, still frames (as with reduced motion), with their captions. */
    @Test
    fun faces() = ThemeMode.entries.forEach { mode ->
        shot("faces_${mode.id}") {
            Frame(mode) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    AxiomWordmark()
                    Spacer(Modifier.height(8.dp))
                    Mood.entries.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SolisFace(m, size = 88.dp)
                            Text("   ${m.caption()}", style = lStyle(18.sp))
                        }
                    }
                }
            }
        }
    }

    /** The 3D head up close, in every state, to check shading, depth and glow. */
    @Test
    fun facesLarge() = listOf(ThemeMode.VOID, ThemeMode.PAPER, ThemeMode.NETRUNNER).forEach { mode ->
        shot("faces_large_${mode.id}") {
            Frame(mode) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Mood.IDLE to Mood.THINKING, Mood.WORKING to Mood.LISTENING, Mood.PAUSED to Mood.ERROR).forEach { (a, b) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(a, b).forEach { m ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    SolisFace(m, size = 170.dp)
                                    Text(m.caption(), style = lStyle(14.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Memory as a brain: hemispheres, task clusters, links, and memories Solis just recalled ringed (still mode). */
    @Test
    fun brain() {
        val vm = vm()
        val done = seedTasks(vm)
        val mem = vm.solis.memory
        listOf(
            "Prefers window seats and early flights" to MemoryKind.PREFERENCE,
            "Lisbon trip: flights booked for October 12" to MemoryKind.FACT,
            "Lisbon trip: staying near Alfama" to MemoryKind.FACT,
            "Vegetarian, but eats fish" to MemoryKind.PREFERENCE,
            "Decided to skip checked luggage" to MemoryKind.DECISION,
            "Sister Ana lives in Porto" to MemoryKind.FACT,
            "Gym on Monday and Thursday mornings" to MemoryKind.NOTE,
            "Coffee: flat white, oat milk" to MemoryKind.PREFERENCE,
        ).forEach { (t, k) -> mem.remember(t, k, Scope(Space.PERSONAL), "you", 0.9, pinned = t.startsWith("Vegetarian")) }
        listOf(
            "Team standup is at 9:30 on weekdays" to MemoryKind.FACT,
            "Quarterly report due Friday" to MemoryKind.FACT,
            "Quarterly report uses the new template" to MemoryKind.DECISION,
            "Prefers async updates over meetings" to MemoryKind.PREFERENCE,
            "Repo octo/app is the main project" to MemoryKind.NOTE,
        ).forEach { (t, k) -> mem.remember(t, k, Scope(Space.WORK), "you", 0.9) }
        mem.remember("Shortlisted three tascas in Alfama", MemoryKind.NOTE, Scope(Space.PERSONAL, done), "solis:$done")
        idleFor(300)
        vm.solis.debugClearBrainActivity()
        mem.search("Lisbon flights window seats", setOf(Scope(Space.PERSONAL), Scope(Space.PERSONAL, done)), by = done)
        vm.brainView = true
        idleUntil("activity mirrored") { vm.brainActivity.isNotEmpty() }
        listOf(ThemeMode.VOID, ThemeMode.PAPER, ThemeMode.NETRUNNER).forEach { mode ->
            shot("brain_${mode.id}") { Frame(mode) { MemoryScreen(vm) {} } }
        }
    }

    /** Claude Code jobs: overview, home card, settings, and the omnibox hand-off. */
    @Test
    fun coding() {
        val vm = vm()
        vm.finishOnboarding()
        val now = System.currentTimeMillis()
        val bridge = vm.solis.coding
        bridge.debugPut(com.axiom.launcher.solis.coding.CodingJob("octo/app", 12, "Add dark mode toggle", "https://github.com/octo/app/issues/12", null,
            now - 300_000, now - 60_000, com.axiom.launcher.solis.coding.JobState.WORKING, "3/5 · Add tests for the toggle", stepsDone = 3, stepsTotal = 5))
        bridge.debugPut(com.axiom.launcher.solis.coding.CodingJob("octo/app", 9, "Fix crash when opening settings", "https://github.com/octo/app/issues/9", null,
            now - 3_600_000, now - 120_000, com.axiom.launcher.solis.coding.JobState.PR_OPEN, "PR #10 · +42 −7 in 3 files · all 4 checks passed",
            branch = "claude/issue-9-20261026-0912", pr = 10, prUrl = "https://github.com/octo/app/pull/10", checks = "all 4 checks passed", mergeRequested = true))
        bridge.debugPut(com.axiom.launcher.solis.coding.CodingJob("octo/site", 4, "Update the pricing page copy", "https://github.com/octo/site/issues/4", null,
            now - 7_200_000, now - 7_000_000, com.axiom.launcher.solis.coding.JobState.MERGED, "PR #5 merged", pr = 5, watching = false))
        idleUntil("jobs mirrored") { vm.codingJobs.size == 3 }
        shot("tasks_coding_void") { Frame(ThemeMode.VOID) { TasksScreen(vm, {}, {}) } }
        shot("home_coding_paper") { Frame(ThemeMode.PAPER) { HomePage(vm, {}, {}, {}, {}, {}, {}) } }
        vm.settingsSection = "coding"
        shot("settings_coding_void") { Frame(ThemeMode.VOID) { SettingsScreen(vm) {} } }
        vm.query = "code: fix the login crash on Android 15"
        idleUntil("code hit") { vm.results.firstOrNull() is com.axiom.launcher.Hit.Code }
        shot("search_code_netrunner") { Frame(ThemeMode.NETRUNNER) { SearchOverlay(vm, {}, {}) } }
    }

    @Test
    fun icon() = listOf(ThemeMode.VOID, ThemeMode.PAPER).forEach { mode ->
        shot("icon_${mode.id}") {
            Frame(mode) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(216.dp).clip(RoundedCornerShape(48.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(28.dp))
                    AxiomWordmark(markSize = 40.dp, textSize = 30.sp)
                }
            }
        }
    }
}
