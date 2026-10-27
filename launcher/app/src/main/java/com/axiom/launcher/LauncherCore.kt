package com.axiom.launcher

import android.app.Application
import android.content.pm.LauncherApps
import com.axiom.launcher.core.Actions
import com.axiom.launcher.core.Fuzzy
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.AppRepository
import com.axiom.launcher.data.FileRepository
import com.axiom.launcher.data.IconPacks
import com.axiom.launcher.data.Note
import com.axiom.launcher.data.Prefs
import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.modes.Routine
import com.axiom.launcher.platform.DeviceConditions
import com.axiom.launcher.platform.ProfileKind
import com.axiom.launcher.platform.ProfileManager
import com.axiom.launcher.platform.Widgets
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-scoped launcher state shared by the UI and by Solis's phone tools: apps (profile-aware),
 * usage, notes, theme, launcher modes and routines. Lives as long as the process, so background Solis
 * tasks never depend on an Activity being alive.
 */
class LauncherCore(val app: Application) {
    val prefs = Prefs(app)
    val actions = Actions(app)
    val files = FileRepository(app)
    val repo = AppRepository(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val profiles = ProfileManager(app) { reloadApps() }
    /** Wired to the Solis engine by [com.axiom.launcher.solis.SolisHost]. */
    var onConditionsChanged: () -> Unit = {}
    val conditions = DeviceConditions(app) { onConditionsChanged() }
    val widgets = Widgets(app, prefs) { profiles.profiles.value }

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    private val _notes = MutableStateFlow(prefs.notes)
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private val _theme = MutableStateFlow(ThemeMode.from(prefs.theme))
    /** The user's chosen theme; a mode may override it (see [effectiveTheme]). */
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val modes = ModeManager(prefs)
    private val _iconRevision = MutableStateFlow(0)
    /** Bumped when app icons must be redrawn (icon pack changed). */
    val iconRevision: StateFlow<Int> = _iconRevision.asStateFlow()

    private val usage = prefs.loadUsage()
    @Volatile private var recents = prefs.loadRecents()
    private val noteSeq = AtomicLong(maxOf(System.currentTimeMillis(), (_notes.value.maxOfOrNull { it.id } ?: 0) + 1))
    private val notesLock = Any()

    private var appsCallback: LauncherApps.Callback? = null
    private var started = false
    @Volatile private var frozenApps = false

    fun start() {
        if (started) return
        started = true
        profiles.start()
        conditions.start()
        reloadApps()
        appsCallback = repo.registerCallback { scope.launch { reloadApps() } }
        widgets.releaseOrphans()
        if (prefs.iconPack.isNotBlank()) applyIconPack(prefs.iconPack)
    }

    fun reloadApps() {
        scope.launch {
            profiles.refresh()
            val list = withContext(Dispatchers.IO) { repo.load(prefs.renames, profiles.profiles.value) }
            if (!frozenApps) _apps.value = list
        }
    }

    /** Used by screenshot tests, which have no real launcher apps. */
    fun debugSetApps(list: List<AppInfo>) {
        frozenApps = true
        _apps.value = list
    }

    fun effectiveTheme(mode: LauncherMode = modes.active.value): ThemeMode = mode.theme ?: _theme.value

    // ---------------- launching & usage ----------------

    /** Launches through LauncherApps (the system enforces profile and suspension rules). */
    fun launch(app: AppInfo): Boolean {
        val ok = runCatching { repo.launch(app) }.isSuccess
        if (ok) recordLaunch(app)
        return ok
    }

    private fun recordLaunch(app: AppInfo) {
        val h = hour()
        synchronized(usage) { usage.getOrPut(app.key) { IntArray(24) }[h]++ }
        recents = (listOf(app.key) + recents.filter { it != app.key }).take(12)
        val snapshot = synchronized(usage) { usage.mapValues { it.value.copyOf() } }
        val r = recents
        scope.launch(Dispatchers.IO) {
            prefs.saveUsage(snapshot)
            prefs.saveRecents(r)
        }
    }

    fun usageScore(key: String, hour: Int = hour()): Double {
        val a = synchronized(usage) { usage[key]?.copyOf() } ?: return 0.0
        val total = a.sum()
        return a[hour] * 3.0 + a[(hour + 1) % 24] + a[(hour + 23) % 24] + total / 24.0
    }

    fun recents(): List<String> = recents

    /** Fuzzy search over [pool], nudged by what the user tends to open at this hour. */
    fun findApps(query: String, pool: List<AppInfo>, limit: Int): List<AppInfo> {
        val h = hour()
        return pool
            .map { it to Fuzzy.score(query, it.label).let { s -> if (s > 0) s + usageScore(it.key, h).coerceAtMost(60.0).toInt() else 0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Apps worth suggesting right now (time-of-day usage + recency), excluding [exclude]. */
    fun suggestions(pool: List<AppInfo>, exclude: Set<String>, limit: Int = 4): List<AppInfo> {
        val h = hour()
        val recentBoost = recents.withIndex().associate { (i, k) -> k to (12 - i) * 0.15 }
        return pool.asSequence()
            .filter { it.key !in exclude && it.runnable }
            .map { it to usageScore(it.key, h) + (recentBoost[it.key] ?: 0.0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun isDistracting(app: AppInfo): Boolean {
        val set = prefs.distracting
        return if (set != null) app.key in set else app.category == AppCategory.SOCIAL || app.category == AppCategory.GAMES
    }

    // ---------------- notes ----------------

    fun addNote(text: String): Note {
        val n = Note(noteSeq.incrementAndGet(), text.trim(), System.currentTimeMillis())
        synchronized(notesLock) {
            _notes.update { listOf(n) + it }
            prefs.notes = _notes.value
        }
        return n
    }

    fun deleteNote(id: Long): Boolean = synchronized(notesLock) {
        val had = _notes.value.any { it.id == id }
        _notes.update { list -> list.filter { it.id != id } }
        prefs.notes = _notes.value
        had
    }

    fun togglePin(id: Long) = synchronized(notesLock) {
        _notes.update { list -> list.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }
        prefs.notes = _notes.value
    }

    /** Re-reads everything a settings import can change. */
    fun reloadFromPrefs() {
        _theme.value = ThemeMode.from(prefs.theme)
        synchronized(notesLock) { _notes.value = prefs.notes }
        modes.reload()
        applyIconPack(prefs.iconPack)
        reloadApps()
    }

    // ---------------- icon pack ----------------

    /** Switches to the icon pack in [pkg] ("" for system icons); falls back to system icons if it can't be read. */
    fun applyIconPack(pkg: String, onDone: (Boolean) -> Unit = {}) {
        prefs.iconPack = pkg
        scope.launch {
            val pack = if (pkg.isBlank()) null else withContext(Dispatchers.IO) { IconPacks.load(app, pkg) }
            val ok = pkg.isBlank() || pack != null
            if (!ok) prefs.iconPack = ""
            repo.iconPack = pack
            repo.clearIcons()
            _iconRevision.update { it + 1 }
            onDone(ok)
        }
    }

    // ---------------- theme ----------------

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.theme = mode.id
    }

    // ---------------- profile boundary ----------------

    /**
     * Apps a Solis task in [space] may see and open: never Private Space; work-profile apps only for work
     * tasks. (Work tasks may also open personal apps: launching is a user-visible action, not a data read.)
     */
    fun appsFor(space: Space): List<AppInfo> {
        val hidden = prefs.hidden
        return _apps.value.filter { a ->
            a.key !in hidden && a.profile != ProfileKind.PRIVATE && when (space) {
                Space.WORK -> true
                Space.PERSONAL -> a.profile != ProfileKind.WORK
            }
        }
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}

/** Active launcher mode, temporary modes (e.g. Focus for 25 min) and routines. */
class ModeManager(private val prefs: Prefs, private val clock: () -> Long = System::currentTimeMillis) {
    private val _modes = MutableStateFlow(loadModes())
    val modes: StateFlow<List<LauncherMode>> = _modes.asStateFlow()
    private val _active = MutableStateFlow(resolve(prefs.activeMode))
    val active: StateFlow<LauncherMode> = _active.asStateFlow()
    private val _tempUntil = MutableStateFlow(prefs.tempModeUntil)
    /** When the current temporary mode ends (0 = not temporary). */
    val tempUntil: StateFlow<Long> = _tempUntil.asStateFlow()
    private val _routines = MutableStateFlow(loadRoutines())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private fun resolve(id: String): LauncherMode = _modes.value.firstOrNull { it.id == id } ?: _modes.value.first()

    private fun loadModes(): List<LauncherMode> = prefs.modesJson?.let { j ->
        runCatching { JSONArray(j).let { a -> (0 until a.length()).map { LauncherMode.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    }?.takeIf { it.isNotEmpty() } ?: LauncherMode.defaults()

    private fun loadRoutines(): List<Routine> = prefs.routinesJson?.let { j ->
        runCatching { JSONArray(j).let { a -> (0 until a.length()).map { Routine.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    } ?: Routine.defaults()

    fun mode(id: String): LauncherMode? = _modes.value.firstOrNull { it.id == id }

    /** After a settings import: modes, routines and the active mode come from prefs again. */
    @Synchronized
    fun reload() {
        _modes.value = loadModes()
        _routines.value = loadRoutines()
        clearTemporary()
        _active.value = resolve(prefs.activeMode)
    }

    @Synchronized
    fun switch(id: String): Boolean {
        val m = mode(id) ?: return false
        clearTemporary()
        _active.value = m
        prefs.activeMode = id
        return true
    }

    /** Enters a mode for [minutes], then returns to the mode that was active before. */
    @Synchronized
    fun startTemporary(id: String, minutes: Int) {
        if (minutes <= 0) { endTemporary(revert = true); return }
        val m = mode(id) ?: return
        tick()
        if (_tempUntil.value == 0L) prefs.tempModeRevert = _active.value.id
        _active.value = m
        prefs.activeMode = id
        val until = clock() + minutes * 60_000L
        _tempUntil.value = until
        prefs.tempModeUntil = until
    }

    /** Ends Focus whether it was started for a while or switched to permanently. */
    @Synchronized
    fun stopFocus() {
        if (_tempUntil.value != 0L) { endTemporary(revert = true); return }
        if (_active.value.blockDistracting) {
            val back = prefs.tempModeRevert.takeIf { it != _active.value.id && mode(it) != null } ?: _modes.value.first().id
            switch(back)
        }
    }

    @Synchronized
    fun endTemporary(revert: Boolean) {
        if (_tempUntil.value == 0L) return
        clearTemporary()
        if (revert) {
            val back = mode(prefs.tempModeRevert) ?: _modes.value.first()
            _active.value = back
            prefs.activeMode = back.id
        }
    }

    private fun clearTemporary() {
        _tempUntil.value = 0
        prefs.tempModeUntil = 0
    }

    /** Call on minute ticks and resume; returns true if a temporary mode just ended. */
    @Synchronized
    fun tick(): Boolean {
        val until = _tempUntil.value
        if (until != 0L && clock() >= until) { endTemporary(revert = true); return true }
        return false
    }

    @Synchronized
    fun update(mode: LauncherMode) {
        val exists = _modes.value.any { it.id == mode.id }
        val next = if (exists) _modes.value.map { if (it.id == mode.id) mode else it } else _modes.value + mode
        _modes.value = next
        prefs.modesJson = JSONArray(next.map { it.toJson() }).toString()
        if (_active.value.id == mode.id) _active.value = mode
    }

    @Synchronized
    fun resetModes() {
        prefs.modesJson = null
        _modes.value = LauncherMode.defaults()
        _active.value = resolve(prefs.activeMode)
    }

    @Synchronized
    fun saveRoutine(r: Routine) {
        val next = _routines.value.map { if (it.id == r.id) r else it }.let { if (it.none { x -> x.id == r.id }) it + r else it }
        _routines.value = next
        prefs.routinesJson = JSONArray(next.map { it.toJson() }).toString()
    }

    @Synchronized
    fun deleteRoutine(id: String) {
        val next = _routines.value.filterNot { it.id == id }
        _routines.value = next
        prefs.routinesJson = JSONArray(next.map { it.toJson() }).toString()
    }

    /** Routines due now (each at most once a day); marks them as run before returning them. */
    @Synchronized
    fun takeDueRoutines(): List<Routine> {
        val now = clock()
        val due = _routines.value.filter { it.dueAt(now) }
        due.forEach { saveRoutine(it.copy(lastRunDay = Routine.dayKey(now))) }
        return due
    }
}
