package com.dcode.wallet.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardBrand
import com.dcode.wallet.data.CardNumbers
import com.dcode.wallet.data.CardRepository
import com.dcode.wallet.ui.components.CardFace
import com.dcode.wallet.ui.components.ColorPicker
import com.dcode.wallet.ui.components.GroupingTransformation
import com.dcode.wallet.ui.components.PaymentCardView
import java.time.YearMonth

/** Validates the add/edit form. Returns field name -> error message; empty means valid. */
fun validateCard(number: String, holder: String, expiry: String, cvv: String, now: YearMonth = YearMonth.now()): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val brand = CardBrand.detect(number)
    if (!CardNumbers.isValidLength(number, brand) || !CardNumbers.luhnValid(number)) errors["number"] = "Enter a valid card number"
    if (holder.isBlank()) errors["holder"] = "Enter the name on the card"
    val month = expiry.take(2).toIntOrNull()
    val year = expiry.drop(2).toIntOrNull()
    when {
        expiry.length != 4 || month == null || year == null -> errors["expiry"] = "Use MM/YY"
        month !in 1..12 -> errors["expiry"] = "Month must be 01–12"
        YearMonth.of(2000 + year, month).isBefore(now) -> errors["expiry"] = "This card has expired"
    }
    if (cvv.length != brand.cvvLength) errors["cvv"] = "${brand.cvvLength} digits on the back of the card"
    return errors
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCardScreen(existing: Card?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    var number by rememberSaveable { mutableStateOf(existing?.number ?: "") }
    var holder by rememberSaveable { mutableStateOf(existing?.holder ?: "") }
    var expiry by rememberSaveable { mutableStateOf(existing?.let { "%02d%02d".format(it.expMonth, it.expYear % 100) } ?: "") }
    var cvv by rememberSaveable { mutableStateOf(existing?.cvv ?: "") }
    var nickname by rememberSaveable { mutableStateOf(existing?.nickname ?: "") }
    var isVirtual by rememberSaveable { mutableStateOf(existing?.isVirtual ?: false) }
    var colorIndex by rememberSaveable { mutableIntStateOf(existing?.colorIndex ?: 0) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var attempted by remember { mutableStateOf(false) }

    val brand = CardBrand.detect(number)
    val numberTransformation = remember(brand) { GroupingTransformation(CardNumbers.groupBreaks(brand), ' ') }
    val expiryTransformation = remember { GroupingTransformation(setOf(2), '/') }

    fun revalidate() {
        if (attempted) errors = validateCard(number, holder, expiry, cvv)
    }

    fun save() {
        attempted = true
        errors = validateCard(number, holder, expiry, cvv)
        if (errors.isNotEmpty()) return
        val card = Card(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            number = number,
            holder = holder.trim(),
            expMonth = expiry.take(2).toInt(),
            expYear = 2000 + expiry.drop(2).toInt(),
            cvv = cvv,
            nickname = nickname.trim(),
            colorIndex = colorIndex,
            isVirtual = isVirtual,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        CardRepository.upsert(card)
        onSaved(card.id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add a card" else "Edit card") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaymentCardView(
                CardFace(
                    number = number,
                    holder = holder,
                    expiry = if (expiry.isEmpty()) "" else expiry.take(2) + (if (expiry.length > 2) "/" + expiry.drop(2) else ""),
                    colorIndex = colorIndex,
                    nickname = nickname,
                    isVirtual = isVirtual,
                ),
                revealNumber = true,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter(Char::isDigit).take(19); revalidate() },
                label = { Text("Card number") },
                singleLine = true,
                isError = "number" in errors,
                supportingText = { Text(errors["number"] ?: if (brand != CardBrand.UNKNOWN) brand.display else "") },
                visualTransformation = numberTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = holder,
                onValueChange = { holder = it.take(40); revalidate() },
                label = { Text("Name on card") },
                singleLine = true,
                isError = "holder" in errors,
                supportingText = errors["holder"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it.filter(Char::isDigit).take(4); revalidate() },
                    label = { Text("Expiry (MM/YY)") },
                    singleLine = true,
                    isError = "expiry" in errors,
                    supportingText = errors["expiry"]?.let { { Text(it) } },
                    visualTransformation = expiryTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cvv,
                    onValueChange = { cvv = it.filter(Char::isDigit).take(4); revalidate() },
                    label = { Text("CVV") },
                    singleLine = true,
                    isError = "cvv" in errors,
                    supportingText = errors["cvv"]?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                label = { Text("Card nickname (optional)") },
                placeholder = { Text("e.g. Salary Debit, Online Shopping") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Virtual card", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Issued in an app for online payments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isVirtual, onCheckedChange = { isVirtual = it })
            }
            Text("Card colour", style = MaterialTheme.typography.bodyLarge)
            ColorPicker(colorIndex, { colorIndex = it }, Modifier.horizontalScroll(rememberScrollState()))
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (existing == null) "Save card" else "Save changes")
            }
            Text(
                "Card details are encrypted on this device with a hardware-backed key and never leave your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

