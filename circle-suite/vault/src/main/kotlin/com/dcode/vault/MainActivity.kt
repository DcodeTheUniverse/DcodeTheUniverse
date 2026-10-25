package com.dcode.vault

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.dcode.vault.core.NodeKind
import com.dcode.vault.core.TextTypes
import com.dcode.vault.core.VaultSession
import com.dcode.vault.data.VaultState
import com.dcode.vault.ui.screens.BrowserScreen
import com.dcode.vault.ui.screens.BrowserState
import com.dcode.vault.ui.screens.EditorScreen
import com.dcode.vault.ui.screens.FileScreen
import com.dcode.vault.ui.screens.LockScreen
import com.dcode.vault.ui.screens.PasswordScreen
import com.dcode.vault.ui.screens.SettingsScreen
import com.dcode.vault.ui.screens.SetupScreen
import com.dcode.vault.ui.theme.V
import com.dcode.vault.ui.theme.VaultTheme

sealed interface Route {
    val depth: Int
    data object Browser : Route { override val depth = 0 }
    data class Editor(val id: String) : Route { override val depth = 1 }
    data class FileView(val id: String) : Route { override val depth = 1 }
    data class Password(val id: String?, val parent: String?) : Route { override val depth = 1 }
    data object Settings : Route { override val depth = 1 }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // No screenshots, no recents thumbnail, no overlay tapjacking.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { window.setHideOverlayWindows(true) }
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        setContent {
            val changes by vault.prefs.changes.collectAsState()
            val theme = remember(changes) { vault.prefs.theme }
            VaultTheme(theme) { Root() }
        }
    }

    @Composable
    private fun Root() {
        val state by vault.state.collectAsState()
        var inSetup by rememberSaveable { mutableStateOf(vault.state.value is VaultState.NoVault) }
        if (state is VaultState.NoVault) inSetup = true
        val key = when {
            inSetup -> "setup"
            state is VaultState.Unlocked -> "open"
            state is VaultState.Locked -> "locked"
            else -> "loading"
        }
        Box(Modifier.fillMaxSize().background(V.palette.background)) {
            AnimatedContent(
                key,
                transitionSpec = { (fadeIn(tween(380)) + scaleIn(tween(420), initialScale = 0.92f)) togetherWith (fadeOut(tween(220)) + scaleOut(tween(300), targetScale = 1.06f)) },
                label = "root",
            ) { k ->
                when (k) {
                    "setup" -> SetupScreen(onFinished = { inSetup = false })
                    "locked" -> LockScreen()
                    "open" -> (state as? VaultState.Unlocked)?.session?.let { Unlocked(it) }
                    else -> Unit
                }
            }
        }
    }

    @Composable
    private fun Unlocked(session: VaultSession) {
        val stack = remember(session) { mutableStateListOf<Route>(Route.Browser) }
        val browser = remember(session) { BrowserState() }
        val top = stack.last()
        fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
        fun go(r: Route) { stack.add(r) }

        AnimatedContent(
            top,
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                (slideInHorizontally(tween(320)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(280)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(200)))
            },
            label = "vault-nav",
        ) { route ->
            when (route) {
                Route.Browser -> BrowserScreen(
                    session, browser,
                    onOpen = { n ->
                        when (n.kind) {
                            NodeKind.FOLDER -> { browser.folder = n.id; browser.query = "" }
                            NodeKind.NOTE -> go(Route.Editor(n.id))
                            NodeKind.PASSWORD -> go(Route.Password(n.id, n.parentId))
                            NodeKind.FILE -> go(if (TextTypes.isTextLike(n.name, n.mime)) Route.Editor(n.id) else Route.FileView(n.id))
                        }
                    },
                    onNewPassword = { parent -> go(Route.Password(null, parent)) },
                    onSettings = { go(Route.Settings) },
                )
                is Route.Editor -> EditorScreen(session, route.id, onBack = ::back)
                is Route.FileView -> FileScreen(session, route.id, onBack = ::back, onOpenAsText = { n -> back(); go(Route.Editor(n.id)) })
                is Route.Password -> PasswordScreen(session, route.id, route.parent, onBack = ::back, onSaved = { id -> back(); go(Route.Password(id, route.parent)) })
                Route.Settings -> SettingsScreen(session, onBack = ::back)
            }
        }
    }
}
