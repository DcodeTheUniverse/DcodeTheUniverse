package com.axiom.launcher.solis.engine

import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ContentPart
import java.math.BigInteger

/** Placeholders sent in place of personal details, and how to put the real values back. */
class Redaction(private val values: Map<String, String>, val counts: Map<String, Int>) {
    val total: Int get() = values.size

    fun restore(s: String): String = if (values.isEmpty() || s.indexOf('⟦') < 0) s else Redactor.PLACEHOLDER.replace(s) { values[it.value] ?: it.value }
}

/**
 * Hides personal details from cloud models: email addresses, phone numbers, payment card numbers and IBANs
 * become placeholders such as ⟦EMAIL_1⟧ before a request leaves the phone, and the real values are put back
 * into the reply and into tool arguments before anything runs. Numbering follows first appearance in the
 * conversation, so earlier turns always redact the same way and prompt caching keeps working. Images and
 * files are sent as they are; the receipt says so.
 */
object Redactor {
    val PLACEHOLDER = Regex("⟦(EMAIL|PHONE|CARD|IBAN)_\\d+⟧")

    private enum class Kind(val tag: String, val regex: Regex) {
        EMAIL("EMAIL", Regex("(?<![\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![\\w-])")),
        IBAN("IBAN", Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b")),
        CARD("CARD", Regex("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])")),
        PHONE("PHONE", Regex(
            "(?<![\\w+])(?:\\+\\d{1,3}[\\s.-]?(?:\\(?\\d{1,4}\\)?[\\s.-]?){1,5}\\d{2,4}" + // +44 20 7946 0958
                "|\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}" + // (555) 123-4567
                "|0\\d{1,4}[\\s/.-]?\\d{2,4}[\\s.-]?\\d{2,4}(?:[\\s.-]?\\d{2,4})?)(?![\\w])", // 030 1234 5678
        )),
    }

    private val DATE = Regex("^\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|^\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}$")

    private fun valid(k: Kind, v: String): Boolean {
        val digits = v.filter { it.isDigit() }
        return when (k) {
            Kind.EMAIL -> true
            Kind.CARD -> digits.length in 13..19 && luhn(digits)
            Kind.IBAN -> ibanOk(v.replace(" ", ""))
            Kind.PHONE -> digits.length in 8..15 && !DATE.containsMatchIn(v.trim()) && digits.toSet().size > 2
        }
    }

    private fun luhn(d: String): Boolean {
        var sum = 0
        d.reversed().forEachIndexed { i, c ->
            var n = c - '0'
            if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
            sum += n
        }
        return sum % 10 == 0
    }

    private fun ibanOk(s: String): Boolean {
        if (s.length !in 15..34) return false
        val moved = s.substring(4) + s.substring(0, 4)
        val num = moved.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
        return runCatching { BigInteger(num).mod(BigInteger.valueOf(97)).toInt() == 1 }.getOrDefault(false)
    }

    /** Hides details in one conversation; reuse it for every string of the same request. */
    class Session {
        private val byValue = HashMap<String, String>()
        private val byPlaceholder = LinkedHashMap<String, String>()
        private val n = IntArray(Kind.entries.size)

        fun apply(s: String): String {
            if (s.isEmpty()) return s
            var out = s
            for (k in Kind.entries) {
                out = k.regex.replace(out) { m ->
                    val v = m.value
                    if (!valid(k, v) || PLACEHOLDER.containsMatchIn(v)) v
                    else byValue.getOrPut(k.name + "\u0000" + key(k, v)) {
                        val ph = "⟦${k.tag}_${++n[k.ordinal]}⟧"
                        byPlaceholder[ph] = v.trim()
                        ph
                    }
                }
            }
            return out
        }

        private fun key(k: Kind, v: String) = when (k) {
            Kind.EMAIL -> v.lowercase()
            else -> v.filter { it.isLetterOrDigit() }.uppercase()
        }

        fun result(): Redaction = Redaction(byPlaceholder.toMap(), Kind.entries.associate { it.name.lowercase() to n[it.ordinal] }.filterValues { it > 0 })
    }

    class Redacted(val system: String, val messages: List<ChatMessage>, val redaction: Redaction)

    fun redact(system: String, messages: List<ChatMessage>): Redacted {
        val s = Session()
        val sys = s.apply(system)
        val msgs = messages.map { m ->
            m.copy(
                parts = m.parts.map { p -> if (p is ContentPart.Text) ContentPart.Text(s.apply(p.text)) else p },
                toolCalls = m.toolCalls.map { c -> c.copy(argumentsJson = s.apply(c.argumentsJson)) },
            )
        }
        return Redacted(sys, msgs, s.result())
    }
}
