package com.dcode.vault.ui.screens

import androidx.activity.compose.LocalActivity
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.dcode.circle.protocol.CircleContract
import com.dcode.circle.protocol.RequestKind
import com.dcode.crypto.PasswordGenerator
import com.dcode.vault.core.DeviceSlotMode
import com.dcode.vault.security.BioResult
import com.dcode.vault.security.Biometrics
import com.dcode.vault.security.CircleClient
import com.dcode.vault.ui.components.ButtonStyle
import com.dcode.vault.ui.components.Card
import com.dcode.vault.ui.components.Hint
import com.dcode.vault.ui.components.IconTile
import com.dcode.vault.ui.components.PillButton
import com.dcode.vault.ui.components.StrengthMeter
import com.dcode.vault.ui.components.VaultDial
import com.dcode.vault.ui.components.VaultField
import com.dcode.vault.ui.theme.V
import com.dcode.vault.vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Launches Circle ID for a result and routes the signed answer back. Keeps auto-lock from firing meanwhile. */
@Composable
fun rememberCircleLauncher(onResult: (CircleClient.Pending, ActivityResult) -> Unit): (RequestKind) -> String? {
    val manager = LocalContext.current.vault
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        manager.holdLock = false
        val p = manager.pendingCircle
        manager.pendingCircle = null
        p?.let { onResult(it, res) }
    }
    return { kind ->
        val p = manager.newCircleRequest(kind)
        manager.pendingCircle = p
        manager.holdLock = true
        try {
            launcher.launch(manager.circle.intent(p))
            null
        } catch (_: ActivityNotFoundException) {
            manager.holdLock = false
            "Circle ID is not installed"
        }
    }
}

fun describeCircleFailure(res: ActivityResult): String? = when {
    res.resultCode == Activity.RESULT_OK -> null
    res.data?.getStringExtra("error") == "not_enrolled" -> "Open Circle ID and set up your identity first."
    else -> "Cancelled"
}

@Composable
fun PassphraseField(value: String, onChange: (String) -> Unit, placeholder: String, onDone: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    VaultField(
        value = value, onChange = onChange, placeholder = placeholder,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show", tint = V.palette.inkSoft)
            }
        },
    )
}

// ===================================================================== setup

private enum class SetupStep { Welcome, Passphrase, Creating, Method, Done }

@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val p = V.palette
    val context = LocalContext.current
    val manager = context.vault
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SetupStep.Welcome) }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        manager.holdLock = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { context.contentResolver.openInputStream(uri)!!.use { manager.restore(it) } }
            if (r.isFailure) error = "Not a Vault backup: ${r.exceptionOrNull()?.message}" else onFinished()
        }
    }
    val launchCircle = rememberCircleLauncher { pending, res ->
        describeCircleFailure(res)?.let { error = it; return@rememberCircleLauncher }
        scope.launch {
            busy = true
            error = manager.completeCirclePairing(pending, res.data?.getByteArrayExtra(CircleContract.EXTRA_RESPONSE))
            busy = false
            if (error == null) step = SetupStep.Done
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AnimatedContent(
            step,
            transitionSpec = { (slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(340))) togetherWith (slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(200))) },
            label = "setup",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
                    SetupStep.Welcome -> {
                        Spacer(Modifier.height(40.dp))
                        VaultDial(Modifier.size(240.dp))
                        Spacer(Modifier.height(36.dp))
                        Text("Vault", style = MaterialTheme.typography.displayLarge, color = p.ink)
                        Spacer(Modifier.height(10.dp))
                        Hint("Your ideas, files and passwords — encrypted on this device with a dual AES-256 + ChaCha20 cascade, unlocked only by you.")
                        Spacer(Modifier.height(48.dp))
                        PillButton("Create a vault", onClick = { step = SetupStep.Passphrase }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        PillButton("Restore encrypted backup", onClick = { manager.holdLock = true; restore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, style = ButtonStyle.Outline, icon = Icons.Rounded.Restore, modifier = Modifier.fillMaxWidth())
                    }
                    SetupStep.Passphrase -> {
                        Spacer(Modifier.height(24.dp))
                        IconTile(Icons.Rounded.Key, p.accent, size = 64.dp)
                        Spacer(Modifier.height(20.dp))
                        Text("Recovery passphrase", style = MaterialTheme.typography.headlineLarge, color = p.ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Hint("It's the one key that works if biometrics change or you move phones. It's stretched with Argon2id (64 MiB) so guessing is slow. There is no reset — write it down.")
                        Spacer(Modifier.height(24.dp))
                        PassphraseField(pass, { pass = it; error = null }, "Passphrase")
                        Spacer(Modifier.height(10.dp))
                        PassphraseField(confirm, { confirm = it; error = null }, "Confirm passphrase")
                        Spacer(Modifier.height(12.dp))
                        StrengthMeter(pass, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { val g = PasswordGenerator.passphrase(6); pass = g; confirm = g }) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = p.accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Suggest a 6-word passphrase", color = p.accent)
                        }
                        if (pass.isNotEmpty() && pass == confirm && pass.contains('-') && pass.split('-').size == 6) {
                            Text(pass, style = MaterialTheme.typography.titleMedium, color = p.ink, textAlign = TextAlign.Center)
                        }
                        error?.let { Spacer(Modifier.height(8.dp)); Hint(it, color = p.danger) }
                        Spacer(Modifier.height(24.dp))
                        PillButton("Create vault", onClick = {
                            when {
                                PasswordGenerator.entropyBits(pass) < 45 -> error = "Choose a stronger passphrase (at least “Fair”)."
                                pass != confirm -> error = "The passphrases don't match."
                                else -> {
                                    step = SetupStep.Creating
                                    scope.launch {
                                        manager.create(pass.toCharArray())
                                        pass = ""; confirm = ""
                                        step = SetupStep.Method
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    SetupStep.Creating -> {
                        Spacer(Modifier.height(80.dp))
                        VaultDial(Modifier.size(200.dp), busy = true)
                        Spacer(Modifier.height(28.dp))
                        Text("Forging your keys…", style = MaterialTheme.typography.headlineSmall, color = p.ink)
                        Spacer(Modifier.height(8.dp))
                        Hint("Generating a 256-bit key and hardening your passphrase with Argon2id.")
                    }
                    SetupStep.Method -> {
                        Spacer(Modifier.height(24.dp))
                        Text("How should Vault unlock?", style = MaterialTheme.typography.headlineLarge, color = p.ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        val circleInstalled = manager.circle.isInstalled
                        MethodCard(
                            Icons.Rounded.RadioButtonChecked, "Circle ID", if (circleInstalled) "Fingerprint + face + liveness, verified by Circle ID. Recommended." else "Install Circle ID to enable multimodal unlock.",
                            recommended = true, enabled = circleInstalled && !busy,
                        ) {
                            error = launchCircle(RequestKind.PAIR)
                        }
                        Spacer(Modifier.height(12.dp))
                        MethodCard(Icons.Rounded.Fingerprint, "Fingerprint", "Class 3 fingerprint only, via a hardware-bound key.", enabled = Biometrics.available(context) && !busy) {
                            scope.launch {
                                val cipher = runCatching { manager.biometricSetupCipher() }.getOrNull() ?: run { error = "Couldn't create key"; return@launch }
                                when (val r = Biometrics.authenticate(activity, "Enable fingerprint unlock", "Touch the sensor", BiometricPrompt.CryptoObject(cipher))) {
                                    is BioResult.Success -> { error = manager.completeBiometricSetup(r.crypto!!.cipher!!); if (error == null) step = SetupStep.Done }
                                    is BioResult.Error -> error = r.message
                                    BioResult.Cancelled -> Unit
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MethodCard(Icons.Rounded.Key, "Passphrase only", "Most portable, least convenient.", enabled = !busy) { step = SetupStep.Done }
                        error?.let { Spacer(Modifier.height(12.dp)); Hint(it, color = p.danger) }
                    }
                    SetupStep.Done -> {
                        Spacer(Modifier.height(60.dp))
                        VaultDial(Modifier.size(200.dp), openness = 1f)
                        Spacer(Modifier.height(28.dp))
                        Text("Your vault is ready", style = MaterialTheme.typography.headlineLarge, color = p.ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Hint("Everything you add is encrypted before it touches storage — names, folders and search index included.")
                        Spacer(Modifier.height(36.dp))
                        PillButton("Open vault", onClick = onFinished, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, recommended: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = V.palette
    Card(Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, if (enabled) p.accent else p.inkFaint, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) p.ink else p.inkFaint)
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Text("BEST", style = MaterialTheme.typography.labelSmall, color = p.accent)
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.inkSoft)
            }
        }
    }
}

// ===================================================================== lock

@Composable
fun LockScreen() {
    val p = V.palette
    val context = LocalContext.current
    val manager = context.vault
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val mode = remember { manager.unlockMode() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(mode == null) }
    var pass by remember { mutableStateOf("") }
    var lockout by remember { mutableLongStateOf(manager.store.passphraseLockoutRemainingMs()) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(lockout) {
        if (lockout > 0) { delay(1000); lockout = manager.store.passphraseLockoutRemainingMs() }
    }

    val launchCircle = rememberCircleLauncher { pending, res ->
        describeCircleFailure(res)?.let { error = it; return@rememberCircleLauncher }
        scope.launch {
            busy = true
            val e = manager.completeCircleUnlock(pending, res.data?.getByteArrayExtra(CircleContract.EXTRA_RESPONSE))
            busy = false
            error = e
            if (e == null) opening = true
        }
    }

    fun submitPassphrase() {
        if (pass.isEmpty() || busy) return
        scope.launch {
            busy = true
            val e = manager.unlockWithPassphrase(pass.toCharArray())
            busy = false
            pass = ""
            error = e
            lockout = manager.store.passphraseLockoutRemainingMs()
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        VaultDial(Modifier.size(220.dp), openness = if (opening) 1f else 0f, busy = busy)
        Spacer(Modifier.height(32.dp))
        Text("Vault is locked", style = MaterialTheme.typography.headlineLarge, color = p.ink)
        Spacer(Modifier.height(8.dp))
        Hint(
            when (mode) {
                DeviceSlotMode.CIRCLE -> "Circle ID will check your fingerprint, face and liveness."
                DeviceSlotMode.BIOMETRIC -> "Unlock with your fingerprint."
                null -> "Enter your recovery passphrase."
            },
        )
        Spacer(Modifier.height(36.dp))
        when (mode) {
            DeviceSlotMode.CIRCLE -> PillButton("Unlock with Circle ID", enabled = !busy, icon = Icons.Rounded.RadioButtonChecked, onClick = {
                error = launchCircle(RequestKind.VERIFY)
            }, modifier = Modifier.fillMaxWidth())
            DeviceSlotMode.BIOMETRIC -> PillButton("Unlock with fingerprint", enabled = !busy, icon = Icons.Rounded.Fingerprint, onClick = {
                val cipher = manager.biometricUnlockCipher()
                if (cipher == null) {
                    error = "Fingerprints changed, so the hardware key was invalidated. Use your passphrase, then re-enable fingerprint unlock."
                    showPass = true
                } else {
                    scope.launch {
                        when (val r = Biometrics.authenticate(activity, "Unlock Vault", "Touch the sensor", BiometricPrompt.CryptoObject(cipher))) {
                            is BioResult.Success -> { busy = true; error = manager.completeBiometricUnlock(r.crypto!!.cipher!!); busy = false; if (error == null) opening = true }
                            is BioResult.Error -> error = r.message
                            BioResult.Cancelled -> Unit
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth())
            null -> Unit
        }
        AnimatedVisibility(showPass, enter = fadeIn() + expandVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                PassphraseField(pass, { pass = it; error = null }, "Recovery passphrase", onDone = ::submitPassphrase)
                Spacer(Modifier.height(12.dp))
                PillButton(
                    if (lockout > 0) "Wait ${lockout / 1000 + 1}s" else if (busy) "Unlocking…" else "Unlock",
                    enabled = !busy && lockout == 0L && pass.isNotEmpty(),
                    style = if (mode == null) ButtonStyle.Accent else ButtonStyle.Ink,
                    onClick = ::submitPassphrase, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!showPass) {
            TextButton(onClick = { showPass = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Use recovery passphrase", color = p.inkSoft) }
        }
        AnimatedVisibility(error != null) { Hint(error.orEmpty(), Modifier.padding(top = 16.dp), color = if (error == "Cancelled") p.inkSoft else p.danger) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("AES-256-GCM", "ChaCha20-Poly1305", "Argon2id").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = p.inkFaint) }
        }
    }
}
