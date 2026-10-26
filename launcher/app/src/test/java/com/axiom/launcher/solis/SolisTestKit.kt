package com.axiom.launcher.solis

import com.axiom.launcher.solis.catalog.CatalogSnapshot
import com.axiom.launcher.solis.catalog.ModelInfo
import com.axiom.launcher.solis.cortex.BudgetLedger
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import com.axiom.launcher.solis.cortex.PolicyBook
import com.axiom.launcher.solis.cortex.StatsBook
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.DeterministicHandler
import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.engine.ProviderRegistry
import com.axiom.launcher.solis.engine.SideEffect
import com.axiom.launcher.solis.engine.SolisEngine
import com.axiom.launcher.solis.engine.SolisSettings
import com.axiom.launcher.solis.engine.SolisTool
import com.axiom.launcher.solis.engine.ThreadStatus
import com.axiom.launcher.solis.engine.ToolGroup
import com.axiom.launcher.solis.engine.ToolHost
import com.axiom.launcher.solis.engine.ToolResult
import com.axiom.launcher.solis.llm.ChatRequest
import com.axiom.launcher.solis.llm.Effort
import com.axiom.launcher.solis.llm.FinishReason
import com.axiom.launcher.solis.llm.LlmEvent
import com.axiom.launcher.solis.llm.LlmProvider
import com.axiom.launcher.solis.llm.ProviderException
import com.axiom.launcher.solis.llm.ToolCall
import com.axiom.launcher.solis.memory.MemoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** A scripted reply: either events to emit (with an optional delay) or an error, or hang forever. */
sealed interface Script {
    data class Reply(val events: List<LlmEvent>, val delayMs: Long = 0) : Script
    data class Fail(val error: ProviderException, val delayMs: Long = 0) : Script
    object Hang : Script

    companion object {
        fun text(t: String, cost: Double = 0.001, delayMs: Long = 0) = Reply(
            listOf(LlmEvent.TextDelta(t), LlmEvent.Usage(100, 20, costUsd = cost), LlmEvent.Finish(FinishReason.STOP)), delayMs)

        fun tool(name: String, args: String, id: String = "call_${name}", cost: Double = 0.001) = Reply(
            listOf(LlmEvent.ToolCallReady(ToolCall(id, name, args)), LlmEvent.Usage(100, 10, costUsd = cost), LlmEvent.Finish(FinishReason.TOOL_CALLS)))
    }
}

/** Records every request and answers from a script function keyed by model id. */
class FakeProvider(
    override val id: String = "fake",
    override val isLocal: Boolean = false,
    var configured: Boolean = true,
    var script: (ChatRequest, Int) -> Script = { _, _ -> Script.text("ok") },
) : LlmProvider {
    override val displayName = "Fake $id"
    val requests: MutableList<ChatRequest> = Collections.synchronizedList(ArrayList())
    private val counter = AtomicInteger()
    val inFlight = AtomicInteger()
    @Volatile var maxInFlight = 0

    override fun isConfigured() = configured
    override suspend fun listModels(): List<ModelInfo> = emptyList()

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        val n = counter.getAndIncrement()
        val now = inFlight.incrementAndGet()
        synchronized(this@FakeProvider) { if (now > maxInFlight) maxInFlight = now }
        try {
            when (val s = script(request, n)) {
                is Script.Reply -> { if (s.delayMs > 0) delay(s.delayMs); s.events.forEach { emit(it) } }
                is Script.Fail -> { if (s.delayMs > 0) delay(s.delayMs); throw s.error }
                Script.Hang -> awaitCancellation()
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    fun requestsFor(model: String) = requests.filter { it.model == model }
}

object Models {
    fun m(id: String, inP: Double, outP: Double, quality: Double?, tools: Boolean = true, images: Boolean = false,
          efforts: List<Effort> = emptyList(), provider: String = "fake", local: Boolean = false, ctx: Int = 128_000) = ModelInfo(
        provider = provider, id = id, name = id.substringAfter('/'), contextLength = ctx, inputPrice = inP, outputPrice = outP,
        supportsTools = tools, supportsJson = true, inputModalities = if (images) setOf("text", "image") else setOf("text"),
        efforts = efforts, qualityIndex = quality, isLocal = local,
    )

    // Prices are per token.
    val cheap = m("acme/cheap", 1e-7, 4e-7, 30.0)
    val mid = m("acme/mid", 1e-6, 4e-6, 50.0, efforts = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH))
    val strong = m("zeta/strong", 4e-6, 2e-5, 70.0, efforts = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.MAX))
    val judge = m("orbit/judge", 2e-6, 1e-5, 65.0)
    val noTools = m("plain/talker", 5e-8, 2e-7, 20.0, tools = false)

    val all = listOf(cheap, mid, strong, judge, noTools)
}

class CountingTools : ToolHost {
    val alarmRuns = AtomicInteger()
    val noteRuns = AtomicInteger()

    override fun tools() = listOf(
        SolisTool("set_alarm", "Set an alarm", ToolGroup.CLOCK, SideEffect.LOCAL,
            mapOf("hour" to SolisTool.int("h"), "minute" to SolisTool.int("m")), listOf("hour", "minute"),
            resource = { a -> "alarm:${a.optInt("hour")}:${a.optInt("minute")}" }) { a, _ ->
            alarmRuns.incrementAndGet()
            ToolResult("Alarm set for ${a.getInt("hour")}:${a.getInt("minute")}", true, "alarm set")
        },
        SolisTool("note_add", "Add a note", ToolGroup.NOTES, SideEffect.LOCAL, mapOf("text" to SolisTool.str("t")), listOf("text")) { a, _ ->
            noteRuns.incrementAndGet()
            ToolResult("Saved note: ${a.getString("text")}", true, "note saved")
        },
        SolisTool("list_apps", "List apps", ToolGroup.APPS, SideEffect.NONE, emptyMap()) { _, ctx ->
            ToolResult(if (ctx.space.name == "WORK") "Slack (work), Gmail (work)" else "Spotify, Camera", true, "apps")
        },
    )
}

/** Freezable store: after [freeze], writes are dropped — simulates the process dying mid-task. */
class CrashableStore : InMemoryBrainStore() {
    @Volatile var frozen = false
    override fun upsertThread(t: AgentThread) { if (!frozen) super.upsertThread(t) }
    override fun appendEntry(e: com.axiom.launcher.solis.engine.TranscriptEntry) { if (!frozen) super.appendEntry(e) }
    override fun putCheckpoint(c: com.axiom.launcher.solis.engine.Checkpoint) { if (!frozen) super.putCheckpoint(c) }
    override fun putAction(a: com.axiom.launcher.solis.engine.ActionRecord) { if (!frozen) super.putAction(a) }
    override fun upsertMemory(item: com.axiom.launcher.solis.memory.MemoryItem) { if (!frozen) super.upsertMemory(item) }
    override fun addOutcome(o: com.axiom.launcher.solis.cortex.Outcome) { if (!frozen) super.addOutcome(o) }
    override fun addLedger(e: com.axiom.launcher.solis.cortex.LedgerEntry) { if (!frozen) super.addLedger(e) }
}

class Rig(
    val store: InMemoryBrainStore = InMemoryBrainStore(),
    val providers: List<FakeProvider> = listOf(FakeProvider()),
    var settings: SolisSettings = SolisSettings(),
    var device: DeviceSnapshot = DeviceSnapshot(),
    val tools: CountingTools = CountingTools(),
    deterministic: DeterministicHandler? = null,
    models: List<ModelInfo> = Models.all,
) {
    val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e; e.printStackTrace() })
    val registry = ProviderRegistry(store)
    val memory = MemoryService(store)
    val engine: SolisEngine

    init {
        providers.forEach { registry.register(it) }
        models.groupBy { it.provider }.forEach { (p, list) -> registry.catalog.replace(CatalogSnapshot(p, System.currentTimeMillis(), list)) }
        engine = SolisEngine(
            scope = scope, store = store, providers = registry, memory = memory, policies = PolicyBook(store),
            ledger = BudgetLedger(store), stats = StatsBook(), toolHost = tools, settings = { settings }, device = { device },
            deterministic = deterministic, contextLine = { "now: test" },
        )
    }

    fun start(): Rig { engine.start(); live += this; return this }
    fun thread(id: String) = engine.thread(id)!!

    fun kill() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    companion object {
        /** Rigs started by the current test; stopped after each test so engines never outlive it. */
        val live: MutableList<Rig> = Collections.synchronizedList(ArrayList())
        fun killAll() { synchronized(live) { live.forEach { it.kill() }; live.clear() } }
    }
}

fun awaitUntil(timeoutMs: Long = 8000, what: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out waiting for $what")
}

fun awaitStatus(rig: Rig, id: String, vararg s: ThreadStatus, timeoutMs: Long = 8000) =
    awaitUntil(timeoutMs, "status ${s.joinToString()} (now ${rig.engine.thread(id)?.status} / ${rig.engine.thread(id)?.blocker}) [${rig.engine.debugInfo()}]") {
        rig.engine.thread(id)?.status in s
    }
