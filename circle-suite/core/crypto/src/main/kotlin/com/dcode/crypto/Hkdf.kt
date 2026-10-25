package com.dcode.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val s = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        mac.init(SecretKeySpec(s, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        Bytes.wipe(previous)
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray?, info: String, length: Int = 32): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info.toByteArray(Charsets.UTF_8), length)
        } finally {
            Bytes.wipe(prk)
        }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }
}
