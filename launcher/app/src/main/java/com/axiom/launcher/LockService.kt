package com.axiom.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Optional accessibility service used only to lock the screen on double-tap. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile private var instance: LockService? = null
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        fun enabled(): Boolean = instance != null
    }
}
