package com.dcode.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.WalletState
import com.dcode.wallet.ui.components.PaymentCardView
import com.dcode.wallet.ui.components.face

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: WalletState,
    onCardClick: (String) -> Unit,
    onAddCard: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onLock) { Icon(Icons.Outlined.Lock, "Lock wallet") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCard,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add to Wallet") },
            )
        },
    ) { padding ->
        val default = state.defaultCard
        if (default == null) {
            EmptyWallet(Modifier.padding(padding), onAddCard)
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                SectionLabel("Ready to pay")
                PaymentCardView(default.face(isDefault = true), onClick = { onCardClick(default.id) })
                Text(
                    "Tap a card to pay online or fill checkout forms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                val others = state.cards.filter { it.id != default.id }
                if (others.isNotEmpty()) {
                    SectionLabel("All cards · ${state.cards.size}")
                    CardStack(others, onCardClick)
                }
                Spacer(Modifier.height(112.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
    )
}

/** Overlapping stack like a physical wallet; each card peeks out above the next. */
@Composable
private fun CardStack(cards: List<Card>, onCardClick: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardHeight = maxWidth / 1.586f
        val peek = 68.dp
        Box(Modifier.fillMaxWidth().height(cardHeight + peek * (cards.size - 1))) {
            cards.forEachIndexed { i, card ->
                PaymentCardView(card.face(), Modifier.offset(y = peek * i), onClick = { onCardClick(card.id) })
            }
        }
    }
}

@Composable
private fun EmptyWallet(modifier: Modifier, onAddCard: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CreditCard, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text("Add your first card", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Store your bank and virtual cards securely, then use them to pay online with a fingerprint.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddCard) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.size(8.dp))
            Text("Add a card")
        }
    }
}
