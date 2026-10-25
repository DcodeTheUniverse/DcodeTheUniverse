package com.dcode.wallet.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dcode.wallet.BuildConfigInfo
import com.dcode.wallet.data.CardRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAutofillSettings: () -> Unit) {
    val context = LocalContext.current
    var autofillOn by remember { mutableStateOf(isAutofillEnabled(context)) }
    var confirmWipe by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        autofillOn = isAutofillEnabled(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Header("Payments")
            Item(Icons.Outlined.EditNote, "Checkout autofill",
                if (autofillOn) "On — saved cards appear on payment forms in apps and browsers"
                else "Off — turn on to fill card details into checkout forms")
            if (!autofillOn) {
                Button(onClick = onOpenAutofillSettings, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)) {
                    Text("Turn on autofill")
                }
            } else {
                OutlinedButton(onClick = onOpenAutofillSettings, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)) {
                    Text("Change autofill service")
                }
            }
            Text(
                "In Chrome, also open Settings › Autofill services and choose \"Autofill using another service\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp),
            )
            Item(Icons.Outlined.Contactless, "Tap to pay in stores",
                "Not available. Contactless payments need card-network tokenisation (Visa/Mastercard) that only " +
                    "certified wallets such as Google Wallet and banking apps can access. Use this wallet for online payments.")

            Header("Security")
            Item(Icons.Outlined.Lock, "Hardware-backed encryption", "Cards are encrypted with AES-256-GCM using a key stored in the Android Keystore")
            Item(Icons.Outlined.CheckCircle, "Verify to pay", "Fingerprint, face or screen lock is required to unlock the app and every payment")
            Item(Icons.Outlined.Timer, "Auto-lock", "Locks after ${com.dcode.wallet.security.Session.AUTO_LOCK_MS / 1000}s in the background")
            Item(Icons.Outlined.Screenshot, "Screen protection", "Screenshots and screen recording are blocked; no cloud backup of card data")

            Header("Data")
            OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove all cards", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text("Dcode Wallet ${BuildConfigInfo.versionName(context)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Remove all cards?") },
            text = { Text("Every card and its activity will be permanently deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; CardRepository.clearAll() }) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
