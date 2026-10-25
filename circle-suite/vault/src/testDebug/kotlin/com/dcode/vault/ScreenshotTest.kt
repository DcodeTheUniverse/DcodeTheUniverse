package com.dcode.vault

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dcode.vault.ui.theme.V
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcode.crypto.Argon2Params
import com.dcode.crypto.Base32
import com.dcode.vault.core.PasswordFields
import com.dcode.vault.core.VaultSession
import com.dcode.vault.core.VaultStore
import com.dcode.vault.ui.markdown.MarkdownView
import com.dcode.vault.ui.screens.BrowserScreen
import com.dcode.vault.ui.screens.BrowserState
import com.dcode.vault.ui.screens.LockScreen
import com.dcode.vault.ui.screens.PasswordScreen
import com.dcode.vault.ui.screens.SetupScreen
import com.dcode.vault.ui.theme.ThemeMode
import com.dcode.vault.ui.theme.VaultTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Renders key screens to PNG for design review. Runs only when SNAPSHOT_DIR is set. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<TestHostActivity>()
    private val dir = System.getenv("SNAPSHOT_DIR")

    private val readme = """
        # Project Orion
        A **reusable** lander that docks with *orbital* fuel depots.

        ## Next steps
        - [x] Sketch the docking ring
        - [ ] Model heat shield in `ablate.py`
        - [ ] Ask about [ESA grants](https://esa.int)

        > Ideas are cheap; execution is everything.

        ```kotlin
        val thrust = mass * accel
        ```

        | Stage | Fuel | Δv |
        |---|:-:|--:|
        | 1 | RP-1 | 3.2 km/s |
        | 2 | LH2 | 4.1 km/s |
    """.trimIndent()

    private fun sampleSession(): Pair<VaultSession, String> {
        val store = VaultStore(File(Files.createTempDirectory("shot").toFile(), "v"))
        val s = store.open(store.create("x".toCharArray(), Argon2Params(1024, 1, 1)))
        val ideas = s.createFolder(null, "Ideas")
        s.createFolder(null, "Research")
        s.createFolder(ideas.id, "Space")
        s.createNote(null, "README.md", readme)
        s.createNote(null, "Shower thoughts.md", "Teleportation via photon entanglement…")
        s.importFile(null, "orbit-sketch.png", "image/png", ByteArray(48_000).inputStream())
        val pw = s.savePassword(
            null, null, "GitHub",
            PasswordFields("dcode", "c7#Lq9!vRz2@WmP8", "github.com", totp = Base32.encode("12345678901234567890".toByteArray())),
        )
        s.setFavorite(ideas.id, true)
        return s to pw.id
    }

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

    @Test fun setupLight() = shot("vault_setup_light") { VaultTheme(ThemeMode.Light) { SetupScreen {} } }
    @Test fun lockDark() = shot("vault_lock_dark") { VaultTheme(ThemeMode.Dark) { LockScreen() } }
    @Test fun browserLight() { val (s, _) = sampleSession(); shot("vault_browser_light") { VaultTheme(ThemeMode.Light) { BrowserScreen(s, BrowserState(), {}, {}, {}) } } }
    @Test fun browserDark() { val (s, _) = sampleSession(); shot("vault_browser_dark") { VaultTheme(ThemeMode.Dark) { BrowserScreen(s, BrowserState(), {}, {}, {}) } } }
    @Test fun searchLight() { val (s, _) = sampleSession(); shot("vault_search_light") { VaultTheme(ThemeMode.Light) { BrowserScreen(s, BrowserState().apply { query = "orbit" }, {}, {}, {}) } } }
    @Test fun markdownDark() = shot("vault_markdown_dark") { VaultTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().background(V.palette.background)) { MarkdownView(readme) } } }
    @Test fun passwordLight() { val (s, id) = sampleSession(); shot("vault_password_light") { VaultTheme(ThemeMode.Light) { PasswordScreen(s, id, null, {}, {}) } } }
}

