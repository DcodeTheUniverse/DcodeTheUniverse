package com.axiom.launcher.solis.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaBase64PdfSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.axiom.launcher.solis.catalog.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct Anthropic API adapter built on the official Java SDK (beta Messages namespace, so server-side
 * refusal fallbacks and their `fallback` content blocks are understood). Provider-specific details —
 * thinking blocks, fallbacks, effort — stay inside this class.
 */
class AnthropicProvider(
    private val apiKey: () -> String?,
    private val baseUrl: String? = null,
) : LlmProvider {
    override val id = "anthropic"
    override val displayName = "Anthropic"
    override val isLocal = false

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    override fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    @Synchronized
    private fun client(): AnthropicClient {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw ProviderException(ErrorKind.AUTH, "No Anthropic API key")
        if (client == null || clientKey != key) {
            client?.close()
            client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofMinutes(5))
                .maxRetries(1)
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()
            clientKey = key
        }
        return client!!
    }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            client().models().list().autoPager().mapNotNull { m ->
                if (m.lifecycle().toString().equals("retired", true)) return@mapNotNull null
                val caps = m.capabilities().orElse(null)
                val efforts = buildList {
                    caps?.effort()?.let { e ->
                        if (e.supported()) {
                            if (e.low().supported()) add(Effort.LOW)
                            if (e.medium().supported()) add(Effort.MEDIUM)
                            if (e.high().supported()) add(Effort.HIGH)
                            if (e.xhigh().map { it.supported() }.orElse(false)) add(Effort.XHIGH)
                            if (e.max().supported()) add(Effort.MAX)
                        }
                    }
                }
                ModelInfo(
                    provider = id,
                    id = m.id(),
                    name = m.displayName(),
                    contextLength = m.maxInputTokens().orElse(200_000L).toInt(),
                    maxOutputTokens = m.maxTokens().orElse(0L).toInt().takeIf { it > 0 },
                    // The Models API does not publish prices; cost stays unknown unless the user allows it.
                    inputPrice = null,
                    outputPrice = null,
                    supportsTools = true,
                    supportsJson = false,
                    inputModalities = buildSet {
                        add("text")
                        if (caps?.imageInput()?.supported() == true) add("image")
                        if (caps?.pdfInput()?.supported() == true) add("file")
                    },
                    efforts = efforts,
                    expiresAt = m.retiresAt().map { it.toInstant().toEpochMilli() }.orElse(null),
                    createdAt = m.createdAt().toInstant().toEpochMilli(),
                )
            }.toList()
        } catch (e: Throwable) {
            throw mapError(e)
        }
    }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val open = AtomicReference<StreamResponse<BetaRawMessageStreamEvent>?>()
        val worker = launch(Dispatchers.IO) {
            try {
                val acc = BetaMessageAccumulator.create()
                val s = client().beta().messages().createStreaming(params(request))
                open.set(s)
                s.use { stream ->
                    val it = stream.stream().iterator()
                    while (it.hasNext()) {
                        val ev = it.next()
                        acc.accumulate(ev)
                        val text = ev.contentBlockDelta().flatMap { d -> d.delta().text() }.map { t -> t.text() }.orElse(null)
                        if (text != null) send(LlmEvent.TextDelta(text))
                        val thinking = ev.contentBlockDelta().flatMap { d -> d.delta().thinking() }.map { t -> t.thinking() }.orElse(null)
                        if (!thinking.isNullOrEmpty()) send(LlmEvent.ReasoningDelta(thinking))
                    }
                }
                finish(acc.message(), request) { send(it) }
                close()
            } catch (e: Throwable) {
                close(if (e is kotlinx.coroutines.CancellationException) null else mapError(e))
            }
        }
        awaitClose {
            runCatching { open.get()?.close() }
            worker.cancel()
        }
    }

    private suspend fun finish(msg: BetaMessage, request: ChatRequest, send: suspend (LlmEvent) -> Unit) {
        val stop = msg.stopReason().orElse(null)
        val usage = msg.usage()
        send(
            LlmEvent.Usage(
                inputTokens = (usage.inputTokens() + usage.cacheReadInputTokens().orElse(0L) + usage.cacheCreationInputTokens().orElse(0L)).toInt(),
                outputTokens = usage.outputTokens().toInt(),
                cachedInputTokens = usage.cacheReadInputTokens().orElse(0L).toInt(),
                cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L).toInt(),
            )
        )
        if (stop == BetaStopReason.REFUSAL) {
            send(LlmEvent.Finish(FinishReason.REFUSAL, "refusal", msg.model().asString()))
            return
        }
        val content = echoable(msg, stop)
        val calls = content.mapNotNull { it.toolUse().orElse(null) }.map { use ->
            ToolCall(use.id(), use.name(), runCatching { jsonText(use._input()) }.getOrDefault("{}"))
        }
        calls.forEach { send(LlmEvent.ToolCallReady(it)) }
        val reason = when (stop) {
            BetaStopReason.TOOL_USE -> FinishReason.TOOL_CALLS
            BetaStopReason.MAX_TOKENS -> FinishReason.LENGTH
            else -> FinishReason.STOP
        }
        // The exact assistant turn (thinking blocks included) for replay within this conversation only.
        val native = NativeState(id, request.model, msg.toBuilder().content(content).build().toParam())
        send(LlmEvent.Finish(reason, stop?.toString(), msg.model().asString(), native))
    }

    /**
     * After a mid-output server-side fallback, blocks before the last `fallback` marker other than text are
     * dropped (and never executed). On max_tokens, a possibly truncated tool call is dropped.
     */
    private fun echoable(msg: BetaMessage, stop: BetaStopReason?): List<BetaContentBlock> {
        val content = msg.content()
        val boundary = content.indexOfLast { it.isFallback() }
        var out = if (boundary < 0) content else content.filterIndexed { i, b -> i > boundary || (i < boundary && b.text().isPresent) }
        if (stop == BetaStopReason.MAX_TOKENS) out = out.filter { !it.toolUse().isPresent }
        return out
    }

    internal fun params(r: ChatRequest): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(r.model)
            .maxTokens(r.maxOutputTokens.toLong())
            .messages(toParams(r.messages, r.model))
        if (r.cachePrefix && r.system.isNotBlank()) {
            // An explicit breakpoint on the frozen system prompt caches tools + system for every task that shares
            // them; top-level automatic caching then moves a second breakpoint along the growing conversation.
            b.systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(r.system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
            b.cacheControl(BetaCacheControlEphemeral.builder().build())
        } else if (r.system.isNotBlank()) b.system(r.system)
        r.effort?.let { b.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(it.wire)).build()) }
        r.tools.forEach { t ->
            val schema = JSONObject(t.parametersSchema)
            val props = BetaTool.InputSchema.Properties.builder()
            schema.optJSONObject("properties")?.let { p -> p.keys().forEach { k -> props.putAdditionalProperty(k, JsonValue.from(toJava(p.get(k)))) } }
            val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            b.addTool(
                BetaTool.builder().name(t.name).description(t.description)
                    .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required(required).build())
                    .eagerInputStreaming(true)
                    .build()
            )
        }
        if (r.webSearch) b.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        // Server-side refusal fallback for the Claude 5 Opus/Sonnet/Fable lines (not offered for Haiku).
        if (FALLBACK_MODELS.containsMatchIn(r.model)) b.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        return b.build()
    }

    /** Normalized history -> Anthropic messages. Tool results for one turn are grouped into one user message. */
    internal fun toParams(messages: List<ChatMessage>, model: String): List<BetaMessageParam> {
        val out = ArrayList<BetaMessageParam>()
        val user = ArrayList<BetaContentBlockParam>()
        fun flush() {
            if (user.isNotEmpty()) { out += BetaMessageParam.ofUserOfContentBlockParams(user.toList()); user.clear() }
        }
        for (m in messages) when (m.role) {
            ChatRole.USER -> user += m.parts.mapNotNull { block(it) }
            ChatRole.TOOL -> user += BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam.builder().toolUseId(m.toolCallId ?: "").content(m.text).isError(m.isError).build()
            )
            ChatRole.ASSISTANT -> {
                flush()
                val n = m.native
                if (n != null && n.providerId == id && n.modelId == model && n.payload is BetaMessageParam) {
                    out += n.payload
                } else {
                    val blocks = ArrayList<BetaContentBlockParam>()
                    if (m.text.isNotBlank()) blocks += BetaContentBlockParam.ofText(m.text)
                    m.toolCalls.forEach { c ->
                        val input = BetaToolUseBlockParam.Input.builder()
                        runCatching { JSONObject(c.argumentsJson) }.getOrNull()?.let { o ->
                            o.keys().forEach { k -> input.putAdditionalProperty(k, JsonValue.from(toJava(o.get(k)))) }
                        }
                        blocks += BetaContentBlockParam.ofToolUse(BetaToolUseBlockParam.builder().id(c.id).name(c.name).input(input.build()).build())
                    }
                    if (blocks.isEmpty()) blocks += BetaContentBlockParam.ofText("(no reply)")
                    out += BetaMessageParam.ofAssistantOfContentBlockParams(blocks)
                }
            }
        }
        flush()
        return out
    }

    private fun block(p: ContentPart): BetaContentBlockParam? = when (p) {
        is ContentPart.Text -> if (p.text.isEmpty()) null else BetaContentBlockParam.ofText(p.text)
        is ContentPart.Image -> BetaContentBlockParam.ofImage(
            BetaImageBlockParam.builder().source(
                BetaBase64ImageSource.builder().mediaType(BetaBase64ImageSource.MediaType.of(p.mediaType)).data(p.base64).build()
            ).build()
        )
        is ContentPart.Document -> BetaContentBlockParam.ofDocument(
            BetaRequestDocumentBlock.builder().source(BetaBase64PdfSource.builder().data(p.base64).build()).title(p.name).build()
        )
    }

    private fun jsonText(v: JsonValue): String {
        @Suppress("UNCHECKED_CAST")
        val map = v.convert(Map::class.java) as Map<String, Any?>?
        return JSONObject(map ?: emptyMap<String, Any?>()).toString()
    }

    private fun toJava(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { toJava(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { toJava(v.get(it)) }
        else -> v
    }

    private fun mapError(e: Throwable): ProviderException = when (e) {
        is ProviderException -> e
        is UnauthorizedException -> ProviderException(ErrorKind.AUTH, "Anthropic rejected the API key", 401, cause = e)
        is PermissionDeniedException -> ProviderException(ErrorKind.AUTH, "Anthropic: permission denied", 403, cause = e)
        is RateLimitException -> ProviderException(ErrorKind.RATE_LIMIT, "Anthropic: rate limited", 429,
            e.headers().values("retry-after").firstOrNull()?.toDoubleOrNull()?.let { (it * 1000).toLong() }, e)
        is NotFoundException -> ProviderException(ErrorKind.UNAVAILABLE, "Anthropic: model not found", 404, cause = e)
        is BadRequestException -> {
            val m = e.message.orEmpty()
            ProviderException(if (m.contains("too long", true) || m.contains("context", true)) ErrorKind.CONTEXT_LENGTH else ErrorKind.BAD_REQUEST,
                "Anthropic: ${m.take(300)}", 400, cause = e)
        }
        is AnthropicServiceException -> ProviderException(
            if (e.statusCode() >= 500) ErrorKind.UNAVAILABLE else ErrorKind.UNKNOWN, "Anthropic error ${e.statusCode()}", e.statusCode(), cause = e)
        is AnthropicIoException -> ProviderException(ErrorKind.NETWORK, "Couldn't reach Anthropic: ${e.message}", cause = e)
        else -> ProviderException(ErrorKind.UNKNOWN, "${e.javaClass.simpleName}: ${e.message}", cause = e)
    }

    companion object {
        private val FALLBACK_MODELS = Regex("claude-(opus-5|sonnet-5-5|fable-5)")
    }
}
