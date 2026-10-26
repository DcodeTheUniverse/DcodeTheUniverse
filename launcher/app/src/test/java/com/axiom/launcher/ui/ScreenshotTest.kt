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
