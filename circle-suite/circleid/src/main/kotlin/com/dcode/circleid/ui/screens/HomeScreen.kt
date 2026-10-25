package com.dcode.circleid.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dcode.circleid.graph
import com.dcode.circleid.ui.components.BreathingRings
import com.dcode.circleid.ui.components.ListRow
import com.dcode.circleid.ui.components.PillButton
import com.dcode.circleid.ui.components.Section
import com.dcode.circleid.ui.components.Staggered
import com.dcode.circleid.ui.theme.Circle
import com.dcode.circleid.ui.theme.MonoStyle
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(onVerify: () -> Unit, onLab: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit, onApps: () -> Unit) {
    val p = Circle.palette
    val g = LocalContext.current.graph
    val changes by g.prefs.changes.collectAsState()
    val history by g.history.entries.collectAsState()
    val circleId = remember(changes) { g.prefs.circleId.orEmpty() }
    val paired = remember(changes) { g.prefs.pairedApps }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val heroScale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "hero")

    Column(Modifier.fillMaxSize().background(p.background).verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("circle", style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = p.ink) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(330.dp).scale(heroScale).clickable(interaction, indication = null, onClick = onVerify),
            contentAlignment = Alignment.Center,
        ) {
            BreathingRings(Modifier.size(300.dp), seed = circleId)
        }
        Text("YOUR CIRCLE ID", style = MaterialTheme.typography.labelSmall, color = p.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(6.dp))
        Text(circleId, style = MaterialTheme.typography.titleLarge.merge(MonoStyle), color = p.ink, modifier = Modifier.align(Alignment.CenterHorizontally), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PillButton("Verify it's me", onClick = onVerify, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth())
        Spacer(Modifier.height(24.dp))

        Staggered(0) {
            Section("Status") {
                val last = history.firstOrNull { it.kind != "ENROLL" }
                ListRow(
                    title = if (last == null) "No verifications yet" else "${if (last.status == "VERIFIED") "Verified" else last.status.lowercase().replaceFirstChar { it.uppercase() }} · ${last.requester}",
                    subtitle = last?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.at)) } ?: "Tap the circle to run a full multimodal check",
                    icon = Icons.Rounded.History, onClick = onHistory,
                )
                ListRow(
                    title = "Paired apps",
                    subtitle = if (paired.isEmpty()) "None yet — open Vault to pair" else paired.joinToString { it.name },
                    icon = Icons.Rounded.Apps, onClick = onApps,
                    trailing = { CountBadge(paired.size) },
                )
            }
        }
        Staggered(1) {
            Section("Research") {
                ListRow("Sensor Lab", "Ultrasonic sensor probe, pulse & vein-light experiments", Icons.Rounded.Science, onClick = onLab)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CountBadge(n: Int) {
    val p = Circle.palette
    Box(Modifier.size(28.dp).clip(CircleShape).background(p.ink), contentAlignment = Alignment.Center) {
        Text("$n", color = p.background, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val p = Circle.palette
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        BreathingRings(Modifier.size(260.dp), seed = "CIRCLE")
        Spacer(Modifier.height(40.dp))
        Text("Circle ID", style = MaterialTheme.typography.displayLarge, color = p.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "Proof that you are you.\nFingerprint, face, liveness and pulse — fused on-device, sealed in secure hardware.",
            style = MaterialTheme.typography.bodyLarge, color = p.inkSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("No cloud", "No accounts", "No tracking").forEach {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.inkFaint)
            }
        }
        Spacer(Modifier.height(20.dp))
        PillButton("Set up Circle ID", onClick = onStart, modifier = Modifier.fillMaxWidth())
    }
}
