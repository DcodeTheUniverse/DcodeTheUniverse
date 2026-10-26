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
    }

    override fun onStop() {
        PerfMonitor.detach(window)
        super.onStop()
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
                return true
            }
        }
        return false
    }

    companion object {
        const val ACTION_OPEN_SOLIS = "com.axiom.launcher.OPEN_SOLIS"
    }
}
