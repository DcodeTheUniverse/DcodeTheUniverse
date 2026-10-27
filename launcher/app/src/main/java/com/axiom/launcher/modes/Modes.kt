package com.axiom.launcher.modes

import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.ui.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** How a mode shows notifications on the home screen. */
enum class NotifPresentation(val label: String) {
    FULL("Count and digest"), COUNT("Count only"), DIGEST_ONLY("Digest on request"), HIDDEN("Hidden")
}

/** How work-profile apps appear in this mode. */
enum class WorkApps(val label: String) { ALL("Mixed in"), WORK_FIRST("Work first"), HIDE("Hidden"), ONLY("Work only") }

/** Where Solis may send this mode's requests. */
enum class AiPrivacy(val label: String, val blurb: String) {
    CLOUD("Cloud", "Any eligible provider; providers that collect data are excluded"),
    CLOUD_ZDR("Cloud, zero retention", "Only endpoints with zero data retention"),
    LOCAL_ONLY("On-device / local only", "Only a local runtime you configured"),
    OFF("Solis off", "No AI in this mode; on-device commands still work");

    fun constraint(): PrivacyConstraint = when (this) {
        CLOUD -> PrivacyConstraint(cloudAllowed = true, zdr = false, denyDataCollection = true)
        CLOUD_ZDR -> PrivacyConstraint(cloudAllowed = true, zdr = true, denyDataCollection = true)
        LOCAL_ONLY, OFF -> PrivacyConstraint(cloudAllowed = false, zdr = true, denyDataCollection = true)
    }
}

/**
 * A launcher mode: a layout and behavior preset (favorites, shortcuts, notifications, AI budget and
 * privacy). Modes are a launcher feature — they don't create Android profiles or grant permissions.
 */
data class LauncherMode(
    val id: String,
    val name: String,
    val glyph: String,
    /** Which Solis memory space tasks started in this mode use. */
    val space: Space,
    /** Mode-specific pinned apps; null = share the default favorites. */
    val favorites: List<String>? = null,
    val showSuggestions: Boolean = true,
    val notifications: NotifPresentation = NotifPresentation.FULL,
    val workApps: WorkApps = WorkApps.ALL,
    /** Daily AI spending cap for this mode (USD); null = only the global caps. */
    val aiDailyBudgetUsd: Double? = null,
    val aiPrivacy: AiPrivacy = AiPrivacy.CLOUD,
    val blockDistracting: Boolean = false,
    /** Omnibox commands shown as one-tap shortcuts. */
    val shortcuts: List<String> = emptyList(),
    val theme: ThemeMode? = null,
    /** Travel: a second clock for home time. */
    val homeTimezone: String? = null,
    /** Treat the network as metered: fewer parallel tasks, cheaper routing. */
    val dataSaver: Boolean = false,
    /** Repository coding tasks go to in this mode ("owner/name"); null = the default in Settings › Coding. */
    val codingRepo: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("glyph", glyph); put("space", space.name)
        put("favorites", favorites?.let { JSONArray(it) } ?: JSONObject.NULL)
        put("showSuggestions", showSuggestions); put("notifications", notifications.name); put("workApps", workApps.name)
        put("aiDailyBudgetUsd", aiDailyBudgetUsd ?: JSONObject.NULL); put("aiPrivacy", aiPrivacy.name)
        put("blockDistracting", blockDistracting); put("shortcuts", JSONArray(shortcuts))
        put("theme", theme?.id ?: JSONObject.NULL); put("homeTimezone", homeTimezone ?: JSONObject.NULL); put("dataSaver", dataSaver)
        put("codingRepo", codingRepo ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): LauncherMode = LauncherMode(
            id = o.getString("id"), name = o.optString("name"), glyph = o.optString("glyph", "◐"),
            space = runCatching { Space.valueOf(o.optString("space")) }.getOrDefault(Space.PERSONAL),
            favorites = if (o.isNull("favorites")) null else o.optJSONArray("favorites")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            showSuggestions = o.optBoolean("showSuggestions", true),
            notifications = runCatching { NotifPresentation.valueOf(o.optString("notifications")) }.getOrDefault(NotifPresentation.FULL),
            workApps = runCatching { WorkApps.valueOf(o.optString("workApps")) }.getOrDefault(WorkApps.ALL),
            aiDailyBudgetUsd = if (o.isNull("aiDailyBudgetUsd")) null else o.optDouble("aiDailyBudgetUsd"),
            aiPrivacy = runCatching { AiPrivacy.valueOf(o.optString("aiPrivacy")) }.getOrDefault(AiPrivacy.CLOUD),
            blockDistracting = o.optBoolean("blockDistracting"),
            shortcuts = o.optJSONArray("shortcuts")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            theme = if (o.isNull("theme")) null else ThemeMode.entries.firstOrNull { it.id == o.optString("theme") },
            homeTimezone = if (o.isNull("homeTimezone")) null else o.optString("homeTimezone"),
            dataSaver = o.optBoolean("dataSaver"),
            codingRepo = if (o.isNull("codingRepo")) null else o.optString("codingRepo").ifBlank { null },
        )

        fun defaults(): List<LauncherMode> = listOf(
            LauncherMode("personal", "Personal", "◐", Space.PERSONAL, shortcuts = listOf("camera", "agenda", "brief")),
            LauncherMode("work", "Work", "▣", Space.WORK, favorites = emptyList(), notifications = NotifPresentation.DIGEST_ONLY,
                workApps = WorkApps.WORK_FIRST, aiDailyBudgetUsd = 0.50, aiPrivacy = AiPrivacy.CLOUD_ZDR,
                shortcuts = listOf("agenda", "digest", "focus 50")),
            LauncherMode("focus", "Focus", "◎", Space.PERSONAL, showSuggestions = false, notifications = NotifPresentation.HIDDEN,
                aiDailyBudgetUsd = 0.10, blockDistracting = true, shortcuts = listOf("timer 25 min", "focus off")),
            LauncherMode("travel", "Travel", "✈", Space.PERSONAL, notifications = NotifPresentation.COUNT, aiDailyBudgetUsd = 0.30,
                homeTimezone = TimeZone.getDefault().id, dataSaver = true,
                shortcuts = listOf("maps", "agenda", "battery")),
        )
    }
}

/** What a routine does when it fires. Starting AI work requires the user's explicit opt-in per routine. */
sealed interface RoutineAction {
    data class SwitchMode(val modeId: String) : RoutineAction
    /** An on-device command (alarm, timer, flashlight...); never sent to a model. */
    data class Command(val text: String) : RoutineAction
    /** Starts a Solis task. Only runs when [allowAi] is true and within [budgetUsd]. */
    data class AskSolis(val prompt: String, val allowAi: Boolean, val budgetUsd: Double) : RoutineAction

    fun toJson(): JSONObject = when (this) {
        is SwitchMode -> JSONObject().put("type", "mode").put("mode", modeId)
        is Command -> JSONObject().put("type", "command").put("text", text)
        is AskSolis -> JSONObject().put("type", "solis").put("prompt", prompt).put("allowAi", allowAi).put("budget", budgetUsd)
    }

    fun describe(): String = when (this) {
        is SwitchMode -> "switch to $modeId"
        is Command -> "run “$text”"
        is AskSolis -> "ask Solis: “${prompt.take(40)}”" + if (!allowAi) " (needs your OK)" else ""
    }

    companion object {
        fun fromJson(o: JSONObject): RoutineAction = when (o.optString("type")) {
            "mode" -> SwitchMode(o.getString("mode"))
            "solis" -> AskSolis(o.getString("prompt"), o.optBoolean("allowAi"), o.optDouble("budget", 0.05))
            else -> Command(o.optString("text"))
        }
    }
}

/** A user-configured routine: at a time on chosen days, do something. */
data class Routine(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val enabled: Boolean = true,
    /** Calendar.SUNDAY..SATURDAY */
    val days: Set<Int> = (1..7).toSet(),
    val hour: Int,
    val minute: Int,
    val action: RoutineAction,
    /** yyyyDDD of the last run, so a routine fires at most once a day. */
    val lastRunDay: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("days", JSONArray(days.toList()))
        .put("hour", hour).put("minute", minute).put("action", action.toJson()).put("lastRunDay", lastRunDay)

    /**
     * Due if enabled, today is a chosen day, the time has passed within [catchUpMs] (so a routine missed
     * while the phone was off still runs once), and it hasn't run today.
     */
    fun dueAt(now: Long, catchUpMs: Long = 60 * 60_000L): Boolean {
        if (!enabled) return false
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (c.get(Calendar.DAY_OF_WEEK) !in days) return false
        val today = dayKey(now)
        if (lastRunDay == today) return false
        val fire = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return now >= fire && now - fire <= catchUpMs
    }

    companion object {
        fun fromJson(o: JSONObject) = Routine(
            id = o.getString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled", true),
            days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: (1..7).toSet(),
            hour = o.optInt("hour"), minute = o.optInt("minute"),
            action = RoutineAction.fromJson(o.getJSONObject("action")), lastRunDay = o.optInt("lastRunDay"),
        )

        fun dayKey(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

        fun defaults(): List<Routine> = listOf(
            Routine(name = "Workday starts", enabled = false, days = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
                hour = 9, minute = 0, action = RoutineAction.SwitchMode("work")),
            Routine(name = "Evening", enabled = false, days = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
                hour = 18, minute = 0, action = RoutineAction.SwitchMode("personal")),
            // Off until the user turns it on; even then it asks on the home screen before using AI.
            Routine(name = "Morning brief", enabled = false, days = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
                hour = 7, minute = 30, action = RoutineAction.AskSolis(
                    "Give me my morning brief: today's agenda, anything notable in my notifications and notes, battery, any Claude Code jobs that need me, " +
                        "and one suggestion for what to focus on. Keep it short.", allowAi = false, budgetUsd = 0.05)),
        )
    }
}
