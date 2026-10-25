package com.dcode.circleid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dcode.circleid.ui.theme.Circle
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The Circle ID mark: concentric rings that slowly counter-rotate and breathe. */
@Composable
fun BreathingRings(modifier: Modifier = Modifier, active: Boolean = true, seed: String = "") {
    val p = Circle.palette
    val t = rememberInfiniteTransition(label = "rings")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0.94f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val energy by animateFloatAsState(if (active) 1f else 0.4f, tween(600), label = "energy")
    val bits = remember(seed) { seed.filter { it.isLetterOrDigit() }.map { it.code } }
    Canvas(modifier.scale(breath)) {
        val r = size.minDimension / 2
        val c = center
        val stroke = r * 0.018f
        for (i in 0 until 5) {
            val rr = r * (1f - i * 0.16f)
            val alpha = (0.18f + 0.16f * (4 - i)) * energy
            rotate(if (i % 2 == 0) spin else -spin * 1.3f, c) {
                val dashOn = r * (0.04f + 0.03f * ((bits.getOrNull(i) ?: (i * 7)) % 5))
                drawCircle(
                    color = p.ink.copy(alpha = alpha.coerceIn(0f, 1f)),
                    radius = rr,
                    center = c,
                    style = Stroke(
                        width = stroke * (if (i == 0) 1.6f else 1f),
                        cap = StrokeCap.Round,
                        pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOn * 0.6f + i * 3f)),
                    ),
                )
            }
        }
        // Identity glyph: 16 radial ticks whose lengths encode the Circle ID.
        val glyphR = r * 0.30f
        for (i in 0 until 16) {
            val v = ((bits.getOrNull(i) ?: (i * 31 + 7)) % 7) / 6f
            val a = (i / 16f) * 2 * PI.toFloat() + spin / 180f * PI.toFloat() * 0.2f
            val inner = glyphR * 0.55f
            val outer = inner + glyphR * (0.18f + 0.45f * v)
            drawLine(
                color = p.ink.copy(alpha = 0.85f * energy),
                start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                strokeWidth = stroke * 1.4f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(p.ink.copy(alpha = energy), radius = r * 0.06f, center = c)
    }
}

/** A thin progress ring drawn around the camera viewport. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, color: Color = Circle.palette.ink, track: Color = Circle.palette.hairline, width: Dp = 4.dp) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "progress")
    Canvas(modifier) {
        val w = width.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * animated, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Scanning sweep shown while a factor is being evaluated. */
@Composable
fun ScanSweep(modifier: Modifier = Modifier) {
    val p = Circle.palette
    val t = rememberInfiniteTransition(label = "scan")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        val w = 3.dp.toPx()
        rotate(a) {
            drawArc(p.ink, 0f, 70f, false, topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w), style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** Draws a circle, then a check (or a cross) with path-trim animation. */
@Composable
fun ResultMark(success: Boolean, modifier: Modifier = Modifier) {
    val p = Circle.palette
    val ring = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(success) {
        ring.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        haptics.performHapticFeedback(if (success) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }
    val color = if (success) p.ink else p.danger
    Canvas(modifier) {
        val w = size.minDimension * 0.045f
        drawArc(color, -90f, 360f * ring.value, false, topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w), style = Stroke(w, cap = StrokeCap.Round))
        fun segment(points: List<Offset>, fraction: Float): Path {
            val full = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (pt in points.drop(1)) lineTo(pt.x, pt.y)
            }
            val measure = PathMeasure().apply { setPath(full, false) }
            return Path().also { measure.getSegment(0f, measure.length * fraction.coerceIn(0f, 1f), it, true) }
        }
        val sw = size.width
        val sh = size.height
        val partial = if (success) {
            segment(listOf(Offset(sw * 0.30f, sh * 0.52f), Offset(sw * 0.44f, sh * 0.66f), Offset(sw * 0.71f, sh * 0.37f)), mark.value)
        } else {
            Path().apply {
                addPath(segment(listOf(Offset(sw * 0.35f, sh * 0.35f), Offset(sw * 0.65f, sh * 0.65f)), mark.value * 2))
                addPath(segment(listOf(Offset(sw * 0.65f, sh * 0.35f), Offset(sw * 0.35f, sh * 0.65f)), mark.value * 2 - 1))
            }
        }
        drawPath(partial, color, style = Stroke(w * 1.2f, cap = StrokeCap.Round))
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    icon: ImageVector? = null,
) {
    val p = Circle.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    val bg = if (filled) p.ink else Color.Transparent
    val fg = if (filled) p.background else p.ink
    Row(
        modifier
            .scale(scale)
            .height(56.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.3f))
            .then(if (filled) Modifier else Modifier.border(BorderStroke(1.dp, p.ink.copy(alpha = if (enabled) 0.9f else 0.3f)), CircleShape))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Circle.palette.ink) }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Circle.palette.ink, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = Circle.palette
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.inkSoft, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.raised).border(1.dp, p.hairline, RoundedCornerShape(24.dp)),
        ) { content() }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = Circle.palette
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(p.background).border(1.dp, p.hairline, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = p.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
        }
        trailing?.invoke()
    }
}

@Composable
fun MonoSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = Circle.palette
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.background, checkedTrackColor = p.ink, checkedBorderColor = p.ink,
            uncheckedThumbColor = p.inkSoft, uncheckedTrackColor = p.background, uncheckedBorderColor = p.inkFaint,
        ),
    )
}

/** Fades and lifts children in one after another. */
@Composable
fun Staggered(index: Int, baseDelayMs: Long = 60, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * baseDelayMs)
        visible = true
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(380)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 3 }) {
        content()
    }
}

@Composable
fun FactorChip(label: String, passed: Boolean?, detail: String, modifier: Modifier = Modifier) {
    val p = Circle.palette
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val c = when (passed) {
            true -> p.ink
            false -> p.danger
            null -> p.inkFaint
        }
        Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, c, CircleShape), contentAlignment = Alignment.Center) {
            if (passed == true) Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            if (passed == false) Text("!", color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
        }
    }
}

@Composable
fun Waveform(samples: FloatArray, modifier: Modifier = Modifier) {
    val p = Circle.palette
    Canvas(modifier) {
        if (samples.size < 2) {
            drawLine(p.hairline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            return@Canvas
        }
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i / (samples.size - 1f) * size.width
            val y = size.height / 2 - v * size.height * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, p.ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Circle.palette.inkSoft, textAlign = TextAlign.Center, modifier = modifier)
}
