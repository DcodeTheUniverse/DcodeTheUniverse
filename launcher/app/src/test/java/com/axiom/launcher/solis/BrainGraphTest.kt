package com.axiom.launcher.solis

import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.memory.BrainLayout
import com.axiom.launcher.solis.memory.MemoryActivity
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.MemoryService
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** The brain view's layout and the memory events that drive its pulses. */
class BrainGraphTest {
    private fun service() = MemoryService(InMemoryBrainStore()).apply {
        remember("Lisbon trip: flights booked for May 3", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        remember("Lisbon trip: hotel near Alfama for May 3 to 6", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        remember("Prefers window seats on long flights", MemoryKind.PREFERENCE, Scope(Space.PERSONAL), "you", pinned = true)
        remember("Quarterly report due Friday", MemoryKind.FACT, Scope(Space.WORK), "you")
        remember("Quarterly report uses the new template", MemoryKind.DECISION, Scope(Space.WORK), "you")
        remember("Found three hotels near Alfama under 150 a night", MemoryKind.NOTE, Scope(Space.PERSONAL, "t1"), "solis:t1")
    }

    @Test
    fun hemispheresTaskClustersAndLinks() {
        val svc = service()
        val g = BrainLayout.build(svc.all(), mapOf("t1" to "Plan Lisbon"), { svc.related(it) })
        assertEquals(6, g.nodes.size)
        g.nodes.filter { it.threadId == null }.forEach { n ->
            if (n.space == Space.PERSONAL) assertTrue("personal on the left: ${n.text}", n.x < 0) else assertTrue("work on the right: ${n.text}", n.x > 0)
        }
        val note = g.nodes.single { it.threadId == "t1" }
        val hub = g.hub("t1")!!
        assertEquals("Plan Lisbon", hub.title)
        assertTrue("task notes sit below, near their hub", note.y > 0.6f && kotlin.math.hypot(note.x - hub.x, note.y - hub.y) < 0.1f)

        fun linked(a: String, b: String) = g.edges.any { e ->
            val x = g.nodes[e.a].text; val y = g.nodes[e.b].text
            (x.startsWith(a) && y.startsWith(b)) || (x.startsWith(b) && y.startsWith(a))
        }
        assertTrue("memories about the same trip are linked", linked("Lisbon trip: flights", "Lisbon trip: hotel"))
        assertTrue("work memories about the report are linked", linked("Quarterly report due", "Quarterly report uses"))
        assertTrue("unrelated memories are not", !linked("Quarterly report due", "Lisbon trip: flights"))
        assertTrue("no self links or duplicates", g.edges.none { it.a == it.b } && g.edges.map { it.a to it.b }.toSet().size == g.edges.size)

        val again = BrainLayout.build(svc.all(), mapOf("t1" to "Plan Lisbon"), { svc.related(it) })
        assertEquals("deterministic", g.nodes.map { it.id to (it.x to it.y) }, again.nodes.map { it.id to (it.x to it.y) })
    }

    @Test
    fun largeMemoriesAreCappedButPinnedStay() {
        val svc = MemoryService(InMemoryBrainStore())
        repeat(600) { i -> svc.remember("Note $i about topic ${i % 17} and item ${i * 7}", MemoryKind.NOTE, Scope(if (i % 3 == 0) Space.WORK else Space.PERSONAL), "you", pinned = i % 100 == 0) }
        val started = System.nanoTime()
        val g = BrainLayout.build(svc.all(), emptyMap(), { svc.related(it) }, maxNodes = 120)
        val ms = (System.nanoTime() - started) / 1e6
        assertEquals(120, g.nodes.size)
        assertEquals(480, g.hidden)
        assertEquals("every pinned memory is shown", 6, g.nodes.count { it.pinned })
        assertTrue("layout plus links take $ms ms", ms < 2000)
        g.nodes.forEach { n -> assertTrue(n.x in -1.05f..1.05f && n.y in -0.8f..1f) }
    }

    @Test
    fun pulsesComeFromRealRecallsAndWrites() {
        val svc = service()
        val events = Collections.synchronizedList(ArrayList<MemoryActivity>())
        svc.onActivity = { events += it }
        val scopes = setOf(Scope(Space.PERSONAL), Scope(Space.PERSONAL, "t1"))
        val hits = svc.search("Lisbon hotel", scopes, by = "t1")
        val recall = events.single()
        assertEquals(MemoryActivity.Kind.RECALL, recall.kind)
        assertEquals("t1", recall.by)
        assertEquals(hits.map { it.item.id }, recall.ids)

        svc.search("Lisbon hotel", scopes)
        assertEquals("a search with no task behind it doesn't pulse", 1, events.size)

        val m = svc.remember("Allergic to peanuts", MemoryKind.FACT, Scope(Space.PERSONAL), "solis:t2")
        assertEquals(MemoryActivity.Kind.WRITE, events.last().kind)
        assertEquals(listOf(m.id), events.last().ids)
        assertEquals("t2", events.last().by)
        svc.forget(m.id)
        assertEquals(MemoryActivity.Kind.FORGET, events.last().kind)
    }
}
