package com.axiom.launcher.core

import com.axiom.launcher.ui.theme.ThemeMode

/** Commands understood instantly, offline, before anything is sent to Claude. */
sealed class Cmd(val title: String, val hint: String) {
    class Timer(val seconds: Int, val label: String?) : Cmd("Set timer · ${fmtDur(seconds)}", "timer")
    class Alarm(val hour: Int, val minute: Int, val label: String?) : Cmd("Set alarm · %02d:%02d".format(hour, minute), "alarm")
    class Call(val target: String) : Cmd("Call $target", "phone")
    class Text(val target: String, val body: String?) : Cmd("Message $target" + (body?.let { " · \"$it\"" } ?: ""), "sms")
    class Email(val to: String) : Cmd("Email $to", "email")
    class Torch(val on: Boolean?) : Cmd("Flashlight " + when (on) { true -> "on"; false -> "off"; null -> "toggle" }, "torch")
    class Setting(val panel: String, label: String) : Cmd("Open $label settings", "settings")
    class Note(val text: String) : Cmd("Save note · $text", "note")
    class Web(val query: String) : Cmd("Search the web · $query", "web")
    class Navigate(val dest: String) : Cmd("Navigate to $dest", "maps")
    class Play(val query: String) : Cmd("Play $query", "music")
    class Focus(val minutes: Int) : Cmd(if (minutes > 0) "Focus mode · $minutes min" else "End focus mode", "focus")
    class Theme(val mode: ThemeMode) : Cmd("Switch theme · ${mode.title}", "theme")
    class Url(val url: String) : Cmd("Open $url", "link")
    class Remember(val fact: String) : Cmd("Remember · $fact", "memory")
    class Media(val action: String) : Cmd("Media · $action", "music")
    class Volume(val percent: Int?, val delta: Int) : Cmd(
        when { percent != null -> "Volume · $percent%"; delta > 0 -> "Volume up"; else -> "Volume down" }, "volume")
    object Brief : Cmd("Daily brief from Claude", "brief")
    object Digest : Cmd("Summarize my notifications", "notifications")
    object Agenda : Cmd("Today's agenda", "calendar")
    object Camera : Cmd("Open camera", "camera")
    object Calendar : Cmd("Open calendar", "calendar")
    object Notifications : Cmd("Show notifications", "notifications")
    object QuickSettings : Cmd("Quick settings", "settings")
    object Neofetch : Cmd("neofetch · system info", "terminal")
    object Matrix : Cmd("Enter the Matrix", "terminal")
    object Lock : Cmd("Lock screen", "lock")
    object Alarms : Cmd("Show alarms", "alarm")

    companion object {
        fun fmtDur(s: Int): String {
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return buildString {
                if (h > 0) append("${h}h ")
                if (m > 0) append("${m}m ")
                if (sec > 0 || (h == 0 && m == 0)) append("${sec}s")
            }.trim()
        }
    }
}

object CommandParser {
    private val durRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\b")

    fun parseDuration(t: String): Int? {
        var total = 0.0
        var found = false
        durRe.findAll(t).forEach { m ->
            found = true
            val n = m.groupValues[1].toDouble()
            total += when (m.groupValues[2].first()) { 'h' -> n * 3600; 'm' -> n * 60; else -> n }
        }
        if (!found) {
            when {
                t.contains("half an hour") -> return 1800
                t.contains("an hour") -> return 3600
                t.contains("a minute") -> return 60
            }
            Regex("^(\\d+)$").find(t.trim())?.let { return it.groupValues[1].toInt() * 60 }
        }
        return if (found && total > 0) total.toInt() else null
    }

    fun parseTime(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?:[:.h ](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3]
        if (ampm.startsWith("p") && h < 12) h += 12
        if (ampm.startsWith("a") && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return h to min
    }

    fun parse(raw: String): Cmd? {
        val t = raw.trim()
        val l = t.lowercase()
        if (l.isEmpty()) return null

        Regex("^(?:set (?:a )?)?timer(?: for)? (.+)$").find(l)?.let { m ->
            parseDuration(m.groupValues[1])?.let { return Cmd.Timer(it, null) }
        }
        Regex("^(.+?) timer$").find(l)?.let { m -> parseDuration(m.groupValues[1])?.let { return Cmd.Timer(it, null) } }
        Regex("^(?:remind me in|in) (.+?)(?: to (.+))?$").find(l)?.let { m ->
            parseDuration(m.groupValues[1])?.let { return Cmd.Timer(it, m.groupValues[2].ifBlank { null }) }
        }
        Regex("^(?:set (?:an )?alarm(?: for| at)?|alarm(?: at| for)?|wake me(?: up)?(?: at)?) (.+)$").find(l)?.let { m ->
            parseTime(m.groupValues[1])?.let { return Cmd.Alarm(it.first, it.second, null) }
        }
        if (l == "alarms" || l == "show alarms") return Cmd.Alarms
        Regex("^(?:call|dial|ring) (.+)$").find(t)?.let { return Cmd.Call(it.groupValues[1].trim()) }
        Regex("^(?:text|sms|message|msg) ([^:,]+?)(?:[:,] ?| saying | that )(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Cmd.Text(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^(?:text|sms|message) (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Text(it.groupValues[1].trim(), null) }
        Regex("^(?:email|mail) (\\S+@\\S+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Email(it.groupValues[1]) }
        Regex("^(?:flash ?light|torch|light)(?: (on|off))?$").find(l)?.let {
            return Cmd.Torch(when (it.groupValues[1]) { "on" -> true; "off" -> false; else -> null })
        }
        Regex("^(?:turn )?(on|off) (?:the )?(?:flash ?light|torch)$").find(l)?.let { return Cmd.Torch(it.groupValues[1] == "on") }
        Regex("^(?:note|jot|todo)[: ] ?(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Note(it.groupValues[1].trim()) }
        Regex("^remember(?: that)? (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Remember(it.groupValues[1].trim()) }
        Regex("^(?:google|search|web|look up|lookup) (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Web(it.groupValues[1]) }
        Regex("^(?:navigate to|directions to|take me to|drive to|maps?) (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Navigate(it.groupValues[1]) }
        when (l) {
            "play", "resume", "play music", "resume music" -> return Cmd.Media("play")
            "pause", "stop music", "pause music", "shh" -> return Cmd.Media("pause")
            "next", "next song", "skip", "skip song", "next track" -> return Cmd.Media("next")
            "previous", "previous song", "prev", "last song" -> return Cmd.Media("previous")
            "mute", "silence" -> return Cmd.Volume(0, 0)
            "louder", "volume up", "vol up" -> return Cmd.Volume(null, 2)
            "quieter", "softer", "volume down", "vol down" -> return Cmd.Volume(null, -2)
            "brief", "briefing", "daily brief", "brief me", "morning brief" -> return Cmd.Brief
            "digest", "summarize notifications", "summarise notifications", "what did i miss", "what did i miss?" -> return Cmd.Digest
            "today", "agenda today", "my day", "what's today", "whats today" -> return Cmd.Agenda
        }
        Regex("^(?:volume|vol)(?: to)? (\\d{1,3})%?$").find(l)?.let { return Cmd.Volume(it.groupValues[1].toInt().coerceIn(0, 100), 0) }
        Regex("^play (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return Cmd.Play(it.groupValues[1]) }
        Regex("^focus(?: mode)?(?: for)?(?: (.+))?$").find(l)?.let { m ->
            val arg = m.groupValues[1]
            if (arg == "off" || arg == "stop" || arg == "end") return Cmd.Focus(0)
            val secs = if (arg.isBlank()) 25 * 60 else parseDuration(arg) ?: return@let
            return Cmd.Focus((secs / 60).coerceAtLeast(1))
        }
        if (l == "unfocus" || l == "end focus") return Cmd.Focus(0)
        Regex("^(?:theme|mode|switch to|go) (paper|light|white|void|dark|black|oled|netrunner|cyberpunk|hacker|matrix mode)$").find(l)?.let {
            val mode = when (it.groupValues[1]) {
                "paper", "light", "white" -> ThemeMode.PAPER
                "void", "dark", "black", "oled" -> ThemeMode.VOID
                else -> ThemeMode.NETRUNNER
            }
            return Cmd.Theme(mode)
        }
        if (l == "hack the planet" || l == "jack in" || l == "sudo hack") return Cmd.Theme(ThemeMode.NETRUNNER)
        if (Regex("^(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$").matches(l) && !l.contains(' ')) return Cmd.Url(t)
        if (l == "camera" || l == "take a photo" || l == "selfie") return Cmd.Camera
        if (l == "calendar" || l == "schedule") return Cmd.Calendar
        if (l == "agenda") return Cmd.Agenda
        if (l == "notifications" || l == "notifs") return Cmd.Notifications
        if (l == "quick settings" || l == "qs" || l == "toggles") return Cmd.QuickSettings
        if (l == "neofetch" || l == "whoami" || l == "uname -a" || l == "sysinfo") return Cmd.Neofetch
        if (l == "matrix" || l == "the matrix" || l == "follow the white rabbit") return Cmd.Matrix
        if (l == "lock" || l == "lock screen" || l == "lock phone") return Cmd.Lock
        Regex("^(?:open |show )?(.+?) settings$").find(l)?.let { m ->
            val key = m.groupValues[1].trim()
            if (Actions.SETTINGS.containsKey(key)) return Cmd.Setting(key, key)
        }
        if (Actions.SETTINGS.containsKey(l) && l !in setOf("settings", "apps", "time", "data")) return Cmd.Setting(l, l)
        return null
    }

    private val questionStarts = listOf(
        "what", "why", "how", "who", "when", "where", "which", "can ", "could ", "should ", "would ", "is ", "are ",
        "do ", "does ", "explain", "write", "draft", "summarize", "summarise", "translate", "help", "tell me",
        "give me", "plan", "make me", "create", "compose", "find me", "recommend", "suggest", "ask ", "claude",
        "hey ", "hi ", "hello", "compare", "calculate", "convert", "define", "describe", "list ", "brainstorm",
    )

    /** Heuristic: does this read like something to ask the AI rather than an app name? */
    fun looksLikeQuestion(t: String): Boolean {
        val l = t.trim().lowercase()
        if (l.endsWith("?")) return true
        if (l.startsWith("?") || l.startsWith("/ai") || l.startsWith("@claude")) return true
        if (questionStarts.any { l.startsWith(it) }) return true
        return l.split(Regex("\\s+")).size >= 5
    }
}
