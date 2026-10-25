package com.dcode.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vault shares Circle ID's white / OLED-black foundation and adds a single indigo accent. */
@Immutable
data class VaultPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val folder: Color,
    val note: Color,
    val file: Color,
    val key: Color,
    val isDark: Boolean,
)

private val Light = VaultPalette(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F6F8),
    surfaceHigh = Color(0xFFEDEDF1),
    ink = Color(0xFF000000),
    inkSoft = Color(0xFF5B5B66),
    inkFaint = Color(0xFFA1A1AB),
    hairline = Color(0xFFEAEAEE),
    accent = Color(0xFF4F46E5),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDEBFF),
    danger = Color(0xFFE11D48),
    success = Color(0xFF059669),
    folder = Color(0xFF4F46E5),
    note = Color(0xFFD97706),
    file = Color(0xFF0891B2),
    key = Color(0xFF059669),
    isDark = false,
)

private val Dark = VaultPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF0B0B0E),
    surfaceHigh = Color(0xFF15151A),
    ink = Color(0xFFFFFFFF),
    inkSoft = Color(0xFFA4A4AE),
    inkFaint = Color(0xFF5E5E68),
    hairline = Color(0xFF17171C),
    accent = Color(0xFF8B83FF),
    onAccent = Color(0xFF000000),
    accentSoft = Color(0xFF17153A),
    danger = Color(0xFFFB7185),
    success = Color(0xFF34D399),
    folder = Color(0xFF8B83FF),
    note = Color(0xFFFBBF24),
    file = Color(0xFF22D3EE),
    key = Color(0xFF34D399),
    isDark = true,
)

val LocalVaultPalette = staticCompositionLocalOf { Light }

enum class ThemeMode { System, Light, Dark }

val VaultTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 52.sp, letterSpacing = (-2).sp, lineHeight = 56.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 40.sp, letterSpacing = (-1.4).sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 32.sp, letterSpacing = (-1).sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = (-0.6).sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 21.sp, letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.3.sp),
)

val Mono = FontFamily.Monospace

@Composable
fun VaultTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val p = if (dark) Dark else Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.ink, onSecondary = p.background, secondaryContainer = p.surfaceHigh, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = p.onAccent,
        background = p.background, onBackground = p.ink,
        surface = p.background, onSurface = p.ink, surfaceVariant = p.surface, onSurfaceVariant = p.inkSoft,
        surfaceContainerLowest = p.background, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        surfaceBright = p.background, surfaceDim = p.background,
        outline = p.inkFaint, outlineVariant = p.hairline, error = p.danger, onError = Color.White,
        inverseSurface = p.ink, inverseOnSurface = p.background, inversePrimary = p.accent,
    )
    CompositionLocalProvider(LocalVaultPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VaultTypography,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp)),
            content = content,
        )
    }
}

object V {
    val palette: VaultPalette
        @Composable get() = LocalVaultPalette.current
}
