package com.dcode.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcode.launcher.Hit
import com.dcode.launcher.LauncherViewModel
import com.dcode.launcher.core.Cmd
import com.dcode.launcher.core.DeviceInfo
import com.dcode.launcher.data.AppCategory
import com.dcode.launcher.data.AppInfo
import com.dcode.launcher.ui.theme.LocalPalette
import com.dcode.launcher.ui.theme.lStyle
import kotlinx.coroutines.launch

@Composable
fun SearchOverlay(vm: LauncherViewModel, onClose: () -> Unit, onAppMenu: (AppInfo) -> Unit) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val voice = LocalVoice.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg.copy(alpha = 0.97f))
            .blockTouches()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            if (vm.query.isBlank()) {
                item { SearchTips(vm) }
            }
            items(vm.results, key = { it.key }) { hit ->
                HitRow(vm, hit, onAppMenu) {
                    vm.execute(hit)
                    if (hit !is Hit.Calc && hit !is Hit.NoteItem) onClose()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        InputBar(
            value = vm.query,
            onValue = { vm.query = it },
            placeholder = if (Netrunner) "ask or exec" else "Ask Claude, search, or command",
            focus = focus,
            onSubmit = { vm.submit(); onClose() },
            trailing = {
                if (vm.query.isNotEmpty()) IconBtn(Icons.Outlined.Close, "Clear") { vm.query = "" }
                IconBtn(Icons.Outlined.Mic, "Voice") { voice { t -> vm.submit(t, fromVoice = true); onClose() } }
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SearchTips(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val tips = if (vm.effectiveEngine == "api") listOf(
        "timer 10 min", "alarm 6:45am", "call mom", "text alex: running late", "note buy oat milk",
        "navigate to the airport", "play lofi beats", "focus 45", "18% of 64.50", "what's eating my storage?",
    ) else listOf(
        "timer 10 min", "alarm 6:45am", "call mom", "note buy oat milk", "play lofi beats", "focus 45",
        "wifi", "how do black holes form?", "neofetch",
    )
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(if (Netrunner) "// try" else "TRY", style = lStyle(10.sp, FontWeight.Medium, p.dim).copy(letterSpacing = 2.sp))
        Spacer(Modifier.height(8.dp))
        tips.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                row.forEach { Chip(it) { vm.query = it } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (vm.effectiveEngine == "api") "Questions go to Claude inside Dcode (${com.dcode.launcher.ai.ClaudeAgent.prettyModel(vm.model)})."
            else "Questions open the Claude app on your subscription. Add an API key in settings for the built-in agent.",
            style = lStyle(12.sp, color = p.dim),
        )
    }
}

@Composable
fun InputBar(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester? = null,
    onSubmit: () -> Unit,
    singleLine: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, if (Netrunner) p.fg.copy(alpha = 0.6f) else p.faint.copy(alpha = 0.6f), shape)
            .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Netrunner) Text("$ ", style = lStyle(16.sp, FontWeight.Bold, p.accent2))
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.isEmpty()) Text(placeholder, style = lStyle(16.sp, color = p.dim))
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                textStyle = lStyle(16.sp),
                cursorBrush = SolidColor(if (Netrunner) p.fg else p.accent),
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Go else ImeAction.Default),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().let { m -> if (focus != null) m.focusRequester(focus) else m },
            )
        }
        trailing()
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint ?: p.fg, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HitRow(vm: LauncherViewModel, hit: Hit, onAppMenu: (AppInfo) -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val (icon, title, sub) = when (hit) {
        is Hit.App -> Triple(null, displayLabel(hit.app.label), hit.app.category.title.lowercase())
        is Hit.Command -> Triple(cmdIcon(hit.cmd), hit.cmd.title, if (Netrunner) "exec" else "command")
        is Hit.Calc -> Triple(Icons.Outlined.Calculate, "= ${hit.result}", "tap to copy")
        is Hit.Contact -> Triple(Icons.Outlined.Person, hit.c.name, hit.c.number ?: "")
        is Hit.FileItem -> Triple(if (hit.f.isDir) Icons.Outlined.Folder else Icons.Outlined.Description, hit.f.name,
            hit.f.path.removePrefix("/storage/emulated/0").substringBeforeLast('/').ifEmpty { "/" } + if (!hit.f.isDir) " · " + DeviceInfo.human(hit.f.size) else "")
        is Hit.Setting -> Triple(Icons.Outlined.Settings, hit.title, "settings")
        is Hit.NoteItem -> Triple(Icons.Outlined.PushPin, hit.note.text, "note")
        is Hit.Ask -> Triple(Icons.Outlined.AutoAwesome, "Ask Claude: ${hit.q}",
            if (vm.effectiveEngine == "api") "in Dcode · ${com.dcode.launcher.ai.ClaudeAgent.prettyModel(vm.model)}" else "opens the Claude app")
        is Hit.Web -> Triple(Icons.Outlined.Language, "Search the web: ${hit.q}", "browser")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = { if (hit is Hit.App) onAppMenu(hit.app) })
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hit is Hit.App && vm.showIcons) {
            AppIcon(vm, hit.app, 26.dp)
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = if (hit is Hit.Ask) p.accent else p.dim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = lStyle(if (hit is Hit.App) 20.sp else 16.sp, color = if (hit is Hit.Ask) p.accent else p.fg),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun cmdIcon(c: Cmd): ImageVector = when (c) {
    is Cmd.Timer, is Cmd.Alarm, Cmd.Alarms -> Icons.Outlined.Alarm
    is Cmd.Text, is Cmd.Email -> Icons.AutoMirrored.Outlined.Message
    is Cmd.Call -> Icons.Outlined.Person
    is Cmd.Web, is Cmd.Url -> Icons.Outlined.Language
    Cmd.Neofetch, Cmd.Matrix -> Icons.Outlined.Terminal
    is Cmd.Note, is Cmd.Remember -> Icons.Outlined.PushPin
    is Cmd.Setting, Cmd.QuickSettings -> Icons.Outlined.Settings
    else -> Icons.Outlined.Bolt
}

// =============================== Drawer ===============================

@Composable
fun Drawer(vm: LauncherViewModel, onClose: () -> Unit, onAppMenu: (AppInfo) -> Unit) {
    val p = LocalPalette.current
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AppCategory.ALL) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (vm.autoKeyboard) runCatching { focus.requestFocus() } }

    val all = vm.visibleApps()
    val categories = remember(all) { listOf(AppCategory.ALL) + AppCategory.entries.filter { c -> c != AppCategory.ALL && all.any { it.category == c } } }
    val list = remember(all, filter, category) {
        val base = if (category == AppCategory.ALL) all else all.filter { it.category == category }
        if (filter.isBlank()) base else vm.findApps(filter, 100).filter { category == AppCategory.ALL || it.category == category }
    }
    val letters = remember(list, filter) { if (filter.isBlank()) list.map { it.label.first().uppercaseChar().let { c -> if (c.isLetter()) c else '#' } }.distinct() else emptyList() }

    // Pull down at the top of the list closes the drawer.
    val closeOnPull = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) { pulled += available.y; if (pulled > 220f) { pulled = 0f; onClose() } } else pulled = 0f
                return Offset.Zero
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .blockTouches()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().pointerInput(Unit) {
            var t = 0f
            detectVerticalDragGestures(onDragStart = { t = 0f }, onDragEnd = { if (t > 80f) onClose() }) { _, dy -> t += dy }
        }) {
            InputBar(
                value = filter, onValue = { filter = it },
                placeholder = if (Netrunner) "grep apps" else "Search ${all.size} apps",
                focus = focus,
                onSubmit = { list.firstOrNull()?.let { vm.open(it); onClose() } },
                trailing = { IconBtn(Icons.Outlined.Close, "Close") { kb?.hide(); onClose() } },
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { c -> Chip(c.title.lowercase().let { if (Netrunner) it else c.title }, selected = c == category) { category = c } }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().nestedScroll(closeOnPull), state = listState) {
                items(list, key = { it.key }) { app ->
                    AppRow(vm, app, size = 21.sp, dim = app.key in vm.hidden, onLongClick = { onAppMenu(app) })
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
            if (letters.size > 4) {
                Column(Modifier.fillMaxHeight().width(22.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    letters.forEach { l ->
                        Text(l.toString(), style = lStyle(11.sp, FontWeight.Medium, p.dim), modifier = Modifier.clickable {
                            val idx = list.indexOfFirst { (it.label.first().uppercaseChar().let { c -> if (c.isLetter()) c else '#' }) == l }
                            if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
                        }.padding(2.dp))
                    }
                }
            }
        }
    }
}
