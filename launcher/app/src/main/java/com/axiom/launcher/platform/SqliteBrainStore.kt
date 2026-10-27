package com.axiom.launcher.platform

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.axiom.launcher.solis.cortex.CallRole
import com.axiom.launcher.solis.cortex.LedgerEntry
import com.axiom.launcher.solis.cortex.Outcome
import com.axiom.launcher.solis.cortex.PolicyVersion
import com.axiom.launcher.solis.cortex.Tier
import com.axiom.launcher.solis.engine.ActionRecord
import com.axiom.launcher.solis.engine.AgentThread
import com.axiom.launcher.solis.engine.BrainStore
import com.axiom.launcher.solis.engine.Checkpoint
import com.axiom.launcher.solis.engine.EntryKind
import com.axiom.launcher.solis.engine.Phase
import com.axiom.launcher.solis.engine.TranscriptEntry
import com.axiom.launcher.solis.llm.ModelRef
import com.axiom.launcher.solis.memory.MemoryItem
import com.axiom.launcher.solis.memory.MemoryService
import org.json.JSONObject

/**
 * The brain's durable store: one SQLite database in WAL mode. Everything Solis needs to continue after
 * process death, a reboot or a model change lives here. API keys never do (see [SecretStore]).
 * All methods block; callers run them off the main thread.
 */
class SqliteBrainStore(context: Context, name: String = DB_NAME) :
    SQLiteOpenHelper(context, name, null, VERSION), BrainStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE threads (id TEXT PRIMARY KEY, status TEXT, updated_at INTEGER, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE entries (thread_id TEXT, seq INTEGER, turn INTEGER, kind TEXT, text TEXT, ts INTEGER, meta TEXT, PRIMARY KEY(thread_id, seq))")
        db.execSQL("CREATE TABLE checkpoints (thread_id TEXT, turn INTEGER, seq INTEGER, phase TEXT, state TEXT, ts INTEGER, note TEXT, PRIMARY KEY(thread_id, turn, seq))")
        db.execSQL("CREATE TABLE actions (k TEXT PRIMARY KEY, thread_id TEXT, turn INTEGER, step_id TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, ts INTEGER)")
        db.execSQL("CREATE INDEX actions_thread ON actions(thread_id, turn)")
        db.execSQL("CREATE TABLE outcomes (id TEXT PRIMARY KEY, ts INTEGER, json TEXT)")
        db.execSQL("CREATE INDEX outcomes_ts ON outcomes(ts)")
        db.execSQL("CREATE TABLE memory (id TEXT PRIMARY KEY, scope TEXT, updated_at INTEGER, json TEXT)")
        db.execSQL("CREATE TABLE policies (version INTEGER PRIMARY KEY, json TEXT)")
        db.execSQL("CREATE TABLE ledger (id TEXT PRIMARY KEY, ts INTEGER, json TEXT)")
        db.execSQL("CREATE INDEX ledger_ts ON ledger(ts)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; future migrations go here, never destructive.
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) prune(db)
    }

    /** Keeps the database bounded: old outcomes/ledger rows and long transcripts are trimmed. */
    private fun prune(db: SQLiteDatabase) = runCatching {
        val now = System.currentTimeMillis()
        db.delete("outcomes", "ts < ?", arrayOf((now - 120L * 86_400_000).toString()))
        db.delete("ledger", "ts < ?", arrayOf((now - 400L * 86_400_000).toString()))
        // Keep the newest MAX_ENTRIES per thread: one indexed lookup per oversized thread.
        val heavy = db.rawQuery("SELECT thread_id FROM entries GROUP BY thread_id HAVING COUNT(*) > $MAX_ENTRIES", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        heavy.forEach { tid ->
            val cut = db.rawQuery("SELECT seq FROM entries WHERE thread_id = ? ORDER BY seq DESC LIMIT 1 OFFSET ${MAX_ENTRIES - 1}", arrayOf(tid))
                .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (cut != null) db.delete("entries", "thread_id = ? AND seq < ?", arrayOf(tid, cut.toString()))
        }
        db.execSQL("DELETE FROM checkpoints WHERE turn < (SELECT MAX(turn) - 2 FROM checkpoints c WHERE c.thread_id = checkpoints.thread_id)")
    }

    private val db: SQLiteDatabase get() = writableDatabase

    // ---------------- threads ----------------

    override fun loadThreads(): List<AgentThread> = db.rawQuery("SELECT json FROM threads ORDER BY updated_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(AgentThread.fromJson(JSONObject(c.getString(0)))) } }
    }

    override fun upsertThread(t: AgentThread) {
        db.insertWithOnConflict("threads", null, ContentValues().apply {
            put("id", t.id); put("status", t.status.name); put("updated_at", t.updatedAt); put("json", t.toJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteThread(id: String) {
        db.beginTransaction()
        try {
            listOf("threads" to "id", "entries" to "thread_id", "checkpoints" to "thread_id", "actions" to "thread_id")
                .forEach { (table, col) -> db.delete(table, "$col = ?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- transcript ----------------

    override fun appendEntry(e: TranscriptEntry) {
        db.insertWithOnConflict("entries", null, ContentValues().apply {
            put("thread_id", e.threadId); put("seq", e.seq); put("turn", e.turn); put("kind", e.kind.name)
            put("text", e.text); put("ts", e.ts); put("meta", e.meta)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun entries(threadId: String, limit: Int): List<TranscriptEntry> =
        db.rawQuery("SELECT seq, turn, kind, text, ts, meta FROM (SELECT * FROM entries WHERE thread_id = ? ORDER BY seq DESC LIMIT ?) ORDER BY seq ASC",
            arrayOf(threadId, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(TranscriptEntry(threadId, c.getLong(0), c.getInt(1),
                    runCatching { EntryKind.valueOf(c.getString(2)) }.getOrDefault(EntryKind.STATUS), c.getString(3), c.getLong(4),
                    if (c.isNull(5)) null else c.getString(5)))
            }
        }

    // ---------------- checkpoints ----------------

    override fun putCheckpoint(c: Checkpoint) {
        db.insertWithOnConflict("checkpoints", null, ContentValues().apply {
            put("thread_id", c.threadId); put("turn", c.turn); put("seq", c.seq); put("phase", c.phase.name)
            put("state", c.state); put("ts", c.ts); put("note", c.note)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun lastCheckpoint(threadId: String, turn: Int): Checkpoint? =
        db.rawQuery("SELECT seq, phase, state, ts, note FROM checkpoints WHERE thread_id = ? AND turn = ? ORDER BY seq DESC LIMIT 1",
            arrayOf(threadId, turn.toString())).use { c ->
            if (!c.moveToFirst()) null else Checkpoint(threadId, turn, c.getInt(0), Phase.valueOf(c.getString(1)), c.getString(2), c.getLong(3), c.getString(4))
        }

    override fun checkpoints(threadId: String): List<Checkpoint> =
        db.rawQuery("SELECT turn, seq, phase, state, ts, note FROM checkpoints WHERE thread_id = ? ORDER BY turn, seq", arrayOf(threadId)).use { c ->
            buildList { while (c.moveToNext()) add(Checkpoint(threadId, c.getInt(0), c.getInt(1), Phase.valueOf(c.getString(2)), c.getString(3), c.getLong(4), c.getString(5))) }
        }

    // ---------------- idempotent actions ----------------

    override fun findAction(key: String): ActionRecord? =
        db.rawQuery("SELECT thread_id, turn, step_id, tool, args, result, ok, ts FROM actions WHERE k = ?", arrayOf(key)).use { c ->
            if (!c.moveToFirst()) null else ActionRecord(key, c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6) == 1, c.getLong(7))
        }

    override fun putAction(a: ActionRecord) {
        db.insertWithOnConflict("actions", null, ContentValues().apply {
            put("k", a.key); put("thread_id", a.threadId); put("turn", a.turn); put("step_id", a.stepId); put("tool", a.tool)
            put("args", a.args); put("result", a.result); put("ok", if (a.ok) 1 else 0); put("ts", a.ts)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun actions(threadId: String, turn: Int): List<ActionRecord> =
        db.rawQuery("SELECT k, step_id, tool, args, result, ok, ts FROM actions WHERE thread_id = ? AND turn = ? ORDER BY ts", arrayOf(threadId, turn.toString())).use { c ->
            buildList { while (c.moveToNext()) add(ActionRecord(c.getString(0), threadId, turn, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) == 1, c.getLong(6))) }
        }

    // ---------------- outcomes / ledger ----------------

    override fun addOutcome(o: Outcome) {
        db.insertWithOnConflict("outcomes", null, ContentValues().apply { put("id", o.id); put("ts", o.ts); put("json", outcomeJson(o).toString()) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun loadOutcomes(since: Long, limit: Int): List<Outcome> =
        db.rawQuery("SELECT json FROM (SELECT json, ts FROM outcomes WHERE ts >= ? ORDER BY ts DESC LIMIT ?) ORDER BY ts", arrayOf(since.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) runCatching { add(outcomeFrom(JSONObject(c.getString(0)))) } }
        }

    override fun loadLedger(since: Long): List<LedgerEntry> =
        db.rawQuery("SELECT json FROM ledger WHERE ts >= ? ORDER BY ts", arrayOf(since.toString())).use { c ->
            buildList { while (c.moveToNext()) runCatching { add(ledgerFrom(JSONObject(c.getString(0)))) } }
        }

    override fun addLedger(e: LedgerEntry) {
        db.insertWithOnConflict("ledger", null, ContentValues().apply { put("id", e.id); put("ts", e.ts); put("json", ledgerJson(e).toString()) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------- memory / policies / kv ----------------

    override fun loadMemory(): List<MemoryItem> = db.rawQuery("SELECT json FROM memory", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(MemoryService.fromJson(JSONObject(c.getString(0)))) } }
    }

    override fun upsertMemory(item: MemoryItem) {
        db.insertWithOnConflict("memory", null, ContentValues().apply {
            put("id", item.id); put("scope", item.scope.key); put("updated_at", item.updatedAt); put("json", MemoryService.toJson(item).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteMemory(id: String) { db.delete("memory", "id = ?", arrayOf(id)) }

    override fun loadPolicies(): List<PolicyVersion> = db.rawQuery("SELECT json FROM policies ORDER BY version", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(PolicyVersion.fromJson(JSONObject(c.getString(0)))) } }
    }

    override fun putPolicy(p: PolicyVersion) {
        db.insertWithOnConflict("policies", null, ContentValues().apply { put("version", p.version); put("json", p.toJson().toString()) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun kvGet(key: String): String? = db.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun kvPut(key: String, value: String?) {
        if (value == null) db.delete("kv", "k = ?", arrayOf(key))
        else db.insertWithOnConflict("kv", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val DB_NAME = "solis.db"
        private const val VERSION = 1
        private const val MAX_ENTRIES = 2000

        fun outcomeJson(o: Outcome): JSONObject = JSONObject().apply {
            put("id", o.id); put("ts", o.ts); put("thread", o.threadId); put("role", o.role.name); put("tier", o.tier.name)
            put("model", o.model.key); put("ok", o.ok); put("success", o.success ?: JSONObject.NULL); put("latency", o.latencyMs)
            put("cost", o.costUsd); put("in", o.inputTokens); put("out", o.outputTokens); put("error", o.errorKind ?: JSONObject.NULL)
            put("policy", o.policyVersion); put("trial", o.trial)
        }

        fun outcomeFrom(j: JSONObject): Outcome = Outcome(
            id = j.getString("id"), ts = j.getLong("ts"), threadId = j.optString("thread"),
            role = CallRole.valueOf(j.getString("role")), tier = Tier.valueOf(j.getString("tier")),
            model = ModelRef.parse(j.getString("model"))!!, ok = j.optBoolean("ok"),
            success = if (j.isNull("success")) null else j.optBoolean("success"), latencyMs = j.optLong("latency"),
            costUsd = j.optDouble("cost", 0.0), inputTokens = j.optInt("in"), outputTokens = j.optInt("out"),
            errorKind = if (j.isNull("error")) null else j.optString("error"), policyVersion = j.optInt("policy"), trial = j.optBoolean("trial"),
        )

        fun ledgerJson(e: LedgerEntry): JSONObject = JSONObject().apply {
            put("id", e.id); put("ts", e.ts); put("thread", e.threadId); put("mode", e.modeId); put("role", e.role.name)
            put("model", e.model); put("cost", e.costUsd); put("in", e.inputTokens); put("out", e.outputTokens); put("est", e.estimated)
        }

        fun ledgerFrom(j: JSONObject): LedgerEntry = LedgerEntry(
            j.getString("id"), j.getLong("ts"), j.optString("thread"), j.optString("mode"), CallRole.valueOf(j.getString("role")),
            j.optString("model"), j.optDouble("cost", 0.0), j.optInt("in"), j.optInt("out"), j.optBoolean("est"),
        )
    }
}

/**
 * Opens the durable store on first use (always a background thread: the engine loads on its IO
 * dispatcher) and degrades to an in-memory store if the database can't be opened, so the launcher keeps
 * working. [persistent] tells the UI whether memory and tasks will survive a restart.
 */
class FallbackBrainStore(primary: () -> BrainStore, fallback: () -> BrainStore) : BrainStore {
    @Volatile var persistent: Boolean = true; private set
    @Volatile var openError: String? = null; private set

    private val target: BrainStore by lazy {
        runCatching { primary().also { it.kvGet("schema_probe") } }.getOrElse { e ->
            persistent = false
            openError = "${e.javaClass.simpleName}: ${e.message}"
            fallback()
        }
    }

    override fun loadThreads() = target.loadThreads()
    override fun upsertThread(t: AgentThread) = target.upsertThread(t)
    override fun deleteThread(id: String) = target.deleteThread(id)
    override fun appendEntry(e: TranscriptEntry) = target.appendEntry(e)
    override fun entries(threadId: String, limit: Int) = target.entries(threadId, limit)
    override fun putCheckpoint(c: Checkpoint) = target.putCheckpoint(c)
    override fun lastCheckpoint(threadId: String, turn: Int) = target.lastCheckpoint(threadId, turn)
    override fun checkpoints(threadId: String) = target.checkpoints(threadId)
    override fun findAction(key: String) = target.findAction(key)
    override fun putAction(a: ActionRecord) = target.putAction(a)
    override fun actions(threadId: String, turn: Int) = target.actions(threadId, turn)
    override fun addOutcome(o: Outcome) = target.addOutcome(o)
    override fun loadOutcomes(since: Long, limit: Int) = target.loadOutcomes(since, limit)
    override fun kvGet(key: String) = target.kvGet(key)
    override fun kvPut(key: String, value: String?) = target.kvPut(key, value)
    override fun loadMemory() = target.loadMemory()
    override fun upsertMemory(item: MemoryItem) = target.upsertMemory(item)
    override fun deleteMemory(id: String) = target.deleteMemory(id)
    override fun loadPolicies() = target.loadPolicies()
    override fun putPolicy(p: PolicyVersion) = target.putPolicy(p)
    override fun loadLedger(since: Long) = target.loadLedger(since)
    override fun addLedger(e: LedgerEntry) = target.addLedger(e)
}
