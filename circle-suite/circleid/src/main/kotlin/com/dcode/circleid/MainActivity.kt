package com.dcode.circleid

import android.os.Bundle
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dcode.circleid.enroll.EnrollScreen
import com.dcode.circleid.ui.screens.AppsScreen
import com.dcode.circleid.ui.screens.HistoryScreen
import com.dcode.circleid.ui.screens.HomeScreen
import com.dcode.circleid.ui.screens.LabScreen
import com.dcode.circleid.ui.screens.SettingsScreen
import com.dcode.circleid.ui.screens.WelcomeScreen
import com.dcode.circleid.ui.theme.Circle
import com.dcode.circleid.ui.theme.CircleTheme
import com.dcode.circleid.verify.VerifyScreen
import com.dcode.circleid.verify.VerifyViewModel

enum class Screen(val depth: Int) { Welcome(0), Enroll(1), Home(0), Verify(1), Lab(1), History(1), Settings(1), Apps(1) }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        VerifyActivity.harden(this)
        enableEdgeToEdge()
        setContent {
            val changes by graph.prefs.changes.collectAsState()
            val theme = remember(changes) { graph.prefs.theme }
            CircleTheme(theme) { App() }
        }
    }

    @Composable
    private fun App() {
        val stack = remember { mutableStateListOf(if (graph.isEnrolled) Screen.Home else Screen.Welcome) }
        val top = stack.last()
        var verifyRun by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        fun go(s: Screen) {
            if (s == Screen.Verify) verifyRun++
            stack.add(s)
        }
        fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
        fun reset(s: Screen) { stack.clear(); stack.add(s) }
        BackHandler(stack.size > 1) { back() }

        Box(Modifier.fillMaxSize().background(Circle.palette.background)) {
            AnimatedContent(
                targetState = top,
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    if (targetState == Screen.Verify || initialState == Screen.Verify) {
                        (fadeIn(tween(300)) + scaleIn(tween(360), initialScale = 0.94f)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(260), targetScale = 1.04f))
                    } else {
                        (slideInHorizontally(tween(320)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(280)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(200)))
                    }
                },
                label = "nav",
            ) { screen ->
                when (screen) {
                    Screen.Welcome -> WelcomeScreen(onStart = { go(Screen.Enroll) })
                    Screen.Enroll -> EnrollScreen(onFinished = { reset(Screen.Home) })
                    Screen.Home -> HomeScreen(
                        onVerify = { go(Screen.Verify) },
                        onLab = { go(Screen.Lab) },
                        onHistory = { go(Screen.History) },
                        onSettings = { go(Screen.Settings) },
                        onApps = { go(Screen.Apps) },
                    )
                    Screen.Verify -> {
                        // A fresh ViewModel per verification run.
                        val vm: VerifyViewModel = viewModel(key = "verify-$verifyRun")
                        androidx.compose.runtime.LaunchedEffect(vm) { vm.begin(null) }
                        VerifyScreen(vm, onDone = { back() })
                    }
                    Screen.Lab -> LabScreen(onBack = { back() })
                    Screen.History -> HistoryScreen(onBack = { back() })
                    Screen.Settings -> SettingsScreen(onBack = { back() }, onReset = { reset(Screen.Welcome) })
                    Screen.Apps -> AppsScreen(onBack = { back() })
                }
            }
        }
    }
}
