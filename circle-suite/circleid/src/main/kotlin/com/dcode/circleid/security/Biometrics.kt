package com.dcode.circleid.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

sealed class BioResult {
    data class Success(val crypto: BiometricPrompt.CryptoObject?) : BioResult()
    data class Error(val code: Int, val message: String) : BioResult()
    data object Cancelled : BioResult()
}

object Biometrics {
    fun strongStatus(context: Context): Int = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)

    fun strongAvailable(context: Context) = strongStatus(context) == BiometricManager.BIOMETRIC_SUCCESS

    fun describe(status: Int): String = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Ready"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No fingerprint enrolled on this device"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No Class 3 biometric hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
        else -> "Unavailable ($status)"
    }

    /** Shows the system biometric prompt. With a [crypto] object the match also unlocks that Keystore operation. */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        crypto: BiometricPrompt.CryptoObject?,
        negative: String = "Cancel",
    ): BioResult = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BioResult.Success(result.cryptoObject))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    cont.resume(if (cancelled) BioResult.Cancelled else BioResult.Error(errorCode, errString.toString()))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}

/** Environment checks reported as the "device integrity" factor. */
object DeviceIntegrity {
    data class Check(val label: String, val ok: Boolean, val critical: Boolean)

    fun run(context: Context, keystore: CircleKeystore): List<Check> {
        val km = context.getSystemService(KeyguardManager::class.java)
        val loc = keystore.location(CircleKeystore.IDENTITY)
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk", "/data/local/xbin/su")
        return listOf(
            Check("Screen lock is set", km?.isDeviceSecure == true, true),
            Check("Keys in secure hardware (${loc.label})", loc == KeyLocation.StrongBox || loc == KeyLocation.Tee, true),
            Check("No debugger attached", !Debug.isDebuggerConnected(), true),
            Check("Release-signed OS build", Build.TAGS?.contains("test-keys") != true, false),
            Check("No root binaries found", suPaths.none { File(it).exists() }, false),
            Check("Not an emulator", !(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")), false),
        )
    }
}
