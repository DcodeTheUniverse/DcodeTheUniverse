package com.dcode.circleid.ui.screens

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.core.FaceStrictness
import com.dcode.circleid.graph
import com.dcode.circleid.security.BioResult
import com.dcode.circleid.security.Biometrics
import com.dcode.circleid.security.CircleKeystore
import com.dcode.circleid.ui.components.Caption
import com.dcode.circleid.ui.components.ListRow
import com.dcode.circleid.ui.components.MonoSwitch
import com.dcode.circleid.ui.components.Section
import com.dcode.circleid.ui.components.Staggered
import com.dcode.circleid.ui.components.TopBar
import com.dcode.circleid.ui.theme.Circle
import com.dcode.circleid.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val p = Circle.palette
    val g = LocalContext.current.graph
    val entries by g.history.entries.collectAsState()
    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        TopBar("History", onBack)
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Caption("Nothing yet.") }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.size, key = { entries[it].at }) { i ->
                val e = entries[i]
                Staggered(i.coerceAtMost(10), 40) {
                    Section {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.requester, style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
                                Text(
                                    e.status, style = MaterialTheme.typography.labelSmall,
                                    color = if (e.status == "VERIFIED") p.ink else if (e.status == "CANCELLED") p.inkFaint else p.danger,
                                )
                            }
                            Text(
                                "${e.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.at))}" +
                                    if (e.assurance > 0) " · AL${e.assurance}" else "",
                                style = MaterialTheme.typography.bodySmall, color = p.inkSoft,
                            )
                            if (e.factors.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(e.factors.joinToString("   "), style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
                            }
                            if (e.message.isNotBlank() && e.status != "VERIFIED") {
                                Text(e.message, style = MaterialTheme.typography.bodySmall, color = p.inkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val p = Circle.palette
    val g = LocalContext.current.graph
    val changes by g.prefs.changes.collectAsState()
    val apps = remember(changes) { g.prefs.pairedApps }
    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        TopBar("Paired apps", onBack)
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Caption("No apps are paired. Open Vault and choose \"Unlock with Circle ID\" to pair it.")
            }
            return
        }
        Section {
            apps.forEach { a ->
                ListRow(
                    a.name,
                    "${a.packageName}\nPaired ${DateFormat.getDateInstance().format(Date(a.pairedAt))}" +
                        if (a.lastUsed > 0) " · last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(a.lastUsed))}" else "",
                    Icons.Rounded.Apps,
                )
            }
        }
        Caption(
            "Each paired app holds only a sealed copy of its key. Circle ID releases the matching secret to that app — and only that app, verified by its signing certificate — after a successful multimodal check.",
            Modifier.padding(24.dp),
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onReset: () -> Unit) {
    val p = Circle.palette
    val context = LocalContext.current
    val activity = LocalActivity.current as FragmentActivity
    val g = context.graph
    val prefs = g.prefs
    val changes by prefs.changes.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    // Weakening the security policy requires a fresh fingerprint.
    fun guarded(weakens: Boolean, action: () -> Unit) {
        if (!weakens) { action(); return }
        scope.launch {
            val r = Biometrics.authenticate(activity, "Circle ID", "Confirm to lower a security setting", null)
            if (r is BioResult.Success) action()
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        TopBar("Settings", onBack)
        changes.let { } // recompose when preferences change

        Section("Appearance") {
            Column(Modifier.padding(18.dp)) {
                ListRowHeader(Icons.Rounded.DarkMode, "Theme")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = prefs.theme == m, onClick = { prefs.theme = m },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            colors = monoSegmented(),
                        ) { Text(m.name) }
                    }
                }
            }
        }

        Section("Factors") {
            ListRow("Fingerprint", "Class 3 · required · ${g.keystore.location(CircleKeystore.PROFILE_KEK).label}", Icons.Rounded.Fingerprint)
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                ListRowHeader(Icons.Rounded.Face, "Face match strictness")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FaceStrictness.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = prefs.strictness == s,
                            onClick = { guarded(s.threshold < prefs.strictness.threshold) { prefs.strictness = s } },
                            shape = SegmentedButtonDefaults.itemShape(i, FaceStrictness.entries.size),
                            colors = monoSegmented(),
                        ) { Text(s.label) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                ListRowHeader(Icons.Rounded.Tune, "Liveness challenges per check")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (1..4).forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = prefs.challengeCount == n,
                            onClick = { guarded(n < prefs.challengeCount) { prefs.challengeCount = n } },
                            shape = SegmentedButtonDefaults.itemShape(i, 4),
                            colors = monoSegmented(),
                        ) { Text("$n") }
                    }
                }
            }
            ListRow("Require pulse", "Adds a fingertip heartbeat check (rear camera + torch)", Icons.Rounded.Favorite, trailing = {
                MonoSwitch(prefs.requirePulse, { v -> guarded(!v) { prefs.requirePulse = v } })
            })
            ListRow(
                "Require vascular match",
                if (prefs.vascularEnrolled) "Experimental · uses your saved vein-light template" else "Experimental · save a template in Sensor Lab first",
                Icons.Rounded.Shield,
                trailing = { MonoSwitch(prefs.requireVascular, { v -> guarded(!v) { prefs.requireVascular = v } }, enabled = prefs.vascularEnrolled) },
            )
        }

        Section("Identity") {
            ListRow("Identity key", g.keystore.location(CircleKeystore.IDENTITY).label + " · ECDSA P-256, attested", Icons.Rounded.Shield)
            ListRow("Clear history", null, onClick = { g.history.clear(); info = "History cleared" })
            ListRow("Reset Circle ID", "Destroys keys, template and pairings", Icons.Rounded.DeleteForever, onClick = { confirmReset = true })
        }
        info?.let { Caption(it, Modifier.fillMaxWidth().padding(16.dp)) }
        Caption(
            "Circle ID ${com.dcode.circleid.BuildConfig.VERSION_NAME} · everything stays on this device",
            Modifier.fillMaxWidth().padding(24.dp),
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Circle ID?") },
            text = { Text("Your identity key, face template and all app pairings are destroyed. Paired apps will need their recovery method.", textAlign = TextAlign.Start) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = Biometrics.authenticate(activity, "Reset Circle ID", "Confirm it's you", null)
                        if (r is BioResult.Success) { g.resetEverything(); confirmReset = false; onReset() }
                    }
                }) { Text("Reset", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = p.ink) } },
            containerColor = p.background,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun ListRowHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.Icon(icon, null, tint = Circle.palette.ink)
        Text(title, style = MaterialTheme.typography.titleMedium, color = Circle.palette.ink)
    }
}

@Composable
private fun monoSegmented() = SegmentedButtonDefaults.colors(
    activeContainerColor = Circle.palette.ink,
    activeContentColor = Circle.palette.background,
    activeBorderColor = Circle.palette.ink,
    inactiveContainerColor = Circle.palette.background,
    inactiveContentColor = Circle.palette.ink,
    inactiveBorderColor = Circle.palette.hairline,
)
