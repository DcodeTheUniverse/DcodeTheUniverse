package com.axiom.launcher.solis.engine

import com.axiom.launcher.solis.cortex.NarratorPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class EventKind { STARTED, ASSESSED, STEP_STARTED, STEP_DONE, TOOL_DONE, WAITING, BLOCKED, FAILED, COMPLETED, PAUSED, RESUMED, CANCELLED }

data class ThreadEvent(val threadId: String, val kind: EventKind, val detail: String, val ts: Long)

/**
 * Background narration with an inexpensive general model. It is strictly read-only: it receives task
 * events, coalesces them, and may publish a one-line summary. It cannot start, change or resume work,
 * and it never runs on a timer — only after meaningful events, debounced, rate-limited and capped.
 * Most of the time the engine's deterministic status text is enough and no model is called at all.
 */
class Narrator(
    private val scope: CoroutineScope,
    private val policy: () -> NarratorPolicy,
    /** False when offline, battery/thermal constrained, or no cheap model is eligible. */
    private val canUseModel: () -> Boolean,
    /** Runs one narrator-role model call (routing + budget handled by the engine). */
    private val summarize: suspend (threadId: String, prompt: String) -> String?,
    /** Writes the narration line; the only effect the narrator has. */
    private val publish: (threadId: String, text: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class PerThread {
        val pending = ArrayDeque<ThreadEvent>()
        var job: Job? = null
        var lastCallAt = 0L
        var calls = 0
        var turnKey = ""
    }

    private val state = HashMap<String, PerThread>()
    @Volatile var modelCalls = 0; private set

    /** [snapshot] provides the latest thread view (title, steps, result) at flush time. */
    fun onEvent(e: ThreadEvent, snapshot: () -> AgentThread?) {
        val p = policy()
        if (!p.enabled) return
        val st = synchronized(state) { state.getOrPut(e.threadId) { PerThread() } }
        synchronized(st) {
            val t = snapshot()
            val key = "${t?.turn}"
            if (st.turnKey != key) { st.turnKey = key; st.calls = 0 }
            st.pending.addLast(e)
            while (st.pending.size > 20) st.pending.removeFirst()
            if (e.kind in TRIGGERS) {
                st.job?.cancel()
                st.job = scope.launch {
                    delay(p.coalesceMs)
                    flush(e.threadId, st, snapshot)
                }
            }
        }
    }

    private suspend fun flush(threadId: String, st: PerThread, snapshot: () -> AgentThread?) {
        val p = policy()
        val events: List<ThreadEvent>
        synchronized(st) {
            events = st.pending.toList()
            st.pending.clear()
        }
        val t = snapshot() ?: return
        val completed = events.any { it.kind == EventKind.COMPLETED }
        val steps = events.count { it.kind == EventKind.STEP_DONE }
        val worthIt = (completed && (t.stepsTotal >= p.minStepsForModel || (t.lastResult?.length ?: 0) > 700)) ||
            (!completed && steps >= 3)
        if (!worthIt || !canUseModel()) return
        val now = clock()
        synchronized(st) {
            if (st.calls >= p.maxCallsPerThread || now - st.lastCallAt < p.minIntervalMs) return
            st.calls++
            st.lastCallAt = now
        }
        val prompt = buildString {
            append("Write ONE short status line (at most 18 words) for a phone task card. Plain text, no quotes.\n")
            append("Task: ").append(t.title.take(200)).append('\n')
            append("Progress: ${t.stepsDone}/${t.stepsTotal} steps, status ${t.status.label}.\n")
            append("Recent events:\n")
            events.takeLast(8).forEach { append("- ${it.kind.name.lowercase()}: ${it.detail.take(160)}\n") }
            if (completed) t.lastResult?.let { append("Result excerpt:\n<result>\n${it.take(1200)}\n</result>\n") }
        }
        val line = runCatching { summarize(threadId, prompt) }.getOrNull()?.trim()?.lineSequence()?.firstOrNull()?.take(160)
        modelCalls++
        if (!line.isNullOrBlank()) publish(threadId, line)
    }

    fun forget(threadId: String) {
        synchronized(state) { state.remove(threadId) }?.job?.cancel()
    }

    companion object {
        private val TRIGGERS = setOf(EventKind.STEP_DONE, EventKind.COMPLETED, EventKind.FAILED, EventKind.BLOCKED)
    }
}
