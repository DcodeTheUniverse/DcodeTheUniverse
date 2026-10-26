package com.axiom.launcher.ui.solis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle

/** Sun amber: Solis's color in the Axiom mark. */
val SolisAmber = Color(0xFFFFB224)
val SolisAmberCore = Color(0xFFFFC85A)

/** Dots of the dot-matrix "A" on a 7 x 7 grid (the apex is the sun, drawn separately). */
private val A_DOTS = listOf(
    2 to 1, 4 to 1, 2 to 2, 4 to 2, 1 to 3, 5 to 3,
    1 to 4, 2 to 4, 3 to 4, 4 to 4, 5 to 4, 0 to 5, 6 to 5, 0 to 6, 6 to 6,
)

/** The Axiom mark: a dot-matrix "A" whose apex is a small sun — Solis. Same geometry as the app icon. */
@Composable
fun AxiomMark(size: Dp, modifier: Modifier = Modifier, ink: Color = LocalPalette.current.fg) {
    Canvas(modifier.size(size).semantics { contentDescription = "Axiom" }) {
        val pitch = this.size.width / 7f
        val r = pitch * 0.39f
        fun center(c: Int, row: Int) = Offset(pitch * (c + 0.5f), pitch * (row + 0.5f))
        A_DOTS.forEach { (c, row) -> drawCircle(ink, r, center(c, row)) }
        val sun = center(3, 0)
        drawCircle(SolisAmber.copy(alpha = 0.18f), pitch * 0.92f, sun)
        drawCircle(SolisAmber.copy(alpha = 0.35f), pitch * 0.65f, sun)
        drawCircle(SolisAmberCore, pitch * 0.45f, sun)
    }
}

/** Mark plus the spaced "AXIOM" wordmark. */
@Composable
fun AxiomWordmark(markSize: Dp = 34.dp, textSize: TextUnit = 26.sp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AxiomMark(markSize)
        Text("AXIOM", style = lStyle(textSize, FontWeight.Light).copy(letterSpacing = (textSize.value * 0.32f).sp))
    }
}
