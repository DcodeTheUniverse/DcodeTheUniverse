package com.dcode.wallet.autofill

import com.dcode.wallet.data.Card
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

enum class CardField { NUMBER, HOLDER, EXP_DATE, EXP_MONTH, EXP_YEAR, CVV }

/** Pure, platform-independent heuristics for recognising checkout fields and formatting values for them. */
object CardFields {
    private val separators = Regex("[\\s_\\-./:]+")

    /**
     * Classifies a field from its signals: autofill hints, HTML autocomplete/name/id attributes,
     * resource id, hint text and content description.
     */
    fun classify(signals: List<String>): CardField? {
        val norm = signals.filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT).replace(separators, "") }
        if (norm.isEmpty()) return null
        fun has(vararg keys: String) = norm.any { s -> keys.any { s.contains(it) } }
        fun exact(vararg keys: String) = norm.any { it in keys }
        return when {
            has("cctype", "cardtype", "creditcardtype", "cardbrand") -> null
            has("creditcardsecuritycode", "cccsc", "cvv", "cvc", "securitycode", "cardverification", "cvn") ||
                exact("csc", "cid") -> CardField.CVV
            has("creditcardexpirationmonth", "ccexpmonth", "expmonth", "expirymonth", "expirationmonth", "cardmonth", "expmm") ||
                exact("mm", "month") -> CardField.EXP_MONTH
            has("creditcardexpirationyear", "ccexpyear", "expyear", "expiryyear", "expirationyear", "cardyear", "expyy") ||
                exact("yy", "yyyy", "year") -> CardField.EXP_YEAR
            has("creditcardexpirationdate", "ccexp", "expir", "expdate", "mmyy", "validthru", "validthrough", "validtill") ->
                CardField.EXP_DATE
            has("ccname", "nameoncard", "cardholder", "holdername", "cardname", "creditcardholder", "namecard") ->
                CardField.HOLDER
            has("creditcardnumber", "ccnumber", "cardnumber", "cardno", "ccnum", "cardnum", "creditcard", "debitcard") ||
                exact("pan", "card") -> CardField.NUMBER
            else -> null
        }
    }

    /** Text to type into a free-text field. [maxLength] is the field's max length or -1 if unknown. */
    fun text(field: CardField, card: Card, maxLength: Int = -1): String {
        val mm = "%02d".format(card.expMonth)
        val yyyy = card.expYear.toString()
        val yy = "%02d".format(card.expYear % 100)
        return when (field) {
            CardField.NUMBER -> card.number
            CardField.HOLDER -> card.holder
            CardField.CVV -> card.cvv
            CardField.EXP_DATE -> when (maxLength) {
                4 -> "$mm$yy"
                6 -> "$mm$yyyy"
                7 -> "$mm/$yyyy"
                else -> "$mm/$yy"
            }
            CardField.EXP_MONTH -> mm
            CardField.EXP_YEAR -> if (maxLength == 2) yy else yyyy
        }
    }

    /** Index of the option to select in a dropdown, or -1 if nothing matches. */
    fun listIndex(field: CardField, card: Card, options: List<String>): Int {
        val yyyy = card.expYear.toString()
        val yy = "%02d".format(card.expYear % 100)
        return when (field) {
            CardField.EXP_MONTH -> {
                val name = Month.of(card.expMonth).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ROOT)
                options.indexOfFirst { opt ->
                    val digits = opt.filter(Char::isDigit)
                    (digits.isNotEmpty() && digits.length <= 2 && digits.toInt() == card.expMonth) ||
                        opt.trim().lowercase(Locale.ROOT).startsWith(name)
                }
            }
            CardField.EXP_YEAR -> options.indexOfFirst { opt ->
                val digits = opt.filter(Char::isDigit)
                digits == yyyy || digits == yy
            }
            CardField.EXP_DATE -> options.indexOfFirst { opt ->
                val digits = opt.filter(Char::isDigit)
                digits == "%02d%s".format(card.expMonth, yy) || digits == "%02d%s".format(card.expMonth, yyyy)
            }
            else -> -1
        }
    }
}
