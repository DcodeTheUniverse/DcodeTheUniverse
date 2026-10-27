package com.axiom.launcher.solis

import com.axiom.launcher.data.CalEvent
import com.axiom.launcher.solis.cortex.DeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveTest {
    private val now = 1_800_000_000_000L
    private fun input(
        events: List<CalEvent> = emptyList(), device: DeviceSnapshot = DeviceSnapshot(batteryPct = 80), hour: Int = 14,
        headphones: Boolean = false, storage: Int? = 40, aiReady: Boolean = true, briefToday: Boolean = false,
    ) = ProactiveInput(now, hour, "20270115", device, events, headphones, musicActive = false, storageFreePct = storage, aiReady = aiReady, briefToday = briefToday)

    private fun at(min: Long) = now + min * 60_000

    @Test
    fun leaveAndJoinCardsFollowTheCalendar() {
        val cards = ProactiveRules.cards(input(listOf(
            CalEvent("Dentist", at(40), at(80), false, "Rua Augusta 10, Lisbon"),
            CalEvent("Standup", at(4), at(19), false, "https://meet.google.com/abc-defg-hij"),
            CalEvent("Holiday", at(-600), at(600), true, "Spain"),
        )), emptySet(), max = 5)
        val join = cards.first { it.id.startsWith("join:") }
        assertEquals(CardAction.OpenUrl("https://meet.google.com/abc-defg-hij"), join.action)
        assertTrue(join.detail, join.detail.contains("4 min"))
        val leave = cards.first { it.id.startsWith("leave:") }
        assertEquals(CardAction.Navigate("Rua Augusta 10, Lisbon"), leave.action)
        assertTrue("all-day events don't nag", cards.none { it.title.contains("Holiday") })
    }

    @Test
    fun phoneStateCardsAndLimits() {
        val cards = ProactiveRules.cards(input(device = DeviceSnapshot(batteryPct = 15, charging = false), headphones = true, storage = 5, hour = 8),
            emptySet(), max = 5)
        assertEquals(listOf("battery", "headphones", "storage", "brief"), cards.map { it.id.substringBefore(':') })
        assertEquals(CardAction.Ask(com.axiom.launcher.BRIEF_PROMPT), cards.last().action)
        assertEquals("two at a time by default", 2, ProactiveRules.cards(input(device = DeviceSnapshot(batteryPct = 15), headphones = true, storage = 5), emptySet()).size)
        val noAi = ProactiveRules.cards(input(storage = 5, hour = 8, aiReady = false), emptySet(), max = 5)
        assertEquals(CardAction.Settings("storage"), noAi.single().action)
        assertTrue("charging or saver: no battery card", ProactiveRules.cards(input(device = DeviceSnapshot(batteryPct = 10, charging = true)), emptySet()).isEmpty())
    }

    @Test
    fun dismissedCardsStayAwayForTheDay() {
        val i = input(device = DeviceSnapshot(batteryPct = 12))
        val first = ProactiveRules.cards(i, emptySet()).single()
        assertTrue(ProactiveRules.cards(i, setOf(first.id)).isEmpty())
        assertEquals("a new day brings it back", 1, ProactiveRules.cards(i.copy(day = "20270116"), setOf(first.id)).size)
        assertTrue("brief only once a day", ProactiveRules.cards(input(hour = 7, briefToday = true), emptySet()).isEmpty())
    }
}
