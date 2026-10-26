package com.axiom.launcher.ui.solis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.ui.effects.motionAllowed
import com.axiom.launcher.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Face grid: 11 x 9 dots. */
private const val COLS = 11
private const val ROWS = 9

/**
 * Solis's dot-matrix face, drawn as a curved 3D head: every dot is a small lit sphere set into the surface,
 * lit dots stand out of it (with a glow on dark themes and a cast shadow on light ones), and the head turns
 * with its state — up and aside while thinking, leaning in while listening, down when paused. The
 * expression is driven only by real engine state (see [Mood]). With reduced motion, battery saver or
 * thermal pressure each state is a still 3D pose and expression, so nothing is conveyed by motion alone.
 * With [interactive], dragging tilts the head; it springs back on release.
 */
@Composable
fun SolisFace(
    state: Mood,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color? = null,
    interactive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val motion = motionAllowed()
    val animate = motion && state.animated
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
    val socket = p.faint
    val widthPx = with(LocalDensity.current) { size.toPx() }
    val sprites = remember(on, socket, p.isDark, widthPx.toInt()) { Sprites.build(on, socket, p.isDark, widthPx) }
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    var m = modifier
        .size(size, size * FACE_ASPECT)
        .semantics {
            contentDescription = "Solis"
            stateDescription = state.spoken
        }
    if (interactive) m = m.pointerInput(motion) {
        val limit = this.size.width * 0.45f
        fun release() = scope.launch { if (motion) drag.animateTo(Offset.Zero, spring(dampingRatio = 0.42f, stiffness = 180f)) else drag.snapTo(Offset.Zero) }
        detectDragGestures(onDragEnd = { release() }, onDragCancel = { release() }) { change, d ->
            change.consume()
            scope.launch { drag.snapTo(Offset((drag.value.x + d.x).coerceIn(-limit, limit), (drag.value.y + d.y).coerceIn(-limit, limit))) }
        }
    }
    if (onClick != null) m = m.clickable(onClickLabel = "Open Solis's brain", onClick = onClick)

    Canvas(m) {
        val levels = faceFrame(state, t, animate)
        val base = facePose(state, t, animate)
        val d = drag.value
        val pose = base.copy(yaw = base.yaw + d.x / this.size.width * 1.4f, pitch = base.pitch - d.y / this.size.height * 1.1f)
        drawFace(levels, projectFace(levels, pose), sprites, p.isDark, p.fg)
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

/**
 * Sleep until the next visible change. Idle wakes only to blink and for an occasional glance; active
 * states tick at 20–30 fps while they last.
 */
private fun Mood.frameDelay(now: Long): Long = when (this) {
    Mood.IDLE -> {
        val b = now % BLINK_PERIOD
        val g = now % GLANCE_PERIOD
        if (b < BLINK_LEN || g < GLANCE_LEN) 33L
        else minOf(BLINK_PERIOD - b, GLANCE_PERIOD - g).coerceIn(33L, BLINK_PERIOD)
    }
    Mood.LISTENING -> 33L
    Mood.THINKING -> 50L
    Mood.WORKING -> 40L
    else -> 1000L
}

private const val BLINK_PERIOD = 5200L
private const val BLINK_LEN = 140L
private const val GLANCE_PERIOD = 9100L
private const val GLANCE_LEN = 1500L

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

/**
 * Head orientation in radians: [yaw] > 0 turns toward the right of the screen, [pitch] > 0 looks up,
 * [roll] tilts clockwise; [lean] > 1 brings the face closer.
 */
internal data class Pose(val yaw: Float, val pitch: Float, val roll: Float = 0f, val lean: Float = 1f)

/** Where the head points in each state. Pure function of state and time; constant when not animating. */
internal fun facePose(state: Mood, t: Long, animate: Boolean): Pose {
    val s = t / 1000.0
    return when (state) {
        Mood.IDLE -> {
            if (!animate) return Pose(-0.06f, 0.04f)
            val g = t % GLANCE_PERIOD
            if (g >= GLANCE_LEN) return Pose(-0.06f, 0.04f)
            // A slow look to one side and back; alternates direction each time.
            val u = g / GLANCE_LEN.toDouble()
            val dir = if ((t / GLANCE_PERIOD) % 2 == 0L) 1 else -1
            val e = sin(PI * u).let { it * it }
            Pose((-0.06 + dir * 0.18 * e).toFloat(), (0.04 + 0.05 * e).toFloat())
        }
        Mood.LISTENING -> if (!animate) Pose(0f, -0.02f, lean = 1.05f)
            else Pose((0.03 * sin(s * 1.3)).toFloat(), (-0.02 + 0.04 * sin(s * 4.2)).toFloat(), lean = 1.05f)
        Mood.THINKING -> if (!animate) Pose(0.13f, 0.1f)
            else Pose((0.13 + 0.04 * sin(s * 1.1)).toFloat(), (0.1 + 0.03 * sin(s * 1.7)).toFloat(), (0.03 * sin(s * 0.9)).toFloat())
        Mood.WORKING -> if (!animate) Pose(0f, -0.08f)
            else Pose((0.08 * sin(s * 0.8)).toFloat(), (-0.08 + 0.02 * sin(s * 2.0)).toFloat())
        Mood.PAUSED -> Pose(-0.05f, -0.16f)
        Mood.OFFLINE -> Pose(-0.2f, -0.08f)
        Mood.ERROR -> Pose(0.03f, 0f, roll = 0.1f)
    }
}

private const val SPAN = 0.62f
private const val CAMERA = 5f
private const val POP = 0.08f
internal const val DOT_R = 0.047f
/** Half-width of the face in the rest pose, in projected units. */
private val REST_HALF: Float = sin(SPAN) * CAMERA / (CAMERA - cos(SPAN))
/** The canvas spans ±[FACE_HALF_W] × ±[FACE_HALF_H] projected units: room for the head to turn. */
internal val FACE_HALF_W: Float = REST_HALF * 1.34f
internal const val FACE_ASPECT = 0.88f
internal val FACE_HALF_H: Float = FACE_HALF_W * FACE_ASPECT
private const val LX = -0.43f
private const val LY = -0.57f
private const val LZ = 0.70f

/** Dots after the face surface is curved, turned and put in perspective (units: about ±0.75 across). */
internal class Projected(n: Int) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
    val scale = FloatArray(n)
    /** Lambert term against the key light, 0..1. */
    val light = FloatArray(n)
    /** How squarely the surface faces the viewer, 0..1: dots turning away draw a little smaller. */
    val facing = FloatArray(n)
}

/** Projects the dot grid onto a sphere cap, rotates it by [pose], and applies perspective. */
internal fun projectFace(levels: FloatArray, pose: Pose): Projected {
    val out = Projected(COLS * ROWS)
    val cy = cos(pose.yaw); val sy = sin(pose.yaw)
    val cp = cos(pose.pitch); val sp = sin(pose.pitch)
    val cr = cos(pose.roll); val sr = sin(pose.roll)
    val half = (COLS - 1) / 2f
    val mid = (ROWS - 1) / 2f
    val ll = sqrt(LX * LX + LY * LY + LZ * LZ)
    for (row in 0 until ROWS) for (col in 0 until COLS) {
        val i = row * COLS + col
        val lon = (col - half) / half * SPAN
        val lat = (row - mid) / half * SPAN
        val r = 1f + POP * levels[i]
        val x = cos(lat) * sin(lon) * r
        val y = sin(lat) * r
        val z = cos(lat) * cos(lon) * r
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val x3 = x1 * cr - y2 * sr
        val y3 = x1 * sr + y2 * cr
        val persp = CAMERA / (CAMERA - z2) * pose.lean
        out.x[i] = x3 * persp
        out.y[i] = y3 * persp
        out.z[i] = z2
        out.scale[i] = persp
        val len = sqrt(x3 * x3 + y3 * y3 + z2 * z2)
        out.light[i] = max(0f, (x3 * LX + y3 * LY + z2 * LZ) / (len * ll))
        out.facing[i] = max(0f, z2 / len)
        out.scale[i] = persp * (0.55f + 0.45f * out.facing[i])
    }
    return out
}

/** Pre-rendered sphere sprites, so a frame is ~100 bitmap draws instead of ~100 new gradient shaders. */
private class Sprites(val lit: ImageBitmap, val socket: ImageBitmap, val halo: ImageBitmap) {
    companion object {
        fun build(on: Color, socket: Color, dark: Boolean, widthPx: Float): Sprites {
            val unit = widthPx / 2f / FACE_HALF_W
            val px = ceil(DOT_R * unit * 2f * 1.5f).toInt().coerceIn(8, 160)
            val opaqueSocket = socket.copy(alpha = 1f)
            val lit = render(px) {
                val c = Offset(px / 2f, px / 2f)
                drawCircle(
                    Brush.radialGradient(
                        0f to lerp(on, Color.White, if (dark) 0.75f else 0.55f),
                        0.38f to on,
                        1f to lerp(on, Color.Black, if (dark) 0.5f else 0.35f),
                        center = Offset(px * 0.36f, px * 0.32f), radius = px * 0.78f,
                    ),
                    radius = px / 2f, center = c,
                )
            }
            val sock = render(px) {
                // A small concave pit: shaded at the top-left rim, lit toward the bottom-right.
                drawCircle(
                    Brush.radialGradient(
                        0f to lerp(opaqueSocket, Color.White, 0.18f),
                        0.65f to opaqueSocket,
                        1f to lerp(opaqueSocket, Color.Black, 0.4f),
                        center = Offset(px * 0.62f, px * 0.66f), radius = px * 0.72f,
                    ),
                    radius = px / 2f, center = Offset(px / 2f, px / 2f),
                )
            }
            val hp = px * 2
            val halo = render(hp) {
                val core = if (dark) on else Color.Black
                drawCircle(
                    Brush.radialGradient(
                        0f to core.copy(alpha = if (dark) 0.8f else 0.55f),
                        0.4f to core.copy(alpha = if (dark) 0.22f else 0.18f),
                        1f to core.copy(alpha = 0f),
                        center = Offset(hp / 2f, hp / 2f), radius = hp / 2f,
                    ),
                    radius = hp / 2f, center = Offset(hp / 2f, hp / 2f),
                )
            }
            return Sprites(lit, sock, halo)
        }

        private fun render(px: Int, block: DrawScope.() -> Unit): ImageBitmap {
            val img = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat()), block)
            return img
        }
    }
}

private fun DrawScope.sprite(img: ImageBitmap, cx: Float, cy: Float, diameter: Float, alpha: Float) {
    if (alpha <= 0.01f || diameter < 0.5f) return
    val s = diameter / img.width
    withTransform({
        translate(cx - diameter / 2f, cy - diameter / 2f)
        scale(s, s, pivot = Offset.Zero)
    }) {
        drawImage(img, alpha = alpha.coerceAtMost(1f))
    }
}

private fun DrawScope.drawFace(levels: FloatArray, pr: Projected, sp: Sprites, dark: Boolean, fg: Color) {
    val unit = size.width / 2f / FACE_HALF_W
    val cx = size.width / 2f
    val cy = size.height / 2f
    val n = levels.size
    // A faint head volume behind the matrix, following the head and lit from the same side as the dots.
    val headR = size.height * 0.5f
    val head = Offset(cx + pr.x[n / 2] * unit * 0.35f, cy + pr.y[n / 2] * unit * 0.35f)
    drawCircle(
        Brush.radialGradient(
            0f to fg.copy(alpha = if (dark) 0.07f else 0.045f),
            1f to fg.copy(alpha = 0f),
            center = Offset(head.x - headR * 0.25f, head.y - headR * 0.3f), radius = headR,
        ),
        radius = headR, center = head,
    )
    // Glow (dark themes) or cast shadow (light themes) under lit dots.
    for (i in 0 until n) {
        val lv = levels[i]
        if (lv <= 0.02f) continue
        val r = DOT_R * unit * pr.scale[i] * (1f + 0.1f * lv)
        if (dark) sprite(sp.halo, cx + pr.x[i] * unit, cy + pr.y[i] * unit, r * 5.2f, 0.32f * lv)
        else sprite(sp.halo, cx + pr.x[i] * unit + r * 0.45f, cy + pr.y[i] * unit + r * 0.7f, r * 2.9f, 0.3f * lv)
    }
    // Sockets of unlit dots, shaded by how they face the light.
    for (i in 0 until n) {
        if (levels[i] > 0.02f) continue
        val r = DOT_R * unit * pr.scale[i] * 0.8f
        sprite(sp.socket, cx + pr.x[i] * unit, cy + pr.y[i] * unit, r * 2f, (if (dark) 0.5f else 0.55f) * (0.45f + 0.55f * pr.light[i]))
    }
    // Lit dots, back to front.
    val order = (0 until n).filter { levels[it] > 0.02f }.sortedBy { pr.z[it] }
    for (i in order) {
        val lv = levels[i]
        val r = DOT_R * unit * pr.scale[i] * (1f + 0.1f * lv)
        sprite(sp.lit, cx + pr.x[i] * unit, cy + pr.y[i] * unit, r * 2f, (0.3f + 0.7f * lv) * (0.62f + 0.38f * pr.light[i]))
    }
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
