package com.axiom.launcher.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONObject
import java.time.Duration

/**
 * The in-launcher agent: a manual tool-use loop over the Messages API (beta namespace, for
 * server-side refusal fallbacks), streaming text back to the UI as it arrives.
 *
 * History is append-only within a session so thinking blocks stay valid.
 */
class ClaudeAgent(private val tools: AgentTools, private val baseUrl: String? = null) {

    interface Listener {
        fun onText(delta: String)
        fun onTool(summary: String, isError: Boolean)
        fun onNewTurn()
    }

    class AgentError(message: String) : Exception(message)

    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val history = mutableListOf<BetaMessageParam>()

    @Volatile var cancelled = false

    fun reset() = synchronized(history) { history.clear() }

    fun hasHistory() = history.isNotEmpty()

    private fun clientFor(key: String): AnthropicClient {
        if (client == null || clientKey != key) {
            client?.close()
            client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofMinutes(5))
                .maxRetries(2)
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()
            clientKey = key
        }
        return client!!
    }

    /** Runs one user turn to completion (including tool round trips). Blocking; call off the main thread. */
    fun send(
        apiKey: String,
        model: String,
        effort: String,
        webSearch: Boolean,
        system: String,
        userBlocks: List<BetaContentBlockParam>,
        listener: Listener,
    ) {
        cancelled = false
        val c = clientFor(apiKey)
        val startSize = history.size
        history += BetaMessageParam.ofUserOfContentBlockParams(userBlocks)

        try {
            var rounds = 0
            while (rounds++ < 16 && !cancelled) {
                val msg = streamOnce(c, model, effort, webSearch, system, listener)
                val stop = msg.stopReason().orElse(null)

                if (stop == BetaStopReason.REFUSAL) {
                    // Roll back this turn so the conversation stays usable.
                    while (history.size > startSize) history.removeAt(history.size - 1)
                    throw AgentError("Claude declined this request. Try rephrasing it.")
                }

                val content = echoableContent(msg, stop)
                history += msg.toBuilder().content(content).build().toParam()

                if (stop == BetaStopReason.PAUSE_TURN) { listener.onNewTurn(); continue }
                if (stop != BetaStopReason.TOOL_USE) break

                val results = content.mapNotNull { it.toolUse().orElse(null) }.map { use ->
                    val input = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        AgentTools.jsonOf(use._input().convert(Map::class.java) as Map<*, *>?)
                    }.getOrNull()
                    val outcome = if (input == null) ToolOutcome("INVALID_JSON: tool input could not be parsed; re-issue the call.", true, "${use.name()}: bad input")
                    else tools.run(use.name(), input)
                    listener.onTool(outcome.summary, outcome.isError)
                    BetaContentBlockParam.ofToolResult(
                        BetaToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(outcome.text)
                            .isError(outcome.isError)
                            .build()
                    )
                }
                if (results.isEmpty()) break
                history += BetaMessageParam.ofUserOfContentBlockParams(results)
                listener.onNewTurn()
            }
        } catch (e: AgentError) {
            throw e
        } catch (e: UnauthorizedException) {
            rollback(startSize)
            throw AgentError("Your API key was rejected. Check it in Settings › Claude.")
        } catch (e: RateLimitException) {
            rollback(startSize)
            throw AgentError("Rate limited by the API. Give it a moment and try again.")
        } catch (e: AnthropicServiceException) {
            rollback(startSize)
            throw AgentError("API error ${e.statusCode()}: ${e.message?.take(300)}")
        } catch (e: AnthropicIoException) {
            rollback(startSize)
            throw AgentError("Network problem: ${e.message ?: "couldn't reach Claude"}")
        } catch (e: Exception) {
            rollback(startSize)
            throw AgentError("${e.javaClass.simpleName}: ${e.message?.take(300)}")
        }
    }

    private fun rollback(size: Int) {
        // Keep history consistent (every tool_use must be followed by its tool_result).
        while (history.size > size) history.removeAt(history.size - 1)
    }

    private fun streamOnce(
        c: AnthropicClient, model: String, effort: String, webSearch: Boolean, system: String, listener: Listener,
    ): BetaMessage {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .messages(history.toList())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
        tools.definitions.forEach { b.addTool(it) }
        if (webSearch) b.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        // Server-side refusal fallback; not offered for Haiku.
        if (!model.contains("haiku")) {
            b.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        }

        val acc = BetaMessageAccumulator.create()
        c.beta().messages().createStreaming(b.build()).use { stream ->
            val it = stream.stream().iterator()
            while (it.hasNext()) {
                if (cancelled) break
                val ev = it.next()
                acc.accumulate(ev)
                ev.contentBlockDelta().flatMap { d -> d.delta().text() }.ifPresent { t -> listener.onText(t.text()) }
                ev.contentBlockStart().ifPresent { s ->
                    if (s.contentBlock().isFallback()) listener.onText("\n")
                }
            }
        }
        if (cancelled) throw AgentError("Stopped.")
        return acc.message()
    }

    /**
     * Content to echo back. After a mid-output fallback, blocks before the last `fallback` marker other
     * than text are dropped (and never executed). On max_tokens, a possibly truncated tool call is dropped.
     */
    private fun echoableContent(msg: BetaMessage, stop: BetaStopReason?): List<BetaContentBlock> {
        val content = msg.content()
        val boundary = content.indexOfLast { it.isFallback() }
        var out = if (boundary < 0) content else
            content.filterIndexed { i, b -> i > boundary || (i < boundary && b.text().isPresent) }
        if (stop == BetaStopReason.MAX_TOKENS) out = out.filter { !it.toolUse().isPresent }
        return out
    }

    companion object {
        val MODELS = listOf(
            "claude-opus-5-5" to "Opus 5.5 · smartest",
            "claude-sonnet-5-5" to "Sonnet 5.5 · fast & capable",
            "claude-haiku-5-5" to "Haiku 5.5 · fastest, cheapest",
        )
        val EFFORTS = listOf("low", "medium", "high")

        /** "claude-opus-5-5" -> "Opus 5.5" */
        fun prettyModel(id: String): String {
            val parts = id.removePrefix("claude-").split('-')
            val name = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: id
            return (listOf(name) + parts.drop(1).joinToString(".").let { if (it.isEmpty()) emptyList() else listOf(it) }).joinToString(" ")
        }

        fun systemPrompt(userName: String, memories: List<String>, netrunner: Boolean): String = buildString {
            append(
                """
                You are Axiom, the AI layer built into the user's Android home-screen launcher. You live on their phone and can act on it through tools: open apps, find and read files, set timers and alarms, draft messages, start navigation, play music, change settings screens, keep notes, and more.

                How to behave:
                - Do, don't describe. When the user asks for something a tool can do, call the tool right away, then confirm in one short line.
                - Messages, emails, calls and calendar events open pre-filled screens; the user confirms them. Say so briefly when relevant.
                - Replies are read on a phone screen: default to 1–4 sentences or a tight bullet list. Go longer only when asked.
                - Use light markdown only (bold, bullets, `code`). No tables, no headings for short answers.
                - Never invent file contents, contacts, or app names. Look them up with tools.
                - Use the remember tool when the user shares a lasting preference or fact worth keeping.
                - Each user message starts with a [device] line with the current time, battery and so on. Use it; don't repeat it back.
                """.trimIndent()
            )
            if (userName.isNotBlank()) append("\n\nThe user's name is ${userName.trim()}.")
            if (memories.isNotEmpty()) {
                append("\n\nThings you remembered about the user (index: fact):\n")
                memories.forEachIndexed { i, m -> append("$i: $m\n") }
            }
            if (netrunner) {
                append(
                    "\n\nThe launcher is in Netrunner (cyberpunk terminal) mode. Answer in a terse, playful hacker-terminal voice " +
                        "with light cyberpunk flavor (\"jacking in\", \"ICE\", \"netrunner\"), but stay genuinely helpful and accurate."
                )
            }
        }

        fun userText(contextLine: String, text: String) = "[device] $contextLine\n\n$text"

        @Suppress("unused")
        fun parse(json: String) = JSONObject(json)
    }
}
