package com.dcode.circleid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circle ID is strictly monochrome: light mode is bright white with OLED-black ink, dark mode is the exact
 * inverse (true #000000 for OLED panels, pure white ink). Greys are derived symmetrically for both modes.
 */
@Immutable
data class CirclePalette(
    val background: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val hairline: Color,
    val raised: Color,
    val success: Color,
    val danger: Color,
    val isDark: Boolean,
)

private val LightPalette = CirclePalette(
    background = Color(0xFFFFFFFF),
    ink = Color(0xFF000000),
    inkSoft = Color(0xFF5C5C5C),
    inkFaint = Color(0xFFA3A3A3),
    hairline = Color(0xFFEBEBEB),
    raised = Color(0xFFF6F6F6),
    success = Color(0xFF000000),
    danger = Color(0xFFE5191C),
    isDark = false,
)

private val DarkPalette = CirclePalette(
    background = Color(0xFF000000),
    ink = Color(0xFFFFFFFF),
    inkSoft = Color(0xFFA3A3A3),
    inkFaint = Color(0xFF5C5C5C),
    hairline = Color(0xFF141414),
    raised = Color(0xFF090909),
    success = Color(0xFFFFFFFF),
    danger = Color(0xFFFF4D4F),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

enum class ThemeMode { System, Light, Dark }

private fun scheme(p: CirclePalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.ink, onPrimary = p.background,
        primaryContainer = p.raised, onPrimaryContainer = p.ink,
        secondary = p.inkSoft, onSecondary = p.background,
        secondaryContainer = p.raised, onSecondaryContainer = p.ink,
        tertiary = p.ink, onTertiary = p.background,
        background = p.background, onBackground = p.ink,
        surface = p.background, onSurface = p.ink,
        surfaceVariant = p.raised, onSurfaceVariant = p.inkSoft,
        surfaceContainer = p.raised, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
        surfaceContainerLow = p.background, surfaceContainerLowest = p.background,
        surfaceBright = p.background, surfaceDim = p.background,
        outline = p.inkFaint, outlineVariant = p.hairline,
        error = p.danger, onError = Color.White,
        inverseSurface = p.ink, inverseOnSurface = p.background, inversePrimary = p.background,
        scrim = Color.Black,
    )
}

private val Sans = FontFamily.Default

val CircleTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 56.sp, letterSpacing = (-2).sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = (-1.5).sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 32.sp, letterSpacing = (-1).sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = (-0.6).sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 21.sp, letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.4.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 2.sp)

@Composable
fun CircleTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme(palette),
            typography = CircleTypography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

object Circle {
    val palette: CirclePalette
        @Composable get() = LocalPalette.current
}
