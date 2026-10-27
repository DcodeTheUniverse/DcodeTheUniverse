package com.axiom.launcher.solis.ondevice

import com.axiom.launcher.solis.llm.ChatMessage
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ChatRole
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.Effort
import org.json.JSONArray
import org.json.JSONObject

/*
 * What the launcher and the on-device model process say to each other. A request travels as a JSON file in the
 * app's cache (requests can be far bigger than a binder transaction allows); events come back as small messages.
 */

data class LocalToolCall(val name: String, val argsJson: String)

data class LocalToolResult(val name: String, val content: String)

data class LocalMessage(
    /** "user", "model" or "tool". */
    val role: String,
    val text: String = "",
    /** Absolute paths of image files in the app's cache. */
    val images: List<String> = emptyList(),
    val toolCalls: List<LocalToolCall> = emptyList(),
    /** For "tool": every result of one round, in call order. */
    val toolResults: List<LocalToolResult> = emptyList(),
) {
    /** Stable identity for "is this the conversation the model already has in memory?". */
    fun signature(): String = buildString {
        append(role).append('\u0001').append(text.trim()).append('\u0001')
        images.forEach { append(it).append(',') }
        append('\u0001')
        toolCalls.forEach { append(it.name).append('(').append(canonicalJson(it.argsJson)).append(')') }
        append('\u0001')
        toolResults.forEach { append(it.name).append('=').append(it.content).append('\u0002') }
    }

    fun toJson(): JSONObject = JSONObject().put("role", role).put("text", text)
        .put("images", JSONArray(images))
        .put("calls", JSONArray().apply { toolCalls.forEach { put(JSONObject().put("name", it.name).put("args", it.argsJson)) } })
        .put("results", JSONArray().apply { toolResults.forEach { put(JSONObject().put("name", it.name).put("content", it.content)) } })

    companion object {
        fun fromJson(o: JSONObject) = LocalMessage(
            role = o.getString("role"),
            text = o.optString("text"),
            images = o.optJSONArray("images").strings(),
            toolCalls = o.optJSONArray("calls").objects().map { LocalToolCall(it.getString("name"), it.optString("args", "{}")) },
            toolResults = o.optJSONArray("results").objects().map { LocalToolResult(it.getString("name"), it.optString("content")) },
        )
    }
}

data class LocalTool(val name: String, val description: String, val schemaJson: String) {
    /** The OpenAPI-style declaration LiteRT-LM puts in the prompt. */
    fun declaration(): String = JSONObject().put("name", name).put("description", description)
        .put("parameters", runCatching { JSONObject(schemaJson) }.getOrElse { JSONObject().put("type", "object") }).toString()
}

data class LocalRequest(
    val id: String,
    val modelId: String,
    val modelPath: String,
    /** "auto" (GPU, falling back to CPU), "gpu" or "cpu". */
    val backend: String,
    val contextTokens: Int,
    val system: String,
    val messages: List<LocalMessage>,
    val tools: List<LocalTool> = emptyList(),
    val maxOutputTokens: Int = 1024,
    /** 0 = no thinking. */
    val thinkingBudget: Int = 0,
    val jsonMode: Boolean = false,
    /** Chat format override ([GemmaTemplate.ID]) or null for the file's own. */
    val template: String? = null,
    val vision: Boolean = false,
    val speculative: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("modelId", modelId).put("modelPath", modelPath).put("backend", backend)
        .put("ctx", contextTokens).put("system", system)
        .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        .put("tools", JSONArray().apply { tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("schema", it.schemaJson)) } })
        .put("maxOut", maxOutputTokens).put("think", thinkingBudget).put("json", jsonMode).put("template", template ?: JSONObject.NULL)
        .put("vision", vision).put("spec", speculative)

    /** Everything that shapes the start of the prompt; a live conversation can only continue under the same key. */
    fun prefixKey(): String = listOf(modelPath, backend, contextTokens, system, tools.joinToString("\u0001") { it.declaration() },
        thinkingBudget > 0, jsonMode, template, vision).joinToString("\u0000").let { sha1(it) }

    companion object {
        fun fromJson(o: JSONObject) = LocalRequest(
            id = o.getString("id"), modelId = o.getString("modelId"), modelPath = o.getString("modelPath"),
            backend = o.optString("backend", "auto"), contextTokens = o.optInt("ctx", 8192), system = o.optString("system"),
            messages = o.optJSONArray("messages").objects().map { LocalMessage.fromJson(it) },
            tools = o.optJSONArray("tools").objects().map { LocalTool(it.getString("name"), it.optString("description"), it.optString("schema", "{}")) },
            maxOutputTokens = o.optInt("maxOut", 1024), thinkingBudget = o.optInt("think", 0), jsonMode = o.optBoolean("json"),
            template = if (o.isNull("template")) null else o.optString("template").ifBlank { null },
            vision = o.optBoolean("vision"), speculative = o.optBoolean("spec", true),
        )
    }
}

/** Measurements the runtime reports after each call. */
data class LocalStats(
    val prefillTokens: Int = 0,
    val decodeTokens: Int = 0,
    /** Tokens the conversation holds after this call. */
    val contextTokens: Int = 0,
    /** Tokens already in the model's memory from the previous call (not processed again). */
    val reusedTokens: Int = 0,
    val prefillTps: Double = 0.0,
    val decodeTps: Double = 0.0,
    val firstTokenMs: Long = 0,
    /** Time spent loading the model for this call (0 when it was already loaded). */
    val loadMs: Long = 0,
    val backend: String = "",
    /** "stop", "tool_calls" or "length". */
    val finish: String = "stop",
) {
    fun toJson(): JSONObject = JSONObject().put("prefill", prefillTokens).put("decode", decodeTokens).put("ctx", contextTokens)
        .put("reused", reusedTokens).put("ptps", prefillTps).put("dtps", decodeTps).put("ttft", firstTokenMs).put("load", loadMs)
        .put("backend", backend).put("finish", finish)

    companion object {
        fun fromJson(o: JSONObject) = LocalStats(
            o.optInt("prefill"), o.optInt("decode"), o.optInt("ctx"), o.optInt("reused"), o.optDouble("ptps", 0.0),
            o.optDouble("dtps", 0.0), o.optLong("ttft"), o.optLong("load"), o.optString("backend"), o.optString("finish", "stop"),
        )
    }
}

sealed interface LocalEvent {
    data class Text(val text: String) : LocalEvent
    data class Thought(val text: String) : LocalEvent
    data class Call(val call: LocalToolCall) : LocalEvent
    data class Done(val stats: LocalStats) : LocalEvent

    /** [kind] is an [com.axiom.launcher.solis.llm.ErrorKind] name, or "CANCELLED" / "CRASHED". */
    data class Failed(val kind: String, val message: String) : LocalEvent
}

/** Turns a provider-neutral request into a [LocalRequest]. Pure, so it can be tested without a phone. */
object LocalRequests {

    /** Thinking token budgets for the efforts an on-device model offers (none for quick answers). */
    fun thinkingBudget(effort: Effort?): Int = when (effort) {
        null, Effort.NONE, Effort.MINIMAL, Effort.LOW -> 0
        Effort.MEDIUM -> 512
        Effort.HIGH -> 1024
        Effort.XHIGH, Effort.MAX -> 2048
    }

    /**
     * @param saveImage writes an image part to a file the model process can read and returns its path.
     * @param extraSystem lines appended to the system prompt for this model.
     */
    fun from(
        req: ChatRequest,
        id: String,
        modelPath: String,
        backend: String,
        contextTokens: Int,
        template: String?,
        vision: Boolean,
        speculative: Boolean,
        thinking: Boolean,
        extraSystem: String?,
        saveImage: (ContentPart.Image) -> String,
    ): LocalRequest {
        val out = ArrayList<LocalMessage>()
        for (m in req.messages) {
            when (m.role) {
                ChatRole.USER -> out += LocalMessage("user", text = userText(m), images = if (vision) m.parts.filterIsInstance<ContentPart.Image>().map(saveImage) else emptyList())
                ChatRole.ASSISTANT -> out += LocalMessage("model", text = m.text, toolCalls = m.toolCalls.map { LocalToolCall(it.name, it.argumentsJson) })
                ChatRole.TOOL -> {
                    val r = LocalToolResult(m.toolName ?: "tool", (if (m.isError) "Error: " else "") + m.text)
                    // Results of one round become one tool message, the way Gemma reads them.
                    val last = out.lastOrNull()
                    if (last != null && last.role == "tool") out[out.size - 1] = last.copy(toolResults = last.toolResults + r)
                    else out += LocalMessage("tool", toolResults = listOf(r))
                }
            }
        }
        val system = listOfNotNull(req.system.takeIf { it.isNotBlank() }, extraSystem?.takeIf { req.tools.isNotEmpty() }).joinToString("\n\n")
        return LocalRequest(
            id = id, modelId = req.model, modelPath = modelPath, backend = backend, contextTokens = contextTokens,
            system = system, messages = out,
            tools = req.tools.map { LocalTool(it.name, it.description, it.parametersSchema) },
            maxOutputTokens = req.maxOutputTokens.coerceIn(64, contextTokens / 2),
            thinkingBudget = if (thinking) thinkingBudget(req.effort) else 0,
            jsonMode = req.jsonMode, template = template, vision = vision, speculative = speculative,
        )
    }

    /** Text of a user message; files the phone model can't read are named so it doesn't pretend to see them. */
    private fun userText(m: ChatMessage): String {
        val notes = m.parts.filterIsInstance<ContentPart.Document>().map { "[${it.name} is attached, but the on-device model can't read files.]" }
        return (notes + m.text).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Rough token count (characters / 3.6 for Gemma's tokenizer on mixed text) for context checks. */
    fun estimateTokens(r: LocalRequest): Int {
        val chars = r.system.length + r.tools.sumOf { it.declaration().length } +
            r.messages.sumOf { m -> m.text.length + m.toolCalls.sumOf { it.argsJson.length + it.name.length } + m.toolResults.sumOf { it.content.length + 16 } }
        return (chars / 3.6).toInt() + r.messages.sumOf { it.images.size } * 280 + r.messages.size * 6 + 32
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** JSON with sorted keys, so two encodings of the same arguments compare equal. */
internal fun canonicalJson(s: String): String = runCatching { canonical(JSONObject(s)) }.getOrDefault(s.trim())

private fun canonical(v: Any?): String = when (v) {
    is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(v.opt(k)) }
    is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
    is String -> JSONObject.quote(v)
    is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite() && kotlin.math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toString()
    null, JSONObject.NULL -> "null"
    else -> v.toString()
}

internal fun sha1(s: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
