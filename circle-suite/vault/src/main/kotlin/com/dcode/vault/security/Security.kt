package com.dcode.vault.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.protocol.AssertionStatus
import com.dcode.circle.protocol.AssertionVerifier
import com.dcode.circle.protocol.CircleAssertion
import com.dcode.circle.protocol.CircleCodec
import com.dcode.circle.protocol.CircleContract
import com.dcode.circle.protocol.CircleRequest
import com.dcode.circle.protocol.RequestKind
import com.dcode.crypto.Bytes
import com.dcode.crypto.Ecies
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Device-bound key-encryption keys in Android Keystore (StrongBox when present).
 * - CIRCLE alias: no user-auth flag (the biometric gate is Circle ID's release secret), but usable only
 *   while the device is unlocked and never exportable — so a copied vault is useless off this phone.
 * - BIOMETRIC alias: every use needs a Class 3 biometric; invalidated when fingerprints change.
 */
class VaultKeystore(private val context: Context) {
    private val ks: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun exists(alias: String) = runCatching { ks.containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) {
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun create(alias: String, biometric: Boolean): SecretKey {
        delete(alias)
        fun gen(strongBox: Boolean): SecretKey {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
            if (biometric) {
                b.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
            }
            if (strongBox) b.setIsStrongBoxBacked(true)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(b.build())
                generateKey()
            }
        }
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return if (strongBox) {
            try { gen(true) } catch (_: StrongBoxUnavailableException) { gen(false) } catch (_: java.security.ProviderException) { gen(false) }
        } else {
            gen(false)
        }
    }

    private fun key(alias: String): SecretKey = ks.getKey(alias, null) as? SecretKey ?: throw IllegalStateException("Device key missing")

    /** Encrypts with the device-bound (non-biometric) key. Returns iv + ciphertext. */
    fun encryptDevice(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance(AES).apply { init(Cipher.ENCRYPT_MODE, create(CIRCLE_ALIAS, biometric = false)) }
        return c.iv to c.doFinal(plain)
    }

    fun decryptDevice(iv: ByteArray, ct: ByteArray): ByteArray =
        Cipher.getInstance(AES).apply { init(Cipher.DECRYPT_MODE, key(CIRCLE_ALIAS), GCMParameterSpec(128, iv)) }.doFinal(ct)

    fun biometricEncryptCipher(): Cipher = Cipher.getInstance(AES).apply { init(Cipher.ENCRYPT_MODE, create(BIO_ALIAS, biometric = true)) }

    fun biometricDecryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(AES).apply { init(Cipher.DECRYPT_MODE, key(BIO_ALIAS), GCMParameterSpec(128, iv)) }

    fun wipe() {
        delete(CIRCLE_ALIAS)
        delete(BIO_ALIAS)
    }

    companion object {
        const val CIRCLE_ALIAS = "vault.device.kek.v1"
        const val BIO_ALIAS = "vault.bio.kek.v1"
        private const val AES = "AES/GCM/NoPadding"
    }
}

/** Client side of the Circle ID protocol. */
class CircleClient(private val context: Context) {
    class Pending(val request: CircleRequest, val ephemeral: KeyPair)

    sealed class Result {
        class Released(val assertion: CircleAssertion, val secret: ByteArray, val circleCert: String) : Result()
        class Denied(val message: String) : Result()
    }

    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(CircleContract.PACKAGE, 0); true }.getOrDefault(false)

    fun circleCertSha256(): String? = try {
        val info = context.packageManager.getPackageInfo(CircleContract.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.map { Bytes.hex(Bytes.sha256(it.toByteArray())) }?.sorted()?.joinToString(":")
    } catch (_: Exception) {
        null
    }

    fun newRequest(kind: RequestKind, reason: String): Pending {
        val eph = Ecies.generateKeyPair()
        val req = CircleRequest(
            kind = kind,
            clientPackage = context.packageName,
            clientName = "Vault",
            nonce = Bytes.b64(Bytes.random(CircleContract.NONCE_SIZE)),
            ephemeralKey = Bytes.b64(eph.public.encoded),
            reason = reason,
            issuedAt = System.currentTimeMillis(),
            minAssurance = 2,
        )
        return Pending(req, eph)
    }

    fun intent(p: Pending): Intent = Intent(if (p.request.kind == RequestKind.PAIR) CircleContract.ACTION_PAIR else CircleContract.ACTION_VERIFY)
        .setComponent(ComponentName(CircleContract.PACKAGE, "${CircleContract.PACKAGE}.VerifyActivity"))
        .putExtra(CircleContract.EXTRA_REQUEST, CircleCodec.encodeRequest(p.request))

    /**
     * Verifies Circle ID's answer: the installed Circle ID must carry the pinned signing certificate, the
     * envelope must be signed by the pinned identity key (or, while pairing, by the key it presents), and the
     * assertion must be fresh, bound to our nonce and package, and VERIFIED at assurance ≥ 2.
     */
    fun complete(p: Pending, response: ByteArray?, pinnedKey: ByteArray?, pinnedCert: String?): Result {
        if (response == null) return Result.Denied("Circle ID did not return a verification")
        val cert = circleCertSha256() ?: return Result.Denied("Circle ID is not installed")
        if (pinnedCert != null && pinnedCert != cert) return Result.Denied("Circle ID's signing certificate changed — refusing to trust it")
        val assertion = when (val o = AssertionVerifier.verify(response, p.request, pinnedKey, System.currentTimeMillis())) {
            is AssertionVerifier.Outcome.Invalid -> return Result.Denied(o.reason)
            is AssertionVerifier.Outcome.Valid -> o.assertion
        }
        if (assertion.status != AssertionStatus.VERIFIED) {
            return Result.Denied(
                when (assertion.status) {
                    AssertionStatus.NOT_PAIRED -> "Vault isn't paired with this Circle ID. Unlock with your passphrase and pair again."
                    AssertionStatus.CANCELLED -> "Cancelled"
                    else -> assertion.message ?: "Circle ID could not verify you"
                },
            )
        }
        if (assertion.assurance < p.request.minAssurance) return Result.Denied("Assurance too low (AL${assertion.assurance})")
        val sealed = assertion.sealedSecret ?: return Result.Denied("No key was released")
        val secret = try {
            Ecies.open(p.ephemeral, Bytes.unb64(sealed), CircleContract.releaseContext(p.request.clientPackage, p.request.nonce))
        } catch (e: Exception) {
            return Result.Denied("Released key could not be opened")
        }
        return Result.Released(assertion, secret, cert)
    }
}

sealed class BioResult {
    data class Success(val crypto: BiometricPrompt.CryptoObject?) : BioResult()
    data class Error(val message: String) : BioResult()
    data object Cancelled : BioResult()
}

object Biometrics {
    fun available(context: Context) = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String, crypto: BiometricPrompt.CryptoObject?): BioResult =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BioResult.Success(result.cryptoObject))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    cont.resume(if (cancelled) BioResult.Cancelled else BioResult.Error(errString.toString()))
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle(subtitle).setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BIOMETRIC_STRONG).setConfirmationRequired(false).build()
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
