package com.xarvis.ai.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPacerTest {
    @Test fun anOlderViewerThatNeverAnswersIsNeverHeldUp() {
        val p = VideoPacer(maxFrames = 3)
        repeat(50) { i -> assertTrue(p.shouldSend(false, i * 10L)); p.sent(i * 10L) }
    }

    @Test fun aSlowLineSkipsFramesThenRestartsFromAFullPicture() {
        val p = VideoPacer(maxFrames = 3, maxLagMs = 10_000)
        p.sent(0); p.shown() // the viewer answers: pacing is on
        // Three frames go out and nobody answers yet: the fourth is held back.
        repeat(3) { assertTrue(p.shouldSend(false, 10)); p.sent(10) }
        assertFalse(p.shouldSend(false, 20))
        assertFalse(p.needKey()) // still 3 on the way
        // The viewer catches up.
        p.shown(); p.shown()
        assertFalse(p.shouldSend(false, 30)) // an in-between frame never goes after a skip
        assertTrue(p.needKey()) // ask the encoder for a full picture, once
        assertFalse(p.needKey())
        assertTrue(p.shouldSend(true, 40)) // the key frame goes, and normal sending resumes
        p.sent(40)
        assertTrue(p.shouldSend(false, 50))
        assertEquals(2, p.dropped)
    }

    @Test fun anOldUnansweredFrameAlsoCountsAsBehind() {
        val p = VideoPacer(maxFrames = 100, maxLagMs = 1_000)
        p.sent(0); p.shown()
        p.sent(100)
        assertTrue(p.shouldSend(false, 900))
        assertFalse(p.shouldSend(false, 1_200)) // the frame sent at 100 is 1.1 s old
    }

    @Test fun lostFramesDoNotStallForever() {
        val p = VideoPacer(maxFrames = 2, maxLagMs = 100_000, staleMs = 6_000)
        p.sent(0); p.shown()
        p.sent(10); p.sent(10)
        assertFalse(p.shouldSend(false, 20))
        // Their answers never came: after 6 s they count as lost and a key frame restarts the view.
        assertTrue(p.shouldSend(true, 7_000))
    }
}
