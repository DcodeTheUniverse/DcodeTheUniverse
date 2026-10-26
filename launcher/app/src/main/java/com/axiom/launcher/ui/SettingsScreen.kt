package com.axiom.launcher.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.LockService
import com.axiom.launcher.data.CalendarRepo
import com.axiom.launcher.data.NotificationWatcher
import com.axiom.launcher.ui.solis.AxiomMark
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.axiom.launcher.ui.theme.lStyle
import com.axiom.launcher.ui.theme.paletteFor

private val SECTION_TITLES = mapOf(
    "providers" to "Providers & models",
    "cortex" to "Cortex & routing",
    "budget" to "Budget & usage",
    "privacy" to "Privacy & tasks",
    "coding" to "Coding with Claude Code",
    "modes" to "Modes & routines",
    "diagnostics" to "Diagnostics",
)

@Composable
fun SettingsScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    var section by remember { mutableStateOf(vm.settingsSection) }
    LaunchedEffect(vm.settingsSection) { vm.settingsSection?.let { section = it } }
    BackHandler(enabled = section != null) { section = null; vm.settingsSection = null }

    Column(Modifier.fillMaxSize().background(p.bg).blockTouches().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (section != null) IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back") { section = null; vm.settingsSection = null }
            else Spacer(Modifier.padding(start = 12.dp))
            Text(
                section?.let { SECTION_TITLES[it] } ?: if (Netrunner) "/etc/axiom.conf" else "Settings",
                style = lStyle(if (section == null) 30.sp else 24.sp, FontWeight.Light),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            when (section) {
                "providers" -> ProvidersSettings(vm)
                "cortex" -> CortexSettings(vm)
                "budget" -> BudgetSettings(vm)
                "privacy" -> PrivacySettings(vm)
                "coding" -> CodingSettings(vm)
                "modes" -> ModesSettings(vm)
                "diagnostics" -> DiagnosticsSettings(vm)
                else -> MainSettings(vm, onClose) { section = it }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun ColumnScope.MainSettings(vm: LauncherViewModel, onClose: () -> Unit, open: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; vm.refreshGlance() }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    var showHidden by remember { mutableStateOf(false) }
    var showFocusApps by remember { mutableStateOf(false) }

    SectionLabel("Theme")
    ThemePicker(vm)
    if (vm.mode.theme != null) Text("${vm.mode.name} mode uses ${vm.mode.theme!!.title}.", style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp))

    SectionLabel("You")
    InputBar(vm.userName, { vm.updateName(it) }, "Your name", onSubmit = {})

    SectionLabel("Solis")
    Text(
        when (vm.effectiveEngine) {
            "app" -> "Questions open the Claude app on your subscription."
            else -> if (vm.providersConfigured) "Solis runs tasks with your providers and routes each to a suitable model." else "Connect a provider so Solis can run tasks."
        },
        style = lStyle(13.sp, color = p.dim),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Auto", vm.engineMode == "auto") { vm.updateEngine("auto") }
        Chip("Solis brain", vm.engineMode == "brain") { vm.updateEngine("brain") }
        Chip("Claude app", vm.engineMode == "app") { vm.updateEngine("app") }
    }
    NavRow("Providers & models", if (vm.providersConfigured) "OpenRouter, Anthropic, local runtime · models per role" else "Not connected yet") { open("providers") }
    NavRow("Cortex & routing", "Decision matrix, tiers, learning, policy history, routing log") { open("cortex") }
    NavRow("Budget & usage", "Spending caps and what Solis spent") { open("budget") }
    NavRow("Privacy & tasks", "Offline mode, web, memory sharing, concurrency, auto-resume") { open("privacy") }
    NavRow("Coding with Claude Code", if (vm.githubConnected) "GitHub connected · ${vm.prefs.githubRepo.ifBlank { "choose a repository" }}"
        else "Hand coding work to Claude Code on GitHub, on your Claude subscription") { open("coding") }
    NavRow("Memory inspector", "See, correct, forget, export what Solis remembers") { onClose(); vm.brainView = false; vm.overlayRequest = com.axiom.launcher.Overlay.MEMORY }
    NavRow("Task overview", "All Solis threads, models and usage") { onClose(); vm.overlayRequest = com.axiom.launcher.Overlay.TASKS }
    SettingSwitch("Speak replies", "Read answers aloud when you asked by voice or here", vm.speakReplies) { vm.updateSpeak(it) }

    SectionLabel("Modes & routines")
    NavRow("${vm.mode.glyph} ${vm.mode.name} mode is active", "Edit Personal, Work, Focus, Travel and your routines") { open("modes") }

    SectionLabel("Home screen")
    SettingSwitch("App icons", "Off keeps the home screen text-only", vm.showIcons) { vm.updateShowIcons(it) }
    if (vm.showIcons) SettingSwitch("Monochrome icons", "Tint icons to match the theme", vm.monoIcons) { vm.updateMonoIcons(it) }
    SettingSwitch("24-hour clock", null, vm.clock24) { vm.updateClock24(it) }
    SettingSwitch("Keyboard opens with app drawer", null, vm.autoKeyboard) { vm.updateAutoKeyboard(it) }
    SettingSwitch("Visual effects", "Netrunner rain, scanlines, glitches and boot sequence", vm.effects) { vm.updateEffects(it) }
    Text("Motion", style = lStyle(16.sp), modifier = Modifier.padding(top = 8.dp))
    Text("Reduced motion stops decorative animation; Solis's face shows still expressions.", style = lStyle(12.sp, color = p.dim))
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Follow system", vm.reducedMotionPref == "system") { vm.updateReducedMotion("system") }
        Chip("Reduce", vm.reducedMotionPref == "on") { vm.updateReducedMotion("on") }
        Chip("Full", vm.reducedMotionPref == "off") { vm.updateReducedMotion("off") }
    }

    SectionLabel("Focus apps")
    Text("Apps blocked while a mode blocks distractions (Focus by default).", style = lStyle(13.sp, color = p.dim))
    Spacer(Modifier.height(6.dp))
    Chip(if (showFocusApps) "Hide app list" else "Choose apps…") { showFocusApps = !showFocusApps }
    if (showFocusApps) vm.visibleApps().forEach { app ->
        SettingSwitch(app.label, app.category.title, vm.isDistracting(app)) { vm.toggleDistracting(app) }
    }

    SectionLabel("Hidden apps")
    if (vm.hidden.isEmpty()) Text("None. Long-press an app › Hide.", style = lStyle(13.sp, color = p.dim))
    else {
        Chip(if (showHidden) "Collapse" else "Show ${vm.hidden.size} hidden") { showHidden = !showHidden }
        if (showHidden) vm.apps.filter { it.key in vm.hidden }.forEach { app -> ActionRow(null, app.label, "Tap to unhide") { vm.toggleHidden(app) } }
    }

    SectionLabel("Profiles")
    val wp = vm.workProfile
    if (wp == null) Text("No work profile on this phone. Work mode still keeps work tasks and memory separate inside Axiom.", style = lStyle(13.sp, color = p.dim))
    else ActionRow(Icons.Outlined.Work, if (wp.paused) "Work apps are paused" else "Work apps are on",
        "Tap to ${if (wp.paused) "turn them on" else "pause them"}. Your organization's policies apply; Axiom never reads work-app data.") { vm.setWorkPaused(!wp.paused) }
    if (vm.profiles.any { it.kind == com.axiom.launcher.platform.ProfileKind.PRIVATE })
        Text("Private space apps appear in the drawer only while it is unlocked and never reach Solis.", style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp))

    SectionLabel("Permissions & system")
    key(tick) {
        ActionRow(Icons.Outlined.Home, if (isDefaultLauncher(ctx)) "Default home app ✓" else "Set as default home app",
            "So the Home button opens Axiom") { vm.actions.chooseDefaultLauncher() }
        ActionRow(Icons.Outlined.Folder, if (vm.files.hasAllFilesAccess()) "All files access ✓" else "Allow all files access",
            "Folder browsing and file search for you and Solis") { vm.actions.allFilesAccessSettings() }
        ActionRow(Icons.Outlined.Contacts, if (vm.files.hasContacts()) "Contacts ✓" else "Allow contacts",
            "\"call mom\", contact search, texting by name") { contactsPerm.launch(Manifest.permission.READ_CONTACTS) }
        ActionRow(Icons.Outlined.CalendarMonth, if (CalendarRepo.hasPermission(ctx)) "Calendar ✓" else "Allow calendar",
            "Next event on home, agenda for Solis") { calendarPerm.launch(Manifest.permission.READ_CALENDAR) }
        ActionRow(Icons.Outlined.Notifications, if (NotificationWatcher.enabled(ctx)) "Notification digest ✓" else "Enable notification digest",
            "Lets Solis summarize notifications. Kept in memory only; work-profile ones stay with work tasks") { vm.actions.notificationAccessSettings() }
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ActionRow(Icons.Outlined.NotificationsActive, if (granted) "Task notifications ✓" else "Allow task notifications",
                "Progress of Solis tasks running in the background, with a pause button") { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        ActionRow(Icons.Outlined.AccessibilityNew, if (LockService.enabled()) "Double-tap to lock ✓" else "Enable double-tap to lock",
            "Accessibility service, used only to lock the screen") { vm.actions.accessibilitySettings() }
    }

    SectionLabel("Gestures")
    listOf(
        "Swipe up" to "App drawer", "Swipe down" to "Notifications", "Tap search bar" to "Search, ask Solis, > commands",
        "Swipe right" to "Files", "Swipe left" to "Solis", "Long-press" to "These settings", "Tap the mode chip" to "Switch mode",
        "Double-tap" to "Lock", "Hold Home / assist" to "Solis (set Axiom as assist app)",
    ).forEach { (k, v) ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(k, style = lStyle(14.sp, FontWeight.Medium), modifier = Modifier.weight(0.45f))
            Text(v, style = lStyle(14.sp, color = p.dim), modifier = Modifier.weight(0.55f))
        }
    }

    SectionLabel("About")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AxiomMark(28.dp)
        Text(
            "  Axiom ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""} · with Solis",
            style = lStyle(14.sp),
        )
    }
    Text(
        "API keys are encrypted with the Android Keystore, never backed up, and sent only to the provider they belong to.",
        style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp),
    )
    NavRow("Diagnostics & performance", "Startup, frames, memory, battery, Solis latency, engine state") { open("diagnostics") }
    Spacer(Modifier.height(6.dp))
    Chip("Replay intro") { onClose(); vm.resetOnboarding() }
}

/** A row that opens a settings sub-page. */
@Composable
fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = lStyle(16.sp))
            if (subtitle != null) Text(subtitle, style = lStyle(12.sp, color = p.dim))
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = p.dim)
    }
}

@Composable
fun ThemePicker(vm: LauncherViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeMode.entries.forEach { mode ->
            ThemeCard(mode, vm.userTheme == mode, Modifier.weight(1f)) { vm.setTheme(mode) }
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
        listOf("Phone", "Solis", "Music").forEach {
            Text(if (mono) "./${it.lowercase()}" else it, style = TextStyle(fontFamily = ff, fontSize = 11.sp, color = tp.fg))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (mono) tp.accent2 else tp.surfaceHi))
        Spacer(Modifier.height(8.dp))
        Text(mode.title + if (selected) "  ✓" else "", style = TextStyle(fontFamily = ff, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tp.fg))
        Text(mode.tagline, style = TextStyle(fontFamily = ff, fontSize = 9.sp, color = tp.dim), maxLines = 2)
    }
}

/** − value + control for numbers. */
@Composable
fun Stepper(title: String, value: String, subtitle: String? = null, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = lStyle(15.sp))
            if (subtitle != null) Text(subtitle, style = lStyle(12.sp, color = p.dim))
        }
        Chip("−", onClick = onMinus)
        Text(value, style = lStyle(15.sp, FontWeight.Medium), modifier = Modifier.padding(horizontal = 10.dp))
        Chip("+", onClick = onPlus)
    }
}
