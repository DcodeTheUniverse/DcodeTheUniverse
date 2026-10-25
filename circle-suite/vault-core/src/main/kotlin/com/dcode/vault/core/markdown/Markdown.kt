package com.dcode.vault.core.markdown

/** A compact CommonMark/GFM-flavoured parser covering what READMEs and idea notes actually use. */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
    data class Image(val alt: String, val url: String) : Inline
    data object LineBreak : Inline
}

sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : Block
    data class Table(val header: List<List<Inline>>, val alignments: List<Align>, val rows: List<List<List<Inline>>>) : Block
    data object Rule : Block
}

data class ListItem(val blocks: List<Block>, val checked: Boolean?)

enum class Align { Start, Center, End }

object Markdown {
    fun parse(source: String): List<Block> = parseLines(source.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private val heading = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$")
    private val fence = Regex("^ {0,3}(```+|~~~+)\\s*([^`\\s]*).*$")
    private val rule = Regex("^ {0,3}((?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
    private val bullet = Regex("^( *)([-*+])\\s+(.*)$")
    private val ordered = Regex("^( *)(\\d{1,9})[.)]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun parseLines(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var i = 0
        val para = ArrayList<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val sb = StringBuilder()
            para.forEachIndexed { idx, l ->
                if (idx > 0) sb.append(if (para[idx - 1].endsWith("  ")) "\n" else " ")
                sb.append(l.trim())
            }
            blocks.add(Block.Paragraph(inlines(sb.toString())))
            para.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flushPara(); i++; continue }

            val fm = fence.matchEntire(line)
            if (fm != null) {
                flushPara()
                val marker = fm.groupValues[1]
                val lang = fm.groupValues[2]
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) { code.add(lines[i]); i++ }
                i++ // closing fence (or EOF)
                blocks.add(Block.CodeBlock(lang, code.joinToString("\n")))
                continue
            }

            val hm = heading.matchEntire(line)
            if (hm != null) {
                flushPara()
                blocks.add(Block.Heading(hm.groupValues[1].length, inlines(hm.groupValues[2])))
                i++
                continue
            }

            // Setext headings: a paragraph line followed by === or ---
            if (para.isNotEmpty() && Regex("^ {0,3}(=+|-+)\\s*$").matches(line)) {
                val text = para.joinToString(" ") { it.trim() }
                para.clear()
                blocks.add(Block.Heading(if (line.trim().startsWith("=")) 1 else 2, inlines(text)))
                i++
                continue
            }

            if (rule.matches(line)) { flushPara(); blocks.add(Block.Rule); i++; continue }

            if (line.trimStart().startsWith(">")) {
                flushPara()
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val l = lines[i].trimStart()
                    inner.add(if (l.startsWith(">")) l.removePrefix(">").removePrefix(" ") else l)
                    i++
                }
                blocks.add(Block.Quote(parseLines(inner)))
                continue
            }

            if (bullet.matches(line) || ordered.matches(line)) {
                flushPara()
                val (block, next) = parseList(lines, i)
                blocks.add(block)
                i = next
                continue
            }

            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val header = cells(line)
                val aligns = cells(lines[i + 1]).map { c ->
                    val t = c.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> Align.Center
                        t.endsWith(":") -> Align.End
                        else -> Align.Start
                    }
                }
                i += 2
                val rows = ArrayList<List<List<Inline>>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows.add(cells(lines[i]).map { inlines(it.trim()) })
                    i++
                }
                blocks.add(Block.Table(header.map { inlines(it.trim()) }, aligns, rows))
                continue
            }

            if (para.isEmpty() && (line.startsWith("    ") || line.startsWith("\t"))) {
                val code = ArrayList<String>()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    code.add(lines[i].removePrefix("\t").let { if (it.startsWith("    ")) it.substring(4) else it })
                    i++
                }
                blocks.add(Block.CodeBlock("", code.joinToString("\n").trimEnd('\n')))
                continue
            }

            para.add(line)
            i++
        }
        flushPara()
        return blocks
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') { sb.append('|'); i += 2; continue }
            if (c == '|') { out.add(sb.toString()); sb.clear() } else sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseList(lines: List<String>, startIndex: Int): Pair<Block, Int> {
        val first = lines[startIndex]
        val isOrdered = ordered.matches(first) && !bullet.matches(first)
        val baseIndent = first.length - first.trimStart().length
        val start = if (isOrdered) ordered.matchEntire(first)!!.groupValues[2].toInt() else 1
        val items = ArrayList<ListItem>()
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i]
            val m = (if (isOrdered) ordered else bullet).matchEntire(line)
            val indent = line.length - line.trimStart().length
            if (m == null || indent != baseIndent) break
            val body = ArrayList<String>()
            var content = m.groupValues[3]
            var checked: Boolean? = null
            Regex("^\\[([ xX])]\\s+(.*)$").matchEntire(content)?.let {
                checked = it.groupValues[1] != " "
                content = it.groupValues[2]
            }
            body.add(content)
            val contentIndent = baseIndent + 2
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    // A blank line continues the item only if the next line is indented into it.
                    if (i + 1 < lines.size && lines[i + 1].isNotBlank() && lines[i + 1].length - lines[i + 1].trimStart().length >= contentIndent) {
                        body.add(""); i++; continue
                    }
                    break
                }
                val ind = l.length - l.trimStart().length
                if (ind < contentIndent && (bullet.matches(l) || ordered.matches(l))) break
                if (ind < contentIndent && body.isNotEmpty() && (heading.matches(l) || fence.matches(l) || l.trimStart().startsWith(">"))) break
                body.add(if (ind >= contentIndent) l.substring(minOf(contentIndent, ind)) else l.trimStart())
                i++
            }
            items.add(ListItem(parseLines(body), checked))
        }
        return Block.ListBlock(isOrdered, start, items) to i
    }

    fun inlines(text: String): List<Inline> {
        val out = ArrayList<Inline>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(Inline.Text(sb.toString())); sb.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!|~<>" -> { sb.append(text[i + 1]); i += 2 }
                c == '\n' -> { flush(); out.add(Inline.LineBreak); i++ }
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < text.length && text[i + ticks] == '`') ticks++
                    val marker = "`".repeat(ticks)
                    val end = text.indexOf(marker, i + ticks)
                    if (end < 0) { sb.append(marker); i += ticks } else {
                        flush(); out.add(Inline.Code(text.substring(i + ticks, end).trim())); i = end + ticks
                    }
                }
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val link = parseLink(text, i + 1)
                    if (link == null) { sb.append(c); i++ } else {
                        flush(); out.add(Inline.Image(link.first, link.second)); i = link.third
                    }
                }
                c == '[' -> {
                    val link = parseLink(text, i)
                    if (link == null) { sb.append(c); i++ } else {
                        flush(); out.add(Inline.Link(inlines(link.first), link.second)); i = link.third
                    }
                }
                c == '<' && text.indexOf('>', i) > i -> {
                    val end = text.indexOf('>', i)
                    val inner = text.substring(i + 1, end)
                    if (inner.startsWith("http://") || inner.startsWith("https://") || (inner.contains('@') && !inner.contains(' '))) {
                        flush(); out.add(Inline.Link(listOf(Inline.Text(inner)), if (inner.contains('@') && !inner.contains("://")) "mailto:$inner" else inner)); i = end + 1
                    } else { sb.append(c); i++ }
                }
                (c == '*' || c == '_' || c == '~') -> {
                    val double = i + 1 < text.length && text[i + 1] == c
                    if (c == '~' && !double) { sb.append(c); i++; continue }
                    val marker = if (double) "$c$c" else "$c"
                    val canOpen = i + marker.length < text.length && !text[i + marker.length].isWhitespace() &&
                        !(c == '_' && i > 0 && text[i - 1].isLetterOrDigit())
                    val end = if (canOpen) findClose(text, i + marker.length, marker) else -1
                    if (end < 0) { sb.append(marker); i += marker.length } else {
                        flush()
                        val inner = inlines(text.substring(i + marker.length, end))
                        out.add(
                            when {
                                c == '~' -> Inline.Strike(inner)
                                double -> Inline.Bold(inner)
                                else -> Inline.Italic(inner)
                            },
                        )
                        i = end + marker.length
                    }
                }
                (c == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i)) && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != ')') end++
                    while (end > i && text[end - 1] in ".,;:!?") end--
                    flush()
                    val url = text.substring(i, end)
                    out.add(Inline.Link(listOf(Inline.Text(url)), url))
                    i = end
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun findClose(text: String, from: Int, marker: String): Int {
        var i = from
        while (i <= text.length - marker.length) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > 0) { i = end + 1; continue }
            }
            if (text.startsWith(marker, i) && !text[i - 1].isWhitespace()) {
                val after = i + marker.length
                // Don't close a single marker on the first half of a double one ("*a **b** c*").
                if (marker.length == 1 && after < text.length && text[after] == marker[0]) {
                    val inner = findClose(text, after + 1, marker + marker)
                    if (inner > 0) { i = inner + 2; continue }
                }
                if (!(marker[0] == '_' && after < text.length && text[after].isLetterOrDigit())) return i
            }
            i++
        }
        return -1
    }

    /** Parses `[text](url "title")` starting at '['. Returns (text, url, indexAfter). */
    private fun parseLink(text: String, open: Int): Triple<String, String, Int>? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (i >= text.length || i + 1 >= text.length || text[i + 1] != '(') return null
        val close = text.indexOf(')', i + 2)
        if (close < 0) return null
        val target = text.substring(i + 2, close).trim()
        val url = target.substringBefore(' ').removePrefix("<").removeSuffix(">")
        return Triple(text.substring(open + 1, i), url, close + 1)
    }

    /** Plain-text rendition used for previews and search snippets. */
    fun plainText(inlines: List<Inline>): String = buildString {
        for (n in inlines) when (n) {
            is Inline.Text -> append(n.text)
            is Inline.Code -> append(n.text)
            is Inline.Bold -> append(plainText(n.children))
            is Inline.Italic -> append(plainText(n.children))
            is Inline.Strike -> append(plainText(n.children))
            is Inline.Link -> append(plainText(n.children))
            is Inline.Image -> append(n.alt)
            Inline.LineBreak -> append('\n')
        }
    }
}
