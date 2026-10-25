package com.dcode.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Chunked, authenticated, streaming encryption ("CSV1").
 *
 * Layout:
 * ```
 * header  = "CSV1" | suite (1) | chunkShift (1) | salt (32)
 * chunk_i = flags (1) | length (4, BE) | ciphertext
 * ```
 * - A fresh 32-byte salt is drawn for every write, and per-stream keys are derived with
 *   HKDF(masterKey, salt, "csv1|" + context). Rewriting a file therefore never reuses a key/nonce pair.
 * - Every chunk's AAD binds the header, the caller's context (e.g. the file id), the chunk index and the
 *   final flag, which defeats chunk reordering, splicing between files and truncation.
 * - Suite 1 is the ChaCha20-Poly1305 + AES-256-GCM cascade, suite 2 is AES-256-GCM alone.
 */
object SecretStream {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val SUITE_CASCADE: Byte = 1
    const val SUITE_AES: Byte = 2
    private const val SALT_SIZE = 32
    const val HEADER_SIZE = 4 + 1 + 1 + SALT_SIZE
    private const val DEFAULT_SHIFT = 16 // 64 KiB chunks
    private const val FLAG_FINAL = 0x01
    private const val MAX_CHUNK_OVERHEAD = 64

    private class StreamKeys(val aes: ByteArray, val chacha: ByteArray, val noncePrefix: ByteArray) {
        fun wipe() = Bytes.wipe(aes, chacha, noncePrefix)
    }

    private fun deriveKeys(master: ByteArray, salt: ByteArray, context: ByteArray): StreamKeys {
        val prk = Hkdf.extract(salt, master)
        val okm = Hkdf.expand(prk, Bytes.concat("csv1|".toByteArray(), context), 68)
        Bytes.wipe(prk)
        val keys = StreamKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 68))
        Bytes.wipe(okm)
        return keys
    }

    private fun nonce(prefix: ByteArray, index: Long): ByteArray = Bytes.concat(prefix, Bytes.longBE(index))

    private fun aad(header: ByteArray, context: ByteArray, index: Long, flags: Int): ByteArray =
        Bytes.concat(header, Bytes.intBE(context.size), context, Bytes.longBE(index), byteArrayOf(flags.toByte()))

    /** Returns an OutputStream that encrypts everything written to it into [sink]. Closing it seals the final chunk. */
    fun encryptingStream(
        master: ByteArray,
        context: ByteArray,
        sink: OutputStream,
        suite: Byte = SUITE_CASCADE,
        chunkShift: Int = DEFAULT_SHIFT,
    ): OutputStream {
        require(suite == SUITE_CASCADE || suite == SUITE_AES)
        require(chunkShift in 10..22)
        val salt = Bytes.random(SALT_SIZE)
        val header = Bytes.concat(MAGIC, byteArrayOf(suite, chunkShift.toByte()), salt)
        sink.write(header)
        return EncryptingOutputStream(sink, deriveKeys(master, salt, context), header, context, suite, 1 shl chunkShift)
    }

    private class EncryptingOutputStream(
        out: OutputStream,
        private val keys: StreamKeys,
        private val header: ByteArray,
        private val context: ByteArray,
        private val suite: Byte,
        chunkSize: Int,
    ) : FilterOutputStream(out) {
        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (filled == buffer.size) emit(final = false)
                val n = minOf(remaining, buffer.size - filled)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n
                o += n
                remaining -= n
            }
        }

        private fun emit(final: Boolean) {
            val flags = if (final) FLAG_FINAL else 0
            val plain = buffer.copyOf(filled)
            val n = nonce(keys.noncePrefix, index)
            val a = aad(header, context, index, flags)
            val ct = if (suite == SUITE_CASCADE) {
                Aead.cascadeSeal(keys.aes, keys.chacha, n, plain, a)
            } else {
                Aead.aesGcmSeal(keys.aes, n, plain, a)
            }
            Bytes.wipe(plain)
            out.write(flags)
            out.write(Bytes.intBE(ct.size))
            out.write(ct)
            index++
            filled = 0
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(final = true)
                out.flush()
            } finally {
                buffer.fill(0)
                keys.wipe()
                out.close()
            }
        }
    }

    /** Returns an InputStream yielding the verified plaintext of [source]. Throws [CryptoException] on any tampering. */
    fun decryptingStream(master: ByteArray, context: ByteArray, source: InputStream): InputStream {
        val header = ByteArray(HEADER_SIZE)
        readFully(source, header)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw CryptoException("Not a CSV1 stream")
        val suite = header[4]
        if (suite != SUITE_CASCADE && suite != SUITE_AES) throw CryptoException("Unknown cipher suite $suite")
        val shift = header[5].toInt()
        if (shift !in 10..22) throw CryptoException("Bad chunk size")
        val salt = header.copyOfRange(6, HEADER_SIZE)
        return DecryptingInputStream(source, deriveKeys(master, salt, context), header, context, suite, 1 shl shift)
    }

    private class DecryptingInputStream(
        private val source: InputStream,
        private val keys: StreamKeys,
        private val header: ByteArray,
        private val context: ByteArray,
        private val suite: Byte,
        private val chunkSize: Int,
    ) : InputStream() {
        private var current: ByteArray = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var finished = false

        private fun nextChunk(): Boolean {
            if (finished) return false
            val flags = source.read()
            if (flags < 0) throw CryptoException("Stream truncated (missing final chunk)")
            if (flags and FLAG_FINAL.inv() != 0) throw CryptoException("Bad chunk flags")
            val lenBytes = ByteArray(4)
            readFully(source, lenBytes)
            val len = java.nio.ByteBuffer.wrap(lenBytes).int
            if (len < 0 || len > chunkSize + MAX_CHUNK_OVERHEAD) throw CryptoException("Bad chunk length")
            val ct = ByteArray(len)
            readFully(source, ct)
            val n = nonce(keys.noncePrefix, index)
            val a = aad(header, context, index, flags)
            Bytes.wipe(current)
            current = if (suite == SUITE_CASCADE) {
                Aead.cascadeOpen(keys.aes, keys.chacha, n, ct, a)
            } else {
                Aead.aesGcmOpen(keys.aes, n, ct, a)
            }
            pos = 0
            index++
            if (flags and FLAG_FINAL != 0) {
                finished = true
                if (source.read() != -1) throw CryptoException("Trailing data after final chunk")
                keys.wipe()
            }
            return true
        }

        override fun read(): Int {
            while (pos >= current.size) {
                if (!nextChunk()) return -1
            }
            return current[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) {
                if (!nextChunk()) return -1
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = current.size - pos

        override fun close() {
            Bytes.wipe(current)
            if (!finished) keys.wipe()
            source.close()
        }
    }

    fun seal(master: ByteArray, context: ByteArray, plaintext: ByteArray, suite: Byte = SUITE_CASCADE): ByteArray {
        val bos = ByteArrayOutputStream(plaintext.size + HEADER_SIZE + 64)
        encryptingStream(master, context, bos, suite).use { it.write(plaintext) }
        return bos.toByteArray()
    }

    fun open(master: ByteArray, context: ByteArray, ciphertext: ByteArray): ByteArray =
        decryptingStream(master, context, ByteArrayInputStream(ciphertext)).use { it.readBytes() }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) {
                throw CryptoException("Stream truncated", EOFException())
            }
            off += n
        }
    }
}
