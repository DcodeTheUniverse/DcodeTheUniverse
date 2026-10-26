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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.Overlay
import com.axiom.launcher.PAGE_FILES
import com.axiom.launcher.PAGE_HOME
import com.axiom.launcher.PAGE_SOLIS
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.ui.effects.BootSequence
import com.axiom.launcher.ui.effects.MatrixRain
import com.axiom.launcher.ui.effects.motionAllowed
import com.axiom.launcher.ui.effects.scanlines
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Starts speech recognition and delivers the transcript. */
val LocalVoice = compositionLocalOf<((String) -> Unit) -> Unit> { { } }

@Composable
fun LauncherRoot(vm: LauncherViewModel, homeTicks: Int) {
    val p = LocalPalette.current
    val pager = rememberPagerState(initialPage = PAGE_HOME) { 3 }
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    val motion = motionAllowed()

    var voiceCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.listening = false
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { voiceCallback?.invoke(it) }
        }
        voiceCallback = null
    }
    val startVoice: ((String) -> Unit) -> Unit = { cb ->
        voiceCallback = cb
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Solis")
        runCatching { voiceLauncher.launch(i); vm.listening = true }.onFailure { vm.say("No speech recognizer installed") }
    }

    LaunchedEffect(homeTicks) {
        if (homeTicks > 0) {
            overlay = Overlay.NONE
            menuApp = null
            vm.query = ""
            pager.animateScrollToPage(PAGE_HOME)
        }
    }
    LaunchedEffect(vm.pageRequest) {
        vm.pageRequest?.let {
            overlay = Overlay.NONE
            if (motion) pager.animateScrollToPage(it) else pager.scrollToPage(it)
            vm.pageRequest = null
        }
    }
    LaunchedEffect(vm.voiceRequest) {
        if (vm.voiceRequest > 0) {
            delay(300)
            startVoice { vm.sendSolis(it) }
        }
    }
    LaunchedEffect(vm.overlayRequest) {
        vm.overlayRequest?.let { overlay = it; vm.overlayRequest = null }
    }
    LaunchedEffect(vm.toast) {
        if (vm.toast != null) { delay(2600); vm.toast = null }
    }

    BackHandler(enabled = true) { }
    BackHandler(enabled = overlay != Overlay.NONE || pager.currentPage != PAGE_HOME) {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE
        else scope.launch { pager.animateScrollToPage(PAGE_HOME) }
    }

    val effects = Netrunner && vm.effects
    CompositionLocalProvider(LocalVoice provides startVoice) {
        Box(Modifier.fillMaxSize().background(p.bg).scanlines(effects)) {
            if (effects) MatrixRain(Modifier.fillMaxSize(), intensity = 0.13f, color = p.fg)

            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    PAGE_FILES -> FilesPage(vm)
                    PAGE_HOME -> HomePage(
                        vm,
                        openDrawer = { overlay = Overlay.DRAWER },
                        openSearch = { overlay = Overlay.SEARCH },
                        openSettings = { overlay = Overlay.SETTINGS },
                        openTasks = { overlay = Overlay.TASKS },
                        onAppMenu = { menuApp = it },
                        goTo = { scope.launch { pager.animateScrollToPage(it) } },
                    )
                    else -> SolisPage(
                        vm, active = pager.currentPage == PAGE_SOLIS,
                        openTasks = { overlay = Overlay.TASKS },
                        openMemory = { overlay = Overlay.MEMORY },
                    )
                }
            }

            AnimatedVisibility(
                overlay == Overlay.DRAWER,
                enter = slideInVertically(tween(if (motion) 260 else 0)) { it / 3 } + fadeIn(tween(if (motion) 200 else 0)),
                exit = slideOutVertically(tween(if (motion) 220 else 0)) { it / 3 } + fadeOut(tween(if (motion) 180 else 0)),
            ) {
                Drawer(vm, onClose = { overlay = Overlay.NONE }, onAppMenu = { menuApp = it })
            }
            AnimatedVisibility(overlay == Overlay.SEARCH, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                SearchOverlay(vm, onClose = { overlay = Overlay.NONE }, onAppMenu = { menuApp = it })
            }
            AnimatedVisibility(
                overlay == Overlay.SETTINGS,
                enter = slideInVertically(tween(if (motion) 260 else 0)) { it / 4 } + fadeIn(),
                exit = slideOutVertically(tween(if (motion) 200 else 0)) { it / 4 } + fadeOut(),
            ) {
                SettingsScreen(vm, onClose = { overlay = Overlay.NONE; vm.settingsSection = null })
            }
            AnimatedVisibility(overlay == Overlay.TASKS, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                TasksScreen(vm, onClose = { overlay = Overlay.NONE }) { id ->
                    vm.selectedThread = id
                    overlay = Overlay.NONE
                    scope.launch { pager.animateScrollToPage(PAGE_SOLIS) }
                }
            }
            AnimatedVisibility(overlay == Overlay.MEMORY, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                MemoryScreen(vm, onClose = { overlay = Overlay.NONE })
            }

            menuApp?.let { AppMenu(vm, it) { menuApp = null } }
            vm.focusBlocked?.let { FocusBlockedDialog(vm, it) }
            vm.pausedApp?.let { PausedAppDialog(vm, it) }
            vm.terminalOutput?.let { TerminalDialog(it) { vm.terminalOutput = null } }
            if (vm.modeSheet) ModeSheet(vm) { vm.modeSheet = false }
            if (vm.needsLockSetup) LockSetupDialog(vm)

            vm.toast?.let { msg ->
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        if (Netrunner) "> $msg" else msg,
                        style = lStyle(14.sp, color = p.onAccent),
                        modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(if (Netrunner) 2.dp else 50.dp)).background(p.accent)
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
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
