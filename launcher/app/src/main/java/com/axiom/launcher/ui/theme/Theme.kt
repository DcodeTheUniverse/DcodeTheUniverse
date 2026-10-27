package com.axiom.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

enum class ThemeMode(val id: String, val title: String, val tagline: String) {
    PAPER("paper", "Paper", "White canvas, black ink"),
    VOID("void", "Void", "True OLED black, white light"),
    NETRUNNER("netrunner", "Netrunner", "Jack in. Neon terminal mode");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: VOID
    }
}

@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    val accent2: Color,
    val warn: Color,
    val danger: Color,
    val onAccent: Color,
    val isDark: Boolean,
)

val PaperPalette = Palette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF3F3F3),
    surfaceHi = Color(0xFFE6E6E6),
    fg = Color(0xFF000000),
    dim = Color(0xFF6B6B6B),
    faint = Color(0xFFBDBDBD),
    accent = Color(0xFF000000),
    accent2 = Color(0xFF3A3A3A),
    warn = Color(0xFF000000),
    danger = Color(0xFFC62828),
    onAccent = Color(0xFFFFFFFF),
    isDark = false,
)

val VoidPalette = Palette(
    bg = Color(0xFF000000),
    surface = Color(0xFF0D0D0D),
    surfaceHi = Color(0xFF1A1A1A),
    fg = Color(0xFFFFFFFF),
    dim = Color(0xFF8C8C8C),
    faint = Color(0xFF3A3A3A),
    accent = Color(0xFFFFFFFF),
    accent2 = Color(0xFFCFCFCF),
    warn = Color(0xFFFFFFFF),
    danger = Color(0xFFFF5252),
    onAccent = Color(0xFF000000),
    isDark = true,
)

val NetrunnerPalette = Palette(
    bg = Color(0xFF000000),
    surface = Color(0xFF03110B),
    surfaceHi = Color(0xFF072318),
    fg = Color(0xFF00FF9C),
    dim = Color(0xFF00A86B),
    faint = Color(0xFF0B4A33),
    accent = Color(0xFF00E5FF),
    accent2 = Color(0xFFFF2BD6),
    warn = Color(0xFFFCEE0A),
    danger = Color(0xFFFF3860),
    onAccent = Color(0xFF000000),
    isDark = true,
)

@Immutable
data class LauncherType(
    val family: FontFamily,
    val clockWeight: FontWeight,
    val clockSize: TextUnit,
    val bodyWeight: FontWeight,
    val appWeight: FontWeight,
    val appSize: TextUnit,
)

val LocalPalette = staticCompositionLocalOf { VoidPalette }
val LocalMode = staticCompositionLocalOf { ThemeMode.VOID }
val LocalType = staticCompositionLocalOf {
    LauncherType(FontFamily.SansSerif, FontWeight.Thin, 84.sp, FontWeight.Light, FontWeight.Light, 26.sp)
}

fun paletteFor(mode: ThemeMode) = when (mode) {
    ThemeMode.PAPER -> PaperPalette
    ThemeMode.VOID -> VoidPalette
    ThemeMode.NETRUNNER -> NetrunnerPalette
}

fun typeFor(mode: ThemeMode) = when (mode) {
    ThemeMode.NETRUNNER -> LauncherType(FontFamily.Monospace, FontWeight.Bold, 64.sp, FontWeight.Normal, FontWeight.Normal, 20.sp)
    else -> LauncherType(FontFamily.SansSerif, FontWeight.Thin, 88.sp, FontWeight.Light, FontWeight.Light, 26.sp)
}

@Composable
fun LauncherTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val p = paletteFor(mode)
    val t = typeFor(mode)
    val scheme = if (p.isDark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.fg,
        surface = p.bg, onSurface = p.fg, surfaceVariant = p.surface, onSurfaceVariant = p.dim,
        secondary = p.accent2, outline = p.faint, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
        surfaceContainerLow = p.surface,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.fg,
        surface = p.bg, onSurface = p.fg, surfaceVariant = p.surface, onSurfaceVariant = p.dim,
        secondary = p.accent2, outline = p.faint, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
        surfaceContainerLow = p.surface,
    )
    val base = MaterialTheme.typography
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontFamily = t.family),
        bodyMedium = base.bodyMedium.copy(fontFamily = t.family),
        bodySmall = base.bodySmall.copy(fontFamily = t.family),
        labelLarge = base.labelLarge.copy(fontFamily = t.family),
        labelMedium = base.labelMedium.copy(fontFamily = t.family),
        labelSmall = base.labelSmall.copy(fontFamily = t.family),
        titleLarge = base.titleLarge.copy(fontFamily = t.family),
        titleMedium = base.titleMedium.copy(fontFamily = t.family),
        titleSmall = base.titleSmall.copy(fontFamily = t.family),
        headlineSmall = base.headlineSmall.copy(fontFamily = t.family),
        headlineMedium = base.headlineMedium.copy(fontFamily = t.family),
    )
    CompositionLocalProvider(LocalPalette provides p, LocalMode provides mode, LocalType provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Convenience text style using the launcher font family. */
@Composable
fun lStyle(size: TextUnit, weight: FontWeight? = null, color: Color? = null): TextStyle {
    val t = LocalType.current
    val p = LocalPalette.current
    return TextStyle(
        fontFamily = t.family,
        fontSize = size,
        fontWeight = weight ?: t.bodyWeight,
        color = color ?: p.fg,
    )
}
