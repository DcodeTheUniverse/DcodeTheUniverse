package com.axiom.launcher.solis.llm

import kotlinx.coroutines.flow.Flow

/*
 * Provider-neutral vocabulary for talking to language models. The brain and task engine only ever see
 * these types; each provider adapter translates them to and from its own wire format.
 */

enum class ChatRole { USER, ASSISTANT, TOOL }

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Image(val mediaType: String, val base64: String) : ContentPart
    data class Document(val name: String, val mediaType: String, val base64: String) : ContentPart
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * Provider-native state attached to an assistant message by the adapter that produced it (for example
 * reasoning blocks that must be echoed verbatim inside a tool loop). Only that provider and model may
 * read it; it lives in memory only and is never persisted.
 */
class NativeState(val providerId: String, val modelId: String, val payload: Any)

data class ChatMessage(
    val role: ChatRole,
    val parts: List<ContentPart> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    /** For [ChatRole.TOOL]: the call this message answers. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val native: NativeState? = null,
) {
    val text: String get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        fun user(text: String) = ChatMessage(ChatRole.USER, listOf(ContentPart.Text(text)))
        fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(text)))
        fun toolResult(call: ToolCall, content: String, isError: Boolean) =
            ChatMessage(ChatRole.TOOL, listOf(ContentPart.Text(content)), toolCallId = call.id, toolName = call.name, isError = isError)
    }
}

/** A function the model may call. [parametersSchema] is a JSON Schema object serialized as a string. */
data class ToolSpec(val name: String, val description: String, val parametersSchema: String)

/** Reasoning effort levels, ordered from least to most. Only sent when the model advertises support. */
enum class Effort(val wire: String) {
    NONE("none"), MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), MAX("max");

    companion object {
        fun parse(s: String?): Effort? = entries.firstOrNull { it.wire.equals(s?.trim(), ignoreCase = true) }
    }
}

/** Inference-layer privacy controls (for OpenRouter these become `provider` routing preferences). */
data class PrivacyPrefs(
    val denyDataCollection: Boolean = true,
    val zeroDataRetention: Boolean = false,
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxOutputTokens: Int = 4096,
    /** Desired reasoning effort, already mapped to a level the model supports; null = provider default. */
    val effort: Effort? = null,
    /** Ask for a single JSON object as the reply (structured outputs where supported). */
    val jsonMode: Boolean = false,
    val privacy: PrivacyPrefs = PrivacyPrefs(),
    /** Same-provider inference fallbacks (OpenRouter `models`). Separate from Solis's own routing. */
    val inferenceFallbacks: List<String> = emptyList(),
    /** Let the provider run its own web search, if it offers one. */
    val webSearch: Boolean = false,
)

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, REFUSAL, ERROR }

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ReasoningDelta(val text: String) : LlmEvent
    data class ToolCallReady(val call: ToolCall) : LlmEvent

    /** Token usage for the call. [costUsd] is the provider-reported cost when available, else null. */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val costUsd: Double? = null,
    ) : LlmEvent

    data class Finish(
        val reason: FinishReason,
        val nativeReason: String? = null,
        /** The model that actually served the request (may differ after an inference-layer fallback). */
        val servedModel: String? = null,
        val native: NativeState? = null,
    ) : LlmEvent
}

enum class ErrorKind { AUTH, BUDGET, RATE_LIMIT, UNAVAILABLE, BAD_REQUEST, CONTEXT_LENGTH, MODERATION, NETWORK, TIMEOUT, UNKNOWN }

class ProviderException(
    val kind: ErrorKind,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Worth trying the same model again after a pause. */
    val transient: Boolean get() = kind == ErrorKind.RATE_LIMIT || kind == ErrorKind.NETWORK || kind == ErrorKind.TIMEOUT || kind == ErrorKind.UNAVAILABLE

    /** Worth moving to a different model. Credentials and spending problems are never "fixed" by switching. */
    val modelFallbackHelps: Boolean get() = kind != ErrorKind.AUTH && kind != ErrorKind.BUDGET
}

/** Identifies a model across providers. */
data class ModelRef(val provider: String, val model: String) {
    val key: String get() = "$provider|$model"
    override fun toString() = key

    companion object {
        fun parse(key: String?): ModelRef? {
            if (key.isNullOrBlank()) return null
            val i = key.indexOf('|')
            return if (i <= 0) null else ModelRef(key.substring(0, i), key.substring(i + 1))
        }
    }
}

/**
 * One inference backend (OpenRouter, a direct vendor API, or an OpenAI-compatible local runtime).
 * Implementations must make [stream] cold and cancellable: cancelling the collector aborts the request.
 */
interface LlmProvider {
    val id: String
    val displayName: String
    /** True when inference stays on the device or local network. */
    val isLocal: Boolean
    fun isConfigured(): Boolean
    suspend fun listModels(): List<com.axiom.launcher.solis.catalog.ModelInfo>
    fun stream(request: ChatRequest): Flow<LlmEvent>
}
