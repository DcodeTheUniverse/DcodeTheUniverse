package com.axiom.launcher.ui.effects

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.random.Random

/** True when rendering a still frame (screenshot tests): effects draw once instead of animating. */
val LocalStillFrame = androidx.compose.runtime.staticCompositionLocalOf { false }

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜ0123456789ABCDEF<>/{}[]=+*#$%"

/** Classic digital rain. [intensity] 0..1 controls opacity so it can sit behind the home screen. */
@Composable
fun MatrixRain(modifier: Modifier = Modifier, intensity: Float = 1f, color: Color = Color(0xFF00FF9C)) {
    var frame by remember { mutableLongStateOf(0L) }
    val rain = remember { RainState() }
    val still = LocalStillFrame.current
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        // ~20 fps is plenty for rain and keeps the launcher light on battery.
        while (true) withInfiniteAnimationFrameMillis { t -> if (t - last >= 50) { last = t; frame = t } }
    }
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    Canvas(modifier) {
        val cell = 14.dp.toPx()
        paint.textSize = cell * 0.95f
        val cols = (size.width / cell).toInt() + 1
        if (rain.drops.size != cols) {
            rain.drops = FloatArray(cols) { Random.nextFloat() * size.height / cell }
            rain.speeds = FloatArray(cols) { 0.35f + Random.nextFloat() * 0.6f }
        }
        val drops = rain.drops
        val speeds = rain.speeds
        frame // read to invalidate each frame
        val rows = (size.height / cell).toInt() + 2
        drawIntoCanvas { c ->
            for (i in 0 until cols) {
                val head = drops[i]
                val tail = 18
                for (k in 0 until tail) {
                    val row = head.toInt() - k
                    if (row < 0 || row > rows) continue
                    val a = ((1f - k / tail.toFloat()) * intensity).coerceIn(0f, 1f)
                    paint.color = if (k == 0) Color.White.copy(alpha = a).toArgb() else color.copy(alpha = a * 0.8f).toArgb()
                    val ch = GLYPHS[((row * 31 + i * 17 + (frame / 140).toInt() * (k % 3)) and 0x7fffffff) % GLYPHS.length]
                    c.nativeCanvas.drawText(ch.toString(), i * cell, row * cell, paint)
                }
                drops[i] = if (head - tail > rows) -Random.nextInt(0, 20).toFloat() else head + speeds[i]
            }
        }
    }
}

private class RainState {
    var drops = FloatArray(0)
    var speeds = FloatArray(0)
}

/** CRT scanlines + faint vignette, drawn over content. */
fun Modifier.scanlines(enabled: Boolean, color: Color = Color(0xFF00FF9C)): Modifier =
    if (!enabled) this else this.drawWithContent {
        drawContent()
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        drawRect(color.copy(alpha = 0.025f))
    }

/** Text that occasionally glitches with chromatic offsets, Netrunner style. */
@Composable
fun GlitchText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    var glitch by remember { mutableIntStateOf(0) }
    var shown by remember(text) { mutableStateOf(text) }
    val still = LocalStillFrame.current
    LaunchedEffect(enabled, text, still) {
        shown = text
        if (!enabled || still) return@LaunchedEffect
        while (true) {
            delay(2500L + Random.nextLong(4000))
            repeat(4) {
                glitch = Random.nextInt(-6, 7)
                shown = text.map { if (it.isLetterOrDigit() && Random.nextFloat() < 0.25f) GLYPHS.random() else it }.joinToString("")
                delay(55)
            }
            glitch = 0
            shown = text
        }
    }
    Box(modifier) {
        if (glitch != 0) {
            Text(shown, style = style.copy(color = p.accent2.copy(alpha = 0.8f)), modifier = Modifier.padding(start = (glitch.coerceAtLeast(0)).dp))
            Text(shown, style = style.copy(color = p.accent.copy(alpha = 0.8f)), modifier = Modifier.padding(start = ((-glitch).coerceAtLeast(0)).dp))
        }
        Text(shown, style = style)
    }
}

@Composable
fun blinkAlpha(): Float {
    if (LocalStillFrame.current) return 1f
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    return if (a > 0.5f) 1f else 0f
}

private val BOOT_LINES = listOf(
    "AXIOM//OS netrunner kernel 6.6.6-neon",
    "[ ok ] mounting /dev/neural0",
    "[ ok ] bypassing corporate ICE ........",
    "[ ok ] loading claude-link daemon",
    "[ ok ] spoofing MAC 00:DE:C0:DE:13:37",
    "[warn] black ICE detected · rerouting",
    "[ ok ] tunnel established via night.city",
    "",
    "> wake up, runner.",
    "> the net is yours.",
)

/** Full-screen boot animation shown when jacking into Netrunner mode. */
@Composable
fun BootSequence(onDone: () -> Unit) {
    val p = LocalPalette.current
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in BOOT_LINES.indices) {
            delay(if (BOOT_LINES[i].startsWith(">")) 260 else 120)
            count = i + 1
        }
        delay(700)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { onDone() }
            .scanlines(true)
    ) {
        MatrixRain(Modifier.fillMaxSize(), intensity = 0.25f)
        Column(Modifier.statusBarsPadding().padding(24.dp)) {
            BOOT_LINES.take(count).forEach { line ->
                val c = when {
                    line.startsWith("[warn]") -> p.warn
                    line.startsWith(">") -> p.accent2
                    else -> p.fg
                }
                Text(line, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = c,
                    fontWeight = if (line.startsWith(">")) FontWeight.Bold else FontWeight.Normal))
            }
            Text("█", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = p.fg.copy(alpha = blinkAlpha())))
        }
    }
}

@Composable
fun MonoText(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal) {
    Text(text, modifier = modifier, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size, color = color, fontWeight = weight))
}
