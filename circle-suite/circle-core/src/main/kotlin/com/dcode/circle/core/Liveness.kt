package com.dcode.circle.core

import java.security.SecureRandom
import kotlin.math.abs

/** One frame's worth of face-detector output, normalised to the upright (non-mirrored) image. */
data class FaceObservation(
    val trackingId: Int?,
    val timestampMs: Long,
    val faceCount: Int,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
    /** Positive = face turned towards the subject's left. */
    val yaw: Float,
    /** Positive = chin up. */
    val pitch: Float,
    val roll: Float,
    /** Face box width / image width. */
    val sizeRatio: Float,
    /** Face-match score for this frame, if an embedding was computed. */
    val matchScore: Float? = null,
)

enum class Challenge(val instruction: String, val shortLabel: String) {
    Blink("Blink slowly", "Blink"),
    TurnLeft("Turn your head to your left", "Turn left"),
    TurnRight("Turn your head to your right", "Turn right"),
    Smile("Give a big smile", "Smile"),
    LookUp("Tilt your chin up", "Look up"),
}

/**
 * Active liveness: a random sequence of challenges a printed photo or a replayed video cannot anticipate,
 * while the same tracked face must keep matching the enrolled template from first frame to last.
 */
class LivenessSession(
    val challenges: List<Challenge>,
    private val matchThreshold: Float,
    private val timeoutMs: Long = 30_000,
    private val startMs: Long,
) {
    enum class Phase { Positioning, Challenge, Confirm, Done, Failed }

    data class State(
        val phase: Phase,
        val index: Int,
        val challenge: Challenge?,
        val hint: String,
        val challengeProgress: Float,
        val overallProgress: Float,
        val bestMatch: Float,
        val matchedFrames: Int,
        val failure: String? = null,
    )

    private var phase = Phase.Positioning
    private var index = 0
    private var trackingId: Int? = null
    private var lastSeenMs = startMs
    private var stage = 0
    private var stageSinceMs = 0L
    private var hold = 0
    private var bestMatch = 0f
    private var matchedFrames = 0
    private var mismatchedFrames = 0
    private var neutralFrames = 0
    private var hint = "Center your face in the circle"
    private var failure: String? = null

    val state: State get() = snapshot()

    fun onFrame(o: FaceObservation?, nowMs: Long): State {
        if (phase == Phase.Done || phase == Phase.Failed) return snapshot()
        if (nowMs - startMs > timeoutMs) return fail("Timed out — try again in better light")

        if (o == null || o.faceCount == 0) {
            if (trackingId != null && nowMs - lastSeenMs > 900) return fail("Your face left the frame")
            hint = "Center your face in the circle"
            return snapshot()
        }
        if (o.faceCount > 1) {
            if (trackingId != null) return fail("More than one face detected")
            hint = "Only one face, please"
            return snapshot()
        }
        if (trackingId != null && o.trackingId != null && o.trackingId != trackingId) {
            return fail("Face changed during verification")
        }
        lastSeenMs = nowMs

        o.matchScore?.let { s ->
            if (s > bestMatch) bestMatch = s
            if (s >= matchThreshold) {
                matchedFrames++
            } else if (s < matchThreshold - 0.15f && trackingId != null) {
                mismatchedFrames++
                if (mismatchedFrames >= 4 && mismatchedFrames > matchedFrames) return fail("Face does not match the enrolled identity")
            }
        }

        when (phase) {
            Phase.Positioning -> {
                when {
                    o.sizeRatio < 0.28f -> hint = "Move closer"
                    o.sizeRatio > 0.85f -> hint = "Move a little further away"
                    abs(o.yaw) > 10 || abs(o.pitch) > 12 -> hint = "Look straight at the circle"
                    else -> {
                        neutralFrames++
                        hint = "Hold still…"
                        if (neutralFrames >= 4) {
                            trackingId = o.trackingId
                            phase = Phase.Challenge
                            resetStage(nowMs)
                        }
                    }
                }
            }
            Phase.Challenge -> step(o, nowMs)
            Phase.Confirm -> confirm(o)
            else -> Unit
        }
        return snapshot()
    }

    private fun resetStage(nowMs: Long) {
        stage = 0
        hold = 0
        stageSinceMs = nowMs
        hint = challenges[index].instruction
    }

    private fun step(o: FaceObservation, nowMs: Long) {
        val c = challenges[index]
        val completed = when (c) {
            Challenge.Blink -> {
                val l = o.leftEyeOpen ?: return
                val r = o.rightEyeOpen ?: return
                when (stage) {
                    0 -> if (l > 0.65f && r > 0.65f) { stage = 1; stageSinceMs = nowMs }
                    1 -> if (l < 0.3f && r < 0.3f) { stage = 2; stageSinceMs = nowMs }
                    2 -> if (l > 0.65f && r > 0.65f) stage = 3 else if (nowMs - stageSinceMs > 1500) stage = 0
                }
                stage == 3
            }
            Challenge.TurnLeft -> turn(o.yaw > 22f, abs(o.yaw) < 10f)
            Challenge.TurnRight -> turn(o.yaw < -22f, abs(o.yaw) < 10f)
            Challenge.LookUp -> turn(o.pitch > 14f, abs(o.pitch) < 9f)
            Challenge.Smile -> {
                val s = o.smiling ?: return
                when (stage) {
                    0 -> if (s < 0.35f) stage = 1 else hint = "Relax your face first"
                    1 -> {
                        hint = c.instruction
                        if (s > 0.8f) hold++ else hold = 0
                        if (hold >= 3) stage = 2
                    }
                }
                stage == 2
            }
        }
        if (completed) {
            index++
            if (index >= challenges.size) {
                phase = Phase.Confirm
                hint = "Look straight at the circle"
                confirm(o)
            } else {
                resetStage(nowMs)
            }
        }
    }

    /** After the challenges, insist on enough confident frontal matches from the same tracked face. */
    private fun confirm(o: FaceObservation) {
        if (matchedFrames >= REQUIRED_MATCHES) {
            phase = Phase.Done
            hint = "Verified"
        } else {
            hint = if (abs(o.yaw) > 12 || abs(o.pitch) > 12) "Look straight at the circle" else "Hold still…"
        }
    }

    /** Head-turn challenges: reach the target pose and hold it for 2 frames, then come back to neutral. */
    private fun turn(atTarget: Boolean, neutral: Boolean): Boolean {
        when (stage) {
            0 -> if (atTarget) { hold++; if (hold >= 2) stage = 1 } else hold = 0
            1 -> { hint = "Now look back at the circle"; if (neutral) stage = 2 }
        }
        return stage == 2
    }

    private fun fail(reason: String): State {
        phase = Phase.Failed
        failure = reason
        hint = reason
        return snapshot()
    }

    private fun snapshot(): State {
        val per = 1f / challenges.size
        val cp = when (challenges.getOrNull(index)) {
            Challenge.Blink -> stage / 3f
            Challenge.Smile -> stage / 2f
            null -> 1f
            else -> stage / 2f
        }
        val overall = when (phase) {
            Phase.Positioning -> 0f
            Phase.Done -> 1f
            Phase.Confirm -> 0.97f
            else -> (index * per + cp * per).coerceIn(0f, 1f)
        }
        return State(phase, index, challenges.getOrNull(index), hint, cp, overall, bestMatch, matchedFrames, failure)
    }

    val passed: Boolean get() = phase == Phase.Done

    companion object {
        const val REQUIRED_MATCHES = 3
        private val random = SecureRandom()

        /** Picks [count] distinct challenges at random; never two head turns in a row so the sequence stays natural. */
        fun randomChallenges(count: Int): List<Challenge> {
            val pool = Challenge.entries.toMutableList()
            val out = ArrayList<Challenge>()
            while (out.size < count && pool.isNotEmpty()) {
                val c = pool.removeAt(random.nextInt(pool.size))
                val isTurn = c == Challenge.TurnLeft || c == Challenge.TurnRight
                val lastTurn = out.lastOrNull().let { it == Challenge.TurnLeft || it == Challenge.TurnRight }
                if (isTurn && lastTurn && pool.isNotEmpty()) { pool.add(c); continue }
                out.add(c)
            }
            return out
        }
    }
}
