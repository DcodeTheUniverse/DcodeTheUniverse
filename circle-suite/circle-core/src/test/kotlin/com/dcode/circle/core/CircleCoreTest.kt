package com.dcode.circle.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CircleCoreTest {
    @Test
    fun similarityRecoversKnownTransform() {
        // Rotate the template by 20°, scale 2.5x, translate, then check alignment maps it back.
        val ang = 20.0 * PI / 180
        val src = FaceAlignment.TEMPLATE.map { p ->
            Pt(
                (2.5 * (p.x * cos(ang) - p.y * sin(ang)) + 300).toFloat(),
                (2.5 * (p.x * sin(ang) + p.y * cos(ang)) + 120).toFloat(),
            )
        }
        val m = FaceAlignment.similarity(src)
        assertTrue(FaceAlignment.residual(src, m) < 1e-3f)
        // Landmark order from the detector must not matter.
        val shuffled = FaceAlignment.order(src[1], src[0], src[2], src[4], src[3])
        assertEquals(src, shuffled)
    }

    @Test
    fun templateScoring() {
        val rnd = java.util.Random(7)
        fun vec() = FloatArray(128) { rnd.nextGaussian().toFloat() }
        val me = vec()
        fun noisy(base: FloatArray, s: Float) = FloatArray(128) { base[it] + s * rnd.nextGaussian().toFloat() }
        val template = FaceTemplate(List(6) { noisy(me, 0.4f) })
        assertTrue(template.score(noisy(me, 0.4f)) > FaceStrictness.Strict.threshold)
        assertTrue(template.score(vec()) < 0.3f)
        assertTrue(template.consistency() > 0.7f)
    }

    private fun obs(t: Long, yaw: Float = 0f, pitch: Float = 0f, eyes: Float = 0.9f, smile: Float = 0.1f, id: Int = 1, match: Float = 0.8f, faces: Int = 1) =
        FaceObservation(id, t, faces, eyes, eyes, smile, yaw, pitch, 0f, 0.45f, match)

    @Test
    fun livenessPassesTheRealSequence() {
        val s = LivenessSession(listOf(Challenge.Blink, Challenge.TurnLeft, Challenge.Smile), matchThreshold = 0.5f, startMs = 0)
        var t = 0L
        fun feed(o: FaceObservation, times: Int = 1) = repeat(times) { s.onFrame(o.copy(timestampMs = t), t); t += 66 }
        feed(obs(0), 5)
        assertEquals(LivenessSession.Phase.Challenge, s.state.phase)
        feed(obs(0)); feed(obs(0, eyes = 0.1f), 2); feed(obs(0), 1)
        assertEquals(Challenge.TurnLeft, s.state.challenge)
        feed(obs(0, yaw = 30f), 3); feed(obs(0, yaw = 2f))
        assertEquals(Challenge.Smile, s.state.challenge)
        feed(obs(0, smile = 0.1f)); feed(obs(0, smile = 0.95f), 3)
        assertTrue(s.passed, s.state.toString())
    }

    @Test
    fun livenessRejectsSwapsAndMismatches() {
        val swap = LivenessSession(listOf(Challenge.Blink), 0.5f, startMs = 0)
        repeat(5) { swap.onFrame(obs(it * 66L), it * 66L) }
        swap.onFrame(obs(400, id = 2), 400)
        assertEquals("Face changed during verification", swap.state.failure)

        val stranger = LivenessSession(listOf(Challenge.Blink), 0.5f, startMs = 0)
        repeat(12) { stranger.onFrame(obs(it * 66L, match = 0.1f), it * 66L) }
        assertEquals(LivenessSession.Phase.Failed, stranger.state.phase)

        val photo = LivenessSession(listOf(Challenge.TurnRight), 0.5f, timeoutMs = 3000, startMs = 0)
        for (i in 0..60) photo.onFrame(obs(i * 66L), i * 66L) // a static photo never turns
        assertFalse(photo.passed)
        assertEquals(LivenessSession.Phase.Failed, photo.state.phase)
    }

    @Test
    fun randomChallengesAreDistinct() {
        repeat(200) {
            val c = LivenessSession.randomChallenges(3)
            assertEquals(3, c.toSet().size)
        }
    }

    @Test
    fun pulseFindsHeartRateInNoisyJitteryCapture() {
        val a = PulseAnalyzer()
        val rnd = java.util.Random(3)
        var tNs = 0L
        var state: PulseAnalyzer.State? = null
        for (i in 0 until 400) {
            val t = tNs / 1e9
            val red = 190 + 2.0 * sin(2 * PI * 1.2 * t) + 0.6 * sin(2 * PI * 2.4 * t) + 4 * t / 10 + rnd.nextGaussian() * 0.5
            state = a.add(tNs, red, 40.0, 30.0)
            tNs += (33_333_333L + (rnd.nextGaussian() * 4_000_000).toLong()).coerceAtLeast(10_000_000L)
        }
        val bpm = assertNotNull(state!!.bpm)
        assertTrue(abs(bpm - 72) < 4, "bpm=$bpm")
        assertTrue(state.locked)
    }

    @Test
    fun pulseRejectsNoiseAndNoFinger() {
        val a = PulseAnalyzer()
        val rnd = java.util.Random(4)
        var state: PulseAnalyzer.State? = null
        for (i in 0 until 400) state = a.add(i * 33_333_333L, 190 + rnd.nextGaussian() * 2, 40.0, 30.0)
        assertFalse(state!!.locked)
        assertFalse(a.add(500 * 33_333_333L, 60.0, 55.0, 50.0).fingerPresent)
    }

    @Test
    fun vascularSignatureToleratesShiftButSeparatesPatterns() {
        val n = VascularSignature.SIZE
        val rnd = java.util.Random(5)
        fun pattern() = FloatArray(n * n) { rnd.nextFloat() * 20 }
        fun smooth(p: FloatArray) = FloatArray(n * n) { i ->
            val x = i % n; val y = i / n
            var s = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) { val xx = x + dx; val yy = y + dy; if (xx in 0 until n && yy in 0 until n) { s += p[yy * n + xx]; c++ } }
            s / c
        }
        val mine = smooth(pattern())
        val shifted = FloatArray(n * n) { i -> val x = (i % n + 2).coerceAtMost(n - 1); val y = i / n; mine[y * n + x] + rnd.nextFloat() }
        val a = VascularSignature.build(List(5) { mine })
        val b = VascularSignature.build(List(5) { shifted })
        val c = VascularSignature.build(List(5) { smooth(pattern()) })
        assertTrue(VascularSignature.similarity(a, b) > 0.7f)
        assertTrue(VascularSignature.similarity(a, c) < 0.4f)
    }

    @Test
    fun fusionRequiresEveryRequiredFactor() {
        val ok = listOf(
            Evidence(FactorIds.FINGERPRINT, "Fingerprint", true, 1.0, true),
            Evidence(FactorIds.FACE_MATCH, "Face", true, 0.7, true),
            Evidence(FactorIds.FACE_LIVENESS, "Liveness", true, 1.0, true),
        )
        val d = Fusion.decide(ok, 0.56f)
        assertTrue(d.verified)
        assertEquals(3, d.assurance)
        assertTrue(d.impostorOdds > 1e9, Fusion.formatOdds(d.impostorOdds))
        val bad = Fusion.decide(ok.map { if (it.id == FactorIds.FACE_LIVENESS) it.copy(passed = false) else it }, 0.56f)
        assertFalse(bad.verified)
        assertEquals(0, bad.assurance)
    }
}

class EnrollmentPlanTest {
    @Test
    fun learnsYawConventionFromUser() {
        val plan = EnrollmentPlan()
        val rnd = java.util.Random(9)
        val base = FloatArray(128) { rnd.nextGaussian().toFloat() }
        fun sample() = FloatArray(128) { base[it] + 0.3f * rnd.nextGaussian().toFloat() }
        assertTrue(plan.offer(sample(), 0f, 0f))
        assertTrue(plan.offer(sample(), 2f, 1f))
        // This device reports a left turn as negative yaw.
        assertTrue(plan.offer(sample(), -20f, 0f))
        assertEquals(-1f, plan.yawSign)
        assertFalse(plan.offer(sample(), -20f, 0f), "right turn must be the opposite direction")
        assertTrue(plan.offer(sample(), 20f, 0f))
        assertTrue(plan.offer(sample(), 0f, 15f))
        assertTrue(plan.offer(sample(), 0f, 0f))
        assertTrue(plan.offer(sample(), 1f, 0f))
        assertTrue(plan.done)
    }
}
