package com.dcode.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * ECIES over NIST P-256: ephemeral ECDH -> HKDF-SHA256 -> AES-256-GCM.
 * Used by Circle ID to release a per-app secret only to the exact app instance that asked for it.
 */
object Ecies {
    private const val CURVE = "secp256r1"

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE))
        return kpg.generateKeyPair()
    }

    fun decodePublicKey(x509: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))

    fun seal(recipientPublic: ByteArray, plaintext: ByteArray, context: ByteArray): ByteArray {
        val recipient = decodePublicKey(recipientPublic)
        val eph = generateKeyPair()
        val ephPub = eph.public.encoded
        val key = deriveKey(eph.private, recipient, ephPub, recipientPublic, context)
        try {
            val nonce = Bytes.random(Aead.NONCE_SIZE)
            val ct = Aead.aesGcmSeal(key, nonce, plaintext, context)
            return ByteBuffer.allocate(2 + ephPub.size + nonce.size + ct.size)
                .putShort(ephPub.size.toShort()).put(ephPub).put(nonce).put(ct).array()
        } finally {
            Bytes.wipe(key)
        }
    }

    fun open(recipient: KeyPair, sealed: ByteArray, context: ByteArray): ByteArray {
        try {
            val buf = ByteBuffer.wrap(sealed)
            val len = buf.short.toInt()
            if (len <= 0 || len > 256) throw CryptoException("Bad ECIES envelope")
            val ephPub = ByteArray(len).also { buf.get(it) }
            val nonce = ByteArray(Aead.NONCE_SIZE).also { buf.get(it) }
            val ct = ByteArray(buf.remaining()).also { buf.get(it) }
            val key = deriveKey(recipient.private, decodePublicKey(ephPub), ephPub, recipient.public.encoded, context)
            try {
                return Aead.aesGcmOpen(key, nonce, ct, context)
            } finally {
                Bytes.wipe(key)
            }
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("ECIES open failed", e)
        }
    }

    private fun deriveKey(priv: PrivateKey, pub: PublicKey, ephPub: ByteArray, recipientPub: ByteArray, context: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val z = ka.generateSecret()
        try {
            val prk = Hkdf.extract(Bytes.concat(ephPub, recipientPub), z)
            val k = Hkdf.expand(prk, Bytes.concat("circle-ecies-v1|".toByteArray(), context), 32)
            Bytes.wipe(prk)
            return k
        } finally {
            Bytes.wipe(z)
        }
    }
}

/** ECDSA P-256 / SHA-256 signature verification (signing happens inside Android Keystore). */
object EcSignatures {
    fun verify(publicKeyX509: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(Ecies.decodePublicKey(publicKeyX509))
        s.update(data)
        s.verify(signature)
    } catch (_: Exception) {
        false
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    /** A short, human-checkable fingerprint of a public key, e.g. "7KQ2-9XMA-4RTB-H3VE". */
    fun fingerprint(publicKeyX509: ByteArray): String {
        val digest = Bytes.sha256(publicKeyX509)
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        var i = 0
        while (sb.length < 16) {
            value = (value shl 8) or (digest[i++].toInt() and 0xff)
            bits += 8
            while (bits >= 5 && sb.length < 16) {
                sb.append(alphabet[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        return sb.chunked(4).joinToString("-")
    }
}
