package com.axiom.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.IconPackInfo
import com.axiom.launcher.data.IconPacks
import com.axiom.launcher.data.SettingsBackup
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Icon pack" row for Settings › Home screen; opens the chooser. */
@Composable
fun IconPackRow(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val label = remember(vm.iconPack) {
        if (vm.iconPack.isBlank()) "System icons"
        else runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(vm.iconPack, 0)).toString() }.getOrDefault(vm.iconPack)
    }
    ActionRow(Icons.Outlined.Palette, "Icon pack", label) { open = true }
    if (open) IconPackDialog(vm) { open = false }
}

@Composable
private fun IconPackDialog(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val packs by produceState<List<IconPackInfo>?>(null) { value = withContext(Dispatchers.IO) { IconPacks.installed(ctx) } }
    LDialog(onDismiss) {
        Text("Icon pack", style = lStyle(22.sp, FontWeight.Medium))
        Text("Packs in the ADW / Nova format. Apps a pack doesn't cover keep their own icon, on the pack's backdrop if it has one.",
            style = lStyle(12.sp, color = p.dim))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            PackRow(null, "System icons", vm.iconPack.isBlank()) { vm.setIconPack("", "System icons"); onDismiss() }
            when (val list = packs) {
                null -> Text("Looking for icon packs…", style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(vertical = 8.dp))
                else -> {
                    list.forEach { pack -> PackRow(pack.pkg, pack.label, vm.iconPack == pack.pkg) { vm.setIconPack(pack.pkg, pack.label); onDismiss() } }
                    if (list.isEmpty()) {
                        Text("No icon packs installed.", style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(vertical = 8.dp))
                        Chip("Find icon packs") { vm.actions.openUrl("https://play.google.com/store/search?q=icon%20pack&c=apps"); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackRow(pkg: String?, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 32.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = pkg?.let { withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getApplicationIcon(it).toBitmap(px, px).asImageBitmap() }.getOrNull() } }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp)) { icon?.let { Image(it, null, Modifier.size(32.dp)) } }
        Spacer(Modifier.width(14.dp))
        Text(label, style = lStyle(16.sp, if (selected) FontWeight.Medium else FontWeight.Normal), modifier = Modifier.weight(1f))
        if (selected) Text(if (Netrunner) "[x]" else "✓", style = lStyle(16.sp, FontWeight.Medium, p.accent))
    }
}

/** Settings › Backup: export to a file the user keeps, import from one. */
@Composable
fun BackupSection(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportSettings(it) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.readImport(it) } }
    SectionLabel("Backup")
    Text(
        "Export your layout, modes, notes and settings to a file and bring them back here or on another phone. API keys, the GitHub " +
            "token, Solis memory and widgets aren't included (Memory inspector exports memory).",
        style = lStyle(13.sp, color = p.dim),
    )
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Export settings") {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            runCatching { export.launch("axiom-settings-$day.json") }.onFailure { vm.say("No app can save files") }
        }
        Chip("Import…") { runCatching { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { vm.say("No app can open files") } }
    }
}

/** Confirms an import; Solis settings (caps, models, endpoints, routines) only come along when switched on. */
@Composable
fun ImportDialog(vm: LauncherViewModel, parsed: SettingsBackup.Parsed) {
    val p = LocalPalette.current
    var includeSolis by remember { mutableStateOf(false) }
    val layout = parsed.count(SettingsBackup.Group.LAYOUT)
    val solis = parsed.count(SettingsBackup.Group.SOLIS)
    LDialog({ vm.importPreview = null }) {
        Text("Import settings?", style = lStyle(22.sp, FontWeight.Medium))
        Text(
            listOfNotNull(parsed.appVersion.takeIf { it.isNotBlank() }?.let { "Axiom $it" },
                parsed.exported.takeIf { it > 0 }?.let { "exported " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it)) })
                .joinToString(" · "),
            style = lStyle(12.sp, color = p.dim),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (layout == 0) "No layout settings in this file."
            else "$layout layout setting${if (layout == 1) "" else "s"}: theme, home and hidden apps, names, modes and notes. " +
                "Notes are added to yours; the rest replaces what's set now.",
            style = lStyle(14.sp),
        )
        if (solis > 0) SettingSwitch(
            "Also import $solis Solis setting${if (solis == 1) "" else "s"}",
            "Spending caps, models, local runtime address, routines and memory sharing. Only for your own export.",
            includeSolis,
        ) { includeSolis = it }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Import", selected = true) { vm.applyImport(includeSolis) }
            Chip("Cancel") { vm.importPreview = null }
        }
    }
}
