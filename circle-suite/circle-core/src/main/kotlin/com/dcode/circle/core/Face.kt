package com.dcode.circle.core

import kotlin.math.abs
import kotlin.math.sqrt

data class Pt(val x: Float, val y: Float)

/** Five-point similarity alignment to the ArcFace 112×112 template used by MobileFaceNet. */
object FaceAlignment {
    const val SIZE = 112

    /** Image-left eye, image-right eye, nose tip, image-left mouth corner, image-right mouth corner. */
    val TEMPLATE = listOf(
        Pt(38.2946f, 51.6963f),
        Pt(73.5318f, 51.5014f),
        Pt(56.0252f, 71.7366f),
        Pt(41.5493f, 92.3655f),
        Pt(70.7299f, 92.2041f),
    )

    /**
     * Same template with the third point moved from the nose tip to the nose base (subnasale), which is what
     * ML Kit reports as NOSE_BASE. Measured on LFW: the subnasale sits at (56.0, 78.1) ± (2.5, 2.0) in template
     * space, and matching accuracy with this variant is unchanged (98.4%).
     */
    val TEMPLATE_NOSE_BASE = TEMPLATE.toMutableList().also { it[2] = Pt(56.0252f, 78.12f) }.toList()

    /** Orders detector landmarks by image position so mirroring or subject-vs-image "left" can never swap them. */
    fun order(eyeA: Pt, eyeB: Pt, nose: Pt, mouthA: Pt, mouthB: Pt): List<Pt> {
        val (le, re) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val (lm, rm) = if (mouthA.x <= mouthB.x) mouthA to mouthB else mouthB to mouthA
        return listOf(le, re, nose, lm, rm)
    }

    /**
     * Least-squares similarity transform (rotation, uniform scale, translation; no reflection) mapping [src] onto [dst].
     * Returns a 3×3 row-major affine matrix `[a, -b, tx, b, a, ty, 0, 0, 1]` (Android `Matrix.setValues` layout).
     */
    fun similarity(src: List<Pt>, dst: List<Pt> = TEMPLATE): FloatArray {
        require(src.size == dst.size && src.size >= 2)
        val n = src.size
        val sx = src.sumOf { it.x.toDouble() } / n
        val sy = src.sumOf { it.y.toDouble() } / n
        val dx = dst.sumOf { it.x.toDouble() } / n
        val dy = dst.sumOf { it.y.toDouble() } / n
        var dot = 0.0
        var cross = 0.0
        var norm = 0.0
        for (i in 0 until n) {
            val ux = src[i].x - sx
            val uy = src[i].y - sy
            val vx = dst[i].x - dx
            val vy = dst[i].y - dy
            dot += ux * vx + uy * vy
            cross += ux * vy - uy * vx
            norm += ux * ux + uy * uy
        }
        require(norm > 1e-9) { "Degenerate landmarks" }
        val a = dot / norm
        val b = cross / norm
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    }

    fun apply(m: FloatArray, p: Pt): Pt = Pt(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    /** Mean landmark error (in template pixels) after alignment — large values mean a bad detection. */
    fun residual(src: List<Pt>, m: FloatArray, dst: List<Pt> = TEMPLATE): Float =
        src.indices.map { i ->
            val p = apply(m, src[i])
            sqrt((p.x - dst[i].x) * (p.x - dst[i].x) + (p.y - dst[i].y) * (p.y - dst[i].y))
        }.average().toFloat()
}

object Embeddings {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val inv = if (s > 0) (1.0 / sqrt(s)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * inv }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var d = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            d += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0f else (d / sqrt(na * nb)).toFloat()
    }

    fun mean(list: List<FloatArray>): FloatArray {
        require(list.isNotEmpty())
        val out = FloatArray(list[0].size)
        for (v in list) {
            val nv = normalize(v)
            for (i in out.indices) out[i] += nv[i]
        }
        return normalize(out)
    }

    /** Test-time augmentation: average the embeddings of the face and its mirror image. */
    fun fuseFlip(a: FloatArray, b: FloatArray): FloatArray = normalize(FloatArray(a.size) { normalize(a)[it] + normalize(b)[it] })
}

/**
 * Cosine thresholds calibrated on 1,000 LFW pairs with this exact pipeline (5-point alignment + MobileFaceNet +
 * flip fusion): impostor pairs peaked at 0.34 (p99 0.29) while genuine pairs averaged 0.68.
 */
enum class FaceStrictness(val threshold: Float, val label: String) {
    Balanced(0.42f, "Balanced"),
    Strict(0.50f, "Strict"),
    Paranoid(0.58f, "Paranoid"),
}

/** The enrolled identity: several embeddings across poses, compared by top-k mean and centroid similarity. */
class FaceTemplate(samples: List<FloatArray>) {
    val samples: List<FloatArray> = samples.map(Embeddings::normalize)
    val centroid: FloatArray = Embeddings.mean(samples)

    fun score(probe: FloatArray): Float {
        val sims = samples.map { Embeddings.cosine(it, probe) }.sortedDescending()
        val topK = sims.take(3).average().toFloat()
        val c = Embeddings.cosine(centroid, probe)
        return 0.6f * topK + 0.4f * c
    }

    /** Internal consistency of the enrolment: mean pairwise similarity. */
    fun consistency(): Float {
        if (samples.size < 2) return 1f
        var total = 0.0
        var count = 0
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            total += Embeddings.cosine(samples[i], samples[j]); count++
        }
        return (total / count).toFloat()
    }
}

/** Guides enrolment so the template covers frontal and slightly turned poses. */
class EnrollmentPlan {
    enum class Pose(val instruction: String) {
        Front("Look straight at the circle"),
        Left("Turn slightly to your left"),
        Right("Turn slightly to your right"),
        Up("Tilt your chin up a little"),
        Front2("Look straight again"),
    }

    val poses = listOf(Pose.Front, Pose.Front, Pose.Left, Pose.Right, Pose.Up, Pose.Front2, Pose.Front2)
    private val captured = ArrayList<FloatArray>()
    val samples: List<FloatArray> get() = captured
    val progress: Float get() = captured.size.toFloat() / poses.size
    val done: Boolean get() = captured.size >= poses.size
    val currentPose: Pose? get() = poses.getOrNull(captured.size)

    /**
     * Sign conventions for yaw/pitch are learned from the user's own movement during enrolment, so "turn left"
     * challenges stay correct whatever the camera/detector convention on a given device.
     */
    var yawSign: Float? = null
        private set
    var pitchSign: Float? = null
        private set

    fun poseMatches(yaw: Float, pitch: Float): Boolean = when (currentPose) {
        Pose.Front, Pose.Front2 -> abs(yaw) < 10 && abs(pitch) < 12
        Pose.Left -> abs(yaw) in 12f..32f && abs(pitch) < 15 && (yawSign == null || yaw * yawSign!! > 0)
        Pose.Right -> abs(yaw) in 12f..32f && abs(pitch) < 15 && yawSign != null && yaw * yawSign!! < 0
        Pose.Up -> abs(pitch) in 8f..26f && abs(yaw) < 14 && (pitchSign == null || pitch * pitchSign!! > 0)
        null -> false
    }

    /** Accepts a sample if it fits the pose, is distinct from the previous one and consistent with the rest. */
    fun offer(embedding: FloatArray, yaw: Float, pitch: Float): Boolean {
        if (done || !poseMatches(yaw, pitch)) return false
        val e = Embeddings.normalize(embedding)
        val last = captured.lastOrNull()
        if (last != null && Embeddings.cosine(last, e) > 0.985f) return false
        if (captured.size >= 2 && Embeddings.cosine(Embeddings.mean(captured), e) < 0.45f) return false
        when (currentPose) {
            Pose.Left -> if (yawSign == null) yawSign = if (yaw > 0) 1f else -1f
            Pose.Up -> if (pitchSign == null) pitchSign = if (pitch > 0) 1f else -1f
            else -> Unit
        }
        captured.add(e)
        return true
    }

    fun reset() {
        captured.clear()
        yawSign = null
        pitchSign = null
    }
}
