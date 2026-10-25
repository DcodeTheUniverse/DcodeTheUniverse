package com.dcode.wallet.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId

data class ParsedField(
    val id: AutofillId,
    val type: CardField,
    val autofillType: Int,
    val maxLength: Int,
    val options: List<String>,
)

data class ParsedForm(
    val packageName: String,
    val webDomain: String?,
    val fields: List<ParsedField>,
) {
    val isPaymentForm: Boolean get() = fields.any { it.type == CardField.NUMBER }
}

object StructureParser {
    private val htmlAttributes = setOf("autocomplete", "name", "id", "placeholder", "aria-label", "label", "x-autocompletetype")

    fun parse(structure: AssistStructure): ParsedForm {
        val fields = mutableListOf<ParsedField>()
        var webDomain: String? = null
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode) { node ->
                if (webDomain == null && !node.webDomain.isNullOrBlank()) webDomain = node.webDomain
                val id = node.autofillId ?: return@walk
                if (node.autofillType == View.AUTOFILL_TYPE_NONE || node.visibility != View.VISIBLE) return@walk
                val type = CardFields.classify(signals(node)) ?: return@walk
                fields += ParsedField(
                    id = id,
                    type = type,
                    autofillType = node.autofillType,
                    maxLength = if (Build.VERSION.SDK_INT >= 28) node.maxTextLength else -1,
                    options = node.autofillOptions?.map { it.toString() } ?: emptyList(),
                )
            }
        }
        return ParsedForm(structure.activityComponent.packageName, webDomain, fields)
    }

    private fun signals(node: AssistStructure.ViewNode): List<String> = buildList {
        node.autofillHints?.let { addAll(it) }
        node.htmlInfo?.attributes?.forEach { attr ->
            if (attr.first?.lowercase() in htmlAttributes) attr.second?.let { add(it) }
        }
        node.idEntry?.let { add(it) }
        node.hint?.let { add(it) }
        node.contentDescription?.let { add(it.toString()) }
    }

    private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChildAt(i), visit)
    }
}
