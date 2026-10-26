package com.axiom.launcher.solis.engine

import com.axiom.launcher.solis.cortex.Assessment
import com.axiom.launcher.solis.cortex.BudgetCaps
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.LedgerEntry
import com.axiom.launcher.solis.cortex.LedgerPersistence
import com.axiom.launcher.solis.cortex.Outcome
import com.axiom.launcher.solis.cortex.PolicyPersistence
import com.axiom.launcher.solis.cortex.PolicyVersion
import com.axiom.launcher.solis.cortex.PrivacyConstraint
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryPersistence
import com.axiom.launcher.solis.memory.SharingRules
import com.axiom.launcher.solis.memory.Space
import org.json.JSONArray
import org.json.JSONObject

enum class ThreadStatus(val label: String, val runnable: Boolean, val acceptsFollowUp: Boolean) {
    QUEUED("Queued", true, false),
    WAITING("Waiting", true, false),
    RUNNING("Working", false, false),
    NEEDS_INPUT("Needs your input", false, true),
    PAUSED("Paused", false, false),
    INTERRUPTED("Interrupted", false, false),
    BLOCKED("Blocked", false, true),
    DONE("Done", false, true),
    FAILED("Failed", false, true),
    CANCELLED("Cancelled", false, true),
}

/** Groups of phone/brain tools a thread may be allowed to use. */
enum class ToolGroup(val label: String) {
    APPS("Open and list apps"),
    FILES_READ("Search and read files"),
    FILES_OPEN("Open files"),
    CLOCK("Timers and alarms"),
    CALENDAR("Calendar"),
    CONTACTS("Contacts"),
    COMMUNICATION("Draft calls, texts, email"),
    MEDIA("Music, maps, volume"),
    SYSTEM("Settings, flashlight, theme, mode"),
    NOTIFICATIONS("Read notifications"),
    CLIPBOARD("Clipboard"),
    NOTES("Home-screen notes"),
    MEMORY("Recall and remember"),
    WEB("Web search"),
    DELEGATE("Start other Solis tasks"),
}

data class ThreadPermissions(
    val tools: Set<ToolGroup> = ToolGroup.entries.toSet(),
    /** May write durable memory into the thread's space (otherwise only thread-scoped notes). */
    val memoryWriteSpace: Boolean = true,
    val maxCostUsd: Double = 0.50,
    /** Resume automatically after the app was killed or the phone restarted. */
    val autoResume: Boolean = false,
    val privacy: PrivacyConstraint = PrivacyConstraint(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tools", JSONArray(tools.map { it.name }))
        .put("memoryWriteSpace", memoryWriteSpace)
        .put("maxCostUsd", maxCostUsd)
        .put("autoResume", autoResume)
        .put("cloud", privacy.cloudAllowed).put("zdr", privacy.zdr).put("deny", privacy.denyDataCollection)

    companion object {
        fun fromJson(o: JSONObject?): ThreadPermissions {
            if (o == null) return ThreadPermissions()
            val tools = o.optJSONArray("tools")?.let { a -> (0 until a.length()).mapNotNull { runCatching { ToolGroup.valueOf(a.getString(it)) }.getOrNull() }.toSet() }
            return ThreadPermissions(
                tools = tools ?: ToolGroup.entries.toSet(),
                memoryWriteSpace = o.optBoolean("memoryWriteSpace", true),
                maxCostUsd = o.optDouble("maxCostUsd", 0.5),
                autoResume = o.optBoolean("autoResume", false),
                privacy = PrivacyConstraint(o.optBoolean("cloud", true), o.optBoolean("zdr", false), o.optBoolean("deny", true)),
            )
        }
    }
}

data class ThreadUsage(
    val calls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val byRole: Map<String, Double> = emptyMap(),
) {
    fun plus(role: CallRole, inTok: Int, outTok: Int, cost: Double) = copy(
        calls = calls + 1, inputTokens = inputTokens + inTok, outputTokens = outputTokens + outTok, costUsd = costUsd + cost,
        byRole = byRole + (role.name to ((byRole[role.name] ?: 0.0) + cost)),
    )

    fun toJson(): JSONObject = JSONObject().put("calls", calls).put("in", inputTokens).put("out", outputTokens)
        .put("cost", costUsd).put("byRole", JSONObject(byRole))

    companion object {
        fun fromJson(o: JSONObject?): ThreadUsage = if (o == null) ThreadUsage() else ThreadUsage(
            o.optInt("calls"), o.optInt("in"), o.optInt("out"), o.optDouble("cost", 0.0),
            o.optJSONObject("byRole")?.let { b -> b.keys().asSequence().associateWith { b.getDouble(it) } } ?: emptyMap(),
        )
    }
}

/** An independent Solis agent thread: own identity, goal, context, checkpoints, permissions and models. */
data class AgentThread(
    val id: String,
    val title: String,
    val space: Space,
    val modeId: String,
    val status: ThreadStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** Increments with each user message; checkpoints and actions are per turn. */
    val turn: Int = 1,
    val permissions: ThreadPermissions = ThreadPermissions(),
    val dependsOn: List<String> = emptyList(),
    val tier: Tier? = null,
    /** Role -> model actually used most recently (for the task overview). */
    val models: Map<String, String> = emptyMap(),
    val usage: ThreadUsage = ThreadUsage(),
    /** Deterministic, always-truthful status line. */
    val statusText: String = "",
    /** Optional summary written by the background narrator model. */
    val narration: String? = null,
    val blocker: String? = null,
    val lastResult: String? = null,
    val pinned: Map<CallRole, ModelRef> = emptyMap(),
    /** Frozen at creation so provider conversations stay append-only. */
    val system: String = "",
    val toolNames: List<String> = emptyList(),
    val stepsDone: Int = 0,
    val stepsTotal: Int = 0,
    val leaseOwner: String? = null,
    val origin: String = "user",
) {
    val isActive: Boolean get() = status == ThreadStatus.RUNNING || status == ThreadStatus.QUEUED || status == ThreadStatus.WAITING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("space", space.name); put("modeId", modeId); put("status", status.name)
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("turn", turn)
        put("permissions", permissions.toJson()); put("dependsOn", JSONArray(dependsOn))
        put("tier", tier?.name ?: JSONObject.NULL); put("models", JSONObject(models)); put("usage", usage.toJson())
        put("statusText", statusText); put("narration", narration ?: JSONObject.NULL); put("blocker", blocker ?: JSONObject.NULL)
        put("lastResult", lastResult ?: JSONObject.NULL)
        put("pinned", JSONObject(pinned.mapKeys { it.key.name }.mapValues { it.value.key }))
        put("system", system); put("toolNames", JSONArray(toolNames)); put("stepsDone", stepsDone); put("stepsTotal", stepsTotal)
        put("leaseOwner", leaseOwner ?: JSONObject.NULL); put("origin", origin)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

        fun fromJson(o: JSONObject): AgentThread = AgentThread(
            id = o.getString("id"), title = o.optString("title"),
            space = runCatching { Space.valueOf(o.optString("space")) }.getOrDefault(Space.PERSONAL),
            modeId = o.optString("modeId", "personal"),
            status = runCatching { ThreadStatus.valueOf(o.optString("status")) }.getOrDefault(ThreadStatus.INTERRUPTED),
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"), turn = o.optInt("turn", 1),
            permissions = ThreadPermissions.fromJson(o.optJSONObject("permissions")),
            dependsOn = o.optJSONArray("dependsOn")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            tier = o.str("tier")?.let { runCatching { Tier.valueOf(it) }.getOrNull() },
            models = o.optJSONObject("models")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap(),
            usage = ThreadUsage.fromJson(o.optJSONObject("usage")),
            statusText = o.optString("statusText"), narration = o.str("narration"), blocker = o.str("blocker"),
            lastResult = o.str("lastResult"),
            pinned = o.optJSONObject("pinned")?.let { p ->
                p.keys().asSequence().mapNotNull { k -> runCatching { CallRole.valueOf(k) }.getOrNull()?.let { r -> ModelRef.parse(p.getString(k))?.let { r to it } } }.toMap()
            } ?: emptyMap(),
            system = o.optString("system"),
            toolNames = o.optJSONArray("toolNames")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            stepsDone = o.optInt("stepsDone"), stepsTotal = o.optInt("stepsTotal"),
            leaseOwner = o.str("leaseOwner"), origin = o.optString("origin", "user"),
        )
    }
}

enum class EntryKind { USER, ASSISTANT, TOOL, ROUTE, STATUS, ERROR }

/** One line of a thread's visible history. */
data class TranscriptEntry(
    val threadId: String,
    val seq: Long,
    val turn: Int,
    val kind: EntryKind,
    val text: String,
    val ts: Long,
    /** Extra detail (routing explanation, tool name, model), JSON. */
    val meta: String? = null,
)

/** Where a turn got to. Steps that finished are never re-run after a restart. */
enum class Phase { ASSESSED, PLANNED, STEP_DONE, COMBINED, VERIFIED, DONE }

data class Checkpoint(
    val threadId: String,
    val turn: Int,
    val seq: Int,
    val phase: Phase,
    val state: String,
    val ts: Long,
    val note: String,
)

data class StepResult(val stepId: String, val ok: Boolean, val text: String, val model: String, val tools: List<String>)

/** Serializable progress of one turn, written into every checkpoint. */
data class TurnState(
    var assessment: Assessment? = null,
    val results: MutableMap<String, StepResult> = LinkedHashMap(),
    var finalText: String? = null,
    var verified: Boolean? = null,
    var verifyNote: String? = null,
    var escalations: Int = 0,
    var tierOverride: Tier? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("assessment", assessment?.toJson() ?: JSONObject.NULL)
        put("results", JSONObject().apply {
            results.values.forEach { r ->
                put(r.stepId, JSONObject().put("ok", r.ok).put("text", r.text).put("model", r.model).put("tools", JSONArray(r.tools)))
            }
        })
        put("finalText", finalText ?: JSONObject.NULL)
        put("verified", verified ?: JSONObject.NULL)
        put("verifyNote", verifyNote ?: JSONObject.NULL)
        put("escalations", escalations)
        put("tierOverride", tierOverride?.name ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(s: String?): TurnState {
            if (s.isNullOrBlank()) return TurnState()
            val o = JSONObject(s)
            val st = TurnState(
                assessment = o.optJSONObject("assessment")?.let { Assessment.fromJson(it) },
                finalText = if (o.isNull("finalText")) null else o.optString("finalText"),
                verified = if (o.isNull("verified")) null else o.optBoolean("verified"),
                verifyNote = if (o.isNull("verifyNote")) null else o.optString("verifyNote"),
                escalations = o.optInt("escalations"),
                tierOverride = if (o.isNull("tierOverride")) null else runCatching { Tier.valueOf(o.getString("tierOverride")) }.getOrNull(),
            )
            o.optJSONObject("results")?.let { r ->
                r.keys().forEach { k ->
                    val j = r.getJSONObject(k)
                    st.results[k] = StepResult(k, j.optBoolean("ok"), j.optString("text"), j.optString("model"),
                        j.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
                }
            }
            return st
        }
    }
}

/** A side-effecting tool call that already happened; replays return this instead of acting twice. */
data class ActionRecord(
    val key: String,
    val threadId: String,
    val turn: Int,
    val stepId: String,
    val tool: String,
    val args: String,
    val result: String,
    val ok: Boolean,
    val ts: Long,
)

enum class LivePhase { ASSESSING, PLANNING, THINKING, STREAMING, TOOLS, VERIFYING, COMBINING }

/** Ephemeral per-thread state for the UI (not persisted). */
data class LiveState(
    val phase: LivePhase,
    val text: String = "",
    val model: String? = null,
    val step: String? = null,
)

/** Solis's face states. Derived from real engine state, never decorative. */
enum class Mood(val label: String) {
    IDLE("Idle"), LISTENING("Listening"), THINKING("Thinking"), WORKING("Working"), PAUSED("Paused"), OFFLINE("Offline"), ERROR("Error")
}

data class SolisSettings(
    val maxConcurrentThreads: Int = 3,
    val maxQueued: Int = 12,
    val caps: BudgetCaps = BudgetCaps(),
    val modeDailyCaps: Map<String, Double> = emptyMap(),
    val allowUnknownPrice: Boolean = false,
    val pinned: Map<CallRole, ModelRef> = emptyMap(),
    val sharing: SharingRules = SharingRules(),
    /** User-forced offline mode: only local runtimes and on-device actions. */
    val offline: Boolean = false,
    val userName: String = "",
    val persona: String? = null,
)

/** Persistence for everything the brain must survive process death and reboots with. */
interface BrainStore : MemoryPersistence, PolicyPersistence, LedgerPersistence {
    fun loadThreads(): List<AgentThread>
    fun upsertThread(t: AgentThread)
    fun deleteThread(id: String)
    fun appendEntry(e: TranscriptEntry)
    fun entries(threadId: String, limit: Int = 400): List<TranscriptEntry>
    fun putCheckpoint(c: Checkpoint)
    fun lastCheckpoint(threadId: String, turn: Int): Checkpoint?
    fun checkpoints(threadId: String): List<Checkpoint>
    fun findAction(key: String): ActionRecord?
    fun putAction(a: ActionRecord)
    fun actions(threadId: String, turn: Int): List<ActionRecord>
    fun addOutcome(o: Outcome)
    fun loadOutcomes(since: Long, limit: Int = 2000): List<Outcome>
    fun kvGet(key: String): String?
    fun kvPut(key: String, value: String?)
}

/** Thread-safe in-memory store (tests, and a fallback if the database can't be opened). */
open class InMemoryBrainStore : BrainStore {
    private val lock = Any()
    private val threads = LinkedHashMap<String, AgentThread>()
    private val entries = HashMap<String, MutableList<TranscriptEntry>>()
    private val checkpoints = HashMap<String, MutableList<Checkpoint>>()
    private val actions = HashMap<String, ActionRecord>()
    private val outcomes = ArrayList<Outcome>()
    private val memory = LinkedHashMap<String, MemoryItem>()
    private val policies = sortedMapOf<Int, PolicyVersion>()
    private val ledger = ArrayList<LedgerEntry>()
    private val kv = HashMap<String, String>()

    override fun loadThreads() = synchronized(lock) { threads.values.toList() }
    override fun upsertThread(t: AgentThread) = synchronized(lock) { threads[t.id] = t }
    override fun deleteThread(id: String) = synchronized(lock) { threads.remove(id); entries.remove(id); checkpoints.remove(id); Unit }
    override fun appendEntry(e: TranscriptEntry) = synchronized(lock) { entries.getOrPut(e.threadId) { ArrayList() } += e }
    override fun entries(threadId: String, limit: Int) = synchronized(lock) { entries[threadId]?.takeLast(limit)?.toList() ?: emptyList() }
    override fun putCheckpoint(c: Checkpoint) = synchronized(lock) { checkpoints.getOrPut(c.threadId) { ArrayList() } += c }
    override fun lastCheckpoint(threadId: String, turn: Int) = synchronized(lock) { checkpoints[threadId]?.lastOrNull { it.turn == turn } }
    override fun checkpoints(threadId: String) = synchronized(lock) { checkpoints[threadId]?.toList() ?: emptyList() }
    override fun findAction(key: String) = synchronized(lock) { actions[key] }
    override fun putAction(a: ActionRecord) = synchronized(lock) { actions[a.key] = a }
    override fun actions(threadId: String, turn: Int) = synchronized(lock) { actions.values.filter { it.threadId == threadId && it.turn == turn }.sortedBy { it.ts } }
    override fun addOutcome(o: Outcome) = synchronized(lock) { outcomes += o; Unit }
    override fun loadOutcomes(since: Long, limit: Int) = synchronized(lock) { outcomes.filter { it.ts >= since }.takeLast(limit) }
    override fun kvGet(key: String) = synchronized(lock) { kv[key] }
    override fun kvPut(key: String, value: String?) = synchronized(lock) { if (value == null) kv.remove(key) else kv[key] = value; Unit }
    override fun loadMemory() = synchronized(lock) { memory.values.toList() }
    override fun upsertMemory(item: MemoryItem) = synchronized(lock) { memory[item.id] = item }
    override fun deleteMemory(id: String) = synchronized(lock) { memory.remove(id); Unit }
    override fun loadPolicies() = synchronized(lock) { policies.values.toList() }
    override fun putPolicy(p: PolicyVersion) = synchronized(lock) { policies[p.version] = p }
    override fun loadLedger(since: Long) = synchronized(lock) { ledger.filter { it.ts >= since } }
    override fun addLedger(e: LedgerEntry) = synchronized(lock) { ledger += e; Unit }
}

/** Markdown → plain text, for one-line previews, notifications and memory summaries. */
fun plainText(md: String): String = md
    .replace(Regex("```[A-Za-z0-9]*\\n?"), "")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")

/** The thread's one truthful status line: narration if any, else the deterministic status text. */
val AgentThread.statusLine: String get() = (narration ?: statusText).takeIf { it.isNotBlank() } ?: status.label
