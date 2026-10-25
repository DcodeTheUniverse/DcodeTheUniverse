package com.dcode.wallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dcode.wallet.data.CardRepository
import com.dcode.wallet.security.Auth
import com.dcode.wallet.security.Session
import com.dcode.wallet.ui.screens.AddEditCardScreen
import com.dcode.wallet.ui.screens.CardDetailScreen
import com.dcode.wallet.ui.screens.HomeScreen
import com.dcode.wallet.ui.screens.LockScreen
import com.dcode.wallet.ui.screens.SettingsScreen
import com.dcode.wallet.ui.theme.WalletTheme
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep card details out of screenshots, screen recordings and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { WalletTheme { WalletRoot(this) } }
    }

    override fun onStart() {
        super.onStart()
        Session.onForeground()
    }

    override fun onStop() {
        super.onStop()
        Session.onBackground()
    }
}

/** Routes: home, add, settings, detail:<id>, edit:<id>. */
@Composable
private fun WalletRoot(activity: FragmentActivity) {
    if (!Session.unlocked) {
        LockGate(activity)
        return
    }
    val state by CardRepository.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    val openAutofill = { openAutofillSettings(activity) }

    BackHandler(enabled = route != "home") {
        route = if (route.startsWith("edit:")) "detail:" + route.removePrefix("edit:") else "home"
    }

    when {
        route == "add" -> AddEditCardScreen(existing = null, onBack = { route = "home" }, onSaved = { route = "detail:$it" })
        route == "settings" -> SettingsScreen(onBack = { route = "home" }, onOpenAutofillSettings = openAutofill)
        route.startsWith("edit:") -> {
            val id = route.removePrefix("edit:")
            val card = state.card(id)
            if (card == null) LaunchedEffect(id) { route = "home" }
            else AddEditCardScreen(existing = card, onBack = { route = "detail:$id" }, onSaved = { route = "detail:$it" })
        }
        route.startsWith("detail:") -> {
            val id = route.removePrefix("detail:")
            val card = state.card(id)
            if (card == null) LaunchedEffect(id) { route = "home" }
            else CardDetailScreen(
                card = card,
                isDefault = state.defaultCard?.id == id,
                activity = state.activityFor(id),
                onBack = { route = "home" },
                onEdit = { route = "edit:$id" },
                onDeleted = { route = "home" },
                onOpenAutofillSettings = openAutofill,
            )
        }
        else -> HomeScreen(
            state = state,
            onCardClick = { route = "detail:$it" },
            onAddCard = { route = "add" },
            onSettings = { route = "settings" },
            onLock = { Session.lock() },
        )
    }
}

@Composable
private fun LockGate(activity: FragmentActivity) {
    var canAuth by remember { mutableStateOf(Auth.isAvailable(activity)) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoPrompted by remember { mutableStateOf(false) }
    val unlock = {
        error = null
        Auth.authenticate(activity, "Unlock Dcode Wallet", onFailure = { error = it }) { Session.unlock() }
    }
    LifecycleResumeEffect(Unit) {
        canAuth = Auth.isAvailable(activity)
        onPauseOrDispose { }
    }
    LaunchedEffect(canAuth) {
        if (canAuth && !autoPrompted) {
            autoPrompted = true
            delay(300)
            unlock()
        }
    }
    LockScreen(
        canAuthenticate = canAuth,
        error = error,
        onUnlock = unlock,
        onOpenSecuritySettings = { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
    )
}

private fun openAutofillSettings(activity: FragmentActivity) {
    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${activity.packageName}"))
    runCatching { activity.startActivity(intent) }.onFailure {
        runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
