package com.xarvis.ai.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureWindowTest {
    @Test fun oldViewersThatNeverAnswerAreNotHeldUp() {
        val w = PictureWindow()
        repeat(10) { w.sent(it * 300L) }
        assertTrue(w.room(3_000))
    }

    @Test fun atMostTwoPicturesOnTheWay() {
        val w = PictureWindow(max = 2)
        w.sent(0); w.shown() // the viewer answers: from now on it counts
        w.sent(100); w.sent(200)
        assertFalse(w.room(300))
        w.shown()
        assertTrue(w.room(400))
    }

    @Test fun aLostAnswerNeverStallsTheView() {
        val w = PictureWindow(max = 2, staleMs = 6_000)
        w.sent(0); w.shown()
        w.sent(1_000); w.sent(1_100)
        assertFalse(w.room(5_000))
        assertTrue(w.room(7_200))
    }
}
