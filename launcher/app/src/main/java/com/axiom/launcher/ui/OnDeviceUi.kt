package com.axiom.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.axiom.launcher.describeSpeed
import com.axiom.launcher.platform.InstalledModel
import com.axiom.launcher.platform.ModelDownload
import com.axiom.launcher.solis.ondevice.DeviceSpec
import com.axiom.launcher.solis.ondevice.Fit
import com.axiom.launcher.solis.ondevice.OnDeviceCatalog
import com.axiom.launcher.solis.ondevice.OnDeviceModel
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle

/** Settings › On-device AI: which model runs on this phone, getting it, and how Solis uses it. */
@Composable
fun ColumnScope.OnDeviceSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val od = vm.onDevice
    val spec = remember { od.deviceSpec() }
    val installed by od.installed.collectAsState()
    val download by remember { od.downloads() }.collectAsState(initial = null)
    val importing by od.importing.collectAsState()
    val status by od.client.status.collectAsState()
    val stats by od.stats.collectAsState()
    val recommended = remember(spec) { OnDeviceCatalog.recommend(spec) }
    val active = installed.firstOrNull { it.id == vm.prefs.onDeviceModel } ?: installed.firstOrNull()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it) } }

    PhoneCard(od.deviceLine(spec), spec, recommended)

    Text(
        "Solis can answer on the phone itself: nothing you ask leaves it, it works with no connection, and it costs nothing. " +
            "Cloud models stay better at hard work, so Solis still uses them for that unless you choose otherwise.",
        style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 10.dp),
    )

    SectionLabel("Models")
    OnDeviceCatalog.all.forEach { m ->
        val inst = installed.firstOrNull { it.id == m.id }
        ModelCard(vm, m, inst, isActive = inst != null && inst.id == active?.id, isRecommended = m.id == recommended?.id,
            download = download?.takeIf { it.modelId == m.id }, spec = spec)
    }
    installed.filter { it.catalog == null }.forEach { m -> ImportedRow(vm, m, isActive = m.id == active?.id) }
    importing?.let { (done, total) ->
        Text("Importing… " + if (total > 0) "${OnDeviceCatalog.size(done)} of ${OnDeviceCatalog.size(total)}" else OnDeviceCatalog.size(done),
            style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 8.dp))
        if (total > 0) Progress(done.toFloat() / total, Modifier.padding(top = 4.dp))
    }
    ActionRow(Icons.Outlined.FileOpen, "Import a model file", "A .litertlm file you already have (for example from Google AI Edge Gallery or a computer)") {
        pick.launch(arrayOf("*/*"))
    }

    if (installed.isEmpty()) return

    SectionLabel("Use the phone's model")
    SettingSwitch("On-device AI", if (vm.onDeviceOn) "Solis can run on ${active?.name ?: "the phone"}" else "Off: Solis only uses the providers you set up", vm.onDeviceOn) { vm.updateOnDeviceOn(it) }
    if (vm.onDeviceOn) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("When needed", vm.onDeviceUse == "needed") { vm.updateOnDeviceUse("needed") }
            Chip("Quick questions", vm.onDeviceUse == "quick") { vm.updateOnDeviceUse("quick") }
            Chip("Everything", vm.onDeviceUse == "always") { vm.updateOnDeviceUse("always") }
        }
        Text(
            when (vm.onDeviceUse) {
                "needed" -> "Only when the cloud can't be used: offline, in modes set to local-only privacy, or when cloud models fail."
                "always" -> "Everything the phone's model can handle runs here; cloud models only step in when it can't (very long tasks, files) or fails."
                else -> "Quick questions and phone actions run here; bigger tasks go to your cloud models. Offline and local-only modes always use the phone."
            } + if (vm.onDeviceUse != "needed") " When the phone is hot or the battery is low, Solis uses the cloud instead." else "",
            style = lStyle(12.sp, color = p.dim),
        )

        SectionLabel("Speed")
        val last = active?.let { stats[it.id] }
        Text(
            when (status.state) {
                "loading" -> "Loading ${active?.name} on the ${status.backend.uppercase()}…"
                "ready", "busy" -> "${active?.name} is loaded on the ${status.backend.uppercase()}" + if (status.state == "busy") " and answering" else ""
                "crashed" -> "The model stopped unexpectedly (${status.detail}); it starts again on the next question"
                else -> "Not loaded (it loads when Solis needs it and frees the memory after ${vm.onDeviceKeepMin} min)"
            },
            style = lStyle(14.sp),
        )
        (vm.speedResult ?: last?.let { "Last answer: " + describeSpeed(it.copy(loadMs = 0)).replaceFirstChar { c -> c.lowercase() } })?.let {
            Text(it, style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (vm.speedTesting) "Testing…" else "Test speed", selected = vm.speedTesting) { vm.runSpeedTest() }
            if (status.state == "ready") Chip("Free memory now") { od.client.unload() }
        }

        SectionLabel("Processor")
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Automatic", vm.onDeviceBackend == "auto") { vm.updateOnDeviceBackend("auto") }
            Chip("GPU", vm.onDeviceBackend == "gpu") { vm.updateOnDeviceBackend("gpu") }
            Chip("CPU", vm.onDeviceBackend == "cpu") { vm.updateOnDeviceBackend("cpu") }
        }
        val blocked = active?.let { od.gpuBlockReason(it.id) }
        Text(
            if (blocked != null) "The GPU didn't work with this model on this phone, so it runs on the CPU (slower). ($blocked)"
            else "Automatic uses the GPU, which is fastest, and falls back to the CPU if this phone's GPU can't run the model.",
            style = lStyle(12.sp, color = if (blocked != null) p.accent else p.dim),
        )
        if (blocked != null && active != null) Row(Modifier.padding(top = 6.dp)) { Chip("Try the GPU again") { vm.retryGpu(active.id) } }

        SectionLabel("Memory")
        Text("How much conversation the model can hold at once. More lets it handle longer chats and tool results, and uses more RAM.", style = lStyle(12.sp, color = p.dim))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(4096, 8192, 16384, 32768).filter { it <= (active?.maxContext ?: 32768) }.forEach { ctx ->
                Chip("${ctx / 1024}K", vm.onDeviceContext == ctx) { vm.updateOnDeviceContext(ctx) }
            }
        }
        Text("Keep it loaded after an answer for", style = lStyle(14.sp), modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 15, 30).forEach { m -> Chip("$m min", vm.onDeviceKeepMin == m) { vm.updateOnDeviceKeep(m) } }
        }
        if (active?.catalog?.thinking == true) {
            SettingSwitch("Think on hard questions", "Gemma reasons step by step before answering bigger tasks. Better answers, slower on a phone", vm.onDeviceThink) { vm.updateOnDeviceThink(it) }
        }
        SettingSwitch("Download on Wi-Fi only", "Models are several gigabytes", vm.onDeviceWifiOnly) { vm.updateOnDeviceWifi(it) }
    }
}

@Composable
private fun PhoneCard(line: String, spec: DeviceSpec, recommended: OnDeviceModel?) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
            .border(1.dp, p.faint, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(line, style = lStyle(16.sp, FontWeight.Medium))
        Text("${OnDeviceCatalog.size(spec.freeStorageBytes)} free storage", style = lStyle(12.sp, color = p.dim))
        Text(
            when {
                !spec.arm64 -> "This phone can't run on-device models (they need a 64-bit ARM processor)."
                recommended == null -> "This phone has too little memory for a useful on-device model."
                recommended == OnDeviceCatalog.all.first() -> "Can run ${recommended.name}, the most capable model Axiom offers on a phone."
                else -> "Best fit: ${recommended.name}. Bigger models would be slow or short of memory here."
            },
            style = lStyle(13.sp, color = p.accent), modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ModelCard(vm: LauncherViewModel, m: OnDeviceModel, inst: InstalledModel?, isActive: Boolean, isRecommended: Boolean, download: ModelDownload?, spec: DeviceSpec) {
    val p = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    val fit = OnDeviceCatalog.fit(m, spec, installed = inst != null)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isActive) p.accent else p.faint, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = lStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            when {
                isActive -> Tag("In use")
                inst != null -> Tag("On the phone")
                isRecommended -> Tag("Best for this phone")
            }
        }
        Text(m.blurb, style = lStyle(13.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp))
        Text(
            listOfNotNull(
                OnDeviceCatalog.size(m.sizeBytes),
                "text" + (if (m.vision) ", images" else "") + (if (m.audio) ", audio" else ""),
                if (m.tools) "uses Solis's tools" else null,
                if (m.thinking) "can think" else null,
                "Apache 2.0",
            ).joinToString(" · "),
            style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp),
        )
        if (fit == Fit.TIGHT && inst == null) Text("This phone has ${OnDeviceModelUi.ram(spec)} of RAM; ${m.name} wants about ${m.minRamGb.toInt()} GB and may be slow or stopped by Android.",
            style = lStyle(12.sp, color = p.accent), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        when {
            inst != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) Chip("Use this", selected = true) { vm.useOnDeviceModel(inst.id) }
                if (confirmDelete) {
                    Chip("Delete ${OnDeviceCatalog.size(inst.sizeBytes)}?", selected = true) { confirmDelete = false; vm.deleteModel(inst.id) }
                    Chip("Keep") { confirmDelete = false }
                } else Chip("Delete") { confirmDelete = true }
            }
            download != null && download.state in setOf("waiting", "running", "verifying") -> {
                Text(
                    when (download.state) {
                        "verifying" -> "Checking the file…"
                        "waiting" -> if (vm.onDeviceWifiOnly) "Waiting for Wi-Fi" + progressText(download) else "Waiting to start" + progressText(download)
                        else -> "Downloading" + progressText(download)
                    },
                    style = lStyle(13.sp),
                )
                Progress(if (download.total > 0) download.done.toFloat() / download.total else 0f, Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Cancel") { vm.cancelModelDownload() }
                    if (download.state == "waiting" && vm.onDeviceWifiOnly) Chip("Use mobile data") { vm.downloadModel(m, wifiOnly = false) }
                }
            }
            else -> {
                download?.takeIf { it.state == "failed" }?.let { Text("Last try failed: ${it.error ?: "unknown error"}", style = lStyle(12.sp, color = p.danger), modifier = Modifier.padding(bottom = 6.dp)) }
                val partial = download?.takeIf { it.done > 0 && it.state != "done" }
                when (fit) {
                    Fit.UNSUPPORTED -> Text("Not supported on this phone", style = lStyle(13.sp, color = p.dim))
                    Fit.NO_STORAGE -> Text("Needs ${OnDeviceCatalog.size(m.sizeBytes + OnDeviceCatalog.STORAGE_MARGIN)} free storage", style = lStyle(13.sp, color = p.dim))
                    else -> Chip(if (partial != null) "Resume download (${OnDeviceCatalog.size(partial.done)} done)" else "Download ${OnDeviceCatalog.size(m.sizeBytes)}", selected = isRecommended) { vm.downloadModel(m) }
                }
            }
        }
    }
}

@Composable
private fun ImportedRow(vm: LauncherViewModel, m: InstalledModel, isActive: Boolean) {
    val p = LocalPalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isActive) p.accent else p.faint, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = lStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            Tag(if (isActive) "In use" else "Imported")
        }
        Text("${OnDeviceCatalog.size(m.sizeBytes)} · " + (if (m.vision) "text and images" else "text") + " · answers only (no phone tools)",
            style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isActive) Chip("Use this", selected = true) { vm.useOnDeviceModel(m.id) }
            if (confirmDelete) {
                Chip("Delete ${OnDeviceCatalog.size(m.sizeBytes)}?", selected = true) { confirmDelete = false; vm.deleteModel(m.id) }
                Chip("Keep") { confirmDelete = false }
            } else Chip("Delete") { confirmDelete = true }
        }
    }
}

@Composable
private fun Tag(text: String) {
    val p = LocalPalette.current
    Text(text, style = lStyle(11.sp, FontWeight.Medium, p.onAccent),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(p.accent).padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun progressText(d: ModelDownload): String =
    if (d.total > 0 && d.done > 0) " · ${OnDeviceCatalog.size(d.done)} of ${OnDeviceCatalog.size(d.total)}" else ""

private object OnDeviceModelUi {
    fun ram(spec: DeviceSpec) = "${com.axiom.launcher.platform.OnDeviceManager.marketingRam(spec.totalRamBytes)} GB"
}
