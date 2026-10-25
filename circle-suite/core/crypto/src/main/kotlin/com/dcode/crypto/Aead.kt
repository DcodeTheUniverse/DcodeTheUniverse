package com.dcode.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class CryptoException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Authenticated encryption primitives.
 *
 * AES-256-GCM uses the platform provider (hardware AES on ARMv8). ChaCha20-Poly1305 uses the platform
 * provider when present (Conscrypt on Android, SunJCE on desktop JVMs) and falls back to Bouncy Castle.
 */
object Aead {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
            if (aad != null) c.updateAAD(aad)
            return c.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("AES-GCM authentication failed", e)
        }
    }

    fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray =
        ChaCha.seal(key, nonce, plaintext, aad)

    fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray =
        try {
            ChaCha.open(key, nonce, ciphertext, aad)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("ChaCha20-Poly1305 authentication failed", e)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw CryptoException("ChaCha20-Poly1305 authentication failed", e)
        }

    /**
     * Dual-cipher cascade: ChaCha20-Poly1305 inside AES-256-GCM, with independent keys.
     * An attacker must break both ciphers (and both MACs) to read or forge content.
     */
    fun cascadeSeal(aesKey: ByteArray, chachaKey: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val inner = chachaSeal(chachaKey, nonce, plaintext, aad)
        try {
            return aesGcmSeal(aesKey, nonce, inner, aad)
        } finally {
            Bytes.wipe(inner)
        }
    }

    fun cascadeOpen(aesKey: ByteArray, chachaKey: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val inner = aesGcmOpen(aesKey, nonce, ciphertext, aad)
        try {
            return chachaOpen(chachaKey, nonce, inner, aad)
        } finally {
            Bytes.wipe(inner)
        }
    }

    /** Convenience: random nonce prepended. Used for small key-wrapping blobs. */
    fun sealWithRandomNonce(key: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val nonce = Bytes.random(NONCE_SIZE)
        return Bytes.concat(nonce, aesGcmSeal(key, nonce, plaintext, aad))
    }

    fun openWithRandomNonce(key: ByteArray, blob: ByteArray, aad: ByteArray?): ByteArray {
        if (blob.size < NONCE_SIZE + TAG_SIZE) throw CryptoException("Ciphertext too short")
        val nonce = blob.copyOfRange(0, NONCE_SIZE)
        return aesGcmOpen(key, nonce, blob.copyOfRange(NONCE_SIZE, blob.size), aad)
    }

    private object ChaCha {
        private val platformName: String? by lazy {
            listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding").firstOrNull { name ->
                try {
                    val c = Cipher.getInstance(name)
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "ChaCha20"), IvParameterSpec(ByteArray(12)))
                    c.doFinal(ByteArray(1))
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }

        fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
            val name = platformName ?: return bc(true, key, nonce, plaintext, aad)
            val c = Cipher.getInstance(name)
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad != null) c.updateAAD(aad)
            return c.doFinal(plaintext)
        }

        fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
            val name = platformName ?: return bc(false, key, nonce, ciphertext, aad)
            val c = Cipher.getInstance(name)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad != null) c.updateAAD(aad)
            return c.doFinal(ciphertext)
        }

        private fun bc(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
            val engine = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
            engine.init(
                encrypt,
                org.bouncycastle.crypto.params.AEADParameters(
                    org.bouncycastle.crypto.params.KeyParameter(key), TAG_SIZE * 8, nonce, aad,
                ),
            )
            val out = ByteArray(engine.getOutputSize(input.size))
            var n = engine.processBytes(input, 0, input.size, out, 0)
            n += engine.doFinal(out, n)
            return if (n == out.size) out else out.copyOf(n)
        }
    }
}
