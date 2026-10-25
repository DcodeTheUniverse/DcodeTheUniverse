package com.dcode.wallet.data

import android.content.Context
import android.util.Log
import com.dcode.wallet.security.CryptoBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for the wallet. The whole wallet is serialised to JSON and stored
 * as one AES-GCM encrypted file in app-private storage.
 */
object CardRepository {
    private const val TAG = "CardRepository"
    private const val FILE_NAME = "wallet.enc"
    private const val MAX_ACTIVITY = 200

    private var file: File? = null
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        _state.value = load(f)
    }

    fun upsert(card: Card) = update { s ->
        val exists = s.cards.any { it.id == card.id }
        val cards = if (exists) s.cards.map { if (it.id == card.id) card else it } else s.cards + card
        s.copy(cards = cards, defaultCardId = s.defaultCardId ?: card.id)
    }

    fun delete(cardId: String) = update { s ->
        val cards = s.cards.filter { it.id != cardId }
        s.copy(
            cards = cards,
            defaultCardId = if (s.defaultCardId == cardId) cards.firstOrNull()?.id else s.defaultCardId,
            activity = s.activity.filter { it.cardId != cardId },
        )
    }

    fun setDefault(cardId: String) = update { it.copy(defaultCardId = cardId) }

    fun log(cardId: String, description: String) = update { s ->
        s.copy(activity = (listOf(ActivityEntry(cardId = cardId, description = description)) + s.activity).take(MAX_ACTIVITY))
    }

    fun clearAll() = update { WalletState() }

    @Synchronized
    private fun update(transform: (WalletState) -> WalletState) {
        val next = transform(_state.value)
        _state.value = next
        file?.let { save(it, next) }
    }

    private fun load(f: File): WalletState {
        if (!f.exists()) return WalletState()
        return try {
            fromJson(JSONObject(String(CryptoBox.decrypt(f.readBytes()), Charsets.UTF_8)))
        } catch (e: Exception) {
            // Keep the unreadable file aside rather than overwriting it on the next save.
            Log.e(TAG, "Could not read wallet, starting empty", e)
            f.renameTo(File(f.parentFile, "$FILE_NAME.unreadable-${System.currentTimeMillis()}"))
            WalletState()
        }
    }

    private fun save(f: File, state: WalletState) {
        try {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(CryptoBox.encrypt(toJson(state).toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save wallet", e)
        }
    }

    private fun toJson(s: WalletState) = JSONObject().apply {
        put("version", 1)
        put("defaultCardId", s.defaultCardId ?: JSONObject.NULL)
        put("cards", JSONArray().apply {
            s.cards.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("number", c.number)
                    put("holder", c.holder)
                    put("expMonth", c.expMonth)
                    put("expYear", c.expYear)
                    put("cvv", c.cvv)
                    put("nickname", c.nickname)
                    put("colorIndex", c.colorIndex)
                    put("isVirtual", c.isVirtual)
                    put("createdAt", c.createdAt)
                })
            }
        })
        put("activity", JSONArray().apply {
            s.activity.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("cardId", a.cardId)
                    put("timestamp", a.timestamp)
                    put("description", a.description)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): WalletState {
        val cards = o.optJSONArray("cards") ?: JSONArray()
        val activity = o.optJSONArray("activity") ?: JSONArray()
        return WalletState(
            cards = (0 until cards.length()).map { i ->
                val c = cards.getJSONObject(i)
                Card(
                    id = c.getString("id"),
                    number = c.getString("number"),
                    holder = c.optString("holder"),
                    expMonth = c.getInt("expMonth"),
                    expYear = c.getInt("expYear"),
                    cvv = c.optString("cvv"),
                    nickname = c.optString("nickname"),
                    colorIndex = c.optInt("colorIndex"),
                    isVirtual = c.optBoolean("isVirtual"),
                    createdAt = c.optLong("createdAt"),
                )
            },
            defaultCardId = if (o.isNull("defaultCardId")) null else o.optString("defaultCardId"),
            activity = (0 until activity.length()).map { i ->
                val a = activity.getJSONObject(i)
                ActivityEntry(
                    id = a.getString("id"),
                    cardId = a.getString("cardId"),
                    timestamp = a.getLong("timestamp"),
                    description = a.getString("description"),
                )
            },
        )
    }
}
