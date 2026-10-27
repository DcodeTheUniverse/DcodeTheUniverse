package com.axiom.launcher.solis.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Resolves only to public internet addresses. Loopback, private, link-local, carrier-grade NAT, multicast
 * and reserved ranges are dropped (also when embedded in IPv6), so a web page can never make Solis read the
 * user's router, a service on their network or the phone itself.
 */
class PublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val ok = delegate.lookup(hostname).filter { isPublic(it) }
        if (ok.isEmpty()) throw UnknownHostException("$hostname isn't on the public internet")
        return ok
    }

    companion object {
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            if (a is Inet4Address) return isPublicV4(b)
            if (a is Inet6Address) {
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                if (b0 and 0xfe == 0xfc) return false // fc00::/7 unique local
                if (b0 == 0xfe && b1 and 0xc0 == 0xc0) return false // fec0::/10 old site-local
                if (b.copyOfRange(0, 10).all { it.toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff)
                    return isPublicV4(b.copyOfRange(12, 16)) // ::ffff:a.b.c.d
                if (b.copyOfRange(0, 12).all { it.toInt() == 0 }) return false // ::/96 (compat, unspecified)
                if (b0 == 0x00 && b1 == 0x64 && (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b)
                    return isPublicV4(b.copyOfRange(12, 16)) // 64:ff9b::/96 NAT64
                if (b0 == 0x20 && b1 == 0x02) return isPublicV4(b.copyOfRange(2, 6)) // 2002::/16 6to4
                if (b0 == 0x20 && b1 == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0) return false // 2001::/32 Teredo
                return true
            }
            return false
        }

        private fun isPublicV4(b: ByteArray): Boolean {
            val o = b.map { it.toInt() and 0xff }
            return when {
                o[0] == 0 || o[0] == 10 || o[0] == 127 -> false
                o[0] == 100 && o[1] in 64..127 -> false // carrier-grade NAT
                o[0] == 169 && o[1] == 254 -> false
                o[0] == 172 && o[1] in 16..31 -> false
                o[0] == 192 && o[1] == 168 -> false
                o[0] == 192 && o[1] == 0 && (o[2] == 0 || o[2] == 2) -> false
                o[0] == 198 && (o[1] == 18 || o[1] == 19) -> false
                o[0] == 198 && o[1] == 51 && o[2] == 100 -> false
                o[0] == 203 && o[1] == 0 && o[2] == 113 -> false
                o[0] >= 224 -> false // multicast, reserved, broadcast
                else -> true
            }
        }

        /** An IP literal in a URL never goes through DNS, so it is checked directly. */
        fun literalAllowed(host: String): Boolean {
            val looksLiteral = host.contains(':') || host.all { it.isDigit() || it == '.' }
            if (!looksLiteral) return !host.equals("localhost", true) && !host.endsWith(".localhost", true) && !host.endsWith(".local", true)
            return runCatching { isPublic(InetAddress.getByName(host)) }.getOrDefault(false)
        }
    }
}

class WebPage(val url: String, val title: String, val text: String, val truncated: Boolean)

/** Fetches public web pages for Solis and returns their readable text. */
class WebReader(base: OkHttpClient, dns: Dns = PublicDns()) {
    private val http = base.newBuilder()
        .dns(dns)
        // A proxy would resolve hosts itself, past the address filter.
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun read(url: String, maxChars: Int = 30_000): WebPage = withContext(Dispatchers.IO) {
        var target = parse(url)
        repeat(MAX_REDIRECTS + 1) {
            val req = Request.Builder().url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,application/json;q=0.8,*/*;q=0.3")
                .build()
            http.newCall(req).execute().use { r ->
                if (r.isRedirect) {
                    val next = r.header("Location")?.let { target.resolve(it) } ?: throw WebException("The page redirected nowhere")
                    target = checked(next)
                    return@repeat
                }
                if (!r.isSuccessful) throw WebException("The site answered ${r.code}${r.message.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}")
                val body = r.body ?: throw WebException("The page was empty")
                val type = body.contentType()
                val mime = type?.let { "${it.type}/${it.subtype}" }?.lowercase() ?: "text/html"
                if (mime == "application/pdf") throw WebException("That link is a PDF. Download it and share it to Solis to read it.")
                if (!(mime.startsWith("text/") || mime.contains("json") || mime.contains("xml"))) throw WebException("That link isn't a web page ($mime)")
                val source = body.source()
                source.request(MAX_BYTES)
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES))
                val charset = type?.charset() ?: HtmlText.declaredCharset(bytes) ?: Charsets.UTF_8
                val raw = String(bytes, charset)
                val isHtml = mime.contains("html") || (mime == "text/plain" && raw.trimStart().startsWith("<"))
                val (title, text) = if (isHtml) HtmlText.extract(raw) else "" to raw
                val clean = text.trim()
                if (clean.isEmpty()) throw WebException("The page has no readable text (it may need JavaScript)")
                return@withContext WebPage(target.toString(), title, clean.take(maxChars), clean.length > maxChars)
            }
        }
        throw WebException("Too many redirects")
    }

    private fun parse(url: String): HttpUrl {
        val u = url.trim().let { if (!it.contains("://")) "https://$it" else it }.toHttpUrlOrNull()
            ?: throw WebException("That isn't a web address")
        return checked(u)
    }

    private fun checked(u: HttpUrl): HttpUrl {
        if (u.scheme != "http" && u.scheme != "https") throw WebException("Only http and https links can be read")
        if (!PublicDns.literalAllowed(u.host)) throw WebException("Solis only reads pages on the public internet")
        return u
    }

    companion object {
        const val MAX_BYTES = 2_000_000L
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 AxiomSolis/2.2"
    }
}

class WebException(message: String) : Exception(message)

/** Readable text from HTML without a browser: main content first, scripts and markup removed. */
object HtmlText {
    private val DROP = Regex("(?is)<(script|style|noscript|svg|template|iframe|canvas|form|select|button)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val OG_TITLE = Regex("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)")
    private val BLOCK_END = Regex("(?i)</(p|div|section|article|li|ul|ol|h[1-6]|tr|table|blockquote|pre|header|footer|figcaption|dd|dt)\\s*>")
    private val BREAK = Regex("(?i)<(br|hr)\\b[^>]*>")
    private val HEADING = Regex("(?i)<h([1-6])\\b[^>]*>")
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val CELL = Regex("(?i)</t[dh]\\s*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val CHARSET = Regex("(?i)<meta[^>]+charset=[\"']?([A-Za-z0-9_\\-]+)")

    fun extract(html: String): Pair<String, String> {
        val noComments = COMMENT.replace(html, " ")
        val title = (OG_TITLE.find(noComments)?.groupValues?.get(1) ?: TITLE.find(noComments)?.groupValues?.get(1)).orEmpty()
            .let { decode(TAG.replace(it, "")).replace(Regex("\\s+"), " ").trim() }
        var body = DROP.replace(noComments, " ")
        // Prefer the page's main content over navigation and footers when the page marks it.
        section(body, "article")?.let { body = it } ?: section(body, "main")?.let { body = it }
            ?: section(body, "body")?.let { body = it }
        body = HEADING.replace(body) { "\n\n" + "#".repeat(it.groupValues[1].toInt().coerceAtMost(3)) + " " }
        body = LIST_ITEM.replace(body, "\n• ")
        body = CELL.replace(body, " | ")
        body = BLOCK_END.replace(body, "\n\n")
        body = BREAK.replace(body, "\n")
        body = decode(TAG.replace(body, " "))
        val lines = body.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        val out = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isEmpty()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(l)
            blank = 0
        }
        return title to out.toString()
    }

    private fun section(html: String, tag: String): String? {
        val m = Regex("(?is)<$tag\\b[^>]*>(.*)</$tag\\s*>").find(html) ?: return null
        return m.groupValues[1].takeIf { TAG.replace(it, "").isNotBlank() && TAG.replace(it, "").trim().length > 200 || tag == "body" }
    }

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x", true) -> e.substring(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp.coerceIn(0, 0x10FFFF))) } ?: m.value
            else -> NAMED[e.lowercase()] ?: m.value
        }
    }

    fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return CHARSET.find(head)?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() }
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®",
        "trade" to "™", "euro" to "€", "pound" to "£", "yen" to "¥", "middot" to "·", "bull" to "•", "deg" to "°",
        "times" to "×", "eacute" to "é", "egrave" to "è", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
    )
}
