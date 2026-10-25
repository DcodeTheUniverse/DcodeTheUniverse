package com.dcode.launcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thin persistence layer. All state lives in the ViewModel; this only reads/writes it. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dcode", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString("theme", "void") ?: "void"
        set(v) = sp.edit().putString("theme", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var showIcons: Boolean
        get() = sp.getBoolean("show_icons", false)
        set(v) = sp.edit().putBoolean("show_icons", v).apply()

    var monoIcons: Boolean
        get() = sp.getBoolean("mono_icons", true)
        set(v) = sp.edit().putBoolean("mono_icons", v).apply()

    var clock24: Boolean
        get() = sp.getBoolean("clock24", true)
        set(v) = sp.edit().putBoolean("clock24", v).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", false)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var effects: Boolean
        get() = sp.getBoolean("effects", true)
        set(v) = sp.edit().putBoolean("effects", v).apply()

    var autoKeyboard: Boolean
        get() = sp.getBoolean("auto_kb", true)
        set(v) = sp.edit().putBoolean("auto_kb", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** "app" = hand off to the Claude app (subscription), "api" = in-launcher agent with API key. */
    var engine: String
        get() = sp.getString("engine", "auto") ?: "auto"
        set(v) = sp.edit().putString("engine", v).apply()

    var model: String
        get() = sp.getString("model", "claude-opus-5-5") ?: "claude-opus-5-5"
        set(v) = sp.edit().putString("model", v).apply()

    var effort: String
        get() = sp.getString("effort", "low") ?: "low"
        set(v) = sp.edit().putString("effort", v).apply()

    var webSearch: Boolean
        get() = sp.getBoolean("web_search", true)
        set(v) = sp.edit().putBoolean("web_search", v).apply()

    var focusUntil: Long
        get() = sp.getLong("focus_until", 0L)
        set(v) = sp.edit().putLong("focus_until", v).apply()

    var favorites: List<String>
        get() = readStringList("favorites")
        set(v) = writeStringList("favorites", v)

    var hidden: Set<String>
        get() = readStringList("hidden").toSet()
        set(v) = writeStringList("hidden", v.toList())

    var distracting: Set<String>?
        get() = if (sp.contains("distracting")) readStringList("distracting").toSet() else null
        set(v) = if (v == null) sp.edit().remove("distracting").apply() else writeStringList("distracting", v.toList())

    var renames: Map<String, String>
        get() {
            val o = JSONObject(sp.getString("renames", "{}") ?: "{}")
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        set(v) = sp.edit().putString("renames", JSONObject(v).toString()).apply()

    var memories: List<String>
        get() = readStringList("memories")
        set(v) = writeStringList("memories", v)

    var notes: List<Note>
        get() {
            val arr = JSONArray(sp.getString("notes", "[]") ?: "[]")
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Note(o.getLong("id"), o.getString("text"), o.getLong("created"), o.optBoolean("pinned"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created).put("pinned", it.pinned)) }
            sp.edit().putString("notes", arr.toString()).apply()
        }

    /** Usage: key -> IntArray(24) launches per hour of day, plus last-launch time. */
    fun loadUsage(): MutableMap<String, IntArray> {
        val o = JSONObject(sp.getString("usage", "{}") ?: "{}")
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
        sp.edit().putString("usage", o.toString()).apply()
    }

    fun loadRecents(): List<String> = readStringList("recents")
    fun saveRecents(v: List<String>) = writeStringList("recents", v)

    fun loadChat(): List<ChatMsg> {
        val arr = JSONArray(sp.getString("chat", "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val role = runCatching { ChatRole.valueOf(o.getString("role")) }.getOrNull() ?: return@mapNotNull null
            ChatMsg(o.getLong("id"), role, o.getString("text"), error = o.optBoolean("error"))
        }
    }

    fun saveChat(msgs: List<ChatMsg>) {
        val arr = JSONArray()
        msgs.takeLast(80).forEach {
            arr.put(JSONObject().put("id", it.id).put("role", it.role.name).put("text", it.text).put("error", it.error))
        }
        sp.edit().putString("chat", arr.toString()).apply()
    }

    // --- API key, encrypted with a hardware-backed Keystore key ---

    var apiKey: String
        get() {
            val enc = sp.getString("api_key_enc", null) ?: return ""
            return runCatching { decrypt(enc) }.getOrDefault("")
        }
        set(v) {
            if (v.isBlank()) sp.edit().remove("api_key_enc").apply()
            else sp.edit().putString("api_key_enc", encrypt(v.trim())).apply()
        }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(c.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(enc: String): String {
        val (iv, body) = enc.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(body, Base64.NO_WRAP)))
    }

    private fun readStringList(key: String): List<String> {
        val arr = JSONArray(sp.getString(key, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun writeStringList(key: String, v: List<String>) {
        sp.edit().putString(key, JSONArray(v).toString()).apply()
    }

    companion object {
        private const val KEY_ALIAS = "dcode_api_key"
    }
}
