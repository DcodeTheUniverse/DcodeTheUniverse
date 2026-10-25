package com.dcode.circle.core

import com.dcode.circle.protocol.FactorResult
import kotlin.math.exp
import kotlin.math.log10

object FactorIds {
    const val FINGERPRINT = "fingerprint"
    const val FACE_MATCH = "face_match"
    const val FACE_LIVENESS = "face_liveness"
    const val PULSE = "pulse_liveness"
    const val VASCULAR = "vascular_signature"
    const val DEVICE = "device_integrity"
}

data class Evidence(
    val id: String,
    val label: String,
    val passed: Boolean,
    /** Normalised 0..1 strength of this factor's evidence. */
    val score: Double,
    val required: Boolean,
    val detail: String = "",
) {
    fun toResult() = FactorResult(id, label, passed, score, detail)
}

data class FusionDecision(
    val verified: Boolean,
    /** 0 = none, 1 = single factor, 2 = two independent modalities, 3 = multimodal + liveness. */
    val assurance: Int,
    /** 1 − estimated probability that an impostor passes every factor that passed. */
    val confidence: Double,
    val impostorOdds: Double,
    val failures: List<String>,
)

/**
 * Decision-level fusion: every required factor must pass (AND rule), then the estimated false-accept rates
 * of independent factors are multiplied to express how unlikely an impostor is to get through.
 *
 * The FAR figures are conservative published ballparks, not measurements of this device:
 * Android Class 3 fingerprint ≤ 1/50,000 (CDD requirement); MobileFaceNet at our thresholds ≈ 1/1,000–1/20,000
 * depending on margin; liveness challenges and pulse mostly defeat presentation attacks rather than look-alikes,
 * so they are credited modestly.
 */
object Fusion {
    fun decide(evidence: List<Evidence>, faceThreshold: Float): FusionDecision {
        val failures = evidence.filter { it.required && !it.passed }.map { "${it.label}: ${it.detail.ifEmpty { "failed" }}" }
        val passed = evidence.filter { it.passed }
        var far = 1.0
        for (e in passed) {
            far *= when (e.id) {
                FactorIds.FINGERPRINT -> 1.0 / 50_000
                FactorIds.FACE_MATCH -> faceFar(e.score, faceThreshold)
                FactorIds.FACE_LIVENESS -> 0.1
                FactorIds.PULSE -> 0.2
                FactorIds.VASCULAR -> 0.5
                FactorIds.DEVICE -> 0.9
                else -> 1.0
            }
        }
        val ids = passed.map { it.id }.toSet()
        val modalities = listOf(FactorIds.FINGERPRINT, FactorIds.FACE_MATCH, FactorIds.VASCULAR).count { it in ids }
        val live = FactorIds.FACE_LIVENESS in ids || FactorIds.PULSE in ids
        val assurance = when {
            failures.isNotEmpty() || passed.isEmpty() -> 0
            modalities >= 2 && live -> 3
            modalities >= 2 -> 2
            else -> 1
        }
        val verified = failures.isEmpty() && passed.isNotEmpty()
        return FusionDecision(verified, assurance, if (verified) 1.0 - far else 0.0, if (verified) 1.0 / far else 0.0, failures)
    }

    /** Larger margins above the threshold make an impostor match exponentially less likely. */
    fun faceFar(score: Double, threshold: Float): Double {
        val margin = (score - threshold).coerceAtLeast(0.0)
        return (1.0 / 1_000) * exp(-margin * 12.0).coerceAtLeast(0.05)
    }

    fun formatOdds(odds: Double): String {
        if (odds < 1) return "—"
        val units = listOf(1e12 to "trillion", 1e9 to "billion", 1e6 to "million", 1e3 to "thousand")
        for ((scale, name) in units) if (odds >= scale) {
            val v = odds / scale
            val digits = if (v >= 100) 0 else 1
            return "1 in ${"%.${digits}f".format(v)} $name"
        }
        return "1 in ${odds.toLong()}"
    }

    /** Confidence as "nines", e.g. 0.999999 -> 6. */
    fun nines(confidence: Double): Int = if (confidence >= 1.0) 12 else (-log10(1.0 - confidence)).toInt().coerceIn(0, 12)
}
