package com.dcode.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Small, allocation-conscious byte helpers shared by every crypto primitive in the suite. */
object Bytes {
    private val random: SecureRandom by lazy { SecureRandom() }

    fun random(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun randomInt(bound: Int): Int = random.nextInt(bound)

    /** Best-effort zeroisation. The JVM may have copied the array, but we never keep secrets longer than needed. */
    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }

    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun longBE(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    fun intBE(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(chars)
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /** UTF-8 encode a CharArray without creating an intermediate String (so the secret can be wiped). */
    fun utf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill(0)
        return out
    }
}
