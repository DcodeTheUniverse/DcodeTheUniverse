package com.dcode.vault.core.search

import java.text.Normalizer
import java.util.TreeMap
import kotlin.math.ln

/**
 * Case- and accent-insensitive folding that preserves string length, so match offsets in the folded
 * text map 1:1 onto the original text for highlighting.
 */
object Fold {
    private val table: CharArray by lazy {
        CharArray(65536) { i ->
            val c = i.toChar()
            when {
                i < 128 -> c.lowercaseChar()
                Character.isSurrogate(c) -> c
                else -> {
                    val lower = Character.toLowerCase(c)
                    val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
                    val base = decomposed.firstOrNull() ?: lower
                    if (decomposed.length > 1 && Character.isLetter(base)) base else lower
                }
            }
        }
    }

    fun fold(text: CharSequence): String {
        val t = table
        val out = CharArray(text.length)
        for (i in 0 until text.length) out[i] = t[text[i].code]
        return String(out)
    }

    fun isIdeographic(c: Char): Boolean {
        val block = Character.UnicodeScript.of(c.code)
        return block == Character.UnicodeScript.HAN || block == Character.UnicodeScript.HIRAGANA ||
            block == Character.UnicodeScript.KATAKANA || block == Character.UnicodeScript.HANGUL ||
            block == Character.UnicodeScript.THAI
    }

    /** Tokens of folded text: runs of letters/digits; each ideographic character is its own token. */
    inline fun tokens(folded: String, consumer: (token: String, start: Int) -> Unit) {
        var i = 0
        val n = folded.length
        while (i < n) {
            val c = folded[i]
            if (!Character.isLetterOrDigit(c)) {
                i++
                continue
            }
            if (isIdeographic(c)) {
                consumer(c.toString(), i)
                i++
                continue
            }
            val start = i
            while (i < n && Character.isLetterOrDigit(folded[i]) && !isIdeographic(folded[i])) i++
            consumer(folded.substring(start, i), start)
        }
    }
}

data class SearchHit(
    val id: String,
    val score: Double,
    val nameRanges: List<IntRange>,
    val snippet: String?,
    val snippetRanges: List<IntRange>,
    val matchedContent: Boolean,
)

enum class SearchScope { ALL, NAMES, CONTENT }

/**
 * In-memory full-text index. Lives only while the vault is unlocked and is persisted encrypted.
 *
 * - Content: term → (doc → term frequency) in a sorted map, so a query word matches every indexed word it
 *   prefixes (type-ahead search) via a single sub-map range scan.
 * - Names: folded names are scanned with substring matching, which is cheap even for tens of thousands of items.
 * - "Quoted phrases" are verified against the folded text after candidate generation.
 */
class SearchIndex {
    private class Doc(val id: String, var name: String, var nameFolded: String, var text: String, var textFolded: String, var terms: Set<String>, var length: Int)

    private val docs = ArrayList<Doc?>()
    private var lengths = IntArray(64)
    private val byId = HashMap<String, Int>()
    private val free = ArrayDeque<Int>()
    private val postings = TreeMap<String, HashMap<Int, Int>>()
    private var liveDocs = 0

    @Synchronized
    fun size(): Int = liveDocs

    @Synchronized
    fun contains(id: String): Boolean = byId.containsKey(id)

    @Synchronized
    fun put(id: String, name: String, text: String?) {
        remove(id)
        val content = text.orEmpty()
        val folded = Fold.fold(content)
        val counts = HashMap<String, Int>()
        var length = 0
        Fold.tokens(folded) { t, _ ->
            counts[t] = (counts[t] ?: 0) + 1
            length++
        }
        val idx = free.removeFirstOrNull() ?: docs.size.also { docs.add(null) }
        docs[idx] = Doc(id, name, Fold.fold(name), content, folded, counts.keys, length)
        if (idx >= lengths.size) lengths = lengths.copyOf(maxOf(lengths.size * 2, idx + 1))
        lengths[idx] = length
        byId[id] = idx
        for ((term, count) in counts) postings.getOrPut(term) { HashMap(4) }[idx] = count
        liveDocs++
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val doc = byId[id]?.let { docs[it] } ?: return
        doc.name = name
        doc.nameFolded = Fold.fold(name)
    }

    @Synchronized
    fun remove(id: String) {
        val idx = byId.remove(id) ?: return
        val doc = docs[idx] ?: return
        for (term in doc.terms) {
            val p = postings[term] ?: continue
            p.remove(idx)
            if (p.isEmpty()) postings.remove(term)
        }
        docs[idx] = null
        free.addLast(idx)
        liveDocs--
    }

    @Synchronized
    fun clear() {
        docs.clear(); byId.clear(); free.clear(); postings.clear(); liveDocs = 0
    }

    @Synchronized
    fun textOf(id: String): String? = byId[id]?.let { docs[it]?.text }

    data class ParsedQuery(val terms: List<String>, val phrases: List<String>) {
        val isEmpty get() = terms.isEmpty() && phrases.isEmpty()
    }

    fun parse(raw: String): ParsedQuery {
        val phrases = ArrayList<String>()
        val rest = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') {
                val end = raw.indexOf('"', i + 1)
                if (end > i + 1) {
                    phrases.add(Fold.fold(raw.substring(i + 1, end)).trim())
                    i = end + 1
                    continue
                }
            }
            rest.append(c)
            i++
        }
        val terms = ArrayList<String>()
        Fold.tokens(Fold.fold(rest)) { t, _ -> if (t !in terms) terms.add(t) }
        return ParsedQuery(terms, phrases.filter { it.isNotEmpty() })
    }

    @Synchronized
    fun search(raw: String, scope: SearchScope = SearchScope.ALL, limit: Int = 200): List<SearchHit> {
        val q = parse(raw)
        if (q.isEmpty) return emptyList()
        val needles = q.terms + q.phrases
        val scores = HashMap<Int, Double>()
        val nameHit = HashSet<Int>()
        val contentHit = HashSet<Int>()

        if (scope != SearchScope.CONTENT) {
            for (idx in docs.indices) {
                val d = docs[idx] ?: continue
                var s = 0.0
                var all = true
                for (n in needles) {
                    val at = d.nameFolded.indexOf(n)
                    if (at < 0) { all = false; continue }
                    s += when {
                        d.nameFolded == n -> 60.0
                        at == 0 -> 40.0
                        !Character.isLetterOrDigit(d.nameFolded[at - 1]) -> 30.0
                        else -> 18.0
                    }
                }
                if (all && s > 0) {
                    scores[idx] = s + 25.0
                    nameHit.add(idx)
                }
            }
        }

        if (scope != SearchScope.NAMES) {
            val termSets = (q.terms + q.phrases.flatMap { p -> buildList { Fold.tokens(p) { t, _ -> add(t) } } }).distinct()
            val n = docs.size
            val total = liveDocs.coerceAtLeast(1).toDouble()
            var acc: DoubleArray? = null
            for (term in termSets) {
                val matches = DoubleArray(n)
                val range = postings.subMap(term, true, term + Char.MAX_VALUE, false)
                for ((indexed, docMap) in range) {
                    val idf = ln(1.0 + total / docMap.size)
                    val exact = if (indexed.length == term.length) 1.0 else 0.6
                    for ((idx, tf) in docMap) {
                        val len = lengths[idx]
                        matches[idx] += idf * exact * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * len / 300.0))
                    }
                }
                val prev = acc
                if (prev == null) {
                    acc = matches
                } else {
                    var any = false
                    for (i in 0 until n) {
                        if (prev[i] > 0.0 && matches[i] > 0.0) { prev[i] += matches[i]; any = true } else prev[i] = 0.0
                    }
                    if (!any) break
                }
            }
            val final = acc
            if (final != null) {
                for (idx in 0 until n) {
                    val s = final[idx]
                    if (s <= 0.0) continue
                    val d = docs[idx] ?: continue
                    if (q.phrases.any { !d.textFolded.contains(it) }) continue
                    contentHit.add(idx)
                    scores[idx] = (scores[idx] ?: 0.0) + s * 4.0 + q.phrases.size * 10.0
                }
            }
        }

        return scores.entries.sortedByDescending { it.value }.take(limit).mapNotNull { (idx, score) ->
            val d = docs[idx] ?: return@mapNotNull null
            val (snippet, ranges) = if (idx in contentHit) snippet(d, needles) else (null to emptyList())
            SearchHit(d.id, score, if (idx in nameHit) ranges(d.nameFolded, needles) else emptyList(), snippet, ranges, idx in contentHit)
        }
    }

    private fun ranges(folded: String, needles: List<String>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (n in needles) {
            var at = folded.indexOf(n)
            var guard = 0
            while (at >= 0 && guard++ < 64) {
                out.add(at until at + n.length)
                at = folded.indexOf(n, at + n.length)
            }
        }
        return merge(out)
    }

    private fun merge(list: List<IntRange>): List<IntRange> {
        if (list.size < 2) return list
        val sorted = list.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else { out.add(cur); r }
        }
        out.add(cur)
        return out
    }

    private fun snippet(d: Doc, needles: List<String>): Pair<String, List<IntRange>> {
        var first = Int.MAX_VALUE
        for (n in needles) {
            var at = d.textFolded.indexOf(n)
            // Prefer a match at a word start.
            while (at > 0 && Character.isLetterOrDigit(d.textFolded[at - 1])) {
                val next = d.textFolded.indexOf(n, at + 1)
                if (next < 0) break
                at = next
            }
            if (at in 0 until first) first = at
        }
        if (first == Int.MAX_VALUE) first = 0
        var start = (first - 48).coerceAtLeast(0)
        while (start > 0 && start > first - 70 && !d.text[start - 1].isWhitespace()) start--
        val end = (first + 140).coerceAtMost(d.text.length)
        val raw = d.text.substring(start, end)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < d.text.length) "…" else ""
        val cleaned = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val shown = prefix + cleaned + suffix
        val local = ranges(Fold.fold(cleaned), needles).map { (it.first + prefix.length)..(it.last + prefix.length) }
        return shown to local
    }

    // ---- persistence: the index is serialized as (id, name, text) triples and rebuilt on load ----

    @Synchronized
    fun entries(): List<Triple<String, String, String>> = docs.filterNotNull().map { Triple(it.id, it.name, it.text) }
}
