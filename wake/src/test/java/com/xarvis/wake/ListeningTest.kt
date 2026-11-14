package com.xarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTest {
    /** Feeds [chunks] of the same [level]; returns whether the gate ended open. */
    private fun SoundGate.hold(level: Float, chunks: Int): Boolean {
        var open = true
        repeat(chunks) { open = feed(level + (it % 3)) } // a little flutter, like a real microphone
        return open
    }

    @Test fun aQuietRoomRestsTheModels() {
        val gate = SoundGate()
        assertTrue("starts awake", gate.open)
        assertTrue("still awake after a second", gate.hold(12f, 12))
        assertFalse("resting after two seconds of quiet", gate.hold(12f, 14))
    }

    @Test fun aVoiceWakesItAtOnceAndItStaysAwakeAWhile() {
        val gate = SoundGate()
        gate.hold(12f, 40)
        assertFalse(gate.open)
        assertTrue("a far, quiet voice is enough", gate.feed(70f))
        assertTrue("awake a second and a half after the word", gate.hold(12f, 18))
        assertFalse(gate.hold(12f, 6))
    }

    @Test fun steadyNoiseBecomesTheNewQuiet() {
        val gate = SoundGate()
        gate.hold(15f, 40)
        // A fan starts: loud at first, then it's just the room.
        assertTrue(gate.hold(400f, 100))
        assertFalse("after half a minute the fan no longer keeps the models running", gate.hold(400f, 400))
        // A voice over the fan still wakes it.
        assertTrue(gate.feed(750f))
        // And when the fan stops, quiet is quiet again straight away.
        gate.hold(15f, 30)
        assertFalse(gate.open)
        assertTrue(gate.feed(70f))
    }

    @Test fun talkOrMusicKeepsItAwake() {
        val gate = SoundGate()
        var open = true
        repeat(600) { open = gate.feed(if (it % 7 < 3) 900f else 150f) }
        assertTrue(open)
    }

    @Test fun loudnessIsTheRootMeanSquare() {
        assertEquals(0f, SoundGate.level(ShortArray(1280)), 0f)
        assertEquals(1000f, SoundGate.level(ShortArray(1280) { if (it % 2 == 0) 1000 else -1000 }), 0.01f)
    }

    @Test fun oneLuckyStepIsNotAWakeWord() {
        val rule = HitRule()
        assertFalse(rule.feed(0.1f, 0.5f))
        assertFalse("one step over the line", rule.feed(0.9f, 0.5f))
        assertFalse(rule.feed(0.2f, 0.5f))
        assertFalse(rule.feed(0.1f, 0.5f))
        assertFalse(rule.feed(0.6f, 0.5f))
        assertTrue("two of the last three", rule.feed(0.7f, 0.5f))
        rule.reset()
        assertFalse(rule.feed(0.7f, 0.5f))
        assertFalse(rule.feed(0.3f, 0.5f))
        assertTrue("with a step between", rule.feed(0.7f, 0.5f))
    }

    @Test fun sensitivityMovesTheLine() {
        assertTrue(Sensitivity.HIGH.threshold < Sensitivity.NORMAL.threshold)
        assertTrue(Sensitivity.NORMAL.threshold < Sensitivity.LOW.threshold)
        assertEquals(Sensitivity.NORMAL, Sensitivity.of(99))
        assertEquals(Sensitivity.HIGH, Sensitivity.of(Sensitivity.HIGH.ordinal))
    }
}
