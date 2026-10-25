package com.dcode.circleid.enroll

import androidx.activity.compose.LocalActivity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dcode.circle.core.EnrollmentPlan
import com.dcode.circle.core.FaceTemplate
import com.dcode.circleid.CircleApp
import com.dcode.circleid.face.CameraPermissionGate
import com.dcode.circleid.face.FaceAnalyzer
import com.dcode.circleid.face.FaceCamera
import com.dcode.circleid.security.BioResult
import com.dcode.circleid.security.Biometrics
import com.dcode.circleid.security.HistoryEntry
import com.dcode.circleid.security.Profile
import com.dcode.circleid.ui.components.BreathingRings
import com.dcode.circleid.ui.components.Caption
import com.dcode.circleid.ui.components.PillButton
import com.dcode.circleid.ui.components.ProgressRing
import com.dcode.circleid.ui.components.ResultMark
import com.dcode.circleid.ui.theme.Circle
import com.dcode.crypto.EcSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EnrollStep { Intro, Face, Seal, Done }

class EnrollViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as CircleApp).graph
    var step by mutableStateOf(EnrollStep.Intro)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val plan = EnrollmentPlan()
    private val _progress = MutableStateFlow(0f to "Center your face in the circle")
    val progress = _progress.asStateFlow()
    private val _ticks = MutableStateFlow(0)
    val ticks = _ticks.asStateFlow()

    fun startFace() {
        if (!Biometrics.strongAvailable(getApplication())) {
            error = Biometrics.describe(Biometrics.strongStatus(getApplication())) + ". Add a fingerprint in system settings first."
            return
        }
        error = null
        plan.reset()
        _progress.value = 0f to (plan.currentPose?.instruction ?: "")
        step = EnrollStep.Face
    }

    private val embedder = runCatching { graph.embedder }.onFailure {
        error = "The on-device face model failed to load: ${it.message}"
    }.getOrNull()

    fun analyzer() = FaceAnalyzer(embedder, onFrame = { f ->
        val e = f.embedding
        if (plan.done) return@FaceAnalyzer
        if (f.observation == null) {
            _progress.value = plan.progress to "Center your face in the circle"
            return@FaceAnalyzer
        }
        if (f.observation.faceCount > 1) {
            _progress.value = plan.progress to "Only one face, please"
            return@FaceAnalyzer
        }
        if (e != null && plan.offer(e, f.yaw, f.pitch)) _ticks.value++
        _progress.value = plan.progress to (plan.currentPose?.instruction ?: "Perfect")
        if (plan.done) viewModelScope.launch(Dispatchers.Main) { onFaceDone() }
    })

    private fun onFaceDone() {
        val consistency = FaceTemplate(plan.samples).consistency()
        if (consistency < 0.5f) {
            error = "Those captures didn't agree with each other (lighting or another face?). Let's try again in even light."
            plan.reset()
            step = EnrollStep.Intro
            return
        }
        step = EnrollStep.Seal
    }

    suspend fun seal(activity: FragmentActivity) {
        val cipher = try {
            graph.profiles.sealCipher()
        } catch (e: Exception) {
            error = "Couldn't create a hardware key: ${e.message}"
            return
        }
        when (val r = Biometrics.authenticate(activity, "Seal your Circle ID", "Touch the fingerprint sensor", BiometricPrompt.CryptoObject(cipher))) {
            is BioResult.Success -> {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        graph.keystore.ensureIdentityKey()
                        val profile = Profile(
                            createdAt = System.currentTimeMillis(),
                            faceSamples = plan.samples.map { it.toList() },
                            yawSign = plan.yawSign ?: 1f,
                            pitchSign = plan.pitchSign ?: 1f,
                        )
                        graph.profiles.seal(r.crypto!!.cipher!!, profile).close()
                        graph.prefs.circleId = EcSignatures.fingerprint(graph.keystore.identityPublicKey())
                        graph.prefs.enrolledAt = profile.createdAt
                        graph.history.add(HistoryEntry(profile.createdAt, "Circle ID", "ENROLL", "VERIFIED", 0, listOf("Fingerprint: ✓", "Face (${plan.samples.size} poses): ✓")))
                    }
                }
                if (ok.isSuccess) step = EnrollStep.Done else error = "Sealing failed: ${ok.exceptionOrNull()?.message}"
            }
            BioResult.Cancelled -> error = "Fingerprint is required to seal your identity."
            is BioResult.Error -> error = r.message
        }
    }
}

@Composable
fun EnrollScreen(onFinished: () -> Unit, vm: EnrollViewModel = viewModel()) {
    val p = Circle.palette
    Box(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(vm.step, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "enroll") { step ->
            when (step) {
                EnrollStep.Intro -> IntroStep(vm)
                EnrollStep.Face -> FaceCaptureStep(vm)
                EnrollStep.Seal -> SealStep(vm)
                EnrollStep.Done -> DoneStep(onFinished)
            }
        }
    }
}

@Composable
private fun IntroStep(vm: EnrollViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            BreathingRings(Modifier.fillMaxSize())
            Icon(Icons.Rounded.Face, null, tint = Circle.palette.ink, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("Teach Circle your face", style = MaterialTheme.typography.headlineLarge, color = Circle.palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Caption("We'll capture a few angles, then seal everything with your fingerprint. Your face template is encrypted under a key that only a fingerprint match can release, and it never leaves this phone.")
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Circle.palette.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (it.contains("fingerprint", ignoreCase = true) && it.contains("settings")) {
                Spacer(Modifier.height(8.dp))
                PillButton("Open security settings", onClick = {
                    context.startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, filled = false)
            }
        }
        Spacer(Modifier.weight(1f))
        PillButton("Begin", onClick = vm::startFace, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FaceCaptureStep(vm: EnrollViewModel) {
    val p = Circle.palette
    val prog by vm.progress.collectAsState()
    val ticks by vm.ticks.collectAsState()
    val haptics = LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(ticks) { if (ticks > 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FACE ENROLMENT", style = MaterialTheme.typography.labelSmall, color = p.inkSoft, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(0.6f))
        CameraPermissionGate("Circle ID builds your face template on this device. Frames are never saved.") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    val analyzer = remember { vm.analyzer() }
                    FaceCamera(analyzer, Modifier.size(272.dp).clip(CircleShape))
                    ProgressRing(prog.first, Modifier.fillMaxSize(), width = 5.dp)
                }
                Spacer(Modifier.height(28.dp))
                AnimatedContent(prog.second, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pose") {
                    Text(it, style = MaterialTheme.typography.headlineSmall, color = p.ink, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Caption("Even, front-facing light works best. Glasses are fine.")
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SealStep(vm: EnrollViewModel) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.seal(activity) }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            BreathingRings(Modifier.fillMaxSize())
            Icon(Icons.Rounded.Fingerprint, null, tint = Circle.palette.ink, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("Seal with your fingerprint", style = MaterialTheme.typography.headlineMedium, color = Circle.palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Caption("This creates a key inside secure hardware that only your fingerprint can unlock.")
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Circle.palette.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PillButton("Try again", onClick = { scope.launch { vm.seal(activity) } })
        }
        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
private fun DoneStep(onFinished: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        ResultMark(true, Modifier.size(132.dp))
        Spacer(Modifier.height(28.dp))
        Text("You're in the Circle", style = MaterialTheme.typography.headlineLarge, color = Circle.palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Caption("Fingerprint and face are now bound to a hardware identity key on this device.")
        Spacer(Modifier.weight(1f))
        PillButton("Continue", onClick = onFinished, modifier = Modifier.fillMaxWidth())
    }
}
