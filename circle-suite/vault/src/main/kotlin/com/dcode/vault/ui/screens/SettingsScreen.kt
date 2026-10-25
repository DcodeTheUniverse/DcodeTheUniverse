package com.dcode.vault.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.protocol.CircleContract
import com.dcode.circle.protocol.RequestKind
import com.dcode.crypto.PasswordGenerator
import com.dcode.vault.core.DeviceSlotMode
import com.dcode.vault.core.VaultSession
import com.dcode.vault.security.BioResult
import com.dcode.vault.security.Biometrics
import com.dcode.vault.ui.components.Card
import com.dcode.vault.ui.components.Chip
import com.dcode.vault.ui.components.Hint
import com.dcode.vault.ui.components.SectionTitle
import com.dcode.vault.ui.components.SettingRow
import com.dcode.vault.ui.components.StrengthMeter
import com.dcode.vault.ui.components.TopBar
import com.dcode.vault.ui.components.VaultField
import com.dcode.vault.ui.theme.ThemeMode
import com.dcode.vault.ui.theme.V
import com.dcode.vault.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(session: VaultSession, onBack: () -> Unit) {
    val p = V.palette
    val context = LocalContext.current
    val activity = LocalActivity.current as FragmentActivity
    val manager = context.vault
    val prefs = manager.prefs
    val scope = rememberCoroutineScope()
    val changes by prefs.changes.collectAsState()
    val snap by session.snapshot.collectAsState()
    var headerRev by remember { mutableIntStateOf(0) }
    val header = remember(headerRev) { manager.header() }
    var message by remember { mutableStateOf<String?>(null) }
    var changingPass by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    changes.let { }

    val launchCircle = rememberCircleLauncher { pending, res ->
        describeCircleFailure(res)?.let { message = it; return@rememberCircleLauncher }
        scope.launch {
            message = manager.completeCirclePairing(pending, res.data?.getByteArrayExtra(CircleContract.EXTRA_RESPONSE)) ?: "Paired with Circle ID"
            headerRev++
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        manager.holdLock = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { manager.exportBackup(context.contentResolver.openOutputStream(uri)!!) }
            message = if (r.isSuccess) "Encrypted backup saved. Restoring it needs your recovery passphrase." else "Backup failed: ${r.exceptionOrNull()?.message}"
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        TopBar("Settings", onBack)
        message?.let { Hint(it, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), color = p.accent) }

        SectionTitle("Unlock")
        Card(Modifier.padding(horizontal = 16.dp)) {
            val mode = header?.device?.mode
            val circle = header?.circle
            SettingRow(
                when (mode) {
                    DeviceSlotMode.CIRCLE -> "Circle ID · multimodal"
                    DeviceSlotMode.BIOMETRIC -> "Fingerprint"
                    null -> "Recovery passphrase only"
                },
                when {
                    circle != null -> "Circle ${circle.circleId} · paired ${DateFormat.getDateInstance().format(Date(circle.pairedAt))}"
                    mode == DeviceSlotMode.BIOMETRIC -> "Class 3 biometric key in secure hardware"
                    else -> "Add a device unlock for convenience"
                },
                Icons.Rounded.Shield,
            )
            if (manager.circle.isInstalled) {
                SettingRow(if (circle != null) "Re-pair Circle ID" else "Pair with Circle ID", "Fingerprint + face + liveness", Icons.Rounded.RadioButtonChecked, onClick = {
                    message = launchCircle(RequestKind.PAIR)
                })
            } else {
                SettingRow("Circle ID not installed", "Install Circle ID for multimodal unlock", Icons.Rounded.RadioButtonChecked)
            }
            if (mode != DeviceSlotMode.BIOMETRIC && Biometrics.available(context)) {
                SettingRow("Use fingerprint instead", "Replaces Circle ID unlock on this device", Icons.Rounded.Fingerprint, onClick = {
                    scope.launch {
                        val cipher = runCatching { manager.biometricSetupCipher() }.getOrNull() ?: return@launch
                        when (val r = Biometrics.authenticate(activity, "Enable fingerprint unlock", "Touch the sensor", BiometricPrompt.CryptoObject(cipher))) {
                            is BioResult.Success -> { message = manager.completeBiometricSetup(r.crypto!!.cipher!!) ?: "Fingerprint unlock enabled"; headerRev++ }
                            is BioResult.Error -> message = r.message
                            BioResult.Cancelled -> Unit
                        }
                    }
                })
            }
            if (mode != null) {
                SettingRow("Remove device unlock", "Only the recovery passphrase will open the vault", Icons.Rounded.Key, onClick = {
                    scope.launch { manager.removeDeviceSlot(); headerRev++; message = "Device unlock removed" }
                })
            }
            SettingRow("Change recovery passphrase", null, Icons.Rounded.Key, onClick = { changingPass = true })
        }

        SectionTitle("Auto-lock")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SettingRow("Lock when away", "Screen-off always locks immediately", Icons.Rounded.Timer)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Immediately", 30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min").forEach { (s, label) ->
                    Chip(label, prefs.autoLockSeconds == s, { prefs.autoLockSeconds = s })
                }
            }
            SettingRow("Clear clipboard after", null, Icons.Rounded.Timer)
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { s -> Chip("$s s", prefs.clipboardClearSeconds == s, { prefs.clipboardClearSeconds = s }) }
            }
            SettingRow("Lock now", null, Icons.Rounded.Lock, onClick = { manager.lock() })
        }

        SectionTitle("Appearance")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SettingRow("Theme", null, Icons.Rounded.DarkMode)
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> Chip(m.name, prefs.theme == m, { prefs.theme = m }) }
            }
            SettingRow("Sort by last modified", "Otherwise alphabetical", Icons.Rounded.Sort, trailing = {
                Switch(prefs.sortByModified, { prefs.sortByModified = it }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent))
            })
        }

        SectionTitle("Data")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SettingRow(
                "Search index",
                "${snap.nodes.size} items · ${session.indexedDocuments()} indexed for instant name & content search",
                Icons.Rounded.ManageSearch,
                onClick = { scope.launch { val n = withContext(Dispatchers.IO) { session.repairIndex() }; message = if (n == 0) "Index is up to date" else "Re-indexed $n documents" } },
            )
            SettingRow("Export encrypted backup", "A .zip of ciphertext only — useless without your passphrase", Icons.Rounded.Backup, onClick = {
                manager.holdLock = true
                backup.launch("vault-backup-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(Date())}.zip")
            })
            SettingRow("Wipe vault", "Crypto-shreds every key and file", Icons.Rounded.DeleteForever, tint = p.danger, onClick = { wiping = true })
        }

        SectionTitle("How your data is protected")
        Card(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "• A random 256-bit data key encrypts everything. It is wrapped three ways: by your Argon2id-hardened passphrase, " +
                    "and by a device-bound Android Keystore key combined with a secret only Circle ID releases after fingerprint + face + liveness.\n" +
                    "• Every file, note, folder name and the search index use a chunked AES-256-GCM ⨉ ChaCha20-Poly1305 cascade with fresh per-write keys. " +
                    "Chunks are bound to their file and position, so tampering, truncation or swapping is detected.\n" +
                    "• The header is MAC-authenticated; screenshots and overlays are blocked; nothing is backed up or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium, color = p.inkSoft, modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    if (changingPass) ChangePassphraseDialog(onDismiss = { changingPass = false }) { current, next ->
        scope.launch {
            val e = manager.changePassphrase(current.toCharArray(), next.toCharArray())
            message = e ?: "Recovery passphrase changed"
            if (e == null) changingPass = false
        }
    }

    if (wiping) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { wiping = false },
            containerColor = p.background,
            title = { Text("Wipe everything?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Every note, file and password is destroyed and the keys are erased from secure hardware. Type WIPE to confirm.")
                    VaultField(typed, { typed = it }, placeholder = "WIPE")
                }
            },
            confirmButton = {
                TextButton(enabled = typed == "WIPE", onClick = {
                    scope.launch {
                        val r = Biometrics.authenticate(activity, "Wipe Vault", "Confirm it's you", null)
                        if (r is BioResult.Success || !Biometrics.available(context)) { wiping = false; manager.wipe() }
                    }
                }) { Text("Wipe", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { wiping = false }) { Text("Cancel", color = p.ink) } },
        )
    }
}

@Composable
private fun ChangePassphraseDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val p = V.palette
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = next.isNotEmpty() && next == confirm && PasswordGenerator.entropyBits(next) >= 45 && current.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.background,
        title = { Text("Change recovery passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PassphraseField(current, { current = it }, "Current passphrase")
                PassphraseField(next, { next = it }, "New passphrase")
                PassphraseField(confirm, { confirm = it }, "Confirm new passphrase")
                StrengthMeter(next, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(current, next) }) { Text("Change", color = if (ok) p.accent else p.inkFaint) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.ink) } },
    )
}
