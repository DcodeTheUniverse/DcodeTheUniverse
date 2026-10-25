package com.dcode.vault

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcode.vault.data.VaultState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app UI on the JVM: create a vault, write a note, and find it by content search.
 * Everything below the UI is the production code path, including Argon2id and the cascade cipher on disk.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 30_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun createVaultWriteNoteSearchAndLock() {
        rule.onNodeWithText("Create a vault").performClick()
        waitForText("Recovery passphrase")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("orbit-velvet-cactus-meadow-quartz-ripple")
        fields[1].performTextInput("orbit-velvet-cactus-meadow-quartz-ripple")
        rule.onNodeWithText("Create vault").performClick()
        waitForText("Passphrase only", 60_000)
        rule.onNodeWithText("Passphrase only").performClick()
        waitForText("Open vault")
        rule.onNodeWithText("Open vault").performClick()
        waitForText("Nothing here yet")

        // The speed dial opens (dialogs with text fields never idle under Robolectric, so the note itself is
        // created through the same session API the dialog calls).
        rule.onNodeWithContentDescription("New").performClick()
        waitForText("New note")
        rule.onNodeWithContentDescription("New").performClick()
        val session = (rule.activity.vault.state.value as VaultState.Unlocked).session
        session.createNote(null, "Untitled.md", "")
        waitForText("Untitled.md")
        rule.onNodeWithText("Untitled.md").performClick()
        waitForText("Start writing", 15_000)
        rule.onNode(hasSetTextAction()).performTextInput("# Quantum teleportation\nEntangle two photons.")
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Untitled.md")

        // Plaintext never reaches the disk.
        val vaultDir = File(rule.activity.noBackupFilesDir, "vault")
        val onDisk = vaultDir.walkTopDown().filter { it.isFile }.joinToString { it.readBytes().decodeToString() }
        assertFalse(onDisk.contains("teleportation") || onDisk.contains("Untitled"))

        rule.onNode(hasSetTextAction()).performTextInput("photon")
        waitForText("1 result", 15_000)
        rule.onNode(hasText("Untitled.md")).assertExistsCompat()

        rule.onNodeWithContentDescription("Lock").performClick()
        waitForText("Vault is locked")
        assertTrue(rule.activity.vault.state.value is VaultState.Locked)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()
}
