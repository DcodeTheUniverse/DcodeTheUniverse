package com.dcode.circleid.verify

import android.app.Application
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dcode.circle.core.Challenge
import com.dcode.circle.core.Evidence
import com.dcode.circle.core.FaceTemplate
import com.dcode.circle.core.FactorIds
import com.dcode.circle.core.Fusion
import com.dcode.circle.core.FusionDecision
import com.dcode.circle.core.LivenessSession
import com.dcode.circle.core.PulseAnalyzer
import com.dcode.circle.core.VascularSignature
import com.dcode.circle.protocol.AssertionStatus
import com.dcode.circle.protocol.CircleAssertion
import com.dcode.circle.protocol.CircleCodec
import com.dcode.circle.protocol.CircleContract
import com.dcode.circle.protocol.CircleRequest
import com.dcode.circle.protocol.RequestKind
import com.dcode.circleid.CircleApp
import com.dcode.circleid.face.FaceAnalyzer
import com.dcode.circleid.face.FaceFrame
import com.dcode.circleid.face.PulseFrame
import com.dcode.circleid.security.BioResult
import com.dcode.circleid.security.Biometrics
import com.dcode.circleid.security.ClientRecord
import com.dcode.circleid.security.DeviceIntegrity
import com.dcode.circleid.security.HistoryEntry
import com.dcode.circleid.security.PairedApp
import com.dcode.circleid.security.ProfileSession
import com.dcode.crypto.Bytes
import com.dcode.crypto.EcSignatures
import com.dcode.crypto.Ecies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A verified request from another app. [callerPackage] comes from the platform, never from the request body. */
data class ExternalRequest(
    val request: CircleRequest,
    val callerPackage: String,
    val callerName: String,
    val callerCert: String,
)

sealed interface VStep {
    data class Consent(val req: ExternalRequest) : VStep
    data object Fingerprint : VStep
    data object Face : VStep
    data object Pulse : VStep
    data object Working : VStep
    data class Result(
        val success: Boolean,
        val title: String,
        val message: String,
        val decision: FusionDecision?,
        val evidence: List<Evidence>,
    ) : VStep
}

/** Final answer for the calling app. */
data class Outcome(val ok: Boolean, val response: ByteArray?)

class VerifyViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as CircleApp).graph
    private val prefs = graph.prefs

    var step by mutableStateOf<VStep>(VStep.Working)
        private set
    var totalSteps by mutableStateOf(2)
        private set

    private val _liveness = MutableStateFlow<LivenessSession.State?>(null)
    val liveness: StateFlow<LivenessSession.State?> = _liveness.asStateFlow()
    private val _pulse = MutableStateFlow<PulseAnalyzer.State?>(null)
    val pulse: StateFlow<PulseAnalyzer.State?> = _pulse.asStateFlow()
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    var challenges: List<Challenge> = emptyList()
        private set

    private var external: ExternalRequest? = null
    private var session: ProfileSession? = null
    private var template: FaceTemplate? = null
    private val evidence = ArrayList<Evidence>()
    private val lock = Any()
    private var live: LivenessSession? = null
    private val matchScores = ArrayList<Float>()
    private val pulseAnalyzer = PulseAnalyzer()
    private val patches = ArrayList<FloatArray>()
    private var pulseStartedAt = 0L
    private var started = false
    private val needsPulse get() = prefs.requirePulse || (prefs.requireVascular && prefs.vascularEnrolled)

    fun begin(ext: ExternalRequest?) {
        if (started) return
        started = true
        external = ext
        totalSteps = if (needsPulse) 3 else 2
        if (ext == null) {
            step = VStep.Fingerprint
            return
        }
        // Early, public-metadata checks so the user isn't asked to verify for nothing.
        if (ext.request.kind == RequestKind.VERIFY) {
            val paired = prefs.pairedApps.firstOrNull { it.packageName == ext.callerPackage }
            if (paired == null) {
                respondWithoutVerification(AssertionStatus.NOT_PAIRED, "${ext.callerName} is not paired with Circle ID")
                return
            }
        }
        step = VStep.Consent(ext)
    }

    fun consent(approved: Boolean) {
        if (approved) step = VStep.Fingerprint else cancel()
    }

    /** True once an outcome exists: nothing may change the result after that (first outcome wins). */
    private val finished: Boolean get() = _outcome.value != null || step is VStep.Result

    private fun settle(o: Outcome) {
        _outcome.compareAndSet(null, o)
    }

    /** Stops the flow for good: cameras stop (step changes), the unlocked profile is wiped, nothing is released. */
    fun cancel() {
        if (finished) return
        timeout?.cancel()
        record(AssertionStatus.CANCELLED, 0, "Cancelled")
        closeSession()
        step = VStep.Result(false, "Cancelled", "Nothing was shared.", null, emptyList())
        settle(Outcome(false, null))
    }

    private var timeout: kotlinx.coroutines.Job? = null

    /** Timeouts run on a timer, so a camera that never delivers frames can't hang a step. */
    private fun armTimeout(ms: Long, onExpire: () -> Unit) {
        timeout?.cancel()
        timeout = viewModelScope.launch {
            kotlinx.coroutines.delay(ms)
            onExpire()
        }
    }

    // ------------------------------------------------------------------ step 1: fingerprint

    suspend fun runFingerprint(activity: FragmentActivity) {
        val cipher = try {
            graph.profiles.unlockCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            fail("Fingerprints changed", "A fingerprint was added or removed on this device, so Circle ID's hardware key was destroyed by design. Reset Circle ID and enroll again.")
            return
        } catch (e: Exception) {
            fail("Not enrolled", "Set up Circle ID first.")
            return
        }
        val result = Biometrics.authenticate(
            activity,
            title = "Circle ID",
            subtitle = "Step 1 of $totalSteps · Fingerprint",
            crypto = BiometricPrompt.CryptoObject(cipher),
        )
        if (finished) return
        when (result) {
            is BioResult.Success -> {
                val c = result.crypto?.cipher
                val s = runCatching { withContext(Dispatchers.IO) { graph.profiles.open(c!!) } }.getOrNull()
                if (s == null) {
                    fail("Couldn't unlock", "The fingerprint matched but the hardware key did not release the profile.")
                    return
                }
                session = s
                template = s.profile.faceTemplate()
                evidence.add(
                    Evidence(
                        FactorIds.FINGERPRINT, "Fingerprint", true, 1.0, true,
                        "Class 3 hardware match released the ${graph.keystore.location(com.dcode.circleid.security.CircleKeystore.PROFILE_KEK).label} key",
                    ),
                )
                startFace()
            }
            BioResult.Cancelled -> cancel()
            is BioResult.Error -> {
                evidence.add(Evidence(FactorIds.FINGERPRINT, "Fingerprint", false, 0.0, true, result.message))
                complete()
            }
        }
    }

    // ------------------------------------------------------------------ step 2: face

    private fun startFace() {
        challenges = LivenessSession.randomChallenges(prefs.challengeCount)
        synchronized(lock) {
            matchScores.clear()
            // The liveness clock starts with the first camera frame, not before permission prompts and warm-up.
            live = null
        }
        _liveness.value = null
        step = VStep.Face
        armTimeout(60_000) {
            if (step != VStep.Face) return@armTimeout
            val st = synchronized(lock) { live?.state }
            finishFace(
                st?.copy(phase = LivenessSession.Phase.Failed, failure = "Timed out — no usable camera frames")
                    ?: LivenessSession.State(LivenessSession.Phase.Failed, 0, null, "", 0f, 0f, 0f, 0, "Camera never started"),
            )
        }
    }

    fun newFaceAnalyzer(): FaceAnalyzer {
        val p = session?.profile
        val t = template
        return FaceAnalyzer(
            // If the model can't load, no frame ever matches and the check fails safely on timeout.
            embedder = runCatching { graph.embedder }.getOrNull(),
            yawSign = p?.yawSign ?: 1f,
            pitchSign = p?.pitchSign ?: 1f,
            scorer = t?.let { tpl -> { e: FloatArray -> tpl.score(e) } },
            onFrame = ::onFaceFrame,
        )
    }

    private fun onFaceFrame(frame: FaceFrame) {
        val state = synchronized(lock) {
            if (step != VStep.Face) return
            val l = live ?: LivenessSession(challenges, prefs.strictness.threshold, startMs = SystemClock.elapsedRealtime()).also { live = it }
            if (l.state.phase == LivenessSession.Phase.Done || l.state.phase == LivenessSession.Phase.Failed) return
            frame.observation?.matchScore?.let { matchScores.add(it) }
            l.onFrame(frame.observation, SystemClock.elapsedRealtime())
        }
        _liveness.value = state
        if (state.phase == LivenessSession.Phase.Done || state.phase == LivenessSession.Phase.Failed) {
            viewModelScope.launch(Dispatchers.Main) { finishFace(state) }
        }
    }

    private fun finishFace(state: LivenessSession.State) {
        if (step != VStep.Face || finished) return
        timeout?.cancel()
        val threshold = prefs.strictness.threshold
        val scores = synchronized(lock) { matchScores.toList() }
        val good = scores.filter { it >= threshold }
        val top = scores.sortedDescending().take(5)
        val matchScore = if (top.isEmpty()) 0.0 else top.average()
        val matchPassed = good.size >= LivenessSession.REQUIRED_MATCHES && matchScore >= threshold && state.failure?.contains("match") != true
        evidence.add(
            Evidence(
                FactorIds.FACE_MATCH, "Face match", matchPassed, matchScore, true,
                if (scores.isEmpty()) "No usable face frames" else "Similarity ${"%.2f".format(matchScore)} vs ${"%.2f".format(threshold)} needed · ${good.size}/${scores.size} frames matched",
            ),
        )
        evidence.add(
            Evidence(
                FactorIds.FACE_LIVENESS, "Liveness challenges", state.phase == LivenessSession.Phase.Done, if (state.phase == LivenessSession.Phase.Done) 1.0 else state.overallProgress.toDouble(), true,
                state.failure ?: challenges.joinToString(" · ") { it.shortLabel } + " — same face tracked throughout",
            ),
        )
        if (state.phase == LivenessSession.Phase.Done && matchPassed && needsPulse) {
            pulseAnalyzer.reset()
            patches.clear()
            pulseStartedAt = SystemClock.elapsedRealtime()
            step = VStep.Pulse
            armTimeout(40_000) { finishPulse(pulseAnalyzer.state) }
        } else {
            complete()
        }
    }

    // ------------------------------------------------------------------ step 3: pulse (optional)

    fun onPulseFrame(f: PulseFrame) {
        if (step != VStep.Pulse) return
        val s = synchronized(lock) {
            val st = pulseAnalyzer.add(f.timestampNs, f.r, f.g, f.b)
            if (st.fingerPresent && patches.size < 150) patches.add(f.patch)
            if (!st.fingerPresent) patches.clear()
            st
        }
        _pulse.value = s
        val elapsed = SystemClock.elapsedRealtime() - pulseStartedAt
        if ((s.locked && s.elapsedSeconds > 7) || elapsed > 35_000) {
            // The timer in armTimeout covers the case where frames stop arriving altogether.
            viewModelScope.launch(Dispatchers.Main) { finishPulse(s) }
        }
    }

    private fun finishPulse(s: PulseAnalyzer.State) {
        if (step != VStep.Pulse || finished) return
        timeout?.cancel()
        evidence.add(
            Evidence(
                FactorIds.PULSE, "Pulse (living tissue)", s.locked, s.quality.coerceIn(0.0, 1.0), prefs.requirePulse,
                if (s.locked) "Heartbeat ${s.bpm?.toInt()} bpm · signal quality ${"%.0f".format(s.quality * 100)}%" else "No stable heartbeat detected",
            ),
        )
        val enrolled = session?.profile?.vascular?.toFloatArray()
        val captured = synchronized(lock) { patches.toList() }
        if (enrolled != null && captured.size >= 30) {
            val sim = VascularSignature.similarity(enrolled, VascularSignature.build(captured))
            evidence.add(
                Evidence(
                    FactorIds.VASCULAR, "Vascular texture (experimental)", sim >= 0.55f, sim.toDouble().coerceAtLeast(0.0), prefs.requireVascular,
                    "Correlation ${"%.2f".format(sim)} (experimental, 0.55 needed)",
                ),
            )
        } else if (prefs.requireVascular) {
            evidence.add(Evidence(FactorIds.VASCULAR, "Vascular texture (experimental)", false, 0.0, true, "Not enough finger frames captured"))
        }
        complete()
    }

    // ------------------------------------------------------------------ decision

    private fun complete() {
        if (finished) return
        step = VStep.Working
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val integrity = withContext(Dispatchers.IO) { DeviceIntegrity.run(ctx, graph.keystore) }
            val critical = integrity.filter { it.critical }
            evidence.add(
                Evidence(
                    FactorIds.DEVICE, "Device integrity", critical.all { it.ok }, integrity.count { it.ok }.toDouble() / integrity.size, false,
                    integrity.filterNot { it.ok }.joinToString { it.label }.ifEmpty { "All ${integrity.size} checks passed" },
                ),
            )
            val decision = Fusion.decide(evidence, prefs.strictness.threshold)
            val ext = external
            if (_outcome.value != null) return@launch
            if (ext == null) {
                record(if (decision.verified) AssertionStatus.VERIFIED else AssertionStatus.REJECTED, decision.assurance, decision.failures.joinToString())
                step = VStep.Result(
                    decision.verified,
                    if (decision.verified) "It's you" else "Not verified",
                    if (decision.verified) "Identity confirmed with ${evidence.count { it.passed }} independent signals." else decision.failures.firstOrNull() ?: "Verification failed",
                    decision, evidence.toList(),
                )
            } else {
                val (status, message, response) = withContext(Dispatchers.IO) { respond(ext, decision) }
                record(status, decision.assurance, message)
                val ok = status == AssertionStatus.VERIFIED
                step = VStep.Result(
                    ok,
                    if (ok) (if (ext.request.kind == RequestKind.PAIR) "Paired" else "Verified") else "Not verified",
                    if (ok) "Returning to ${ext.callerName}…" else message,
                    decision, evidence.toList(),
                )
                settle(Outcome(ok, response))
            }
            closeSession()
        }
    }

    private fun respond(ext: ExternalRequest, decision: FusionDecision): Triple<AssertionStatus, String, ByteArray> {
        val req = ext.request
        val s = session
        var status = if (decision.verified) AssertionStatus.VERIFIED else AssertionStatus.REJECTED
        var message = if (decision.verified) "Verified" else decision.failures.joinToString("; ").ifEmpty { "Verification failed" }
        var sealed: String? = null
        if (status == AssertionStatus.VERIFIED && s != null) {
            val now = System.currentTimeMillis()
            val secret: ByteArray? = when (req.kind) {
                RequestKind.PAIR -> {
                    val fresh = Bytes.random(CircleContract.RELEASE_SECRET_SIZE)
                    graph.profiles.update(s) { p ->
                        p.copy(clients = p.clients.filterNot { it.packageName == ext.callerPackage } +
                            ClientRecord(ext.callerPackage, ext.callerName, ext.callerCert, Bytes.b64(fresh), now))
                    }
                    prefs.pairedApps = prefs.pairedApps.filterNot { it.packageName == ext.callerPackage } + PairedApp(ext.callerPackage, ext.callerName, now, now)
                    fresh
                }
                RequestKind.VERIFY -> {
                    val client = s.profile.clients.firstOrNull { it.packageName == ext.callerPackage }
                    when {
                        client == null -> { status = AssertionStatus.NOT_PAIRED; message = "Not paired"; null }
                        client.certSha256 != ext.callerCert -> {
                            status = AssertionStatus.REJECTED
                            message = "${ext.callerName}'s signing certificate changed since pairing"
                            null
                        }
                        else -> {
                            graph.profiles.update(s) { p ->
                                p.copy(clients = p.clients.map { if (it.packageName == client.packageName) it.copy(lastVerifiedAt = now, verifications = it.verifications + 1) else it })
                            }
                            prefs.pairedApps = prefs.pairedApps.map { if (it.packageName == client.packageName) it.copy(lastUsed = now) else it }
                            Bytes.unb64(client.secret)
                        }
                    }
                }
            }
            if (secret != null) {
                sealed = Bytes.b64(Ecies.seal(Bytes.unb64(req.ephemeralKey), secret, CircleContract.releaseContext(ext.callerPackage, req.nonce)))
                Bytes.wipe(secret)
            }
        }
        return Triple(status, message, sign(ext, status, message, decision, sealed))
    }

    private fun sign(ext: ExternalRequest, status: AssertionStatus, message: String, decision: FusionDecision?, sealed: String?): ByteArray {
        val pub = graph.keystore.identityPublicKey()
        val assertion = CircleAssertion(
            kind = ext.request.kind,
            status = status,
            requestNonce = ext.request.nonce,
            clientPackage = ext.callerPackage,
            clientCertSha256 = ext.callerCert,
            circleId = EcSignatures.fingerprint(pub),
            identityPublicKey = Bytes.b64(pub),
            issuedAt = System.currentTimeMillis(),
            assurance = if (status == AssertionStatus.VERIFIED) decision?.assurance ?: 0 else 0,
            confidence = if (status == AssertionStatus.VERIFIED) decision?.confidence ?: 0.0 else 0.0,
            factors = evidence.map { it.toResult() },
            sealedSecret = sealed,
            message = message,
        )
        val chain = if (ext.request.kind == RequestKind.PAIR) graph.keystore.attestationChain() else emptyList()
        return CircleCodec.sealAssertion(assertion, chain) { graph.keystore.sign(it) }
    }

    private fun respondWithoutVerification(status: AssertionStatus, message: String) {
        val ext = external ?: return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { sign(ext, status, message, null, null) }.getOrNull() }
            record(status, 0, message)
            step = VStep.Result(false, "Not paired", "$message. Open ${ext.callerName} and pair it with Circle ID first.", null, emptyList())
            settle(Outcome(false, bytes))
        }
    }

    private fun fail(title: String, message: String) {
        record(AssertionStatus.ERROR, 0, message)
        timeout?.cancel()
        step = VStep.Result(false, title, message, null, evidence.toList())
        settle(Outcome(false, null))
        closeSession()
    }

    private fun record(status: AssertionStatus, assurance: Int, message: String) {
        val ext = external
        graph.history.add(
            HistoryEntry(
                at = System.currentTimeMillis(),
                requester = ext?.callerName ?: "Circle ID",
                kind = ext?.request?.kind?.name ?: "SELF",
                status = status.name,
                assurance = assurance,
                factors = evidence.map { "${it.label}: ${if (it.passed) "✓" else "✗"}" },
                message = message,
            ),
        )
    }

    @Synchronized
    private fun closeSession() {
        session?.close()
        session = null
        template = null
    }

    override fun onCleared() {
        closeSession()
    }
}
