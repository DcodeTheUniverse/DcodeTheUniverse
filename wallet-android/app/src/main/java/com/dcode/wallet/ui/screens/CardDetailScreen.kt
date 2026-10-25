package com.dcode.wallet.ui.screens

import android.content.Context
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dcode.wallet.data.ActivityEntry
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardRepository
import com.dcode.wallet.security.Auth
import com.dcode.wallet.ui.components.PaymentCardView
import com.dcode.wallet.ui.components.face
import com.dcode.wallet.ui.findActivity
import com.dcode.wallet.util.SecureClipboard
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val REVEAL_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    card: Card,
    isDefault: Boolean,
    activity: List<ActivityEntry>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var remaining by remember { mutableFloatStateOf(1f) }
    var confirmDelete by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var autofillOn by remember { mutableStateOf(isAutofillEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        autofillOn = isAutofillEnabled(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < REVEAL_MS) {
            remaining = 1f - (System.currentTimeMillis() - start).toFloat() / REVEAL_MS
            delay(200)
        }
        revealed = false
    }

    fun pay() {
        val act = context.findActivity() ?: return
        authError = null
        Auth.authenticate(
            activity = act,
            title = "Pay with ${card.title} •••• ${card.last4}",
            subtitle = "Confirm it's you to show your card details",
            onFailure = { authError = it },
        ) {
            revealed = true
            CardRepository.log(card.id, "Card details unlocked for payment")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PaymentCardView(card.face(isDefault), revealNumber = revealed, modifier = Modifier.padding(top = 4.dp))

            if (card.isExpired()) {
                Text("This card has expired. Edit it to update the expiry date.", color = MaterialTheme.colorScheme.error)
            }

            if (!revealed) {
                Button(onClick = ::pay, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Pay with this card", style = MaterialTheme.typography.titleMedium)
                }
                authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else {
                RevealedDetails(card, remaining, onHide = { revealed = false })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Icons.Outlined.Edit, "Edit", Modifier.weight(1f), onEdit)
                ActionButton(
                    if (isDefault) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    if (isDefault) "Default" else "Make default",
                    Modifier.weight(1f),
                ) { if (!isDefault) CardRepository.setDefault(card.id) }
                ActionButton(Icons.Outlined.Delete, "Remove", Modifier.weight(1f)) { confirmDelete = true }
            }

            if (!autofillOn) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pay faster at checkout", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Turn on Dcode Wallet autofill and your cards appear on payment forms in apps and browsers. " +
                                "Details are filled only after your fingerprint or PIN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        TextButton(onClick = onOpenAutofillSettings, modifier = Modifier.align(Alignment.End)) {
                            Text("Turn on autofill")
                        }
                    }
                }
            }

            DetailsSection(card)
            ActivitySection(activity)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this card?") },
            text = { Text("${card.title} •••• ${card.last4} will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    CardRepository.delete(card.id)
                    onDeleted()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RevealedDetails(card: Card, remaining: Float, onHide: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use these details at checkout", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onHide) { Icon(Icons.Outlined.VisibilityOff, "Hide details") }
            }
            CopyRow("Card number", card.formattedNumber) { SecureClipboard.copy(context, "Card number", card.number) }
            CopyRow("Name on card", card.holder) { SecureClipboard.copy(context, "Name on card", card.holder) }
            Row {
                CopyRow("Expiry", card.expiry, Modifier.weight(1f)) { SecureClipboard.copy(context, "Expiry", card.expiry) }
                CopyRow("CVV", card.cvv, Modifier.weight(1f)) { SecureClipboard.copy(context, "CVV", card.cvv) }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { remaining }, modifier = Modifier.fillMaxWidth())
            Text(
                "Hidden automatically in ${(remaining * REVEAL_MS / 1000).toInt()}s. Copied values are wiped from the clipboard after 60s.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, modifier: Modifier = Modifier, onCopy: () -> Unit) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun DetailsSection(card: Card) {
    Column {
        Text("Card info", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        InfoRow("Network", card.brand.display)
        InfoRow("Type", if (card.isVirtual) "Virtual card" else "Physical card")
        InfoRow("Number", "•••• ${card.last4}")
        InfoRow("Added", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(card.createdAt)))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
    HorizontalDivider()
}

@Composable
private fun ActivitySection(activity: List<ActivityEntry>) {
    Column {
        Text("Recent activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (activity.isEmpty()) {
            Text("No activity yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        activity.take(20).forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                    Text(fmt.format(Date(entry.timestamp)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// The system call can time out and throw on some devices; treat that as "not enabled".
fun isAutofillEnabled(context: Context): Boolean = runCatching {
    context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
}.getOrDefault(false)
