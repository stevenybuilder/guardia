package com.stevenyang.datadogproactive.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/**
 * Deterministic tests for [ScoreDial]. Exercises the tweening math without booting
 * an IntelliJ Application — uses [ScoreDial.tickForTest] to step the internal clock
 * instead of waiting for the real [javax.swing.Timer].
 */
class ScoreDialTest {

    @Test
    fun `dial starts at zero`() {
        val dial = ScoreDial()
        assertEquals(0, dial.displayedScore())
    }

    @Test
    fun `cubic-out easing passes midpoint before half the time`() {
        val dial = ScoreDial()
        dial.animateTo(100)
        // Sample midway through the 800ms tween; cubic-out (t=0.5) ≈ 0.875.
        dial.tickForTest(400)
        val mid = dial.displayedScore()
        assertTrue("Expected score past midpoint by t=0.5 (cubic-out); got $mid", mid >= 80)
    }

    @Test
    fun `dial reaches target after duration elapses`() {
        val dial = ScoreDial()
        dial.animateTo(85)
        dial.tickForTest(1000) // > 800ms duration
        assertEquals(85, dial.displayedScore())
    }

    @Test
    fun `reset snaps back to zero`() {
        val dial = ScoreDial()
        dial.animateTo(60)
        dial.tickForTest(1000)
        assertEquals(60, dial.displayedScore())
        dial.reset()
        assertEquals(0, dial.displayedScore())
    }

    @Test
    fun `color band blends green at low scores`() {
        val c = ScoreDial.blendBandColors(10.0)
        assertEquals(Color(0x2E7D32), c)
    }

    @Test
    fun `color band blends to red at high scores`() {
        val c = ScoreDial.blendBandColors(100.0)
        assertEquals(Color(0xC62828), c)
    }

    @Test
    fun `color band interpolates between amber and red mid-range`() {
        val c = ScoreDial.blendBandColors(90.0)
        // At score=90, t=0.5 between amber (239,138,0) and red (198,40,40) — strictly between endpoints.
        assertTrue("Red channel should lie between endpoints, got $c", c.red in 199..238)
        assertTrue("Green channel should lie between endpoints, got $c", c.green in 41..137)
    }
}
