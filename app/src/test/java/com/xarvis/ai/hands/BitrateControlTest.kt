package com.xarvis.ai.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateControlTest {
    private val second = 1_000_000_000L

    /** Feeds 30 frames over one second, each waiting [waitShare] of its 33 ms; returns any change. */
    private fun second(c: BitrateControl, start: Long, waitShare: Double): Int? {
        var change: Int? = null
        for (i in 1..30) {
            val r = c.sent((second / 30 * waitShare).toLong(), start + second * i / 30)
            if (r != null) change = r
        }
        return change
    }

    @Test fun dropsAtOnceWhenTheLineIsFull() {
        val c = BitrateControl(max = 4_000_000).also { it.sent(0, 0) }
        assertEquals(2_400_000, second(c, 0, waitShare = 0.5))
        assertEquals(1_440_000, second(c, second, waitShare = 0.5))
    }

    @Test fun climbsBackSlowlyWhenThereIsRoom() {
        val c = BitrateControl(max = 4_000_000, start = 1_000_000).also { it.sent(0, 0) }
        assertNull(second(c, 0, 0.0))
        assertNull(second(c, second, 0.0))
        assertEquals(1_200_000, second(c, 2 * second, 0.0)) // three easy seconds, then up 20%
    }

    @Test fun staysWithinItsLimits() {
        val c = BitrateControl(max = 500_000).also { it.sent(0, 0) }
        repeat(10) { second(c, it * second, 0.9) }
        assertEquals(BitrateControl.MIN_BPS, c.current)
        repeat(40) { second(c, (10 + it) * second, 0.0) }
        assertEquals(500_000, c.current)
    }

    @Test fun startsLowerAwayFromHome() {
        assertEquals(4_000_000, BitrateControl.startFor("wifi", 4_000_000))
        assertEquals(2_000_000, BitrateControl.startFor("direct", 4_000_000))
        assertTrue(BitrateControl.startFor("relay", 500_000) <= 500_000)
    }
}
