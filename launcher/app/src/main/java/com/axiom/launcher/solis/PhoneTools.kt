package com.axiom.launcher.solis

import android.content.Context
import com.axiom.launcher.core.Actions
import com.axiom.launcher.core.DeviceInfo
import com.axiom.launcher.data.AppCategory
import com.axiom.launcher.data.AppInfo
import com.axiom.launcher.data.FileRepository
import com.axiom.launcher.data.Note
import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.solis.engine.SideEffect
import com.axiom.launcher.solis.engine.SolisTool
import com.axiom.launcher.solis.engine.SolisTool.Companion.bool
import com.axiom.launcher.solis.engine.SolisTool.Companion.enumOf
import com.axiom.launcher.solis.engine.SolisTool.Companion.int
import com.axiom.launcher.solis.engine.SolisTool.Companion.str
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.engine.ToolHost
import com.axiom.launcher.solis.engine.ToolResult
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What Solis's phone tools may touch. Implemented by the launcher core. */
interface PhoneHost {
    val context: Context
    val actions: Actions
    val files: FileRepository
    /** Apps visible to a task in [space]: work-profile apps only for work, never Private Space apps. */
    fun apps(space: Space): List<AppInfo>
    fun findApps(query: String, space: Space, limit: Int = 5): List<AppInfo>
    fun launch(app: AppInfo): Boolean
    fun notes(): List<Note>
    fun addNote(text: String)
    fun deleteNote(id: Long): Boolean
    fun setTheme(mode: ThemeMode)
    fun modes(): List<LauncherMode>
    fun setMode(id: String): Boolean
    fun startFocus(minutes: Int)
    fun lockScreen(): Boolean
    fun calendarText(days: Int): String
    fun notificationsText(space: Space): String
}

/** The launcher's phone abilities as provider-neutral tools, grouped for per-thread permissions. */
class PhoneTools(private val host: PhoneHost) : ToolHost {

    private val list: List<SolisTool> by lazy { build() }
    override fun tools(): List<SolisTool> = list

    private fun ok(b: Boolean, okText: String, failText: String, summary: String) =
        if (b) ToolResult(okText, true, summary) else ToolResult(failText, false, "$summary ✗")

    private fun resolve(path: String): File {
        val p = path.trim()
        if (p.isEmpty() || p == "/") return host.files.root
        return if (p.startsWith("/")) File(p) else File(host.files.root, p)
    }

    private fun resolveNumber(target: String): String {
        if (target.any { it.isDigit() } && target.none { it.isLetter() }) return target
        return host.files.searchContacts(target, 1).firstOrNull()?.number ?: target
    }

    private fun fileLine(path: String, dir: Boolean, size: Long, modified: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(modified))
        return if (dir) "[dir] $path" else "$path · ${DeviceInfo.human(size)} · $date"
    }

    private fun parseIso(s: String): Long? = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        .firstNotNullOfOrNull { fmt -> runCatching { SimpleDateFormat(fmt, Locale.US).parse(s.take(19))?.time }.getOrNull() }

    private fun build(): List<SolisTool> = listOf(
        SolisTool("open_app", "Launch an installed app by (fuzzy) name. Use list_apps first if unsure.", ToolGroup.APPS, SideEffect.LOCAL,
            mapOf("name" to str("App name, e.g. 'Spotify' or 'maps'")), listOf("name")) { a, ctx ->
            val q = a.getString("name")
            val app = host.findApps(q, ctx.space, 1).firstOrNull() ?: return@SolisTool ToolResult("No app matches '$q' in this space.", false, "open $q ✗")
            ok(host.launch(app), "Opened ${app.label}.", "Couldn't open ${app.label} (its profile may be paused).", "opened ${app.label}")
        },
        SolisTool("list_apps", "List installed apps, optionally filtered by a fuzzy query or category.", ToolGroup.APPS, SideEffect.NONE,
            mapOf("query" to str("Optional fuzzy filter"), "category" to enumOf(AppCategory.entries.map { it.name.lowercase() }, "Optional category"))) { a, ctx ->
            val q = a.optString("query").ifBlank { null }
            val cat = a.optString("category").ifBlank { null }?.let { c -> AppCategory.entries.firstOrNull { it.name.equals(c, true) } }
            var apps = if (q != null) host.findApps(q, ctx.space, 40) else host.apps(ctx.space)
            if (cat != null && cat != AppCategory.ALL) apps = apps.filter { it.category == cat }
            ToolResult(apps.take(150).joinToString("\n") { "${it.label} [${it.category.title.lowercase()}]${if (it.isWork) " (work)" else ""}" }.ifEmpty { "No apps found." },
                true, "listed ${apps.size} apps")
        },
        SolisTool("search_files", "Search files and folders on shared storage by name.", ToolGroup.FILES_READ, SideEffect.NONE,
            mapOf("query" to str("Part of the file name")), listOf("query")) { a, _ ->
            val hits = host.files.search(a.getString("query"), 25)
            val note = if (!host.files.hasAllFilesAccess()) "\n(limited to media: all-files access not granted)" else ""
            ToolResult(hits.joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }.ifEmpty { "No files match." } + note, true, "found ${hits.size} files")
        },
        SolisTool("recent_files", "List the most recently modified files.", ToolGroup.FILES_READ, SideEffect.NONE, emptyMap()) { _, _ ->
            val hits = host.files.recent(25)
            ToolResult(hits.joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }.ifEmpty { "No recent files." }, true, "recent files")
        },
        SolisTool("list_directory", "List a folder on shared storage ('/' for the root).", ToolGroup.FILES_READ, SideEffect.NONE,
            mapOf("path" to str("Absolute path or path relative to storage root")), listOf("path")) { a, _ ->
            val dir = resolve(a.optString("path"))
            if (!dir.isDirectory) return@SolisTool ToolResult("Not a directory: ${dir.path}", false, "ls ✗")
            val items = host.files.list(dir)
            ToolResult("${dir.path}\n" + items.take(200).joinToString("\n") { fileLine(it.path, it.isDir, it.size, it.modified) }.ifEmpty { "(empty or no permission)" },
                true, "listed ${dir.name.ifEmpty { "/" }}")
        },
        SolisTool("read_text_file", "Read a text-like file (txt, md, csv, json, code), up to ~40k characters.", ToolGroup.FILES_READ, SideEffect.NONE,
            mapOf("path" to str("Absolute file path")), listOf("path")) { a, _ ->
            val f = resolve(a.getString("path"))
            if (!f.isFile) return@SolisTool ToolResult("Not a file: ${f.path}", false, "read ✗")
            if (f.length() > 5_000_000) return@SolisTool ToolResult("File too large (${DeviceInfo.human(f.length())}).", false, "read ✗")
            val text = f.readText().let { if (it.length > 40_000) it.take(40_000) + "\n…[truncated]" else it }
            ToolResult("<file name=\"${f.name}\">\n$text\n</file>", true, "read ${f.name}")
        },
        SolisTool("open_file", "Open a file in the right app for the user to view.", ToolGroup.FILES_OPEN, SideEffect.LOCAL,
            mapOf("path" to str("Absolute file path")), listOf("path")) { a, _ ->
            val f = resolve(a.getString("path"))
            ok(host.actions.openFile(f.path), "Opened ${f.name}.", "Couldn't open ${f.name}.", "opened ${f.name}")
        },
        SolisTool("set_timer", "Start a countdown timer in the clock app.", ToolGroup.CLOCK, SideEffect.LOCAL,
            mapOf("seconds" to int("Duration in seconds"), "label" to str("Optional label")), listOf("seconds")) { a, _ ->
            val s = a.getInt("seconds")
            ok(host.actions.setTimer(s, a.optString("label").ifBlank { null }), "Timer set for $s s.", "No clock app accepted the timer.", "timer ${s}s")
        },
        SolisTool("set_alarm", "Create an alarm (24h time).", ToolGroup.CLOCK, SideEffect.LOCAL,
            mapOf("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional label")), listOf("hour", "minute"),
            resource = { a -> "alarm:%02d:%02d".format(a.optInt("hour"), a.optInt("minute")) }) { a, _ ->
            val h = a.getInt("hour"); val m = a.getInt("minute")
            ok(host.actions.setAlarm(h, m, a.optString("label").ifBlank { null }), "Alarm set for %02d:%02d.".format(h, m), "No clock app accepted the alarm.", "alarm %02d:%02d".format(h, m))
        },
        SolisTool("calendar_agenda", "List upcoming calendar events.", ToolGroup.CALENDAR, SideEffect.NONE,
            mapOf("days" to int("Days ahead, 1 = today")), listOf("days")) { a, _ ->
            ToolResult(host.calendarText(a.optInt("days", 1).coerceIn(1, 31)), true, "read agenda")
        },
        SolisTool("create_calendar_event", "Open a pre-filled new calendar event for the user to confirm.", ToolGroup.CALENDAR, SideEffect.DRAFT,
            mapOf("title" to str("Event title"), "start" to str("Local start, yyyy-MM-ddTHH:mm"), "end" to str("Optional local end"),
                "location" to str("Optional location"), "description" to str("Optional notes")), listOf("title", "start")) { a, _ ->
            val start = parseIso(a.getString("start")) ?: return@SolisTool ToolResult("Invalid input: start must be yyyy-MM-ddTHH:mm", false, "event ✗")
            ok(host.actions.calendarEvent(a.getString("title"), start, a.optString("end").ifBlank { null }?.let { parseIso(it) },
                a.optString("location").ifBlank { null }, a.optString("description").ifBlank { null }),
                "Opened a pre-filled event; the user must tap save.", "No calendar app.", "event draft")
        },
        SolisTool("search_contacts", "Find contacts by name (names and numbers).", ToolGroup.CONTACTS, SideEffect.NONE,
            mapOf("query" to str("Name")), listOf("query")) { a, _ ->
            if (!host.files.hasContacts()) return@SolisTool ToolResult("Contacts permission not granted (Settings › Permissions).", false, "contacts ✗")
            val hits = host.files.searchContacts(a.getString("query"), 10)
            ToolResult(hits.joinToString("\n") { "${it.name}: ${it.number ?: "no number"}" }.ifEmpty { "No contacts found." }, true, "contacts")
        },
        SolisTool("dial", "Open the dialer with a number filled in (the user presses call).", ToolGroup.COMMUNICATION, SideEffect.DRAFT,
            mapOf("target" to str("Number or contact name")), listOf("target")) { a, _ ->
            val n = resolveNumber(a.getString("target"))
            ok(host.actions.dial(n), "Dialer opened with $n.", "Couldn't open the dialer.", "dial $n")
        },
        SolisTool("compose_sms", "Open messaging with a pre-filled text (the user presses send).", ToolGroup.COMMUNICATION, SideEffect.DRAFT,
            mapOf("to" to str("Number or contact name"), "body" to str("Message")), listOf("to", "body")) { a, _ ->
            val to = resolveNumber(a.getString("to"))
            ok(host.actions.sms(to, a.optString("body")), "Draft opened for $to; the user must press send.", "No messaging app.", "sms draft")
        },
        SolisTool("compose_email", "Open email with a pre-filled draft (the user presses send).", ToolGroup.COMMUNICATION, SideEffect.DRAFT,
            mapOf("to" to str("Address"), "subject" to str("Subject"), "body" to str("Body")), listOf("to")) { a, _ ->
            ok(host.actions.email(a.getString("to"), a.optString("subject"), a.optString("body")), "Email draft opened.", "No email app.", "email draft")
        },
        SolisTool("open_url", "Open a web page in the browser.", ToolGroup.APPS, SideEffect.LOCAL, mapOf("url" to str("URL")), listOf("url")) { a, _ ->
            val u = a.getString("url")
            ok(host.actions.openUrl(u), "Opened $u.", "No browser.", "opened link")
        },
        SolisTool("navigate", "Start navigation or show a place in maps.", ToolGroup.MEDIA, SideEffect.LOCAL,
            mapOf("destination" to str("Address or place")), listOf("destination")) { a, _ ->
            val d = a.getString("destination")
            ok(host.actions.navigate(d), "Maps opened for $d.", "No maps app.", "navigate")
        },
        SolisTool("play_media", "Ask the music app to play a song, artist, album or playlist.", ToolGroup.MEDIA, SideEffect.LOCAL,
            mapOf("query" to str("What to play")), listOf("query"), resource = { "media" }) { a, _ ->
            val q = a.getString("query")
            ok(host.actions.playMedia(q), "Asked the music app to play $q.", "No music app handled it.", "play")
        },
        SolisTool("media_control", "Control playback: play, pause, toggle, next, previous.", ToolGroup.MEDIA, SideEffect.LOCAL,
            mapOf("action" to enumOf(listOf("play", "pause", "toggle", "next", "previous"), "Action")), listOf("action"), resource = { "media" }) { a, _ ->
            host.actions.mediaKey(a.getString("action")); ToolResult("Sent ${a.getString("action")}.", true, "media ${a.getString("action")}")
        },
        SolisTool("set_volume", "Set media volume.", ToolGroup.MEDIA, SideEffect.LOCAL, mapOf("percent" to int("0-100")), listOf("percent"),
            resource = { "volume" }) { a, _ ->
            val v = host.actions.setVolume(a.getInt("percent")); ToolResult("Media volume now $v%.", true, "volume $v%")
        },
        SolisTool("flashlight", "Turn the flashlight on or off.", ToolGroup.SYSTEM, SideEffect.LOCAL, mapOf("on" to bool("true = on")), listOf("on"),
            resource = { "torch" }) { a, _ ->
            val on = a.getBoolean("on")
            ok(host.actions.toggleTorch(on), "Flashlight ${if (on) "on" else "off"}.", "No flashlight.", "torch ${if (on) "on" else "off"}")
        },
        SolisTool("open_settings", "Open a system settings screen.", ToolGroup.SYSTEM, SideEffect.LOCAL,
            mapOf("panel" to enumOf(Actions.SETTINGS.keys.toList(), "Which screen")), listOf("panel")) { a, _ ->
            val p = a.getString("panel")
            ok(host.actions.openSettings(p), "Opened $p settings.", "Couldn't open settings.", "settings $p")
        },
        SolisTool("device_status", "Battery, storage, network, time, next alarm and device model.", ToolGroup.SYSTEM, SideEffect.NONE, emptyMap()) { _, _ ->
            ToolResult(DeviceInfo.contextLine(host.context) + " | device: ${DeviceInfo.model()} | uptime: ${DeviceInfo.uptime()}", true, "device status")
        },
        SolisTool("set_theme", "Switch the launcher theme.", ToolGroup.SYSTEM, SideEffect.LOCAL,
            mapOf("theme" to enumOf(ThemeMode.entries.map { it.id }, "paper, void or netrunner")), listOf("theme"), resource = { "theme" }) { a, _ ->
            val m = ThemeMode.from(a.getString("theme")); host.setTheme(m); ToolResult("Theme is now ${m.title}.", true, "theme ${m.title}")
        },
        SolisTool("set_mode", "Switch the launcher mode (layout, notifications, AI budget and privacy presets).", ToolGroup.SYSTEM, SideEffect.LOCAL,
            mapOf("mode" to enumOf(host.modes().map { it.id }, "Mode id")), listOf("mode"), resource = { "mode" }) { a, _ ->
            val id = a.getString("mode")
            ok(host.setMode(id), "Switched to ${host.modes().firstOrNull { it.id == id }?.name ?: id} mode.", "No such mode.", "mode $id")
        },
        SolisTool("focus_mode", "Start Focus mode for N minutes (0 ends it).", ToolGroup.SYSTEM, SideEffect.LOCAL,
            mapOf("minutes" to int("Minutes, 0 to stop")), listOf("minutes"), resource = { "mode" }) { a, _ ->
            val m = a.getInt("minutes"); host.startFocus(m)
            ToolResult(if (m > 0) "Focus mode on for $m minutes." else "Focus mode off.", true, "focus $m min")
        },
        SolisTool("lock_screen", "Lock the screen (needs the Axiom accessibility service).", ToolGroup.SYSTEM, SideEffect.LOCAL, emptyMap()) { _, _ ->
            ok(host.lockScreen(), "Locked.", "Locking needs the Axiom accessibility service (Settings › Accessibility).", "lock")
        },
        SolisTool("recent_notifications", "Read current notifications (app, title, text) to summarize or triage.", ToolGroup.NOTIFICATIONS, SideEffect.NONE, emptyMap()) { _, ctx ->
            ToolResult(host.notificationsText(ctx.space), true, "read notifications")
        },
        SolisTool("clipboard_read", "Read the clipboard text.", ToolGroup.CLIPBOARD, SideEffect.NONE, emptyMap()) { _, _ ->
            ToolResult(host.actions.clipboard()?.take(20_000) ?: "(clipboard empty or not accessible)", true, "read clipboard")
        },
        SolisTool("clipboard_write", "Copy text to the clipboard.", ToolGroup.CLIPBOARD, SideEffect.LOCAL, mapOf("text" to str("Text")), listOf("text"),
            resource = { "clipboard" }) { a, _ ->
            host.actions.setClipboard(a.getString("text")); ToolResult("Copied.", true, "copied")
        },
        SolisTool("notes_list", "List the home-screen notes.", ToolGroup.NOTES, SideEffect.NONE, emptyMap()) { _, _ ->
            val n = host.notes()
            ToolResult(n.joinToString("\n") { "#${it.id}: ${it.text}" }.ifEmpty { "No notes." }, true, "notes (${n.size})")
        },
        SolisTool("note_add", "Save a note on the home screen.", ToolGroup.NOTES, SideEffect.LOCAL, mapOf("text" to str("Note text")), listOf("text")) { a, _ ->
            host.addNote(a.getString("text")); ToolResult("Saved.", true, "note saved")
        },
        SolisTool("note_delete", "Delete a home-screen note by id.", ToolGroup.NOTES, SideEffect.LOCAL, mapOf("id" to int("Note id")), listOf("id"),
            resource = { a -> "note:${a.optLong("id")}" }) { a, _ ->
            ok(host.deleteNote(a.getLong("id")), "Deleted.", "No note with that id.", "note deleted")
        },
    )
}
