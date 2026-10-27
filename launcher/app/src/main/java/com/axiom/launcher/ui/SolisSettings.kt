package com.axiom.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axiom.launcher.LauncherViewModel
import com.axiom.launcher.platform.PerfMonitor
import com.axiom.launcher.solis.SolisHost
import com.axiom.launcher.solis.catalog.Freshness
import com.axiom.launcher.solis.catalog.ModelInfo
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.CoordinatorMode
import com.axiom.launcher.solis.cortex.DecisionMatrix
import com.axiom.launcher.solis.cortex.PolicyStatus
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.cortex.TierPolicy
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.ui.theme.LocalPalette
import com.axiom.launcher.ui.theme.lStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

// =============================================================== providers & models

@Composable
fun ProvidersSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    Text(
        "Solis talks to models through providers. OpenRouter gives one key for hundreds of models; Anthropic connects directly; " +
            "a local runtime (Ollama, llama.cpp, LM Studio…) keeps everything on your hardware. Model lists, prices and capabilities " +
            "are discovered from each provider and refreshed daily, never hardcoded.",
        style = lStyle(13.sp, color = p.dim),
    )
    ProviderBlock(vm, SolisHost.OPENROUTER, "OpenRouter", "sk-or-…", "https://openrouter.ai/keys")
    ProviderBlock(vm, SolisHost.ANTHROPIC, "Anthropic (direct)", "sk-ant-…", "https://console.anthropic.com/settings/keys")
    LocalRuntimeBlock(vm)
    Spacer(Modifier.height(8.dp))
    Chip("Refresh all model catalogs") { vm.refreshCatalogs() }

    SectionLabel("Models by role")
    Text(
        "Auto lets the Cortex pick per task from measured quality, price, speed and reliability. A pinned model is preferred, " +
            "but privacy, capability and budget limits still apply.",
        style = lStyle(13.sp, color = p.dim),
    )
    val rev = vm.settingsRevision + vm.catalogRevision
    val pins = remember(rev) { vm.solis.pinnedModels() }
    var picking by remember { mutableStateOf<CallRole?>(null) }
    listOf(
        CallRole.COORDINATOR to "Coordinator — sizes up and plans tasks",
        CallRole.WORKER to "Worker — does the task",
        CallRole.VERIFIER to "Verifier — independently checks T3 answers",
        CallRole.COMBINER to "Combiner — merges parallel results",
        CallRole.NARRATOR to "Background updates — cheap status lines",
    ).forEach { (role, label) ->
        val pin = pins[role]
        NavRow(label, pin?.let { "${it.model} · ${it.provider}" } ?: if (role == CallRole.COMBINER && pins[CallRole.COORDINATOR] != null) "Same as coordinator" else "Auto") { picking = role }
    }
    picking?.let { role -> ModelPicker(vm, "Model for ${role.name.lowercase()}", pins[role], onPick = { vm.pinModel(role, it) }) { picking = null } }
}

@Composable
private fun ProviderBlock(vm: LauncherViewModel, id: String, title: String, hint: String, keyUrl: String) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val rev = vm.settingsRevision + vm.catalogRevision
    val configured = remember(rev) { vm.solis.provider(id)?.isConfigured() == true }
    val snap = remember(rev) { vm.solis.registry.catalog.snapshot(id) }
    val fresh = remember(rev) { vm.solis.registry.freshness(id) }
    var key by remember { mutableStateOf("") }
    var credits by remember { mutableStateOf<String?>(null) }
    SectionLabel(title)
    Text(
        (if (configured) "Connected ✓" else "Not connected") + " · " + when (fresh) {
            Freshness.MISSING -> "no model list yet"
            Freshness.STALE -> "${snap?.models?.size ?: 0} models · list is stale (${snap?.let { ago(it.fetchedAt) }})"
            Freshness.FRESH -> "${snap?.models?.size ?: 0} models · updated ${snap?.let { ago(it.fetchedAt) }}"
        },
        style = lStyle(13.sp, color = if (configured) p.fg else p.dim),
    )
    Spacer(Modifier.height(6.dp))
    SecretField(key, { key = it }, if (configured) "••••••••  (paste to replace)" else hint)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (key.isNotBlank()) Chip("Save & test", selected = true) { vm.saveKey(id, key); key = "" }
        if (configured) {
            Chip("Test") { scope.launch { vm.say(vm.solis.testProvider(id)) } }
            Chip("Remove key") { vm.saveKey(id, null) }
        }
        if (configured && id == SolisHost.OPENROUTER) Chip("Check credits") {
            scope.launch {
                val s = vm.solis.openRouterKeyStatus()
                credits = s?.let {
                    "Key “${it.label}” · today ${usd(it.usageDaily)} · this month ${usd(it.usageMonthly)}" +
                        (it.limitRemaining?.let { r -> " · ${usd(r)} left on the key" } ?: "") + (if (it.isFreeTier) " · free tier" else "")
                } ?: "Couldn't read the key's status"
            }
        }
        Chip("Get a key", icon = Icons.AutoMirrored.Outlined.OpenInNew) { vm.actions.openUrl(keyUrl) }
    }
    credits?.let { Text(it, style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun LocalRuntimeBlock(vm: LauncherViewModel) {
    val p = LocalPalette.current
    var url by remember { mutableStateOf(vm.prefs.localBaseUrl) }
    var tools by remember { mutableStateOf(vm.prefs.localTools) }
    var ctx by remember { mutableIntStateOf(vm.prefs.localContext) }
    var key by remember { mutableStateOf("") }
    SectionLabel("Local runtime")
    Text("An OpenAI-compatible server on this phone or your network, e.g. http://192.168.1.20:11434/v1 for Ollama. Costs nothing per call; used for local-only modes and offline.",
        style = lStyle(13.sp, color = p.dim))
    Spacer(Modifier.height(6.dp))
    InputBar(url, { url = it.trim() }, "http://host:port/v1", onSubmit = {})
    val insecure = url.startsWith("http://") && !Regex("^http://(localhost|127\\.0\\.0\\.1|\\[::1\\])").containsMatchIn(url)
    if (insecure) Text("Unencrypted connection: requests and any key can be read on the network. Use only on a network you trust.",
        style = lStyle(12.sp, color = p.danger), modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(6.dp))
    SecretField(key, { key = it }, "API key (optional)")
    SettingSwitch("The model supports tool calls", "Only if your runtime and model really do; otherwise Solis uses it for text-only steps", tools) { tools = it }
    Text("Context window", style = lStyle(15.sp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4096, 8192, 16384, 32768, 131072).forEach { n -> Chip("${n / 1024}k", ctx == n) { ctx = n } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Save & test", selected = true) { vm.saveLocalRuntime(url, tools, ctx, key.ifBlank { null }); key = "" }
        if (vm.prefs.localBaseUrl.isNotBlank()) Chip("Remove") { url = ""; vm.saveLocalRuntime("", false, ctx, "") }
    }
}

/** Pick a model from the discovered catalogs of configured providers, or "Auto" (null) to let the Cortex decide. */
@Composable
fun ModelPicker(vm: LauncherViewModel, title: String, current: ModelRef?, onPick: (ModelRef?) -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    var q by remember { mutableStateOf("") }
    val models = remember(vm.catalogRevision) {
        vm.solis.registry.catalog.all().filter { !it.isMeta && !it.isBatch && vm.solis.provider(it.provider)?.isConfigured() == true }
            .sortedBy { it.name.lowercase() }
    }
    val shown = remember(models, q) {
        if (q.isBlank()) models else models.filter { it.name.contains(q, true) || it.id.contains(q, true) }
    }
    LDialog(onDone) {
        Text(title, style = lStyle(20.sp, FontWeight.Medium))
        Spacer(Modifier.height(8.dp))
        InputBar(q, { q = it }, "Search ${models.size} models", onSubmit = {})
        Spacer(Modifier.height(6.dp))
        Text(
            if (current == null) "● Auto (Cortex decides)" else "○ Auto (Cortex decides)",
            style = lStyle(15.sp, color = p.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(null); onDone() }.padding(vertical = 10.dp),
        )
        if (models.isEmpty()) Text("No models yet: connect a provider and refresh its catalog.", style = lStyle(13.sp, color = p.dim))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(shown.take(300), key = { it.ref.key }) { m -> ModelRow(m, m.ref == current) { onPick(m.ref); onDone() } }
        }
    }
}

@Composable
private fun ModelRow(m: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val price = when {
        m.isLocal -> "local · free"
        m.isFree -> "free"
        m.priceKnown -> "$" + "%.2f".format(m.inputPrice!! * 1e6) + " / $" + "%.2f".format(m.outputPrice!! * 1e6) + " per M tokens"
        else -> "price unknown"
    }
    val badges = buildList {
        if (m.supportsTools) add("tools")
        if ("image" in m.inputModalities) add("vision")
        if ("file" in m.inputModalities) add("pdf")
        if (m.efforts.isNotEmpty()) add("reasoning")
        m.qualityIndex?.let { add("index ${it.toInt()}") }
        m.expiresAt?.let { add("retiring") }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text((if (selected) "● " else "") + m.name, style = lStyle(15.sp, color = if (selected) p.accent else p.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${m.provider} · ${m.contextLength / 1000}k ctx · $price" + if (badges.isNotEmpty()) " · " + badges.joinToString(" ") else "",
            style = lStyle(11.sp, color = p.dim), maxLines = 2)
    }
}

// =============================================================== cortex

@Composable
fun CortexSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val rev = vm.settingsRevision
    val active = remember(rev) { vm.solis.policies.active() }
    var m by remember(active.version) { mutableStateOf(active.matrix) }
    val dirty = m != active.matrix

    Text(
        "The Cortex sizes up each request and routes it. Plain commands never reach a model (T0). Routine work goes to one " +
            "efficient model (T1); multi-part work is planned and delegated (T2); difficult or sensitive work gets stronger reasoning " +
            "and an independent check by a different model family (T3). Learned preferences only reorder models that already pass " +
            "your privacy, capability and spending limits.",
        style = lStyle(13.sp, color = p.dim),
    )
    Text("Active policy: v${active.version} · ${active.origin.name.lowercase().replace('_', ' ')} · ${active.note}", style = lStyle(13.sp), modifier = Modifier.padding(top = 8.dp))

    SectionLabel("Assessment")
    Text("Ask the coordinator model to assess", style = lStyle(15.sp))
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CoordinatorMode.WHEN_UNCERTAIN to "When unsure", CoordinatorMode.ALWAYS to "Always", CoordinatorMode.NEVER to "Never").forEach { (mode, label) ->
            Chip(label, m.assessment.coordinatorMode == mode) { m = m.copy(assessment = m.assessment.copy(coordinatorMode = mode)) }
        }
    }
    Stepper("T1 up to complexity", "%.2f".format(m.assessment.t1Max), "Higher keeps more work on one cheap model",
        { m = m.copy(assessment = m.assessment.copy(t1Max = (m.assessment.t1Max - 0.05).coerceIn(0.05, m.assessment.t2Max - 0.05))) },
        { m = m.copy(assessment = m.assessment.copy(t1Max = (m.assessment.t1Max + 0.05).coerceIn(0.05, m.assessment.t2Max - 0.05))) })
    Stepper("T2 up to complexity", "%.2f".format(m.assessment.t2Max), "Above this is T3",
        { m = m.copy(assessment = m.assessment.copy(t2Max = (m.assessment.t2Max - 0.05).coerceIn(m.assessment.t1Max + 0.05, 0.95))) },
        { m = m.copy(assessment = m.assessment.copy(t2Max = (m.assessment.t2Max + 0.05).coerceIn(m.assessment.t1Max + 0.05, 0.95))) })
    Stepper("Coordinator call cap", usd(m.coordinatorMaxCallCostUsd), null,
        { m = m.copy(coordinatorMaxCallCostUsd = (m.coordinatorMaxCallCostUsd / 2).coerceAtLeast(0.001)) },
        { m = m.copy(coordinatorMaxCallCostUsd = (m.coordinatorMaxCallCostUsd * 2).coerceAtMost(1.0)) })

    listOf(Tier.T1, Tier.T2, Tier.T3).forEach { tier ->
        SectionLabel("${tier.label} · ${tier.blurb}")
        TierEditor(m.policy(tier)) { np -> m = m.copy(tiers = m.tiers + (tier to np)) }
    }

    SectionLabel("Adaptive routing")
    SettingSwitch("Learn from outcomes", "Trials a candidate policy on a share of tasks; promotes it only if it does no worse, else rolls back", m.learning.enabled) {
        m = m.copy(learning = m.learning.copy(enabled = it))
    }
    Stepper("Evidence needed", "${m.learning.minEvidence} outcomes", "Per model and tier before any adjustment",
        { m = m.copy(learning = m.learning.copy(minEvidence = (m.learning.minEvidence - 4).coerceAtLeast(4))) },
        { m = m.copy(learning = m.learning.copy(minEvidence = (m.learning.minEvidence + 4).coerceAtMost(200))) })
    Stepper("Trial share", "${(m.learning.trialShare * 100).toInt()}%", null,
        { m = m.copy(learning = m.learning.copy(trialShare = (m.learning.trialShare - 0.05).coerceAtLeast(0.05))) },
        { m = m.copy(learning = m.learning.copy(trialShare = (m.learning.trialShare + 0.05).coerceAtMost(0.5))) })

    SectionLabel("Background updates")
    SettingSwitch("Narrated status lines", "A cheap model summarizes progress of multi-step tasks; otherwise status text is generated on the device", m.narrator.enabled) {
        m = m.copy(narrator = m.narrator.copy(enabled = it))
    }
    Stepper("Narration calls per task", "${m.narrator.maxCallsPerThread}", "Never starts work; triggered only by meaningful events",
        { m = m.copy(narrator = m.narrator.copy(maxCallsPerThread = (m.narrator.maxCallsPerThread - 1).coerceAtLeast(0))) },
        { m = m.copy(narrator = m.narrator.copy(maxCallsPerThread = (m.narrator.maxCallsPerThread + 1).coerceAtMost(12))) })

    SectionLabel("Always T3 for")
    var domains by remember(m.sensitiveDomains) { mutableStateOf(m.sensitiveDomains.joinToString(", ")) }
    InputBar(domains, { domains = it; m = m.copy(sensitiveDomains = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) }, "medical, legal, …", onSubmit = {})

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dirty) {
            Chip("Apply as v${(vm.solis.policies.history().firstOrNull()?.version ?: active.version) + 1}", selected = true) { vm.applyMatrix(m, "Edited in settings") }
            Chip("Discard") { m = active.matrix }
        }
        Chip("Restore defaults") { m = DecisionMatrix.default() }
    }

    SectionLabel("Policy history")
    val history = remember(rev) { vm.solis.policies.history().take(12) }
    history.forEach { v ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("v${v.version} · ${v.status.name.lowercase().replace('_', ' ')} · ${v.origin.name.lowercase().replace('_', ' ')}" +
                (if (v.bonuses.isNotEmpty()) " · ${v.bonuses.size} learned adjustments" else ""), style = lStyle(14.sp, FontWeight.Medium))
            Text(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(v.createdAt)) + " · " + v.note + (v.evaluation?.let { " · $it" } ?: ""),
                style = lStyle(12.sp, color = p.dim))
            if (v.status != PolicyStatus.ACTIVE) Text("Restore this version", style = lStyle(12.sp, FontWeight.Medium, p.accent),
                modifier = Modifier.clickable { vm.rollbackPolicy(v.version) }.padding(vertical = 4.dp))
        }
    }

    SectionLabel("Routing log")
    val log = remember(vm.threads) { vm.engine.log.value.take(40) }
    if (log.isEmpty()) Text("No routing decisions yet.", style = lStyle(13.sp, color = p.dim))
    log.forEach { Text(it, style = lStyle(11.sp, color = p.dim).copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(vertical = 2.dp)) }
}

@Composable
private fun TierEditor(t: TierPolicy, onChange: (TierPolicy) -> Unit) {
    Stepper("Minimum quality", "${(t.minQuality * 100).toInt()}th pct", "Within the catalog's quality estimates",
        { onChange(t.copy(minQuality = (t.minQuality - 0.05).coerceIn(0.0, 0.95))) }, { onChange(t.copy(minQuality = (t.minQuality + 0.05).coerceIn(0.0, 0.95))) })
    Stepper("Max cost per call", usd(t.maxCallCostUsd), null,
        { onChange(t.copy(maxCallCostUsd = (t.maxCallCostUsd / 2).coerceAtLeast(0.001))) }, { onChange(t.copy(maxCallCostUsd = (t.maxCallCostUsd * 2).coerceAtMost(5.0))) })
    Text("Reasoning effort (only where the model supports it)", style = lStyle(15.sp), modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Model default", t.effort == null) { onChange(t.copy(effort = null)) }
        listOf(Effort.MINIMAL, Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH).forEach { e -> Chip(e.wire, t.effort == e) { onChange(t.copy(effort = e)) } }
    }
    SettingSwitch("Independent verification", "A model from a different family checks the answer", t.verify) { onChange(t.copy(verify = it)) }
    SettingSwitch("Delegate to specialists", "Plan and run independent steps in parallel", t.allowDelegation) { onChange(t.copy(allowDelegation = it)) }
    Stepper("Parallel steps", "${t.maxParallel}", null, { onChange(t.copy(maxParallel = (t.maxParallel - 1).coerceIn(1, 6))) }, { onChange(t.copy(maxParallel = (t.maxParallel + 1).coerceIn(1, 6))) })
    Stepper("Retries", "${t.maxRetries}", "On another model when one fails",
        { onChange(t.copy(maxRetries = (t.maxRetries - 1).coerceIn(0, 5))) }, { onChange(t.copy(maxRetries = (t.maxRetries + 1).coerceIn(0, 5))) })
    Stepper("Escalations", "${t.maxEscalations}", "Moves a failed or rejected step up one tier",
        { onChange(t.copy(maxEscalations = (t.maxEscalations - 1).coerceIn(0, 3))) }, { onChange(t.copy(maxEscalations = (t.maxEscalations + 1).coerceIn(0, 3))) })
}

// =============================================================== budget

@Composable
fun BudgetSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val rev = vm.settingsRevision
    val summary = remember(rev, vm.threads) { vm.solis.ledger.summary() }
    val st = remember(rev, vm.mode) { vm.solis.settings() }
    val recent = remember(rev, vm.threads) { vm.solis.ledger.recent(30) }
    val titles = remember(vm.threads) { vm.threads.associate { it.id to it.title } }

    Text("Every model call counts — planning, background updates, retries and verification included. Calls that would cross a cap are refused before they're made.",
        style = lStyle(13.sp, color = p.dim))
    SectionLabel("Half-price batches")
    Text(
        if (vm.batchAvailable) "For questions that can wait: Solis sends them through Anthropic's Message Batches API at half the usual price. " +
            "Answers arrive as tasks within 24 hours, usually within the hour. A batch answer is one reply: no tools or phone actions. " +
            "Turn it on per message with the “Half-price batch” chip on the Solis page."
        else "Add an Anthropic API key (Providers & models) to send questions that can wait at half price through Anthropic's Message Batches API.",
        style = lStyle(13.sp, color = p.dim),
    )
    if (vm.batchAvailable) {
        val model = remember(rev, vm.batchBest, vm.catalogRevision) { vm.solis.batchModel(vm.batchBest)?.name ?: "no Anthropic models in the catalog yet" }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Newest Sonnet", selected = !vm.batchBest) { vm.updateBatchBest(false) }
            Chip("Most capable", selected = vm.batchBest) { vm.updateBatchBest(true) }
        }
        Text("Batches use $model.", style = lStyle(12.sp, color = p.dim))
    }
    SectionLabel("Spent")
    Text("Today ${usd(summary.today)} of ${usd(st.caps.dailyUsd)} · ${summary.callsToday} calls", style = lStyle(15.sp))
    Progress((summary.today / st.caps.dailyUsd).toFloat(), Modifier.padding(vertical = 6.dp))
    Text("This month ${usd(summary.month)} of ${usd(st.caps.monthlyUsd)}", style = lStyle(15.sp))
    Progress((summary.month / st.caps.monthlyUsd).toFloat(), Modifier.padding(vertical = 6.dp))
    if (summary.byRoleToday.isNotEmpty()) Text("By role today: " + summary.byRoleToday.entries.joinToString { "${it.key.name.lowercase()} ${usd(it.value)}" },
        style = lStyle(12.sp, color = p.dim))
    if (summary.byModeToday.isNotEmpty()) Text("By mode today: " + summary.byModeToday.entries.joinToString { "${it.key} ${usd(it.value)}" },
        style = lStyle(12.sp, color = p.dim))
    val (cached, input) = remember(vm.threads) { vm.threads.sumOf { it.usage.cachedTokens } to vm.threads.sumOf { it.usage.inputTokens } }
    Text(
        if (cached > 0) "Prompt caching: ${cached * 100 / input.coerceAtLeast(1)}% of input tokens in your current tasks came from cache."
        else "Prompt caching: Solis marks the stable part of each task (instructions, tools, the request) for Claude models, so tool loops and back-to-back tasks re-read it at a fraction of the price.",
        style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 4.dp),
    )

    SectionLabel("Caps")
    fun step(v: Float, up: Boolean) = if (up) (v * 2).coerceAtMost(500f) else (v / 2).coerceAtLeast(0.01f)
    Stepper("Per day", usd(st.caps.dailyUsd), null, { vm.editSolisPrefs { it.dailyCapUsd = step(it.dailyCapUsd, false) } }, { vm.editSolisPrefs { it.dailyCapUsd = step(it.dailyCapUsd, true) } })
    Stepper("Per month", usd(st.caps.monthlyUsd), null, { vm.editSolisPrefs { it.monthlyCapUsd = step(it.monthlyCapUsd, false) } }, { vm.editSolisPrefs { it.monthlyCapUsd = step(it.monthlyCapUsd, true) } })
    val threadCap = remember(rev) { vm.prefs.threadCapUsd.toDouble() }
    Stepper("Per task", usd(threadCap), "Default for new tasks", { vm.editSolisPrefs { it.threadCapUsd = step(it.threadCapUsd, false) } }, { vm.editSolisPrefs { it.threadCapUsd = step(it.threadCapUsd, true) } })
    Stepper("Background updates per day", usd(st.caps.narratorDailyUsd), null, { vm.editSolisPrefs { it.narratorCapUsd = step(it.narratorCapUsd, false) } }, { vm.editSolisPrefs { it.narratorCapUsd = step(it.narratorCapUsd, true) } })
    Text("Modes can add their own daily cap (Settings › Modes).", style = lStyle(12.sp, color = p.dim))
    SettingSwitch("Allow models with unknown prices", "Off: such models are skipped because their cost can't be capped", st.allowUnknownPrice) { v -> vm.editSolisPrefs { it.allowUnknownPrice = v } }

    SectionLabel("Recent calls")
    if (recent.isEmpty()) Text("No model calls yet.", style = lStyle(13.sp, color = p.dim))
    recent.forEach { e ->
        Text(
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(e.ts)) + " · ${e.role.name.lowercase()} · ${e.model.substringAfter('|')} · ${usd(e.costUsd)}" +
                (if (e.estimated) " (est.)" else "") + " · ${e.inputTokens}/${e.outputTokens} tok · " + (titles[e.threadId] ?: "deleted task"),
            style = lStyle(11.sp, color = p.dim), modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

// =============================================================== privacy & tasks

@Composable
fun PrivacySettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val rev = vm.settingsRevision
    val sharing = remember(rev) { vm.prefs.workReadsPersonal to vm.prefs.personalReadsWork }
    val maxConcurrent = remember(rev) { vm.prefs.maxConcurrent }
    val autoResume = remember(rev) { vm.prefs.autoResume }

    SectionLabel("Network")
    SettingSwitch("Offline mode", "Only a local runtime and on-device actions. Tasks that need the cloud wait.", vm.offline) { vm.updateOffline(it) }
    SettingSwitch("Web search", "Let tasks search the web when they need fresh information", vm.webSearch) { vm.updateWebSearch(it) }
    Text(
        "Requests go only to the provider serving them. OpenRouter requests ask providers not to collect data, and use zero-retention " +
            "endpoints in modes that require it (Work by default). Local-only modes never leave your network.",
        style = lStyle(12.sp, color = p.dim),
    )

    SectionLabel("Personal details")
    val redact = remember(rev) { vm.prefs.redactPersonal }
    SettingSwitch(
        "Hide personal details from cloud models",
        "Emails, phone numbers, card numbers and IBANs are sent as placeholders like ⟦EMAIL_1⟧ and filled back in on the phone, " +
            "so drafts and calls still use the real ones. Images and files are sent as they are. Applies to tasks started afterwards.",
        redact,
    ) { v -> vm.editSolisPrefs { it.redactPersonal = v } }
    Text("Each task's receipts (in the task's menu) show which model got what, and the exact text of the last request.",
        style = lStyle(12.sp, color = p.dim))

    SectionLabel("Lock")
    SettingSwitch("Lock Solis", "Fingerprint, face or screen lock before Solis, memory, tasks or settings open. Locks again when the screen " +
        "turns off or after five minutes away. Home and apps stay as they are.", vm.bioLock) { vm.updateBioLock(it) }

    SectionLabel("Memory sharing")
    SettingSwitch("Work tasks may read personal memory", null, sharing.first) { v -> vm.editSolisPrefs { it.workReadsPersonal = v } }
    SettingSwitch("Personal tasks may read work memory", null, sharing.second) { v -> vm.editSolisPrefs { it.personalReadsWork = v } }
    Text("A task's own notes are never visible to other tasks. Recalled memory is passed to models as evidence with its source and date, not as instructions.",
        style = lStyle(12.sp, color = p.dim))

    SectionLabel("Tasks")
    Stepper("Tasks running at once", "$maxConcurrent", "Fewer when battery is low, the phone is hot, or on metered data",
        { vm.editSolisPrefs { it.maxConcurrent = (it.maxConcurrent - 1).coerceIn(1, 6) } }, { vm.editSolisPrefs { it.maxConcurrent = (it.maxConcurrent + 1).coerceIn(1, 6) } })
    SettingSwitch("Resume after restarts", "Interrupted tasks continue from their last checkpoint without asking. Steps that already acted are never repeated.", autoResume) { v ->
        vm.editSolisPrefs { it.autoResume = v }
    }
}

// =============================================================== diagnostics

@Composable
fun DiagnosticsSettings(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val perf by produceState("…", refresh) { value = withContext(Dispatchers.IO) { PerfMonitor.report(ctx) } }
    val diag by produceState("…", refresh) { value = withContext(Dispatchers.IO) { vm.solis.diagnostics() } }
    Text("Measured on this phone while Axiom runs. Nothing here leaves the device unless you share it.", style = lStyle(13.sp, color = p.dim))
    SelfTestSection(vm)
    SectionLabel("Performance")
    Text(perf, style = lStyle(12.sp).copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp))
    SectionLabel("Solis")
    Text(diag, style = lStyle(12.sp).copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp))
    vm.solis.routineLog.value.takeIf { it.isNotEmpty() }?.let { log ->
        SectionLabel("Routines")
        log.forEach { Text(it, style = lStyle(11.sp, color = p.dim)) }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Refresh") { refresh++ }
        Chip("Copy") {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Axiom diagnostics", "$perf\n\n$diag"))
            vm.say("Diagnostics copied")
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Background tasks keep running through a foreground service with a visible notification; it stops as soon as nothing is active.",
        style = lStyle(12.sp, color = p.dim))
}

/** One tap checks everything Axiom depends on; each problem links to where it is fixed. */
@Composable
private fun SelfTestSection(vm: LauncherViewModel) {
    val p = LocalPalette.current
    SectionLabel("Self-test")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (vm.selfTestRunning) "Checking…" else if (vm.selfTest == null) "Run self-test" else "Run again", selected = true) { vm.runSelfTest() }
        Chip("Share report") { vm.shareDiagnostics() }
    }
    Text("The report has app and phone versions, these checks, performance, crashes and why Android last stopped Axiom. " +
        "It never includes keys, memories, conversations, notifications or contacts.", style = lStyle(12.sp, color = p.dim), modifier = Modifier.padding(top = 6.dp))
    val checks = vm.selfTest ?: return
    checks.groupBy { it.area }.forEach { (area, list) ->
        Text(area, style = lStyle(13.sp, FontWeight.Medium), modifier = Modifier.padding(top = 10.dp))
        list.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(com.axiom.launcher.solis.SelfTest.mark(c.status) + "  ", style = lStyle(14.sp, FontWeight.Bold,
                    when (c.status) {
                        com.axiom.launcher.solis.CheckStatus.FAIL -> p.danger
                        com.axiom.launcher.solis.CheckStatus.WARN -> p.accent
                        else -> p.dim
                    }))
                Text(c.text, style = lStyle(13.sp), modifier = Modifier.weight(1f))
                c.fix?.let { f -> Chip(if (f == com.axiom.launcher.solis.CheckFix.SHARE_REPORT) "Share" else "Fix") { vm.fix(f) } }
            }
        }
    }
}
