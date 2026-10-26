package com.axiom.launcher.ui

import android.content.ComponentName
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.AxiomTestKit.installFakeProvider
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.Overlay
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.ui.effects.LocalStillFrame
import com.axiom.launcher.ui.theme.LauncherTheme
import com.axiom.launcher.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Drives the real launcher UI (LauncherRoot) with taps and typing, so the flows people use are
 * verified end to end: mode switching, the command palette, on-device commands, and asking Solis.
 * Runs under Robolectric with a scripted local model; it is a simulation, not a device test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class UiFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()

    private var lastVm: LauncherViewModel? = null

    private fun launch(): LauncherViewModel {
        app.installFakeProvider()
        val vm = LauncherViewModel(app)
        lastVm = vm
        vm.finishOnboarding()
        val u = Process.myUserHandle()
        vm.debugSetApps(listOf("Phone", "Messages", "Maps").map { AppInfo("k$it", it, it, ComponentName("p.$it", "M"), u, false, AppCategory.TOOLS) }, listOf("kPhone"))
        compose.setContent {
            LauncherTheme(ThemeMode.VOID) {
                CompositionLocalProvider(LocalStillFrame provides true) { LauncherRoot(vm, 0) }
            }
        }
        waitFor(5_000) { vm.providersConfigured }
        return vm
    }

    /** Waits for [cond] while advancing the main looper's clock with real time (debounces and delays need it). */
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            compose.waitForIdle()
            if (cond()) return
            Thread.sleep(16)
        }
        throw AssertionError("condition not met in $timeoutMs ms; query='${lastVm?.query}' results=${lastVm?.results?.size} toast=${lastVm?.toast} threads=${lastVm?.threads?.map { it.status }}")
    }

    private fun openSearch() {
        compose.onNodeWithText("Ask Solis, search, or > command").performClick()
        compose.waitForIdle()
    }

    /** The pager keeps neighbouring pages composed, so pick the text field that is actually on screen. */
    private fun searchField(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(hasSetTextAction())
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val index = all.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.width > 0f && it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= width }
        check(index >= 0) { "no on-screen text field" }
        return all[index]
    }

    @Test
    fun modeChipSwitchesTheLauncherMode() {
        val vm = launch()
        compose.onNodeWithContentDescription("Mode: Personal", substring = true).performClick()
        compose.onAllNodesWithText("Work")[0].performClick()
        waitFor(3_000) { vm.mode.id == "work" }
        compose.onNodeWithContentDescription("Mode: Work", substring = true).assertExists()
    }

    @Test
    fun commandPaletteOpensTheMemoryInspector() {
        val vm = launch()
        openSearch()
        searchField().performTextInput(">memory")
        waitFor(3_000) { vm.results.isNotEmpty() }
        compose.onNodeWithText("Memory inspector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search memory").assertExists()
    }

    @Test
    fun deviceCommandFromSearchRunsWithoutAModel() {
        val vm = launch()
        openSearch()
        searchField().performTextInput("timer 5 min")
        waitFor(3_000) { vm.results.isNotEmpty() && vm.query == "timer 5 min" }
        searchField().performImeAction()
        waitFor(5_000) { vm.toast?.startsWith("Timer set") == true }
        assertTrue("no Solis task for a device command", vm.threads.isEmpty())
    }

    @Test
    fun askingFromSearchStartsATaskAndShowsItsAnswer() {
        val vm = launch()
        openSearch()
        searchField().performTextInput("Plan my week around three gym sessions")
        waitFor(3_000) { vm.results.isNotEmpty() }
        searchField().performImeAction()
        waitFor(10_000) { vm.threads.firstOrNull()?.status == ThreadStatus.DONE }
        compose.waitForIdle()
        assertEquals(vm.threads.first().id, vm.selectedThread)
        // The answer shows in the thread transcript and on the home task card.
        assertTrue(compose.onAllNodesWithText("Here's what I found.").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("Reply in this thread").assertExists()
        // The face is labelled for screen readers wherever it appears.
        assertTrue(compose.onAllNodesWithContentDescription("Solis").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun overlaysCanBeOpenedFromAnywhere() {
        val vm = launch()
        vm.overlayRequest = Overlay.TASKS
        compose.waitForIdle()
        compose.onNodeWithText("New task").assertExists()
    }
}
