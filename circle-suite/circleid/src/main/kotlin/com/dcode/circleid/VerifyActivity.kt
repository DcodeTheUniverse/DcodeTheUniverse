package com.dcode.circleid

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dcode.circle.protocol.CircleCodec
import com.dcode.circle.protocol.CircleContract
import com.dcode.circle.protocol.CircleRequest
import com.dcode.circle.protocol.RequestKind
import com.dcode.circleid.security.CallerIdentity
import com.dcode.circleid.ui.theme.CircleTheme
import com.dcode.circleid.verify.ExternalRequest
import com.dcode.circleid.verify.VerifyScreen
import com.dcode.circleid.verify.VerifyViewModel
import com.dcode.crypto.Bytes
import com.dcode.crypto.Ecies
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Handles VERIFY / PAIR requests from other apps.
 *
 * The caller is identified by the platform (`callingPackage`, only available because the caller used
 * startActivityForResult) plus its APK signing certificate — never by anything inside the request body.
 */
class VerifyActivity : FragmentActivity() {
    private val vm: VerifyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        harden(this)
        enableEdgeToEdge()

        val ext = parse()
        if (ext == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        if (!graph.isEnrolled) {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", "not_enrolled"))
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        vm.begin(ext)

        setContent {
            CircleTheme(graph.prefs.theme) {
                VerifyScreen(vm, onDone = { deliver() })
            }
        }

        lifecycleScope.launch {
            val outcome = vm.outcome.filterNotNull().first()
            // Let the result animation play, then hand the signed assertion back.
            delay(if (outcome.ok) 1400 else 2600)
            deliver()
        }
    }

    private var delivered = false

    private fun deliver() {
        if (delivered) return
        delivered = true
        val outcome = vm.outcome.value
        if (outcome?.response != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(CircleContract.EXTRA_RESPONSE, outcome.response))
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving mid-verification aborts it: an attacker cannot park a half-finished session in the background.
        if (!isChangingConfigurations && !delivered && vm.outcome.value == null) {
            vm.cancel()
            deliver()
        }
    }

    private fun parse(): ExternalRequest? {
        val caller = callingPackage ?: return null
        val bytes = intent.getByteArrayExtra(CircleContract.EXTRA_REQUEST) ?: return null
        val req: CircleRequest = runCatching { CircleCodec.decodeRequest(bytes) }.getOrNull() ?: return null
        val expectedKind = when (intent.action) {
            CircleContract.ACTION_PAIR -> RequestKind.PAIR
            CircleContract.ACTION_VERIFY -> RequestKind.VERIFY
            else -> return null
        }
        if (req.kind != expectedKind || req.version != CircleContract.PROTOCOL_VERSION) return null
        if (req.clientPackage != caller) return null
        if (abs(System.currentTimeMillis() - req.issuedAt) > 5 * 60_000) return null
        if (runCatching { Bytes.unb64(req.nonce).size }.getOrDefault(0) != CircleContract.NONCE_SIZE) return null
        if (runCatching { Ecies.decodePublicKey(Bytes.unb64(req.ephemeralKey)) }.isFailure) return null
        val cert = CallerIdentity.certSha256(this, caller) ?: return null
        return ExternalRequest(req, caller, CallerIdentity.label(this, caller), cert)
    }

    companion object {
        /** Screenshots, screen recording, recents thumbnails and overlay tapjacking are all blocked. */
        fun harden(activity: android.app.Activity) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { activity.window.setHideOverlayWindows(true) }
            activity.window.decorView.filterTouchesWhenObscured = true
        }
    }
}
