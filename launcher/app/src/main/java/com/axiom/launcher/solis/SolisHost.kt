package com.axiom.launcher.solis

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.axiom.launcher.LauncherCore
import com.axiom.launcher.LockService
import com.axiom.launcher.core.Calc
import com.axiom.launcher.core.Cmd
import com.axiom.launcher.core.CommandParser
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.core.Fuzzy
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.CalendarRepo
import com.axiom.launcher.data.FileRepository
import com.axiom.launcher.data.NotificationWatcher
import com.axiom.launcher.data.Note
import com.axiom.launcher.data.NotifItem
import com.axiom.launcher.modes.AiPrivacy
import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.modes.Routine
import com.axiom.launcher.modes.RoutineAction
import com.axiom.launcher.platform.AgentService
import com.axiom.launcher.platform.FallbackBrainStore
import com.axiom.launcher.platform.PerfMonitor
import com.axiom.launcher.platform.SecretStore
import com.axiom.launcher.platform.SqliteBrainStore
import com.axiom.launcher.solis.catalog.Freshness
import com.axiom.launcher.solis.cortex.BudgetCaps
import com.axiom.launcher.solis.cortex.BudgetLedger
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.engine.DeterministicHandler
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.engine.LivePhase
import com.axiom.launcher.solis.engine.ProviderRegistry
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SolisSettings
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadPermissions
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.llm.AnthropicProvider
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.LlmProvider
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.llm.OpenAiCompatProvider
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.MemoryService
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.SharingRules
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Assembles the Solis brain for the running app — durable store, inference providers (OpenRouter, a
 * direct Anthropic adapter, a local OpenAI-compatible runtime), memory, Cortex policy, budget ledger,
 * engine and phone tools — and connects it to the launcher: launcher modes shape each task's space,
 * privacy and budget; routines fire on minute ticks; the foreground service follows active work.
 */
class SolisHost(private val app: Application, private val core: LauncherCore) {
    private val prefs = core.prefs
    val secrets = SecretStore(app)

    private val _lastError = MutableStateFlow<String?>(null)
    /** Last unexpected background failure (shown in diagnostics instead of crashing the launcher). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val errors = CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "Solis background failure", e)
        _lastError.value = "${e.javaClass.simpleName}: ${e.message}"
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Gap allowed between streamed chunks; OpenRouter sends keep-alive comments while a model thinks.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val store = FallbackBrainStore({ SqliteBrainStore(app) }, { InMemoryBrainStore() })
    val registry = ProviderRegistry(store)
    val openRouter = OpenAiCompatProvider(
        OPENROUTER, "OpenRouter", { OpenAiCompatProvider.OPENROUTER_URL }, { secrets.get(SecretStore.OPENROUTER) },
        OpenAiCompatProvider.Flavor.OPENROUTER, http,
    )
    val anthropic = AnthropicProvider({ secrets.get(SecretStore.ANTHROPIC) })
    val local = OpenAiCompatProvider(
        LOCAL, "Local runtime", { prefs.localBaseUrl }, { secrets.get(SecretStore.LOCAL) },
        OpenAiCompatProvider.Flavor.LOCAL, http, { prefs.localTools }, { prefs.localContext },
    )
    val memory = MemoryService(store)
    val policies = PolicyBook(store)
    val ledger = BudgetLedger(store)
    val stats = StatsBook()
    val tools = PhoneTools(Host())

    @Volatile private var cachedSettings: SolisSettings? = null

    val engine: SolisEngine = SolisEngine(
        scope = scope, store = store, providers = registry, memory = memory, policies = policies, ledger = ledger,
        stats = stats, toolHost = tools, settings = ::settings, device = ::device,
        deterministic = DeterministicHandler { text, space -> deterministic(text, space) },
        contextLine = { DeviceInfo.contextLine(app) },
    )

    init {
        registry.register(openRouter)
        registry.register(anthropic)
        registry.register(local)
    }

    private val _memoryRevision = MutableStateFlow(0)
    val memoryRevision: StateFlow<Int> = _memoryRevision.asStateFlow()
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision.asStateFlow()
    private val _routineLog = MutableStateFlow<List<String>>(emptyList())
    val routineLog: StateFlow<List<String>> = _routineLog.asStateFlow()
    private val _pendingAsks = MutableStateFlow<List<Routine>>(emptyList())
    /** Routines that want to start AI work and wait for the user's OK (AI is never started silently). */
    val pendingAsks: StateFlow<List<Routine>> = _pendingAsks.asStateFlow()

    /** Settings that change routing or limits re-evaluate parked work. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        cachedSettings = null
        if (key in ROUTING_KEYS) engine.onConditionsChanged()
    }
    private val minuteTick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onMinute()
    }
    private var started = false
    @Volatile private var lastAutoRefresh = 0L
    /** "threadId:turn" -> when the user submitted it, for end-to-end latency. */
    private val turnStarted = ConcurrentHashMap<String, Long>()
    private val firstTokenSeen = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (started) return
        started = true
        prefs.listen(prefsListener)
        memory.addListener { _memoryRevision.update { it + 1 } }
        core.onConditionsChanged = {
            engine.onConditionsChanged()
            autoRefreshCatalogs()
        }
        engine.start()
        val f = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(minuteTick, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(minuteTick, f)
        scope.launch {
            engine.awaitStarted()
            refreshCatalogs(force = false)
        }
        // Keep running work alive in the background; brief on-device (T0) tasks never flash a notification.
        scope.launch {
            fun needsService() = engine.threads.value.any { it.status == ThreadStatus.RUNNING || it.status == ThreadStatus.QUEUED }
            engine.threads.map { list -> list.any { it.status == ThreadStatus.RUNNING || it.status == ThreadStatus.QUEUED } }.distinctUntilChanged().collect { active ->
                if (active) {
                    delay(1500)
                    if (needsService()) AgentService.start(app)
                }
            }
        }
        watchLatency()
        // A mode switch changes budgets, privacy and data-saver: re-plan waiting work.
        scope.launch {
            core.modes.active.drop(1).collect {
                cachedSettings = null
                engine.onConditionsChanged()
            }
        }
        // The Netrunner persona follows the theme for tasks started afterwards.
        scope.launch { core.theme.drop(1).collect { cachedSettings = null } }
        onMinute()
    }

    // =========================================================== settings & conditions

    fun settings(): SolisSettings = cachedSettings ?: buildSettings().also { cachedSettings = it }

    /** Drops cached settings now (the preference listener runs a moment later) and re-plans waiting work. */
    fun invalidateSettings() {
        cachedSettings = null
        engine.onConditionsChanged()
    }

    private fun buildSettings(): SolisSettings = SolisSettings(
        maxConcurrentThreads = prefs.maxConcurrent,
        caps = BudgetCaps(prefs.dailyCapUsd.toDouble(), prefs.monthlyCapUsd.toDouble(), prefs.narratorCapUsd.toDouble()),
        modeDailyCaps = core.modes.modes.value.mapNotNull { m -> m.aiDailyBudgetUsd?.let { m.id to it } }.toMap(),
        allowUnknownPrice = prefs.allowUnknownPrice,
        pinned = pinnedModels(),
        sharing = SharingRules(prefs.workReadsPersonal, prefs.personalReadsWork),
        offline = prefs.offlineMode,
        userName = prefs.userName,
        persona = if (core.effectiveTheme() == ThemeMode.NETRUNNER) NETRUNNER_PERSONA else null,
    )

    fun pinnedModels(): Map<CallRole, ModelRef> = prefs.pinnedModels.mapNotNull { (k, v) ->
        val role = runCatching { CallRole.valueOf(k) }.getOrNull()
        val ref = ModelRef.parse(v)
        if (role != null && ref != null) role to ref else null
    }.toMap()

    fun pinModel(role: CallRole, ref: ModelRef?) {
        prefs.pinnedModels = if (ref == null) prefs.pinnedModels - role.name else prefs.pinnedModels + (role.name to ref.key)
    }

    /** Device conditions as the Cortex sees them; a data-saver mode (Travel) counts the network as metered. */
    fun device(): DeviceSnapshot {
        val d = core.conditions.state.value
        return if (core.modes.active.value.dataSaver && !d.metered) d.copy(metered = true) else d
    }

    // =========================================================== tasks

    fun permissionsFor(mode: LauncherMode, maxCostUsd: Double? = null) = ThreadPermissions(
        tools = if (prefs.webSearch) ToolGroup.entries.toSet() else ToolGroup.entries.toSet() - ToolGroup.WEB,
        maxCostUsd = maxCostUsd ?: prefs.threadCapUsd.toDouble(),
        autoResume = prefs.autoResume,
        privacy = mode.aiPrivacy.constraint(),
    )

    /** Starts a task (or a follow-up in [threadId]) in the active mode's space, privacy and budget. */
    fun submit(
        text: String,
        parts: List<ContentPart> = emptyList(),
        threadId: String? = null,
        origin: String = "user",
        maxCostUsd: Double? = null,
        title: String? = null,
        dependsOn: List<String> = emptyList(),
    ): SubmitResult {
        val mode = core.modes.active.value
        if (mode.aiPrivacy == AiPrivacy.OFF) return SubmitResult.Rejected("Solis is off in ${mode.name} mode. On-device commands still work.")
        val r = engine.submit(text, mode.space, mode.id, threadId, parts, permissionsFor(mode, maxCostUsd), dependsOn, title, origin)
        if (r is SubmitResult.Accepted) engine.thread(r.threadId)?.let { turnStarted["${it.id}:${it.turn}"] = SystemClock.elapsedRealtime() }
        return r
    }

    /** Submit → first streamed token, and submit → done, measured where the user experiences them. */
    private fun watchLatency() {
        scope.launch {
            engine.live.collect { live ->
                live.forEach { (id, s) ->
                    if (s.phase != LivePhase.STREAMING) return@forEach
                    val t = engine.thread(id) ?: return@forEach
                    val k = "$id:${t.turn}"
                    val at = turnStarted[k] ?: return@forEach
                    if (firstTokenSeen.add(k)) PerfMonitor.recordLatency("first token", SystemClock.elapsedRealtime() - at)
                }
            }
        }
        scope.launch {
            engine.threads.collect { list ->
                list.forEach { t ->
                    val k = "${t.id}:${t.turn}"
                    val at = turnStarted[k] ?: return@forEach
                    if (t.isActive || t.status == ThreadStatus.PAUSED) return@forEach
                    turnStarted.remove(k)
                    firstTokenSeen.remove(k)
                    if (t.status == ThreadStatus.DONE) PerfMonitor.recordLatency("done (${t.tier?.name ?: "?"})", SystemClock.elapsedRealtime() - at)
                }
            }
        }
    }

    val anyProvider: Boolean get() = registry.all().any { it.isConfigured() }

    // =========================================================== providers

    fun keyName(providerId: String) = when (providerId) {
        OPENROUTER -> SecretStore.OPENROUTER
        ANTHROPIC -> SecretStore.ANTHROPIC
        else -> SecretStore.LOCAL
    }

    fun hasKey(providerId: String) = secrets.has(keyName(providerId))

    /** Saves (or with blank, removes) a provider credential, then fetches that provider's models. */
    suspend fun setKey(providerId: String, key: String?): String = withContext(Dispatchers.IO) {
        secrets.put(keyName(providerId), key)
        cachedSettings = null
        engine.onConditionsChanged()
        if (key.isNullOrBlank()) {
            if (providerId != LOCAL) registry.forget(providerId)
            _catalogRevision.update { it + 1 }
            "Key removed"
        } else testProvider(providerId)
    }

    /** Fetches the provider's current model list; the result doubles as a connection test. */
    suspend fun testProvider(providerId: String): String = withContext(Dispatchers.IO) {
        val p = registry.get(providerId) ?: return@withContext "Unknown provider"
        if (!p.isConfigured()) return@withContext "${p.displayName} isn't set up"
        val r = runCatching { registry.refresh(providerId) }
        _catalogRevision.update { it + 1 }
        engine.onConditionsChanged()
        r.fold({ "${p.displayName}: ${it.models.size} models available" }, { "${p.displayName}: ${it.message ?: it.javaClass.simpleName}" })
    }

    /** Refreshes configured providers' catalogs that are missing or stale (or all, when [force]). */
    suspend fun refreshCatalogs(force: Boolean): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        val d = device()
        for (p in registry.all()) {
            if (!p.isConfigured()) continue
            if (!force && registry.freshness(p.id) == Freshness.FRESH) continue
            if (!p.isLocal && (!d.online || prefs.offlineMode)) continue
            runCatching { registry.refresh(p.id) }
                .onSuccess { out += "${p.displayName}: ${it.models.size} models" }
                .onFailure { out += "${p.displayName}: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (out.isNotEmpty()) _catalogRevision.update { it + 1 }
        out
    }

    private fun autoRefreshCatalogs() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRefresh < 10 * 60_000L) return
        if (registry.all().none { it.isConfigured() && registry.freshness(it.id) != Freshness.FRESH }) return
        lastAutoRefresh = now
        scope.launch { refreshCatalogs(force = false) }
    }

    suspend fun openRouterKeyStatus(): OpenAiCompatProvider.KeyStatus? = withContext(Dispatchers.IO) {
        runCatching { openRouter.keyStatus() }.getOrNull()
    }

    fun provider(id: String): LlmProvider? = registry.get(id)

    // =========================================================== routines

    private fun onMinute() {
        if (core.modes.tick()) log("Temporary mode ended")
        core.modes.takeDueRoutines().forEach { runRoutine(it) }
    }

    /** Runs a routine now. AI routines need either their own opt-in or [confirmed] (the user tapped Run). */
    fun runRoutine(r: Routine, confirmed: Boolean = false) {
        when (val a = r.action) {
            is RoutineAction.SwitchMode -> log("${r.name}: " + if (core.modes.switch(a.modeId)) "switched to ${a.modeId}" else "no mode “${a.modeId}”")
            is RoutineAction.Command -> scope.launch(Dispatchers.IO) {
                val res = runCatching { deterministic(a.text, core.modes.active.value.space) }.getOrNull()
                log("${r.name}: " + (res ?: "“${a.text}” isn't an on-device command, so nothing ran"))
            }
            is RoutineAction.AskSolis -> if (a.allowAi || confirmed) {
                val res = submit(a.prompt, origin = "routine:${r.id}", maxCostUsd = a.budgetUsd, title = r.name)
                log("${r.name}: " + when (res) {
                    is SubmitResult.Accepted -> "started a Solis task (cap $${"%.2f".format(a.budgetUsd)})"
                    is SubmitResult.Rejected -> res.reason
                })
            } else {
                _pendingAsks.update { list -> list.filterNot { it.id == r.id } + r }
                log("${r.name}: waiting for your OK to ask Solis")
            }
        }
    }

    fun confirmAsk(r: Routine) {
        dismissAsk(r.id)
        runRoutine(r, confirmed = true)
    }

    fun dismissAsk(id: String) = _pendingAsks.update { list -> list.filterNot { it.id == id } }

    private fun log(line: String) {
        val t = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date())
        _routineLog.update { (listOf("$t  $line") + it).take(30) }
    }

    // =========================================================== T0: on-device handling

    /**
     * Commands and quick lookups answered on the device with no model and no cost. Returns null when the
     * request needs a model. Anything ambiguous (an unknown contact, a long web query) goes to a model.
     */
    suspend fun deterministic(text: String, space: Space): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160 || t.contains('\n')) return null
        Calc.tryEval(t)?.let { return "$t = **$it**" }
        quickLookup(t)?.let { return it }
        CommandParser.parse(t)?.let { cmd -> return if (CommandParser.isCompound(t, cmd)) null else runCommand(cmd, space) }
        modeCommand(t)?.let { return it }
        return openAppCommand(t, space)
    }

    private fun quickLookup(t: String): String? {
        val l = t.lowercase().trimEnd('?', '!', '.').trim()
        val clock = if (prefs.clock24) "HH:mm" else "h:mm a"
        return when (l) {
            "time", "what time is it", "what's the time", "whats the time", "the time" ->
                "It's ${SimpleDateFormat(clock, Locale.getDefault()).format(Date())}."
            "date", "what's the date", "whats the date", "what day is it", "today's date", "todays date", "what's today's date" ->
                "Today is ${SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(Date())}."
            "battery", "battery level", "how much battery", "how much battery do i have", "how's my battery", "hows my battery" -> {
                val b = DeviceInfo.battery(app)
                "Battery ${b.percent}%" + if (b.charging) ", charging." else "."
            }
            "storage", "free space", "how much storage", "how much space do i have", "storage left" -> {
                val (free, total) = DeviceInfo.storage()
                "${DeviceInfo.human(free)} free of ${DeviceInfo.human(total)}."
            }
            "next alarm", "when is my alarm", "when's my alarm", "whens my alarm", "when is my next alarm" ->
                DeviceInfo.nextAlarm(app)?.let { "Next alarm: ${SimpleDateFormat("EEE $clock", Locale.getDefault()).format(Date(it))}." } ?: "No alarm set."
            else -> null
        }
    }

    private fun modeCommand(t: String): String? {
        val l = t.lowercase().trim()
        val m = Regex("^(?:switch to |enter |go to |activate |turn on )?(?:the )?(.+?) mode$").find(l) ?: Regex("^mode (.+)$").find(l) ?: return null
        val name = m.groupValues[1].trim()
        val mode = core.modes.modes.value.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) } ?: return null
        core.modes.switch(mode.id)
        return "${mode.name} mode is on."
    }

    private fun openAppCommand(t: String, space: Space): String? {
        val m = Regex("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val q = m.groupValues[1]
        if (Regex("\\b(and|then)\\b").containsMatchIn(q)) return null
        val best = core.findApps(q, core.appsFor(space), 1).firstOrNull() ?: return null
        if (Fuzzy.score(q, best.label) < 600) return null
        if (!best.runnable) return "${best.label} can't open right now: " + if (best.paused) "its profile is paused. Turn work apps on first." else "the app is suspended."
        return if (core.launch(best)) "Opened ${best.label}." else "Couldn't open ${best.label}."
    }

    private fun contactNumber(target: String): String? =
        if (target.any { it.isDigit() } && target.none { it.isLetter() }) target
        else core.files.searchContacts(target, 1).firstOrNull()?.number

    /**
     * Executes one parsed command and returns a confirmation. Null = not a device action after all (let a
     * model handle it). May query contacts or write memory: call off the main thread.
     */
    fun runCommand(cmd: Cmd, space: Space): String? {
        val a = core.actions
        fun done(ok: Boolean, yes: String, no: String) = if (ok) yes else no
        return when (cmd) {
            is Cmd.Timer -> done(a.setTimer(cmd.seconds, cmd.label), "Timer set · ${Cmd.fmtDur(cmd.seconds)}.", "No clock app accepted the timer.")
            is Cmd.Alarm -> done(a.setAlarm(cmd.hour, cmd.minute, cmd.label), "Alarm set · %02d:%02d.".format(cmd.hour, cmd.minute), "No clock app accepted the alarm.")
            Cmd.Alarms -> done(a.showAlarms(), "Opened your alarms.", "No clock app found.")
            is Cmd.Call -> contactNumber(cmd.target)?.let { n -> done(a.dial(n), "Dialer ready for ${cmd.target}. Press call to connect.", "Couldn't open the dialer.") }
            is Cmd.Text -> contactNumber(cmd.target)?.let { n -> done(a.sms(n, cmd.body), "Message draft for ${cmd.target} is open. Press send when ready.", "No messaging app found.") }
            is Cmd.Email -> done(a.email(cmd.to, null, null), "Email draft to ${cmd.to} is open.", "No email app found.")
            is Cmd.Torch -> done(a.toggleTorch(cmd.on), "Flashlight ${if (a.torchOn) "on" else "off"}.", "This phone has no flashlight.")
            is Cmd.Setting -> done(a.openSettings(cmd.panel), "Opened ${cmd.panel} settings.", "Couldn't open settings.")
            is Cmd.Note -> { core.addNote(cmd.text); "Saved to your notes." }
            is Cmd.Remember -> {
                memory.remember(cmd.fact, MemoryKind.FACT, Scope(space), source = "you", confidence = 0.95)
                "I'll remember that (${space.name.lowercase()} memory)."
            }
            // A short query is a browser search; a long one is research, which is a model's job.
            is Cmd.Web -> if (cmd.query.split(' ').size <= 6) done(a.webSearch(cmd.query), "Searching the web for “${cmd.query}”.", "No browser found.") else null
            is Cmd.Navigate -> done(a.navigate(cmd.dest), "Maps is open for ${cmd.dest}.", "No maps app found.")
            is Cmd.Play -> done(a.playMedia(cmd.query), "Asked your music app to play ${cmd.query}.", "No music app handled that.")
            is Cmd.Focus -> if (cmd.minutes > 0) {
                core.modes.startTemporary(FOCUS_MODE, cmd.minutes)
                "Focus mode for ${cmd.minutes} min."
            } else {
                core.modes.stopFocus()
                "Focus mode is off."
            }
            is Cmd.Theme -> { core.setTheme(cmd.mode); "Theme: ${cmd.mode.title}." }
            is Cmd.Url -> done(a.openUrl(cmd.url), "Opened ${cmd.url}.", "No browser found.")
            Cmd.Camera -> done(a.camera(), "Camera is open.", "No camera app found.")
            Cmd.Calendar -> done(a.openCalendar(), "Calendar is open.", "No calendar app found.")
            Cmd.Notifications -> done(a.expandNotifications(), "Notifications are open.", "Couldn't open notifications.")
            Cmd.QuickSettings -> done(a.expandQuickSettings(), "Quick settings are open.", "Couldn't open quick settings.")
            Cmd.Neofetch -> "```\n" + DeviceInfo.neofetch(app, prefs.userName) + "\n```"
            Cmd.Matrix -> "Wake up… Type `matrix` in the home search bar to see it."
            Cmd.Lock -> done(LockService.lock(), "Locked.", "Locking needs the Axiom accessibility service (Settings › Permissions).")
            is Cmd.Media -> { a.mediaKey(cmd.action); "Media: ${cmd.action}." }
            is Cmd.Volume -> "Media volume ${a.setVolume(cmd.percent, cmd.delta)}%."
            Cmd.Agenda -> "Today:\n" + calendarText(1)
            Cmd.Brief, Cmd.Digest -> null
        }
    }

    // =========================================================== shared text helpers

    fun calendarText(days: Int): String {
        if (!CalendarRepo.hasPermission(app)) return "Calendar permission isn't granted (Settings › Permissions)."
        val ev = CalendarRepo.upcoming(app, days, 40)
        if (ev.isEmpty()) return "No events in the next $days day(s)."
        val day = SimpleDateFormat("EEE d MMM", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        return ev.joinToString("\n") { e ->
            (if (e.allDay) "${day.format(Date(e.begin))} (all day)" else "${day.format(Date(e.begin))} ${time.format(Date(e.begin))}–${time.format(Date(e.end))}") +
                " · ${e.title}" + (e.location?.let { " @ $it" } ?: "")
        }
    }

    /**
     * Notifications visible to a task in [space]. Work-profile notifications reach only work tasks;
     * personal ones reach work tasks only when "work may read personal" sharing is on.
     */
    fun notificationsText(space: Space): String {
        if (!NotificationWatcher.connected()) return "Notification access is off. The user can enable it in Axiom settings › Permissions."
        val all = NotificationWatcher.snapshot()
        val visible = visibleNotifications(all, space, prefs.workReadsPersonal)
        val hidden = all.size - visible.size
        val f = SimpleDateFormat("HH:mm", Locale.US)
        val body = visible.joinToString("\n") { "[${f.format(Date(it.time))}] ${it.app}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}" }
            .ifEmpty { "No notifications." }
        return if (hidden > 0) "$body\n($hidden notification(s) from the other profile are outside this task's space.)" else body
    }

    fun diagnostics(): String = buildString {
        appendLine("store: ${if (store.persistent) "SQLite (durable)" else "in-memory · ${store.openError}"}")
        appendLine("engine: ${engine.debugInfo()}")
        registry.all().forEach { p ->
            appendLine("provider ${p.id}: ${if (p.isConfigured()) "configured" else "not set up"} · catalog ${registry.freshness(p.id).name.lowercase()} · ${registry.catalog.snapshot(p.id)?.models?.size ?: 0} models")
        }
        appendLine("memory: ${memory.size} items · policy v${policies.active().version}")
        appendLine("device: ${device()}")
        appendLine("pid ${Process.myPid()} · heap ${DeviceInfo.human(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())}")
        lastError.value?.let { appendLine("last error: $it") }
    }

    // =========================================================== phone tool host

    private inner class Host : PhoneHost {
        override val context: Context get() = app
        override val actions get() = core.actions
        override val files: FileRepository get() = core.files
        override fun apps(space: Space): List<AppInfo> = core.appsFor(space)
        override fun findApps(query: String, space: Space, limit: Int) = core.findApps(query, core.appsFor(space), limit)
        override fun launch(app: AppInfo): Boolean = app.runnable && core.launch(app)
        override fun notes(): List<Note> = core.notes.value
        override fun addNote(text: String) { core.addNote(text) }
        override fun deleteNote(id: Long) = core.deleteNote(id)
        override fun setTheme(mode: ThemeMode) = core.setTheme(mode)
        override fun modes(): List<LauncherMode> = core.modes.modes.value
        override fun setMode(id: String) = core.modes.switch(id)
        override fun startFocus(minutes: Int) = if (minutes > 0) core.modes.startTemporary(FOCUS_MODE, minutes) else core.modes.stopFocus()
        override fun lockScreen() = LockService.lock()
        override fun calendarText(days: Int) = this@SolisHost.calendarText(days)
        override fun notificationsText(space: Space) = this@SolisHost.notificationsText(space)
    }

    companion object {
        private const val TAG = "Solis"

        /** The profile boundary for notifications: work-profile ones only for work tasks; personal ones for work only if shared. */
        fun visibleNotifications(all: List<NotifItem>, space: Space, workReadsPersonal: Boolean): List<NotifItem> = all.filter { n ->
            when (space) {
                Space.PERSONAL -> !n.otherProfile
                Space.WORK -> n.otherProfile || workReadsPersonal
            }
        }

        const val OPENROUTER = "openrouter"
        const val ANTHROPIC = "anthropic"
        const val LOCAL = "local"
        const val FOCUS_MODE = "focus"
        private val ROUTING_KEYS = setOf(
            "offline", "local_url", "local_tools", "local_ctx", "cap_day", "cap_month", "cap_narrator", "cap_thread",
            "unknown_price", "max_concurrent", "pinned_models", "modes", "active_mode", "share_w_p", "share_p_w",
        )
        const val NETRUNNER_PERSONA = "Style: the user chose the Netrunner theme. Keep a terse, playful terminal tone " +
            "(lowercase is fine, a little hacker flavor), but stay clear, accurate and kind."
    }
}
