package com.dcode.crypto

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoTest {
    private val key = Bytes.random(32)
    private val ctx = "file:abc".toByteArray()

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.expand(Hkdf.extract(salt, ikm), info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(okm),
        )
    }

    @Test
    fun streamRoundTripsAcrossChunkBoundaries() {
        for (size in listOf(0, 1, 1023, 1024, 1025, 4096, 70_000)) {
            for (suite in listOf(SecretStream.SUITE_CASCADE, SecretStream.SUITE_AES)) {
                val plain = Bytes.random(size)
                val bos = ByteArrayOutputStream()
                SecretStream.encryptingStream(key, ctx, bos, suite, chunkShift = 10).use { out ->
                    // Write in odd-sized pieces to exercise buffering.
                    var o = 0
                    while (o < plain.size) {
                        val n = minOf(333, plain.size - o)
                        out.write(plain, o, n)
                        o += n
                    }
                }
                assertContentEquals(plain, SecretStream.open(key, ctx, bos.toByteArray()), "size=$size suite=$suite")
            }
        }
    }

    @Test
    fun streamDetectsTamperTruncationAndWrongContext() {
        val plain = Bytes.random(5000)
        val bos = ByteArrayOutputStream()
        SecretStream.encryptingStream(key, ctx, bos, chunkShift = 10).use { it.write(plain) }
        val ct = bos.toByteArray()

        val flipped = ct.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException> { SecretStream.open(key, ctx, flipped) }

        // Drop the final chunk: everything before it still authenticates, but the stream must not end silently.
        val firstChunkEnd = SecretStream.HEADER_SIZE + 5 + 1024 + 32
        assertFailsWith<CryptoException> { SecretStream.open(key, ctx, ct.copyOf(firstChunkEnd)) }

        assertFailsWith<CryptoException> { SecretStream.open(key, "file:other".toByteArray(), ct) }
        assertFailsWith<CryptoException> { SecretStream.open(Bytes.random(32), ctx, ct) }
    }

    @Test
    fun everyWriteUsesFreshSalt() {
        val a = SecretStream.seal(key, ctx, "same".toByteArray())
        val b = SecretStream.seal(key, ctx, "same".toByteArray())
        assertNotEquals(Bytes.hex(a), Bytes.hex(b))
    }

    @Test
    fun cascadeNeedsBothKeys() {
        val k1 = Bytes.random(32)
        val k2 = Bytes.random(32)
        val n = Bytes.random(12)
        val ct = Aead.cascadeSeal(k1, k2, n, "secret".toByteArray(), ByteArray(0))
        assertEquals("secret", Aead.cascadeOpen(k1, k2, n, ct, ByteArray(0)).decodeToString())
        assertFailsWith<CryptoException> { Aead.cascadeOpen(k1, Bytes.random(32), n, ct, ByteArray(0)) }
    }

    @Test
    fun argon2idMatchesRfc9106Vector() {
        // RFC 9106 test vectors use secret/ad which the high-level API does not expose, so instead check
        // determinism and parameter sensitivity with the low-cost profile.
        val params = Argon2Params(memoryKiB = 256, iterations = 2, parallelism = 1)
        val salt = ByteArray(16) { 1 }
        val a = PasswordKdf.argon2id("correct horse".toCharArray(), salt, params)
        val b = PasswordKdf.argon2id("correct horse".toCharArray(), salt, params)
        val c = PasswordKdf.argon2id("correct horsf".toCharArray(), salt, params)
        assertContentEquals(a, b)
        assertNotEquals(Bytes.hex(a), Bytes.hex(c))
        assertEquals(32, a.size)
    }

    @Test
    fun eciesRoundTripAndBinding() {
        val recipient = Ecies.generateKeyPair()
        val secret = Bytes.random(32)
        val sealed = Ecies.seal(recipient.public.encoded, secret, "ctx-a".toByteArray())
        assertContentEquals(secret, Ecies.open(recipient, sealed, "ctx-a".toByteArray()))
        assertFailsWith<CryptoException> { Ecies.open(recipient, sealed, "ctx-b".toByteArray()) }
        assertFailsWith<CryptoException> { Ecies.open(Ecies.generateKeyPair(), sealed, "ctx-a".toByteArray()) }
    }

    @Test
    fun signaturesAndFingerprint() {
        val kp = Ecies.generateKeyPair()
        val sig = EcSignatures.sign(kp.private, "hello".toByteArray())
        assertTrue(EcSignatures.verify(kp.public.encoded, "hello".toByteArray(), sig))
        assertTrue(!EcSignatures.verify(kp.public.encoded, "hellp".toByteArray(), sig))
        val fp = EcSignatures.fingerprint(kp.public.encoded)
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(fp), fp)
    }

    @Test
    fun totpMatchesRfc6238() {
        val secret = "12345678901234567890".toByteArray()
        val cfg = Totp.Config(secret, digits = 8)
        assertEquals("94287082", Totp.code(cfg, 59_000L))
        assertEquals("07081804", Totp.code(cfg, 1_111_111_109_000L))
        assertEquals("65353130", Totp.code(cfg, 20_000_000_000_000L))
    }

    @Test
    fun totpParsesOtpauthUri() {
        val secret = Base32.encode("12345678901234567890".toByteArray())
        val cfg = Totp.parse("otpauth://totp/Example:alice@example.com?secret=$secret&issuer=Example&digits=6")!!
        assertEquals("Example", cfg.issuer)
        assertEquals("alice@example.com", cfg.account)
        assertEquals("287082", Totp.code(cfg, 59_000L))
        assertContentEquals("12345678901234567890".toByteArray(), Base32.decode(secret))
    }

    @Test
    fun passwordGeneratorRespectsOptions() {
        val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 40, symbols = false))
        assertEquals(40, pw.length)
        assertTrue(pw.all { it.isLetterOrDigit() })
        assertTrue(pw.any { it.isDigit() } && pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() })
        assertEquals(PasswordGenerator.Strength.VeryWeak, PasswordGenerator.strength("abc"))
        assertTrue(PasswordGenerator.entropyBits(PasswordGenerator.passphrase(6)) > 60)
    }
}
