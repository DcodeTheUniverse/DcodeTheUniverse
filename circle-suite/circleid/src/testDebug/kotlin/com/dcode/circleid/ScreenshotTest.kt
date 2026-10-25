package com.dcode.circleid

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dcode.circleid.ui.theme.Circle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcode.circle.core.Evidence
import com.dcode.circle.core.FactorIds
import com.dcode.circle.core.Fusion
import com.dcode.circleid.ui.components.FactorChip
import com.dcode.circleid.ui.components.ResultMark
import com.dcode.circleid.ui.components.Section
import com.dcode.circleid.ui.screens.HomeScreen
import com.dcode.circleid.ui.screens.WelcomeScreen
import com.dcode.circleid.ui.theme.CircleTheme
import com.dcode.circleid.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders key screens to PNG for design review. Runs only when SNAPSHOT_DIR is set. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = System.getenv("SNAPSHOT_DIR")

    private fun shot(name: String, content: @Composable () -> Unit) {
        assumeTrue(dir != null)
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        rule.mainClock.advanceTimeBy(1500)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun welcomeLight() = shot("circle_welcome_light") { CircleTheme(ThemeMode.Light) { WelcomeScreen {} } }
    @Test fun welcomeDark() = shot("circle_welcome_dark") { CircleTheme(ThemeMode.Dark) { WelcomeScreen {} } }
    @Test fun homeLight() = shot("circle_home_light") { CircleTheme(ThemeMode.Light) { HomeScreen({}, {}, {}, {}, {}) } }
    @Test fun homeDark() = shot("circle_home_dark") { CircleTheme(ThemeMode.Dark) { HomeScreen({}, {}, {}, {}, {}) } }
    @Test fun resultDark() = shot("circle_result_dark") {
        CircleTheme(ThemeMode.Dark) {
            val ev = listOf(
                Evidence(FactorIds.FINGERPRINT, "Fingerprint", true, 1.0, true, "Class 3 hardware match released the StrongBox key"),
                Evidence(FactorIds.FACE_MATCH, "Face match", true, 0.71, true, "Similarity 0.71 vs 0.50 needed · 14/16 frames matched"),
                Evidence(FactorIds.FACE_LIVENESS, "Liveness challenges", true, 1.0, true, "Blink · Turn left — same face tracked throughout"),
                Evidence(FactorIds.DEVICE, "Device integrity", true, 1.0, false, "All 6 checks passed"),
            )
            Column(Modifier.fillMaxSize().background(Circle.palette.background), horizontalAlignment = Alignment.CenterHorizontally) {
                ResultMark(true, Modifier.padding(top = 48.dp).size(120.dp))
                Text(Fusion.formatOdds(Fusion.decide(ev, 0.5f).impostorOdds), color = Circle.palette.ink, modifier = Modifier.padding(24.dp))
                Section("Signals") { ev.forEach { FactorChip(it.label, it.passed, it.detail) } }
            }
        }
    }
}
