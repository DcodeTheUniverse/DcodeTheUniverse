package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.modes.AiPrivacy
import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.modes.NotifPresentation
import com.axiom.launcher.modes.Routine
import com.axiom.launcher.modes.RoutineAction
import com.axiom.launcher.modes.WorkApps
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.ThemeMode
import com.axiom.launcher.ui.theme.lStyle
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

private val DAY_LABELS = listOf(Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W", Calendar.THURSDAY to "T",
    Calendar.FRIDAY to "F", Calendar.SATURDAY to "S", Calendar.SUNDAY to "S")

@Composable
fun ModesSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf<String?>(null) }
    Text(
        "A mode is a preset for the home screen, notifications, AI budget and privacy. Modes don't create Android profiles or grant " +
            "permissions; your work profile and its policies stay as they are.",
        style = lStyle(13.sp, color = p.dim),
    )
    vm.modes.forEach { m ->
        SectionLabel("${m.glyph} ${m.name}" + if (m.id == vm.mode.id) " · active" else "")
        Text(modeSummary(m), style = lStyle(12.sp, color = p.dim))
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.id != vm.mode.id) Chip("Switch") { vm.switchMode(m.id) }
            Chip(if (open == m.id) "Done" else "Edit", selected = open == m.id) { open = if (open == m.id) null else m.id }
        }
        if (open == m.id) ModeEditor(m) { vm.updateMode(it) }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Add a mode") {
            val id = "mode-" + UUID.randomUUID().toString().take(6)
            vm.updateMode(LauncherMode(id, "New mode", "◇", vm.mode.space))
            open = id
        }
        Chip("Reset modes") { vm.resetModes() }
    }

    SectionLabel("Routines")
    Text("At a time on chosen days: switch mode, run an on-device command, or ask Solis. AI routines run only if you allow it for that routine; otherwise they wait on the home screen for your OK.",
        style = lStyle(13.sp, color = p.dim))
    var editing by remember { mutableStateOf<String?>(null) }
    vm.routines.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { editing = if (editing == r.id) null else r.id }) {
                    Text(r.name, style = lStyle(16.sp))
                    Text("%02d:%02d · %s · %s".format(r.hour, r.minute, daysLabel(r.days), r.action.describe()), style = lStyle(12.sp, color = p.dim))
                }
                androidx.compose.material3.Switch(checked = r.enabled, onCheckedChange = { vm.saveRoutine(r.copy(enabled = it)) })
            }
            if (editing == r.id) RoutineEditor(vm, r, onDone = { editing = null })
        }
    }
    Spacer(Modifier.height(8.dp))
    Chip("Add a routine") {
        val r = Routine(name = "New routine", hour = 8, minute = 0, action = RoutineAction.SwitchMode(vm.mode.id), enabled = false)
        vm.saveRoutine(r)
        editing = r.id
    }
}

private fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "every day"
    days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "weekdays"
    days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "weekends"
    else -> DAY_LABELS.filter { it.first in days }.joinToString("") { it.second }
}

@Composable
private fun ModeEditor(m: LauncherMode, save: (LauncherMode) -> Unit) {
    val p = LocalPalette.current
    var name by remember(m.id) { mutableStateOf(m.name) }
    var shortcuts by remember(m.id) { mutableStateOf(m.shortcuts.joinToString(", ")) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(12.dp)) {
        InputBar(name, { name = it }, "Mode name", onSubmit = { save(m.copy(name = name.ifBlank { m.name })) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (name != m.name && name.isNotBlank()) Chip("Save name", selected = true) { save(m.copy(name = name)) }
            listOf("◐", "▣", "◎", "✈", "☾", "◇", "⚡", "♫").forEach { g -> Chip(g, m.glyph == g) { save(m.copy(glyph = g)) } }
        }
        Label("Memory space for tasks")
        Choices(Space.entries.map { it to it.name.lowercase() }, m.space) { save(m.copy(space = it)) }
        Label("AI")
        Choices(AiPrivacy.entries.map { it to it.label }, m.aiPrivacy) { save(m.copy(aiPrivacy = it)) }
        Text(m.aiPrivacy.blurb, style = lStyle(12.sp, color = p.dim))
        Stepper("Daily AI budget", m.aiDailyBudgetUsd?.let { "$" + "%.2f".format(it) } ?: "global only", null,
            { save(m.copy(aiDailyBudgetUsd = m.aiDailyBudgetUsd?.let { if (it <= 0.05) null else it / 2 })) },
            { save(m.copy(aiDailyBudgetUsd = (m.aiDailyBudgetUsd?.times(2) ?: 0.10).coerceAtMost(50.0))) })
        Label("Notifications on home")
        Choices(NotifPresentation.entries.map { it to it.label }, m.notifications) { save(m.copy(notifications = it)) }
        Label("Work-profile apps")
        Choices(WorkApps.entries.map { it to it.label }, m.workApps) { save(m.copy(workApps = it)) }
        Label("Theme")
        Choices(listOf<Pair<ThemeMode?, String>>(null to "Your theme") + ThemeMode.entries.map { it to it.title }, m.theme) { save(m.copy(theme = it)) }
        SettingSwitch("Own home apps", "Pin a separate set of apps for this mode", m.favorites != null) { save(m.copy(favorites = if (it) emptyList() else null)) }
        SettingSwitch("Block distracting apps", "Ask before opening apps on your focus list", m.blockDistracting) { save(m.copy(blockDistracting = it)) }
        SettingSwitch("App suggestions", null, m.showSuggestions) { save(m.copy(showSuggestions = it)) }
        SettingSwitch("Data saver", "Treat the network as metered: fewer parallel tasks", m.dataSaver) { save(m.copy(dataSaver = it)) }
        SettingSwitch("Home-time clock", "Show the time where you set this, when you travel", m.homeTimezone != null) {
            save(m.copy(homeTimezone = if (it) TimeZone.getDefault().id else null))
        }
        Label("Shortcuts (commands, comma-separated)")
        InputBar(shortcuts, { shortcuts = it }, "agenda, focus 50, camera", onSubmit = {})
        if (shortcuts != m.shortcuts.joinToString(", ")) Chip("Save shortcuts", selected = true) {
            save(m.copy(shortcuts = shortcuts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(8)))
        }
    }
}

@Composable
private fun RoutineEditor(vm: LauncherViewModel, r: Routine, onDone: () -> Unit) {
    val p = LocalPalette.current
    var name by remember(r.id) { mutableStateOf(r.name) }
    var value by remember(r.id) {
        mutableStateOf(when (val a = r.action) { is RoutineAction.SwitchMode -> a.modeId; is RoutineAction.Command -> a.text; is RoutineAction.AskSolis -> a.prompt })
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(p.surface).padding(12.dp)) {
        InputBar(name, { name = it }, "Routine name", onSubmit = {})
        Stepper("Time", "%02d:%02d".format(r.hour, r.minute), null,
            { vm.saveRoutine(minutesShift(r, -15)) }, { vm.saveRoutine(minutesShift(r, 15)) })
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_LABELS.forEach { (d, l) ->
                Chip(l, d in r.days) { vm.saveRoutine(r.copy(days = if (d in r.days) r.days - d else r.days + d)) }
            }
        }
        Label("Action")
        val kind = when (r.action) { is RoutineAction.SwitchMode -> 0; is RoutineAction.Command -> 1; is RoutineAction.AskSolis -> 2 }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Switch mode", kind == 0) { vm.saveRoutine(r.copy(action = RoutineAction.SwitchMode(vm.modes.first().id))); value = vm.modes.first().id }
            Chip("Command", kind == 1) { vm.saveRoutine(r.copy(action = RoutineAction.Command("agenda"))); value = "agenda" }
            Chip("Ask Solis", kind == 2) { vm.saveRoutine(r.copy(action = RoutineAction.AskSolis("Give me my brief for this morning", false, 0.05))); value = "Give me my brief for this morning" }
        }
        when (val a = r.action) {
            is RoutineAction.SwitchMode -> Choices(vm.modes.map { it.id to "${it.glyph} ${it.name}" }, a.modeId) { vm.saveRoutine(r.copy(action = RoutineAction.SwitchMode(it))) }
            is RoutineAction.Command -> {
                InputBar(value, { value = it }, "timer 10 min, flashlight on, agenda…", onSubmit = {})
                Text("Runs on the phone only; never sent to a model.", style = lStyle(12.sp, color = p.dim))
            }
            is RoutineAction.AskSolis -> {
                InputBar(value, { value = it }, "What should Solis do?", onSubmit = {}, singleLine = false)
                SettingSwitch("Run without asking", "Off: it waits on the home screen for your OK", a.allowAi) { vm.saveRoutine(r.copy(action = a.copy(allowAi = it))) }
                Stepper("Budget for this run", "$" + "%.2f".format(a.budgetUsd), null,
                    { vm.saveRoutine(r.copy(action = a.copy(budgetUsd = (a.budgetUsd / 2).coerceAtLeast(0.01)))) },
                    { vm.saveRoutine(r.copy(action = a.copy(budgetUsd = (a.budgetUsd * 2).coerceAtMost(2.0)))) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Save", selected = true) {
                val action = when (val a = r.action) {
                    is RoutineAction.SwitchMode -> a
                    is RoutineAction.Command -> RoutineAction.Command(value.trim())
                    is RoutineAction.AskSolis -> a.copy(prompt = value.trim())
                }
                vm.saveRoutine(r.copy(name = name.ifBlank { r.name }, action = action))
                onDone()
            }
            Chip("Run now") { vm.runRoutine(r) }
            Chip("Delete") { vm.deleteRoutine(r.id); onDone() }
        }
    }
}

private fun minutesShift(r: Routine, delta: Int): Routine {
    val total = ((r.hour * 60 + r.minute + delta) % 1440 + 1440) % 1440
    return r.copy(hour = total / 60, minute = total % 60)
}

@Composable
private fun Label(text: String) {
    Text(text, style = lStyle(14.sp, FontWeight.Medium), modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Chip(label, v == selected) { onSelect(v) } }
        Spacer(Modifier.width(4.dp))
    }
}
