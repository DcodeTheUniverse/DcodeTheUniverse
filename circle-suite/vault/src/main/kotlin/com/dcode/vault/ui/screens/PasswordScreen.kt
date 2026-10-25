package com.dcode.vault.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcode.crypto.PasswordGenerator
import com.dcode.crypto.Totp
import com.dcode.vault.SecureClipboard
import com.dcode.vault.core.PasswordFields
import com.dcode.vault.core.VaultSession
import com.dcode.vault.ui.components.Card
import com.dcode.vault.ui.components.CountdownRing
import com.dcode.vault.ui.components.FieldLabel
import com.dcode.vault.ui.components.IconTile
import com.dcode.vault.ui.components.PillButton
import com.dcode.vault.ui.components.StrengthMeter
import com.dcode.vault.ui.components.TopBar
import com.dcode.vault.ui.components.VaultField
import com.dcode.vault.ui.theme.Mono
import com.dcode.vault.ui.theme.V
import com.dcode.vault.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Composable
fun PasswordScreen(session: VaultSession, id: String?, parentId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val p = V.palette
    val context = LocalContext.current
    val manager = context.vault
    val scope = rememberCoroutineScope()
    val snap by session.snapshot.collectAsState()
    val node = id?.let { snap.nodes[it] }
    var editing by remember { mutableStateOf(id == null) }
    var title by remember(node?.id) { mutableStateOf(node?.name ?: "") }
    var fields by remember(node?.id) { mutableStateOf(node?.password ?: PasswordFields()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1800); copied = null } }

    BackHandler {
        if (editing && id != null) { editing = false; title = node?.name ?: ""; fields = node?.password ?: PasswordFields() } else onBack()
    }

    fun copy(label: String, value: String) {
        SecureClipboard.copy(context, label, value, manager.prefs.clipboardClearSeconds)
        copied = label
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding().imePadding()) {
        TopBar(if (id == null) "New login" else title.ifBlank { "Login" }, onBack) {
            if (editing) {
                IconButton(onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { runCatching { session.savePassword(id, parentId, title, fields) }.getOrNull() }
                        if (saved != null) { editing = false; if (id == null) onSaved(saved.id) }
                    }
                }) { Icon(Icons.Rounded.Check, "Save", tint = p.accent) }
            } else {
                IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit", tint = p.ink) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = p.danger) }
            }
        }
        AnimatedContent(editing, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "pw") { edit ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (edit) {
                    EditFields(title, { title = it }, fields, { fields = it })
                } else {
                    ViewFields(title, fields, ::copy) { url ->
                        val u = if (url.startsWith("http")) url else "https://$url"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                    }
                }
            }
        }
    }

    copied?.let {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                "$it copied · clears in ${manager.prefs.clipboardClearSeconds}s", color = p.background, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.background(p.ink, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    if (confirmDelete && id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.background,
            title = { Text("Delete ${title}?") },
            text = { Text("This login is removed from the encrypted catalog.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { withContext(Dispatchers.IO) { session.delete(id) }; onBack() } }) { Text("Delete", color = p.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = p.ink) } },
        )
    }
}

@Composable
private fun ViewFields(title: String, f: PasswordFields, copy: (String, String) -> Unit, open: (String) -> Unit) {
    val p = V.palette
    var reveal by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.Key, p.key, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = p.ink)
            if (f.url.isNotBlank()) Text(f.url, style = MaterialTheme.typography.bodyMedium, color = p.inkSoft)
        }
    }
    Card {
        if (f.username.isNotBlank()) ValueRow("Username", f.username, onCopy = { copy("Username", f.username) })
        ValueRow(
            "Password", if (reveal) f.password else "•".repeat(f.password.length.coerceIn(8, 16)), mono = reveal,
            onCopy = { copy("Password", f.password) },
            extra = {
                IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Reveal", tint = p.inkSoft) }
            },
        )
        if (f.url.isNotBlank()) ValueRow("Website", f.url, onCopy = { copy("Website", f.url) }, extra = {
            IconButton(onClick = { open(f.url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open", tint = p.inkSoft) }
        })
    }
    if (f.password.isNotEmpty()) {
        StrengthMeter(f.password, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        if (f.passwordChangedAt > 0) {
            val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - f.passwordChangedAt)
            Text(
                if (days == 0L) "Password set today" else "Password set $days day${if (days == 1L) "" else "s"} ago" + if (days > 365) " — consider rotating it" else "",
                style = MaterialTheme.typography.bodySmall, color = if (days > 365) p.note else p.inkSoft, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
    val totp = remember(f.totp) { Totp.parse(f.totp) }
    if (totp != null) TotpCard(totp, copy)
    if (f.notes.isNotBlank()) {
        Card { Column(Modifier.padding(16.dp)) { FieldLabel("Notes"); Text(f.notes, style = MaterialTheme.typography.bodyLarge, color = p.ink) } }
    }
    f.extra.forEach { field ->
        Card { ValueRow(field.label, if (field.secret) "••••••••" else field.value, onCopy = { copy(field.label, field.value) }) }
    }
}

@Composable
private fun TotpCard(cfg: Totp.Config, copy: (String, String) -> Unit) {
    val p = V.palette
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cfg) { while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) } }
    val code = Totp.code(cfg, now)
    val left = Totp.secondsRemaining(cfg, now)
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldLabel("One-time code" + (cfg.issuer?.let { " · $it" } ?: ""))
                AnimatedContent(code, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "totp") { c ->
                    Text(c.chunked(3).joinToString(" "), fontFamily = Mono, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = if (left <= 5) p.danger else p.ink, letterSpacing = 2.sp)
                }
            }
            Box(contentAlignment = Alignment.Center) {
                CountdownRing(left / cfg.periodSeconds.toFloat(), Modifier.size(40.dp), color = if (left <= 5) p.danger else p.accent)
                Text("$left", style = MaterialTheme.typography.labelMedium, color = p.ink)
            }
            IconButton(onClick = { copy("Code", code) }) { Icon(Icons.Rounded.ContentCopy, "Copy code", tint = p.accent) }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, mono: Boolean = false, onCopy: () -> Unit, extra: @Composable () -> Unit = {}) {
    val p = V.palette
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.inkSoft)
            Text(value, style = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono) else MaterialTheme.typography.bodyLarge, color = p.ink)
        }
        extra()
        IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy $label", tint = p.accent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFields(title: String, onTitle: (String) -> Unit, f: PasswordFields, onChange: (PasswordFields) -> Unit) {
    val p = V.palette
    var showGenerator by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(f.password.isEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { FieldLabel("Title"); VaultField(title, onTitle, placeholder = "e.g. GitHub") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Username or email")
        VaultField(f.username, { onChange(f.copy(username = it)) }, placeholder = "you@example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Password")
        VaultField(
            f.password, { onChange(f.copy(password = it)) }, placeholder = "Password",
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailing = {
                Row {
                    IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Reveal", tint = p.inkSoft) }
                    IconButton(onClick = { showGenerator = true }) { Icon(Icons.Rounded.AutoAwesome, "Generate", tint = p.accent) }
                }
            },
        )
        StrengthMeter(f.password, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Website")
        VaultField(f.url, { onChange(f.copy(url = it)) }, placeholder = "github.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("2FA secret (optional)")
        VaultField(f.totp, { onChange(f.copy(totp = it.trim())) }, placeholder = "Base32 secret or otpauth:// link", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        if (f.totp.isNotBlank() && Totp.parse(f.totp) == null) Text("That doesn't look like a valid TOTP secret", style = MaterialTheme.typography.bodySmall, color = p.danger)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Notes")
        VaultField(f.notes, { onChange(f.copy(notes = it)) }, placeholder = "Recovery codes, security questions…", singleLine = false, modifier = Modifier.height(140.dp))
    }
    Spacer(Modifier.height(40.dp))

    if (showGenerator) {
        ModalBottomSheet(onDismissRequest = { showGenerator = false }, containerColor = p.background) {
            Generator(onUse = { onChange(f.copy(password = it)); reveal = true; showGenerator = false })
        }
    }
}

@Composable
private fun Generator(onUse: (String) -> Unit) {
    val p = V.palette
    var words by remember { mutableStateOf(false) }
    var opts by remember { mutableStateOf(PasswordGenerator.Options()) }
    var wordCount by remember { mutableStateOf(6) }
    var value by remember(opts, words, wordCount) { mutableStateOf(if (words) PasswordGenerator.passphrase(wordCount) else PasswordGenerator.generate(opts)) }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Generate", style = MaterialTheme.typography.headlineSmall, color = p.ink)
        Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(value, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "gen", modifier = Modifier.weight(1f)) {
                    Text(it, fontFamily = Mono, fontSize = 17.sp, color = p.ink)
                }
                IconButton(onClick = { value = if (words) PasswordGenerator.passphrase(wordCount) else PasswordGenerator.generate(opts) }) {
                    Icon(Icons.Rounded.Refresh, "Regenerate", tint = p.accent)
                }
            }
        }
        StrengthMeter(value, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Word passphrase", style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
            Switch(words, { words = it }, colors = switchColors)
        }
        if (words) {
            Text("$wordCount words", style = MaterialTheme.typography.bodyMedium, color = p.inkSoft)
            Slider(wordCount.toFloat(), { wordCount = it.toInt() }, valueRange = 4f..10f, steps = 5, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
        } else {
            Text("${opts.length} characters", style = MaterialTheme.typography.bodyMedium, color = p.inkSoft)
            Slider(opts.length.toFloat(), { opts = opts.copy(length = it.toInt()) }, valueRange = 8f..64f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
            listOf(
                "Uppercase" to opts.uppercase, "Digits" to opts.digits, "Symbols" to opts.symbols, "Avoid look-alikes (Il1O0)" to opts.avoidAmbiguous,
            ).forEachIndexed { i, (label, on) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
                    Switch(on, { v ->
                        opts = when (i) { 0 -> opts.copy(uppercase = v); 1 -> opts.copy(digits = v); 2 -> opts.copy(symbols = v); else -> opts.copy(avoidAmbiguous = v) }
                    }, colors = switchColors)
                }
            }
        }
        PillButton("Use this password", onClick = { onUse(value) }, modifier = Modifier.fillMaxWidth())
    }
}
