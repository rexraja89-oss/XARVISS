package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RemindCommandsTest {

    // A fixed "now": 15 Jun 2026, 10:00 local time.
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JUNE, 15, 10, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun clock(hour: Int, minute: Int, addDays: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, addDays)
    }.timeInMillis

    @Test fun durationToTaskAtEnd() {
        val s = RemindCommands.parse("remind me to drink water in 20 minutes", now) as Step.Remind
        assertEquals(now + 20 * 60_000L, s.atMillis)
        assertEquals("drink water", s.text)
        assertEquals("in 20 minutes", s.whenLabel)
    }

    @Test fun durationThenTask() {
        val s = RemindCommands.parse("remind me in 2 hours to call the office", now) as Step.Remind
        assertEquals(now + 2 * 3600_000L, s.atMillis)
        assertEquals("call the office", s.text)
        assertEquals("in 2 hours", s.whenLabel)
    }

    @Test fun clockTimeTaskAtEnd() {
        val s = RemindCommands.parse("remind me to call Atiq at 6 pm", now) as Step.Remind
        assertEquals(clock(18, 0), s.atMillis) // 6pm today (still ahead of 10am)
        assertEquals("call Atiq", s.text)
        assertEquals("at 6:00 PM", s.whenLabel)
    }

    @Test fun clockTimeThenTask() {
        val s = RemindCommands.parse("remind me at 7:30 am to take medicine", now) as Step.Remind
        // 7:30am already passed today, so tomorrow.
        assertEquals(clock(7, 30, addDays = 1), s.atMillis)
        assertEquals("take medicine", s.text)
        assertEquals("tomorrow at 7:30 AM", s.whenLabel)
    }

    @Test fun tomorrowAt() {
        val s = RemindCommands.parse("remind me to call mom tomorrow at 9 am", now) as Step.Remind
        assertEquals(clock(9, 0, addDays = 1), s.atMillis)
        assertEquals("call mom", s.text)
        assertEquals("tomorrow at 9:00 AM", s.whenLabel)
    }

    @Test fun politePrefixStillWorks() {
        val s = RemindCommands.parse("can you remind me to lock the door in 5 minutes", now) as Step.Remind
        assertEquals(now + 5 * 60_000L, s.atMillis)
        assertEquals("lock the door", s.text)
    }

    @Test fun listAndCancel() {
        assertTrue(RemindCommands.parse("my reminders", now) is Step.ListReminders)
        assertTrue(RemindCommands.parse("show reminders", now) is Step.ListReminders)
        assertEquals("", (RemindCommands.parse("cancel all reminders", now) as Step.CancelReminders).query)
        assertEquals("water", (RemindCommands.parse("cancel reminder water", now) as Step.CancelReminders).query)
    }

    @Test fun noTimeAsksWhen() {
        assertTrue(RemindCommands.parse("remind me to call Atiq", now) is Step.Respond)
    }

    @Test fun notAReminderIsNull() {
        assertNull(RemindCommands.parse("what's the weather today", now))
        assertNull(RemindCommands.parse("call Atiq", now))
    }
}
