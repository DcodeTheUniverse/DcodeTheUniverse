package com.dcode.vault.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dcode.crypto.PasswordGenerator
import com.dcode.vault.ui.theme.V
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Animated vault dial: tick ring rotates while locked; [openness] 0→1 spins it open. */
@Composable
fun VaultDial(modifier: Modifier = Modifier, openness: Float = 0f, busy: Boolean = false) {
    val p = V.palette
    val t = rememberInfiniteTransition(label = "dial")
    val idle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 2200 else 30_000, easing = LinearEasing)), label = "idle")
    val pulse by t.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val open by animateFloatAsState(openness, spring(dampingRatio = 0.7f, stiffness = 120f), label = "open")
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        val w = r * 0.022f
        drawCircle(p.hairline, r * 0.98f, c, style = Stroke(w))
        rotate(idle + open * 540f, c) {
            for (i in 0 until 60) {
                val a = (i / 60f) * 2 * PI.toFloat()
                val long = i % 5 == 0
                val inner = r * (if (long) 0.80f else 0.85f)
                drawLine(
                    if (long) p.ink.copy(alpha = 0.8f) else p.inkFaint.copy(alpha = 0.6f),
                    Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    Offset(c.x + cos(a) * r * 0.9f, c.y + sin(a) * r * 0.9f),
                    strokeWidth = if (long) w * 1.3f else w * 0.8f, cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(p.accent.copy(alpha = 0.12f + 0.1f * open), r * 0.62f * pulse, c)
        drawCircle(p.accent, r * 0.62f * pulse, c, style = Stroke(w * 1.6f))
        rotate(-idle * 0.6f - open * 180f, c) {
            drawArc(p.accent, -90f, 300f * (1f - open) + 60f, false, Offset(c.x - r * 0.45f, c.y - r * 0.45f), Size(r * 0.9f, r * 0.9f), style = Stroke(w * 2.2f, cap = StrokeCap.Round))
        }
        // Keyhole
        val kh = r * 0.11f * (1f - open * 0.5f)
        drawCircle(p.ink, kh, Offset(c.x, c.y - kh * 0.4f))
        drawRoundRect(p.ink, Offset(c.x - kh * 0.4f, c.y - kh * 0.2f), Size(kh * 0.8f, kh * 1.8f), androidx.compose.ui.geometry.CornerRadius(kh * 0.3f))
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Accent,
    icon: ImageVector? = null,
) {
    val p = V.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    val (bg, fg) = when (style) {
        ButtonStyle.Accent -> p.accent to p.onAccent
        ButtonStyle.Ink -> p.ink to p.background
        ButtonStyle.Outline -> Color.Transparent to p.ink
        ButtonStyle.Danger -> p.danger to Color.White
    }
    Row(
        modifier.scale(scale).height(54.dp).clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .then(if (style == ButtonStyle.Outline) Modifier.border(BorderStroke(1.dp, p.hairline.copy(alpha = 1f)), CircleShape) else Modifier)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = if (enabled) 1f else 0.6f), maxLines = 1)
    }
}

enum class ButtonStyle { Accent, Ink, Outline, Danger }

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = V.palette
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.ink) } else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = if (V.palette.isDark) 0.16f else 0.11f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = V.palette
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(22.dp))) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = V.palette.inkSoft, modifier = modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, tint: Color = V.palette.ink, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val p = V.palette
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, if (tint == p.ink) p.accent else tint, size = 38.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (tint == p.danger) p.danger else p.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
        }
        trailing?.invoke()
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null, placeholder: String = "Search names and contents") {
    val p = V.palette
    val active = value.isNotEmpty()
    val border by animateColorAsState(if (active) p.accent else p.hairline, label = "searchBorder")
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(p.surface).border(1.dp, border, CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (active) p.accent else p.inkSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = p.inkFaint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            )
        }
        AnimatedVisibility(active, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Clear", tint = p.inkSoft) }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val p = V.palette
    val bg by animateColorAsState(if (selected) p.ink else p.background, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (selected) p.background else p.ink, tween(220), label = "chipFg")
    Row(
        Modifier.clip(CircleShape).background(bg).border(1.dp, if (selected) p.ink else p.hairline, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun StrengthMeter(password: String, modifier: Modifier = Modifier) {
    val p = V.palette
    val s = PasswordGenerator.strength(password)
    val level = if (password.isEmpty()) 0 else s.ordinal + 1
    val color = when (s) {
        PasswordGenerator.Strength.VeryWeak, PasswordGenerator.Strength.Weak -> p.danger
        PasswordGenerator.Strength.Fair -> p.note
        else -> p.success
    }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..5) {
                val c by animateColorAsState(if (i <= level) color else p.hairline, tween(250), label = "seg$i")
                Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(c))
            }
        }
        if (password.isNotEmpty()) {
            Text("${s.label} · ~${PasswordGenerator.entropyBits(password).toInt()} bits", style = MaterialTheme.typography.bodySmall, color = p.inkSoft, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun CountdownRing(fraction: Float, modifier: Modifier = Modifier, color: Color = V.palette.accent) {
    val animated by animateFloatAsState(fraction, tween(900, easing = LinearEasing), label = "countdown")
    val track = V.palette.hairline
    Canvas(modifier) {
        val w = size.minDimension * 0.12f
        drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(color, -90f, 360f * animated, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = V.palette.inkSoft) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center, modifier = modifier)
}

@Composable
fun FieldLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = V.palette.inkSoft, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
}

/** Outlined single-field input styled for the vault. */
@Composable
fun VaultField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = V.palette
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = textStyle, color = p.inkFaint)
            BasicTextField(
                value, onChange, singleLine = singleLine, textStyle = textStyle.copy(color = p.ink), cursorBrush = SolidColor(p.accent),
                visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}
