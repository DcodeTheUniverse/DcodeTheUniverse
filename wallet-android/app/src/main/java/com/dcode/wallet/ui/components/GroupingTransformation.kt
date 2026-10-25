package com.dcode.wallet.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Displays raw digits with a separator inserted before each index in [breaks] (e.g. card numbers, MM/YY). */
class GroupingTransformation(private val breaks: Set<Int>, private val separator: Char) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val mapping = GroupingMapping.build(text.text, breaks, separator)
        return TransformedText(AnnotatedString(mapping.output), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = mapping.toTransformed(offset)
            override fun transformedToOriginal(offset: Int) = mapping.toOriginal(offset)
        })
    }

    override fun equals(other: Any?) = other is GroupingTransformation && other.breaks == breaks && other.separator == separator
    override fun hashCode() = breaks.hashCode() * 31 + separator.hashCode()
}

/** Plain offset bookkeeping, kept separate from Compose types so it can be unit tested. */
class GroupingMapping private constructor(
    val output: String,
    private val toTransformedTable: IntArray,
    private val toOriginalTable: IntArray,
) {
    fun toTransformed(offset: Int) = toTransformedTable[offset.coerceIn(0, toTransformedTable.size - 1)]
    fun toOriginal(offset: Int) = toOriginalTable[offset.coerceIn(0, toOriginalTable.size - 1)]

    companion object {
        fun build(src: String, breaks: Set<Int>, separator: Char): GroupingMapping {
            val out = StringBuilder()
            val toTransformed = IntArray(src.length + 1)
            val toOriginal = ArrayList<Int>()
            for (i in src.indices) {
                if (i in breaks) {
                    out.append(separator)
                    toOriginal += i
                }
                toTransformed[i] = out.length
                out.append(src[i])
                toOriginal += i
            }
            toTransformed[src.length] = out.length
            toOriginal += src.length
            return GroupingMapping(out.toString(), toTransformed, toOriginal.toIntArray())
        }
    }
}
