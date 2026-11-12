package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCommandsTest {
    private fun p(s: String) = UpdateCommands.parse(s)

    @Test fun thisPhone() {
        val here = UpdateCommands.Cmd(null)
        assertEquals(here, p("update everything"))
        assertEquals(here, p("Update XARVIS"))
        assertEquals(here, p("update all apps"))
        assertEquals(here, p("update all the xarvis apps now"))
        assertEquals(here, p("please update my phone."))
        assertEquals(here, p("Jarvis, upgrade everything"))
    }

    @Test fun theOtherPhone() {
        assertEquals(UpdateCommands.Cmd("S22", phoneOnly = true), p("update my S22"))
        assertEquals(UpdateCommands.Cmd("s22 ultra", phoneOnly = true), p("update the s22 ultra please"))
        assertEquals(UpdateCommands.Cmd("S22"), p("update everything on my S22"))
        assertEquals(UpdateCommands.Cmd("benco"), p("update xarvis on the benco"))
    }

    @Test fun bothPhones() {
        val both = UpdateCommands.Cmd(null, both = true)
        assertEquals(both, p("update both phones"))
        assertEquals(both, p("update all my phones"))
        assertEquals(both, p("update everything on both phones"))
    }

    @Test fun otherSentencesAreLeftAlone() {
        // A bare name is only a command if it names one of Rex's phones (XarvisAgent checks).
        assertEquals(UpdateCommands.Cmd("CV", phoneOnly = true), p("update my CV"))
        assertNull(p("update me on the news from India"))
        assertNull(p("can you update the spreadsheet with the new prices for this month"))
        assertNull(p("what's new in the update"))
        assertNull(p("update"))
        assertNull(p("I updated everything yesterday"))
    }
}
