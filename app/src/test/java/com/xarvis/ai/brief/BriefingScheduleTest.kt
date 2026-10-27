package com.xarvis.ai.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BriefingScheduleTest {

    private fun at(year: Int, month1: Int, day: Int, hour: Int, min: Int): Long =
        Calendar.getInstance().apply { set(year, month1 - 1, day, hour, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun labelFormatsTwelveHourClock() {
        assertEquals("3:33 PM", BriefingSchedule.label(15, 33))
        assertEquals("12:00 AM", BriefingSchedule.label(0, 0))
        assertEquals("12:05 PM", BriefingSchedule.label(12, 5))
        assertEquals("9:00 AM", BriefingSchedule.label(9, 0))
        assertEquals("11:59 PM", BriefingSchedule.label(23, 59))
    }

    @Test fun nextTimeIsLaterToday() {
        val next = BriefingSchedule.nextTime(15, 33, at(2026, 1, 1, 10, 0))
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(33, c.get(Calendar.MINUTE))
        assertEquals(1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun nextTimeRollsToTomorrowOncePassed() {
        val next = BriefingSchedule.nextTime(15, 33, at(2026, 1, 1, 16, 0))
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun nextTimeIsAlwaysInTheFuture() {
        val from = at(2026, 6, 15, 15, 33) // exactly on the mark counts as passed
        assertTrue(BriefingSchedule.nextTime(15, 33, from) > from)
    }
}
