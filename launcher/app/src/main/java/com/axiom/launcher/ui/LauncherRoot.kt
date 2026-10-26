package com.axiom.launcher.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.ui.effects.BootSequence
import com.axiom.launcher.ui.effects.MatrixRain
import com.axiom.launcher.ui.effects.scanlines
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Starts speech recognition and delivers the transcript. */
val LocalVoice = compositionLocalOf<((String) -> Unit) -> Unit> { { } }

enum class Overlay { NONE, DRAWER, SEARCH, SETTINGS }

@Composable
fun LauncherRoot(vm: LauncherViewModel, homeTicks: Int) {
    val p = LocalPalette.current
    val pager = rememberPagerState(initialPage = 1) { 3 }
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }

    var voiceCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { voiceCallback?.invoke(it) }
        }
        voiceCallback = null
    }
    val startVoice: ((String) -> Unit) -> Unit = { cb ->
        voiceCallback = cb
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Axiom")
        runCatching { voiceLauncher.launch(i) }.onFailure { vm.say("No speech recognizer installed") }
    }

    LaunchedEffect(homeTicks) {
        if (homeTicks > 0) {
            overlay = Overlay.NONE
            menuApp = null
            vm.query = ""
            pager.animateScrollToPage(1)
        }
    }
    LaunchedEffect(vm.pageRequest) {
        vm.pageRequest?.let {
            overlay = Overlay.NONE
            pager.animateScrollToPage(it)
            vm.pageRequest = null
        }
    }
    LaunchedEffect(vm.toast) {
        if (vm.toast != null) { delay(2200); vm.toast = null }
    }

    BackHandler(enabled = true) { }
    BackHandler(enabled = overlay != Overlay.NONE || pager.currentPage != 1) {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE
        else scope.launch { pager.animateScrollToPage(1) }
    }

    CompositionLocalProvider(LocalVoice provides startVoice) {
        Box(
            Modifier
                .fillMaxSize()
                .background(p.bg)
                .scanlines(Netrunner && vm.effects)
        ) {
            if (Netrunner && vm.effects) MatrixRain(Modifier.fillMaxSize(), intensity = 0.13f, color = p.fg)

            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> FilesPage(vm)
                    1 -> HomePage(
                        vm,
                        openDrawer = { overlay = Overlay.DRAWER },
                        openSearch = { overlay = Overlay.SEARCH },
                        openSettings = { overlay = Overlay.SETTINGS },
                        onAppMenu = { menuApp = it },
                        goTo = { scope.launch { pager.animateScrollToPage(it) } },
                    )
                    else -> ChatPage(vm, active = pager.currentPage == 2)
                }
            }

            AnimatedVisibility(
                overlay == Overlay.DRAWER,
                enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
            ) {
                Drawer(vm, onClose = { overlay = Overlay.NONE }, onAppMenu = { menuApp = it })
            }
            AnimatedVisibility(overlay == Overlay.SEARCH, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                SearchOverlay(vm, onClose = { overlay = Overlay.NONE }, onAppMenu = { menuApp = it })
            }
            AnimatedVisibility(
                overlay == Overlay.SETTINGS,
                enter = slideInVertically(tween(260)) { it / 4 } + fadeIn(),
                exit = slideOutVertically(tween(200)) { it / 4 } + fadeOut(),
            ) {
                SettingsScreen(vm, onClose = { overlay = Overlay.NONE })
            }

            menuApp?.let { AppMenu(vm, it) { menuApp = null } }
            vm.focusBlocked?.let { FocusBlockedDialog(vm, it) }
            vm.terminalOutput?.let { TerminalDialog(it) { vm.terminalOutput = null } }
            if (vm.needsLockSetup) LockSetupDialog(vm)

            vm.toast?.let { msg ->
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        if (Netrunner) "> $msg" else msg,
                        style = lStyle(14.sp, color = p.onAccent),
                        modifier = Modifier.clip(RoundedCornerShape(if (Netrunner) 2.dp else 50.dp)).background(p.accent)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }

            if (vm.matrixMode) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { vm.matrixMode = false }) {
                    MatrixRain(Modifier.fillMaxSize(), 1f)
                    LaunchedEffect(Unit) { delay(7000); vm.matrixMode = false }
                    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("follow the white rabbit.", style = lStyle(18.sp, color = androidx.compose.ui.graphics.Color.White))
                    }
                }
                BackHandler { vm.matrixMode = false }
            }
            if (vm.bootSequence) BootSequence { vm.bootSequence = false }
            if (!vm.onboarded) Onboarding(vm)
        }
    }
}
