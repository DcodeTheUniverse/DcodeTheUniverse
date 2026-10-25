package com.dcode.crypto

import kotlin.math.ln
import kotlin.math.log2

/** Cryptographically random password generation and a conservative strength estimate. */
object PasswordGenerator {
    data class Options(
        val length: Int = 24,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidAmbiguous: Boolean = true,
    )

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~"
    private const val AMBIGUOUS = "Il1O0o"

    fun generate(options: Options): String {
        val sets = buildList {
            if (options.lowercase) add(LOWER)
            if (options.uppercase) add(UPPER)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }.map { set -> if (options.avoidAmbiguous) set.filterNot { it in AMBIGUOUS } else set }
            .ifEmpty { listOf(LOWER) }
        val length = options.length.coerceIn(sets.size, 128)
        val all = sets.joinToString("")
        val chars = CharArray(length)
        // Guarantee one character from each selected class, then fill uniformly.
        sets.forEachIndexed { i, set -> chars[i] = set[Bytes.randomInt(set.length)] }
        for (i in sets.size until length) chars[i] = all[Bytes.randomInt(all.length)]
        for (i in length - 1 downTo 1) {
            val j = Bytes.randomInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return String(chars)
    }

    private val WORDS = (
        "acorn amber anchor apron arctic atlas autumn badge bamboo banner basil beacon birch bison blaze " +
            "bloom bolt bramble breeze brick bronze cabin cactus canyon carbon cedar cello chalk cherry cider " +
            "cinder circle citrus clover cobalt comet coral cosmos cotton crater crimson crystal cypress dawn " +
            "delta desert dune ember emerald falcon fern fjord flint forest fossil galaxy garnet geyser ginger " +
            "glacier granite gravel harbor hazel helium heron hollow horizon iris island ivory jasper juniper " +
            "kelp kernel lagoon lantern lava lemon lilac linen lotus lunar maple marble meadow mercury meteor " +
            "mint mirror monsoon moss nebula nectar nickel nova oasis obsidian ocean olive onyx orbit orchid " +
            "otter pebble pepper pine planet plasma polar prism pulse quartz quill radar raven reef ripple " +
            "river saffron sage salt sapphire saturn sequoia shadow sierra silver solar sparrow spruce static " +
            "stone summit sunset tango thistle thunder tidal timber topaz tundra twilight umber valley velvet " +
            "violet volcano walnut willow winter zephyr zinc"
        ).split(' ')

    fun passphrase(words: Int = 6, separator: String = "-"): String =
        (1..words.coerceIn(3, 16)).joinToString(separator) { WORDS[Bytes.randomInt(WORDS.size)] }

    /** Rough entropy estimate in bits, penalising short and repetitive input. */
    fun entropyBits(password: CharSequence): Double {
        if (password.isEmpty()) return 0.0
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 32
        if (password.any { it.code >= 128 }) pool += 100
        val distinct = password.toSet().size
        val repetitionPenalty = distinct.toDouble() / password.length
        val words = password.split('-', ' ', '_', '.').filter { it.length >= 3 }
        val wordBased = if (words.size >= 3 && words.all { it.all(Char::isLetter) }) words.size * log2(WORDS.size.toDouble()) else 0.0
        val charBased = password.length * (ln(pool.coerceAtLeast(1).toDouble()) / ln(2.0)) * (0.5 + 0.5 * repetitionPenalty)
        return maxOf(wordBased, charBased)
    }

    enum class Strength(val label: String) { VeryWeak("Very weak"), Weak("Weak"), Fair("Fair"), Strong("Strong"), Excellent("Excellent") }

    fun strength(password: CharSequence): Strength = when (entropyBits(password)) {
        in 0.0..<28.0 -> Strength.VeryWeak
        in 28.0..<45.0 -> Strength.Weak
        in 45.0..<64.0 -> Strength.Fair
        in 64.0..<90.0 -> Strength.Strong
        else -> Strength.Excellent
    }
}
