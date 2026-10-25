package com.dcode.launcher

import android.app.Application
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaBase64PdfSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock
import com.dcode.launcher.ai.AgentTools
import com.dcode.launcher.ai.ClaudeAgent
import com.dcode.launcher.ai.PhoneBridge
import com.dcode.launcher.core.Actions
import com.dcode.launcher.core.Calc
import com.dcode.launcher.core.Cmd
import com.dcode.launcher.core.CommandParser
import com.dcode.launcher.core.DeviceInfo
import com.dcode.launcher.core.Fuzzy
import com.dcode.launcher.data.AppCategory
import com.dcode.launcher.data.AppInfo
import com.dcode.launcher.data.AppRepository
import com.dcode.launcher.data.CalEvent
import com.dcode.launcher.data.CalendarRepo
import com.dcode.launcher.data.NotificationWatcher
import com.dcode.launcher.data.ChatMsg
import com.dcode.launcher.data.ChatRole
import com.dcode.launcher.data.ContactHit
import com.dcode.launcher.data.FileHit
import com.dcode.launcher.data.FileRepository
import com.dcode.launcher.data.Note
import com.dcode.launcher.data.Prefs
import com.dcode.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale

sealed class Hit(val key: String) {
    class App(val app: AppInfo) : Hit("app:" + app.key)
    class Command(val cmd: Cmd) : Hit("cmd:" + cmd.title)
    class Calc(val expr: String, val result: String) : Hit("calc")
    class Contact(val c: ContactHit) : Hit("contact:" + c.name)
    class FileItem(val f: FileHit) : Hit("file:" + f.path)
    class Setting(val title: String, val panel: String) : Hit("setting:$panel")
    class NoteItem(val note: Note) : Hit("note:" + note.id)
    class Ask(val q: String) : Hit("ask")
    class Web(val q: String) : Hit("web")
}

const val BRIEF_PROMPT = "Give me my brief for right now: agenda, anything notable in my notifications and notes, battery, and one suggestion for what to focus on. Keep it short."
const val DIGEST_PROMPT = "Summarize my notifications: group by app, surface anything that needs a reply or action first, and skip the noise."

class Attachment(val name: String, val path: String, val blocks: List<BetaContentBlockParam>)

class LauncherViewModel(app: Application) : AndroidViewModel(app), PhoneBridge {
    override val context get() = getApplication<Application>()
    val prefs = Prefs(app)
    private val repo = AppRepository(app)
    override val files = FileRepository(app)
    override val actions = Actions(app)
    private val agent = ClaudeAgent(AgentTools(this))
    private val main = Handler(Looper.getMainLooper())

    // ---- persisted settings, mirrored as Compose state ----
    var theme by mutableStateOf(ThemeMode.from(prefs.theme)); private set
    var userName by mutableStateOf(prefs.userName); private set
    var showIcons by mutableStateOf(prefs.showIcons); private set
    var monoIcons by mutableStateOf(prefs.monoIcons); private set
    var clock24 by mutableStateOf(prefs.clock24); private set
    var speakReplies by mutableStateOf(prefs.speakReplies); private set
    var effects by mutableStateOf(prefs.effects); private set
    var autoKeyboard by mutableStateOf(prefs.autoKeyboard); private set
    var engine by mutableStateOf(prefs.engine); private set
    var model by mutableStateOf(prefs.model); private set
    var effort by mutableStateOf(prefs.effort); private set
    var webSearch by mutableStateOf(prefs.webSearch); private set
    var hasApiKey by mutableStateOf(prefs.apiKey.isNotBlank()); private set
    var onboarded by mutableStateOf(prefs.onboarded); private set

    var apps by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var favorites by mutableStateOf(prefs.favorites); private set
    var hidden by mutableStateOf(prefs.hidden); private set
    var notes by mutableStateOf(prefs.notes); private set
    var memories by mutableStateOf(prefs.memories); private set
    var focusUntil by mutableStateOf(prefs.focusUntil); private set
    var distracting by mutableStateOf(prefs.distracting); private set
    var suggestions by mutableStateOf<List<AppInfo>>(emptyList()); private set
    private val usage = prefs.loadUsage()
    private var recents = prefs.loadRecents()

    // ---- transient UI state ----
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Hit>>(emptyList()); private set
    private var lastSearched = ""
    var toast by mutableStateOf<String?>(null)
    var terminalOutput by mutableStateOf<String?>(null)
    var matrixMode by mutableStateOf(false)
    var bootSequence by mutableStateOf(false)
    var pageRequest by mutableStateOf<Int?>(null)
    var focusBlocked by mutableStateOf<AppInfo?>(null)
    var pendingAttachment by mutableStateOf<Attachment?>(null)
    var chatDraft by mutableStateOf("")
    var needsLockSetup by mutableStateOf(false)

    val chat = mutableStateListOf<ChatMsg>().apply { addAll(prefs.loadChat()) }
    var busy by mutableStateOf(false); private set
    private var currentAssistantId: Long? = null
    private var idSeq = System.currentTimeMillis()

    private var tts: TextToSpeech? = null
    private var appsCallback: LauncherApps.Callback? = null

    init {
        reloadApps()
        appsCallback = repo.registerCallback { main.post { reloadApps() } }
        observeQuery()
        viewModelScope.launch(Dispatchers.IO) { runCatching { files.ensureIndex() } }
        refreshGlance()
    }

    override fun onCleared() {
        appsCallback?.let { repo.unregister(it) }
        tts?.shutdown()
        super.onCleared()
    }

    private var frozenApps = false

    fun reloadApps() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.load(prefs.renames) }
            if (frozenApps) return@launch
            apps = list
            refreshSuggestions()
        }
    }

    /** Used by screenshot tests, which have no real launcher apps. */
    fun debugSetApps(list: List<AppInfo>, favs: List<String>) {
        frozenApps = true
        apps = list
        favorites = favs
        refreshSuggestions()
    }

    override fun visibleApps(): List<AppInfo> = apps.filter { it.key !in hidden }

    fun favoriteApps(): List<AppInfo> {
        val byKey = apps.associateBy { it.key }
        return favorites.mapNotNull { byKey[it] }
    }

    fun icon(app: AppInfo, size: Int): Bitmap? = repo.icon(app, size)

    // ================= launching & usage =================

    val focusActive: Boolean get() = focusUntil > System.currentTimeMillis()

    fun isDistracting(app: AppInfo): Boolean {
        val set = distracting
        return if (set != null) app.key in set else app.category == AppCategory.SOCIAL || app.category == AppCategory.GAMES
    }

    fun open(app: AppInfo, force: Boolean = false) {
        if (!force && focusActive && isDistracting(app)) {
            focusBlocked = app
            return
        }
        runCatching { repo.launch(app) }.onFailure { say("Couldn't open ${app.label}") }
        recordLaunch(app)
        query = ""
    }

    override fun launchApp(app: AppInfo): Boolean = runCatching {
        main.post { open(app, force = true) }
        true
    }.getOrDefault(false)

    private fun recordLaunch(app: AppInfo) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val arr = usage.getOrPut(app.key) { IntArray(24) }
        arr[h]++
        recents = (listOf(app.key) + recents.filter { it != app.key }).take(12)
        viewModelScope.launch(Dispatchers.IO) {
            prefs.saveUsage(usage)
            prefs.saveRecents(recents)
        }
        refreshSuggestions()
    }

    private fun usageScore(key: String, hour: Int): Double {
        val a = usage[key] ?: return 0.0
        val total = a.sum()
        return a[hour] * 3.0 + a[(hour + 1) % 24] + a[(hour + 23) % 24] + total / 24.0
    }

    fun refreshSuggestions() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val favs = favorites.toSet()
        val recentBoost = recents.withIndex().associate { (i, k) -> k to (12 - i) * 0.15 }
        suggestions = visibleApps()
            .filter { it.key !in favs }
            .map { it to usageScore(it.key, h) + (recentBoost[it.key] ?: 0.0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
    }

    // ================= favorites / hide / rename =================

    fun toggleFavorite(app: AppInfo) {
        favorites = if (app.key in favorites) favorites - app.key else (favorites + app.key)
        prefs.favorites = favorites
        refreshSuggestions()
    }

    fun moveFavorite(app: AppInfo, delta: Int) {
        val l = favorites.toMutableList()
        val i = l.indexOf(app.key)
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i < 0 || i == j) return
        l.removeAt(i); l.add(j, app.key)
        favorites = l
        prefs.favorites = l
    }

    fun toggleHidden(app: AppInfo) {
        hidden = if (app.key in hidden) hidden - app.key else hidden + app.key
        prefs.hidden = hidden
        if (app.key in hidden && app.key in favorites) toggleFavorite(app)
    }

    fun rename(app: AppInfo, newName: String) {
        val m = prefs.renames.toMutableMap()
        if (newName.isBlank() || newName == app.originalLabel) m.remove(app.key) else m[app.key] = newName.trim()
        prefs.renames = m
        reloadApps()
    }

    fun appInfo(app: AppInfo) = runCatching { repo.appDetails(app) }
    fun uninstall(app: AppInfo) = actions.uninstall(app.packageName)

    // ================= settings =================

    override fun applyTheme(mode: ThemeMode) {
        main.post {
            if (mode == ThemeMode.NETRUNNER && theme != ThemeMode.NETRUNNER && effects) bootSequence = true
            theme = mode
            prefs.theme = mode.id
        }
    }

    fun updateName(v: String) { userName = v; prefs.userName = v }
    fun updateShowIcons(v: Boolean) { showIcons = v; prefs.showIcons = v }
    fun updateMonoIcons(v: Boolean) { monoIcons = v; prefs.monoIcons = v }
    fun updateClock24(v: Boolean) { clock24 = v; prefs.clock24 = v }
    fun updateSpeak(v: Boolean) { speakReplies = v; prefs.speakReplies = v }
    fun updateEffects(v: Boolean) { effects = v; prefs.effects = v }
    fun updateAutoKeyboard(v: Boolean) { autoKeyboard = v; prefs.autoKeyboard = v }
    fun updateEngine(v: String) { engine = v; prefs.engine = v }
    fun updateModel(v: String) { model = v; prefs.model = v }
    fun updateEffort(v: String) { effort = v; prefs.effort = v }
    fun updateWebSearch(v: Boolean) { webSearch = v; prefs.webSearch = v }
    fun updateApiKey(v: String) { prefs.apiKey = v; hasApiKey = v.isNotBlank(); agent.reset() }
    fun finishOnboarding() { onboarded = true; prefs.onboarded = true }
    fun resetOnboarding() { onboarded = false; prefs.onboarded = false }

    fun toggleDistracting(app: AppInfo) {
        val base = distracting ?: visibleApps().filter { isDistracting(it) }.map { it.key }.toSet()
        val next = if (app.key in base) base - app.key else base + app.key
        distracting = next
        prefs.distracting = next
    }

    override fun startFocus(minutes: Int) {
        main.post {
            focusUntil = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
            prefs.focusUntil = focusUntil
            say(if (minutes > 0) "Focus mode · $minutes min" else "Focus mode off")
        }
    }

    // ================= notes & memory =================

    override fun notesSnapshot() = notes
    override fun addNote(text: String) {
        main.post {
            notes = listOf(Note(++idSeq, text.trim(), System.currentTimeMillis())) + notes
            prefs.notes = notes
        }
    }
    override fun deleteNote(id: Long): Boolean {
        val exists = notes.any { it.id == id }
        main.post { notes = notes.filter { it.id != id }; prefs.notes = notes }
        return exists
    }
    fun togglePin(note: Note) {
        notes = notes.map { if (it.id == note.id) it.copy(pinned = !it.pinned) else it }
        prefs.notes = notes
    }

    override fun memoriesSnapshot() = memories
    override fun remember(fact: String) {
        main.post { memories = memories + fact.trim(); prefs.memories = memories }
    }
    override fun forget(index: Int): Boolean {
        if (index !in memories.indices) return false
        main.post { memories = memories.filterIndexed { i, _ -> i != index }; prefs.memories = memories }
        return true
    }

    override fun lockScreen(): Boolean {
        val ok = LockService.lock()
        if (!ok) main.post { needsLockSetup = true }
        return ok
    }

    override fun findApps(query: String, limit: Int): List<AppInfo> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return visibleApps()
            .map { it to Fuzzy.score(query, it.label).let { s -> if (s > 0) s + usageScore(it.key, h).coerceAtMost(60.0).toInt() else 0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
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
        val out = ArrayList<Hit>()
        Calc.tryEval(q)?.let { out += Hit.Calc(q, it) }
        val cmd = CommandParser.parse(q)
        if (cmd != null) out += Hit.Command(cmd)
        val question = CommandParser.looksLikeQuestion(q)
        val appHits = findApps(q, 6)
        val strongApp = appHits.firstOrNull()?.let { Fuzzy.score(q, it.label) >= 600 } == true
        if (question && !strongApp && cmd == null) out += Hit.Ask(q)
        appHits.forEach { out += Hit.App(it) }
        if (!light) files.searchContacts(q, 3).forEach { out += Hit.Contact(it) }
        Actions.SETTINGS_TITLES.filter { Fuzzy.score(q, it.first) >= 450 }.take(2).forEach { out += Hit.Setting(it.first, it.second) }
        notes.filter { it.text.contains(q, ignoreCase = true) }.take(2).forEach { out += Hit.NoteItem(it) }
        if (!light && q.length >= 3) files.search(q, 5).forEach { out += Hit.FileItem(it) }
        if (out.none { it is Hit.Ask }) out += Hit.Ask(q)
        out += Hit.Web(q)
        return out
    }

    /** Enter / voice: run the best interpretation. */
    fun submit(text: String = query, fromVoice: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty()) return
        val top = if (fromVoice) search(q, light = true).firstOrNull { it !is Hit.App || Fuzzy.score(q.removePrefix("open "), it.app.label) >= 600 }
        else results.firstOrNull()?.takeIf { results.isNotEmpty() && lastSearched == q } ?: search(q, light = true).firstOrNull()
        if (fromVoice && q.lowercase().startsWith("open ")) {
            findApps(q.substring(5), 1).firstOrNull()?.let { open(it); return }
        }
        top?.let { execute(it) }
    }

    fun execute(hit: Hit) {
        when (hit) {
            is Hit.App -> open(hit.app)
            is Hit.Command -> run(hit.cmd)
            is Hit.Calc -> { copyToClipboard(hit.result); say("${hit.result} copied") }
            is Hit.Contact -> hit.c.number?.let { actions.dial(it) }
            is Hit.FileItem -> if (hit.f.isDir) { filesPath = hit.f.path; pageRequest = 0 } else actions.openFile(hit.f.path)
            is Hit.Setting -> actions.openSettings(hit.panel)
            is Hit.NoteItem -> say(hit.note.text)
            is Hit.Ask -> ask(hit.q)
            is Hit.Web -> actions.webSearch(hit.q)
        }
        if (hit !is Hit.Calc) query = ""
    }

    var filesPath by mutableStateOf<String?>(null)

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Dcode", text))
    }

    fun run(cmd: Cmd) {
        when (cmd) {
            is Cmd.Timer -> if (!actions.setTimer(cmd.seconds, cmd.label)) say("No clock app found")
            is Cmd.Alarm -> if (!actions.setAlarm(cmd.hour, cmd.minute, cmd.label)) say("No clock app found")
            is Cmd.Call -> {
                val num = if (cmd.target.any { it.isDigit() }) cmd.target else files.searchContacts(cmd.target, 1).firstOrNull()?.number
                if (num != null) actions.dial(num) else say("No contact '${cmd.target}'")
            }
            is Cmd.Text -> {
                val num = if (cmd.target.any { it.isDigit() }) cmd.target else files.searchContacts(cmd.target, 1).firstOrNull()?.number ?: cmd.target
                actions.sms(num, cmd.body)
            }
            is Cmd.Email -> actions.email(cmd.to, null, null)
            is Cmd.Torch -> if (actions.toggleTorch(cmd.on)) say("Flashlight ${if (actions.torchOn) "on" else "off"}") else say("No flashlight")
            is Cmd.Setting -> actions.openSettings(cmd.panel)
            is Cmd.Note -> { addNote(cmd.text); say("Noted") }
            is Cmd.Remember -> { remember(cmd.fact); say("I'll remember that") }
            is Cmd.Web -> actions.webSearch(cmd.query)
            is Cmd.Navigate -> actions.navigate(cmd.dest)
            is Cmd.Play -> if (!actions.playMedia(cmd.query)) say("No music app handled that")
            is Cmd.Focus -> startFocus(cmd.minutes)
            is Cmd.Theme -> applyTheme(cmd.mode)
            is Cmd.Url -> actions.openUrl(cmd.url)
            Cmd.Camera -> actions.camera()
            Cmd.Calendar -> actions.openCalendar()
            Cmd.Notifications -> actions.expandNotifications()
            Cmd.QuickSettings -> actions.expandQuickSettings()
            Cmd.Neofetch -> terminalOutput = DeviceInfo.neofetch(context, userName)
            Cmd.Matrix -> matrixMode = true
            Cmd.Lock -> lockScreen()
            Cmd.Alarms -> actions.showAlarms()
            is Cmd.Media -> { actions.mediaKey(cmd.action); say(cmd.action.replaceFirstChar { it.uppercase() }) }
            is Cmd.Volume -> say("Volume ${actions.setVolume(cmd.percent, cmd.delta)}%")
            Cmd.Brief -> ask(BRIEF_PROMPT)
            Cmd.Digest -> if (NotificationWatcher.enabled(context)) ask(DIGEST_PROMPT) else { say("Enable notification access in settings"); actions.notificationAccessSettings() }
            Cmd.Agenda -> if (CalendarRepo.hasPermission(context)) {
                val ev = CalendarRepo.upcoming(context, 1)
                say(if (ev.isEmpty()) "Nothing else today" else ev.take(3).joinToString(" · ") { fmtEvent(it) })
            } else actions.openCalendar()
        }
    }

    fun say(msg: String) { toast = msg }

    // ================= calendar & notifications =================

    var nextEvent by mutableStateOf<CalEvent?>(null); private set
    var notifCount by mutableStateOf(0); private set

    fun refreshGlance() {
        viewModelScope.launch(Dispatchers.IO) {
            val ev = CalendarRepo.upcoming(context, 2, 5).firstOrNull()
            val n = if (NotificationWatcher.connected()) NotificationWatcher.snapshot().size else 0
            main.post { nextEvent = ev; notifCount = n }
        }
    }

    fun fmtEvent(e: CalEvent): String {
        val sameDay = android.text.format.DateUtils.isToday(e.begin)
        val pattern = when { e.allDay -> "EEE"; sameDay -> if (clock24) "HH:mm" else "h:mm a"; else -> if (clock24) "EEE HH:mm" else "EEE h:mm a" }
        val t = java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(e.begin))
        return "$t ${e.title}"
    }

    override fun calendarText(days: Int): String {
        if (!CalendarRepo.hasPermission(context)) return "Calendar permission not granted. Ask the user to allow it in Dcode settings."
        val ev = CalendarRepo.upcoming(context, days, 40)
        if (ev.isEmpty()) return "No events in the next $days day(s)."
        val f = java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
        return ev.joinToString("\n") { e ->
            (if (e.allDay) "${java.text.SimpleDateFormat("EEE d MMM", Locale.US).format(java.util.Date(e.begin))} (all day)" else "${f.format(java.util.Date(e.begin))}–${java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(e.end))}") +
                " · ${e.title}" + (e.location?.let { " @ $it" } ?: "")
        }
    }

    override fun notificationsText(): String {
        if (!NotificationWatcher.connected()) return "Notification access is off. The user can enable it in Dcode settings › Notification digest."
        val items = NotificationWatcher.snapshot()
        if (items.isEmpty()) return "No notifications."
        val f = java.text.SimpleDateFormat("HH:mm", Locale.US)
        return items.joinToString("\n") { "[${f.format(java.util.Date(it.time))}] ${it.app}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}" }
    }

    // ================= Claude =================

    /** "api" when an API key is set (or forced), otherwise hand off to the Claude app (subscription). */
    val effectiveEngine: String
        get() = when (engine) {
            "app" -> "app"
            "api" -> if (hasApiKey) "api" else "app"
            else -> if (hasApiKey) "api" else "app"
        }

    fun ask(text: String) {
        if (effectiveEngine == "app") {
            if (!actions.askClaudeApp(text)) say("Couldn't reach the Claude app")
            return
        }
        pageRequest = 2
        sendChat(text)
    }

    fun sendChat(text: String) {
        val t = text.trim()
        val att = pendingAttachment
        if ((t.isEmpty() && att == null) || busy) return
        if (effectiveEngine == "app") {
            if (att != null) actions.start(
                android.content.Intent(android.content.Intent.ACTION_SEND).setPackage(Actions.CLAUDE_PKG)
                    .setType("*/*").putExtra(android.content.Intent.EXTRA_TEXT, t)
                    .putExtra(android.content.Intent.EXTRA_STREAM,
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(att.path)))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ) else actions.askClaudeApp(t)
            pendingAttachment = null
            chatDraft = ""
            return
        }
        val key = prefs.apiKey
        val prompt = t.ifEmpty { "What's in this file?" }
        chat += ChatMsg(++idSeq, ChatRole.USER, if (att != null) "📎 ${att.name}\n$prompt" else prompt)
        pendingAttachment = null
        chatDraft = ""
        busy = true
        currentAssistantId = null
        val system = ClaudeAgent.systemPrompt(userName, memories, theme == ThemeMode.NETRUNNER)
        val blocks = (att?.blocks ?: emptyList()) +
            BetaContentBlockParam.ofText(ClaudeAgent.userText(DeviceInfo.contextLine(context), prompt))
        val listener = object : ClaudeAgent.Listener {
            override fun onText(delta: String) = main.post { appendAssistant(delta) }.let { }
            override fun onTool(summary: String, isError: Boolean) = main.post {
                finishAssistant()
                chat += ChatMsg(++idSeq, ChatRole.TOOL, summary, error = isError)
            }.let { }
            override fun onNewTurn() = main.post { finishAssistant() }.let { }
        }
        viewModelScope.launch(Dispatchers.IO) {
            var err: String? = null
            try {
                agent.send(key, model, effort, webSearch, system, blocks, listener)
            } catch (e: ClaudeAgent.AgentError) {
                err = e.message
            } catch (e: Throwable) {
                err = "${e.javaClass.simpleName}: ${e.message}"
            }
            main.post {
                finishAssistant()
                if (err != null) chat += ChatMsg(++idSeq, ChatRole.SYSTEM, err, error = true)
                busy = false
                prefs.saveChat(chat)
                if (err == null && speakReplies) chat.lastOrNull { it.role == ChatRole.ASSISTANT }?.let { speak(it.text) }
            }
        }
    }

    fun stopChat() { agent.cancelled = true }

    private fun appendAssistant(delta: String) {
        val id = currentAssistantId
        val idx = if (id == null) -1 else chat.indexOfFirst { it.id == id }
        if (idx < 0) {
            if (delta.isBlank()) return
            val m = ChatMsg(++idSeq, ChatRole.ASSISTANT, delta.trimStart(), streaming = true)
            currentAssistantId = m.id
            chat += m
        } else {
            chat[idx] = chat[idx].copy(text = chat[idx].text + delta)
        }
    }

    private fun finishAssistant() {
        val id = currentAssistantId ?: return
        val idx = chat.indexOfFirst { it.id == id }
        if (idx >= 0) chat[idx] = chat[idx].copy(streaming = false, text = chat[idx].text.trimEnd())
        currentAssistantId = null
    }

    fun clearChat() {
        if (busy) stopChat()
        chat.clear()
        agent.reset()
        prefs.saveChat(emptyList())
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*`#_>]"), "")
        val t = tts
        if (t == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "dcode")
                }
            }
        } else t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "dcode")
    }

    fun stopSpeaking() { tts?.stop() }

    /** Prepare a file so the next chat message carries it to Claude (image, PDF or text). */
    fun attachFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(path)
            val ext = f.extension.lowercase()
            val blocks: List<BetaContentBlockParam>? = runCatching {
                when (ext) {
                    "jpg", "jpeg", "png", "webp", "gif", "heic" -> listOf(imageBlock(f))
                    "pdf" -> if (f.length() < 25_000_000) listOf(
                        BetaContentBlockParam.ofDocument(
                            BetaRequestDocumentBlock.builder()
                                .source(BetaBase64PdfSource.builder().data(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)).build())
                                .title(f.name).build()
                        )
                    ) else null
                    else -> if (f.length() < 2_000_000) {
                        val txt = f.readText()
                        if (txt.count { it == '\u0000' } > 0) null
                        else listOf(BetaContentBlockParam.ofText("<file name=\"${f.name}\">\n${txt.take(80_000)}\n</file>"))
                    } else null
                }
            }.getOrNull()
            main.post {
                if (blocks == null && effectiveEngine == "api") say("Can't send that file type to Claude")
                else {
                    pendingAttachment = Attachment(f.name, f.path, blocks ?: emptyList())
                    pageRequest = 2
                }
            }
        }
    }

    private fun imageBlock(f: File): BetaContentBlockParam {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1568 * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("decode failed")
        val scale = 1568f / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return BetaContentBlockParam.ofImage(
            BetaImageBlockParam.builder().source(
                BetaBase64ImageSource.builder()
                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                    .data(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                    .build()
            ).build()
        )
    }

    // ================= home helpers =================

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night" }
        return if (userName.isBlank()) part else "$part, ${userName.trim()}"
    }
}
