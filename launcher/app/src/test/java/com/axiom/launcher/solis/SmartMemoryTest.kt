package com.axiom.launcher.solis

import com.axiom.launcher.solis.engine.InMemoryBrainStore
import com.axiom.launcher.solis.memory.MemoryKind
import com.axiom.launcher.solis.memory.MemoryService
import com.axiom.launcher.solis.memory.Scope
import com.axiom.launcher.solis.memory.Space
import com.axiom.launcher.solis.memory.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMemoryTest {
    private var now = 1_000_000_000_000L
    private val store = InMemoryBrainStore()
    private val m = MemoryService(store) { now }.also { it.load() }
    private val personal = setOf(Scope(Space.PERSONAL))

    @Test
    fun topicsLinkEverydayWords() {
        assertTrue(Topics.of("Allergic to peanuts").contains("diet"))
        assertTrue(Topics.of("My sister lives in Porto").contains("family"))
        assertEquals(listOf("diet"), Topics.expand("what can't I eat"))
        assertTrue("a query that already names the topic adds nothing", Topics.expand("my diet").isEmpty())
    }

    @Test
    fun findsMemoriesByMeaningNotJustWords() {
        m.remember("Allergic to peanuts", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        m.remember("Sister Ana lives in Porto", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        m.remember("Flies out of Lisbon on Fridays", MemoryKind.FACT, Scope(Space.PERSONAL), "you")
        val hits = m.search("what can't I eat at dinner?", personal)
        assertEquals("Allergic to peanuts", hits.first().item.text)
        assertTrue(m.search("family", personal).any { it.item.text.contains("Ana") })
        // Solis's own tags count too.
        m.remember("Takes the 8:10 commuter train", MemoryKind.FACT, Scope(Space.PERSONAL), "solis:t1", tags = listOf("Commute ", "TRANSIT"))
        val train = m.all().first { it.text.contains("8:10") }
        assertTrue(train.tags.toString(), train.tags.containsAll(listOf("commute", "transit", "transport")))
        assertTrue(m.search("transit", personal).any { it.item.id == train.id })
    }

    @Test
    fun tidyMergesDuplicatesClearsOldTaskNotesAndTags() {
        val a = m.remember("Prefers window seats on flights", MemoryKind.PREFERENCE, Scope(Space.PERSONAL), "you", confidence = 0.9)
        now += 1000
        // Same fact, saved as a different kind, so remember() didn't merge it.
        val b = m.remember("Prefers window seats on flights.", MemoryKind.FACT, Scope(Space.PERSONAL), "solis:t9", confidence = 0.6, pinned = true)
        m.remember("Prefers window seats on flights", MemoryKind.FACT, Scope(Space.WORK), "you") // other space: kept apart
        val stale = m.remember("Draft is in Downloads", MemoryKind.TASK_STATE, Scope(Space.PERSONAL, "gone"), "solis:gone")
        val live = m.remember("Halfway through the list", MemoryKind.TASK_STATE, Scope(Space.PERSONAL, "alive"), "solis:alive")
        // An older memory from before topic tags existed.
        store.upsertMemory(com.axiom.launcher.solis.memory.MemoryItem("old1", MemoryKind.NOTE, "Gym on Monday mornings", Scope(Space.PERSONAL), "import", now, now))
        m.load()
        now += 15 * 86_400_000L
        val r = m.tidy(liveThreads = setOf("alive"))
        assertEquals(1, r.merged)
        assertEquals(1, r.cleared)
        assertEquals(1, r.tagged)
        assertTrue(m.get("old1")!!.tags.contains("fitness"))
        assertTrue(r.summary(), r.summary().startsWith("Merged 1 duplicate"))
        assertEquals(null, m.get(stale.id))
        assertTrue("live tasks keep their notes", m.get(live.id) != null)
        val kept = m.all().filter { it.scope == Scope(Space.PERSONAL) && it.text.startsWith("Prefers window") }
        assertEquals(1, kept.size)
        assertEquals(b.id, kept.single().id)
        assertTrue("pinned survives the merge", kept.single().pinned)
        assertEquals(0.9, kept.single().confidence, 1e-9)
        assertEquals(2, m.all().count { it.text.startsWith("Prefers window") })
        assertFalse("nothing left to do", m.tidy(setOf("alive")).changed)
    }
}
