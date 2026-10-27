package com.axiom.launcher.data

import com.axiom.launcher.modes.LauncherMode
import com.axiom.launcher.modes.Routine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings export and import as a JSON file the user keeps. It never contains API keys or the GitHub token
 * (those stay in the Keystore), Solis memory or tasks (Memory inspector has its own export), widget
 * placements (tied to this phone) or app usage history.
 */
object SettingsBackup {
    const val FORMAT = "axiom-settings"
    const val VERSION = 1

    enum class Group { LAYOUT, SOLIS }

    /**
     * Every key that can travel, with its stored type. SOLIS keys change where prompts are sent, what Solis
     * runs on its own or how much it may spend, so they are only imported when the user asks for them.
     */
    private val KEYS: Map<String, Pair<Char, Group>> = buildMap {
        listOf(
            "theme" to 's', "user_name" to 's', "show_icons" to 'b', "show_badges" to 'b', "mono_icons" to 'b', "icon_pack" to 's',
            "clock24" to 'b', "speak" to 'b', "effects" to 'b', "auto_kb" to 'b', "reduced_motion" to 's', "favorites" to 's',
            "hidden" to 's', "distracting" to 's', "renames" to 's', "notes" to 's', "modes" to 's', "active_mode" to 's',
            "saved_prompts" to 's', "proactive" to 'b',
        ).forEach { (k, t) -> put(k, t to Group.LAYOUT) }
        listOf(
            "engine" to 's', "web_search" to 'b', "local_url" to 's', "local_tools" to 'b', "local_ctx" to 'i', "pinned_models" to 's',
            "cap_day" to 'f', "cap_month" to 'f', "cap_narrator" to 'f', "cap_thread" to 'f', "unknown_price" to 'b',
            "max_concurrent" to 'i', "offline" to 'b', "share_w_p" to 'b', "share_p_w" to 'b', "auto_resume" to 'b',
            "gh_repo" to 's', "gh_notify" to 'b', "routines" to 's', "redact" to 'b', "batch_best" to 'b',
        ).forEach { (k, t) -> put(k, t to Group.SOLIS) }
    }

    fun group(key: String): Group? = KEYS[key]?.second

    class Parsed(val appVersion: String, val exported: Long, val values: Map<String, Any>) {
        fun count(g: Group) = values.keys.count { group(it) == g }
    }

    fun export(prefs: Prefs, appVersion: String, now: Long = System.currentTimeMillis()): String {
        val values = JSONObject()
        prefs.snapshot(KEYS.keys).forEach { (k, v) ->
            val t = KEYS.getValue(k).first
            if (typeOf(v) == t) values.put(k, JSONObject().put("t", t.toString()).put("v", if (v is Float) v.toDouble() else v))
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("app", appVersion).put("exported", now)
            .put("values", values).toString(2)
    }

    /** Checks everything before anything is written; throws with a message fit for the user. */
    fun parse(text: String): Parsed {
        val notOurs = "That file isn't an Axiom settings export"
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalArgumentException(notOurs)
        require(o.optString("format") == FORMAT) { notOurs }
        require(o.optInt("version") in 1..VERSION) { "That export is from a newer Axiom. Update Axiom first." }
        val vals = o.optJSONObject("values") ?: throw IllegalArgumentException("The export has no settings")
        val out = LinkedHashMap<String, Any>()
        for (k in vals.keys()) {
            val t = KEYS[k]?.first ?: continue // keys from other versions are skipped
            val e = vals.optJSONObject(k) ?: continue
            if (e.optString("t") != t.toString()) continue
            val raw = e.opt("v")
            val v: Any = when (t) {
                's' -> raw as? String
                'b' -> raw as? Boolean
                'i' -> (raw as? Number)?.toInt()
                'l' -> (raw as? Number)?.toLong()
                'f' -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() }
                else -> null
            } ?: continue
            if (valid(k, v)) out[k] = clamp(k, v)
        }
        require(out.isNotEmpty()) { "Nothing in that file could be imported" }
        return Parsed(o.optString("app"), o.optLong("exported"), out)
    }

    /** Writes the chosen values; imported notes are added to the current ones rather than replacing them. */
    fun apply(prefs: Prefs, parsed: Parsed, includeSolis: Boolean): Int {
        val chosen = parsed.values.filterKeys { includeSolis || group(it) == Group.LAYOUT }.toMutableMap()
        (chosen["notes"] as? String)?.let { chosen["notes"] = mergeNotes(prefs.snapshot(setOf("notes"))["notes"] as? String, it) }
        prefs.restore(chosen)
        return chosen.size
    }

    private fun mergeNotes(current: String?, imported: String): String {
        val byText = LinkedHashMap<String, JSONObject>()
        listOf(imported, current ?: "[]").forEach { json ->
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val n = a.getJSONObject(i)
                val prev = byText[n.getString("text")]
                if (prev == null) byText[n.getString("text")] = n
                else if (n.optBoolean("pinned")) prev.put("pinned", true)
            }
        }
        // Ids must stay unique after merging two phones' notes.
        val ids = HashSet<Long>()
        val merged = byText.values.sortedByDescending { it.getLong("created") }.map { n ->
            var id = n.getLong("id")
            while (!ids.add(id)) id++
            n.put("id", id)
        }
        return JSONArray(merged).toString()
    }

    private fun typeOf(v: Any): Char? = when (v) {
        is String -> 's'; is Boolean -> 'b'; is Int -> 'i'; is Long -> 'l'; is Float -> 'f'; else -> null
    }

    private fun valid(k: String, v: Any): Boolean = runCatching {
        when (k) {
            "favorites", "hidden", "distracting", "saved_prompts" -> JSONArray(v as String).let { a -> for (i in 0 until a.length()) a.getString(i) }
            "renames", "pinned_models" -> JSONObject(v as String).let { o -> o.keys().forEach { o.getString(it) } }
            "notes" -> JSONArray(v as String).let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { n -> n.getLong("id"); n.getString("text"); n.getLong("created") }
            }
            "modes" -> JSONArray(v as String).let { a -> require(a.length() > 0); for (i in 0 until a.length()) LauncherMode.fromJson(a.getJSONObject(i)) }
            "routines" -> JSONArray(v as String).let { a -> for (i in 0 until a.length()) Routine.fromJson(a.getJSONObject(i)) }
            "local_url" -> require((v as String).isBlank() || v.startsWith("http://") || v.startsWith("https://"))
            "engine" -> require(v in setOf("auto", "brain", "app"))
            "reduced_motion" -> require(v in setOf("system", "on", "off"))
        }
        true
    }.getOrDefault(false)

    private fun clamp(k: String, v: Any): Any = when (k) {
        "cap_day", "cap_month", "cap_narrator", "cap_thread" -> (v as Float).coerceIn(0f, 1000f)
        "max_concurrent" -> (v as Int).coerceIn(1, 6)
        "local_ctx" -> (v as Int).coerceIn(1024, 2_000_000)
        "user_name" -> (v as String).take(60)
        else -> v
    }
}
