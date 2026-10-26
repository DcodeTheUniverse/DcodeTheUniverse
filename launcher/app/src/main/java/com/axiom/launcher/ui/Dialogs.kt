package com.axiom.launcher.ui

import android.app.role.RoleManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.FileHit
import com.axiom.launcher.data.Note
import com.axiom.launcher.ui.theme.LocalMode
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.axiom.launcher.ui.theme.lStyle
import java.io.File

/** Themed dialog card used by every popup in the launcher. */
@Composable
fun LDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val mode = LocalMode.current
    val shape = shapeFor(mode)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(p.bg)
                .border(1.dp, if (mode == ThemeMode.NETRUNNER) p.fg.copy(alpha = 0.6f) else p.faint, shape)
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
fun AppMenu(vm: LauncherViewModel, app: AppInfo, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(app.label) }
    val fav = app.key in vm.favorites
    LDialog(onDismiss) {
        Text(displayLabel(app.label), style = lStyle(22.sp, FontWeight.Medium))
        Text(app.packageName, style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(12.dp))
        if (renaming) {
            InputBar(name, { name = it }, "Name", onSubmit = { vm.rename(app, name); onDismiss() })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Save", selected = true) { vm.rename(app, name); onDismiss() }
                Chip("Reset") { vm.rename(app, ""); onDismiss() }
            }
            return@LDialog
        }
        ActionRow(Icons.Outlined.PushPin, if (fav) "Unpin from home" else "Pin to home") { vm.toggleFavorite(app); onDismiss() }
        if (fav) {
            Row {
                Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.ArrowUpward, "Move up") { vm.moveFavorite(app, -1) } }
                Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.ArrowDownward, "Move down") { vm.moveFavorite(app, 1) } }
            }
        }
        ActionRow(Icons.Outlined.Edit, "Rename") { renaming = true }
        ActionRow(if (app.key in vm.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            if (app.key in vm.hidden) "Unhide" else "Hide from drawer") { vm.toggleHidden(app); onDismiss() }
        ActionRow(Icons.Outlined.DoNotDisturbOn, if (vm.isDistracting(app)) "Allow during focus" else "Block during focus") {
            vm.toggleDistracting(app); onDismiss()
        }
        ActionRow(Icons.Outlined.AutoAwesome, "Ask Claude about this app") {
            onDismiss(); vm.ask("Give me 3 quick power-user tips for the ${app.label} app on Android.")
        }
        ActionRow(Icons.Outlined.Info, "App info") { vm.appInfo(app); onDismiss() }
        ActionRow(Icons.Outlined.Delete, "Uninstall") { vm.uninstall(app); onDismiss() }
    }
}

@Composable
fun FileMenu(vm: LauncherViewModel, f: FileHit, onDone: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    LDialog(onDone) {
        Text(f.name, style = lStyle(20.sp, FontWeight.Medium))
        Text(f.path, style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(12.dp))
        if (confirmDelete) {
            Text("Delete ${if (f.isDir) "this folder and everything in it" else "this file"}? This can't be undone.", style = lStyle(15.sp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Delete", selected = true) {
                    val ok = runCatching { if (f.isDir) File(f.path).deleteRecursively() else File(f.path).delete() }.getOrDefault(false)
                    vm.say(if (ok) "Deleted" else "Couldn't delete")
                    onDone()
                }
                Chip("Cancel") { confirmDelete = false }
            }
            return@LDialog
        }
        if (!f.isDir) {
            ActionRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open") { vm.actions.openFile(f.path); onDone() }
            ActionRow(Icons.Outlined.AutoAwesome, "Ask Claude about this file",
                if (vm.effectiveEngine == "api") "images, PDFs and text files" else "shares it to the Claude app") {
                vm.attachFile(f.path); onDone()
            }
            ActionRow(Icons.Outlined.Share, "Share") { vm.actions.shareFile(f.path); onDone() }
        }
        ActionRow(Icons.Outlined.ContentCopy, "Copy path") {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.path))
            vm.say("Path copied"); onDone()
        }
        ActionRow(Icons.Outlined.Delete, "Delete") { confirmDelete = true }
    }
}

@Composable
fun NoteDialog(vm: LauncherViewModel, note: Note, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    LDialog(onDismiss) {
        Text(if (Netrunner) "// note #${note.id % 10000}" else "Note", style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(6.dp))
        Text(note.text, style = lStyle(18.sp))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Done", selected = true) { vm.deleteNote(note.id); onDismiss() }
            Chip(if (note.pinned) "Unpin" else "Pin") { vm.togglePin(note); onDismiss() }
            Chip("Ask Claude") { onDismiss(); vm.ask("Help me with this note: ${note.text}") }
        }
    }
}

@Composable
fun FocusBlockedDialog(vm: LauncherViewModel, app: AppInfo) {
    val p = LocalPalette.current
    val left = ((vm.focusUntil - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
    LDialog({ vm.focusBlocked = null }) {
        Text(if (Netrunner) "ACCESS DENIED" else "Stay focused", style = lStyle(22.sp, FontWeight.Medium, if (Netrunner) p.danger else p.fg))
        Spacer(Modifier.height(6.dp))
        Text(
            if (Netrunner) "> ${displayLabel(app.label)} is firewalled for another $left min."
            else "${app.label} is paused for another $left minutes.",
            style = lStyle(15.sp, color = p.dim),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Back to work", selected = true) { vm.focusBlocked = null }
            Chip("Open anyway") { vm.focusBlocked = null; vm.open(app, force = true) }
        }
    }
}

@Composable
fun TerminalDialog(text: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    LDialog(onDismiss) {
        Text("$ neofetch", style = lStyle(13.sp, color = p.accent2).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                """
                  ____
                 |  _ \
                 | | | |
                 | |_| |
                 |____/ _
                """.trimIndent(),
                style = lStyle(12.sp, color = p.accent).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
            Spacer(Modifier.padding(6.dp))
            Text(text, style = lStyle(12.sp).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 17.sp))
        }
    }
}

@Composable
fun LockSetupDialog(vm: LauncherViewModel) {
    val p = LocalPalette.current
    LDialog({ vm.needsLockSetup = false }) {
        Text("Double-tap to lock", style = lStyle(20.sp, FontWeight.Medium))
        Text(
            "Android only lets launchers lock the screen through an accessibility service. Turn on \"Axiom · double-tap to lock\" " +
                "in Accessibility settings. It does nothing else and reads no screen content.",
            style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open settings", selected = true) { vm.needsLockSetup = false; vm.actions.accessibilitySettings() }
            Chip("Not now") { vm.needsLockSetup = false }
        }
    }
}

fun isDefaultLauncher(ctx: android.content.Context): Boolean {
    val rm = ctx.getSystemService(RoleManager::class.java)
    return rm?.isRoleHeld(RoleManager.ROLE_HOME) == true
}

@Composable
fun Onboarding(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(vm.userName) }
    Box(Modifier.fillMaxSize().background(p.bg).blockTouches()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp),
        ) {
            Spacer(Modifier.height(30.dp))
            Text(if (Netrunner) "AXIOM//OS" else "Axiom", style = lStyle(48.sp, FontWeight.Thin))
            Text(
                if (Netrunner) "> an ai-first shell for your deck." else "An AI-first home screen. Minimal by default, Claude when you need it.",
                style = lStyle(16.sp, color = p.dim),
            )
            Spacer(Modifier.height(28.dp))
            SectionLabel("Pick a look")
            ThemePicker(vm)
            SectionLabel("What should Claude call you?")
            InputBar(name, { name = it }, "Your name", onSubmit = { vm.updateName(name) })
            SectionLabel("Make it home")
            Text(
                "Set Axiom as your default home app so the Home button brings you here.",
                style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(bottom = 8.dp),
            )
            Chip(if (isDefaultLauncher(ctx)) "Axiom is your home app ✓" else "Set as default home") { vm.actions.chooseDefaultLauncher() }
            SectionLabel("How it works")
            listOf(
                "Swipe up" to "all apps, searchable, by category",
                "Tap the bar" to "search apps, files, contacts, settings · or ask Claude",
                "Swipe left / right" to "files · Claude",
                "Swipe down" to "notifications",
                "Long-press home" to "settings & themes",
                "Double-tap" to "lock screen (optional)",
            ).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(k, style = lStyle(14.sp, FontWeight.Medium), modifier = Modifier.weight(0.42f))
                    Text(v, style = lStyle(14.sp, color = p.dim), modifier = Modifier.weight(0.58f))
                }
            }
            Spacer(Modifier.height(28.dp))
            Chip(if (Netrunner) "jack in" else "Start", selected = true) {
                vm.updateName(name.trim())
                vm.finishOnboarding()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
