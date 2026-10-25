package com.dcode.wallet.data

import java.time.YearMonth
import java.util.UUID

enum class CardBrand(val display: String, val cvvLength: Int) {
    VISA("Visa", 3),
    MASTERCARD("Mastercard", 3),
    AMEX("American Express", 4),
    DISCOVER("Discover", 3),
    VERVE("Verve", 3),
    JCB("JCB", 3),
    DINERS("Diners Club", 3),
    UNIONPAY("UnionPay", 3),
    RUPAY("RuPay", 3),
    MAESTRO("Maestro", 3),
    UNKNOWN("Card", 3);

    companion object {
        fun detect(number: String): CardBrand {
            val d = number.filter(Char::isDigit)
            fun range(len: Int, lo: Int, hi: Int) = d.length >= len && d.substring(0, len).toInt() in lo..hi
            fun starts(vararg p: String) = p.any { d.startsWith(it) }
            return when {
                range(6, 506099, 506198) || range(6, 650002, 650027) || range(6, 507865, 507964) -> VERVE
                starts("34", "37") -> AMEX
                range(3, 300, 305) || starts("36", "38", "39") -> DINERS
                range(4, 3528, 3589) -> JCB
                starts("4") -> VISA
                range(2, 51, 55) || range(4, 2221, 2720) -> MASTERCARD
                starts("6521", "6522", "508", "60", "81", "82") && !starts("6011") -> RUPAY
                starts("6011", "65") || range(3, 644, 649) -> DISCOVER
                starts("62") -> UNIONPAY
                starts("50", "56", "57", "58", "6304", "6759", "676770", "676774") -> MAESTRO
                else -> UNKNOWN
            }
        }
    }
}

object CardNumbers {
    fun luhnValid(number: String): Boolean {
        val d = number.filter(Char::isDigit)
        if (d.length < 12) return false
        var sum = 0
        d.reversed().forEachIndexed { i, c ->
            var n = c - '0'
            if (i % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return sum % 10 == 0
    }

    fun isValidLength(number: String, brand: CardBrand = CardBrand.detect(number)): Boolean {
        val len = number.count(Char::isDigit)
        return when (brand) {
            CardBrand.AMEX -> len == 15
            CardBrand.DINERS -> len in 14..19
            else -> len in 13..19
        }
    }

    /** Positions (in the digit string) before which a space is shown. */
    fun groupBreaks(brand: CardBrand): Set<Int> =
        if (brand == CardBrand.AMEX) setOf(4, 10) else setOf(4, 8, 12, 16)

    fun format(number: String, brand: CardBrand = CardBrand.detect(number)): String {
        val breaks = groupBreaks(brand)
        val sb = StringBuilder()
        number.filter(Char::isDigit).forEachIndexed { i, c ->
            if (i in breaks) sb.append(' ')
            sb.append(c)
        }
        return sb.toString()
    }
}

data class Card(
    val id: String = UUID.randomUUID().toString(),
    val number: String,
    val holder: String,
    val expMonth: Int,
    val expYear: Int,
    val cvv: String,
    val nickname: String = "",
    val colorIndex: Int = 0,
    val isVirtual: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val brand: CardBrand get() = CardBrand.detect(number)
    val last4: String get() = number.takeLast(4)
    val title: String get() = nickname.ifBlank { brand.display }
    val expiry: String get() = "%02d/%02d".format(expMonth, expYear % 100)
    val formattedNumber: String get() = CardNumbers.format(number, brand)

    fun isExpired(now: YearMonth = YearMonth.now()): Boolean = YearMonth.of(expYear, expMonth).isBefore(now)
}

data class ActivityEntry(
    val id: String = UUID.randomUUID().toString(),
    val cardId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
)

data class WalletState(
    val cards: List<Card> = emptyList(),
    val defaultCardId: String? = null,
    val activity: List<ActivityEntry> = emptyList(),
) {
    /** The card used for quick pay: the chosen default, or the first card if none was chosen. */
    val defaultCard: Card? get() = cards.firstOrNull { it.id == defaultCardId } ?: cards.firstOrNull()

    fun card(id: String): Card? = cards.firstOrNull { it.id == id }

    fun activityFor(cardId: String): List<ActivityEntry> = activity.filter { it.cardId == cardId }

    /** Default card first, then the rest in the order they were added. */
    val cardsDefaultFirst: List<Card>
        get() = defaultCard?.let { d -> listOf(d) + cards.filter { it.id != d.id } } ?: cards
}
