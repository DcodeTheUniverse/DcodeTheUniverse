package com.axiom.launcher.ai

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.axiom.launcher.core.Actions
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.FileRepository
import com.axiom.launcher.data.Note
import com.axiom.launcher.ui.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the agent is allowed to touch. Implemented by the launcher ViewModel. */
interface PhoneBridge {
    val context: Context
    val actions: Actions
    val files: FileRepository
    fun visibleApps(): List<AppInfo>
    fun findApps(query: String, limit: Int = 5): List<AppInfo>
    fun launchApp(app: AppInfo): Boolean
    fun notesSnapshot(): List<Note>
    fun addNote(text: String)
    fun deleteNote(id: Long): Boolean
    fun memoriesSnapshot(): List<String>
    fun remember(fact: String)
    fun forget(index: Int): Boolean
    fun applyTheme(mode: ThemeMode)
    fun startFocus(minutes: Int)
    fun lockScreen(): Boolean
    fun calendarText(days: Int): String
    fun notificationsText(): String
}

class ToolOutcome(val text: String, val isError: Boolean = false, val summary: String)

class AgentTools(private val bridge: PhoneBridge) {

    val definitions: List<BetaTool> = listOf(
        tool("open_app", "Launch an installed app by (fuzzy) name. Use list_apps first if unsure of the exact name.",
            mapOf("name" to str("App name, e.g. 'Spotify' or 'maps'")), listOf("name")),
        tool("list_apps", "List installed apps, optionally filtered by a fuzzy query or a category.",
            mapOf(
                "query" to str("Optional fuzzy filter"),
                "category" to enumOf(AppCategory.entries.map { it.name.lowercase() }, "Optional category"),
            ), emptyList()),
        tool("search_files", "Search files and folders on shared storage by name. Returns paths, sizes and dates.",
            mapOf("query" to str("Part of the file name")), listOf("query")),
        tool("recent_files", "List the most recently modified files on the phone.", emptyMap(), emptyList()),
        tool("list_directory", "List a folder on shared storage. Use '/' or '' for the storage root.",
            mapOf("path" to str("Absolute path or path relative to storage root")), listOf("path")),
        tool("read_text_file", "Read a text-like file (txt, md, csv, json, code, etc.), up to ~40k characters.",
            mapOf("path" to str("Absolute file path")), listOf("path")),
        tool("open_file", "Open a file in the appropriate app for the user to view.",
            mapOf("path" to str("Absolute file path")), listOf("path")),
        tool("set_timer", "Start a countdown timer in the clock app.",
            mapOf("seconds" to int("Duration in seconds"), "label" to str("Optional label")), listOf("seconds")),
        tool("set_alarm", "Create an alarm in the clock app (24h time).",
            mapOf("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional label")), listOf("hour", "minute")),
        tool("create_calendar_event", "Open a pre-filled new calendar event for the user to confirm and save.",
            mapOf(
                "title" to str("Event title"),
                "start" to str("Local start time, ISO format yyyy-MM-ddTHH:mm"),
                "end" to str("Optional local end time, ISO format yyyy-MM-ddTHH:mm"),
                "location" to str("Optional location"),
                "description" to str("Optional notes"),
            ), listOf("title", "start")),
        tool("search_contacts", "Find contacts by name; returns names and phone numbers.",
            mapOf("query" to str("Name or part of it")), listOf("query")),
        tool("dial", "Open the dialer with a number filled in (user presses call). Accepts a number or a contact name.",
            mapOf("target" to str("Phone number or contact name")), listOf("target")),
        tool("compose_sms", "Open the messaging app with a pre-filled text (the user presses send).",
            mapOf("to" to str("Phone number or contact name"), "body" to str("Message text")), listOf("to", "body")),
        tool("compose_email", "Open the email app with a pre-filled draft (the user presses send).",
            mapOf("to" to str("Email address"), "subject" to str("Subject"), "body" to str("Body")), listOf("to")),
        tool("open_url", "Open a web page in the browser.", mapOf("url" to str("URL")), listOf("url")),
        tool("navigate", "Start navigation / show a place in the maps app.",
            mapOf("destination" to str("Address or place name")), listOf("destination")),
        tool("play_media", "Ask the music app to play a song, artist, album or playlist.",
            mapOf("query" to str("What to play")), listOf("query")),
        tool("flashlight", "Turn the flashlight on or off.", mapOf("on" to bool("true = on")), listOf("on")),
        tool("open_settings", "Open a system settings screen.",
            mapOf("panel" to enumOf(Actions.SETTINGS.keys.toList(), "Which settings screen")), listOf("panel")),
        tool("device_status", "Get battery, storage, network, time, next alarm and device model.", emptyMap(), emptyList()),
        tool("notes_list", "List the user's quick notes saved in the launcher.", emptyMap(), emptyList()),
        tool("note_add", "Save a quick note on the home screen.", mapOf("text" to str("Note text")), listOf("text")),
        tool("note_delete", "Delete a quick note by id.", mapOf("id" to int("Note id from notes_list")), listOf("id")),
        tool("remember", "Store a durable fact or preference about the user for future conversations.",
            mapOf("fact" to str("Short fact, e.g. 'Prefers metric units'")), listOf("fact")),
        tool("forget", "Delete a remembered fact by its index (0-based) in the memory list.",
            mapOf("index" to int("Index")), listOf("index")),
        tool("set_theme", "Switch the launcher theme.",
            mapOf("theme" to enumOf(ThemeMode.entries.map { it.id }, "paper = white/black, void = OLED black/white, netrunner = cyberpunk hacker")),
            listOf("theme")),
        tool("focus_mode", "Start focus mode for N minutes (hides distracting apps). 0 ends focus mode.",
            mapOf("minutes" to int("Minutes, 0 to stop")), listOf("minutes")),
        tool("calendar_agenda", "List upcoming calendar events (needs calendar permission).",
            mapOf("days" to int("How many days ahead, 1 = today")), listOf("days")),
        tool("recent_notifications", "Read the user's current notifications (app, title, text) to summarize or triage them.",
            emptyMap(), emptyList()),
        tool("media_control", "Control whatever is playing: play, pause, toggle, next, previous.",
            mapOf("action" to enumOf(listOf("play", "pause", "toggle", "next", "previous"), "Media action")), listOf("action")),
        tool("set_volume", "Set the media volume.", mapOf("percent" to int("0-100")), listOf("percent")),
        tool("clipboard_read", "Read the current clipboard text.", emptyMap(), emptyList()),
        tool("clipboard_write", "Copy text to the clipboard.", mapOf("text" to str("Text to copy")), listOf("text")),
        tool("lock_screen", "Lock the phone screen (requires the Axiom accessibility service).", emptyMap(), emptyList()),
    )

    /** Runs a tool. [input] is the parsed tool_use input. Never throws. */
    fun run(name: String, input: JSONObject): ToolOutcome = try {
        execute(name, input)
    } catch (e: MissingArg) {
        ToolOutcome("Invalid input: missing or malformed '${e.message}'. Re-issue the call with valid JSON.", true, "$name: bad input")
    } catch (e: Exception) {
        ToolOutcome("Error: ${e.javaClass.simpleName}: ${e.message}", true, "$name failed")
    }

    private class MissingArg(field: String) : Exception(field)

    private fun JSONObject.reqStr(k: String): String = optString(k).takeIf { has(k) && it.isNotBlank() } ?: throw MissingArg(k)
    private fun JSONObject.reqInt(k: String): Int = if (has(k)) optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: throw MissingArg(k) else throw MissingArg(k)
    private fun JSONObject.optStrOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null

    private fun ok(b: Boolean, okText: String, failText: String, summary: String) =
        if (b) ToolOutcome(okText, false, summary) else ToolOutcome(failText, true, "$summary ✗")

    private fun execute(name: String, a: JSONObject): ToolOutcome {
        val actions = bridge.actions
        return when (name) {
            "open_app" -> {
                val q = a.reqStr("name")
                val app = bridge.findApps(q, 1).firstOrNull()
                    ?: return ToolOutcome("No installed app matches '$q'.", true, "open $q ✗")
                ok(bridge.launchApp(app), "Opened ${app.label}.", "Could not open ${app.label}.", "open ${app.label}")
            }
            "list_apps" -> {
                val q = a.optStrOrNull("query")
                val cat = a.optStrOrNull("category")?.let { c -> AppCategory.entries.firstOrNull { it.name.equals(c, true) } }
                var apps = if (q != null) bridge.findApps(q, 40) else bridge.visibleApps()
                if (cat != null && cat != AppCategory.ALL) apps = apps.filter { it.category == cat }
                val text = apps.take(150).joinToString("\n") { "${it.label} [${it.category.title.lowercase()}]${if (it.isWork) " (work)" else ""}" }
                ToolOutcome(text.ifEmpty { "No apps found." }, false, "list apps (${apps.size})")
            }
            "search_files" -> {
                val q = a.reqStr("query")
                val hits = bridge.files.search(q, 25)
                val note = if (!bridge.files.hasAllFilesAccess()) "\n(note: limited to media; user hasn't granted all-files access)" else ""
                ToolOutcome(hits.joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }.ifEmpty { "No files match '$q'." } + note,
                    false, "search files '$q' (${hits.size})")
            }
            "recent_files" -> {
                val hits = bridge.files.recent(25)
                ToolOutcome(hits.joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }.ifEmpty { "No recent files." },
                    false, "recent files")
            }
            "list_directory" -> {
                val raw = a.optString("path", "")
                val dir = resolve(raw)
                if (!dir.isDirectory) return ToolOutcome("Not a directory: ${dir.path}", true, "ls ✗")
                val items = bridge.files.list(dir)
                ToolOutcome("${dir.path}\n" + items.take(200).joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }
                    .ifEmpty { "(empty or no permission)" }, false, "ls ${dir.name.ifEmpty { "/" }}")
            }
            "read_text_file" -> {
                val f = resolve(a.reqStr("path"))
                if (!f.isFile) return ToolOutcome("Not a file: ${f.path}", true, "read ✗")
                if (f.length() > 5_000_000) return ToolOutcome("File too large to read (${DeviceInfo.human(f.length())}).", true, "read ✗")
                val text = f.readText().let { if (it.length > 40_000) it.take(40_000) + "\n…[truncated]" else it }
                ToolOutcome(text, false, "read ${f.name}")
            }
            "open_file" -> {
                val f = resolve(a.reqStr("path"))
                ok(actions.openFile(f.path), "Opened ${f.name}.", "Could not open ${f.name}.", "open ${f.name}")
            }
            "set_timer" -> {
                val s = a.reqInt("seconds")
                ok(actions.setTimer(s, a.optStrOrNull("label")), "Timer set for $s seconds.", "No clock app accepted the timer.", "timer ${s}s")
            }
            "set_alarm" -> {
                val h = a.reqInt("hour"); val m = a.reqInt("minute")
                ok(actions.setAlarm(h, m, a.optStrOrNull("label")), "Alarm set for %02d:%02d.".format(h, m), "No clock app accepted the alarm.",
                    "alarm %02d:%02d".format(h, m))
            }
            "create_calendar_event" -> {
                val title = a.reqStr("title")
                val start = parseIso(a.reqStr("start")) ?: throw MissingArg("start")
                val end = a.optStrOrNull("end")?.let { parseIso(it) }
                ok(actions.calendarEvent(title, start, end, a.optStrOrNull("location"), a.optStrOrNull("description")),
                    "Opened a pre-filled event '$title'. The user must tap save.", "No calendar app available.", "event $title")
            }
            "search_contacts" -> {
                if (!bridge.files.hasContacts()) return ToolOutcome("Contacts permission not granted. Ask the user to allow it in Axiom settings.", true, "contacts ✗")
                val hits = bridge.files.searchContacts(a.reqStr("query"), 10)
                ToolOutcome(hits.joinToString("\n") { "${it.name}: ${it.number ?: "no number"}" }.ifEmpty { "No contacts found." }, false, "contacts")
            }
            "dial" -> {
                val target = resolveNumber(a.reqStr("target"))
                ok(actions.dial(target), "Dialer opened with $target.", "Could not open the dialer.", "dial $target")
            }
            "compose_sms" -> {
                val to = resolveNumber(a.reqStr("to"))
                ok(actions.sms(to, a.optStrOrNull("body")), "Message draft opened for $to. The user must press send.", "No messaging app.", "sms $to")
            }
            "compose_email" -> {
                val to = a.reqStr("to")
                ok(actions.email(to, a.optStrOrNull("subject"), a.optStrOrNull("body")), "Email draft opened.", "No email app.", "email $to")
            }
            "open_url" -> { val u = a.reqStr("url"); ok(actions.openUrl(u), "Opened $u.", "No browser.", "open $u") }
            "navigate" -> { val d = a.reqStr("destination"); ok(actions.navigate(d), "Maps opened for $d.", "No maps app.", "navigate $d") }
            "play_media" -> { val q = a.reqStr("query"); ok(actions.playMedia(q), "Asked the music app to play $q.", "No music app handled it.", "play $q") }
            "flashlight" -> {
                val on = if (a.has("on")) a.optBoolean("on") else throw MissingArg("on")
                ok(actions.toggleTorch(on), "Flashlight ${if (on) "on" else "off"}.", "No flashlight available.", "torch ${if (on) "on" else "off"}")
            }
            "open_settings" -> { val p = a.reqStr("panel"); ok(actions.openSettings(p), "Opened $p settings.", "Could not open settings.", "settings $p") }
            "device_status" -> ToolOutcome(DeviceInfo.contextLine(bridge.context) + " | device: ${DeviceInfo.model()} | uptime: ${DeviceInfo.uptime()}", false, "device status")
            "notes_list" -> {
                val n = bridge.notesSnapshot()
                ToolOutcome(n.joinToString("\n") { "#${it.id}: ${it.text}" }.ifEmpty { "No notes." }, false, "notes (${n.size})")
            }
            "note_add" -> { val t = a.reqStr("text"); bridge.addNote(t); ToolOutcome("Saved.", false, "note saved") }
            "note_delete" -> {
                val id = if (a.has("id")) a.optLong("id") else throw MissingArg("id")
                ok(bridge.deleteNote(id), "Deleted.", "No note with id $id.", "note deleted")
            }
            "remember" -> { val f = a.reqStr("fact"); bridge.remember(f); ToolOutcome("Remembered.", false, "remembered") }
            "forget" -> ok(bridge.forget(a.reqInt("index")), "Forgotten.", "No memory at that index.", "forgot")
            "set_theme" -> {
                val mode = ThemeMode.from(a.reqStr("theme"))
                bridge.applyTheme(mode)
                ToolOutcome("Theme switched to ${mode.title}.", false, "theme ${mode.title}")
            }
            "focus_mode" -> {
                val m = a.reqInt("minutes")
                bridge.startFocus(m)
                ToolOutcome(if (m > 0) "Focus mode on for $m minutes." else "Focus mode off.", false, "focus $m min")
            }
            "calendar_agenda" -> ToolOutcome(bridge.calendarText(a.optInt("days", 1).coerceIn(1, 31)), false, "agenda")
            "recent_notifications" -> ToolOutcome(bridge.notificationsText(), false, "notifications")
            "media_control" -> { val act = a.reqStr("action"); actions.mediaKey(act); ToolOutcome("Sent $act.", false, "media $act") }
            "set_volume" -> { val v = actions.setVolume(a.reqInt("percent")); ToolOutcome("Media volume now $v%.", false, "volume $v%") }
            "clipboard_read" -> ToolOutcome(actions.clipboard()?.take(20_000) ?: "(clipboard empty or not accessible)", false, "read clipboard")
            "clipboard_write" -> { actions.setClipboard(a.reqStr("text")); ToolOutcome("Copied.", false, "copied to clipboard") }
            "lock_screen" -> ok(bridge.lockScreen(), "Locked.", "Lock needs the Axiom accessibility service enabled (Settings › Accessibility).", "lock")
            else -> ToolOutcome("Unknown tool $name", true, "unknown tool")
        }
    }

    private fun resolveNumber(target: String): String {
        if (target.any { it.isDigit() } && target.count { it.isLetter() } == 0) return target
        return bridge.files.searchContacts(target, 1).firstOrNull()?.number ?: target
    }

    private fun resolve(path: String): File {
        val p = path.trim()
        if (p.isEmpty() || p == "/") return bridge.files.root
        return if (p.startsWith("/")) File(p) else File(bridge.files.root, p)
    }

    private fun parseIso(s: String): Long? = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        .firstNotNullOfOrNull { fmt -> runCatching { SimpleDateFormat(fmt, Locale.US).parse(s.take(19))?.time }.getOrNull() }

    private fun fileLine(path: String, dir: Boolean, size: Long, modified: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(modified))
        return if (dir) "[dir] $path" else "$path · ${DeviceInfo.human(size)} · $date"
    }

    companion object {
        private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
        private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
        private fun enumOf(values: List<String>, desc: String) = mapOf("type" to "string", "enum" to values, "description" to desc)

        private fun tool(name: String, desc: String, props: Map<String, Map<String, Any>>, required: List<String>): BetaTool {
            val p = BetaTool.InputSchema.Properties.builder()
            props.forEach { (k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)) }
            return BetaTool.builder()
                .name(name)
                .description(desc)
                .inputSchema(BetaTool.InputSchema.builder().properties(p.build()).required(required).build())
                .eagerInputStreaming(true)
                .build()
        }

        fun jsonOf(map: Map<*, *>?): JSONObject = if (map == null) JSONObject() else JSONObject(map)

        @Suppress("unused")
        fun jsonArr(list: List<*>) = JSONArray(list)
    }
}
