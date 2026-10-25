package com.dcode.circle.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photoplethysmography from the rear camera with the torch on and a fingertip over the lens.
 *
 * Blood volume in the fingertip changes with every heartbeat, which modulates how much of the flash's light
 * makes it through the tissue. A living, perfused finger therefore produces a periodic signal at the heart
 * rate; a photo, a silicone/gelatine replica or a dead surface does not. This is the "is there living tissue
 * with a pulse here" factor — the closest thing to vein sensing that phone hardware exposes to apps.
 */
class PulseAnalyzer(
    private val windowSeconds: Double = 10.0,
    private val sampleRate: Double = 30.0,
) {
    data class State(
        val fingerPresent: Boolean,
        val bpm: Double?,
        val quality: Double,
        val locked: Boolean,
        val elapsedSeconds: Double,
        val waveform: FloatArray,
        val perfusionIndex: Double,
    )

    private val times = ArrayList<Double>()
    private val values = ArrayList<Double>()
    private var firstPresentAt: Double? = null
    private var lastAnalysisAt = Double.NEGATIVE_INFINITY
    private val estimates = ArrayDeque<Double>()
    private var last = State(false, null, 0.0, false, 0.0, FloatArray(0), 0.0)

    val state: State get() = last

    fun reset() {
        times.clear(); values.clear(); estimates.clear(); firstPresentAt = null; lastAnalysisAt = Double.NEGATIVE_INFINITY
        last = State(false, null, 0.0, false, 0.0, FloatArray(0), 0.0)
    }

    /** @param red/green/blue mean channel values of the frame in 0..255. */
    fun add(timestampNs: Long, red: Double, green: Double, blue: Double): State {
        val t = timestampNs / 1e9
        val present = isFingerPresent(red, green, blue)
        if (!present) {
            reset()
            return last
        }
        if (firstPresentAt == null) firstPresentAt = t
        times.add(t)
        values.add(red)
        while (times.isNotEmpty() && t - times.first() > windowSeconds) {
            times.removeAt(0); values.removeAt(0)
        }
        val elapsed = t - (firstPresentAt ?: t)
        if (t - times.first() < 4.0 || times.size < 60) {
            last = State(true, null, 0.0, false, elapsed, FloatArray(0), 0.0)
            return last
        }
        // Spectral analysis is throttled to 5 Hz; the camera delivers 30 fps.
        if (t - lastAnalysisAt < 0.2) {
            last = last.copy(elapsedSeconds = elapsed)
            return last
        }
        lastAnalysisAt = t
        val signal = resample()
        val filtered = detrend(signal)
        val (freq, quality) = dominantFrequency(filtered)
        val bpm = freq * 60.0
        if (quality > MIN_QUALITY) {
            estimates.addLast(bpm)
            while (estimates.size > 8) estimates.removeFirst()
        }
        val locked = estimates.size >= 5 && estimates.takeLast(5).let { r -> r.max() - r.min() < 8.0 } && quality > MIN_QUALITY
        val mean = values.average()
        val ac = sqrt(filtered.sumOf { it * it } / filtered.size)
        last = State(
            fingerPresent = true,
            bpm = if (quality > MIN_QUALITY) bpm else null,
            quality = quality,
            locked = locked,
            elapsedSeconds = elapsed,
            waveform = normalizedTail(filtered, 150),
            perfusionIndex = if (mean > 0) 100.0 * ac / mean else 0.0,
        )
        return last
    }

    private fun resample(): DoubleArray {
        val start = times.first()
        val n = ((times.last() - start) * sampleRate).toInt()
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = start + i / sampleRate
            while (j < times.size - 2 && times[j + 1] < t) j++
            val t0 = times[j]
            val t1 = times[j + 1]
            val f = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            out[i] = values[j] + f * (values[j + 1] - values[j])
        }
        return out
    }

    /** Removes slow drift (finger pressure, auto-exposure) with a 1 s moving average, then smooths with 3 taps. */
    private fun detrend(x: DoubleArray): DoubleArray {
        val w = sampleRate.toInt().coerceAtLeast(3)
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        val hp = DoubleArray(x.size) { i ->
            val a = (i - w / 2).coerceAtLeast(0)
            val b = (i + w / 2 + 1).coerceAtMost(x.size)
            x[i] - (prefix[b] - prefix[a]) / (b - a)
        }
        return DoubleArray(hp.size) { i ->
            val a = hp[(i - 1).coerceAtLeast(0)]
            val c = hp[(i + 1).coerceAtMost(hp.size - 1)]
            (a + 2 * hp[i] + c) / 4.0
        }
    }

    /** Scans 0.7–3.5 Hz (42–210 bpm). Quality = share of in-band power near the peak and its harmonic. */
    private fun dominantFrequency(x: DoubleArray): Pair<Double, Double> {
        val window = DoubleArray(x.size) { i -> 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1)) }
        val y = DoubleArray(x.size) { x[it] * window[it] }
        var bestF = 0.0
        var bestP = 0.0
        val powers = ArrayList<Pair<Double, Double>>()
        var f = 0.7
        while (f <= 3.5) {
            val p = power(y, f)
            powers.add(f to p)
            if (p > bestP) { bestP = p; bestF = f }
            f += 0.0125
        }
        val total = powers.sumOf { it.second }
        if (total <= 0) return 0.0 to 0.0
        val near = powers.filter { abs(it.first - bestF) <= 0.1 || abs(it.first - 2 * bestF) <= 0.1 }.sumOf { it.second }
        return bestF to (near / total)
    }

    private fun power(x: DoubleArray, freq: Double): Double {
        var re = 0.0
        var im = 0.0
        val w = 2 * PI * freq / sampleRate
        for (i in x.indices) {
            re += x[i] * cos(w * i)
            im -= x[i] * sin(w * i)
        }
        return re * re + im * im
    }

    private fun normalizedTail(x: DoubleArray, n: Int): FloatArray {
        val tail = x.copyOfRange((x.size - n).coerceAtLeast(0), x.size)
        val m = tail.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        return FloatArray(tail.size) { (tail[it] / m).toFloat() }
    }

    companion object {
        const val MIN_QUALITY = 0.32

        /** A fingertip over the lens with the torch on looks saturated red with very little green/blue. */
        fun isFingerPresent(r: Double, g: Double, b: Double): Boolean = r > 80 && r > 1.5 * g && r > 1.5 * b
    }
}

/**
 * EXPERIMENTAL transillumination texture signature.
 *
 * With the torch shining through a fingertip, absorption by blood vessels and tissue structure produces a
 * faint, person-specific light pattern. Phone cameras have IR-cut filters and the torch is white rather than
 * 850 nm near-infrared, so this is far weaker than a real vein scanner — Circle ID reports its score for
 * experimentation and never relies on it alone.
 */
object VascularSignature {
    const val SIZE = 48

    /** Averages frames (each SIZE×SIZE red-channel intensities), removes illumination fall-off and normalises. */
    fun build(frames: List<FloatArray>): FloatArray {
        require(frames.isNotEmpty())
        val mean = FloatArray(SIZE * SIZE)
        for (f in frames) for (i in mean.indices) mean[i] += f[i]
        for (i in mean.indices) mean[i] /= frames.size
        val blurred = boxBlur(mean, 6)
        val hp = FloatArray(mean.size) { mean[it] - blurred[it] }
        return zNormalize(hp)
    }

    /** Maximum normalised cross-correlation over small shifts (finger placement is never identical). */
    fun similarity(a: FloatArray, b: FloatArray, maxShift: Int = 4): Float {
        var best = -1f
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            var s = 0.0
            var na = 0.0
            var nb = 0.0
            for (y in maxShift until SIZE - maxShift) for (x in maxShift until SIZE - maxShift) {
                val va = a[y * SIZE + x]
                val vb = b[(y + dy) * SIZE + (x + dx)]
                s += va * vb; na += va * va; nb += vb * vb
            }
            if (na > 0 && nb > 0) best = maxOf(best, (s / sqrt(na * nb)).toFloat())
        }
        return best
    }

    private fun boxBlur(src: FloatArray, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var s = 0f; var c = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until SIZE) { s += src[y * SIZE + xx]; c++ } }
            tmp[y * SIZE + x] = s / c
        }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var s = 0f; var c = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until SIZE) { s += tmp[yy * SIZE + x]; c++ } }
            out[y * SIZE + x] = s / c
        }
        return out
    }

    private fun zNormalize(v: FloatArray): FloatArray {
        val m = v.average().toFloat()
        val sd = sqrt(v.sumOf { ((it - m) * (it - m)).toDouble() } / v.size).toFloat().takeIf { it > 1e-6f } ?: 1f
        return FloatArray(v.size) { (v[it] - m) / sd }
    }
}
