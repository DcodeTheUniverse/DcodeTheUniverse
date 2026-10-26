package com.axiom.launcher.ui

import com.axiom.launcher.solis.engine.Mood
import com.axiom.launcher.ui.solis.DOT_R
import com.axiom.launcher.ui.solis.FACE_HALF_H
import com.axiom.launcher.ui.solis.FACE_HALF_W
import com.axiom.launcher.ui.solis.Pose
import com.axiom.launcher.ui.solis.faceFrame
import com.axiom.launcher.ui.solis.facePose
import com.axiom.launcher.ui.solis.projectFace
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

/** The 3D head: still poses per state, and the head never leaves its canvas while it moves. */
class SolisFace3dTest {
    @Test
    fun stillPosesAreFixedAndMeaningful() {
        Mood.entries.forEach { m -> assertEquals("$m pose is static without motion", facePose(m, 0, false), facePose(m, 54_321, false)) }
        val think = facePose(Mood.THINKING, 0, false)
        assertTrue("thinking looks up and aside", think.yaw > 0 && think.pitch > 0)
        assertTrue("paused rests its head down", facePose(Mood.PAUSED, 0, false).pitch < 0)
        assertTrue("listening leans in", facePose(Mood.LISTENING, 0, false).lean > 1f)
        assertTrue("idle mostly holds still between glances", facePose(Mood.IDLE, 3_000, true) == facePose(Mood.IDLE, 4_000, true))
        assertNotEquals("idle glances now and then", facePose(Mood.IDLE, 3_000, true), facePose(Mood.IDLE, 9_100 + 700, true))
    }

    @Test
    fun headStaysInsideItsCanvas() {
        var worstX = 0f
        var worstY = 0f
        var where = ""
        for (m in Mood.entries) for (animate in listOf(true, false)) {
            var t = 0L
            while (t < 30_000) {
                val levels = faceFrame(m, t, animate)
                val pr = projectFace(levels, facePose(m, t, animate))
                for (i in levels.indices) {
                    val r = DOT_R * pr.scale[i] * (if (levels[i] > 0.02f) 1.1f else 0.8f)
                    val x = kotlin.math.abs(pr.x[i]) + r
                    val y = kotlin.math.abs(pr.y[i]) + r
                    if (x > worstX) { worstX = x; where = "$m t=$t dot $i" }
                    if (y > worstY) worstY = y
                }
                t += 97
            }
        }
        assertTrue("widest $worstX at $where vs ${FACE_HALF_W}", worstX <= FACE_HALF_W)
        assertTrue("tallest $worstY vs ${FACE_HALF_H}", worstY <= FACE_HALF_H)
        assertTrue("the head fills its canvas (no big empty margin): $worstX", worstX >= FACE_HALF_W * 0.85f)
    }

    @Test
    fun surfaceIsCurvedAndLitFromTheUpperLeft() {
        val levels = FloatArray(99)
        val pr = projectFace(levels, Pose(0f, 0f))
        val center = 4 * 11 + 5
        assertTrue("the middle of the face is nearest", (0 until 99).all { pr.z[it] <= pr.z[center] + 1e-6f })
        assertTrue("dots shrink toward the edges", pr.scale[4 * 11] < pr.scale[center])
        assertTrue("upper-left catches more light than lower-right", pr.light[0] > pr.light[98])
        val lit = FloatArray(99).also { it[center] = 1f }
        assertTrue("lit dots stand out of the surface", projectFace(lit, Pose(0f, 0f)).z[center] > pr.z[center])
    }
}
