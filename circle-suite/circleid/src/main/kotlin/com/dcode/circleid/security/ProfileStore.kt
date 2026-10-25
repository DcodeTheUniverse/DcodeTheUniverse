package com.dcode.circleid.security

import android.content.Context
import com.dcode.circle.core.FaceTemplate
import com.dcode.crypto.Bytes
import com.dcode.crypto.SecretStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher

@Serializable
data class ClientRecord(
    val packageName: String,
    val displayName: String,
    val certSha256: String,
    val secret: String,
    val pairedAt: Long,
    val lastVerifiedAt: Long = 0,
    val verifications: Int = 0,
)

/** Everything biometric or secret that Circle ID knows. Encrypted at rest under the biometric-bound KEK. */
@Serializable
data class Profile(
    val version: Int = 1,
    val createdAt: Long,
    val faceSamples: List<List<Float>>,
    val yawSign: Float = 1f,
    val pitchSign: Float = 1f,
    val vascular: List<Float>? = null,
    val clients: List<ClientRecord> = emptyList(),
) {
    fun faceTemplate() = FaceTemplate(faceSamples.map { it.toFloatArray() })
}

@Serializable
private data class WrappedKey(val iv: String, val wrapped: String)

/** An unlocked profile: exists only in memory, only after a fingerprint match. */
class ProfileSession(private val key: ByteArray, profile: Profile) {
    @Volatile
    var profile: Profile = profile
        internal set

    internal fun key(): ByteArray = key

    fun close() = Bytes.wipe(key)
}

class ProfileStore(context: Context, private val keystore: CircleKeystore) {
    private val dir = File(context.noBackupFilesDir, "circle").apply { mkdirs() }
    private val keyFile = File(dir, "profile.key")
    private val dataFile = File(dir, "profile.dat")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val isEnrolled: Boolean get() = keyFile.isFile && dataFile.isFile && keystore.exists(CircleKeystore.PROFILE_KEK)

    fun sealCipher(): Cipher {
        // A fresh enrolment always gets a fresh KEK.
        keystore.delete(CircleKeystore.PROFILE_KEK)
        return keystore.profileEncryptCipher()
    }

    /** Completes enrolment with a cipher authorised by BiometricPrompt. */
    fun seal(authorised: Cipher, profile: Profile): ProfileSession {
        val profileKey = Bytes.random(32)
        val wrapped = authorised.doFinal(profileKey)
        atomicWrite(keyFile, json.encodeToString(WrappedKey.serializer(), WrappedKey(Bytes.b64(authorised.iv), Bytes.b64(wrapped))).toByteArray())
        val session = ProfileSession(profileKey, profile)
        save(session)
        return session
    }

    /** @throws android.security.keystore.KeyPermanentlyInvalidatedException if fingerprints changed since enrolment. */
    fun unlockCipher(): Cipher {
        val w = json.decodeFromString(WrappedKey.serializer(), keyFile.readText())
        return keystore.profileDecryptCipher(Bytes.unb64(w.iv))
    }

    fun open(authorised: Cipher): ProfileSession {
        val w = json.decodeFromString(WrappedKey.serializer(), keyFile.readText())
        val key = authorised.doFinal(Bytes.unb64(w.wrapped))
        val bytes = SecretStream.open(key, CONTEXT, dataFile.readBytes())
        val profile = json.decodeFromString(Profile.serializer(), bytes.decodeToString())
        Bytes.wipe(bytes)
        return ProfileSession(key, profile)
    }

    fun save(session: ProfileSession) {
        val bytes = json.encodeToString(Profile.serializer(), session.profile).toByteArray()
        atomicWrite(dataFile, SecretStream.seal(session.key(), CONTEXT, bytes))
        Bytes.wipe(bytes)
    }

    fun update(session: ProfileSession, transform: (Profile) -> Profile) {
        session.profile = transform(session.profile)
        save(session)
    }

    fun wipe() {
        listOf(keyFile, dataFile).forEach { f ->
            if (f.exists()) runCatching { FileOutputStream(f).use { it.write(Bytes.random(f.length().toInt().coerceAtLeast(32))) } }
            f.delete()
        }
    }

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
    }

    companion object {
        private val CONTEXT = "circle-profile-v1".toByteArray()
    }
}
