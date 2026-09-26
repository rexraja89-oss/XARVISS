package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies that ignore the phone data they were given are replaced by the data. */
class GroundingTest {

    private val location = listOf("Current location: 12 Mall Road, Lahore 54000, Pakistan (31.52037, 74.35875)")
    private val contact = listOf("On benco V91s Plus: Contacts matching the message: Atiq Qc: 0300 1234567 (mobile)")

    @Test fun refusalsAreCaught() {
        // Rex's real replies from Gemma, with the data right there in its prompt.
        assertTrue(XarvisAgent.ignoresData("I do not have access to your current location.", location))
        assertTrue(XarvisAgent.ignoresData("I am a large language model, so I do not have a physical location.", location))
        assertTrue(XarvisAgent.ignoresData("I need to open the contacts app first.", contact))
    }

    @Test fun repliesThatUseTheDataStay() {
        assertFalse(XarvisAgent.ignoresData("You're at 12 Mall Road, Lahore.", location))
        assertFalse(XarvisAgent.ignoresData("Atiq Qc's number is 0300 1234567.", contact))
        assertFalse(XarvisAgent.ignoresData("Atiq Qc's number is 03001234567.", contact))
    }

    @Test fun repliesThatMentionNoneOfTheNumbersAreReplaced() {
        assertTrue(XarvisAgent.ignoresData("Atiq is one of your contacts.", contact))
        assertTrue(XarvisAgent.ignoresData("", location))
    }

    @Test fun dataAnswerReadsCleanly() {
        assertEquals(
            "On benco V91s Plus: Contacts found: Atiq Qc: 0300 1234567 (mobile)",
            XarvisAgent.dataAnswer(contact),
        )
    }
}
