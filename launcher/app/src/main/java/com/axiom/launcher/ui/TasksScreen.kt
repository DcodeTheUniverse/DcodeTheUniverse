package com.axiom.launcher.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.statusLine
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Task overview: every Solis thread with its status, models, usage and controls, plus spend vs caps. */
@Composable
fun TasksScreen(vm: LauncherViewModel, onClose: () -> Unit, openThread: (String) -> Unit) {
    val p = LocalPalette.current
    val groups = remember(vm.threads) {
        listOf(
            "Working" to vm.threads.filter { it.status == ThreadStatus.RUNNING },
            "Waiting" to vm.threads.filter { it.status == ThreadStatus.QUEUED || it.status == ThreadStatus.WAITING },
            "Needs you" to vm.threads.filter { it.status == ThreadStatus.NEEDS_INPUT || it.status == ThreadStatus.BLOCKED || it.status == ThreadStatus.FAILED },
            "Paused" to vm.threads.filter { it.status == ThreadStatus.PAUSED || it.status == ThreadStatus.INTERRUPTED },
            "Done" to vm.threads.filter { it.status == ThreadStatus.DONE || it.status == ThreadStatus.CANCELLED },
        ).filter { it.second.isNotEmpty() }
    }
    val summary = remember(vm.threads, vm.settingsRevision) { vm.solis.ledger.summary() }
    val st = remember(vm.settingsRevision, vm.mode) { vm.solis.settings() }

    Column(Modifier.fillMaxSize().background(p.bg).blockTouches().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Netrunner) "ps -ef | solis" else "Tasks", style = lStyle(30.sp, FontWeight.Light), modifier = Modifier.weight(1f).semantics { heading() })
            IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                "Today ${usd(summary.today)} of ${usd(st.caps.dailyUsd)} · month ${usd(summary.month)} of ${usd(st.caps.monthlyUsd)}" +
                    " · ${summary.callsToday} model calls" + if (summary.reserved > 0) " · ${usd(summary.reserved)} reserved" else "",
                style = lStyle(13.sp, color = p.dim),
            )
            Text(
                "Up to ${st.maxConcurrentThreads} run at once" + (if (vm.device.constrained) " (1 now: battery or heat)" else if (vm.device.metered) " (2 now: metered network)" else "") +
                    " · background updates cap ${usd(st.caps.narratorDailyUsd)}/day",
                style = lStyle(12.sp, color = p.dim),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("New task", selected = true) { vm.newThread(); onClose() }
                if (vm.threads.any { it.isActive }) Chip("Pause all") { vm.pauseAll() }
                if (vm.threads.any { it.status == ThreadStatus.PAUSED || it.status == ThreadStatus.INTERRUPTED }) Chip("Resume all") { vm.resumeAll() }
                if (vm.threads.any { it.status == ThreadStatus.DONE || it.status == ThreadStatus.CANCELLED }) Chip("Clear finished") {
                    vm.threads.filter { it.status == ThreadStatus.DONE || it.status == ThreadStatus.CANCELLED }.forEach { vm.deleteThread(it.id) }
                }
            }
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No tasks yet. Ask Solis anything from the search bar or the Solis page.", style = lStyle(14.sp, color = p.dim))
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
            groups.forEach { (label, list) ->
                item(key = "h:$label") { SectionLabel("$label · ${list.size}") }
                items(list, key = { it.id }) { t -> TaskRow(vm, t) { openThread(t.id) } }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun TaskRow(vm: LauncherViewModel, t: AgentThread, open: () -> Unit) {
    val p = LocalPalette.current
    val time = remember(t.updatedAt) { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(t.updatedAt)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = open).padding(vertical = 10.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusGlyph(t.status) + "  ", style = lStyle(14.sp, FontWeight.Bold, p.accent))
            Text(t.title, style = lStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(time, style = lStyle(11.sp, color = p.dim))
        }
        val meta = buildList {
            add(t.statusLine.take(80))
            t.tier?.let { add(it.label) }
            t.models["WORKER"]?.let { add(it.substringAfter('|').substringAfterLast('/')) }
            if (t.usage.costUsd > 0) add(usd(t.usage.costUsd))
            if (t.space.name == "WORK") add("work")
            if (t.origin.startsWith("routine")) add("routine")
        }.joinToString(" · ")
        Text(meta, style = lStyle(12.sp, color = p.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.stepsTotal > 1 && t.isActive) {
            Spacer(Modifier.height(6.dp))
            Progress(t.stepsDone / t.stepsTotal.toFloat())
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (t.status) {
                ThreadStatus.RUNNING, ThreadStatus.QUEUED, ThreadStatus.WAITING -> Chip("Pause") { vm.pauseThread(t.id) }
                ThreadStatus.PAUSED, ThreadStatus.INTERRUPTED, ThreadStatus.BLOCKED, ThreadStatus.FAILED -> Chip("Resume", selected = true) { vm.resumeThread(t.id) }
                else -> Unit
            }
            if (t.isActive || t.status == ThreadStatus.PAUSED) Chip("Cancel") { vm.cancelThread(t.id) } else Chip("Delete") { vm.deleteThread(t.id) }
        }
    }
}

/** Thin determinate progress bar in the theme's colors. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.faint.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(p.accent))
    }
}
