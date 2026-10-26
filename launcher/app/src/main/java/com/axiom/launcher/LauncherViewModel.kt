package com.axiom.launcher

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.UserHandle
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.axiom.launcher.core.Actions
import com.axiom.launcher.core.Calc
import com.axiom.launcher.core.Cmd
import com.axiom.launcher.core.CommandParser
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.core.Fuzzy
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.CalEvent
import com.axiom.launcher.data.CalendarRepo
import com.axiom.launcher.data.ContactHit
import com.axiom.launcher.data.FileHit
import com.axiom.launcher.data.Note
import com.axiom.launcher.data.NotificationWatcher
import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.modes.Routine
import com.axiom.launcher.modes.WorkApps
import com.axiom.launcher.platform.ProfileInfo
import com.axiom.launcher.platform.ProfileKind
import com.axiom.launcher.solis.SolisHost
import com.axiom.launcher.solis.coding.CodingJob
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.DecisionMatrix
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.LiveState
import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SubmitResult
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.memory.MemoryActivity
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val PAGE_FILES = 0
const val PAGE_HOME = 1
const val PAGE_SOLIS = 2

/** Full-screen layers above the pager. */
enum class Overlay { NONE, DRAWER, SEARCH, SETTINGS, TASKS, MEMORY }

sealed class Hit(val key: String) {
    class App(val app: AppInfo) : Hit("app:" + app.key)
    class Command(val cmd: Cmd) : Hit("cmd:" + cmd.title)
    class Calc(val expr: String, val result: String) : Hit("calc")
    class Contact(val c: ContactHit) : Hit("contact:" + c.name)
    class FileItem(val f: FileHit) : Hit("file:" + f.path)
    class Setting(val title: String, val panel: String) : Hit("setting:$panel")
    class NoteItem(val note: Note) : Hit("note:" + note.id)
    class Thread(val thread: AgentThread) : Hit("thread:" + thread.id)
    class Memory(val item: MemoryItem) : Hit("mem:" + item.id)
    class Mode(val mode: LauncherMode) : Hit("mode:" + mode.id)
    class Palette(val cmd: PaletteCmd) : Hit("pal:" + cmd.id)
    class Ask(val q: String) : Hit("ask")
    class Web(val q: String) : Hit("web")
    /** "code: …" — hand the text straight to Claude Code as an issue in the default repository (no model). */
    class Code(val spec: String) : Hit("code")
}

/** A command-palette entry (omnibox input starting with ">"). */
class PaletteCmd(val id: String, val title: String, val hint: String, val run: () -> Unit)

/** "code: fix the login crash", "claude code: …", "cc: …" */
private val CODE_PREFIX = Regex("^(?:code|cc|claude code)\\s*:\\s*(.+)$|^claude code\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

const val BRIEF_PROMPT = "Give me my brief for right now: agenda, anything notable in my notifications and notes, battery, and one suggestion for what to focus on. Keep it short."
const val DIGEST_PROMPT = "Summarize my notifications: group by app, surface anything that needs a reply or action first, and skip the noise."

class Attachment(val name: String, val path: String, val parts: List<ContentPart>)

/**
 * UI adapter: mirrors the app-scoped [LauncherCore] and [SolisHost] state as Compose state and turns
 * user gestures into calls on them. Holds no state of its own that must outlive the Activity.
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val axiom = app as AxiomApp
    val core: LauncherCore = axiom.core
    val solis: SolisHost = axiom.solis
    val engine: SolisEngine get() = solis.engine
    val prefs = core.prefs
    val actions = core.actions
    val files = core.files
    private val ctx: Application get() = getApplication()

    // ---- persisted settings, mirrored as Compose state ----
    var userTheme by mutableStateOf(core.theme.value); private set
    var userName by mutableStateOf(prefs.userName); private set
    var showIcons by mutableStateOf(prefs.showIcons); private set
    var monoIcons by mutableStateOf(prefs.monoIcons); private set
    var clock24 by mutableStateOf(prefs.clock24); private set
    var speakReplies by mutableStateOf(prefs.speakReplies); private set
    var effects by mutableStateOf(prefs.effects); private set
    var autoKeyboard by mutableStateOf(prefs.autoKeyboard); private set
    var engineMode by mutableStateOf(prefs.engine); private set
    var webSearch by mutableStateOf(prefs.webSearch); private set
    var onboarded by mutableStateOf(prefs.onboarded); private set
    var reducedMotionPref by mutableStateOf(prefs.reducedMotion); private set
    var offline by mutableStateOf(prefs.offlineMode); private set
    var providersConfigured by mutableStateOf(false); private set
    /** Bumped when Solis settings change, so settings screens re-read values that live outside Compose. */
    var settingsRevision by mutableIntStateOf(0); private set

    // ---- launcher state ----
    var apps by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var notes by mutableStateOf(core.notes.value); private set
    var mode by mutableStateOf(core.modes.active.value); private set
    var modes by mutableStateOf(core.modes.modes.value); private set
    var tempUntil by mutableLongStateOf(core.modes.tempUntil.value); private set
    var routines by mutableStateOf(core.modes.routines.value); private set
    var profiles by mutableStateOf<List<ProfileInfo>>(emptyList()); private set
    var device by mutableStateOf(DeviceSnapshot()); private set
    var systemReducedMotion by mutableStateOf(false); private set
    var hidden by mutableStateOf(prefs.hidden); private set
    private var globalFavorites by mutableStateOf(prefs.favorites)
    var distracting by mutableStateOf(prefs.distracting); private set
    var suggestions by mutableStateOf<List<AppInfo>>(emptyList()); private set

    /** The mode may override the user's theme (e.g. a dark Work mode). */
    val theme: ThemeMode get() = mode.theme ?: userTheme
    val focusActive: Boolean get() = mode.blockDistracting
    val reducedMotion: Boolean get() = reducedMotionPref == "on" || (reducedMotionPref == "system" && systemReducedMotion)
    val workProfile: ProfileInfo? get() = profiles.firstOrNull { it.kind == ProfileKind.WORK }

    // ---- Solis ----
    var threads by mutableStateOf<List<AgentThread>>(emptyList()); private set
    var live by mutableStateOf<Map<String, LiveState>>(emptyMap()); private set
    var mood by mutableStateOf(Mood.IDLE); private set
    var memoryRevision by mutableIntStateOf(0); private set
    var catalogRevision by mutableIntStateOf(0); private set
    var pendingAsks by mutableStateOf<List<Routine>>(emptyList()); private set
    /** Voice capture is open: the face shows LISTENING only while that is really true. */
    var listening by mutableStateOf(false)
    var selectedThread by mutableStateOf<String?>(null)
    var solisDraft by mutableStateOf("")
    var pendingAttachment by mutableStateOf<Attachment?>(null)
    var memoryFocus by mutableStateOf<String?>(null)
    /** Memory overlay shows the brain graph instead of the list. */
    var brainView by mutableStateOf(false)
    /** Bumped by the Quick Settings tile: open voice capture on the Solis page. */
    var voiceRequest by mutableIntStateOf(0)
    var codingJobs by mutableStateOf<List<CodingJob>>(emptyList()); private set
    var brainActivity by mutableStateOf<List<MemoryActivity>>(emptyList()); private set
    var githubConnected by mutableStateOf(false); private set
    var codingSetup by mutableStateOf<List<String>>(emptyList()); private set
    val face: Mood get() = if (listening) Mood.LISTENING else mood
    private val speakOnDone = HashSet<String>()

    // ---- transient UI state ----
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Hit>>(emptyList()); private set
    private var lastSearched = ""
    var toast by mutableStateOf<String?>(null)
    var terminalOutput by mutableStateOf<String?>(null)
    var matrixMode by mutableStateOf(false)
    var bootSequence by mutableStateOf(false)
    var pageRequest by mutableStateOf<Int?>(null)
    var overlayRequest by mutableStateOf<Overlay?>(null)
    var settingsSection by mutableStateOf<String?>(null)
    var modeSheet by mutableStateOf(false)
    var focusBlocked by mutableStateOf<AppInfo?>(null)
    var pausedApp by mutableStateOf<AppInfo?>(null)
    var needsLockSetup by mutableStateOf(false)
    var filesPath by mutableStateOf<String?>(null)
    var nextEvent by mutableStateOf<CalEvent?>(null); private set
    var notifCount by mutableIntStateOf(0); private set

    private val claudeAppInstalled = actions.isClaudeAppInstalled()
    private var tts: TextToSpeech? = null

    init {
        val s = viewModelScope
        s.launch { core.theme.collect { userTheme = it } }
        s.launch { core.apps.collect { apps = it; refreshSuggestions() } }
        s.launch { core.notes.collect { notes = it } }
        s.launch { core.modes.active.collect { mode = it; refreshSuggestions(); refreshGlance() } }
        s.launch { core.modes.modes.collect { modes = it } }
        s.launch { core.modes.tempUntil.collect { tempUntil = it } }
        s.launch { core.modes.routines.collect { routines = it } }
        s.launch { core.profiles.profiles.collect { profiles = it } }
        s.launch { core.conditions.state.collect { device = it } }
        s.launch { core.conditions.reducedMotion.collect { systemReducedMotion = it } }
        s.launch { engine.threads.collect { onThreads(it) } }
        s.launch { engine.live.collect { live = it } }
        s.launch { engine.mood.collect { mood = it } }
        s.launch { solis.memoryRevision.collect { memoryRevision = it } }
        // Skip the initial value: the first key read decrypts via the Keystore, which happens on IO below.
        s.launch { solis.catalogRevision.drop(1).collect { catalogRevision = it; providersConfigured = solis.anyProvider } }
        s.launch { solis.pendingAsks.collect { pendingAsks = it } }
        s.launch { solis.coding.jobs.collect { codingJobs = it } }
        s.launch { solis.brainActivity.collect { brainActivity = it } }
        s.launch {
            var last = theme
            snapshotFlow { theme }.collect { t ->
                if (t == ThemeMode.NETRUNNER && last != ThemeMode.NETRUNNER && effects && !reducedMotion && onboarded) bootSequence = true
                last = t
            }
        }
        s.launch(Dispatchers.IO) {
            val configured = solis.anyProvider
            val gh = solis.githubConnected
            withContext(Dispatchers.Main) { providersConfigured = configured; githubConnected = gh }
        }
        observeQuery()
        s.launch(Dispatchers.IO) { runCatching { files.ensureIndex() } }
        refreshGlance()
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    /** Used by screenshot tests, which have no real launcher apps. */
    fun debugSetApps(list: List<AppInfo>, favs: List<String>) {
        core.debugSetApps(list)
        apps = list
        globalFavorites = favs
        refreshSuggestions()
    }

    // ================= apps =================

    /** Apps for lists and search in this mode: hidden apps out, work apps per the mode's presentation. */
    fun visibleApps(): List<AppInfo> {
        val base = apps.filter { it.key !in hidden }
        return when (mode.workApps) {
            WorkApps.ALL -> base
            WorkApps.WORK_FIRST -> base.sortedByDescending { it.profile == ProfileKind.WORK }
            WorkApps.HIDE -> base.filter { it.profile != ProfileKind.WORK }
            WorkApps.ONLY -> base.filter { it.profile == ProfileKind.WORK || it.profile == ProfileKind.PRIVATE }
        }
    }

    val favoriteKeys: List<String> get() = mode.favorites ?: globalFavorites

    fun favoriteApps(): List<AppInfo> {
        val byKey = apps.associateBy { it.key }
        return favoriteKeys.mapNotNull { byKey[it] }.filter { it.profile != ProfileKind.PRIVATE }
    }

    fun icon(app: AppInfo, size: Int): Bitmap? = core.repo.icon(app, size)

    fun findApps(query: String, limit: Int): List<AppInfo> = core.findApps(query, visibleApps(), limit)

    fun isDistracting(app: AppInfo): Boolean = distracting?.let { app.key in it } ?: core.isDistracting(app)

    fun open(app: AppInfo, force: Boolean = false) {
        if (!force && focusActive && isDistracting(app)) { focusBlocked = app; return }
        if (app.paused) { pausedApp = app; return }
        if (!core.launch(app)) say("Couldn't open ${app.label}")
        query = ""
        refreshSuggestions()
    }

    fun refreshSuggestions() {
        suggestions = if (!mode.showSuggestions) emptyList()
        else core.suggestions(visibleApps().filter { it.profile != ProfileKind.PRIVATE }, favoriteKeys.toSet())
    }

    fun toggleFavorite(app: AppInfo) {
        val cur = favoriteKeys
        val next = if (app.key in cur) cur - app.key else cur + app.key
        setFavorites(next)
    }

    fun moveFavorite(app: AppInfo, delta: Int) {
        val l = favoriteKeys.toMutableList()
        val i = l.indexOf(app.key)
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i < 0 || i == j) return
        l.removeAt(i); l.add(j, app.key)
        setFavorites(l)
    }

    /** A mode with its own favorites keeps them; otherwise the shared list changes. */
    private fun setFavorites(list: List<String>) {
        if (mode.favorites != null) core.modes.update(mode.copy(favorites = list))
        else { globalFavorites = list; prefs.favorites = list }
        refreshSuggestions()
    }

    fun toggleHidden(app: AppInfo) {
        hidden = if (app.key in hidden) hidden - app.key else hidden + app.key
        prefs.hidden = hidden
        if (app.key in hidden && app.key in favoriteKeys) toggleFavorite(app)
    }

    fun rename(app: AppInfo, newName: String) {
        val m = prefs.renames.toMutableMap()
        if (newName.isBlank() || newName == app.originalLabel) m.remove(app.key) else m[app.key] = newName.trim()
        prefs.renames = m
        core.reloadApps()
    }

    fun appInfo(app: AppInfo) = runCatching { core.repo.appDetails(app) }
    fun uninstall(app: AppInfo) = actions.uninstall(app.packageName)

    fun toggleDistracting(app: AppInfo) {
        val base = distracting ?: visibleApps().filter { core.isDistracting(it) }.map { it.key }.toSet()
        val next = if (app.key in base) base - app.key else base + app.key
        distracting = next
        prefs.distracting = next
    }

    // ================= profiles =================

    /** Asks the system to pause/resume the work profile; Android may first ask for the work challenge. */
    fun setWorkPaused(paused: Boolean) {
        val wp = workProfile ?: return
        val ok = core.profiles.setPaused(wp.user, paused)
        if (!ok && !paused) say("Android will ask you to unlock work apps")
        core.reloadApps()
    }

    fun profileOf(user: UserHandle): ProfileInfo? = profiles.firstOrNull { it.user == user }

    // ================= modes & routines =================

    fun switchMode(id: String) {
        if (core.modes.switch(id)) say("${core.modes.mode(id)?.name} mode")
    }

    fun startFocus(minutes: Int) {
        if (minutes > 0) { core.modes.startTemporary(SolisHost.FOCUS_MODE, minutes); say("Focus · $minutes min") }
        else { core.modes.stopFocus(); say("Focus off") }
    }

    fun updateMode(m: LauncherMode) {
        core.modes.update(m)
        solis.invalidateSettings()
        refreshSuggestions()
    }

    fun resetModes() {
        core.modes.resetModes()
        solis.invalidateSettings()
    }
    fun saveRoutine(r: Routine) = core.modes.saveRoutine(r)
    fun deleteRoutine(id: String) = core.modes.deleteRoutine(id)
    fun runRoutine(r: Routine) = solis.runRoutine(r, confirmed = true)
    fun confirmAsk(r: Routine) = solis.confirmAsk(r)
    fun dismissAsk(id: String) = solis.dismissAsk(id)

    // ================= settings =================

    fun setTheme(mode: ThemeMode) {
        core.setTheme(mode)
        if (this.mode.theme != null && this.mode.theme != mode) say("${this.mode.name} mode uses its own theme")
    }

    fun updateName(v: String) { userName = v; prefs.userName = v; solis.invalidateSettings() }
    fun updateShowIcons(v: Boolean) { showIcons = v; prefs.showIcons = v }
    fun updateMonoIcons(v: Boolean) { monoIcons = v; prefs.monoIcons = v }
    fun updateClock24(v: Boolean) { clock24 = v; prefs.clock24 = v }
    fun updateSpeak(v: Boolean) { speakReplies = v; prefs.speakReplies = v }
    fun updateEffects(v: Boolean) { effects = v; prefs.effects = v }
    fun updateAutoKeyboard(v: Boolean) { autoKeyboard = v; prefs.autoKeyboard = v }
    fun updateEngine(v: String) { engineMode = v; prefs.engine = v }
    fun updateWebSearch(v: Boolean) { webSearch = v; prefs.webSearch = v }
    fun updateReducedMotion(v: String) { reducedMotionPref = v; prefs.reducedMotion = v }
    fun finishOnboarding() { onboarded = true; prefs.onboarded = true }
    fun resetOnboarding() { onboarded = false; prefs.onboarded = false }

    fun updateOffline(v: Boolean) {
        offline = v
        prefs.offlineMode = v
        solis.invalidateSettings()
        say(if (v) "Offline mode: only local models and on-device actions" else "Online")
    }

    /** Edits Solis settings stored in prefs, then refreshes screens that show them. */
    fun editSolisPrefs(block: (com.axiom.launcher.data.Prefs) -> Unit) {
        block(prefs)
        solis.invalidateSettings()
        settingsRevision++
    }

    fun saveKey(providerId: String, key: String?) {
        viewModelScope.launch {
            say(if (key.isNullOrBlank()) "Removing key…" else "Checking key…")
            val msg = solis.setKey(providerId, key)
            providersConfigured = solis.anyProvider
            settingsRevision++
            say(msg)
        }
    }

    fun saveLocalRuntime(url: String, tools: Boolean, contextTokens: Int, key: String?) {
        prefs.localBaseUrl = url
        prefs.localTools = tools
        prefs.localContext = contextTokens
        viewModelScope.launch {
            if (key != null) solis.secrets.put(solis.keyName(SolisHost.LOCAL), key)
            providersConfigured = solis.anyProvider
            settingsRevision++
            say(if (url.isBlank()) "Local runtime removed" else solis.testProvider(SolisHost.LOCAL))
        }
    }

    fun refreshCatalogs() {
        viewModelScope.launch {
            say("Refreshing model catalogs…")
            val r = solis.refreshCatalogs(force = true)
            say(r.joinToString(" · ").ifEmpty { "No provider is set up yet" })
        }
    }

    fun pinModel(role: CallRole, ref: ModelRef?) {
        solis.pinModel(role, ref)
        solis.invalidateSettings()
        settingsRevision++
    }

    fun applyMatrix(m: DecisionMatrix, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val v = solis.policies.applyUserEdit(m, note)
            withContext(Dispatchers.Main) { settingsRevision++; say("Cortex policy v${v.version} is active") }
        }
    }

    fun rollbackPolicy(version: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { solis.policies.rollbackTo(version) }
            withContext(Dispatchers.Main) {
                settingsRevision++
                say(r.fold({ "Restored v$version as v${it.version}" }, { it.message ?: "Rollback failed" }))
            }
        }
    }

    // ================= notes =================

    fun addNote(text: String) { core.addNote(text) }
    fun deleteNote(id: Long) = core.deleteNote(id)
    fun togglePin(note: Note) = core.togglePin(note.id)

    fun lockScreen(): Boolean {
        val ok = LockService.lock()
        if (!ok) needsLockSetup = true
        return ok
    }

    // ================= omnibox search =================

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeQuery() {
        viewModelScope.launch {
            snapshotFlow { query }
                .debounce(60)
                .mapLatest { q -> q to withContext(Dispatchers.IO) { search(q) } }
                .collect { (q, r) -> lastSearched = q.trim(); results = r }
        }
    }

    private fun search(raw: String, light: Boolean = false): List<Hit> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        if (q.startsWith(">")) return palette(q.removePrefix(">").trim()).map { Hit.Palette(it) }
        val out = ArrayList<Hit>()
        CODE_PREFIX.find(q)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { spec -> out += Hit.Code(spec.trim()) } }
        Calc.tryEval(q)?.let { out += Hit.Calc(q, it) }
        val cmd = CommandParser.parse(q)
        // "wake me at 6:30 and remind me to pack": running only the alarm would drop half the request.
        if (cmd != null && CommandParser.isCompound(q, cmd)) out += Hit.Ask(q)
        if (cmd != null) out += Hit.Command(cmd)
        val question = CommandParser.looksLikeQuestion(q)
        val appHits = findApps(q, 6)
        val strongApp = appHits.firstOrNull()?.let { Fuzzy.score(q, it.label) >= 600 } == true
        if (question && !strongApp && cmd == null) out += Hit.Ask(q)
        appHits.forEach { out += Hit.App(it) }
        threads.asSequence().filter { Fuzzy.score(q, it.title) >= 450 }.take(2).forEach { out += Hit.Thread(it) }
        modes.filter { it.id != mode.id && (Fuzzy.score(q, it.name) >= 800 || Fuzzy.score(q, it.name + " mode") >= 800) }.forEach { out += Hit.Mode(it) }
        if (!light) files.searchContacts(q, 3).forEach { out += Hit.Contact(it) }
        Actions.SETTINGS_TITLES.filter { Fuzzy.score(q, it.first) >= 450 }.take(2).forEach { out += Hit.Setting(it.first, it.second) }
        notes.filter { it.text.contains(q, ignoreCase = true) }.take(2).forEach { out += Hit.NoteItem(it) }
        if (!light && q.length >= 3) {
            // Local-first: Solis memory in the active mode's space, never other threads' private notes.
            val scopes = solis.settings().sharing.readable(mode.space, null)
            solis.memory.search(q, scopes, limit = 2, minScore = 0.2).forEach { out += Hit.Memory(it.item) }
            files.search(q, 5).forEach { out += Hit.FileItem(it) }
        }
        if (out.none { it is Hit.Ask }) out += Hit.Ask(q)
        out += Hit.Web(q)
        return out
    }

    /** Launcher-wide actions for the ">" command palette. */
    fun palette(filter: String): List<PaletteCmd> {
        val running = threads.any { it.status == ThreadStatus.RUNNING || it.status == ThreadStatus.QUEUED || it.status == ThreadStatus.WAITING }
        val resumable = threads.any { it.status == ThreadStatus.PAUSED || it.status == ThreadStatus.INTERRUPTED }
        val all = buildList {
            add(PaletteCmd("new", "New Solis task", "solis") { selectedThread = null; pageRequest = PAGE_SOLIS })
            add(PaletteCmd("tasks", "Task overview", "solis") { overlayRequest = Overlay.TASKS })
            add(PaletteCmd("memory", "Memory inspector", "solis") { brainView = false; overlayRequest = Overlay.MEMORY })
            add(PaletteCmd("brain", "Brain view: memory graph", "solis") { brainView = true; overlayRequest = Overlay.MEMORY })
            add(PaletteCmd("coding", if (codingJobs.any { it.active }) "Coding jobs (${codingJobs.count { it.active }} active)" else "Coding jobs", "code") { overlayRequest = Overlay.TASKS })
            if (running) add(PaletteCmd("pauseall", "Pause all Solis tasks", "solis") { pauseAll() })
            if (resumable) add(PaletteCmd("resumeall", "Resume paused tasks", "solis") { resumeAll() })
            modes.filter { it.id != mode.id }.forEach { m -> add(PaletteCmd("mode:${m.id}", "Switch to ${m.name} mode", "mode") { switchMode(m.id) }) }
            add(PaletteCmd("modes", "Choose a mode…", "mode") { modeSheet = true })
            if (focusActive) add(PaletteCmd("focusoff", "End focus", "focus") { startFocus(0) })
            else listOf(25, 50).forEach { m -> add(PaletteCmd("focus$m", "Focus for $m minutes", "focus") { startFocus(m) }) }
            ThemeMode.entries.filter { it != theme }.forEach { t -> add(PaletteCmd("theme:${t.id}", "Theme: ${t.title}", "theme") { setTheme(t) }) }
            add(PaletteCmd("offline", if (offline) "Offline mode off" else "Offline mode on", "privacy") { updateOffline(!offline) })
            workProfile?.let { wp ->
                add(PaletteCmd("work", if (wp.paused) "Turn work apps on" else "Pause work apps", "profile") { setWorkPaused(!wp.paused) })
            }
            add(PaletteCmd("catalog", "Refresh model catalogs", "solis") { refreshCatalogs() })
            listOf("providers" to "Providers & models", "cortex" to "Cortex & routing", "budget" to "Budget & usage",
                "modes" to "Modes & routines", "privacy" to "Privacy & memory sharing", "coding" to "Coding with Claude Code",
                "diagnostics" to "Diagnostics & performance",
            ).forEach { (k, v) -> add(PaletteCmd("set:$k", "Settings: $v", "settings") { settingsSection = k; overlayRequest = Overlay.SETTINGS }) }
            add(PaletteCmd("lock", "Lock screen", "system") { lockScreen() })
            add(PaletteCmd("home", "Set Axiom as default home app", "system") { actions.chooseDefaultLauncher() })
        }
        if (filter.isBlank()) return all
        return all.map { it to Fuzzy.score(filter, it.title) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** Enter / voice: run the best interpretation. */
    fun submit(text: String = query, fromVoice: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (fromVoice && q.lowercase().startsWith("open ")) {
            findApps(q.substring(5), 1).firstOrNull()?.let { open(it); return }
        }
        val top = if (fromVoice) search(q, light = true).firstOrNull { it !is Hit.App || Fuzzy.score(q, it.app.label) >= 600 }
        else results.firstOrNull()?.takeIf { lastSearched == q } ?: search(q, light = true).firstOrNull()
        top?.let { execute(it, fromVoice) }
    }

    fun execute(hit: Hit, fromVoice: Boolean = false) {
        when (hit) {
            is Hit.App -> open(hit.app)
            is Hit.Command -> run(hit.cmd)
            is Hit.Calc -> { copyToClipboard(hit.result); say("${hit.result} copied") }
            is Hit.Contact -> hit.c.number?.let { actions.dial(it) }
            is Hit.FileItem -> if (hit.f.isDir) { filesPath = hit.f.path; pageRequest = PAGE_FILES } else actions.openFile(hit.f.path)
            is Hit.Setting -> actions.openSettings(hit.panel)
            is Hit.NoteItem -> say(hit.note.text)
            is Hit.Thread -> { selectedThread = hit.thread.id; pageRequest = PAGE_SOLIS }
            is Hit.Memory -> { memoryFocus = hit.item.id; brainView = false; overlayRequest = Overlay.MEMORY }
            is Hit.Mode -> switchMode(hit.mode.id)
            is Hit.Palette -> hit.cmd.run()
            is Hit.Ask -> ask(hit.q, speak = fromVoice)
            is Hit.Web -> actions.webSearch(hit.q)
            is Hit.Code -> codeDirect(hit.spec)
        }
        if (hit !is Hit.Calc) query = ""
    }

    private fun copyToClipboard(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Axiom", text))
    }

    /** Omnibox commands run on the device immediately; only Brief and Digest need Solis. */
    fun run(cmd: Cmd) {
        when (cmd) {
            Cmd.Brief -> ask(BRIEF_PROMPT)
            Cmd.Digest -> if (NotificationWatcher.enabled(ctx)) ask(DIGEST_PROMPT) else { say("Enable notification access in settings"); actions.notificationAccessSettings() }
            Cmd.Neofetch -> terminalOutput = DeviceInfo.neofetch(ctx, userName)
            Cmd.Matrix -> matrixMode = true
            Cmd.Lock -> lockScreen()
            is Cmd.Focus -> startFocus(cmd.minutes)
            is Cmd.Theme -> setTheme(cmd.mode)
            is Cmd.Web -> actions.webSearch(cmd.query)
            else -> viewModelScope.launch {
                val space = mode.space
                val reply = withContext(Dispatchers.IO) { runCatching { solis.runCommand(cmd, space) }.getOrElse { it.message } }
                say((reply ?: when (cmd) {
                    is Cmd.Call -> "No contact named “${cmd.target}”"
                    is Cmd.Text -> "No contact named “${cmd.target}”"
                    else -> "Couldn't do that on the device"
                }).replace("**", "").replace("`", ""))
            }
        }
    }

    fun say(msg: String) { toast = msg }

    // ================= calendar & notifications glance =================

    fun refreshGlance() {
        viewModelScope.launch(Dispatchers.IO) {
            val ev = CalendarRepo.upcoming(ctx, 2, 5).firstOrNull()
            val n = if (NotificationWatcher.connected()) NotificationWatcher.snapshot().size else 0
            withContext(Dispatchers.Main) { nextEvent = ev; notifCount = n }
        }
    }

    fun fmtEvent(e: CalEvent): String {
        val sameDay = android.text.format.DateUtils.isToday(e.begin)
        val pattern = when { e.allDay -> "EEE"; sameDay -> if (clock24) "HH:mm" else "h:mm a"; else -> if (clock24) "EEE HH:mm" else "EEE h:mm a" }
        return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.begin))} ${e.title}"
    }

    // ================= Solis =================

    /** "brain" = Solis runs it; "app" = hand off to the Claude app (uses the user's subscription). */
    val effectiveEngine: String
        get() = when (engineMode) {
            "app" -> "app"
            "brain" -> "brain"
            else -> if (providersConfigured || !claudeAppInstalled) "brain" else "app"
        }

    fun ask(text: String, parts: List<ContentPart> = emptyList(), speak: Boolean = false) {
        if (effectiveEngine == "app") {
            if (!actions.askClaudeApp(text)) say("Couldn't reach the Claude app")
            return
        }
        startTask(text, parts, null, speak)
    }

    private fun startTask(text: String, parts: List<ContentPart>, threadId: String?, speak: Boolean = false): Boolean =
        when (val r = solis.submit(text, parts, threadId)) {
            is SubmitResult.Accepted -> {
                selectedThread = r.threadId
                pageRequest = PAGE_SOLIS
                if (speak || speakReplies) speakOnDone += r.threadId
                true
            }
            is SubmitResult.Rejected -> {
                // A mode with AI off still answers on-device commands.
                viewModelScope.launch {
                    val local = withContext(Dispatchers.IO) { solis.deterministic(text, mode.space) }
                    say(local?.replace("**", "") ?: r.reason)
                }
                false
            }
        }

    /** Sends the Solis page input: a follow-up in the selected thread, or a new independent thread. */
    fun sendSolis(text: String = solisDraft) {
        val t = text.trim()
        val att = pendingAttachment
        if (t.isEmpty() && att == null) return
        if (effectiveEngine == "app") {
            handOffToApp(t, att)
            return
        }
        val sel = selectedThread?.let { engine.thread(it) }
        val followUp = sel != null && sel.status.acceptsFollowUp
        if (sel != null && !followUp) say("“${sel.title}” is still working, so this starts a separate task")
        if (startTask(t.ifEmpty { "What's in this file?" }, att?.parts.orEmpty(), if (followUp) sel!!.id else null)) {
            solisDraft = ""
            pendingAttachment = null
        }
    }

    private fun handOffToApp(text: String, att: Attachment?) {
        if (att != null) actions.start(
            Intent(Intent.ACTION_SEND).setPackage(Actions.CLAUDE_PKG)
                .setType("*/*").putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(att.path)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ) else actions.askClaudeApp(text)
        pendingAttachment = null
        solisDraft = ""
    }

    fun newThread() { selectedThread = null; solisDraft = ""; pageRequest = PAGE_SOLIS }
    fun pauseThread(id: String) = engine.pause(id)
    fun resumeThread(id: String) = engine.resume(id)
    fun cancelThread(id: String) = engine.cancel(id)
    fun rateThread(id: String, good: Boolean) { engine.rate(id, good); say(if (good) "Thanks — noted for routing" else "Noted — Solis will weigh that model lower") }
    fun deleteThread(id: String) {
        engine.delete(id)
        if (selectedThread == id) selectedThread = null
    }
    fun pauseAll() = threads.filter { it.status == ThreadStatus.RUNNING || it.status == ThreadStatus.QUEUED || it.status == ThreadStatus.WAITING }.forEach { engine.pause(it.id) }
    fun resumeAll() = threads.filter { it.status == ThreadStatus.PAUSED || it.status == ThreadStatus.INTERRUPTED }.forEach { engine.resume(it.id) }
    fun pinThreadModel(id: String, role: CallRole, ref: ModelRef?) = engine.setThreadModel(id, role, ref)

    /** Threads worth a card on the home screen: unfinished work, and answers from the last 15 minutes. */
    fun taskCards(now: Long = System.currentTimeMillis()): List<AgentThread> = threads.filter {
        when (it.status) {
            ThreadStatus.DONE, ThreadStatus.FAILED -> now - it.updatedAt < 15 * 60_000L
            ThreadStatus.CANCELLED -> false
            else -> true
        }
    }.sortedWith(compareBy<AgentThread> { if (it.isActive) 0 else 1 }.thenByDescending { it.updatedAt })

    private fun onThreads(list: List<AgentThread>) {
        val before = threads.associateBy { it.id }
        threads = list
        list.forEach { t ->
            if (t.status == ThreadStatus.DONE && before[t.id]?.status != ThreadStatus.DONE && speakOnDone.remove(t.id)) {
                t.lastResult?.let { speak(it) }
            }
        }
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*`#_>|]"), "")
        val t = tts
        if (t == null) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "axiom")
                }
            }
        } else t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "axiom")
    }

    fun stopSpeaking() { tts?.stop() }

    /** Prepares a file so the next Solis message carries it (image, PDF or text). */
    fun attachFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(path)
            val parts: List<ContentPart>? = runCatching {
                when (f.extension.lowercase()) {
                    "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> listOf(imagePart(f))
                    "pdf" -> if (f.length() < 25_000_000) listOf(ContentPart.Document(f.name, "application/pdf", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))) else null
                    else -> if (f.length() < 2_000_000) {
                        val txt = f.readText()
                        if (txt.contains('\u0000')) null
                        else listOf(ContentPart.Text("<file name=\"${f.name.replace("\"", "")}\">\n${txt.take(80_000)}\n</file>"))
                    } else null
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (parts == null && effectiveEngine == "brain") say("Solis can read images, PDFs and text files")
                else {
                    pendingAttachment = Attachment(f.name, f.path, parts.orEmpty())
                    pageRequest = PAGE_SOLIS
                }
            }
        }
    }

    private fun imagePart(f: File): ContentPart.Image {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1568 * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("decode failed")
        val scale = 1568f / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return ContentPart.Image("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
    }

    /** Content shared into Axiom ("Share → Ask Solis"): prefill the draft and attach the file. */
    fun receiveShared(text: String?, path: String?) {
        if (!text.isNullOrBlank()) solisDraft = text.take(8000)
        if (!path.isNullOrBlank()) attachFile(path)
        if (!text.isNullOrBlank() || !path.isNullOrBlank()) selectedThread = null
    }

    // ================= coding bridge =================

    /** Files [spec] as a Claude Code issue in the default repository, with no model call. */
    fun codeDirect(spec: String) {
        if (!githubConnected) { say("Connect GitHub first"); settingsSection = "coding"; overlayRequest = Overlay.SETTINGS; return }
        if (prefs.githubRepo.isBlank()) { say("Choose a default repository"); settingsSection = "coding"; overlayRequest = Overlay.SETTINGS; return }
        viewModelScope.launch {
            say("Sending to Claude Code…")
            val r = runCatching { solis.codeDirect(spec) }
            r.onSuccess { say("Filed ${it.id} for Claude Code"); overlayRequest = Overlay.TASKS }.onFailure { say(it.message ?: "Couldn't reach GitHub") }
        }
    }

    private fun codingCall(done: ((String) -> String)? = null, block: suspend () -> String) {
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            say(r.fold({ done?.invoke(it) ?: it }, { it.message ?: "GitHub request failed" }))
        }
    }

    fun recheckCoding(id: String) = codingCall { solis.coding.refresh(id).let { "${it.state.label}: ${it.detail}" } }
    fun openCodingPr(id: String) = codingCall { solis.coding.openPullRequest(id).let { "Opened PR #${it.pr}" } }
    fun mergeCoding(id: String) = codingCall { solis.coding.merge(id) }
    fun dismissMerge(id: String) = solis.coding.dismissMerge(id)
    fun stopWatchingCoding(id: String) = solis.coding.stopWatching(id)
    fun forgetCoding(id: String) = solis.coding.forget(id)

    /** Opens the job's work in Claude Code on the web, with the request copied for pasting. */
    fun continueInClaudeCode(job: CodingJob) {
        copyToClipboard("In ${job.repo}: ${job.title}\n\n${job.url}")
        actions.openUrl("https://claude.ai/code")
        say("Task copied — paste it into Claude Code")
    }

    fun saveGithubToken(token: String?) {
        viewModelScope.launch {
            say(if (token.isNullOrBlank()) "Disconnecting GitHub…" else "Checking GitHub token…")
            val msg = solis.setGithubToken(token)
            githubConnected = solis.githubConnected
            settingsRevision++
            say(msg)
        }
    }

    fun setGithubRepo(repo: String) { prefs.githubRepo = repo; settingsRevision++ }

    suspend fun githubRepos(): List<String> = withContext(Dispatchers.IO) { runCatching { solis.github.repos(40).map { it.fullName } }.getOrDefault(emptyList()) }

    fun checkCodingSetup() {
        codingSetup = listOf("Checking…")
        viewModelScope.launch {
            codingSetup = runCatching { withContext(Dispatchers.IO) { solis.coding.checkSetup(prefs.githubRepo) } }.getOrElse { listOf("✗ ${it.message}") }
        }
    }

    fun installClaudeWorkflow() = codingCall { solis.coding.installWorkflow(prefs.githubRepo).also { withContext(Dispatchers.Main) { checkCodingSetup() } } }

    fun copyWorkflow() { copyToClipboard(com.axiom.launcher.solis.coding.CodingBridge.WORKFLOW); say("Workflow YAML copied") }

    // ================= memory inspector =================

    suspend fun memoryItems(): List<MemoryItem> = withContext(Dispatchers.IO) { solis.memory.all() }

    fun addMemory(text: String, kind: MemoryKind, space: Space) = memoryWrite("Saved") {
        solis.memory.remember(text, kind, Scope(space), source = "you", confidence = 0.95)
    }

    fun editMemory(item: MemoryItem, text: String? = null, pinned: Boolean? = null, kind: MemoryKind? = null, space: Space? = null) =
        memoryWrite(null) {
            solis.memory.update(item.id, item.version, text = text, pinned = pinned, kind = kind,
                scope = space?.let { Scope(it, item.scope.threadId) }, source = if (text != null) "you (edited)" else null,
                confidence = if (text != null) 0.95 else null)
        }

    fun forgetMemory(id: String) = memoryWrite("Forgotten") { solis.memory.forget(id) }

    fun exportMemory(uri: Uri) = memoryWrite("Memory exported") {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(solis.memory.exportJson().toByteArray()) } ?: error("Couldn't write that file")
    }

    fun importMemory(uri: Uri) = memoryWrite(null) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't read that file")
        val n = solis.memory.importJson(text)
        withContext(Dispatchers.Main) { say("Imported $n memories") }
    }

    private fun memoryWrite(done: String?, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { block() }
            withContext(Dispatchers.Main) {
                r.onSuccess { done?.let { say(it) } }.onFailure { say(it.message ?: "Memory change failed") }
            }
        }
    }

    // ================= home helpers =================

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night" }
        return if (userName.isBlank()) part else "$part, ${userName.trim()}"
    }
}
