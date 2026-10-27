package com.axiom.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.axiom.launcher.solis.engine.plainText
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MemFilter(val label: String) { ALL("All"), PERSONAL("Personal"), WORK("Work"), THREADS("Task notes"), PINNED("Pinned") }

/**
 * Everything Solis remembers, with provenance: inspect, correct, pin, move between spaces, forget,
 * export and import. Recalled memories reach models fenced as evidence, never as instructions.
 */
@Composable
fun MemoryScreen(vm: LauncherViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val items by produceState(emptyList<MemoryItem>(), vm.memoryRevision) { value = vm.memoryItems() }
    var filter by remember { mutableStateOf(MemFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(vm.memoryFocus) }
    var adding by remember { mutableStateOf(false) }
    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportMemory(it) } }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importMemory(it) } }
    val titles = remember(vm.threads) { vm.threads.associate { it.id to it.title } }

    val shown = remember(items, filter, query) {
        items.filter { m ->
            when (filter) {
                MemFilter.ALL -> true
                MemFilter.PERSONAL -> m.scope.space == Space.PERSONAL && !m.scope.isThread
                MemFilter.WORK -> m.scope.space == Space.WORK && !m.scope.isThread
                MemFilter.THREADS -> m.scope.isThread
                MemFilter.PINNED -> m.pinned
            } && (query.isBlank() || m.text.contains(query, ignoreCase = true) || m.tags.any { it.contains(query, true) })
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.memoryFocus, shown) {
        vm.memoryFocus?.let { id ->
            val idx = shown.indexOfFirst { it.id == id }
            if (idx >= 0) { open = id; listState.scrollToItem(idx + 1) }
            vm.memoryFocus = null
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).blockTouches().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Netrunner) "cat ~/.solis/memory" else "Memory", style = lStyle(30.sp, FontWeight.Light),
                modifier = Modifier.weight(1f).semantics { heading() })
            IconBtn(Icons.Outlined.Close, "Close", onClick = onClose)
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("List", selected = !vm.brainView) { vm.brainView = false }
            Chip("Brain", selected = vm.brainView) { vm.brainView = true }
        }
        if (vm.brainView) {
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                InputBar(query, { query = it }, "Highlight memories", onSubmit = {})
                BrainView(vm, items, query, onOpen = { id -> open = id; vm.memoryFocus = id; vm.brainView = false },
                    modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 12.dp))
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), state = listState) {
            item {
                Text(
                    "${items.size} memories · personal and work are kept apart. Solis uses what it recalls as evidence with its source and date, never as instructions.",
                    style = lStyle(13.sp, color = p.dim),
                )
                Spacer(Modifier.height(8.dp))
                val rev = vm.settingsRevision
                val (wp, pw) = remember(rev) { vm.prefs.workReadsPersonal to vm.prefs.personalReadsWork }
                SettingSwitch("Work tasks may read personal memory", null, wp) { v -> vm.editSolisPrefs { it.workReadsPersonal = v } }
                SettingSwitch("Personal tasks may read work memory", null, pw) { v -> vm.editSolisPrefs { it.personalReadsWork = v } }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Add", selected = adding) { adding = !adding }
                    Chip("Export") { exportDoc.launch("axiom-memory-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json") }
                    Chip("Import") { importDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    Chip("Tidy up") { vm.tidyMemory() }
                }
                if (adding) AddMemory(vm) { adding = false }
                InputBar(query, { query = it }, "Search memory", onSubmit = {})
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemFilter.entries.forEach { f -> Chip(f.label, selected = filter == f) { filter = f } }
                }
                if (shown.isEmpty()) Text(
                    if (items.isEmpty()) "Nothing yet. Say “remember …” or tell Solis a preference and it will appear here." else "No matches.",
                    style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            items(shown, key = { it.id }) { m ->
                MemoryRow(vm, m, threadTitle = m.scope.threadId?.let { titles[it] ?: "a deleted task" }, expanded = open == m.id) {
                    open = if (open == m.id) null else m.id
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** Provenance in words: "solis:<thread>" is Solis itself, during that task. */
fun memorySource(source: String): String = when {
    source.startsWith("solis:") -> "Solis"
    source == "you" -> "you"
    source.startsWith("you") -> source
    else -> source
}

@Composable
private fun AddMemory(vm: LauncherViewModel, done: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var space by remember { mutableStateOf(vm.mode.space) }
    var kind by remember { mutableStateOf(MemoryKind.FACT) }
    Column(Modifier.padding(bottom = 10.dp)) {
        InputBar(text, { text = it }, "Something Solis should know", onSubmit = {}, singleLine = false)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Space.entries.forEach { s -> Chip(s.name.lowercase(), selected = space == s) { space = s } }
            listOf(MemoryKind.FACT, MemoryKind.PREFERENCE, MemoryKind.DECISION, MemoryKind.NOTE).forEach { k ->
                Chip(k.name.lowercase(), selected = kind == k) { kind = k }
            }
        }
        Spacer(Modifier.height(8.dp))
        Chip("Remember", selected = true) {
            if (text.isNotBlank()) { vm.addMemory(text, kind, space); done() }
        }
    }
}

@Composable
private fun MemoryRow(vm: LauncherViewModel, m: MemoryItem, threadTitle: String?, expanded: Boolean, toggle: () -> Unit) {
    val p = LocalPalette.current
    var editing by remember(m.id, m.version) { mutableStateOf(m.text) }
    var confirmForget by remember(m.id) { mutableStateOf(false) }
    val date = remember(m.updatedAt) { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(m.updatedAt)) }
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .let { if (expanded) it.background(p.surface).border(1.dp, p.faint, shape) else it }
            .clickable(onClick = toggle).padding(10.dp),
    ) {
        Text((if (m.pinned) "◆ " else "") + if (expanded) m.text else plainText(m.text), style = lStyle(15.sp), maxLines = if (expanded) 20 else 3, overflow = TextOverflow.Ellipsis)
        val scope = when {
            threadTitle != null -> "task: $threadTitle"
            else -> m.scope.space.name.lowercase()
        }
        Text(
            "${m.kind.name.lowercase().replace('_', ' ')} · $scope · from ${memorySource(m.source)} · $date · ${(m.confidence * 100).toInt()}% sure" +
                (m.uncertainty?.let { " · $it" } ?: ""),
            style = lStyle(11.sp, color = p.dim), modifier = Modifier.padding(top = 3.dp),
        )
        if (!expanded) return@Column
        Spacer(Modifier.height(10.dp))
        InputBar(editing, { editing = it }, "Memory text", onSubmit = {}, singleLine = false)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing.trim() != m.text && editing.isNotBlank()) Chip("Save correction", selected = true) { vm.editMemory(m, text = editing) }
            Chip(if (m.pinned) "Unpin" else "Pin") { vm.editMemory(m, pinned = !m.pinned) }
            if (!m.scope.isThread) {
                val other = if (m.scope.space == Space.PERSONAL) Space.WORK else Space.PERSONAL
                Chip("Move to ${other.name.lowercase()}") { vm.editMemory(m, space = other) }
            }
            if (confirmForget) Chip("Forget for good", selected = true) { vm.forgetMemory(m.id) }
            else Chip("Forget") { confirmForget = true }
        }
    }
}
