package com.axiom.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VoiceOverOff
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.axiom.launcher.VoicePhase
import java.io.File
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.modes.AiPrivacy
import com.axiom.launcher.solis.SolisHost
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.LivePhase
import com.axiom.launcher.solis.engine.LiveState
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.TranscriptEntry
import com.axiom.launcher.solis.engine.statusLine
import com.axiom.launcher.ui.effects.blinkAlpha
import com.axiom.launcher.ui.solis.SolisFace
import com.axiom.launcher.ui.solis.caption
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Solis: independent agent threads, each with its own transcript, route explanations and controls. */
@Composable
fun SolisPage(vm: LauncherViewModel, active: Boolean, openTasks: () -> Unit, openMemory: () -> Unit) {
    val p = LocalPalette.current
    val voice = LocalVoice.current
    val ctx = LocalContext.current
    val selected = vm.selectedThread?.let { id -> vm.threads.firstOrNull { it.id == id } }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoiceMode() else vm.say("Voice mode needs the microphone")
    }
    val startVoiceMode = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoiceMode()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    var snapPath by rememberSaveable { mutableStateOf<String?>(null) }
    val snap = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        snapPath?.let { vm.onSnapped(File(it), ok) }
        snapPath = null
    }
    val takePhoto = {
        val f = vm.snapFile()
        snapPath = f.path
        runCatching { snap.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)) }.onFailure { snapPath = null; vm.say("No camera app found") }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SolisFace(vm.face, size = 62.dp, interactive = true, onClick = {
                if (vm.voicePhase == VoicePhase.SPEAKING) vm.voiceInterrupt() else { vm.brainView = true; openMemory() }
            })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (Netrunner) "solis" else "Solis", style = lStyle(28.sp, FontWeight.Light), modifier = Modifier.semantics { heading() })
                Text(headerLine(vm), style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (vm.voicePhase == VoicePhase.OFF) IconBtn(Icons.Outlined.RecordVoiceOver, "Voice mode: talk with Solis hands-free") { startVoiceMode() }
            else IconBtn(Icons.Outlined.VoiceOverOff, "End voice mode", p.accent) { vm.stopVoiceMode() }
            IconBtn(Icons.AutoMirrored.Outlined.ViewList, "Task overview", onClick = openTasks)
            IconBtn(Icons.Outlined.Hub, "Memory and brain view") { vm.brainView = true; openMemory() }
        }
        Spacer(Modifier.height(10.dp))
        ThreadStrip(vm)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.effectiveEngine == "app" -> AppHandoff(vm)
                selected == null -> SolisEmpty(vm)
                else -> ThreadView(vm, selected, active)
            }
        }

        vm.pendingAttachment?.let { att ->
            Row(
                Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(p.surface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, null, tint = p.dim, modifier = Modifier.size(16.dp))
                Text(" ${att.name}", style = lStyle(13.sp), maxLines = 1, modifier = Modifier.widthIn(max = 240.dp))
                IconBtn(Icons.Outlined.Close, "Remove attachment") { vm.pendingAttachment = null }
            }
        }

        if (vm.pendingAttachment?.name?.startsWith("Screen") == true && vm.solisDraft.isBlank()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.screenPrompts.forEach { q -> Chip(q) { vm.sendSolis(q) } }
            }
        }

        if (vm.voicePhase != VoicePhase.OFF) {
            VoicePanel(vm)
            Spacer(Modifier.height(10.dp))
            return@Column
        }

        if (vm.batchAvailable && selected == null && vm.pendingAttachment == null && vm.solisDraft.isNotBlank() && vm.effectiveEngine != "app") {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (vm.batchNext) "Half-price batch ✓" else "Half-price batch", selected = vm.batchNext) { vm.batchNext = !vm.batchNext }
                Text(
                    if (vm.batchNext) "  Answer within 24 h, no phone actions" else "  Not urgent? Half the price",
                    style = lStyle(12.sp, color = p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val followUp = selected != null && selected.status.acceptsFollowUp
        val running = selected != null && selected.status == ThreadStatus.RUNNING
        InputBar(
            value = vm.solisDraft,
            onValue = { vm.solisDraft = it },
            placeholder = when {
                Netrunner -> if (followUp) "reply to thread…" else "spawn a task…"
                vm.effectiveEngine == "app" -> "Message (opens the Claude app)"
                followUp -> "Reply in this thread"
                selected != null -> "Start a separate task"
                else -> "Ask Solis anything"
            },
            onSubmit = { vm.sendSolis() },
            singleLine = false,
            trailing = {
                if (running && vm.solisDraft.isBlank()) IconBtn(Icons.Outlined.Stop, "Pause this task", p.danger) { vm.pauseThread(selected!!.id) }
                else if (vm.solisDraft.isBlank() && vm.pendingAttachment == null) Row {
                    IconBtn(Icons.Outlined.PhotoCamera, "Snap a photo and ask") { takePhoto() }
                    IconBtn(Icons.Outlined.Mic, "Speak to Solis") { voice { vm.sendSolis(it) } }
                }
                else IconBtn(Icons.AutoMirrored.Outlined.Send, "Send", p.accent) { vm.sendSolis() }
            },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** The user's quick prompts: tap to ask, long-press to remove; the current draft can be saved as one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPrompts(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val draft = vm.solisDraft.trim()
    if (vm.savedPrompts.isEmpty() && draft.length < 6) return
    Text(if (Netrunner) "// saved" else "YOUR PROMPTS", style = lStyle(10.sp, FontWeight.Medium, p.dim).copy(letterSpacing = 2.sp))
    vm.savedPrompts.forEach { s ->
        Text(
            (if (Netrunner) "$ " else "★  ") + s,
            style = lStyle(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { vm.sendSolis(s) }, onLongClick = { vm.deletePrompt(s); vm.say("Removed “${s.take(30)}”") })
                .padding(vertical = 8.dp),
        )
    }
    if (draft.length >= 6 && draft !in vm.savedPrompts) Chip("Save draft as a prompt", icon = Icons.Outlined.BookmarkAdd) { vm.savePrompt(draft) }
    Spacer(Modifier.height(12.dp))
}

/** Voice mode: what Solis heard, what it's doing, and a way out. */
@Composable
private fun VoicePanel(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.faint, shape).padding(16.dp)) {
        Text(
            when (vm.voicePhase) {
                VoicePhase.LISTENING -> if (Netrunner) "> listening_" else "Listening…"
                VoicePhase.THINKING -> if (Netrunner) "> processing_" else "Thinking…"
                VoicePhase.SPEAKING -> if (Netrunner) "> speaking_" else "Speaking…"
                VoicePhase.OFF -> ""
            },
            style = lStyle(18.sp, FontWeight.Medium),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            vm.voiceHeard.ifBlank { "Talk normally. Say “stop” or tap End when you're done." },
            style = lStyle(14.sp, color = if (vm.voiceHeard.isBlank()) p.dim else p.fg), maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("End", selected = true) { vm.stopVoiceMode() }
            if (vm.voicePhase == VoicePhase.SPEAKING) Chip("Interrupt") { vm.voiceInterrupt() }
        }
    }
}

private fun headerLine(vm: LauncherViewModel): String {
    val m = vm.mode
    val privacy = when (m.aiPrivacy) {
        AiPrivacy.CLOUD -> "cloud"
        AiPrivacy.CLOUD_ZDR -> "zero-retention"
        AiPrivacy.LOCAL_ONLY -> "local only"
        AiPrivacy.OFF -> "AI off"
    }
    val running = vm.threads.count { it.status == ThreadStatus.RUNNING }
    val waiting = vm.threads.count { it.status == ThreadStatus.QUEUED || it.status == ThreadStatus.WAITING }
    return buildList {
        add(vm.face.caption())
        add("${m.glyph} ${m.name} · $privacy")
        if (running > 0) add("$running running")
        if (waiting > 0) add("$waiting waiting")
        if (vm.offline) add("offline mode")
    }.joinToString(" · ")
}

/** Status glyph for a thread chip; text labels carry the meaning for screen readers. */
fun statusGlyph(s: ThreadStatus): String = when (s) {
    ThreadStatus.RUNNING -> "◉"
    ThreadStatus.QUEUED, ThreadStatus.WAITING -> "◌"
    ThreadStatus.NEEDS_INPUT -> "?"
    ThreadStatus.PAUSED, ThreadStatus.INTERRUPTED -> "‖"
    ThreadStatus.BLOCKED, ThreadStatus.FAILED -> "!"
    ThreadStatus.DONE -> "✓"
    ThreadStatus.CANCELLED -> "×"
}

@Composable
private fun ThreadStrip(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val list = remember(vm.threads) {
        vm.threads.sortedWith(compareBy<AgentThread> { if (it.isActive) 0 else 1 }.thenByDescending { it.updatedAt }).take(24)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Chip("New", selected = vm.selectedThread == null, icon = Icons.Outlined.Add) { vm.newThread() } }
        items(list, key = { it.id }) { t ->
            val sel = vm.selectedThread == t.id
            val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(50)
            Row(
                Modifier.clip(shape)
                    .background(if (sel) p.accent else p.surface)
                    .border(1.dp, if (sel) p.accent else p.faint, shape)
                    .clickable { vm.selectedThread = t.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusGlyph(t.status), style = lStyle(12.sp, FontWeight.Bold, if (sel) p.onAccent else if (t.status == ThreadStatus.FAILED || t.status == ThreadStatus.BLOCKED) p.danger else p.accent))
                Spacer(Modifier.width(6.dp))
                Text(t.title, style = lStyle(13.sp, FontWeight.Medium, if (sel) p.onAccent else p.fg), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp))
            }
        }
    }
}

@Composable
private fun SolisEmpty(vm: LauncherViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
        if (!vm.providersConfigured) {
            ProviderSetupCard(vm)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            if (Netrunner) "> each task runs as its own agent thread." else "Each request becomes its own task.",
            style = lStyle(19.sp),
        )
        Text(
            "Solis plans, picks a model for the job, works on the phone with tools, and keeps going in the background. " +
                "Simple commands run on the device with no model at all.",
            style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(14.dp))
        SavedPrompts(vm)
        listOf(
            "Plan my evening around a 7pm gym session",
            "Find my latest PDF and summarize it",
            "What's taking up my storage?",
            "Wake me at 6:30 and set a 20 min nap timer",
            "Draft a reply to my last unread message",
        ).forEach { s ->
            Text(
                if (Netrunner) "> $s" else "→  $s",
                style = lStyle(15.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.sendSolis(s) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** First-run card: connect OpenRouter (one key, many models), a local runtime, or hand off to the Claude app. */
@Composable
fun ProviderSetupCard(vm: LauncherViewModel) {
    val p = LocalPalette.current
    var key by remember { mutableStateOf("") }
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.faint, shape).padding(16.dp)) {
        Text("Connect Solis to a model", style = lStyle(16.sp, FontWeight.Medium))
        Text(
            "Paste an OpenRouter key to use hundreds of models with automatic routing. Keys are encrypted on this phone and never backed up.",
            style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(vertical = 6.dp),
        )
        SecretField(key, { key = it }, "sk-or-…")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (key.isNotBlank()) Chip("Save key", selected = true) { vm.saveKey(SolisHost.OPENROUTER, key); key = "" }
            Chip("Get a key", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.openUrl("https://openrouter.ai/keys") }
            Chip("Local runtime…") { vm.settingsSection = "providers"; vm.overlayRequest = com.axiom.launcher.Overlay.SETTINGS }
            Chip("Use Claude app") { vm.updateEngine("app") }
        }
    }
}

/** Masked single-line input for credentials. */
@Composable
fun SecretField(value: String, onValue: (String) -> Unit, placeholder: String) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(p.bg).border(1.dp, p.faint, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Key, null, tint = p.dim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = lStyle(14.sp, color = p.dim))
            BasicTextField(
                value, { onValue(it.trim()) }, singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = p.fg),
                visualTransformation = PasswordVisualTransformation(),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AppHandoff(vm: LauncherViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Using the Claude app", style = lStyle(20.sp))
        Text(
            "Messages open the official Claude app, so they run on your Claude subscription. Solis's own brain " +
                "(threads, tools, routing) needs a model provider such as OpenRouter.",
            style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open Claude app", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.askClaudeApp(null) }
            Chip("Use Solis instead", selected = true) { vm.updateEngine("auto") }
        }
    }
}

@Composable
private fun ThreadView(vm: LauncherViewModel, t: AgentThread, active: Boolean) {
    val p = LocalPalette.current
    val cached = remember(t.id) { vm.engine.cachedTranscript(t.id) }
    val entries by produceState(cached?.value ?: emptyList(), t.id) {
        val flow = cached ?: withContext(Dispatchers.IO) { vm.engine.transcript(t.id) }
        flow.collect { value = it }
    }
    val live = vm.live[t.id]
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size, live?.text?.length, active) {
        if (entries.isNotEmpty()) listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    var details by remember(t.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))
        ThreadHeader(vm, t, details) { details = !details }
        if (details) ThreadDetails(vm, t)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(entries, key = { it.seq }) { e -> Entry(e) }
            if (live != null) item(key = "live") { LiveRow(live) }
            t.blocker?.takeIf { !t.isActive }?.let { b ->
                item(key = "blocker") {
                    Text((if (Netrunner) "[halt] " else "") + b, style = lStyle(13.sp, color = if (t.status == ThreadStatus.FAILED) p.danger else p.dim))
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable
private fun ThreadHeader(vm: LauncherViewModel, t: AgentThread, details: Boolean, toggle: () -> Unit) {
    var receipts by remember(t.id) { mutableStateOf(false) }
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = toggle).padding(vertical = 4.dp)) {
        Text(t.title, style = lStyle(17.sp, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val status = buildList {
            add(t.statusLine)
            t.tier?.let { add(it.label) }
            if (t.stepsTotal > 1) add("${t.stepsDone}/${t.stepsTotal} steps")
            if (t.usage.costUsd > 0) add(usd(t.usage.costUsd))
            add(if (t.space.name == "WORK") "work" else "personal")
        }.joinToString(" · ")
        Text(status, style = lStyle(12.sp, color = p.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (t.status) {
            ThreadStatus.RUNNING, ThreadStatus.QUEUED, ThreadStatus.WAITING -> Chip("Pause", icon = Icons.Outlined.Pause) { vm.pauseThread(t.id) }
            ThreadStatus.PAUSED, ThreadStatus.INTERRUPTED, ThreadStatus.BLOCKED, ThreadStatus.FAILED ->
                Chip("Resume", selected = true, icon = Icons.Outlined.PlayArrow) { vm.resumeThread(t.id) }
            else -> Unit
        }
        if (t.status == ThreadStatus.DONE) {
            Chip("Helpful", icon = Icons.Outlined.ThumbUp) { vm.rateThread(t.id, true) }
            Chip("Not helpful", icon = Icons.Outlined.ThumbDown) { vm.rateThread(t.id, false) }
        }
        if (t.isActive || t.status == ThreadStatus.PAUSED) Chip("Cancel", icon = Icons.Outlined.Close) { vm.cancelThread(t.id) }
        else Chip("Delete", icon = Icons.Outlined.Delete) { vm.deleteThread(t.id) }
        Chip(if (details) "Hide details" else "Details") { toggle() }
        if (t.usage.calls > 0 || vm.engine.receipts(t.id).isNotEmpty()) Chip("What was sent") { receipts = true }
    }
    if (receipts) ReceiptsDialog(vm, t) { receipts = false }
}

@Composable
private fun ThreadDetails(vm: LauncherViewModel, t: AgentThread) {
    val p = LocalPalette.current
    val checkpoints by produceState(0, t.id, t.updatedAt) { value = withContext(Dispatchers.IO) { vm.engine.checkpointsOf(t.id).size } }
    val lines = buildList {
        add("Models: " + t.models.entries.joinToString(" · ") { (role, m) -> "${role.lowercase()} ${m.substringAfter('|')}" }.ifEmpty { "none used yet" })
        if (t.pinned.isNotEmpty()) add("Pinned for this task: " + t.pinned.entries.joinToString { "${it.key.name.lowercase()} → ${it.value.model}" })
        if (t.usage.cachedTokens > 0) add("Prompt cache: ${t.usage.cachedTokens} of ${t.usage.inputTokens} input tokens were read from cache (billed at a fraction)")
        add("Usage: ${t.usage.calls} calls · ${t.usage.inputTokens} in / ${t.usage.outputTokens} out tokens · $" + "%.4f".format(t.usage.costUsd) +
            t.usage.byRole.entries.joinToString(prefix = " (", postfix = ")") { "${it.key.lowercase()} $" + "%.4f".format(it.value) }.takeIf { t.usage.byRole.isNotEmpty() }.orEmpty())
        add("Budget: up to $" + "%.2f".format(t.permissions.maxCostUsd) + " · privacy: " +
            (if (!t.permissions.privacy.cloudAllowed) "local only" else if (t.permissions.privacy.zdr) "zero data retention" else "no data collection"))
        add("Tools: " + t.permissions.tools.joinToString { it.label.lowercase() })
        add("Checkpoints: $checkpoints · ${if (t.permissions.autoResume) "resumes automatically after restarts" else "asks before resuming after restarts"}")
        if (t.dependsOn.isNotEmpty()) add("Waits for: " + t.dependsOn.joinToString { id -> vm.threads.firstOrNull { it.id == id }?.title ?: id })
        add("Origin: ${t.origin} · mode ${t.modeId}")
    }
    var picking by remember(t.id) { mutableStateOf<com.axiom.launcher.solis.cortex.CallRole?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(12.dp)) {
        lines.forEach { Text(it, style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(vertical = 2.dp)) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Worker model…") { picking = com.axiom.launcher.solis.cortex.CallRole.WORKER }
            Chip("Coordinator model…") { picking = com.axiom.launcher.solis.cortex.CallRole.COORDINATOR }
        }
    }
    picking?.let { role ->
        ModelPicker(vm, "${role.name.lowercase().replaceFirstChar { it.uppercase() }} for this task", t.pinned[role],
            onPick = { vm.pinThreadModel(t.id, role, it) }) { picking = null }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Entry(e: TranscriptEntry) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(18.dp)
    when (e.kind) {
        EntryKind.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (Netrunner) "$ ${e.text}" else e.text,
                style = lStyle(15.sp, color = if (Netrunner) p.accent else p.onAccent),
                modifier = Modifier.widthIn(max = 300.dp).clip(shape)
                    .background(if (Netrunner) p.surface else p.accent)
                    .let { if (Netrunner) it.border(1.dp, p.accent.copy(alpha = 0.5f), shape) else it }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        EntryKind.ASSISTANT -> SelectionContainer {
            Text(markdown(e.text), style = lStyle(15.sp).copy(lineHeight = 22.sp), modifier = Modifier.fillMaxWidth().padding(end = 24.dp))
        }
        EntryKind.TOOL -> Text(
            (if (Netrunner) "[exec] " else "⚙  ") + e.text,
            style = lStyle(12.sp, color = if (e.text.endsWith("✗")) p.danger else p.dim),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.surface).padding(horizontal = 10.dp, vertical = 5.dp),
        )
        EntryKind.ROUTE -> RouteEntry(e)
        EntryKind.STATUS -> Text(e.text, style = lStyle(12.sp, color = p.dim))
        EntryKind.ERROR -> Text((if (Netrunner) "[err] " else "") + e.text, style = lStyle(13.sp, color = p.danger))
    }
}

/** Why Solis picked this tier/model: one line, tap for the full explanation. */
@Composable
private fun RouteEntry(e: TranscriptEntry) {
    val p = LocalPalette.current
    var open by remember(e.seq) { mutableStateOf(false) }
    val explanation = remember(e.meta) {
        runCatching { JSONObject(e.meta ?: "{}").optJSONArray("explanation")?.let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = explanation.size > 1) { open = !open }.padding(vertical = 2.dp)) {
        Text((if (Netrunner) "// route " else "↳ ") + e.text, style = lStyle(11.sp, color = p.dim.copy(alpha = 0.85f)),
            maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
        if (open) explanation.drop(1).forEach { Text("   · $it", style = lStyle(11.sp, color = p.dim)) }
    }
}

@Composable
private fun LiveRow(s: LiveState) {
    val p = LocalPalette.current
    if (s.phase == LivePhase.STREAMING && s.text.isNotBlank()) {
        Text(markdown(s.text + " ▍"), style = lStyle(15.sp).copy(lineHeight = 22.sp), modifier = Modifier.fillMaxWidth().padding(end = 24.dp))
        return
    }
    val label = when (s.phase) {
        LivePhase.ASSESSING -> "Sizing up the request"
        LivePhase.PLANNING -> "Planning with the coordinator"
        LivePhase.THINKING -> "Thinking" + (s.model?.let { " · $it" } ?: "")
        LivePhase.STREAMING -> "Writing" + (s.model?.let { " · $it" } ?: "")
        LivePhase.TOOLS -> "Using the phone" + (s.step?.let { " · $it" } ?: "")
        LivePhase.VERIFYING -> "Checking the answer independently"
        LivePhase.COMBINING -> "Combining results"
    } + (s.step?.takeIf { s.phase != LivePhase.TOOLS }?.let { " — ${it.take(50)}" } ?: "")
    Text((if (Netrunner) "> " else "") + label + "…", style = lStyle(13.sp, color = p.dim.copy(alpha = 0.5f + 0.5f * blinkAlpha())))
}
