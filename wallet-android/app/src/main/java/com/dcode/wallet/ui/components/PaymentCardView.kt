package com.dcode.wallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardBrand
import com.dcode.wallet.data.CardNumbers

val CardPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFF1F2A44), Color(0xFF3B4C7A)),
    listOf(Color(0xFF0F4C81), Color(0xFF2E86DE)),
    listOf(Color(0xFF0B6E4F), Color(0xFF27AE60)),
    listOf(Color(0xFFC0392B), Color(0xFFF39C12)),
    listOf(Color(0xFF6A1B9A), Color(0xFFD81B60)),
    listOf(Color(0xFF232526), Color(0xFF5A5D60)),
    listOf(Color(0xFF9E5A63), Color(0xFFE0A6A4)),
    listOf(Color(0xFF006D77), Color(0xFF4FB3AA)),
)

/** Everything needed to draw a card face, so the add-card preview can render partial input. */
data class CardFace(
    val number: String,
    val holder: String,
    val expiry: String,
    val colorIndex: Int,
    val nickname: String,
    val isVirtual: Boolean,
    val isDefault: Boolean = false,
) {
    val brand: CardBrand get() = CardBrand.detect(number)
}

fun Card.face(isDefault: Boolean = false) = CardFace(number, holder, expiry, colorIndex, nickname, isVirtual, isDefault)

@Composable
fun PaymentCardView(
    face: CardFace,
    modifier: Modifier = Modifier,
    revealNumber: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val palette = CardPalettes[face.colorIndex.mod(CardPalettes.size)]
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(palette))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.55f, center = Offset(size.width * 1.05f, size.height * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.4f, center = Offset(size.width * 0.1f, size.height * 1.15f))
        }
        Column(Modifier.matchParentSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        face.nickname.ifBlank { face.brand.display },
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val tags = listOfNotNull(if (face.isVirtual) "VIRTUAL" else null, if (face.isDefault) "DEFAULT" else null)
                    if (tags.isNotEmpty()) {
                        Text(tags.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, letterSpacing = 1.5.sp)
                    }
                }
                BrandMark(face.brand)
            }
            Spacer(Modifier.weight(1f))
            if (!face.isVirtual) {
                Chip()
                Spacer(Modifier.height(10.dp))
            }
            Text(
                displayNumber(face, revealNumber),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 19.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    face.holder.ifBlank { "CARDHOLDER NAME" }.uppercase(),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("VALID THRU", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(face.expiry.ifBlank { "MM/YY" }, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun displayNumber(face: CardFace, reveal: Boolean): String {
    val digits = face.number.filter(Char::isDigit)
    if (!reveal) return "••••  ••••  ••••  " + digits.takeLast(4).ifEmpty { "••••" }
    if (digits.isEmpty()) return "•••• •••• •••• ••••"
    return CardNumbers.format(digits, face.brand)
}

@Composable
fun BrandMark(brand: CardBrand, modifier: Modifier = Modifier) {
    when (brand) {
        CardBrand.VISA -> Text(
            "VISA", modifier = modifier, color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, letterSpacing = 1.sp,
        )
        CardBrand.MASTERCARD, CardBrand.MAESTRO -> {
            val (left, right) = if (brand == CardBrand.MASTERCARD) Color(0xFFEB001B) to Color(0xFFF79E1B)
            else Color(0xFF0099DF) to Color(0xFFED0006)
            Canvas(modifier.size(width = 46.dp, height = 28.dp)) {
                val r = size.height / 2
                drawCircle(left, r, Offset(r, r))
                drawCircle(right.copy(alpha = 0.9f), r, Offset(size.width - r, r))
            }
        }
        CardBrand.AMEX -> Box(
            modifier.background(Color(0xFF2E77BC), RoundedCornerShape(4.dp)).border(1.dp, Color.White, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("AMEX", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        else -> Text(
            brand.display.uppercase(), modifier = modifier, color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Chip() {
    Canvas(Modifier.width(42.dp).height(31.dp)) {
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFFF6E27A), Color(0xFFCBA135))), cornerRadius = r)
        val line = Color(0xFF8C6D1F).copy(alpha = 0.6f)
        val stroke = Stroke(width = 1.dp.toPx())
        drawLine(line, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), stroke.width)
        drawLine(line, Offset(0f, size.height * 2 / 3), Offset(size.width, size.height * 2 / 3), stroke.width)
        drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 3), stroke.width)
        drawLine(line, Offset(size.width / 2, size.height * 2 / 3), Offset(size.width / 2, size.height), stroke.width)
        drawRoundRect(line, topLeft = Offset(size.width * 0.3f, size.height / 3), size = Size(size.width * 0.4f, size.height / 3),
            cornerRadius = CornerRadius(3.dp.toPx()), style = stroke)
    }
}

@Composable
fun ColorPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CardPalettes.forEachIndexed { i, colors ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.linearGradient(colors))
                    .border(
                        width = if (i == selected) 3.dp else 0.dp,
                        color = if (i == selected) androidx.compose.material3.MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}
