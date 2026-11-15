package com.xarvis.ai.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CallForwardTest {
    private fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
    private fun plan(sh: Int, eh: Int) = CallForward.Plan(true, "+971500000000", sh, 0, eh, 0, false)

    @Test fun overnightFrame() {
        val p = plan(21, 7)
        assertTrue(CallForward.inWindow(p, at(23, 30)))
        assertTrue(CallForward.inWindow(p, at(3, 0)))
        assertTrue(CallForward.inWindow(p, at(21, 0)))
        assertFalse(CallForward.inWindow(p, at(7, 0)))
        assertFalse(CallForward.inWindow(p, at(12, 0)))
    }

    @Test fun daytimeFrame() {
        val p = plan(9, 17)
        assertTrue(CallForward.inWindow(p, at(9, 0)))
        assertTrue(CallForward.inWindow(p, at(16, 59)))
        assertFalse(CallForward.inWindow(p, at(17, 0)))
        assertFalse(CallForward.inWindow(p, at(8, 59)))
    }
}
