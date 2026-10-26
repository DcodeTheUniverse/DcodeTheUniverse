package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.ChatMsg
import com.axiom.launcher.data.ChatRole
import com.axiom.launcher.ui.effects.blinkAlpha
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle

@Composable
fun ChatPage(vm: LauncherViewModel, active: Boolean) {
    val p = LocalPalette.current
    val voice = LocalVoice.current
    val listState = rememberLazyListState()
    val lastLen = vm.chat.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(vm.chat.size, lastLen) {
        if (vm.chat.isNotEmpty()) listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (Netrunner) "claude-link" else "Claude", style = lStyle(30.sp, FontWeight.Light))
                Text(
                    if (vm.effectiveEngine == "api") (if (Netrunner) "uplink: ${vm.model} · effort:${vm.effort}" else "${com.axiom.launcher.ai.ClaudeAgent.prettyModel(vm.model)} · in Axiom")
                    else (if (Netrunner) "uplink: claude.app (subscription)" else "via the Claude app · your subscription"),
                    style = lStyle(12.sp, color = p.dim),
                )
            }
            if (vm.effectiveEngine == "app") IconBtn(Icons.Outlined.OpenInNew, "Open Claude app") { vm.actions.askClaudeApp(null) }
            if (vm.chat.isNotEmpty()) IconBtn(Icons.Outlined.DeleteSweep, "Clear chat") { vm.clearChat() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.chat.isEmpty()) {
                EmptyChat(vm)
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Spacer(Modifier.height(10.dp)) }
                    items(vm.chat, key = { it.id }) { m -> Bubble(m) }
                    if (vm.busy && vm.chat.lastOrNull()?.streaming != true) {
                        item { Thinking() }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }
        }

        vm.pendingAttachment?.let { att ->
            Row(
                Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(p.surface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, null, tint = p.dim, modifier = Modifier.size(16.dp))
                Text(" ${att.name}", style = lStyle(13.sp), maxLines = 1, modifier = Modifier.widthIn(max = 240.dp))
                IconBtn(Icons.Outlined.Close, "Remove") { vm.pendingAttachment = null }
            }
        }

        InputBar(
            value = vm.chatDraft,
            onValue = { vm.chatDraft = it },
            placeholder = if (Netrunner) "query the construct…" else if (vm.effectiveEngine == "api") "Message Claude" else "Message (opens Claude app)",
            onSubmit = { vm.sendChat(vm.chatDraft) },
            singleLine = false,
            trailing = {
                if (vm.busy) IconBtn(Icons.Outlined.Stop, "Stop", p.danger) { vm.stopChat() }
                else {
                    if (vm.chatDraft.isBlank() && vm.pendingAttachment == null) IconBtn(Icons.Outlined.Mic, "Voice") { voice { vm.sendChat(it) } }
                    else IconBtn(Icons.AutoMirrored.Outlined.Send, "Send", p.accent) { vm.sendChat(vm.chatDraft) }
                }
            },
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun EmptyChat(vm: LauncherViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = p.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        if (vm.effectiveEngine == "api") {
            Text(
                if (Netrunner) "> construct online. it can drive this deck." else "Claude can act on this phone.",
                style = lStyle(20.sp),
            )
            Text(
                "Open apps, find & read files, set timers and alarms, draft texts and emails, navigate, play music, keep notes, search the web.",
                style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(18.dp))
            listOf(
                "Plan my evening around a 7pm gym session",
                "What's taking up my storage?",
                "Find my latest PDF and summarize it",
                "Wake me at 6:30 and set a 20 min nap timer",
                "Open something relaxing",
            ).forEach { s ->
                Text(
                    if (Netrunner) "> $s" else "→  $s",
                    style = lStyle(15.sp, color = p.fg),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.sendChat(s) }.padding(vertical = 8.dp),
                )
            }
        } else {
            Text("Your Claude, one swipe away.", style = lStyle(20.sp))
            Text(
                "Messages here open the official Claude app, so they run on your Claude subscription.\n\n" +
                    "Want Claude to act on the phone itself (open apps, set alarms, search files)? Add an Anthropic API key in Settings › Claude to enable the built-in agent.",
                style = lStyle(14.sp, color = p.dim), modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (vm.actions.isClaudeAppInstalled()) "Open Claude app" else "Get the Claude app", icon = Icons.Outlined.OpenInNew) { vm.actions.askClaudeApp(null) }
            }
        }
    }
}

@Composable
private fun Thinking() {
    val p = LocalPalette.current
    Text(if (Netrunner) "> processing █" else "Thinking…", style = lStyle(14.sp, color = p.dim.copy(alpha = 0.4f + 0.6f * blinkAlpha())))
}

@Composable
private fun Bubble(m: ChatMsg) {
    val p = LocalPalette.current
    val shape = if (Netrunner) RoundedCornerShape(2.dp) else RoundedCornerShape(18.dp)
    when (m.role) {
        ChatRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (Netrunner) "$ ${m.text}" else m.text,
                style = lStyle(15.sp, color = if (Netrunner) p.accent else p.onAccent),
                modifier = Modifier.widthIn(max = 300.dp).clip(shape)
                    .background(if (Netrunner) p.surface else p.accent)
                    .let { if (Netrunner) it.border(1.dp, p.accent.copy(alpha = 0.5f), shape) else it }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        ChatRole.ASSISTANT -> SelectionContainer {
            Text(
                markdown(m.text + if (m.streaming) " ▍" else ""),
                style = lStyle(15.sp).copy(lineHeight = 22.sp),
                modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
            )
        }
        ChatRole.TOOL -> Text(
            (if (Netrunner) "[exec] " else "⚙  ") + m.text,
            style = lStyle(12.sp, color = if (m.error) p.danger else p.dim),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.surface).padding(horizontal = 10.dp, vertical = 5.dp),
        )
        ChatRole.SYSTEM -> Text(
            (if (Netrunner) "[err] " else "") + m.text,
            style = lStyle(13.sp, color = if (m.error) p.danger else p.dim),
        )
    }
}
