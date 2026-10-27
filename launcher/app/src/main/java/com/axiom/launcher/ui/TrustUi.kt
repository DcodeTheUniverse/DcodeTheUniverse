package com.axiom.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.Receipt
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "What was sent": every model call this task made, to whom, what it carried, what was hidden, and what it cost. */
@Composable
fun ReceiptsDialog(vm: LauncherViewModel, t: AgentThread, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val list = remember(t.id, t.updatedAt) { vm.engine.receipts(t.id) }
    var showRequest by remember { mutableStateOf(false) }
    LDialog(onDismiss) {
        Text("What was sent", style = lStyle(22.sp, FontWeight.Medium))
        Text("“${t.title}” · ${list.size} call${if (list.size == 1) "" else "s"} since Axiom started. Receipts stay in memory and are never uploaded.",
            style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (list.isEmpty()) Text("No calls recorded since Axiom started (earlier ones aren't kept).", style = lStyle(13.sp, color = p.dim))
            list.forEach { r -> ReceiptRow(r) }
            if (showRequest) {
                val text = remember(t.id, t.updatedAt) { vm.engine.lastRequest(t.id) }
                Spacer(Modifier.height(8.dp))
                Text("Latest request, exactly as sent", style = lStyle(13.sp, FontWeight.Medium))
                SelectionContainer {
                    Text(text ?: "Not available.", style = lStyle(11.sp).copy(fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.surface).padding(8.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isNotEmpty()) Chip(if (showRequest) "Hide request" else "Show latest request") { showRequest = !showRequest }
            Chip("Close", selected = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun ReceiptRow(r: Receipt) {
    val p = LocalPalette.current
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.at))
    val carried = buildList {
        add("${r.messages} message${if (r.messages == 1) "" else "s"}")
        if (r.images > 0) add("${r.images} image${if (r.images == 1) "" else "s"}")
        if (r.documents > 0) add("${r.documents} file${if (r.documents == 1) "" else "s"}")
        if (r.tools > 0) add("${r.tools} tools offered")
    }.joinToString(", ")
    val tokens = if (r.inputTokens != null) "${r.inputTokens} in / ${r.outputTokens ?: 0} out tokens" + (if (r.cachedTokens > 0) " (${r.cachedTokens} cached)" else "")
        else "~${r.estInputTokens} tokens in"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$time · ${r.role.name.lowercase()} → ${r.provider}" + if (r.onDevice) "" else if (r.local) " (on your network)" else "", style = lStyle(13.sp, FontWeight.Medium))
        Text(r.model, style = lStyle(12.sp, color = p.dim).copy(fontFamily = FontFamily.Monospace))
        Text("$carried · $tokens" + (r.costUsd?.let { " · ${usd(it)}" } ?: "") + if (!r.ok) " · failed" else "", style = lStyle(12.sp, color = p.dim))
        val hidden = r.redacted.entries.joinToString { "${it.value} ${it.key}${if (it.value == 1) "" else "s"}" }
        when {
            r.onDevice -> Text("Nothing left the phone", style = lStyle(12.sp, color = p.accent))
            r.local -> Unit
            hidden.isNotEmpty() -> Text("Hidden: $hidden" + if (r.images + r.documents > 0) " (images and files went as they are)" else "", style = lStyle(12.sp, color = p.accent))
            r.zeroRetention -> Text("Zero-retention endpoint", style = lStyle(12.sp, color = p.dim))
        }
    }
}

/** Shown instead of Solis, memory, tasks or settings while the biometric lock is on and not yet unlocked. */
@Composable
fun LockedPanel(vm: LauncherViewModel, what: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize().background(p.bg).blockTouches(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.Fingerprint, null, tint = p.accent, modifier = Modifier.padding(bottom = 12.dp))
            Text(if (Netrunner) "access denied: $what" else "$what is locked", style = lStyle(20.sp, FontWeight.Medium))
            Text("Unlock with your fingerprint, face or screen lock.", style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            Chip("Unlock", selected = true) { vm.unlock(ctx) }
        }
    }
}
