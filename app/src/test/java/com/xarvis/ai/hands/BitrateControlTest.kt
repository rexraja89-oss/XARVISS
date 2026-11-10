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
        assertEquals(3_000_000, second(c, 0, waitShare = 0.5))
        assertEquals(2_250_000, second(c, second, waitShare = 0.5))
    }

    @Test fun climbsBackSlowlyWhenThereIsRoom() {
        val c = BitrateControl(max = 4_000_000, start = 1_000_000).also { it.sent(0, 0) }
        assertNull(second(c, 0, 0.0))
        assertNull(second(c, second, 0.0))
        assertEquals(1_200_000, second(c, 2 * second, 0.0)) // three easy seconds, then up 20%
    }

    @Test fun aLittleWaitingIsNormal() {
        // A big key picture now and then makes sending wait a bit: that isn't a full line.
        val c = BitrateControl(max = 4_000_000).also { it.sent(0, 0) }
        repeat(5) { assertNull(second(c, it * second, waitShare = 0.2)) }
    }

    @Test fun staysWithinItsLimits() {
        val c = BitrateControl(max = 1_000_000).also { it.sent(0, 0) }
        repeat(10) { second(c, it * second, 0.9) }
        assertEquals(BitrateControl.MIN_BPS, c.current)
        repeat(40) { second(c, (10 + it) * second, 0.0) }
        assertEquals(1_000_000, c.current)
        assertEquals(400_000, BitrateControl(max = 400_000).current) // a cap under the floor is still honoured
    }

    @Test fun startsLowerAwayFromHome() {
        assertEquals(4_000_000, BitrateControl.startFor("wifi", 4_000_000))
        assertEquals(2_000_000, BitrateControl.startFor("direct", 4_000_000))
        assertTrue(BitrateControl.startFor("relay", 500_000) <= 500_000)
    }
}
