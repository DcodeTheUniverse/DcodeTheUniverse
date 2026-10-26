package com.axiom.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launcher preferences. Provider credentials are NOT stored here (see SecretStore), and the Solis brain's
 * memory, threads and policies live in its own database.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("axiom", Context.MODE_PRIVATE)

    /** The caller must keep a strong reference to [l] (SharedPreferences holds it weakly). */
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String?) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var theme: String get() = str("theme", "void"); set(v) = putStr("theme", v)
    var userName: String get() = str("user_name", ""); set(v) = putStr("user_name", v)
    var showIcons: Boolean get() = bool("show_icons", false); set(v) = putBool("show_icons", v)
    var monoIcons: Boolean get() = bool("mono_icons", true); set(v) = putBool("mono_icons", v)
    var clock24: Boolean get() = bool("clock24", true); set(v) = putBool("clock24", v)
    var speakReplies: Boolean get() = bool("speak", false); set(v) = putBool("speak", v)
    var effects: Boolean get() = bool("effects", true); set(v) = putBool("effects", v)
    var autoKeyboard: Boolean get() = bool("auto_kb", true); set(v) = putBool("auto_kb", v)
    var onboarded: Boolean get() = bool("onboarded", false); set(v) = putBool("onboarded", v)
    /** "system" follows Android's animation setting and battery saver; "on" forces reduced motion. */
    var reducedMotion: String get() = str("reduced_motion", "system"); set(v) = putStr("reduced_motion", v)

    /** "auto" = Solis brain when a provider is set up, else hand off; "brain"; "app" = always hand off to an assistant app. */
    var engine: String get() = str("engine", "auto"); set(v) = putStr("engine", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = putBool("web_search", v)

    // ---- Solis providers & routing (credentials are in SecretStore) ----
    var localBaseUrl: String get() = str("local_url", ""); set(v) = putStr("local_url", v.trim())
    var localTools: Boolean get() = bool("local_tools", false); set(v) = putBool("local_tools", v)
    var localContext: Int get() = sp.getInt("local_ctx", 8192); set(v) = sp.edit().putInt("local_ctx", v).apply()
    /** Role name -> "provider|model". */
    var pinnedModels: Map<String, String>
        get() = JSONObject(str("pinned_models", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        set(v) = putStr("pinned_models", JSONObject(v).toString())
    var dailyCapUsd: Float get() = sp.getFloat("cap_day", 1.0f); set(v) = sp.edit().putFloat("cap_day", v).apply()
    var monthlyCapUsd: Float get() = sp.getFloat("cap_month", 15f); set(v) = sp.edit().putFloat("cap_month", v).apply()
    var narratorCapUsd: Float get() = sp.getFloat("cap_narrator", 0.05f); set(v) = sp.edit().putFloat("cap_narrator", v).apply()
    var threadCapUsd: Float get() = sp.getFloat("cap_thread", 0.5f); set(v) = sp.edit().putFloat("cap_thread", v).apply()
    var allowUnknownPrice: Boolean get() = bool("unknown_price", false); set(v) = putBool("unknown_price", v)
    var maxConcurrent: Int get() = sp.getInt("max_concurrent", 3); set(v) = sp.edit().putInt("max_concurrent", v).apply()
    var offlineMode: Boolean get() = bool("offline", false); set(v) = putBool("offline", v)
    var workReadsPersonal: Boolean get() = bool("share_w_p", false); set(v) = putBool("share_w_p", v)
    var personalReadsWork: Boolean get() = bool("share_p_w", false); set(v) = putBool("share_p_w", v)
    var autoResume: Boolean get() = bool("auto_resume", false); set(v) = putBool("auto_resume", v)

    // ---- coding bridge (the GitHub token is in SecretStore) ----
    var githubRepo: String get() = str("gh_repo", ""); set(v) = putStr("gh_repo", v.trim())
    var githubLogin: String get() = str("gh_login", ""); set(v) = putStr("gh_login", v)
    var codingNotify: Boolean get() = bool("gh_notify", true); set(v) = putBool("gh_notify", v)

    // ---- modes & routines ----
    var modesJson: String? get() = sp.getString("modes", null); set(v) = putStr("modes", v)
    var routinesJson: String? get() = sp.getString("routines", null); set(v) = putStr("routines", v)
    var activeMode: String get() = str("active_mode", "personal"); set(v) = putStr("active_mode", v)
    var tempModeUntil: Long get() = sp.getLong("temp_until", 0); set(v) = sp.edit().putLong("temp_until", v).apply()
    var tempModeRevert: String get() = str("temp_revert", "personal"); set(v) = putStr("temp_revert", v)

    // ---- launcher state ----
    var favorites: List<String> get() = readStringList("favorites"); set(v) = writeStringList("favorites", v)
    var hidden: Set<String> get() = readStringList("hidden").toSet(); set(v) = writeStringList("hidden", v.toList())

    var distracting: Set<String>?
        get() = if (sp.contains("distracting")) readStringList("distracting").toSet() else null
        set(v) = if (v == null) sp.edit().remove("distracting").apply() else writeStringList("distracting", v.toList())

    var renames: Map<String, String>
        get() = JSONObject(str("renames", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        set(v) = putStr("renames", JSONObject(v).toString())

    var notes: List<Note>
        get() {
            val arr = JSONArray(str("notes", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Note(o.getLong("id"), o.getString("text"), o.getLong("created"), o.optBoolean("pinned"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created).put("pinned", it.pinned)) }
            putStr("notes", arr.toString())
        }

    /** Usage: key -> launches per hour of day. */
    fun loadUsage(): MutableMap<String, IntArray> {
        val o = JSONObject(str("usage", "{}"))
        val out = HashMap<String, IntArray>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = IntArray(24) { i -> a.optInt(i) }
        }
        return out
    }

    fun saveUsage(m: Map<String, IntArray>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) }
        putStr("usage", o.toString())
    }

    fun loadRecents(): List<String> = readStringList("recents")
    fun saveRecents(v: List<String>) = writeStringList("recents", v)

    private fun readStringList(key: String): List<String> {
        val arr = JSONArray(str(key, "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun writeStringList(key: String, v: List<String>) = putStr(key, JSONArray(v).toString())
}
