package com.xarvis.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelTimelineTest {
    @Test
    fun storyOrder() {
        assertEquals(0f, SentinelTimeline.pose(0f), 0f)               // head lowered at the start
        assertEquals(47f, SentinelTimeline.pose(10f), 0f)             // facing you at the end, and it stays
        assertTrue(SentinelTimeline.pose(3.5f) in 10f..40f)
        assertEquals(0f, SentinelTimeline.eye(3f, thinking = false, typing = false), 0f)
        assertTrue(SentinelTimeline.eye(9f, thinking = false, typing = false) > 0.8f)
        assertTrue(SentinelTimeline.fade(0f) == 0f && SentinelTimeline.fade(2f) == 1f)
        assertEquals(2.2f, SentinelTimeline.rainSpeed(true), 0f)
    }

    @Test
    fun lightningStrikesAtTheOpeningAndLater() {
        assertEquals(0f, SentinelTimeline.flash(0.5f), 0f)
        assertTrue(SentinelTimeline.flash(0.86f) > 0.8f)              // the opening strike
        assertTrue((1..600).map { SentinelTimeline.flash(10f + it * 0.05f) }.max() > 0.1f) // another within 10–40 s
        assertTrue(SentinelTimeline.strikes(500f).size <= 3)          // only strikes still glowing are kept
    }

    @Test
    fun boxFitsTheScreen() {
        val b = SentinelTimeline.box(1080f, 1700f)
        assertTrue(b[2] > 1000f && b[1] > 0f && b[1] + b[3] < 1700f)
    }
}
