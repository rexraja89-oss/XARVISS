package com.xarvis.ai.sentinel

import com.xarvis.ai.sentinel.Gatekeeper.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatekeeperTest {
    @Test
    fun whoIsThere() {
        assertEquals(Seen.NOBODY, Gatekeeper.verdict(listOf(emptyList(), emptyList())))
        assertEquals(Seen.OWNER, Gatekeeper.verdict(listOf(listOf(0.1f), listOf(0.55f))))     // Rex in one picture
        assertEquals(Seen.OWNER, Gatekeeper.verdict(listOf(listOf(0.12f, 0.6f))))             // Rex with a friend
        assertEquals(Seen.UNSURE, Gatekeeper.verdict(listOf(listOf(0.36f))))
        assertEquals(Seen.STRANGER, Gatekeeper.verdict(listOf(listOf(0.15f), emptyList())))
    }

    @Test
    fun whenToLook() {
        val now = 10_000_000L
        assertTrue(Gatekeeper.needsCheck(now, 0L, false, 0L))                                 // first open
        assertTrue(Gatekeeper.needsCheck(now, now - 1000, true, 0L))                          // the phone locked since
        assertFalse(Gatekeeper.needsCheck(now, now - 60_000, false, 120_000))                // trusted a minute ago
        assertFalse(Gatekeeper.needsCheck(now, now - 20 * 60_000L, false, 5_000))            // just switched apps
        assertTrue(Gatekeeper.needsCheck(now, now - 20 * 60_000L, false, 60_000))
    }

    @Test
    fun codesAreHeardHoweverTheyreSaid() {
        val r = java.util.Random(3)
        repeat(50) { assertTrue(Gatekeeper.newCode(r).matches(Regex("\\d{4}"))) }
        assertTrue(Gatekeeper.matches("4821", "4821"))
        assertTrue(Gatekeeper.matches("it's 4 8 2 1", "4821"))
        assertTrue(Gatekeeper.matches("four eight two one", "4821"))
        assertTrue(Gatekeeper.matches("double five one two", "5512"))
        assertTrue(Gatekeeper.matches("oh seven seven nine", "0779"))
        assertTrue(Gatekeeper.matches("char aath do ek", "4821"))
        assertFalse(Gatekeeper.matches("four eight two", "4821"))
        assertFalse(Gatekeeper.matches("hello", "4821"))
        assertEquals("1112", Gatekeeper.digitsIn("triple 1 2"))
    }

    @Test
    fun itSpeaksLikeXarvis() {
        assertTrue(Gatekeeper.greeting(8, 0).startsWith("Good morning"))
        assertTrue((0 until 7).map { Gatekeeper.greeting(14, it) }.toSet().size == 7)
        assertTrue(Gatekeeper.opening(Seen.STRANGER).startsWith("Hello, stranger. Are you authorised"))
        assertTrue(Gatekeeper.opening(Seen.UNSURE).startsWith("I can't quite make you out."))
        assertTrue(Gatekeeper.ask("benco V91s Plus").contains("benco V91s Plus"))
        assertTrue(Gatekeeper.ask(null).contains("fingerprint"))
    }
}
