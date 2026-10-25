package com.dcode.launcher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dcode.launcher.LauncherViewModel
import com.dcode.launcher.LockService
import com.dcode.launcher.data.CalendarRepo
import com.dcode.launcher.data.NotificationWatcher
import com.dcode.launcher.ai.ClaudeAgent
import com.dcode.launcher.ui.theme.LocalPalette
import com.dcode.launcher.ui.theme.ThemeMode
import com.dcode.launcher.ui.theme.lStyle
import com.dcode.launcher.ui.theme.paletteFor

@Composable
fun SettingsScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; vm.refreshGlance() }
    var showHidden by remember { mutableStateOf(false) }
    var showFocusApps by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(p.bg).blockTouches().statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Netrunner) "/etc/dcode.conf" else "Settings", style = lStyle(30.sp, FontWeight.Light), modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            SectionLabel("Theme")
            ThemePicker(vm)

            SectionLabel("You")
            InputBar(vm.userName, { vm.updateName(it) }, "Your name", onSubmit = {})

            SectionLabel("Claude")
            ClaudeSettings(vm)

            SectionLabel("Home screen")
            SettingSwitch("App icons", "Off keeps the home screen text-only", vm.showIcons) { vm.updateShowIcons(it) }
            if (vm.showIcons) SettingSwitch("Monochrome icons", "Tint icons to match the theme", vm.monoIcons) { vm.updateMonoIcons(it) }
            SettingSwitch("24-hour clock", null, vm.clock24) { vm.updateClock24(it) }
            SettingSwitch("Keyboard opens with app drawer", null, vm.autoKeyboard) { vm.updateAutoKeyboard(it) }
            SettingSwitch("Visual effects", "Netrunner rain, scanlines, glitches and boot sequence", vm.effects) { vm.updateEffects(it) }

            SectionLabel("Focus mode")
            Text(
                if (vm.focusActive) "Active · ends ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(vm.focusUntil))}"
                else "Pauses distracting apps. Start it from the search bar (\"focus 45\") or below.",
                style = lStyle(13.sp, color = p.dim),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 90).forEach { m -> Chip("$m min") { vm.startFocus(m) } }
                if (vm.focusActive) Chip("Stop", selected = true) { vm.startFocus(0) }
                Chip(if (showFocusApps) "Hide app list" else "Choose apps…") { showFocusApps = !showFocusApps }
            }
            if (showFocusApps) {
                Spacer(Modifier.height(6.dp))
                vm.visibleApps().forEach { app ->
                    SettingSwitch(app.label, app.category.title, vm.isDistracting(app)) { vm.toggleDistracting(app) }
                }
            }

            SectionLabel("Hidden apps")
            if (vm.hidden.isEmpty()) Text("None. Long-press an app › Hide.", style = lStyle(13.sp, color = p.dim))
            else {
                Chip(if (showHidden) "Collapse" else "Show ${vm.hidden.size} hidden") { showHidden = !showHidden }
                if (showHidden) vm.apps.filter { it.key in vm.hidden }.forEach { app ->
                    ActionRow(null, app.label, "Tap to unhide") { vm.toggleHidden(app) }
                }
            }

            SectionLabel("Permissions & system")
            key(tick) {
            ActionRow(Icons.Outlined.Home, if (isDefaultLauncher(ctx)) "Default home app ✓" else "Set as default home app",
                "So the Home button opens Dcode") { vm.actions.chooseDefaultLauncher() }
            ActionRow(Icons.Outlined.Folder, if (vm.files.hasAllFilesAccess()) "All files access ✓" else "Allow all files access",
                "Folder browsing and file search for you and Claude") { vm.actions.allFilesAccessSettings() }
            ActionRow(Icons.Outlined.Contacts, if (vm.files.hasContacts()) "Contacts ✓" else "Allow contacts",
                "\"call mom\", contact search, texting by name") { contactsPerm.launch(Manifest.permission.READ_CONTACTS) }
            ActionRow(Icons.Outlined.CalendarMonth, if (CalendarRepo.hasPermission(ctx)) "Calendar ✓" else "Allow calendar",
                "Next event on home, agenda for Claude") { calendarPerm.launch(Manifest.permission.READ_CALENDAR) }
            ActionRow(Icons.Outlined.Notifications, if (NotificationWatcher.enabled(ctx)) "Notification digest ✓" else "Enable notification digest",
                "Lets Claude summarize your notifications. Kept in memory only") { vm.actions.notificationAccessSettings() }
            ActionRow(Icons.Outlined.AccessibilityNew, if (LockService.enabled()) "Double-tap to lock ✓" else "Enable double-tap to lock",
                "Accessibility service, used only to lock the screen") { vm.actions.accessibilitySettings() }
            }

            SectionLabel("Gestures")
            listOf(
                "Swipe up" to "App drawer", "Swipe down" to "Notifications", "Tap search bar" to "Omnibox + Claude",
                "Swipe right" to "Files", "Swipe left" to "Claude chat", "Long-press" to "These settings",
                "Double-tap" to "Lock", "Hold Home / assist" to "Claude (set Dcode as assist app)",
            ).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, style = lStyle(14.sp, FontWeight.Medium), modifier = Modifier.weight(0.45f))
                    Text(v, style = lStyle(14.sp, color = p.dim), modifier = Modifier.weight(0.55f))
                }
            }

            SectionLabel("About")
            Text(
                "Dcode ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""} · AI-first launcher.\n" +
                    "Your API key is encrypted with the Android Keystore and only sent to api.anthropic.com.",
                style = lStyle(13.sp, color = p.dim),
            )
            Spacer(Modifier.height(10.dp))
            Chip("Replay intro") { onClose(); vm.resetOnboarding() }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun ClaudeSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    var keyDraft by remember { mutableStateOf("") }
    Text(
        "Claude subscriptions (Pro/Max) can't be used by other apps directly, so Dcode gives you two engines:",
        style = lStyle(13.sp, color = p.dim),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Auto", vm.engine == "auto") { vm.updateEngine("auto") }
        Chip("Claude app", vm.engine == "app") { vm.updateEngine("app") }
        Chip("Built-in agent", vm.engine == "api") { vm.updateEngine("api") }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        when (vm.engine) {
            "app" -> "Questions open the official Claude app, on your subscription."
            "api" -> "Claude runs inside Dcode with your API key and can control the phone."
            else -> "Uses the built-in agent when an API key is set, otherwise the Claude app."
        },
        style = lStyle(12.sp, color = p.dim),
    )
    Spacer(Modifier.height(4.dp))
    ActionRow(Icons.AutoMirrored.Outlined.OpenInNew, if (vm.actions.isClaudeAppInstalled()) "Open the Claude app" else "Install the Claude app") {
        vm.actions.askClaudeApp(null)
    }

    Spacer(Modifier.height(6.dp))
    Text("Anthropic API key", style = lStyle(15.sp, FontWeight.Medium))
    Text(
        if (vm.hasApiKey) "Saved ✓ (encrypted on device)" else "Get one at console.anthropic.com › API keys. Usage is billed to that account.",
        style = lStyle(12.sp, color = p.dim),
    )
    Spacer(Modifier.height(6.dp))
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.faint, shape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Key, null, tint = p.dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (keyDraft.isEmpty()) Text(if (vm.hasApiKey) "••••••••  (paste to replace)" else "sk-ant-…", style = lStyle(15.sp, color = p.dim))
            BasicTextField(
                keyDraft, { keyDraft = it.trim() }, singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = p.fg),
                visualTransformation = PasswordVisualTransformation(),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (keyDraft.isNotEmpty()) Chip("Save", selected = true) { vm.updateApiKey(keyDraft); keyDraft = ""; vm.say("Key saved") }
        else if (vm.hasApiKey) Chip("Remove") { vm.updateApiKey(""); vm.say("Key removed") }
    }

    if (vm.hasApiKey) {
        Spacer(Modifier.height(12.dp))
        Text("Model", style = lStyle(15.sp, FontWeight.Medium))
        Spacer(Modifier.height(6.dp))
        ClaudeAgent.MODELS.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.updateModel(id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (vm.model == id) "●" else "○", style = lStyle(14.sp, color = p.accent))
                Spacer(Modifier.width(12.dp))
                Text(label, style = lStyle(15.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Effort", style = lStyle(15.sp, FontWeight.Medium))
        Text("Low is snappy; high thinks harder on tricky requests.", style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeAgent.EFFORTS.forEach { e -> Chip(e, vm.effort == e) { vm.updateEffort(e) } }
        }
        Spacer(Modifier.height(6.dp))
        SettingSwitch("Web search", "Let Claude search the web for fresh info", vm.webSearch) { vm.updateWebSearch(it) }
        SettingSwitch("Speak replies", "Read answers aloud", vm.speakReplies) { vm.updateSpeak(it) }
    }

    if (vm.memories.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("What Claude remembers", style = lStyle(15.sp, FontWeight.Medium))
        vm.memories.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• $m", style = lStyle(14.sp, color = p.dim), modifier = Modifier.weight(1f))
                IconBtn(Icons.Outlined.Close, "Forget") { vm.forget(i) }
            }
        }
    }
}

@Composable
fun ThemePicker(vm: LauncherViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeMode.entries.forEach { mode ->
            ThemeCard(mode, vm.theme == mode, Modifier.weight(1f)) { vm.applyTheme(mode) }
        }
    }
}

@Composable
private fun ThemeCard(mode: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tp = paletteFor(mode)
    val mono = mode == ThemeMode.NETRUNNER
    val shape = RoundedCornerShape(if (mono) 3.dp else 16.dp)
    Column(
        modifier
            .clip(shape)
            .background(tp.bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.faint, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        val ff = if (mono) FontFamily.Monospace else FontFamily.SansSerif
        Text("12:34", style = TextStyle(fontFamily = ff, fontSize = 22.sp, fontWeight = if (mono) FontWeight.Bold else FontWeight.Thin, color = tp.fg))
        Spacer(Modifier.height(6.dp))
        listOf("Phone", "Claude", "Music").forEach {
            Text(if (mono) "./${it.lowercase()}" else it, style = TextStyle(fontFamily = ff, fontSize = 11.sp, color = tp.fg))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (mono) tp.accent2 else tp.surfaceHi))
        Spacer(Modifier.height(8.dp))
        Text(mode.title + if (selected) "  ✓" else "", style = TextStyle(fontFamily = ff, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tp.fg))
        Text(mode.tagline, style = TextStyle(fontFamily = ff, fontSize = 9.sp, color = tp.dim), maxLines = 2)
    }
}
