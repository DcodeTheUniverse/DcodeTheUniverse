package com.dcode.circleid.verify

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.core.Challenge
import com.dcode.circle.core.Fusion
import com.dcode.circle.core.LivenessSession
import com.dcode.circle.protocol.RequestKind
import com.dcode.circleid.face.CameraPermissionGate
import com.dcode.circleid.face.FaceCamera
import com.dcode.circleid.face.TorchCamera
import com.dcode.circleid.ui.components.BreathingRings
import com.dcode.circleid.ui.components.Caption
import com.dcode.circleid.ui.components.FactorChip
import com.dcode.circleid.ui.components.PillButton
import com.dcode.circleid.ui.components.ProgressRing
import com.dcode.circleid.ui.components.ResultMark
import com.dcode.circleid.ui.components.ScanSweep
import com.dcode.circleid.ui.components.Section
import com.dcode.circleid.ui.components.Staggered
import com.dcode.circleid.ui.components.Waveform
import com.dcode.circleid.ui.theme.Circle

@Composable
fun VerifyScreen(vm: VerifyViewModel, onDone: () -> Unit) {
    val p = Circle.palette
    BackHandler { vm.cancel(); onDone() }
    Box(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(
            targetState = vm.step,
            contentKey = { it::class },
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(380), initialScale = 0.96f)) togetherWith fadeOut(tween(200)) },
            label = "verify-step",
        ) { step ->
            when (step) {
                is VStep.Consent -> ConsentStep(step.req, vm)
                VStep.Fingerprint -> FingerprintStep(vm)
                VStep.Face -> FaceStep(vm, onDone)
                VStep.Pulse -> PulseStep(vm, onDone)
                VStep.Working -> WorkingStep()
                is VStep.Result -> ResultStep(step, onDone)
            }
        }
    }
}

@Composable
private fun StepLabel(index: Int, total: Int, name: String) {
    Text(
        "STEP $index OF $total · ${name.uppercase()}",
        style = MaterialTheme.typography.labelSmall,
        color = Circle.palette.inkSoft,
        modifier = Modifier.padding(top = 24.dp),
    )
}

@Composable
private fun ConsentStep(req: ExternalRequest, vm: VerifyViewModel) {
    val p = Circle.palette
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(96.dp).clip(CircleShape).border(1.5.dp, p.ink, CircleShape), contentAlignment = Alignment.Center) {
            Text(req.callerName.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, color = p.ink)
        }
        Spacer(Modifier.height(28.dp))
        val pair = req.request.kind == RequestKind.PAIR
        Text(
            if (pair) "Pair ${req.callerName} with Circle ID?" else "${req.callerName} wants to confirm it's you",
            style = MaterialTheme.typography.headlineMedium, color = p.ink, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Caption(
            if (pair) "After pairing, ${req.callerName} can only unlock when Circle ID verifies your fingerprint and face. A secret only Circle ID can release protects it."
            else req.request.reason.ifBlank { "Verify with fingerprint and face." },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "${req.callerPackage}\ncert ${req.callerCert.take(16).chunked(4).joinToString(" ")}…",
            style = MaterialTheme.typography.bodySmall, color = p.inkFaint, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        PillButton(if (pair) "Pair and verify" else "Verify it's me", onClick = { vm.consent(true) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Fingerprint)
        Spacer(Modifier.height(12.dp))
        PillButton("Deny", onClick = { vm.consent(false) }, modifier = Modifier.fillMaxWidth(), filled = false)
    }
}

@Composable
private fun FingerprintStep(vm: VerifyViewModel) {
    val activity = LocalActivity.current as FragmentActivity
    LaunchedEffect(Unit) { vm.runFingerprint(activity) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepLabel(1, vm.totalSteps, "Fingerprint")
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            BreathingRings(Modifier.fillMaxSize())
            Icon(Icons.Rounded.Fingerprint, null, tint = Circle.palette.ink, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("Touch the sensor", style = MaterialTheme.typography.headlineMedium, color = Circle.palette.ink)
        Spacer(Modifier.height(8.dp))
        Caption("Your fingerprint is matched inside secure hardware. A match releases Circle ID's key — nothing else can.")
        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
private fun FaceStep(vm: VerifyViewModel, onDone: () -> Unit) {
    val p = Circle.palette
    val state by vm.liveness.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepLabel(2, vm.totalSteps, "Face & liveness")
        Spacer(Modifier.weight(0.6f))
        CameraPermissionGate("Circle ID compares your face with your enrolled template, entirely on this device. Frames are never saved.") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    val analyzer = remember { vm.newFaceAnalyzer() }
                    FaceCamera(analyzer, Modifier.size(272.dp).clip(CircleShape))
                    ProgressRing(state?.overallProgress ?: 0f, Modifier.fillMaxSize(), width = 5.dp)
                    ChallengeArrow(state)
                }
                Spacer(Modifier.height(28.dp))
                AnimatedContent(state?.hint ?: "Center your face", transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "hint") { hint ->
                    Text(hint, style = MaterialTheme.typography.headlineSmall, color = p.ink, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.challenges.forEachIndexed { i, c ->
                        val done = (state?.index ?: 0) > i || state?.phase == LivenessSession.Phase.Done
                        val current = state?.index == i && state?.phase == LivenessSession.Phase.Challenge
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (done) p.ink else p.background)
                                .border(1.dp, if (current || done) p.ink else p.hairline, RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(c.shortLabel, style = MaterialTheme.typography.labelLarge, color = if (done) p.background else if (current) p.ink else p.inkFaint)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PillButton("Cancel", onClick = { vm.cancel(); onDone() }, filled = false, modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun ChallengeArrow(state: LivenessSession.State?) {
    if (state?.phase != LivenessSession.Phase.Challenge) return
    val icon = when (state.challenge) {
        // The preview is mirrored, so "your left" is on the left of the screen.
        Challenge.TurnLeft -> Icons.AutoMirrored.Rounded.ArrowBack
        Challenge.TurnRight -> Icons.AutoMirrored.Rounded.ArrowForward
        Challenge.LookUp -> Icons.Rounded.ArrowUpward
        else -> return
    }
    val align = when (state.challenge) {
        Challenge.TurnLeft -> Alignment.CenterStart
        Challenge.TurnRight -> Alignment.CenterEnd
        else -> Alignment.TopCenter
    }
    Box(Modifier.fillMaxSize(), contentAlignment = align) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Circle.palette.ink), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Circle.palette.background)
        }
    }
}

@Composable
private fun PulseStep(vm: VerifyViewModel, onDone: () -> Unit) {
    val p = Circle.palette
    val s by vm.pulse.collectAsState()
    CameraPermissionGate("The rear camera and flash measure the pulse in your fingertip.") {
        TorchCamera(onFrame = vm::onPulseFrame, modifier = Modifier.size(1.dp))
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepLabel(3, vm.totalSteps, "Pulse")
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                ProgressRing(((s?.elapsedSeconds ?: 0.0) / 8.0).toFloat(), Modifier.fillMaxSize(), width = 5.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Favorite, null, tint = if (s?.locked == true) p.danger else p.ink, modifier = Modifier.size(36.dp))
                    Text(s?.bpm?.toInt()?.toString() ?: "—", style = MaterialTheme.typography.displayLarge, color = p.ink)
                    Text("BPM", style = MaterialTheme.typography.labelMedium, color = p.inkSoft)
                }
            }
            Spacer(Modifier.height(24.dp))
            Waveform(s?.waveform ?: FloatArray(0), Modifier.fillMaxWidth().height(64.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                if (s?.fingerPresent == true) "Hold still…" else "Cover the rear camera and flash with your fingertip",
                style = MaterialTheme.typography.headlineSmall, color = p.ink, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Caption("Light passing through your finger pulses with your heartbeat — proof of living tissue.")
            Spacer(Modifier.weight(1f))
            PillButton("Cancel", onClick = { vm.cancel(); onDone() }, filled = false, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun WorkingStep() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ScanSweep(Modifier.size(120.dp))
    }
}

@Composable
private fun ResultStep(r: VStep.Result, onDone: () -> Unit) {
    val p = Circle.palette
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        ResultMark(r.success, Modifier.size(132.dp))
        Spacer(Modifier.height(28.dp))
        Text(r.title, style = MaterialTheme.typography.displayMedium, color = p.ink)
        Spacer(Modifier.height(8.dp))
        Caption(r.message, Modifier.padding(horizontal = 32.dp))
        val d = r.decision
        if (d != null && d.verified) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Metric("AL${d.assurance}", "Assurance")
                Metric(Fusion.formatOdds(d.impostorOdds).removePrefix("1 in "), "Impostor odds (est.)")
            }
        }
        if (r.evidence.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Section("Signals") {
                r.evidence.forEachIndexed { i, e ->
                    Staggered(i) { FactorChip(e.label + if (!e.required) " · advisory" else "", e.passed, e.detail) }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        PillButton("Done", onClick = onDone, modifier = Modifier.padding(horizontal = 28.dp).fillMaxWidth())
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Circle.palette.ink, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Circle.palette.inkSoft)
    }
}
