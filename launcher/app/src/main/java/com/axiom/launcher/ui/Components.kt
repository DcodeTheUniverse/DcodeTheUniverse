package com.axiom.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.ui.theme.LocalMode
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.LocalType
import com.axiom.launcher.ui.theme.ThemeMode
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Netrunner: Boolean @Composable get() = LocalMode.current == ThemeMode.NETRUNNER

/** Money as people read it: cents for normal amounts, more precision for fractions of a cent. */
fun usd(v: Double): String = if (v != 0.0 && kotlin.math.abs(v) < 0.01) "$" + "%.4f".format(v) else "$" + "%.2f".format(v)

/** Stops taps on an overlay's empty space from reaching the screen underneath. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

/** App label as shown in lists: Netrunner turns "Google Maps" into "./google_maps". */
@Composable
fun displayLabel(label: String): String =
    if (Netrunner) "./" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') else label

fun shapeFor(mode: ThemeMode): Shape = if (mode == ThemeMode.NETRUNNER) RoundedCornerShape(2.dp) else RoundedCornerShape(18.dp)

@Composable
fun AppIcon(vm: LauncherViewModel, app: AppInfo, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, app.key, px) {
        value = withContext(Dispatchers.IO) { vm.icon(app, px) }
    }
    val p = LocalPalette.current
    val filter = if (vm.monoIcons) {
        val m = ColorMatrix().apply { setToSaturation(0f) }
        if (Netrunner) ColorFilter.tint(p.fg.copy(alpha = 0.95f), androidx.compose.ui.graphics.BlendMode.Modulate)
        else ColorFilter.colorMatrix(m)
    } else null
    Box(Modifier.size(size)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(size), colorFilter = filter) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    vm: LauncherViewModel,
    app: AppInfo,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = LocalType.current.appSize,
    dim: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { vm.open(app) },
                onLongClick = onLongClick?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
            )
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.showIcons) {
            AppIcon(vm, app, 30.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            displayLabel(app.label),
            style = lStyle(size, LocalType.current.appWeight, if (dim || !app.runnable) p.dim else p.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val badge = when {
            app.paused -> "paused"
            app.suspended -> "suspended"
            app.profile == com.axiom.launcher.platform.ProfileKind.WORK -> "work"
            app.profile == com.axiom.launcher.platform.ProfileKind.PRIVATE -> "private"
            app.profile == com.axiom.launcher.platform.ProfileKind.CLONE -> "clone"
            else -> null
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(badge, style = lStyle(11.sp, color = p.dim))
        }
        if (vm.focusActive && vm.isDistracting(app)) {
            Spacer(Modifier.width(8.dp))
            Text(if (Netrunner) "[locked]" else "· focus", style = lStyle(11.sp, color = p.dim))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        if (Netrunner) "// ${text.lowercase()}" else text.uppercase(),
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
        style = lStyle(11.sp, FontWeight.Medium, p.dim).copy(letterSpacing = if (Netrunner) 0.sp else 2.sp),
    )
}

@Composable
fun Chip(text: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val mode = LocalMode.current
    val shape = if (mode == ThemeMode.NETRUNNER) RoundedCornerShape(2.dp) else RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) p.accent else Color.Transparent)
            .border(1.dp, if (selected) p.accent else p.faint, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) p.onAccent else p.fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(if (mode == ThemeMode.NETRUNNER) "[$text]" else text, style = lStyle(13.sp, FontWeight.Medium, if (selected) p.onAccent else p.fg))
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = lStyle(16.sp))
            if (subtitle != null) Text(subtitle, style = lStyle(12.sp, color = p.dim))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
                uncheckedThumbColor = p.dim, uncheckedTrackColor = p.surface, uncheckedBorderColor = p.faint,
            ),
        )
    }
}

@Composable
fun ActionRow(icon: ImageVector?, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = p.fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = lStyle(16.sp))
            if (subtitle != null) Text(subtitle, style = lStyle(12.sp, color = p.dim))
        }
    }
}

/** Minimal markdown: **bold**, *italic*, `code`, # headings, bullets. Enough for chat replies. */
@Composable
fun markdown(text: String): AnnotatedString {
    val p = LocalPalette.current
    val codeBg = p.surfaceHi
    return buildAnnotatedString {
        val lines = text.split("\n")
        var inFence = false
        lines.forEachIndexed { li, raw ->
            if (raw.trimStart().startsWith("```")) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(raw) }
                if (li < lines.lastIndex) append("\n")
                return@forEachIndexed
            }
            var line = raw
            var heading = false
            Regex("^#{1,6}\\s+").find(line)?.let { line = line.substring(it.value.length); heading = true }
            Regex("^(\\s*)[-*•]\\s+").find(line)?.let { line = it.groupValues[1] + "•  " + line.substring(it.value.length) }
            val start = length
            inline(line, codeBg, p.accent)
            if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            if (li < lines.lastIndex) append("\n")
        }
    }
}

private fun AnnotatedString.Builder.inline(s: String, codeBg: Color, link: Color) {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' -> {
                val end = s.indexOf('*', i + 1)
                if (end > 0) { withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            s[i] == '[' -> {
                val close = s.indexOf("](", i)
                val end = if (close > 0) s.indexOf(')', close) else -1
                if (close > 0 && end > 0) { withStyle(SpanStyle(color = link, fontWeight = FontWeight.Medium)) { append(s.substring(i + 1, close)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
