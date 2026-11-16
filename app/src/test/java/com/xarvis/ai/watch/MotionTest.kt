package com.xarvis.ai.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MotionTest {
    private val w = 320
    private val h = 240
    private val rnd = Random(7)

    /** A room: a gradient wall with a dark "sofa", plus camera noise. [person] puts a bright figure at that x. */
    private fun frame(brightness: Float = 1f, person: Int? = null): ByteArray {
        val y = ByteArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            var v = 60 + c / 4 + r / 6
            if (r > 160 && c in 40..200) v = 35
            if (person != null && c in person until person + 40 && r in 40..230) v = 210
            v = (v * brightness + rnd.nextInt(-4, 5)).toInt().coerceIn(0, 255)
            y[r * w + c] = v.toByte()
        }
        return y
    }

    private fun Motion.warm() = repeat(Motion.WARMUP + 3) { assertNull(feed(frame(), w, h)) }

    @Test fun aCalmRoomSaysNothing() {
        val m = Motion(); m.warm()
        repeat(60) { assertNull(feed(m, frame())) }
    }

    @Test fun autoExposureDriftIsNotMotion() {
        val m = Motion(); m.warm()
        // The camera brightens the whole picture by 15% over a few frames.
        for (b in listOf(1.03f, 1.06f, 1.09f, 1.12f, 1.15f, 1.15f, 1.15f)) assertNull(m.feed(frame(b), w, h))
    }

    @Test fun aPersonWalkingInIsSeenOnTheRightSide() {
        val m = Motion(); m.warm()
        assertNull(m.feed(frame(person = 250), w, h)) // one frame isn't enough
        val e = m.feed(frame(person = 250), w, h)
        assertNotNull(e)
        assertEquals(Motion.Kind.MOTION, e!!.kind)
        assertEquals("right", e.where)
        // Still there: not reported again while the streak goes on.
        assertNull(m.feed(frame(person = 250), w, h))
    }

    @Test fun lightsSwitchingOn() {
        val m = Motion(); m.warm()
        m.feed(frame(1.8f), w, h)
        val e = m.feed(frame(1.8f), w, h)
        assertEquals(Motion.Kind.LIGHTS, e!!.kind)
    }

    @Test fun somethingLeftBehindBecomesPartOfTheRoom() {
        val m = Motion(); m.warm()
        var events = 0
        repeat(200) { if (m.feed(frame(person = 100), w, h) != null) events++ }
        assertEquals(1, events)
        repeat(20) { assertNull(m.feed(frame(person = 100), w, h)) }
    }

    private fun feed(m: Motion, y: ByteArray) = m.feed(y, w, h)
}
