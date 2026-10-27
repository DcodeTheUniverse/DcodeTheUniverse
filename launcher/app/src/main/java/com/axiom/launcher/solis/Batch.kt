package com.axiom.launcher.solis

import com.axiom.launcher.solis.memory.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class BatchState(val label: String) { SUBMITTED("Waiting for the batch"), DONE("Done"), FAILED("Failed"), CANCELLED("Cancelled") }

/** One request sent through Anthropic's Message Batches API: half the price, answered within 24 hours. */
data class BatchJob(
    val id: String,
    val title: String,
    val prompt: String,
    /** What was actually sent (system prompt and user message), so the answer can be restored the same way. */
    val system: String,
    val user: String,
    val model: String,
    val modelName: String,
    val batchId: String,
    val createdAt: Long,
    val space: Space,
    val modeId: String,
    val estCostUsd: Double,
    val state: BatchState = BatchState.SUBMITTED,
    val threadId: String? = null,
    val error: String? = null,
    val costUsd: Double? = null,
    val checkedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("prompt", prompt); put("system", system); put("user", user); put("model", model)
        put("modelName", modelName); put("batchId", batchId); put("createdAt", createdAt); put("space", space.name); put("modeId", modeId)
        put("estCostUsd", estCostUsd); put("state", state.name); put("threadId", threadId ?: JSONObject.NULL); put("error", error ?: JSONObject.NULL)
        put("costUsd", costUsd ?: JSONObject.NULL); put("checkedAt", checkedAt)
    }

    companion object {
        private fun JSONObject.s(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).ifEmpty { null }

        fun fromJson(o: JSONObject) = BatchJob(
            id = o.getString("id"), title = o.optString("title"), prompt = o.optString("prompt"), system = o.optString("system"),
            user = o.optString("user"), model = o.getString("model"), modelName = o.optString("modelName"), batchId = o.getString("batchId"),
            createdAt = o.optLong("createdAt"), space = runCatching { Space.valueOf(o.optString("space")) }.getOrDefault(Space.PERSONAL),
            modeId = o.optString("modeId", "personal"), estCostUsd = o.optDouble("estCostUsd", 0.0),
            state = runCatching { BatchState.valueOf(o.optString("state")) }.getOrDefault(BatchState.SUBMITTED),
            threadId = o.s("threadId"), error = o.s("error"), costUsd = if (o.isNull("costUsd") || !o.has("costUsd")) null else o.optDouble("costUsd"),
            checkedAt = o.optLong("checkedAt"),
        )
    }
}

class BatchException(message: String) : Exception(message)

/** What a finished batch request returned. */
sealed interface BatchOutcome {
    data class Pending(val status: String) : BatchOutcome
    data class Succeeded(val text: String, val inputTokens: Int, val outputTokens: Int) : BatchOutcome
    data class Failed(val reason: String) : BatchOutcome
}

/** The three calls Axiom needs from the Message Batches API: create, check, read results. */
class AnthropicBatches(
    private val http: OkHttpClient,
    private val key: () -> String?,
    private val baseUrl: String = "https://api.anthropic.com",
) {
    private val json = "application/json".toMediaType()

    suspend fun create(customId: String, model: String, system: String, user: String, maxTokens: Int): String {
        val params = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val body = JSONObject().put("requests", JSONArray().put(JSONObject().put("custom_id", customId).put("params", params)))
        val r = JSONObject(call(request("$baseUrl/v1/messages/batches").post(body.toString().toRequestBody(json)).build()))
        return r.optString("id").ifBlank { throw BatchException("Anthropic didn't return a batch id") }
    }

    /** The batch's result for [customId], or [BatchOutcome.Pending] while it is still processing. */
    suspend fun outcome(batchId: String, customId: String): BatchOutcome {
        require(batchId.matches(Regex("[A-Za-z0-9_-]+"))) { "Bad batch id" }
        val b = JSONObject(call(request("$baseUrl/v1/messages/batches/$batchId").get().build()))
        val status = b.optString("processing_status")
        if (status != "ended") return BatchOutcome.Pending(status)
        val url = b.optString("results_url").ifBlank { return BatchOutcome.Failed("The batch ended without results") }
        // The key only ever goes to Anthropic's own API host.
        if (url.toHttpUrl().host != baseUrl.toHttpUrl().host) return BatchOutcome.Failed("Unexpected results address")
        val lines = call(request(url).get().build()).lineSequence().filter { it.isNotBlank() }
        for (line in lines) {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (o.optString("custom_id") != customId) continue
            val res = o.optJSONObject("result") ?: return BatchOutcome.Failed("No result")
            return when (res.optString("type")) {
                "succeeded" -> {
                    val msg = res.optJSONObject("message") ?: JSONObject()
                    val content = msg.optJSONArray("content") ?: JSONArray()
                    val text = (0 until content.length()).map { content.getJSONObject(it) }
                        .filter { it.optString("type") == "text" }.joinToString("\n\n") { it.optString("text") }
                    val usage = msg.optJSONObject("usage") ?: JSONObject()
                    BatchOutcome.Succeeded(text, usage.optInt("input_tokens"), usage.optInt("output_tokens"))
                }
                "errored" -> BatchOutcome.Failed(res.optJSONObject("error")?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "The request failed")
                "canceled" -> BatchOutcome.Failed("Cancelled")
                "expired" -> BatchOutcome.Failed("Expired before Anthropic got to it (24 hours)")
                else -> BatchOutcome.Failed("Unknown result")
            }
        }
        return BatchOutcome.Failed("The result wasn't in the batch")
    }

    suspend fun cancel(batchId: String) {
        require(batchId.matches(Regex("[A-Za-z0-9_-]+"))) { "Bad batch id" }
        call(request("$baseUrl/v1/messages/batches/$batchId/cancel").post("".toRequestBody(json)).build())
    }

    private fun request(url: String): Request.Builder {
        val k = key() ?: throw BatchException("Batch requests need an Anthropic API key (Settings › Providers)")
        return Request.Builder().url(url).header("x-api-key", k).header("anthropic-version", "2023-06-01")
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val c = http.newCall(req)
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(BatchException(e.message ?: "Network error")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (r.isSuccessful) cont.resume(body)
                        else cont.resumeWithException(BatchException(runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                            ?: "Anthropic answered ${r.code}"))
                    }
                }
            })
        }
    }
}
