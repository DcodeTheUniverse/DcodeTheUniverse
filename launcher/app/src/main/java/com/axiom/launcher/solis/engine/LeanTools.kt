package com.axiom.launcher.solis.engine

import java.util.Locale

/**
 * Chooses the tools a small on-device model is offered for one request. Declaring all of Solis's ~50 tools
 * costs thousands of prompt tokens, which a phone reads at a few hundred a second and which crowd a small
 * context; most requests need two or three groups. Cloud models keep the full set.
 */
object LeanTools {
    private val KEYWORDS: Map<ToolGroup, List<String>> = mapOf(
        ToolGroup.CLOCK to listOf("timer", "alarm", "wake me", "wake up", "countdown", "remind me in", "minutes", "o'clock"),
        ToolGroup.CALENDAR to listOf("calendar", "meeting", "event", "schedule", "appointment", "agenda", "busy", "free on", "free at", "today", "tomorrow", "tonight", "this week", "next week"),
        ToolGroup.CONTACTS to listOf("contact", "phone number", "number for", "number of", "email address"),
        ToolGroup.COMMUNICATION to listOf("text ", "sms", "message", "email", "e-mail", "mail ", "call ", "dial", "whatsapp", "send ", "reply", "tell "),
        ToolGroup.MEDIA to listOf("play ", "music", "song", "playlist", "album", "pause", "skip", "volume", "podcast", "navigate", "directions", "map", "drive to", "route", "how far"),
        ToolGroup.SYSTEM to listOf("wifi", "wi-fi", "bluetooth", "flashlight", "torch", "brightness", "settings", "dark mode", "theme", "mode", "focus", "do not disturb", "battery", "storage", "lock the", "lock my"),
        ToolGroup.NOTIFICATIONS to listOf("notification", "missed", "unread", "what did i miss"),
        ToolGroup.CLIPBOARD to listOf("clipboard", "copied", "paste", "copy "),
        ToolGroup.NOTES to listOf("note", "jot", "write down", "shopping list", "to-do", "todo"),
        ToolGroup.FILES_READ to listOf("file", "document", "pdf", "download", "folder", "photo", "picture", "screenshot", "read my", ".txt", ".csv"),
        ToolGroup.FILES_OPEN to listOf("file", "document", "pdf", "open my"),
        ToolGroup.APPS to listOf("open ", "launch", "start ", " app", "website", "url", "http"),
        ToolGroup.WEB to listOf("http", "www.", "link", "website", "web page", "article", "search the web", "look up", "online", "news", "latest", "weather"),
        ToolGroup.CODE to listOf("github", "repo", "pull request", " pr ", " pr#", "commit", "branch", "claude code", "coding"),
    )

    /** Small and useful for almost anything: Solis's memory. */
    private val ALWAYS = setOf(ToolGroup.MEMORY)

    /** When nothing in the request points anywhere: the everyday phone actions. */
    private val DEFAULT = setOf(ToolGroup.APPS, ToolGroup.CLOCK, ToolGroup.NOTES)

    /** Spawning other tasks is for big cloud jobs, never for the phone's model. */
    private val NEVER = setOf(ToolGroup.DELEGATE)

    /** Rough cap on declaration size, in characters (~1,700 tokens). */
    const val BUDGET_CHARS = 6_000

    fun pick(text: String, tools: List<SolisTool>): List<SolisTool> {
        val t = " " + text.lowercase(Locale.ROOT) + " "
        val hits = KEYWORDS.mapValues { (_, words) -> words.count { it in t } }.filterValues { it > 0 }
        val groups = (if (hits.isEmpty()) DEFAULT else hits.keys) + ALWAYS - NEVER
        // Most-mentioned groups first, memory last, so the budget trims the least related tools.
        val ordered = tools.filter { it.group in groups }
            .sortedWith(compareBy<SolisTool> { if (it.group in ALWAYS) 1 else 0 }.thenByDescending { hits[it.group] ?: 0 })
        val out = ArrayList<SolisTool>()
        var size = 0
        for (tool in ordered) {
            val cost = cost(tool)
            if (size + cost > BUDGET_CHARS && tool.group !in ALWAYS) continue
            out += tool
            size += cost
        }
        return out
    }

    fun cost(t: SolisTool): Int = t.name.length + t.description.length + t.spec.parametersSchema.length + 40
}
