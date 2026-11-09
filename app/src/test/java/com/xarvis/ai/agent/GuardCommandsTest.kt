package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardCommandsTest {
    @Test fun armsAndDisarms() {
        listOf("XARVIS, guard my phone", "guard my phone", "Hey Jarvis guard my mobile.", "guard mode on", "turn on guard mode", "protect my phone")
            .forEach { assertEquals(it, true, GuardCommands.parse(it)) }
        listOf("guard mode off", "stop guarding", "turn off guard mode", "XARVIS, disable guard")
            .forEach { assertEquals(it, false, GuardCommands.parse(it)) }
    }

    @Test fun otherSentencesDont() {
        listOf("how do I guard my phone from thieves", "guard", "what is guard mode", "my phone is guarded", "watch a movie")
            .forEach { assertNull(it, GuardCommands.parse(it)) }
    }

    @Test fun hearsTheDisarmPhrase() {
        listOf("it's alright Jarvis", "its all right jarvis", "it's alright Xarvis", "It is okay Jervis")
            .forEach { assertTrue(it, GuardCommands.isDisarmPhrase(it)) }
        listOf("you are not authorized to touch this device", "it's alright", "hello jarvis")
            .forEach { assertFalse(it, GuardCommands.isDisarmPhrase(it)) }
    }
}
