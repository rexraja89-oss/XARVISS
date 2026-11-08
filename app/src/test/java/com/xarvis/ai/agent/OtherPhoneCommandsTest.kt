package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherPhoneCommandsTest {
    private val s22 = setOf("galaxy", "s22", "ultra")
    private fun parse(m: String) = OtherPhoneCommands.parse(m, s22)

    @Test fun opensAppsThere() {
        assertEquals(OtherPhoneCommands.Open("WhatsApp"), parse("open WhatsApp on my S22"))
        assertEquals(OtherPhoneCommands.Open("youtube"), parse("hey xarvis, open youtube on the galaxy s22 ultra"))
        assertEquals(OtherPhoneCommands.Open("whatsapp", "read my last message"), parse("open whatsapp on s22 and read my last message"))
        assertEquals(OtherPhoneCommands.Open("whatsapp", "tap Chats"), parse("open whatsapp on my s22 and then tap Chats"))
    }

    @Test fun tapsTypesAndReads() {
        assertEquals(OtherPhoneCommands.Tap("Chats"), parse("tap Chats on my S22"))
        assertEquals(OtherPhoneCommands.Type("hello there"), parse("type hello there on s22"))
        listOf("read my s22 screen", "what's on my S22 screen?", "what's on my s22", "check messages on my s22", "any new messages on my s22?")
            .forEach { assertTrue(it, parse(it) is OtherPhoneCommands.Read) }
    }

    @Test fun followUpsAfterOpening() {
        assertEquals(OtherPhoneCommands.Tap("Chats"), OtherPhoneCommands.followUp("tap Chats"))
        assertTrue(OtherPhoneCommands.followUp("read my last message") is OtherPhoneCommands.Read)
        assertTrue(OtherPhoneCommands.followUp("who messaged me") is OtherPhoneCommands.Read)
    }

    @Test fun otherSentencesDont() {
        listOf(
            "what's the battery on my s22", "call mom", "open whatsapp", "control my s22",
            "take photo with front camera and show me here in benco screen", "what time is it in s22 land",
        ).forEach { assertNull(it, parse(it)) }
        assertNull(OtherPhoneCommands.parse("open whatsapp on my s22", emptySet()))
    }
}
