package com.axiom.launcher.solis.ondevice

import com.axiom.launcher.solis.catalog.ModelInfo
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.ContentPart
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.ErrorKind
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.LlmProvider
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.llm.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicLong

/** The model process as the launcher sees it: the binder client in the app, a scripted stand-in in tests. */
fun interface OnDeviceRuntime {
    /** Cold and cancellable: cancelling the collector stops the model. Ends with [LocalEvent.Done] or [LocalEvent.Failed]. */
    fun generate(req: LocalRequest): Flow<LocalEvent>
}

/** The installed model the user picked, with their settings; null when nothing is ready to run. */
data class OnDeviceConfig(
    val modelId: String,
    val name: String,
    val path: String,
    /** "auto", "gpu" or "cpu" (auto drops to the CPU on phones where the GPU path fails). */
    val backend: String,
    val contextTokens: Int,
    val template: String?,
    val vision: Boolean,
    val tools: Boolean,
    /** The model can think and the user allows it on hard questions. */
    val thinking: Boolean,
    val speculative: Boolean,
    val quality: Double,
) {
    fun modelInfo(): ModelInfo = ModelInfo(
        provider = OnDeviceProvider.ID,
        id = modelId,
        name = "$name (on this phone)",
        contextLength = contextTokens,
        maxOutputTokens = minOf(4096, contextTokens / 2),
        inputPrice = 0.0,
        outputPrice = 0.0,
        supportsTools = tools,
        supportsJson = true,
        inputModalities = if (vision) setOf("text", "image") else setOf("text"),
        // No LOW level on purpose: quick (T1) work maps to no thinking, which keeps short answers fast on a phone.
        efforts = if (thinking) listOf(Effort.NONE, Effort.MEDIUM, Effort.HIGH) else emptyList(),
        defaultEffort = if (thinking) Effort.NONE else null,
        isLocal = true,
        priorQuality = quality,
        compactTools = true,
        onDevice = true,
    )
}

/**
 * Solis provider for a model running on the phone itself (Gemma 4 via LiteRT-LM). Nothing leaves the device,
 * it works offline and it costs nothing, so it is always allowed by local-only privacy and offline mode.
 */
class OnDeviceProvider(
    private val runtime: OnDeviceRuntime,
    private val config: () -> OnDeviceConfig?,
    /** Writes an image part where the model process can read it; returns the path. */
    private val saveImage: (ContentPart.Image) -> String,
    /** Measurements of each finished call (speed, backend), for settings and diagnostics. */
    private val onStats: (String, LocalStats) -> Unit = { _, _ -> },
) : LlmProvider {
    override val id = ID
    override val displayName = "On this phone"
    override val isLocal = true

    private val seq = AtomicLong()

    override fun isConfigured(): Boolean = config() != null

    override suspend fun listModels(): List<ModelInfo> = listOfNotNull(config()?.modelInfo())

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val c = config()?.takeIf { it.modelId == request.model }
            ?: throw ProviderException(ErrorKind.UNAVAILABLE, "${request.model} isn't ready on this phone")
        val req = LocalRequests.from(
            request, id = "c${System.currentTimeMillis()}-${seq.incrementAndGet()}", modelPath = c.path, backend = c.backend,
            contextTokens = c.contextTokens, template = c.template, vision = c.vision, speculative = c.speculative,
            thinking = c.thinking, extraSystem = if (c.template == GemmaTemplate.ID) GemmaTemplate.TOOL_REPLY_RULE else null,
            saveImage = saveImage,
        )
        var calls = 0
        var ended = false
        runtime.generate(req).collect { ev ->
            when (ev) {
                is LocalEvent.Text -> emit(LlmEvent.TextDelta(ev.text))
                is LocalEvent.Thought -> emit(LlmEvent.ReasoningDelta(ev.text))
                is LocalEvent.Call -> emit(LlmEvent.ToolCallReady(ToolCall("${req.id}-${++calls}", ev.call.name, ev.call.argsJson)))
                is LocalEvent.Done -> {
                    ended = true
                    val s = ev.stats
                    onStats(c.modelId, s)
                    emit(LlmEvent.Usage(
                        inputTokens = (s.contextTokens - s.decodeTokens).coerceAtLeast(s.prefillTokens),
                        outputTokens = s.decodeTokens,
                        cachedInputTokens = s.reusedTokens,
                        costUsd = 0.0,
                    ))
                    emit(LlmEvent.Finish(
                        reason = when (s.finish) { "tool_calls" -> FinishReason.TOOL_CALLS; "length" -> FinishReason.LENGTH; else -> FinishReason.STOP },
                        nativeReason = s.finish,
                        servedModel = c.modelId,
                    ))
                }
                is LocalEvent.Failed -> { ended = true; throw failure(ev) }
            }
        }
        if (!ended) throw ProviderException(ErrorKind.UNAVAILABLE, "The on-device model stopped without an answer")
    }

    companion object {
        const val ID = "device"

        fun failure(f: LocalEvent.Failed): ProviderException = when (f.kind) {
            "CONTEXT_LENGTH" -> ProviderException(ErrorKind.CONTEXT_LENGTH, f.message)
            "BAD_REQUEST" -> ProviderException(ErrorKind.BAD_REQUEST, f.message)
            "CRASHED" -> ProviderException(ErrorKind.UNAVAILABLE, "The on-device model stopped unexpectedly")
            // Stopped from the model's side (memory pressure, the model was unloaded): another model can take over.
            "CANCELLED" -> ProviderException(ErrorKind.UNAVAILABLE, "The on-device model was stopped")
            "UNAVAILABLE" -> ProviderException(ErrorKind.UNAVAILABLE, f.message)
            else -> ProviderException(ErrorKind.UNKNOWN, f.message)
        }
    }
}
