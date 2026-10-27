package com.axiom.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.toArgb
import com.axiom.launcher.platform.PerfMonitor
import com.axiom.launcher.platform.REQUEST_WIDGET_CONFIGURE
import com.axiom.launcher.ui.LauncherRoot
import com.axiom.launcher.ui.effects.LocalReducedMotion
import com.axiom.launcher.ui.theme.LauncherTheme
import com.axiom.launcher.ui.theme.paletteFor

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    /** Bumped whenever the user presses Home while already on the launcher. */
    private val homeTicks = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PerfMonitor.watchFirstFrame(this)
        handleIntent(intent)
        setContent {
            val mode = vm.theme
            LaunchedEffect(mode) {
                val p = paletteFor(mode)
                val style = if (p.isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                window.decorView.setBackgroundColor(p.bg.toArgb())
            }
            LauncherTheme(mode) {
                CompositionLocalProvider(LocalReducedMotion provides vm.reducedMotion) {
                    LauncherRoot(vm, homeTicks.intValue)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleIntent(intent) && intent.hasCategory(Intent.CATEGORY_HOME)) homeTicks.intValue++
    }

    override fun onStart() {
        super.onStart()
        PerfMonitor.attach(window)
        vm.core.widgets.startListening()
        vm.onForegrounded()
    }

    override fun onStop() {
        vm.onBackgrounded()
        vm.core.widgets.stopListening()
        PerfMonitor.detach(window)
        super.onStop()
    }

    /** A widget's own setup screen is started through the system, so its result comes back here. */
    @Deprecated("Widget setup is started with an IntentSender, which has no ActivityResult contract here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_WIDGET_CONFIGURE) { vm.onWidgetConfigured(resultCode == RESULT_OK); return }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        vm.core.modes.tick()
        vm.refreshSuggestions()
        vm.refreshGlance()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            Intent.ACTION_ASSIST, ACTION_OPEN_SOLIS -> {
                vm.pageRequest = PAGE_SOLIS
                if (intent.getBooleanExtra(EXTRA_VOICE, false)) vm.voiceRequest++
                vm.receiveShared(intent.getStringExtra(EXTRA_SHARED_TEXT), sharedFile(intent.getStringExtra(EXTRA_SHARED_FILE)))
                vm.receiveScreen(sharedFile(intent.getStringExtra(EXTRA_SCREEN_IMAGE)), sharedFile(intent.getStringExtra(EXTRA_SCREEN_TEXT)))
                return true
            }
            ACTION_OPEN_TASKS -> {
                vm.overlayRequest = Overlay.TASKS
                return true
            }
        }
        return false
    }

    /**
     * This activity is exported, so any app can send it these extras. Only files that ShareActivity copied
     * into the share cache are accepted; anything else (for example a path to Axiom's private data) is ignored.
     */
    private fun sharedFile(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val dir = java.io.File(cacheDir, "shared").canonicalFile
        val f = runCatching { java.io.File(path).canonicalFile }.getOrNull() ?: return null
        return f.path.takeIf { f.parentFile == dir && f.isFile }
    }

    companion object {
        const val ACTION_OPEN_SOLIS = "com.axiom.launcher.OPEN_SOLIS"
        const val ACTION_OPEN_TASKS = "com.axiom.launcher.OPEN_TASKS"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_SHARED_FILE = "shared_file"
        /** "Ask about this screen": the screenshot and the screen's text, written by the assistant session. */
        const val EXTRA_SCREEN_IMAGE = "screen_image"
        const val EXTRA_SCREEN_TEXT = "screen_text"
    }
}
