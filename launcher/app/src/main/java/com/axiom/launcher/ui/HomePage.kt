package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.PAGE_FILES
import com.axiom.launcher.PAGE_SOLIS
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.Note
import com.axiom.launcher.modes.NotifPresentation
import com.axiom.launcher.modes.RoutineAction
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.plainText
import com.axiom.launcher.solis.engine.statusLine
import com.axiom.launcher.ui.effects.GlitchText
import com.axiom.launcher.ui.effects.LocalStillFrame
import com.axiom.launcher.ui.effects.blinkAlpha
import com.axiom.launcher.ui.solis.SolisFace
import com.axiom.launcher.ui.solis.caption
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.LocalType
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A clock state that ticks every second only when seconds are shown, otherwise on the minute. */
@Composable
fun rememberClock(seconds: Boolean): LongState {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val still = LocalStillFrame.current
    LaunchedEffect(seconds, still) {
        while (!still) {
            val now = System.currentTimeMillis()
            state.longValue = now
            val step = if (seconds) 1000L else 60_000L
            delay(step - now % step)
        }
    }
    return state
}

@Composable
fun HomePage(
    vm: LauncherViewModel,
    openDrawer: () -> Unit,
    openSearch: () -> Unit,
    openHomeMenu: () -> Unit,
    openTasks: () -> Unit,
    onAppMenu: (AppInfo) -> Unit,
    goTo: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val clock = rememberClock(seconds = Netrunner)
    var noteOpen by remember { mutableStateOf<Note?>(null) }
    // The glance and suggestion cards follow the clock (an event starting, a call to join).
    val minute = clock.longValue / 60_000
    LaunchedEffect(minute) { vm.refreshGlance() }

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
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); openHomeMenu() },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLine(vm, clock, Modifier.weight(1f))
            ModeChip(vm)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { Clock(vm, clock) }
            Column(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { goTo(PAGE_SOLIS) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SolisFace(vm.face, size = 54.dp)
                Text(vm.face.caption().lowercase(), style = lStyle(10.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Greeting(vm)
        Glance(vm)

        ProactiveCards(vm)
        RoutineAsks(vm)
        // With the lock on, task results and coding work stay off the home screen until unlocked.
        if (!vm.locked) {
            TaskCards(vm, openTasks) { id -> vm.selectedThread = id; goTo(PAGE_SOLIS) }
            homeCodingJobs(vm.codingJobs).forEach { j -> CodingCard(vm, j, openTasks) }
        }

        val shownNotes = (vm.notes.filter { it.pinned } + vm.notes.filter { !it.pinned }).take(3)
        if (shownNotes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            shownNotes.forEach { n ->
                Text(
                    (if (Netrunner) "# " else if (n.pinned) "◆  " else "—  ") + n.text,
                    style = lStyle(14.sp, color = p.dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { noteOpen = n }.padding(vertical = 3.dp),
                )
            }
        }

        if (vm.widgets.isEmpty()) Spacer(Modifier.weight(1f)) else WidgetArea(vm, Modifier.weight(1f))

        WorkPausedBanner(vm)

        if (vm.suggestions.isNotEmpty()) {
            Text(if (Netrunner) "// predicted" else "NOW", style = lStyle(10.sp, FontWeight.Medium, p.dim).copy(letterSpacing = 2.sp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.suggestions.forEach { app -> Chip(if (Netrunner) displayLabel(app.label).removePrefix("./") else app.label) { vm.open(app) } }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (vm.mode.shortcuts.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.mode.shortcuts.forEach { s -> Chip(s) { vm.submit(s) } }
            }
            Spacer(Modifier.height(12.dp))
        }

        val favs = vm.favoriteApps()
        if (favs.isEmpty()) {
            Text(
                if (Netrunner) "> no pinned binaries. swipe up · long-press · pin"
                else if (vm.mode.favorites != null) "${vm.mode.name} mode has its own home apps. Swipe up and long-press one to pin it."
                else "Swipe up for apps. Long-press one to pin it here.",
                style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            favs.take(8).forEach { app -> AppRow(vm, app, size = LocalType.current.appSize, onLongClick = { onAppMenu(app) }) }
        }
        Spacer(Modifier.height(16.dp))
        Omnibox(vm, onClick = openSearch)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (Netrunner) "< fs" else "‹ files", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { goTo(PAGE_FILES) }.padding(4.dp))
            Text("↑ apps", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { openDrawer() }.padding(4.dp))
            Text(if (Netrunner) "solis >" else "solis ›", style = lStyle(11.sp, color = p.faint), modifier = Modifier.clickable { goTo(PAGE_SOLIS) }.padding(4.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    noteOpen?.let { NoteDialog(vm, it) { noteOpen = null } }
}

@Composable
private fun StatusLine(vm: LauncherViewModel, clock: LongState, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val now = clock.longValue
    val battery = vm.device
    val alarm = remember(now / 60_000) { DeviceInfo.nextAlarm(ctx) }
    val parts = buildList {
        add(
            if (Netrunner) "pwr[" + "■".repeat(battery.batteryPct / 10) + "·".repeat(10 - battery.batteryPct / 10) + "]${battery.batteryPct}%${if (battery.charging) "+" else ""}"
            else "${battery.batteryPct}%${if (battery.charging) " ⚡" else ""}"
        )
        alarm?.let {
            val t = SimpleDateFormat(if (vm.clock24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()).format(Date(it))
            add(if (Netrunner) "alarm:$t" else "⏰ $t")
        }
        if (!battery.online) add(if (Netrunner) "net:down" else "offline")
        else if (Netrunner) add("net:${if (battery.metered) "metered" else "up"}")
    }
    Text(parts.joinToString(if (Netrunner) " | " else "   "), style = lStyle(12.sp, color = p.dim), modifier = modifier, maxLines = 1)
}

/** The active mode; tap to switch. Shows time left for a temporary mode. */
@Composable
private fun ModeChip(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val m = vm.mode
    val left = if (vm.tempUntil > 0) ((vm.tempUntil - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) else null
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(50)
    Text(
        "${m.glyph} ${if (Netrunner) m.name.lowercase() else m.name}" + (left?.let { " · ${it}m" } ?: ""),
        style = lStyle(12.sp, FontWeight.Medium),
        modifier = Modifier.clip(shape).border(1.dp, p.faint, shape).clickable { vm.modeSheet = true }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { role = Role.Button; contentDescription = "Mode: ${m.name}${left?.let { ", $it minutes left" } ?: ""}. Change mode" },
    )
}

@Composable
private fun Clock(vm: LauncherViewModel, clock: LongState) {
    val p = LocalPalette.current
    val t = LocalType.current
    val now = clock.longValue
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
    // Travel: a second clock for home time when it differs from where the phone is now.
    vm.mode.homeTimezone?.let { id ->
        val home = TimeZone.getTimeZone(id)
        if (home.getOffset(now) != TimeZone.getDefault().getOffset(now)) {
            val f = SimpleDateFormat(if (vm.clock24) "HH:mm" else "h:mm a", Locale.getDefault()).apply { timeZone = home }
            Text("home ${f.format(Date(now))} · ${id.substringAfterLast('/').replace('_', ' ')}", style = lStyle(14.sp, color = p.dim))
        }
    }
}

@Composable
private fun Greeting(vm: LauncherViewModel) {
    val p = LocalPalette.current
    if (Netrunner) {
        Text("root@axiom:~$ whoami", style = lStyle(13.sp, color = p.accent), modifier = Modifier.clickable { vm.run(com.axiom.launcher.core.Cmd.Brief) })
        Text("> ${vm.userName.ifBlank { "runner" }.lowercase()} · solis ${if (vm.providersConfigured) "online" else "unlinked"}", style = lStyle(13.sp, color = p.dim))
    } else {
        Text(vm.greeting() + ".", style = lStyle(18.sp), modifier = Modifier.clickable { vm.run(com.axiom.launcher.core.Cmd.Brief) })
    }
}

/** Next event and notifications, presented the way the active mode asks. */
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
    val n = vm.notifCount
    val digest = { vm.run(com.axiom.launcher.core.Cmd.Digest) }
    val line = when (vm.mode.notifications) {
        NotifPresentation.FULL -> if (n > 0) (if (Netrunner) "> $n packets queued · run digest" else "$n notifications  ·  summarize") else null
        NotifPresentation.COUNT -> if (n > 0) (if (Netrunner) "> $n packets" else "$n notifications") else null
        NotifPresentation.DIGEST_ONLY -> if (n > 0) (if (Netrunner) "> digest ready" else "Notification digest") else null
        NotifPresentation.HIDDEN -> null
    }
    line?.let {
        Text(
            it, style = lStyle(14.sp, color = p.accent), maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).let { m -> if (vm.mode.notifications == NotifPresentation.COUNT) m else m.clickable { digest() } },
        )
    }
}

/** Suggestions from local rules; nothing runs until a button is tapped. */
@Composable
private fun ProactiveCards(vm: LauncherViewModel) {
    val p = LocalPalette.current
    vm.proactive.forEach { c ->
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(if (Netrunner) 2.dp else 12.dp)).background(p.surface)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (Netrunner) "> ${c.title.lowercase()}" else c.title, style = lStyle(14.sp, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(c.detail, style = lStyle(12.sp, color = p.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Chip(c.actionLabel, selected = true) { vm.runCard(c) }
            IconBtn(Icons.Outlined.Close, "Dismiss for today") { vm.dismissCard(c) }
        }
    }
}

/** Routines that want to start AI work wait here for an explicit OK. */
@Composable
private fun RoutineAsks(vm: LauncherViewModel) {
    val p = LocalPalette.current
    vm.pendingAsks.forEach { r ->
        val prompt = (r.action as? RoutineAction.AskSolis)?.prompt.orEmpty()
        Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(12.dp)) {
            Text("${r.name} wants to ask Solis", style = lStyle(14.sp, FontWeight.Medium))
            Text("“${prompt.take(90)}”", style = lStyle(13.sp, color = p.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Run", selected = true) { vm.confirmAsk(r) }
                Chip("Not now") { vm.dismissAsk(r.id) }
            }
        }
    }
}

/** Resumable task cards: unfinished Solis work and fresh answers, one tap from home. */
@Composable
private fun TaskCards(vm: LauncherViewModel, openTasks: () -> Unit, open: (String) -> Unit) {
    val p = LocalPalette.current
    val cards = vm.taskCards()
    if (cards.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    cards.take(3).forEach { t -> TaskCard(vm, t) { open(t.id) } }
    if (cards.size > 3) Text("+${cards.size - 3} more · tasks", style = lStyle(12.sp, color = p.dim),
        modifier = Modifier.clickable(onClick = openTasks).padding(vertical = 4.dp))
}

@Composable
private fun TaskCard(vm: LauncherViewModel, t: AgentThread, open: () -> Unit) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(shape).background(p.surface).clickable(onClick = open)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusGlyph(t.status) + "  ", style = lStyle(13.sp, FontWeight.Bold, if (t.status == ThreadStatus.FAILED) p.danger else p.accent))
            Text(t.title, style = lStyle(14.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (t.status) {
                ThreadStatus.PAUSED, ThreadStatus.INTERRUPTED, ThreadStatus.BLOCKED ->
                    Text("resume", style = lStyle(12.sp, FontWeight.Medium, p.accent), modifier = Modifier.clickable { vm.resumeThread(t.id) }.padding(start = 8.dp))
                ThreadStatus.RUNNING, ThreadStatus.QUEUED, ThreadStatus.WAITING ->
                    Text("pause", style = lStyle(12.sp, color = p.dim), modifier = Modifier.clickable { vm.pauseThread(t.id) }.padding(start = 8.dp))
                else -> Unit
            }
        }
        val sub = when (t.status) {
            ThreadStatus.DONE -> t.lastResult?.let { plainText(it) }?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120) ?: "Done"
            ThreadStatus.NEEDS_INPUT -> t.lastResult?.let { plainText(it) } ?: "Waiting for your answer"
            ThreadStatus.BLOCKED, ThreadStatus.FAILED, ThreadStatus.INTERRUPTED -> t.blocker ?: t.statusLine
            else -> t.statusLine
        }
        Text(sub, style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (t.isActive && t.stepsTotal > 1) {
            Spacer(Modifier.height(5.dp))
            Progress(t.stepsDone / t.stepsTotal.toFloat())
        }
    }
}

/** Work profile turned off: its apps look paused, with a one-tap way to turn them back on. */
@Composable
private fun WorkPausedBanner(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val wp = vm.workProfile ?: return
    if (!wp.paused) return
    val workInView = vm.mode.space.name == "WORK" || vm.favoriteApps().any { it.isWork }
    if (!workInView) return
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (Netrunner) "> work partition offline" else "Work apps are paused", style = lStyle(13.sp, color = p.dim), modifier = Modifier.weight(1f))
        Chip("Turn on") { vm.setWorkPaused(false) }
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
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Netrunner) {
            Text("$ ", style = lStyle(15.sp, FontWeight.Bold, p.accent2))
            Text("ask solis or exec", style = lStyle(15.sp, color = p.dim))
            Text("█", style = lStyle(15.sp, color = p.fg.copy(alpha = blinkAlpha())))
        } else {
            Icon(Icons.Outlined.AutoAwesome, null, tint = p.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ask Solis, search, or > command", style = lStyle(15.sp, color = p.dim))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { voice { vm.submit(it, fromVoice = true) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, "Voice", tint = p.fg, modifier = Modifier.size(20.dp))
        }
    }
}
