package com.axiom.launcher.ui

import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.ui.solis.faceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The face must tell states apart without motion, and only move when motion is allowed. */
class SolisFaceTest {
    private fun still(m: Mood, t: Long = 0) = faceFrame(m, t, animate = false).toList()

    @Test
    fun everyStateHasItsOwnStillExpression() {
        val frames = Mood.entries.map { still(it) }
        assertEquals("all ${Mood.entries.size} states distinct without animation", Mood.entries.size, frames.toSet().size)
        frames.forEach { f -> assertTrue("every state lights some dots", f.any { it > 0f }) }
    }

    @Test
    fun reducedMotionFramesNeverChange() {
        Mood.entries.forEach { m -> assertEquals("$m is static", still(m, 0), still(m, 12_345)) }
    }

    @Test
    fun idleBlinksAndActiveStatesMoveOnlyWhenAnimating() {
        val open = faceFrame(Mood.IDLE, 1_000, animate = true).toList()
        val blink = faceFrame(Mood.IDLE, 5_200 + 20, animate = true).toList()
        assertNotEquals("blink closes the eyes", open, blink)
        assertNotEquals(faceFrame(Mood.WORKING, 0, true).toList(), faceFrame(Mood.WORKING, 350, true).toList())
        assertNotEquals(faceFrame(Mood.THINKING, 0, true).toList(), faceFrame(Mood.THINKING, 400, true).toList())
        assertEquals("paused never moves", faceFrame(Mood.PAUSED, 0, true).toList(), faceFrame(Mood.PAUSED, 9_999, true).toList())
    }
}
