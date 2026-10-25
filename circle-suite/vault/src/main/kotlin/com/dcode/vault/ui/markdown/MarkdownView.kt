package com.dcode.vault.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcode.vault.core.markdown.Align
import com.dcode.vault.core.markdown.Block
import com.dcode.vault.core.markdown.Inline
import com.dcode.vault.core.markdown.Markdown
import com.dcode.vault.ui.theme.Mono
import com.dcode.vault.ui.theme.V
import com.dcode.vault.ui.theme.VaultPalette

/** Renders Markdown natively (no WebView, no network: images show as captions). */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(20.dp)) {
    val blocks = remember(source) { Markdown.parse(source) }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(blocks) { _, b -> BlockView(b) }
        }
    }
}

@Composable
private fun BlockView(b: Block, depth: Int = 0) {
    val p = V.palette
    val t = MaterialTheme.typography
    when (b) {
        is Block.Heading -> {
            val style = when (b.level) {
                1 -> t.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
                2 -> t.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
                3 -> t.headlineSmall
                4 -> t.titleLarge
                else -> t.titleMedium
            }
            Column(Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp)) {
                Text(annotate(b.inlines, p), style = style, color = p.ink)
                if (b.level <= 2) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                }
            }
        }
        is Block.Paragraph -> {
            val images = b.inlines.filterIsInstance<Inline.Image>()
            if (images.isNotEmpty() && b.inlines.size == images.size) {
                images.forEach { ImagePlaceholder(it) }
            } else {
                Text(annotate(b.inlines, p), style = t.bodyLarge, color = p.ink)
            }
        }
        is Block.CodeBlock -> {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surfaceHigh)) {
                if (b.language.isNotEmpty()) {
                    Text(b.language.uppercase(), style = t.labelSmall, color = p.inkSoft, modifier = Modifier.padding(start = 14.dp, top = 10.dp))
                }
                Box(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
                    Text(b.code, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = p.ink, softWrap = false)
                }
            }
        }
        is Block.Quote -> {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(p.accent))
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.blocks.forEach { inner -> BlockView(inner, depth + 1) }
                }
            }
        }
        is Block.ListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.widthIn(min = 26.dp).padding(top = 2.dp)) {
                            when {
                                item.checked != null -> Icon(
                                    if (item.checked == true) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                                    tint = if (item.checked == true) p.accent else p.inkSoft, modifier = Modifier.size(20.dp),
                                )
                                b.ordered -> Text("${b.start + i}.", style = t.bodyLarge, color = p.inkSoft)
                                else -> Box(Modifier.padding(top = 9.dp, start = 6.dp).size(6.dp).clip(CircleShape).background(if (depth % 2 == 0) p.ink else p.inkSoft))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.blocks.forEach { inner -> BlockView(inner, depth + 1) }
                        }
                    }
                }
            }
        }
        is Block.Table -> {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, p.hairline, RoundedCornerShape(12.dp))) {
                    TableRow(b.header, b.alignments, header = true)
                    b.rows.forEach { TableRow(it, b.alignments, header = false) }
                }
            }
        }
        Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(p.hairline))
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, aligns: List<Align>, header: Boolean) {
    val p = V.palette
    Row(Modifier.background(if (header) p.surfaceHigh else p.background)) {
        cells.forEachIndexed { i, c ->
            val align = when (aligns.getOrNull(i)) {
                Align.Center -> androidx.compose.ui.text.style.TextAlign.Center
                Align.End -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
            Text(
                annotate(c, p),
                style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = p.ink, textAlign = align,
                modifier = Modifier.widthIn(min = 96.dp, max = 260.dp).border(0.5.dp, p.hairline).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ImagePlaceholder(img: Inline.Image) {
    val p = V.palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Image, null, tint = p.inkSoft)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(img.alt.ifBlank { "Image" }, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text("Remote images are never loaded — Vault stays offline", style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
        }
    }
}

fun annotate(inlines: List<Inline>, p: VaultPalette): AnnotatedString = buildAnnotatedString {
    fun walk(list: List<Inline>) {
        for (n in list) when (n) {
            is Inline.Text -> append(n.text)
            is Inline.Code -> withStyle(SpanStyle(fontFamily = Mono, background = p.surfaceHigh, fontSize = 0.92.em)) { append(" ${n.text} ") }
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(n.children) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(n.children) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = p.inkSoft)) { walk(n.children) }
            is Inline.Link -> withLink(
                LinkAnnotation.Url(n.url, TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))),
            ) { walk(n.children) }
            is Inline.Image -> withStyle(SpanStyle(color = p.inkSoft, fontStyle = FontStyle.Italic)) { append("[${n.alt.ifBlank { "image" }}]") }
            Inline.LineBreak -> append('\n')
        }
    }
    walk(inlines)
}

private val Double.em get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
