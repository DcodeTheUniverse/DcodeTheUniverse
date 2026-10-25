package com.dcode.crypto

import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 time-based one-time passwords, compatible with Google Authenticator style otpauth:// URIs. */
object Totp {
    data class Config(
        val secret: ByteArray,
        val digits: Int = 6,
        val periodSeconds: Int = 30,
        val algorithm: String = "SHA1",
        val issuer: String? = null,
        val account: String? = null,
    )

    fun code(config: Config, epochMillis: Long): String {
        val counter = (epochMillis / 1000L) / config.periodSeconds
        return hotp(config.secret, counter, config.digits, config.algorithm)
    }

    fun secondsRemaining(config: Config, epochMillis: Long): Int =
        config.periodSeconds - ((epochMillis / 1000L) % config.periodSeconds).toInt()

    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: String = "SHA1"): String {
        val macName = when (algorithm.uppercase()) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(secret, macName))
        val h = mac.doFinal(Bytes.longBE(counter))
        val offset = h[h.size - 1].toInt() and 0x0f
        val binary = ((h[offset].toInt() and 0x7f) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Accepts either a bare base32 secret or an otpauth://totp/... URI. Returns null when unparsable. */
    fun parse(input: String): Config? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("otpauth://", ignoreCase = true)) {
            val secret = Base32.decode(text) ?: return null
            return if (secret.isEmpty()) null else Config(secret)
        }
        return try {
            val uri = java.net.URI(text)
            if (!uri.host.equals("totp", ignoreCase = true)) return null
            val label = URLDecoder.decode(uri.rawPath.trimStart('/'), "UTF-8")
            val params = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                k.lowercase() to URLDecoder.decode(v, "UTF-8")
            }
            val secret = Base32.decode(params["secret"] ?: return null) ?: return null
            val issuer = params["issuer"] ?: label.substringBefore(':', "").ifEmpty { null }
            Config(
                secret = secret,
                digits = params["digits"]?.toIntOrNull()?.coerceIn(6, 10) ?: 6,
                periodSeconds = params["period"]?.toIntOrNull()?.coerceIn(5, 300) ?: 30,
                algorithm = params["algorithm"] ?: "SHA1",
                issuer = issuer,
                account = label.substringAfter(':').ifEmpty { null },
            )
        } catch (_: Exception) {
            null
        }
    }
}

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray? {
        val clean = input.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
