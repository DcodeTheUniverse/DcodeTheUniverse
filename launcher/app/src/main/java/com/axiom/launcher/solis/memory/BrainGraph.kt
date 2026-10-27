package com.axiom.launcher.solis.memory

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One memory in the brain view. Coordinates are layout units: x in -1..1, y in about -0.85..1. */
data class BrainNode(
    val id: String,
    val x: Float,
    val y: Float,
    val kind: MemoryKind,
    val space: Space,
    val threadId: String?,
    val pinned: Boolean,
    val text: String,
)

/** A link between two nodes (indices into [BrainGraph.nodes]); [weight] 0..1. */
data class BrainEdge(val a: Int, val b: Int, val weight: Float, val thread: Boolean = false)

/** A task's cluster of thread-scoped notes, gathered around a hub. */
data class BrainHub(val threadId: String, val title: String, val x: Float, val y: Float)

class BrainGraph(
    val nodes: List<BrainNode>,
    val edges: List<BrainEdge>,
    val hubs: List<BrainHub>,
    /** Memories left out to keep the view light. */
    val hidden: Int,
) {
    private val byId = nodes.withIndex().associate { it.value.id to it.index }
    fun indexOf(id: String): Int? = byId[id]
    fun hub(threadId: String?): BrainHub? = threadId?.let { t -> hubs.firstOrNull { it.threadId == t } }

    companion object {
        val EMPTY = BrainGraph(emptyList(), emptyList(), emptyList(), 0)
    }
}

/**
 * Lays memory out like a brain seen from above: personal memory in the left hemisphere, work memory in
 * the right, each task's notes in a small cluster below, and Solis's core in the middle. Inside a
 * hemisphere nodes follow a sunflower spiral, most important (pinned, long-lived, confident, recent)
 * nearest the centre. The layout is deterministic and needs no iterations, so it is cheap and stable:
 * adding a memory doesn't reshuffle the rest. Links join memories that share distinctive terms.
 */
object BrainLayout {
    private const val GOLDEN = 2.39996323f

    fun build(
        items: List<MemoryItem>,
        threadTitles: Map<String, String>,
        related: (MemoryItem) -> List<Pair<String, Double>>,
        maxNodes: Int = 360,
        now: Long = System.currentTimeMillis(),
    ): BrainGraph {
        if (items.isEmpty()) return BrainGraph.EMPTY
        val kept = items.sortedWith(
            compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.kind.longTerm }.thenByDescending { it.updatedAt },
        ).take(maxNodes)
        val nodes = ArrayList<BrainNode>(kept.size)

        fun importance(m: MemoryItem): Double {
            val ageDays = (now - m.updatedAt).coerceAtLeast(0) / 86_400_000.0
            val kindW = when (m.kind) {
                MemoryKind.PREFERENCE, MemoryKind.DECISION -> 1.0
                MemoryKind.FACT -> 0.9
                MemoryKind.NOTE -> 0.7
                MemoryKind.SUMMARY, MemoryKind.TASK_STATE -> 0.4
            }
            return (if (m.pinned) 2.0 else 0.0) + kindW * (0.5 + 0.5 * m.confidence) / (1.0 + ageDays / 60.0)
        }

        // Hemispheres.
        for ((space, cx) in listOf(Space.PERSONAL to -0.47f, Space.WORK to 0.47f)) {
            val group = kept.filter { it.scope.space == space && !it.scope.isThread }.sortedByDescending { importance(it) }
            val n = group.size
            group.forEachIndexed { k, m ->
                val rf = sqrt((k + 0.6f) / (n + 0.6f))
                val a = k * GOLDEN
                // Mirror the spiral so both hemispheres open away from the midline.
                val dx = cos(a) * 0.38f * rf * (if (space == Space.WORK) 1f else -1f)
                val dy = sin(a) * 0.56f * rf
                nodes += BrainNode(m.id, cx + dx, -0.08f + dy, m.kind, space, null, m.pinned, m.text)
            }
        }

        // Task clusters below the hemispheres.
        val threads = kept.filter { it.scope.isThread }.groupBy { it.scope.threadId!! }
            .entries.sortedByDescending { e -> e.value.maxOf { it.updatedAt } }.take(9)
        val hubs = ArrayList<BrainHub>()
        threads.forEachIndexed { i, (tid, notes) ->
            val span = threads.size
            val hx = if (span == 1) 0f else -0.66f + 1.32f * i / (span - 1)
            val hy = 0.78f + 0.06f * (if (i % 2 == 0) 0f else 1f)
            hubs += BrainHub(tid, threadTitles[tid] ?: "a finished task", hx, hy)
            notes.sortedByDescending { importance(it) }.forEachIndexed { k, m ->
                // Around the hub, never on it: the first notes start on an inner ring.
                val rf = 0.45f + 0.55f * sqrt((k + 1f) / (notes.size + 1f))
                val a = k * GOLDEN - 0.6f
                nodes += BrainNode(m.id, hx + cos(a) * 0.1f * rf, hy + sin(a) * 0.1f * rf, m.kind, m.scope.space, tid, m.pinned, m.text)
            }
        }

        val index = nodes.withIndex().associate { it.value.id to it.index }
        val keptById = kept.associateBy { it.id }
        val edges = ArrayList<BrainEdge>()
        val seen = HashSet<Long>()
        for ((i, n) in nodes.withIndex()) {
            if (n.kind == MemoryKind.SUMMARY || n.kind == MemoryKind.TASK_STATE) continue
            val item = keptById[n.id] ?: continue
            for ((otherId, w) in related(item)) {
                val j = index[otherId] ?: continue
                val key = if (i < j) i.toLong() * 100_000 + j else j.toLong() * 100_000 + i
                if (i != j && seen.add(key)) edges += BrainEdge(minOf(i, j), maxOf(i, j), w.toFloat().coerceIn(0.05f, 1f))
            }
        }
        return BrainGraph(nodes, edges, hubs, (items.size - kept.size).coerceAtLeast(0))
    }
}

/** Something that happened in memory, as the brain view shows it. */
data class MemoryActivity(val kind: Kind, val ids: List<String>, val by: String?, val ts: Long) {
    enum class Kind { RECALL, WRITE, FORGET }
}
