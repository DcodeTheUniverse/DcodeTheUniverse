package com.dcode.vault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dcode.vault.data.VaultManager

class VaultApp : Application() {
    lateinit var vault: VaultManager
        private set

    override fun onCreate() {
        super.onCreate()
        vault = VaultManager(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = vault.onBackground()
            override fun onStart(owner: LifecycleOwner) = vault.onForeground()
        })
        // Screen off always locks, regardless of the timeout.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    vault.holdLock = false
                    vault.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

val Context.vault: VaultManager get() = (applicationContext as VaultApp).vault

/** Copies a secret marked sensitive (hidden from clipboard previews) and clears it after a delay. */
object SecureClipboard {
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, label: String, text: String, clearAfterSeconds: Int) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        handler.removeCallbacksAndMessages(null)
        if (clearAfterSeconds > 0) {
            handler.postDelayed({ runCatching { cm.clearPrimaryClip() } }, clearAfterSeconds * 1000L)
        }
    }
}
