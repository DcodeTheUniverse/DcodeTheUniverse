package com.dcode.circleid.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.dcode.crypto.Bytes
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class KeyLocation(val label: String) { StrongBox("StrongBox secure element"), Tee("Trusted Execution Environment"), Software("Software"), Unknown("Unknown") }

/**
 * All Circle ID key material lives in Android Keystore and never leaves secure hardware:
 * - profile KEK: AES-256-GCM, released only after a Class 3 biometric match, per operation, invalidated if a new
 *   fingerprint is enrolled. Protects the face template and every paired app's release secret.
 * - identity key: ECDSA P-256 with hardware key attestation; signs every assertion Circle ID issues.
 * - log key: AES-256-GCM, device-unlock bound; protects the verification history.
 */
class CircleKeystore(private val context: Context) {
    private val ks: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    val hasStrongBox: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun exists(alias: String) = runCatching { ks.containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) {
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun aesKey(alias: String, build: (KeyGenParameterSpec.Builder) -> Unit): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        fun gen(strongBox: Boolean): SecretKey {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            build(b)
            if (strongBox) b.setIsStrongBoxBacked(true)
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(b.build())
            return kg.generateKey()
        }
        return if (hasStrongBox) {
            try {
                gen(true)
            } catch (_: StrongBoxUnavailableException) {
                gen(false)
            } catch (_: java.security.ProviderException) {
                gen(false)
            }
        } else {
            gen(false)
        }
    }

    fun profileKek(): SecretKey = aesKey(PROFILE_KEK) { b ->
        b.setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
    }

    fun logKey(): SecretKey = aesKey(LOG_KEY) { b -> b.setUnlockedDeviceRequired(true) }

    /** Cipher for sealing a fresh profile key; must be authorised through BiometricPrompt before use. */
    fun profileEncryptCipher(): Cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, profileKek()) }

    fun profileDecryptCipher(iv: ByteArray): Cipher {
        val key = ks.getKey(PROFILE_KEK, null) as? SecretKey ?: throw IllegalStateException("Circle ID is not enrolled")
        return Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }

    fun logEncrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, logKey()) }
        return Bytes.concat(c.iv, c.doFinal(plain))
    }

    fun logDecrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, logKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Creates (once) the attested identity signing key. */
    fun ensureIdentityKey() {
        if (ks.containsAlias(IDENTITY)) return
        fun gen(strongBox: Boolean) {
            val b = KeyGenParameterSpec.Builder(IDENTITY, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUnlockedDeviceRequired(true)
                .setAttestationChallenge(Bytes.random(32))
            if (strongBox) b.setIsStrongBoxBacked(true)
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(b.build())
            kpg.generateKeyPair()
        }
        if (hasStrongBox) {
            try {
                gen(true)
            } catch (_: StrongBoxUnavailableException) {
                gen(false)
            } catch (_: java.security.ProviderException) {
                gen(false)
            }
        } else {
            gen(false)
        }
    }

    fun identityPublicKey(): ByteArray = ks.getCertificate(IDENTITY).publicKey.encoded

    fun attestationChain(): List<ByteArray> = ks.getCertificateChain(IDENTITY)?.map { it.encoded }.orEmpty()

    fun sign(data: ByteArray): ByteArray {
        val key = ks.getKey(IDENTITY, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    fun location(alias: String): KeyLocation = try {
        val info: KeyInfo = when (val key = ks.getKey(alias, null)) {
            is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            is PrivateKey -> java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            else -> return KeyLocation.Unknown
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLocation.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLocation.Tee
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyLocation.Software
                else -> KeyLocation.Unknown
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyLocation.Tee else KeyLocation.Software
        }
    } catch (_: Exception) {
        KeyLocation.Unknown
    }

    fun wipeAll() {
        listOf(PROFILE_KEK, IDENTITY, LOG_KEY).forEach(::delete)
    }

    companion object {
        const val PROFILE_KEK = "circle.profile.kek.v1"
        const val IDENTITY = "circle.identity.v1"
        const val LOG_KEY = "circle.log.v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
    }
}
