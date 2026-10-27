package com.axiom.launcher.solis.ondevice

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException

/** One model call at a time, on a worker thread of the model process. */
interface ModelRuntime {
    /** Runs [req] to the end, reporting through [emit]; [onState] gets ("loading" | "ready" | "gpu_failed", detail). */
    fun generate(req: LocalRequest, emit: (LocalEvent) -> Unit, onState: (String, String) -> Unit)

    /** Loads the model for [req]'s model, backend and context without answering anything; returns the load time. */
    fun prepare(req: LocalRequest, onState: (String, String) -> Unit): Long

    /** Stops the running call (any thread). */
    fun cancel()

    /** Reads a model file's header: kind, modalities, context, speculative decoding. */
    fun inspect(path: String): JSONObject

    /** Frees the engine and the conversation's memory. */
    fun unload()
}

/**
 * Runs `.litertlm` models with Google's LiteRT-LM. Plain Kotlin on purpose: the same class runs inside the
 * phone's model process and, against LiteRT-LM's desktop build, in the real-model checks.
 *
 * Solis sends the whole conversation on every call (the provider contract is stateless). Re-reading it all on
 * a phone costs seconds, so the runtime keeps the last conversation alive: when the next request is that same
 * conversation plus one new message (a tool round or the user's next turn), only the new message is processed.
 */
@OptIn(ExperimentalApi::class)
class LiteRtRuntime(
    private val cacheDir: File,
    /** Chat template text by id ([GemmaTemplate.ID]), or null to use the file's own. */
    private val templates: (String) -> String?,
    private val cpuThreads: Int = 4,
    /**
     * Gemma 4's built-in drafter (speculative decoding) is used on the GPU, where Google measured it faster on every
     * kind of task (up to ~2x). On CPUs its gain is mixed (and it was slower on a desktop CPU), so it stays off there.
     */
    private val speculativeOnCpu: Boolean = false,
    private val log: (String) -> Unit = {},
) : ModelRuntime {

    private var engine: Engine? = null
    private var engineKey: String? = null
    private var engineBackend = ""
    private var live: Live? = null
    @Volatile private var running: Conversation? = null
    @Volatile private var cancelled = false

    private class Live(val key: String, val conversation: Conversation, val consumed: List<String>)

    init {
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        runCatching { ExperimentalFlags.enableBenchmark = true }
    }

    override fun generate(req: LocalRequest, emit: (LocalEvent) -> Unit, onState: (String, String) -> Unit) {
        cancelled = false
        val file = File(req.modelPath)
        if (!file.isFile) return emit(LocalEvent.Failed("UNAVAILABLE", "The model file is missing; download it again in Settings › Solis › On this phone"))
        val last = req.messages.lastOrNull()
        if (last == null || last.role == "model") return emit(LocalEvent.Failed("BAD_REQUEST", "Nothing for the model to answer"))
        val need = LocalRequests.estimateTokens(req) + minOf(req.maxOutputTokens, 512)
        if (need > req.contextTokens) return emit(LocalEvent.Failed("CONTEXT_LENGTH", "About $need tokens won't fit the on-device model's ${req.contextTokens}-token memory"))

        val loadMs = try {
            ensureEngine(req, onState)
        } catch (e: Throwable) {
            return emit(LocalEvent.Failed("UNAVAILABLE", "Couldn't load the model: ${e.message ?: e.javaClass.simpleName}"))
        }
        if (cancelled) return emit(LocalEvent.Failed("CANCELLED", "Stopped"))

        // Continue the conversation in memory, or start one from the history.
        val key = req.prefixKey()
        val sigs = req.messages.map { it.signature() }
        val l = live
        val reuse = l != null && l.key == key && l.conversation.isAlive &&
            sigs.size == l.consumed.size + 1 && sigs.subList(0, l.consumed.size) == l.consumed
        val conv: Conversation
        val reused: Int
        try {
            if (reuse) {
                conv = l!!.conversation
                reused = runCatching { conv.getTokenCount() }.getOrDefault(0)
            } else {
                closeLive()
                // Never with JSON output: LiteRT-LM's constrained decoding breaks under the drafter (Gemma 4 E2B
                // answered {"1":, "2":}); tool calls and plain text are fine.
                val spec = req.speculative && !req.jsonMode && (engineBackend == "gpu" || speculativeOnCpu)
                // A model without a drafter refuses speculative decoding; it then runs without.
                conv = runCatching { engine!!.createConversation(conversationConfig(req, spec)) }
                    .getOrElse { if (spec) engine!!.createConversation(conversationConfig(req, false)) else throw it }
                reused = 0
            }
        } catch (e: Throwable) {
            closeLive()
            return emit(LocalEvent.Failed("UNAVAILABLE", "Couldn't start the conversation: ${e.message ?: e.javaClass.simpleName}"))
        }

        val text = StringBuilder()
        val calls = ArrayList<LocalToolCall>()
        var error: Throwable? = null
        val done = CountDownLatch(1)
        val thinking = if (req.thinkingBudget > 0) ThinkingConfig(true, req.thinkingBudget) else null
        val format = if (req.jsonMode) ResponseFormat.json(JSON_OBJECT) else null
        // LiteRT-LM's own time-to-first-token covers only a conversation's first turn, so measure every call here.
        val sentAt = System.currentTimeMillis()
        var firstAt = 0L
        running = conv
        try {
            conv.sendMessageAsync(toLiteRt(last), object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    message.channels[THOUGHT]?.takeIf { it.isNotEmpty() }?.let { emit(LocalEvent.Thought(it)) }
                    for (c in message.contents.contents) {
                        if (c is Content.Text && c.text.isNotEmpty()) { text.append(c.text); emit(LocalEvent.Text(c.text)) }
                    }
                    for (tc in message.toolCalls) {
                        val call = LocalToolCall(tc.name, argsJson(tc.arguments))
                        calls += call
                        emit(LocalEvent.Call(call))
                    }
                }

                override fun onDone() = done.countDown()

                override fun onError(throwable: Throwable) {
                    error = throwable
                    done.countDown()
                }
            }, emptyMap(), null, null, null, req.maxOutputTokens, thinking, format)
            done.await()
        } catch (e: Throwable) {
            error = e
        } finally {
            running = null
        }

        val err = error
        if (err != null || cancelled) {
            // A cancelled LiteRT-LM conversation refuses every later message, so it is never reused.
            if (reuse) live = null
            runCatching { conv.close() }
            return if (cancelled || err is CancellationException) emit(LocalEvent.Failed("CANCELLED", "Stopped"))
            else emit(LocalEvent.Failed(kindOf(err!!), err.message ?: err.javaClass.simpleName))
        }

        val bench = runCatching { conv.getBenchmarkInfo() }.getOrNull()
        val total = runCatching { conv.getTokenCount() }.getOrDefault(0)
        val decoded = bench?.lastDecodeTokenCount ?: (text.length / 4)
        live = Live(key, conv, sigs + LocalMessage("model", text.toString(), toolCalls = calls).signature())
        emit(LocalEvent.Done(LocalStats(
            prefillTokens = bench?.lastPrefillTokenCount ?: 0,
            decodeTokens = decoded,
            contextTokens = total,
            reusedTokens = reused,
            prefillTps = bench?.lastPrefillTokensPerSecond ?: 0.0,
            decodeTps = bench?.lastDecodeTokensPerSecond ?: 0.0,
            firstTokenMs = if (firstAt > 0) firstAt - sentAt else 0L,
            loadMs = loadMs,
            backend = engineBackend,
            finish = when {
                calls.isNotEmpty() -> "tool_calls"
                decoded >= req.maxOutputTokens -> "length"
                else -> "stop"
            },
        )))
    }

    override fun prepare(req: LocalRequest, onState: (String, String) -> Unit): Long = ensureEngine(req, onState)

    override fun cancel() {
        cancelled = true
        runCatching { running?.cancelProcess() }
    }

    override fun inspect(path: String): JSONObject = ModelInfo.from(path).use { info ->
        val m = info.inputModalities()
        JSONObject().put("type", info.modelType.name).put("vision", m.vision).put("audio", m.audio)
            .put("maxContext", info.maxContextTokens()).put("dynamicContext", info.isDynamicContext())
            .put("textBackends", JSONArray(info.supportedBackends(Modality.TEXT).map { it.name }))
            .put("speculative", (info as? LlmCapability)?.hasSpeculativeDecodingSupport() ?: false)
    }

    override fun unload() {
        closeLive()
        runCatching { engine?.close() }
        engine = null
        engineKey = null
        engineBackend = ""
    }

    // ------------------------------------------------------------------ internals

    /** Loads the engine when the model, backend or context changed. Returns the load time (0 when already loaded). */
    private fun ensureEngine(req: LocalRequest, onState: (String, String) -> Unit): Long {
        val key = listOf(req.modelPath, req.backend, req.contextTokens, req.vision).joinToString("|")
        if (engine != null && engineKey == key) return 0L
        unload()
        cacheDir.mkdirs()
        val started = System.currentTimeMillis()
        val order = when (req.backend) { "gpu" -> listOf("gpu"); "cpu" -> listOf("cpu"); else -> listOf("gpu", "cpu") }
        var lastError: Throwable? = null
        for (b in order) {
            onState("loading", b)
            val e = Engine(EngineConfig(
                modelPath = req.modelPath,
                backend = if (b == "gpu") Backend.GPU() else Backend.CPU(cpuThreads),
                visionBackend = if (req.vision) Backend.CPU(cpuThreads) else null,
                audioBackend = null,
                maxNumTokens = req.contextTokens,
                cacheDir = cacheDir.absolutePath,
            ))
            try {
                e.initialize()
                engine = e
                engineKey = key
                engineBackend = b
                onState("ready", b)
                log("engine ready on $b in ${System.currentTimeMillis() - started} ms")
                return System.currentTimeMillis() - started
            } catch (t: Throwable) {
                lastError = t
                runCatching { e.close() }
                log("engine failed on $b: ${t.message}")
                if (b == "gpu" && order.size > 1) onState("gpu_failed", t.message ?: t.javaClass.simpleName)
            }
        }
        throw lastError ?: IllegalStateException("no backend")
    }

    private fun conversationConfig(req: LocalRequest, speculative: Boolean) = ConversationConfig(
        systemInstruction = req.system.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
        initialMessages = req.messages.dropLast(1).map { toLiteRt(it) },
        tools = req.tools.map { tool(DeclaredTool(it.declaration())) },
        automaticToolCalling = false,
        thinkingConfig = if (req.thinkingBudget > 0) ThinkingConfig(true, req.thinkingBudget) else null,
        enableResponseFormat = req.jsonMode,
        enableSpeculativeDecoding = speculative,
        chatTemplate = req.template?.let(templates),
    )

    private fun closeLive() {
        live?.let { runCatching { it.conversation.close() } }
        live = null
    }

    /** Solis runs the tools itself (permissions, confirmations), so LiteRT-LM only sees their declarations. */
    private class DeclaredTool(private val json: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = json
        override fun execute(paramsJsonString: String): String = throw UnsupportedOperationException("Solis runs tools itself")
    }

    companion object {
        const val THOUGHT = "thought"
        private const val JSON_OBJECT = """{"type":"object"}"""

        fun toLiteRt(m: LocalMessage): Message = when (m.role) {
            "user" -> Message.user(Contents.of(m.images.map { Content.ImageFile(it) } + Content.Text(m.text.ifBlank { " " })))
            "tool" -> Message.tool(Contents.of(m.toolResults.map { Content.ToolResponse(it.name, it.content) }))
            else -> Message.model(
                Contents.of(if (m.text.isBlank()) emptyList() else listOf(Content.Text(m.text))),
                m.toolCalls.map { ToolCall(it.name, argsMap(it.argsJson)) },
                emptyMap(),
            )
        }

        fun argsJson(args: Map<String, Any?>): String = JSONObject(args.mapValues { (_, v) -> toJsonValue(v) }).toString()

        /** LiteRT-LM hands arguments over as Gson values (numbers are Gson's own Number type), so normalize them. */
        private fun toJsonValue(v: Any?): Any = when (v) {
            null, is com.google.gson.JsonNull -> JSONObject.NULL
            is Map<*, *> -> JSONObject(v.entries.associate { (k, x) -> k.toString() to toJsonValue(x) })
            is Iterable<*> -> JSONArray(v.map { toJsonValue(it) })
            is Array<*> -> JSONArray(v.map { toJsonValue(it) })
            is Boolean, is String -> v
            is Number -> v.toString().toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong() else d } ?: v.toString()
            is com.google.gson.JsonElement -> runCatching { org.json.JSONTokener(v.toString()).nextValue() }.getOrDefault(v.toString())
            else -> v.toString()
        }

        fun argsMap(json: String): Map<String, Any> = runCatching { fromJson(JSONObject(json)) }.getOrDefault(emptyMap())

        @Suppress("UNCHECKED_CAST")
        private fun fromJson(o: JSONObject): Map<String, Any> = o.keys().asSequence().associateWith { k -> fromJsonValue(o.get(k)) } as Map<String, Any>

        private fun fromJsonValue(v: Any?): Any? = when (v) {
            is JSONObject -> fromJson(v)
            is JSONArray -> (0 until v.length()).map { fromJsonValue(v.get(it)) }
            JSONObject.NULL -> null
            else -> v
        }

        fun kindOf(e: Throwable): String {
            val msg = (e.message ?: "").lowercase()
            return when {
                "token" in msg && ("exceed" in msg || "max" in msg || "context" in msg || "limit" in msg) -> "CONTEXT_LENGTH"
                "memory" in msg || "alloc" in msg -> "UNAVAILABLE"
                else -> "UNKNOWN"
            }
        }
    }
}
