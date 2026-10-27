package com.axiom.launcher.ui

import android.os.Process
import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.axiom.launcher.AxiomApp
import com.axiom.launcher.AxiomTestKit.idleUntil
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.platform.ModelDownload
import com.axiom.launcher.solis.ondevice.DeviceSpec
import com.axiom.launcher.solis.ondevice.LocalStats
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import com.axiom.launcher.ui.effects.LocalStillFrame
import com.axiom.launcher.ui.effects.MatrixRain
import com.axiom.launcher.ui.effects.scanlines
import com.axiom.launcher.ui.theme.LauncherTheme
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings › On-device AI on a Galaxy S24+, before and after Gemma 4 E4B is on the phone (a tall frame shows the whole page). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1500dp-xxhdpi")
class ScreenshotOnDeviceTest {
    private val app: AxiomApp get() = ApplicationProvider.getApplicationContext()
    private val s24plus = DeviceSpec(11_200_000_000L, 186_000_000_000L, arm64 = true, soc = "SM8650", model = "SM-S926B")

    private fun vm(): LauncherViewModel {
        app.solis.onDevice.debugSpec = s24plus
        val vm = LauncherViewModel(app)
        val user = Process.myUserHandle()
        vm.debugSetApps(listOf("Phone", "Maps").map { n -> AppInfo("k$n", n, n, ComponentName("pkg.${n.lowercase()}", "Main"), user, false, AppCategory.TOOLS) }, listOf("kPhone"))
        vm.finishOnboarding()
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

    @Test
    fun beforeAnyModelIsOnThePhone() {
        val vm = vm()
        vm.settingsSection = "ondevice"
        shot("ondevice_void") { Frame(ThemeMode.VOID) { SettingsScreen(vm) {} } }
        // E2B halfway through its download, waiting for Wi-Fi to come back.
        app.solis.onDevice.debugDownload = ModelDownload(OnDeviceCatalog.GEMMA4_E2B.id, "waiting", 1_250_000_000L, OnDeviceCatalog.GEMMA4_E2B.sizeBytes)
        shot("ondevice_downloading_netrunner") { Frame(ThemeMode.NETRUNNER) { SettingsScreen(vm) {} } }
    }

    @Test
    fun withGemma4E4bInUse() {
        val od = app.solis.onDevice
        val e4b = OnDeviceCatalog.GEMMA4_E4B
        File(od.dir, e4b.file).writeText("stand-in")
        File(od.dir, e4b.file + ".json").writeText(JSONObject().put("id", e4b.id).put("name", e4b.name).put("source", "download")
            .put("vision", true).put("maxContext", 32768).toString())
        od.rescan()
        od.recordStats(e4b.id, LocalStats(prefillTokens = 412, decodeTokens = 64, decodeTps = 16.4, firstTokenMs = 640, backend = "gpu"))
        val vm = vm()
        idleUntil("phone model listed") { vm.onDevice.installed.value.isNotEmpty() }
        vm.settingsSection = "ondevice"
        shot("ondevice_installed_paper") { Frame(ThemeMode.PAPER) { SettingsScreen(vm) {} } }
        vm.settingsSection = null
        shot("settings_with_ondevice_void") { Frame(ThemeMode.VOID) { SettingsScreen(vm) {} } }
    }
}
