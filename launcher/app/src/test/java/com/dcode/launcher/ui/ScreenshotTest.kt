package com.dcode.launcher.ui

import android.app.Application
import android.content.ComponentName
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.dcode.launcher.LauncherViewModel
import com.dcode.launcher.data.AppCategory
import com.dcode.launcher.data.AppInfo
import com.dcode.launcher.data.ChatMsg
import com.dcode.launcher.data.ChatRole
import com.dcode.launcher.ui.effects.MatrixRain
import com.dcode.launcher.ui.effects.scanlines
import com.dcode.launcher.ui.theme.LauncherTheme
import com.dcode.launcher.ui.theme.LocalPalette
import com.dcode.launcher.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders key screens in every theme to PNGs under build/screens for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {

    private fun vm(): LauncherViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LauncherViewModel(app)
        val user = Process.myUserHandle()
        val names = listOf(
            "Phone" to AppCategory.TOOLS, "Messages" to AppCategory.SOCIAL, "Claude" to AppCategory.WORK,
            "Spotify" to AppCategory.MEDIA, "Camera" to AppCategory.MEDIA, "Maps" to AppCategory.TRAVEL,
            "Chrome" to AppCategory.TOOLS, "Gmail" to AppCategory.WORK, "Instagram" to AppCategory.SOCIAL,
            "Calendar" to AppCategory.WORK, "Clock" to AppCategory.TOOLS, "Notion" to AppCategory.WORK,
            "Signal" to AppCategory.SOCIAL, "YouTube" to AppCategory.MEDIA, "Uber" to AppCategory.TRAVEL,
            "Authenticator" to AppCategory.TOOLS, "Duolingo" to AppCategory.OTHER, "Photos" to AppCategory.MEDIA,
        )
        val apps = names.map { (n, c) ->
            AppInfo("k$n", n, n, ComponentName("pkg.${n.lowercase()}", "Main"), user, false, c)
        }.sortedBy { it.label }
        vm.debugSetApps(apps, listOf("kPhone", "kMessages", "kClaude", "kSpotify", "kMaps"))
        return vm
    }

    @Composable
    private fun Frame(mode: ThemeMode, content: @Composable () -> Unit) {
        LauncherTheme(mode) {
            val p = LocalPalette.current
            CompositionLocalProvider(LocalVoice provides { }, com.dcode.launcher.ui.effects.LocalStillFrame provides true) {
                Box(Modifier.fillMaxSize().background(p.bg).scanlines(mode == ThemeMode.NETRUNNER)) {
                    if (mode == ThemeMode.NETRUNNER) MatrixRain(Modifier.fillMaxSize(), intensity = 0.13f, color = p.fg)
                    content()
                }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screens/$name.png") { content() }

    @Test
    fun home() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        vm.updateName("Dee")
        vm.finishOnboarding()
        vm.addNote("Pick up film from the lab")
        shot("home_${mode.id}") { Frame(mode) { HomePage(vm, {}, {}, {}, {}, {}) } }
    }

    @Test
    fun drawer() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        vm.updateAutoKeyboard(false)
        shot("drawer_${mode.id}") { Frame(mode) { Drawer(vm, {}, {}) } }
    }

    @Test
    fun search() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        vm.query = "timer 10 min"
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        shot("search_${mode.id}") { Frame(mode) { SearchOverlay(vm, {}, {}) } }
    }

    @Test
    fun chat() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        vm.updateApiKeyForTest()
        vm.chat += ChatMsg(1, ChatRole.USER, "Wake me at 6:30 and find my boarding pass")
        vm.chat += ChatMsg(2, ChatRole.TOOL, "alarm 06:30")
        vm.chat += ChatMsg(3, ChatRole.TOOL, "search files 'boarding' (1)")
        vm.chat += ChatMsg(4, ChatRole.ASSISTANT, "Done. Alarm set for **6:30**.\n\nFound your boarding pass:\n- `Download/BA0117_boarding.pdf`\n\nWant me to open it?")
        shot("chat_${mode.id}") { Frame(mode) { ChatPage(vm, true) } }
    }

    @Test
    fun chatEmpty() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        shot("chat_empty_${mode.id}") { Frame(mode) { ChatPage(vm, true) } }
    }

    @Test
    fun settings() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        shot("settings_${mode.id}") { Frame(mode) { SettingsScreen(vm) {} } }
    }

    @Test
    fun files() = ThemeMode.entries.forEach { mode ->
        val vm = vm()
        shot("files_${mode.id}") { Frame(mode) { FilesPage(vm) } }
    }

    @Test
    fun onboarding() = listOf(ThemeMode.VOID, ThemeMode.NETRUNNER).forEach { mode ->
        val vm = vm()
        shot("onboarding_${mode.id}") { Frame(mode) { Onboarding(vm) } }
    }
}

private fun LauncherViewModel.updateApiKeyForTest() {
    val f = LauncherViewModel::class.java.getDeclaredField("hasApiKey\$delegate")
    f.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (f.get(this) as androidx.compose.runtime.MutableState<Boolean>).value = true
}
