package com.xarvis.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which messages wake which device tool. */
class ToolTopicsTest {

    @Test fun location() {
        listOf("where am I", "what's my location", "what is my address", "main kahan hoon", "mai kaha hu").forEach {
            assertTrue(it, LocationTool.isAbout(it))
        }
        listOf("what is my email address", "maine kaha tha", "tell me a joke").forEach {
            assertFalse(it, LocationTool.isAbout(it))
        }
    }

    @Test fun battery() {
        listOf("how much battery do I have", "is it charging", "phone charge kitna hai").forEach {
            assertTrue(it, BatteryTool.isAbout(it))
        }
        assertFalse(BatteryTool.isAbout("tell me a joke"))
    }

    @Test fun time() {
        listOf("what day is it", "kitne baje hain", "what's the date tomorrow", "aaj kya hai").forEach {
            assertTrue(it, TimeTool.isAbout(it))
        }
        listOf("tell me a joke", "sometimes I wonder").forEach { assertFalse(it, TimeTool.isAbout(it)) }
    }

    @Test fun bluetooth() {
        listOf("what's connected over bluetooth", "are my earbuds connected", "which headphones are paired").forEach {
            assertTrue(it, BluetoothTool.isAbout(it))
        }
        assertFalse(BluetoothTool.isAbout("tell me a joke"))
    }

    @Test fun contacts() {
        listOf("what's Ali's number", "Ahmed ka number", "show my contacts", "phone number for Sara").forEach {
            assertTrue(it, ContactsTool.isAbout(it))
        }
        listOf("what is the number of planets", "tell me a joke").forEach { assertFalse(it, ContactsTool.isAbout(it)) }
    }
}
