package com.axiom.launcher.solis.catalog

import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ModelRef
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Long-context price tier: applies when the prompt has at least [minPromptTokens] tokens. */
data class PriceOverride(val minPromptTokens: Int, val inputPerToken: Double, val outputPerToken: Double)

/**
 * What Solis knows about one model. Everything here comes from provider metadata at runtime (or the
 * user's own settings for local runtimes); nothing is hardcoded per model.
 */
data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val contextLength: Int,
    val maxOutputTokens: Int? = null,
    /** USD per token. Null means unknown or variable (for example router meta-models). */
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val priceOverrides: List<PriceOverride> = emptyList(),
    val supportsTools: Boolean = false,
    val supportsJson: Boolean = false,
    val inputModalities: Set<String> = setOf("text"),
    /** Reasoning efforts the model accepts; empty = no effort control. */
    val efforts: List<Effort> = emptyList(),
    val reasoningMandatory: Boolean = false,
    val defaultEffort: Effort? = null,
    /** Published general-intelligence benchmark index (e.g. Artificial Analysis), when the catalog has one. */
    val qualityIndex: Double? = null,
    val expiresAt: Long? = null,
    /** A router that picks models itself (variable price). Never auto-selected by Solis. */
    val isMeta: Boolean = false,
    /** Batch-only variant, unusable for interactive streaming. */
    val isBatch: Boolean = false,
    val aliasOf: String? = null,
    val isLocal: Boolean = false,
    val createdAt: Long? = null,
) {
    val ref: ModelRef get() = ModelRef(provider, id)
    val isFree: Boolean get() = inputPrice == 0.0 && outputPrice == 0.0
    val priceKnown: Boolean get() = inputPrice != null && outputPrice != null
    /** Vendor family, e.g. "anthropic" for "anthropic/claude-x"; used to diversify fallbacks and verifiers. */
    val family: String get() = if (id.contains('/')) id.substringBefore('/').trimStart('~') else provider

    /** Estimated USD cost of one call, or null when pricing is unknown. Applies long-context overrides. */
    fun estimateCost(inputTokens: Int, outputTokens: Int): Double? {
        if (isLocal) return 0.0
        val inP = inputPrice ?: return null
        val outP = outputPrice ?: return null
        val tier = priceOverrides.filter { inputTokens >= it.minPromptTokens }.maxByOrNull { it.minPromptTokens }
        return inputTokens * (tier?.inputPerToken ?: inP) + outputTokens * (tier?.outputPerToken ?: outP)
    }

    fun supportsImages() = "image" in inputModalities
    fun supportsFiles() = "file" in inputModalities

    /** Maps a desired effort to the closest level this model accepts, preferring not to exceed it. */
    fun effortFor(desired: Effort?): Effort? {
        if (desired == null || efforts.isEmpty()) return null
        if (desired in efforts) return desired
        val lower = efforts.filter { it.ordinal < desired.ordinal && (it != Effort.NONE || !reasoningMandatory) }
        return lower.maxByOrNull { it.ordinal } ?: efforts.minByOrNull { it.ordinal }
    }
}

enum class Freshness { FRESH, STALE, MISSING }

/** Snapshot of discovered models for one provider plus when it was fetched. */
data class CatalogSnapshot(val provider: String, val fetchedAt: Long, val models: List<ModelInfo>) {
    fun freshness(now: Long, ttlMs: Long = DEFAULT_TTL_MS): Freshness =
        if (models.isEmpty()) Freshness.MISSING else if (now - fetchedAt > ttlMs) Freshness.STALE else Freshness.FRESH

    companion object {
        const val DEFAULT_TTL_MS = 24L * 3600_000
    }
}

object CatalogParsers {

    /** Parses OpenRouter's `GET /api/v1/models` response. */
    fun openRouter(json: String, providerId: String = "openrouter"): List<ModelInfo> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            runCatching { out += openRouterModel(m, providerId) }
        }
        return out
    }

    private fun openRouterModel(m: JSONObject, providerId: String): ModelInfo {
        val id = m.getString("id")
        val pricing = m.optJSONObject("pricing") ?: JSONObject()
        val inP = price(pricing.opt("prompt"))
        val outP = price(pricing.opt("completion"))
        val variable = (inP != null && inP < 0) || (outP != null && outP < 0)
        val overrides = ArrayList<PriceOverride>()
        pricing.optJSONArray("overrides")?.let { arr ->
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val min = o.optInt("min_prompt_tokens", -1)
                val oi = price(o.opt("prompt")); val oo = price(o.opt("completion"))
                if (min > 0 && oi != null && oo != null && oi >= 0 && oo >= 0) overrides += PriceOverride(min, oi, oo)
            }
        }
        val params = stringSet(m.optJSONArray("supported_parameters"))
        val arch = m.optJSONObject("architecture")
        val modalities = stringSet(arch?.optJSONArray("input_modalities")).ifEmpty { setOf("text") }
        val top = m.optJSONObject("top_provider")
        val reasoning = m.optJSONObject("reasoning")
        var efforts = stringSet(reasoning?.optJSONArray("supported_efforts")).mapNotNull { Effort.parse(it) }.sortedBy { it.ordinal }
        if (efforts.isEmpty() && reasoning == null && "reasoning" in params) {
            // OpenRouter normalizes reasoning.effort across vendors; without a published list assume the core levels.
            efforts = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
        }
        val quality = m.optJSONObject("benchmarks")?.optJSONObject("artificial_analysis")
            ?.let { if (it.isNull("intelligence_index")) null else it.optDouble("intelligence_index").takeIf { v -> !v.isNaN() } }
        val alias = m.optJSONObject("alias_target")?.optString("slug")?.ifBlank { null }
        return ModelInfo(
            provider = providerId,
            id = id,
            name = m.optString("name", id).ifBlank { id },
            contextLength = top?.optInt("context_length", 0)?.takeIf { it > 0 } ?: m.optInt("context_length", 8192),
            maxOutputTokens = top?.let { if (it.isNull("max_completion_tokens")) null else it.optInt("max_completion_tokens") }?.takeIf { it > 0 },
            inputPrice = if (variable) null else inP,
            outputPrice = if (variable) null else outP,
            priceOverrides = overrides,
            supportsTools = "tools" in params,
            supportsJson = "response_format" in params || "structured_outputs" in params,
            inputModalities = modalities,
            efforts = efforts,
            reasoningMandatory = reasoning?.optBoolean("mandatory", false) ?: false,
            defaultEffort = Effort.parse(reasoning?.optString("default_effort")),
            qualityIndex = quality,
            expiresAt = m.optString("expiration_date").takeIf { it.isNotBlank() && it != "null" }?.let { parseDate(it) },
            isMeta = variable,
            isBatch = id.endsWith(":batch"),
            aliasOf = alias,
            isLocal = false,
            createdAt = m.optLong("created", 0).takeIf { it > 0 }?.times(1000),
        )
    }

    /**
     * Parses an OpenAI-compatible `GET /v1/models` list (Ollama, llama.cpp server, LM Studio, vLLM...).
     * Such runtimes rarely publish capabilities, so tool support and context come from user settings.
     */
    fun openAiCompatible(json: String, providerId: String, assumeTools: Boolean, contextLength: Int): List<ModelInfo> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").ifBlank { return@mapNotNull null }
            ModelInfo(
                provider = providerId, id = id, name = id, contextLength = contextLength,
                inputPrice = 0.0, outputPrice = 0.0, supportsTools = assumeTools, supportsJson = false,
                isLocal = true, createdAt = m.optLong("created", 0).takeIf { it > 0 }?.times(1000),
            )
        }
    }

    private fun price(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun stringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toSet()
    }

    private fun parseDate(s: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(10))?.time
    }.getOrNull()

    // ---- slim cache format (only the fields Solis uses; parses much faster than the raw catalog) ----

    fun toCache(s: CatalogSnapshot): String {
        val arr = JSONArray()
        s.models.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("n", m.name); put("ctx", m.contextLength)
                m.maxOutputTokens?.let { put("mo", it) }
                m.inputPrice?.let { put("pi", it) }; m.outputPrice?.let { put("po", it) }
                if (m.priceOverrides.isNotEmpty()) put("ov", JSONArray().apply {
                    m.priceOverrides.forEach { o -> put(JSONArray().put(o.minPromptTokens).put(o.inputPerToken).put(o.outputPerToken)) }
                })
                put("t", m.supportsTools); put("j", m.supportsJson)
                put("mod", JSONArray(m.inputModalities.toList()))
                put("ef", JSONArray(m.efforts.map { it.wire }))
                put("rm", m.reasoningMandatory)
                m.defaultEffort?.let { put("de", it.wire) }
                m.qualityIndex?.let { put("q", it) }
                m.expiresAt?.let { put("x", it) }
                put("meta", m.isMeta); put("b", m.isBatch)
                m.aliasOf?.let { put("al", it) }
                put("loc", m.isLocal)
                m.createdAt?.let { put("c", it) }
            })
        }
        return JSONObject().put("provider", s.provider).put("fetchedAt", s.fetchedAt).put("models", arr).toString()
    }

    fun fromCache(text: String): CatalogSnapshot {
        val o = JSONObject(text)
        val provider = o.getString("provider")
        val arr = o.getJSONArray("models")
        val models = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            ModelInfo(
                provider = provider, id = m.getString("id"), name = m.optString("n", m.getString("id")),
                contextLength = m.optInt("ctx", 8192),
                maxOutputTokens = if (m.has("mo")) m.getInt("mo") else null,
                inputPrice = if (m.has("pi")) m.getDouble("pi") else null,
                outputPrice = if (m.has("po")) m.getDouble("po") else null,
                priceOverrides = m.optJSONArray("ov")?.let { ov ->
                    (0 until ov.length()).map { k -> ov.getJSONArray(k).let { PriceOverride(it.getInt(0), it.getDouble(1), it.getDouble(2)) } }
                } ?: emptyList(),
                supportsTools = m.optBoolean("t"), supportsJson = m.optBoolean("j"),
                inputModalities = stringSet(m.optJSONArray("mod")).ifEmpty { setOf("text") },
                efforts = stringSet(m.optJSONArray("ef")).mapNotNull { Effort.parse(it) }.sortedBy { it.ordinal },
                reasoningMandatory = m.optBoolean("rm"),
                defaultEffort = Effort.parse(m.optString("de")),
                qualityIndex = if (m.has("q")) m.getDouble("q") else null,
                expiresAt = if (m.has("x")) m.getLong("x") else null,
                isMeta = m.optBoolean("meta"), isBatch = m.optBoolean("b"),
                aliasOf = m.optString("al").ifBlank { null },
                isLocal = m.optBoolean("loc"),
                createdAt = if (m.has("c")) m.getLong("c") else null,
            )
        }
        return CatalogSnapshot(provider, o.getLong("fetchedAt"), models)
    }
}

/** In-memory view over all providers' snapshots. Thread-safe for reads after [replace]. */
class ModelCatalog {
    @Volatile private var snapshots: Map<String, CatalogSnapshot> = emptyMap()

    fun replace(snapshot: CatalogSnapshot) {
        snapshots = snapshots + (snapshot.provider to snapshot)
    }

    fun remove(provider: String) { snapshots = snapshots - provider }

    fun snapshot(provider: String): CatalogSnapshot? = snapshots[provider]
    fun all(): List<ModelInfo> = snapshots.values.flatMap { it.models }
    fun find(ref: ModelRef): ModelInfo? = snapshots[ref.provider]?.models?.firstOrNull { it.id == ref.model }
    fun providers(): Set<String> = snapshots.keys
}
