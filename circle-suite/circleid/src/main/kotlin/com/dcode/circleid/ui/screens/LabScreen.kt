package com.dcode.circleid.ui.screens

import androidx.activity.compose.LocalActivity
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.core.PulseAnalyzer
import com.dcode.circle.core.VascularSignature
import com.dcode.circleid.face.CameraPermissionGate
import com.dcode.circleid.face.TorchCamera
import com.dcode.circleid.graph
import com.dcode.circleid.lab.ProbeResult
import com.dcode.circleid.lab.SensorProbe
import com.dcode.circleid.lab.Verdict
import com.dcode.circleid.security.BioResult
import com.dcode.circleid.security.Biometrics
import com.dcode.circleid.ui.components.Caption
import com.dcode.circleid.ui.components.PillButton
import com.dcode.circleid.ui.components.ScanSweep
import com.dcode.circleid.ui.components.Section
import com.dcode.circleid.ui.components.TopBar
import com.dcode.circleid.ui.components.Waveform
import com.dcode.circleid.ui.theme.Circle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LabScreen(onBack: () -> Unit) {
    val p = Circle.palette
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        TopBar("Sensor Lab", onBack)
        SecondaryTabRow(
            selectedTabIndex = tab,
            containerColor = p.background,
            contentColor = p.ink,
            indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = false), color = p.ink) },
            divider = {},
        ) {
            listOf("Ultrasonic probe", "Pulse & vein light").forEachIndexed { i, t ->
                Tab(tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelLarge) })
            }
        }
        when (tab) {
            0 -> ProbeTab()
            else -> PulseTab()
        }
    }
}

@Composable
private fun ProbeTab() {
    val p = Circle.palette
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<ProbeResult>?>(null) }
    LaunchedEffect(Unit) { results = withContext(Dispatchers.IO) { SensorProbe.run(context) } }
    val r = results
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ScanSweep(Modifier.size(72.dp)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Finding") {
                Text(SensorProbe.CONCLUSION, style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.padding(18.dp))
            }
        }
        r.groupBy { it.group }.forEach { (group, items) ->
            item(key = group) {
                Section(group) {
                    items.forEach { ProbeRow(it) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ProbeRow(r: ProbeResult) {
    val p = Circle.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        val (label, color) = when (r.verdict) {
            Verdict.Available -> "OPEN" to p.ink
            Verdict.Blocked -> "BLOCKED" to p.danger
            Verdict.Absent -> "NONE" to p.inkFaint
            Verdict.Info -> "INFO" to p.inkSoft
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.width(64.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
        }
    }
}

@Composable
private fun PulseTab() {
    val p = Circle.palette
    val context = LocalContext.current
    val activity = LocalActivity.current as FragmentActivity
    val g = context.graph
    val scope = rememberCoroutineScope()
    val analyzer = remember { PulseAnalyzer() }
    var state by remember { mutableStateOf<PulseAnalyzer.State?>(null) }
    val patches = remember { ArrayList<FloatArray>() }
    val captures = remember { mutableStateListOf<FloatArray>() }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastUi by remember { mutableStateOf(0L) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("How it works") {
                Caption(
                    "Cover the rear camera and flash with a fingertip. The torch shines through the tissue; the camera sees the light " +
                        "dim with every heartbeat as blood volume rises (photoplethysmography). Averaging frames also reveals a faint " +
                        "light-absorption texture — an experimental, low-resolution cousin of a vein scan.",
                    Modifier.padding(18.dp),
                )
            }
        }
        item {
            Section("Live signal") {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (running) {
                        CameraPermissionGate("The rear camera and torch are used to measure your pulse.") {
                            TorchCamera(onFrame = { f ->
                                val s = analyzer.add(f.timestampNs, f.r, f.g, f.b)
                                synchronized(patches) {
                                    if (s.fingerPresent) { if (patches.size < 240) patches.add(f.patch) } else patches.clear()
                                }
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUi > 66) { lastUi = now; state = s }
                            }, modifier = Modifier.size(1.dp))
                        }
                    }
                    val s = state
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(s?.bpm?.toInt()?.toString() ?: "—", style = MaterialTheme.typography.displayLarge, color = p.ink)
                        Spacer(Modifier.width(8.dp))
                        Text("BPM", style = MaterialTheme.typography.labelMedium, color = p.inkSoft, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Waveform(s?.waveform ?: FloatArray(0), Modifier.fillMaxWidth().height(72.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("Finger", if (s?.fingerPresent == true) "on lens" else "—")
                        Stat("Quality", s?.let { "${(it.quality * 100).toInt()}%" } ?: "—")
                        Stat("Perfusion", s?.let { "%.2f%%".format(it.perfusionIndex) } ?: "—")
                        Stat("Lock", if (s?.locked == true) "yes" else "no")
                    }
                    Spacer(Modifier.height(16.dp))
                    PillButton(if (running) "Stop" else "Start torch", onClick = { running = !running; analyzer.reset(); state = null }, filled = !running)
                }
            }
        }
        item {
            Section("Vein-light texture (experimental)") {
                Column(Modifier.padding(18.dp)) {
                    Caption("Capture the same finger twice, then a different finger, and compare. Scores near 1 mean the light patterns match.")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Capture", enabled = running && (state?.fingerPresent == true), onClick = {
                            val frames = synchronized(patches) { patches.toList() }
                            if (frames.size < 30) {
                                status = "Hold the finger still a little longer (${frames.size}/30 frames)"
                            } else {
                                captures.add(VascularSignature.build(frames)); status = "Captured sample ${captures.size}"
                            }
                        })
                        PillButton("Clear", filled = false, onClick = { captures.clear(); status = null })
                    }
                    AnimatedVisibility(status != null) { Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = p.inkSoft, modifier = Modifier.padding(top = 8.dp)) }
                    if (captures.size >= 2) {
                        Spacer(Modifier.height(12.dp))
                        for (i in 0 until captures.size) for (j in i + 1 until captures.size) {
                            val sim = VascularSignature.similarity(captures[i], captures[j])
                            Text("Sample ${i + 1} ↔ ${j + 1}:  ${"%.2f".format(sim)}", style = MaterialTheme.typography.bodyMedium, color = p.ink)
                        }
                    }
                    if (captures.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        PillButton("Save last as my vascular template", filled = false, onClick = {
                            scope.launch {
                                val cipher = runCatching { g.profiles.unlockCipher() }.getOrNull() ?: return@launch
                                val r = Biometrics.authenticate(activity, "Circle ID", "Confirm to update your profile", BiometricPrompt.CryptoObject(cipher))
                                if (r is BioResult.Success) {
                                    val saved = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val session = g.profiles.open(r.crypto!!.cipher!!)
                                            g.profiles.update(session) { it.copy(vascular = captures.last().toList()) }
                                            session.close()
                                        }
                                    }
                                    if (saved.isSuccess) {
                                        g.prefs.vascularEnrolled = true
                                        status = "Saved. Enable it under Settings → Factors."
                                    } else {
                                        status = "Couldn't save: ${saved.exceptionOrNull()?.message}"
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = Circle.palette.ink)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Circle.palette.inkSoft)
    }
}
