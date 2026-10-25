package com.dcode.vault.core

import com.dcode.crypto.Aead
import com.dcode.crypto.Argon2Params
import com.dcode.crypto.Bytes
import com.dcode.crypto.CryptoException
import com.dcode.crypto.Hkdf
import com.dcode.crypto.PasswordKdf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PassphraseSlot(
    val salt: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val wrapped: String,
) {
    val params: Argon2Params get() = Argon2Params(memoryKiB, iterations, parallelism)
}

@Serializable
enum class DeviceSlotMode {
    /** DEK = Keystore(device-bound) ∘ AES-GCM(K from Circle ID's biometric-gated release secret). */
    CIRCLE,

    /** DEK wrapped by a Keystore key that needs a strong biometric for every use. */
    BIOMETRIC,
}

@Serializable
data class DeviceSlot(
    val mode: DeviceSlotMode,
    val keyAlias: String,
    val iv: String,
    val wrapped: String,
)

@Serializable
data class CirclePairing(
    val circleId: String,
    val identityPublicKey: String,
    val circleCertSha256: String,
    val pairedAt: Long,
)

@Serializable
data class VaultHeaderBody(
    val format: Int = 1,
    val vaultId: String,
    val createdAt: Long,
    val passphrase: PassphraseSlot,
    val device: DeviceSlot? = null,
    val circle: CirclePairing? = null,
)

/** On-disk header: the body is MAC'd with a key derived from the DEK, so any tampering is detected after unlock. */
@Serializable
data class VaultHeaderFile(val body: String, val mac: String)

object HeaderCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun bodyBytes(body: VaultHeaderBody): ByteArray =
        json.encodeToString(VaultHeaderBody.serializer(), body).toByteArray()

    fun seal(body: VaultHeaderBody, dek: ByteArray): VaultHeaderFile {
        val bytes = bodyBytes(body)
        return VaultHeaderFile(Bytes.b64(bytes), Bytes.b64(Hkdf.hmac(macKey(dek), bytes)))
    }

    fun parse(file: VaultHeaderFile): VaultHeaderBody =
        json.decodeFromString(VaultHeaderBody.serializer(), Bytes.unb64(file.body).decodeToString())

    fun verify(file: VaultHeaderFile, dek: ByteArray): Boolean {
        val expected = Hkdf.hmac(macKey(dek), Bytes.unb64(file.body))
        return Bytes.constantTimeEquals(expected, Bytes.unb64(file.mac))
    }

    fun encode(file: VaultHeaderFile): ByteArray = json.encodeToString(VaultHeaderFile.serializer(), file).toByteArray()

    fun decode(bytes: ByteArray): VaultHeaderFile = json.decodeFromString(VaultHeaderFile.serializer(), bytes.decodeToString())

    private fun macKey(dek: ByteArray) = Hkdf.derive(dek, null, "vault-header-mac")
}

/** Key-slot helpers. Every slot wraps the same random 256-bit data-encryption key (DEK). */
object KeySlots {
    private fun aad(vaultId: String, slot: String) = "vault-slot|$slot|$vaultId".toByteArray()

    fun createPassphraseSlot(dek: ByteArray, vaultId: String, passphrase: CharArray, params: Argon2Params): PassphraseSlot {
        val salt = Bytes.random(32)
        val kek = PasswordKdf.argon2id(passphrase, salt, params)
        try {
            val wrapped = Aead.sealWithRandomNonce(kek, dek, aad(vaultId, "passphrase"))
            return PassphraseSlot(Bytes.b64(salt), params.memoryKiB, params.iterations, params.parallelism, Bytes.b64(wrapped))
        } finally {
            Bytes.wipe(kek)
        }
    }

    /** @throws CryptoException for a wrong passphrase. */
    fun openPassphraseSlot(slot: PassphraseSlot, vaultId: String, passphrase: CharArray): ByteArray {
        val kek = PasswordKdf.argon2id(passphrase, Bytes.unb64(slot.salt), slot.params)
        try {
            return Aead.openWithRandomNonce(kek, Bytes.unb64(slot.wrapped), aad(vaultId, "passphrase"))
        } finally {
            Bytes.wipe(kek)
        }
    }

    private fun circleKek(releaseSecret: ByteArray, vaultId: String) =
        Hkdf.derive(releaseSecret, vaultId.toByteArray(), "vault-circle-kek")

    /** Inner layer of the Circle slot. The result is then encrypted again by the device-bound Keystore key. */
    fun circleInnerWrap(dek: ByteArray, releaseSecret: ByteArray, vaultId: String): ByteArray {
        val kek = circleKek(releaseSecret, vaultId)
        try {
            return Aead.sealWithRandomNonce(kek, dek, aad(vaultId, "circle"))
        } finally {
            Bytes.wipe(kek)
        }
    }

    fun circleInnerUnwrap(inner: ByteArray, releaseSecret: ByteArray, vaultId: String): ByteArray {
        val kek = circleKek(releaseSecret, vaultId)
        try {
            return Aead.openWithRandomNonce(kek, inner, aad(vaultId, "circle"))
        } finally {
            Bytes.wipe(kek)
        }
    }
}
