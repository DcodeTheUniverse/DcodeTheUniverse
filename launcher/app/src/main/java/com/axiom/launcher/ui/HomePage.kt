package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.Note
import com.axiom.launcher.ui.effects.GlitchText
import com.axiom.launcher.ui.effects.blinkAlpha
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.LocalType
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomePage(
    vm: LauncherViewModel,
    openDrawer: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    onAppMenu: (AppInfo) -> Unit,
    goTo: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val still = com.axiom.launcher.ui.effects.LocalStillFrame.current
    LaunchedEffect(Unit) {
        while (!still) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    var noteOpen by remember { mutableStateOf<Note?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -120f) openDrawer()
                        else if (total > 120f) { if (!vm.actions.expandNotifications()) openSearch() }
                    },
                ) { _, dy -> total += dy }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { vm.lockScreen() },
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); openSettings() },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        StatusLine(vm, now)
        Spacer(Modifier.height(10.dp))
        Clock(vm, now)
        Spacer(Modifier.height(14.dp))
        Greeting(vm)
        Glance(vm)

        val shownNotes = (vm.notes.filter { it.pinned } + vm.notes.filter { !it.pinned }).take(3)
        if (shownNotes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            shownNotes.forEach { n ->
                Text(
                    (if (Netrunner) "# " else if (n.pinned) "◆  " else "—  ") + n.text,
                    style = lStyle(14.sp, color = p.dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { noteOpen = n }.padding(vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (vm.suggestions.isNotEmpty()) {
            Text(if (Netrunner) "// predicted" else "NOW", style = lStyle(10.sp, FontWeight.Medium, p.dim).copy(letterSpacing = 2.sp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.suggestions.forEach { app ->
                    Chip(if (Netrunner) displayLabel(app.label).removePrefix("./") else app.label) { vm.open(app) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        val favs = vm.favoriteApps()
        if (favs.isEmpty()) {
            Text(
                if (Netrunner) "> no pinned binaries. swipe up · long-press · pin" else "Swipe up for apps. Long-press one to pin it here.",
                style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            favs.take(8).forEach { app ->
                AppRow(vm, app, size = LocalType.current.appSize, onLongClick = { onAppMenu(app) })
            }
        }
        Spacer(Modifier.height(18.dp))
        Omnibox(vm, onClick = openSearch)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (Netrunner) "< fs" else "‹ files", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { goTo(0) }.padding(4.dp))
            Text(if (Netrunner) "↑ apps" else "↑ apps", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { openDrawer() }.padding(4.dp))
            Text(if (Netrunner) "claude >" else "claude ›", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { goTo(2) }.padding(4.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    noteOpen?.let { NoteDialog(vm, it) { noteOpen = null } }
}

@Composable
private fun StatusLine(vm: LauncherViewModel, now: Long) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val battery = remember(now / 30_000) { DeviceInfo.battery(ctx) }
    val alarm = remember(now / 60_000) { DeviceInfo.nextAlarm(ctx) }
    val parts = buildList {
        if (battery.percent >= 0) add(
            if (Netrunner) "pwr[" + "■".repeat(battery.percent / 10) + "·".repeat(10 - battery.percent / 10) + "]${battery.percent}%${if (battery.charging) "+" else ""}"
            else "${battery.percent}%${if (battery.charging) " ⚡" else ""}"
        )
        alarm?.let {
            val t = SimpleDateFormat(if (vm.clock24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()).format(Date(it))
            add(if (Netrunner) "alarm:$t" else "⏰ $t")
        }
        if (vm.focusActive) {
            val m = ((vm.focusUntil - now) / 60_000).coerceAtLeast(0) + 1
            add(if (Netrunner) "focus:${m}m" else "focus · ${m}m")
        }
        if (Netrunner) add("net:${DeviceInfo.network(ctx)}")
    }
    Text(parts.joinToString(if (Netrunner) " | " else "   "), style = lStyle(12.sp, color = p.dim))
}

@Composable
private fun Clock(vm: LauncherViewModel, now: Long) {
    val p = LocalPalette.current
    val t = LocalType.current
    val time = SimpleDateFormat(if (vm.clock24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now))
    val style = lStyle(t.clockSize, t.clockWeight, p.fg).copy(lineHeight = t.clockSize)
    Box(Modifier.clickable { vm.actions.showAlarms() }) {
        if (Netrunner) GlitchText(time, style, enabled = vm.effects) else Text(time, style = style)
    }
    Text(
        if (Netrunner) date.lowercase() + " // " + SimpleDateFormat("ss", Locale.getDefault()).format(Date(now)) + "s" else date,
        style = lStyle(16.sp, color = p.dim),
        modifier = Modifier.clickable { vm.actions.openCalendar() },
    )
}

@Composable
private fun Greeting(vm: LauncherViewModel) {
    val p = LocalPalette.current
    if (Netrunner) {
        Text("root@axiom:~$ whoami", style = lStyle(13.sp, color = p.accent), modifier = Modifier.clickable { vm.run(com.axiom.launcher.core.Cmd.Brief) })
        Text("> ${vm.userName.ifBlank { "runner" }.lowercase()} · link ${if (vm.hasApiKey) "online" else "via claude.app"}", style = lStyle(13.sp, color = p.dim))
    } else {
        Text(vm.greeting() + ".", style = lStyle(18.sp, color = p.fg), modifier = Modifier.clickable { vm.run(com.axiom.launcher.core.Cmd.Brief) })
    }
}

/** Next event + notification digest entry points: the AI-first "glance" under the greeting. */
@Composable
private fun Glance(vm: LauncherViewModel) {
    val p = LocalPalette.current
    vm.nextEvent?.let { e ->
        Text(
            (if (Netrunner) "> next: " else "Next  ·  ") + vm.fmtEvent(e),
            style = lStyle(14.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).clickable { vm.actions.openCalendar() },
        )
    }
    if (vm.notifCount > 0) {
        Text(
            if (Netrunner) "> ${vm.notifCount} packets queued · run digest" else "${vm.notifCount} notifications  ·  summarize",
            style = lStyle(14.sp, color = p.accent), maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).clickable { vm.run(com.axiom.launcher.core.Cmd.Digest) },
        )
    }
}

@Composable
fun Omnibox(vm: LauncherViewModel, onClick: () -> Unit) {
    val p = LocalPalette.current
    val voice = LocalVoice.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, if (Netrunner) p.fg.copy(alpha = 0.5f) else p.faint.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Netrunner) {
            Text("$ ", style = lStyle(15.sp, FontWeight.Bold, p.accent2))
            Text("ask or exec", style = lStyle(15.sp, color = p.dim))
            Text("█", style = lStyle(15.sp, color = p.fg.copy(alpha = blinkAlpha())))
        } else {
            Icon(Icons.Outlined.AutoAwesome, null, tint = p.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ask Claude, search, or command", style = lStyle(15.sp, color = p.dim))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { voice { vm.submit(it, fromVoice = true) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, "Voice", tint = p.fg, modifier = Modifier.size(20.dp))
        }
    }
}
