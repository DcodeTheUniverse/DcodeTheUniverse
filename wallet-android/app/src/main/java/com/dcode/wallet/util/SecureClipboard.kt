package com.dcode.wallet.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast

/** Copies card data marked as sensitive and wipes the clipboard after a minute. */
object SecureClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, label: String, value: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            putBoolean(key, true)
        }
        cm.setPrimaryClip(clip)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ clear(cm) }, CLEAR_AFTER_MS)
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun clear(cm: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
