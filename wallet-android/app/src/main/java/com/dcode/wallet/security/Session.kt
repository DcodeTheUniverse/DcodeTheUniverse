package com.dcode.wallet.security

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** In-memory unlock state. Lives only as long as the process, so a killed app always starts locked. */
object Session {
    const val AUTO_LOCK_MS = 60_000L

    var unlocked by mutableStateOf(false)
        private set
    private var backgroundedAt = 0L

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > AUTO_LOCK_MS) lock()
        backgroundedAt = 0L
    }
}
