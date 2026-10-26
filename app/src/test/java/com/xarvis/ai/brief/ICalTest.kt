package com.xarvis.ai.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ICalTest {
    private fun todayStamp(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun parsesTodaysTimedEvent() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Standup\r\nDTSTART:${todayStamp()}T090000Z\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val ev = ICal.parse(ics)
        assertEquals(1, ev.size)
        assertEquals("Standup", ev[0].title)
        assertEquals(9 * 60, ev[0].minutesOfDay)
    }

    @Test fun skipsOtherDays() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Old\r\nDTSTART:19990101T090000Z\r\nEND:VEVENT"
        assertTrue(ICal.parse(ics).isEmpty())
    }

    @Test fun handlesAllDayWithTzidOrValueDate() {
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:${todayStamp()}\r\nEND:VEVENT"
        val ev = ICal.parse(ics)
        assertEquals(1, ev.size)
        assertEquals(-1, ev[0].minutesOfDay)
    }

    @Test fun formatsTimes() {
        assertEquals("9:05 am — Standup", ICal.format(ICal.Event(9 * 60 + 5, "Standup")))
        assertEquals("2:00 pm — Dentist", ICal.format(ICal.Event(14 * 60, "Dentist")))
        assertTrue(ICal.format(ICal.Event(-1, "Holiday")).contains("all day"))
    }
}
