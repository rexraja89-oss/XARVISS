package com.xarvis.ai.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test fun aBadLineIsTriedAgain() {
        // What Rex saw on the benco (11 Nov 2026), and the other ways a line drops.
        listOf(
            "the direct line went quiet",
            "Software caused connection abort",
            "connection closed",
            "Galaxy S22 Ultra is offline.",
            "Read timed out",
            "its screen stopped",
            "I couldn't capture its screen just now (is its screen on?). Try again.",
        ).forEach { assertFalse(it, Reconnect.isRefusal(it)) }
    }

    @Test fun aNoIsRespected() {
        listOf(
            "STOP was pressed on it. Try again in a minute.",
            "They pressed STOP on their phone.",
            "remote control is switched off on it. On that phone: ☰ → XARVIS Hands → tick …",
            "XARVIS Hands is switched off on it (☰ → XARVIS Hands → …).",
            "the XARVIS Hands app on it is off or missing: open XARVIS Hands there …",
            "they haven't allowed it yet. XARVIS Helper is asking them now …",
            "watching its screen through the relay is paused until next month: 400 MB …",
            "this device isn't linked",
        ).forEach { assertTrue(it, Reconnect.isRefusal(it)) }
    }

    @Test fun quickAtFirstThenEveryTenSeconds() {
        assertEquals(1_000L, Reconnect.waitMs(1))
        assertEquals(2_000L, Reconnect.waitMs(2))
        assertEquals(10_000L, Reconnect.waitMs(9))
        assertEquals("", Reconnect.hint(1))
        assertTrue(Reconnect.hint(3).contains("call"))
        assertTrue(Reconnect.hint(7).contains("Always connected"))
    }
}
