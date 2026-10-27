package com.axiom.launcher.solis.engine

import com.axiom.launcher.solis.llm.ToolSpec
import com.axiom.launcher.solis.memory.Space
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** How a tool affects the world; anything but NONE is idempotency-keyed and leased. */
enum class SideEffect { NONE, LOCAL, DRAFT, EXTERNAL }

data class ToolContext(
    val threadId: String,
    val turn: Int,
    val stepId: String,
    val space: Space,
    val permissions: ThreadPermissions,
)

data class ToolResult(val text: String, val ok: Boolean = true, val summary: String = "")

/**
 * A tool Solis can call. [required] lists argument names that must be present (inputs stream in
 * incrementally, so they are validated before running); [resource] names what the call writes to, for
 * conflict detection between threads.
 */
class SolisTool(
    val name: String,
    val description: String,
    val group: ToolGroup,
    val sideEffect: SideEffect,
    val properties: Map<String, Map<String, Any>>,
    val required: List<String> = emptyList(),
    val resource: ((JSONObject) -> String?)? = null,
    val run: suspend (JSONObject, ToolContext) -> ToolResult,
) {
    val spec: ToolSpec by lazy {
        val props = JSONObject()
        properties.forEach { (k, v) -> props.put(k, JSONObject(v)) }
        ToolSpec(name, description, JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).toString())
    }

    /** Returns an error message when required inputs are missing or empty, else null. */
    fun validate(args: JSONObject): String? {
        val missing = required.filter { !args.has(it) || args.isNull(it) || (args.opt(it) is String && args.optString(it).isBlank()) }
        return if (missing.isEmpty()) null else "Invalid input: missing ${missing.joinToString()}. Call again with valid JSON."
    }

    companion object {
        fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
        fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
        fun enumOf(values: List<String>, desc: String) = mapOf("type" to "string", "enum" to values, "description" to desc)
    }
}

/** Supplies the launcher's phone tools to the engine. */
fun interface ToolHost {
    fun tools(): List<SolisTool>
}

/** Handles deterministic (T0) requests on the device; returns null when the text isn't one. */
fun interface DeterministicHandler {
    suspend fun handle(text: String, space: Space): String?
}

/**
 * Short-lived ownership of a writable resource ("alarm:06:30", "file:/x", "torch"). Prevents two
 * threads from changing the same thing at the same moment; the loser gets a clear conflict message.
 */
class LeaseTable(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Lease(val owner: String, val until: Long)
    private val leases = HashMap<String, Lease>()

    @Synchronized
    fun tryAcquire(resource: String, owner: String, ttlMs: Long = 30_000): String? {
        val now = clock()
        val cur = leases[resource]
        if (cur != null && cur.owner != owner && cur.until > now) return cur.owner
        leases[resource] = Lease(owner, now + ttlMs)
        return null
    }

    @Synchronized
    fun release(resource: String, owner: String) {
        if (leases[resource]?.owner == owner) leases.remove(resource)
    }

    @Synchronized
    fun releaseAll(owner: String) { leases.entries.removeAll { it.value.owner == owner } }

    @Synchronized
    fun holder(resource: String): String? = leases[resource]?.takeIf { it.until > clock() }?.owner
}

object Canonical {
    /** Stable JSON text (sorted keys) so equal arguments hash equally regardless of key order. */
    fun json(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + json(v.get(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { json(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }

    fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
