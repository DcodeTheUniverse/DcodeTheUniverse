package com.axiom.launcher.ui.solis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.ui.effects.motionAllowed
import com.axiom.launcher.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** Face grid: 11 x 9 dots. */
private const val COLS = 11
private const val ROWS = 9

/**
 * Solis's dot-matrix face. Every state is driven by real engine state (see [Mood]); the expression only
 * says what is actually happening. With reduced motion, battery saver or thermal pressure the face is a
 * still frame per state, so the state is never conveyed by motion alone.
 */
@Composable
fun SolisFace(state: Mood, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color? = null) {
    val p = LocalPalette.current
    val animate = motionAllowed() && state.animated
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state, animate) {
        t = 0L
        if (!animate) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            val now = (System.nanoTime() - start) / 1_000_000
            t = now
            delay(state.frameDelay(now))
        }
    }
    val on = tint ?: if (state == Mood.ERROR) p.danger else p.fg
    val off = p.faint.copy(alpha = if (p.isDark) 0.28f else 0.35f)
    Canvas(
        modifier
            .size(size, size * ROWS / COLS)
            .semantics {
                contentDescription = "Solis"
                stateDescription = state.spoken
            },
    ) {
        val pitch = this.size.width / COLS
        val r = pitch * 0.36f
        val frame = faceFrame(state, t, animate)
        for (row in 0 until ROWS) for (col in 0 until COLS) {
            val level = frame[row * COLS + col]
            val c = Offset(pitch * (col + 0.5f), pitch * (row + 0.5f))
            if (level <= 0.02f) drawCircle(off, r * 0.8f, c)
            else drawCircle(on.copy(alpha = (0.25f + 0.75f * level).coerceAtMost(1f)), r, c)
        }
    }
}

private val Mood.animated: Boolean get() = this == Mood.IDLE || this == Mood.LISTENING || this == Mood.THINKING || this == Mood.WORKING

private val Mood.spoken: String
    get() = when (this) {
        Mood.IDLE -> "idle"
        Mood.LISTENING -> "listening"
        Mood.THINKING -> "thinking"
        Mood.WORKING -> "working on a task"
        Mood.PAUSED -> "paused"
        Mood.OFFLINE -> "offline"
        Mood.ERROR -> "something went wrong"
    }

/** Sleep until the next visible change: idle only wakes for a blink; active states tick at 12–20 fps. */
private fun Mood.frameDelay(now: Long): Long = when (this) {
    Mood.IDLE -> {
        val phase = now % BLINK_PERIOD
        when {
            phase < BLINK_LEN -> 40L
            else -> (BLINK_PERIOD - phase).coerceIn(40L, BLINK_PERIOD)
        }
    }
    Mood.LISTENING -> 50L
    Mood.THINKING -> 80L
    Mood.WORKING -> 60L
    else -> 1000L
}

private const val BLINK_PERIOD = 5200L
private const val BLINK_LEN = 140L

/** Brightness 0..1 for each dot, row-major. Pure function of state and time, so it is unit-testable. */
internal fun faceFrame(state: Mood, t: Long, animate: Boolean): FloatArray {
    val f = FloatArray(COLS * ROWS)
    fun dot(c: Int, r: Int, v: Float = 1f) { if (c in 0 until COLS && r in 0 until ROWS) f[r * COLS + c] = maxOf(f[r * COLS + c], v) }
    fun eyes(top: Int, height: Int, dx: Int = 0, v: Float = 1f) {
        for (r in top until top + height) for (c in 0..1) { dot(2 + c + dx, r, v); dot(7 + c + dx, r, v) }
    }
    fun mouth(from: Int, to: Int, row: Int = 6, v: Float = 1f) { for (c in from..to) dot(c, row, v) }
    when (state) {
        Mood.IDLE -> {
            val blinking = animate && t % BLINK_PERIOD < BLINK_LEN
            if (blinking) eyes(4, 1) else eyes(2, 3)
            mouth(4, 6)
        }
        Mood.LISTENING -> {
            eyes(1, 4)
            // Five bars that rise and fall: an open, attentive mouth.
            for (i in 0 until 5) {
                val h = if (animate) 1 + ((sin(t / 140.0 + i * 1.3) + 1) * 1.2).toInt() else listOf(1, 2, 3, 2, 1)[i]
                for (k in 0 until h) dot(3 + i, 7 - k)
            }
        }
        Mood.THINKING -> {
            // Eyes glance up and to the side; three dots pulse in turn.
            eyes(1, 2, dx = 1)
            val lit = if (animate) ((t / 320) % 3).toInt() else -1
            for (i in 0 until 3) dot(4 + i, 6, if (i == lit || lit < 0) 1f else 0.35f)
        }
        Mood.WORKING -> {
            eyes(3, 1)
            mouth(4, 6, v = 0.6f)
            // A scanner sweeping the bottom row.
            val pos = if (animate) {
                val x = (t / 70.0) % ((COLS - 1) * 2)
                if (x < COLS - 1) x else (COLS - 1) * 2 - x
            } else (COLS - 1) / 2.0
            for (c in 0 until COLS) {
                val d = abs(c - pos)
                if (d < 3) dot(c, 8, (1 - d / 3).toFloat())
            }
        }
        Mood.PAUSED -> {
            // Eyes closed, resting; nothing moves.
            eyes(4, 1, v = 0.7f)
            mouth(4, 6, v = 0.5f)
        }
        Mood.OFFLINE -> {
            for (k in 0..2) {
                dot(1 + k, 2 + k, 0.55f); dot(3 - k, 2 + k, 0.55f)
                dot(7 + k, 2 + k, 0.55f); dot(9 - k, 2 + k, 0.55f)
            }
            mouth(3, 4, v = 0.45f)
            mouth(6, 7, v = 0.45f)
        }
        Mood.ERROR -> {
            dot(2, 2); dot(3, 3); dot(2, 4)
            dot(8, 2); dot(7, 3); dot(8, 4)
            dot(3, 7); dot(4, 6); dot(5, 7); dot(6, 6); dot(7, 7)
        }
    }
    return f
}

/** One-line, truthful description of what Solis is doing (used next to the face). */
fun Mood.caption(): String = when (this) {
    Mood.IDLE -> "Ready"
    Mood.LISTENING -> "Listening…"
    Mood.THINKING -> "Thinking…"
    Mood.WORKING -> "Working"
    Mood.PAUSED -> "Paused"
    Mood.OFFLINE -> "Offline"
    Mood.ERROR -> "Hit a problem"
}
