package com.dcode.vault.core

import com.dcode.crypto.Argon2Params
import com.dcode.crypto.Bytes
import com.dcode.crypto.CryptoException
import com.dcode.crypto.Hkdf
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class VaultTamperedException(message: String) : CryptoException(message)

@Serializable
private data class Attempts(val failures: Int = 0, val lastFailureAt: Long = 0)

/**
 * Owns the on-disk layout of a vault:
 * ```
 * header.json   key slots + Circle pairing, MAC'd under the DEK
 * catalog.bin   CSV1-encrypted catalog (names, folders, passwords, metadata)
 * index.bin     CSV1-encrypted search corpus
 * blobs/<id>    CSV1-encrypted file and note contents (cascade cipher)
 * ```
 */
class VaultStore(val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val headerFile = File(dir, "header.json")
    private val attemptsFile = File(dir, "attempts.json")
    internal val blobDir = File(dir, "blobs")

    fun exists(): Boolean = headerFile.isFile

    fun readHeader(): VaultHeaderFile = HeaderCodec.decode(headerFile.readBytes())

    fun readHeaderBody(): VaultHeaderBody = HeaderCodec.parse(readHeader())

    fun writeHeader(body: VaultHeaderBody, dek: ByteArray) {
        atomicWrite(headerFile, HeaderCodec.encode(HeaderCodec.seal(body, dek)))
    }

    /** Creates a new, empty vault protected by [passphrase]. Returns the DEK (caller must wipe it). */
    fun create(passphrase: CharArray, params: Argon2Params = Argon2Params()): ByteArray {
        check(!exists()) { "Vault already exists" }
        dir.mkdirs()
        blobDir.mkdirs()
        val dek = Bytes.random(32)
        val vaultId = Bytes.b64(Bytes.random(16))
        val body = VaultHeaderBody(
            vaultId = vaultId,
            createdAt = clock(),
            passphrase = KeySlots.createPassphraseSlot(dek, vaultId, passphrase, params),
        )
        writeHeader(body, dek)
        open(dek).use { /* writes empty catalog + index */ it.flush() }
        return dek
    }

    /** Verifies the header MAC under [dek]; any mismatch means the header was modified offline. */
    fun verifyHeader(dek: ByteArray) {
        if (!HeaderCodec.verify(readHeader(), dek)) throw VaultTamperedException("Vault header failed integrity check")
    }

    fun passphraseLockoutRemainingMs(): Long {
        val a = readAttempts()
        if (a.failures < 3) return 0
        val delay = (1L shl (a.failures - 3).coerceAtMost(8)) * 1000L
        return (a.lastFailureAt + delay - clock()).coerceAtLeast(0)
    }

    fun failedAttempts(): Int = readAttempts().failures

    /** @throws CryptoException on a wrong passphrase (and records the failure for back-off). */
    fun unlockWithPassphrase(passphrase: CharArray): ByteArray {
        check(passphraseLockoutRemainingMs() == 0L) { "Too many attempts" }
        val body = readHeaderBody()
        val dek = try {
            KeySlots.openPassphraseSlot(body.passphrase, body.vaultId, passphrase)
        } catch (e: CryptoException) {
            val a = readAttempts()
            writeAttempts(Attempts(a.failures + 1, clock()))
            throw e
        }
        verifyHeader(dek)
        writeAttempts(Attempts())
        return dek
    }

    fun changePassphrase(dek: ByteArray, newPassphrase: CharArray, params: Argon2Params = Argon2Params()) {
        verifyHeader(dek)
        val body = readHeaderBody()
        writeHeader(body.copy(passphrase = KeySlots.createPassphraseSlot(dek, body.vaultId, newPassphrase, params)), dek)
    }

    fun updateHeader(dek: ByteArray, transform: (VaultHeaderBody) -> VaultHeaderBody) {
        verifyHeader(dek)
        writeHeader(transform(readHeaderBody()), dek)
    }

    fun open(dek: ByteArray): VaultSession = VaultSession(this, dek.copyOf(), clock)

    /** Crypto-shreds the vault: overwrites the header (the only place the wrapped DEK lives) then deletes everything. */
    fun destroy() {
        if (headerFile.exists()) {
            runCatching {
                FileOutputStream(headerFile).use { it.write(Bytes.random(headerFile.length().toInt().coerceAtLeast(64))); it.fd.sync() }
            }
        }
        dir.deleteRecursively()
    }

    /** Writes the raw (still fully encrypted) vault to a zip archive. Restoring requires the recovery passphrase. */
    fun exportBackup(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            dir.walkTopDown().filter { it.isFile && it.name != "attempts.json" && !it.name.endsWith(".tmp") }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun importBackup(input: InputStream) {
        check(!exists()) { "Vault already exists" }
        val staging = File(dir.parentFile, dir.name + ".restore")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(staging, e.name).canonicalFile
                if (!target.path.startsWith(staging.canonicalPath + File.separator)) throw IOException("Bad entry ${e.name}")
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { zip.copyTo(it) }
            }
        }
        val header = File(staging, "header.json")
        if (!header.isFile) {
            staging.deleteRecursively()
            throw IOException("Not a Vault backup")
        }
        // The device slot and Circle pairing belong to the old install; they are re-created after a passphrase unlock.
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw IOException("Could not move restored vault into place")
        File(dir, "blobs").mkdirs()
    }

    private fun readAttempts(): Attempts = try {
        HeaderCodec.json.decodeFromString(Attempts.serializer(), attemptsFile.readText())
    } catch (_: Exception) {
        Attempts()
    }

    private fun writeAttempts(a: Attempts) {
        runCatching { atomicWrite(attemptsFile, HeaderCodec.json.encodeToString(Attempts.serializer(), a).toByteArray()) }
    }

    internal fun file(name: String) = File(dir, name)

    companion object {
        fun atomicWrite(target: File, bytes: ByteArray) {
            atomicWrite(target) { it.write(bytes) }
        }

        fun atomicWrite(target: File, writer: (OutputStream) -> Unit) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                val buffered = java.io.BufferedOutputStream(fos, 64 * 1024)
                writer(object : java.io.FilterOutputStream(buffered) {
                    override fun write(b: ByteArray, off: Int, len: Int) = buffered.write(b, off, len)
                    override fun close() = buffered.flush()
                })
                buffered.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Atomic rename failed for ${target.name}")
            }
        }
    }
}

/** Keys derived from the DEK. Each purpose gets an independent key. */
internal class VaultKeys(val dek: ByteArray) {
    val catalog = Hkdf.derive(dek, null, "vault-catalog")
    val index = Hkdf.derive(dek, null, "vault-index")
    val blobs = Hkdf.derive(dek, null, "vault-blobs")

    fun wipe() = Bytes.wipe(dek, catalog, index, blobs)
}

internal object Contexts {
    val CATALOG = "catalog".toByteArray()
    val INDEX = "index".toByteArray()
    fun blob(id: String) = "blob|$id".toByteArray()
}
