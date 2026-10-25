package com.dcode.launcher.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

object Fuzzy {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    /**
     * Scores how well [query] matches [label]. 0 = no match. Higher is better.
     * exact > prefix > word prefix > initials ("gm" → Google Maps) > substring > subsequence.
     */
    fun score(query: String, label: String): Int {
        val q = norm(query.trim())
        if (q.isEmpty()) return 0
        val l = norm(label)
        if (l == q) return 1000
        if (l.startsWith(q)) return 800 - (l.length - q.length)
        val words = l.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 650 - (l.length - q.length)
        val initials = words.joinToString("") { it.take(1) }
        if (q.length >= 2 && initials.startsWith(q)) return 600
        val compact = l.replace(" ", "")
        if (compact.contains(q.replace(" ", ""))) return 450 - compact.indexOf(q.replace(" ", ""))
        // subsequence
        var i = 0
        var gaps = 0
        var last = -1
        for ((idx, ch) in compact.withIndex()) {
            if (i < q.length && ch == q[i]) {
                if (last >= 0) gaps += idx - last - 1
                last = idx
                i++
            }
        }
        if (i == q.length && q.length >= 2) return (300 - gaps * 10).coerceAtLeast(20)
        return 0
    }
}

/** Tiny, safe arithmetic evaluator for the omnibox: + - * / % ^ ( ) sqrt, pi, e, and "x% of y". */
object Calc {
    private val looksLikeMath = Regex("^[0-9.\\s+\\-*/%^()×÷,πe]+$|^.*(sqrt|pi).*$")

    fun tryEval(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        s = s.removePrefix("=").replace("×", "*").replace("÷", "/").replace(",", "").replace("π", "pi")
        val pct = Regex("^([0-9.]+)\\s*%\\s*of\\s*([0-9.]+)$").find(s)
        if (pct != null) {
            val v = pct.groupValues[1].toDouble() / 100 * pct.groupValues[2].toDouble()
            return format(v)
        }
        if (!looksLikeMath.matches(s)) return null
        if (!s.any { it in "+-*/%^(" } && !s.contains("sqrt")) return null
        return runCatching { format(Parser(s).parse()) }.getOrNull()
    }

    private fun format(v: Double): String? {
        if (v.isNaN() || v.isInfinite()) return null
        return if (abs(v - floor(v)) < 1e-9 && abs(v) < 1e15) floor(v).toLong().toString()
        else "%.8f".format(v).trimEnd('0').trimEnd('.')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun parse(): Double {
            val v = expr()
            skip()
            if (pos < s.length) error("trailing")
            return v
        }
        fun skip() { while (pos < s.length && s[pos] == ' ') pos++ }
        fun eat(c: Char): Boolean { skip(); if (pos < s.length && s[pos] == c) { pos++; return true }; return false }
        fun expr(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }
        fun term(): Double {
            var v = factor()
            while (true) v = when {
                eat('*') -> v * factor()
                eat('/') -> v / factor()
                eat('%') -> v % factor()
                else -> return v
            }
        }
        fun factor(): Double {
            val b = unary()
            return if (eat('^')) b.pow(factor()) else b
        }
        fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> atom()
        }
        fun atom(): Double {
            skip()
            if (eat('(')) { val v = expr(); if (!eat(')')) error(")"); return v }
            if (s.startsWith("sqrt", pos)) { pos += 4; return sqrt(atom()) }
            if (s.startsWith("pi", pos)) { pos += 2; return Math.PI }
            if (pos < s.length && s[pos] == 'e' && (pos + 1 >= s.length || !s[pos + 1].isLetter())) { pos++; return Math.E }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) error("number")
            return s.substring(start, pos).toDouble()
        }
    }
}
