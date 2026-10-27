package com.axiom.launcher.solis.llm

import com.axiom.launcher.solis.catalog.CatalogParsers
import com.axiom.launcher.solis.catalog.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for the OpenAI-compatible Chat Completions wire format. One class serves:
 *  - [Flavor.OPENROUTER]: openrouter.ai, with provider privacy routing, reasoning effort, `models` fallbacks,
 *    usage cost accounting and app attribution headers.
 *  - [Flavor.LOCAL]: local runtimes (Ollama, llama.cpp server, LM Studio, vLLM...) on the device or LAN.
 */
class OpenAiCompatProvider(
    override val id: String,
    override val displayName: String,
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
    private val flavor: Flavor,
    private val http: OkHttpClient,
    private val localAssumeTools: () -> Boolean = { false },
    private val localContext: () -> Int = { 8192 },
) : LlmProvider {

    enum class Flavor { OPENROUTER, LOCAL }

    override val isLocal: Boolean get() = flavor == Flavor.LOCAL

    override fun isConfigured(): Boolean = when (flavor) {
        Flavor.OPENROUTER -> !apiKey().isNullOrBlank()
        Flavor.LOCAL -> baseUrl().isNotBlank()
    }

    private fun url(path: String) = baseUrl().trimEnd('/') + path

    private fun Request.Builder.auth(): Request.Builder {
        apiKey()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        if (flavor == Flavor.OPENROUTER) {
            header("HTTP-Referer", APP_URL)
            header("X-OpenRouter-Title", APP_TITLE)
            header("X-Title", APP_TITLE)
        }
        return this
    }

    override suspend fun listModels(): List<ModelInfo> {
        val req = Request.Builder().url(url("/models")).auth().get().build()
        val body = execute(req)
        return when (flavor) {
            Flavor.OPENROUTER -> CatalogParsers.openRouter(body, id)
            Flavor.LOCAL -> CatalogParsers.openAiCompatible(body, id, localAssumeTools(), localContext())
        }
    }

    /** OpenRouter only: spend and budget for the configured key (`GET /key`). */
    suspend fun keyStatus(): KeyStatus? {
        if (flavor != Flavor.OPENROUTER || !isConfigured()) return null
        val body = execute(Request.Builder().url(url("/key")).auth().get().build())
        val d = JSONObject(body).optJSONObject("data") ?: return null
        val eff = d.optJSONObject("effective_budget")
        return KeyStatus(
            label = d.optString("label"),
            usageDaily = d.optDouble("usage_daily", 0.0),
            usageMonthly = d.optDouble("usage_monthly", 0.0),
            limitRemaining = if (d.isNull("limit_remaining")) null else d.optDouble("limit_remaining"),
            budgetRemaining = eff?.let { if (it.isNull("remaining_usd")) null else it.optDouble("remaining_usd") },
            isFreeTier = d.optBoolean("is_free_tier"),
        )
    }

    data class KeyStatus(
        val label: String,
        val usageDaily: Double,
        val usageMonthly: Double,
        val limitRemaining: Double?,
        val budgetRemaining: Double?,
        val isFreeTier: Boolean,
    )

    private suspend fun execute(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(ioError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) cont.resumeWithException(httpError(it.code, text, it.header("Retry-After")))
                    else cont.resume(text)
                }
            }
        })
    }

    // ------------------------------------------------------------------ streaming

    override fun stream(request: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val body = buildBody(request).toString()
        val req = Request.Builder()
            .url(url("/chat/completions"))
            .auth()
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else ioError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close(httpError(resp.code, resp.body?.string().orEmpty().take(4000), resp.header("Retry-After")))
                        return
                    }
                    try {
                        val reader = resp.body?.charStream()?.buffered() ?: throw ProviderException(ErrorKind.NETWORK, "Empty response body")
                        parseStream(reader) { ev -> trySendBlocking(ev).isSuccess }
                        close()
                    } catch (e: ProviderException) {
                        close(e)
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else ioError(e))
                    } catch (e: Exception) {
                        close(ProviderException(ErrorKind.UNKNOWN, "Bad stream: ${e.message}", cause = e))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Reads SSE events and emits normalized [LlmEvent]s. [emit] returns false once the consumer is gone. */
    internal fun parseStream(reader: BufferedReader, emit: (LlmEvent) -> Boolean) {
        val calls = sortedMapOf<Int, PartialCall>()
        var nextSyntheticIndex = 1000
        var finish: String? = null
        var nativeFinish: String? = null
        var servedModel: String? = null
        var sawDone = false
        var consumerGone = false
        val reasoningDetails = JSONArray()
        val data = StringBuilder()

        fun send(ev: LlmEvent): Boolean = emit(ev).also { if (!it) consumerGone = true }

        fun dispatch(payload: String): Boolean {
            if (payload == "[DONE]") { sawDone = true; return false }
            val o = JSONObject(payload)
            o.optJSONObject("error")?.let { throw streamError(it) }
            if (o.has("model")) servedModel = o.optString("model").ifBlank { servedModel }
            val choices = o.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val ch = choices.getJSONObject(0)
                ch.optJSONObject("error")?.let { throw streamError(it) }
                val delta = ch.optJSONObject("delta") ?: ch.optJSONObject("message")
                if (delta != null) {
                    if (!delta.isNull("content")) {
                        val t = delta.optString("content")
                        if (t.isNotEmpty() && !send(LlmEvent.TextDelta(t))) return false
                    }
                    val r = if (!delta.isNull("reasoning")) delta.optString("reasoning") else ""
                    if (r.isNotEmpty() && !send(LlmEvent.ReasoningDelta(r))) return false
                    delta.optJSONArray("reasoning_details")?.let { arr -> for (k in 0 until arr.length()) reasoningDetails.put(arr.get(k)) }
                    delta.optJSONArray("tool_calls")?.let { arr ->
                        for (k in 0 until arr.length()) {
                            val tc = arr.getJSONObject(k)
                            val idx = if (tc.has("index")) tc.getInt("index") else {
                                val id = tc.optString("id")
                                calls.entries.firstOrNull { it.value.id == id && id.isNotEmpty() }?.key ?: nextSyntheticIndex++
                            }
                            val pc = calls.getOrPut(idx) { PartialCall() }
                            tc.optString("id").takeIf { it.isNotEmpty() }?.let { pc.id = it }
                            tc.optJSONObject("function")?.let { fn ->
                                fn.optString("name").takeIf { it.isNotEmpty() }?.let { pc.name = it }
                                if (!fn.isNull("arguments")) pc.args.append(fn.optString("arguments"))
                            }
                        }
                    }
                }
                if (!ch.isNull("finish_reason")) finish = ch.optString("finish_reason").ifBlank { finish }
                if (!ch.isNull("native_finish_reason")) nativeFinish = ch.optString("native_finish_reason").ifBlank { nativeFinish }
            }
            o.optJSONObject("usage")?.let { u ->
                val pt = u.optJSONObject("prompt_tokens_details")
                val ct = u.optJSONObject("completion_tokens_details")
                val ev = LlmEvent.Usage(
                    inputTokens = u.optInt("prompt_tokens"),
                    outputTokens = u.optInt("completion_tokens"),
                    cachedInputTokens = pt?.optInt("cached_tokens") ?: 0,
                    reasoningTokens = ct?.optInt("reasoning_tokens") ?: 0,
                    costUsd = if (u.has("cost") && !u.isNull("cost")) u.optDouble("cost").takeIf { !it.isNaN() } else null,
                    cacheWriteTokens = pt?.optInt("cache_write_tokens") ?: 0,
                )
                if (!send(ev)) return false
            }
            return true
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = data.toString()
                    data.setLength(0)
                    if (!dispatch(payload)) break
                }
                continue
            }
            if (line.startsWith(":")) continue // keep-alive comment, e.g. ": OPENROUTER PROCESSING"
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
            // other SSE fields (event:, id:, retry:) are not used by this API
        }
        if (consumerGone) return
        if (data.isNotEmpty() && !sawDone) dispatch(data.toString())
        if (consumerGone) return
        if (!sawDone && finish == null && calls.isEmpty()) {
            throw ProviderException(ErrorKind.NETWORK, "Stream ended before the model finished")
        }

        val ready = calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, pc ->
            ToolCall(pc.id.ifEmpty { "call_${System.nanoTime()}_$i" }, pc.name, pc.args.toString().ifBlank { "{}" })
        }
        for (c in ready) if (!emit(LlmEvent.ToolCallReady(c))) return
        val reason = when (finish) {
            "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.CONTENT_FILTER
            "refusal" -> FinishReason.REFUSAL
            else -> if (ready.isNotEmpty()) FinishReason.TOOL_CALLS else FinishReason.STOP
        }
        val native = if (reasoningDetails.length() > 0 && servedModel != null) NativeState(id, servedModel!!, reasoningDetails.toString()) else null
        emit(LlmEvent.Finish(reason, nativeFinish ?: finish, servedModel, native))
    }

    private class PartialCall { var id = ""; var name = ""; val args = StringBuilder() }

    // ------------------------------------------------------------------ request body

    internal fun buildBody(r: ChatRequest): JSONObject {
        val o = JSONObject()
        o.put("model", r.model)
        o.put("stream", true)
        o.put("max_tokens", r.maxOutputTokens)
        val msgs = JSONArray()
        val cache = r.cachePrefix && flavor == Flavor.OPENROUTER && explicitCache(r.model)
        if (r.system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content",
            if (cache) JSONArray().put(JSONObject().put("type", "text").put("text", r.system).put("cache_control", EPHEMERAL)) else r.system))
        val lastUser = if (cache) r.messages.indexOfLast { it.role == ChatRole.USER } else -1
        r.messages.forEachIndexed { i, m -> msgs.put(message(m, r.model, cacheMark = i == lastUser)) }
        o.put("messages", msgs)
        if (r.tools.isNotEmpty()) {
            o.put("tools", JSONArray().apply {
                r.tools.forEach { t ->
                    put(JSONObject().put("type", "function").put("function", JSONObject()
                        .put("name", t.name).put("description", t.description).put("parameters", JSONObject(t.parametersSchema))))
                }
            })
            o.put("tool_choice", "auto")
        }
        if (r.jsonMode) o.put("response_format", JSONObject().put("type", "json_object"))
        if (flavor == Flavor.OPENROUTER) {
            r.effort?.let { o.put("reasoning", JSONObject().put("effort", it.wire)) }
            if (r.inferenceFallbacks.isNotEmpty()) o.put("models", JSONArray(listOf(r.model) + r.inferenceFallbacks.filter { it != r.model }))
            o.put("provider", JSONObject().apply {
                put("data_collection", if (r.privacy.denyDataCollection) "deny" else "allow")
                if (r.privacy.zeroDataRetention) put("zdr", true)
                // Only route to endpoints that honor every parameter we send (tools, JSON mode, reasoning).
                if (r.tools.isNotEmpty() || r.jsonMode) put("require_parameters", true)
            })
            if (r.webSearch) o.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 5)))
        }
        return o
    }

    private fun message(m: ChatMessage, model: String, cacheMark: Boolean = false): JSONObject = when (m.role) {
        ChatRole.USER -> JSONObject().put("role", "user").put("content", userContent(m, cacheMark))
        ChatRole.TOOL -> JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.text)
        ChatRole.ASSISTANT -> JSONObject().apply {
            put("role", "assistant")
            put("content", if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) JSONObject.NULL else m.text)
            if (m.toolCalls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                m.toolCalls.forEach { c ->
                    put(JSONObject().put("id", c.id).put("type", "function")
                        .put("function", JSONObject().put("name", c.name).put("arguments", c.argumentsJson)))
                }
            })
            // Reasoning blocks must be echoed unchanged, and only to the model that produced them.
            val n = m.native
            if (flavor == Flavor.OPENROUTER && n != null && n.providerId == id && n.modelId == model && n.payload is String) {
                put("reasoning_details", JSONArray(n.payload))
            }
        }
    }

    private fun userContent(m: ChatMessage, cacheMark: Boolean): Any {
        if (cacheMark) {
            // Breakpoint after the latest user turn: later tool rounds re-read everything up to here from cache.
            val arr = userParts(m)
            for (i in arr.length() - 1 downTo 0) {
                val part = arr.getJSONObject(i)
                if (part.optString("type") == "text") { part.put("cache_control", EPHEMERAL); return arr }
            }
            return arr
        }
        if (m.parts.all { it is ContentPart.Text }) return m.text
        return userParts(m)
    }

    private fun userParts(m: ChatMessage): JSONArray {
        if (m.parts.all { it is ContentPart.Text }) return JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
        return JSONArray().apply {
            m.parts.forEach { p ->
                when (p) {
                    is ContentPart.Text -> put(JSONObject().put("type", "text").put("text", p.text))
                    is ContentPart.Image -> put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${p.mediaType};base64,${p.base64}")))
                    is ContentPart.Document -> put(JSONObject().put("type", "file").put("file", JSONObject()
                        .put("filename", p.name).put("file_data", "data:${p.mediaType};base64,${p.base64}")))
                }
            }
        }
    }

    // ------------------------------------------------------------------ errors

    private fun ioError(e: IOException): ProviderException = when (e) {
        is SocketTimeoutException -> ProviderException(ErrorKind.TIMEOUT, "Timed out waiting for $displayName", cause = e)
        is InterruptedIOException -> ProviderException(ErrorKind.TIMEOUT, "Request to $displayName was interrupted", cause = e)
        else -> ProviderException(ErrorKind.NETWORK, "Couldn't reach $displayName: ${e.message}", cause = e)
    }

    internal fun httpError(code: Int, body: String, retryAfter: String?): ProviderException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val msg = err?.optString("message")?.ifBlank { null } ?: body.take(300).ifBlank { "HTTP $code" }
        val meta = err?.optJSONObject("metadata")
        val retryMs = retryAfter?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val kind = when (code) {
            400 -> if (msg.contains("context", true) && (msg.contains("length", true) || msg.contains("too long", true))) ErrorKind.CONTEXT_LENGTH else ErrorKind.BAD_REQUEST
            401 -> ErrorKind.AUTH
            402 -> ErrorKind.BUDGET
            403 -> when {
                meta?.has("limit_source") == true -> ErrorKind.BUDGET
                meta?.has("reasons") == true || msg.contains("moderat", true) || msg.contains("flagged", true) -> ErrorKind.MODERATION
                else -> ErrorKind.AUTH
            }
            404 -> ErrorKind.UNAVAILABLE
            408 -> ErrorKind.TIMEOUT
            413 -> ErrorKind.CONTEXT_LENGTH
            429 -> ErrorKind.RATE_LIMIT
            in 500..599 -> ErrorKind.UNAVAILABLE
            else -> ErrorKind.UNKNOWN
        }
        return ProviderException(kind, "$displayName: $msg", code, retryMs)
    }

    private fun streamError(e: JSONObject): ProviderException {
        val code = e.opt("code")
        val msg = e.optString("message").ifBlank { "Provider error" }
        val numeric = (code as? Number)?.toInt() ?: (code as? String)?.toIntOrNull()
        if (numeric != null) return httpError(numeric, JSONObject().put("error", e).toString(), null)
        val kind = when (code?.toString()) {
            "rate_limit_exceeded" -> ErrorKind.RATE_LIMIT
            "context_length_exceeded" -> ErrorKind.CONTEXT_LENGTH
            else -> ErrorKind.UNAVAILABLE
        }
        return ProviderException(kind, "$displayName: $msg")
    }

    companion object {
        /**
         * Models that only cache with explicit `cache_control` breakpoints (Anthropic's, via OpenRouter). Most
         * other vendors cache prompt prefixes automatically and need nothing in the request.
         */
        fun explicitCache(model: String): Boolean = model.trimStart('~').startsWith("anthropic/")

        private val EPHEMERAL get() = JSONObject().put("type", "ephemeral")
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val APP_TITLE = "Axiom Launcher"
        const val APP_URL = "https://github.com/DcodeTheUniverse/DcodeTheUniverse"
        private val JSON = "application/json".toMediaType()
    }
}
