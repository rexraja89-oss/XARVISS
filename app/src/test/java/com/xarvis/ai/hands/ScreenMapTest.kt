package com.xarvis.ai.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMapTest {
    private fun near(expect: Float, got: Float) = assertEquals(expect.toDouble(), got.toDouble(), 3.0)

    @Test fun stillPicturesMapStraight() {
        val (x, y) = ScreenMap.toScreen(0.5, 0.25, 1440, 3088, 0, 0)!!
        near(719.5f, x); near(771.75f, y)
    }

    @Test fun portraitVideoOfAPortraitScreen() {
        // The S22 in portrait, mirrored into a 1440×3088 video (same shape): straight through.
        val (x, y) = ScreenMap.toScreen(0.5, 0.5, 1440, 3088, 1440, 3088)!!
        near(720f, x); near(1544f, y)
    }

    @Test fun phoneTurnedToLandscapeInsideAPortraitVideo() {
        // Started in portrait (video 672×1440), then a game turned the screen to 3088×1440: it sits in the middle
        // of the video, 672 wide and 313 tall, with black bars above and below.
        val (cx, cy) = ScreenMap.toScreen(0.5, 0.5, 3088, 1440, 672, 1440)!!
        near(1544f, cx); near(720f, cy)
        // The game's top-left corner is at the top of the picture strip, not the top of the video.
        val top = (1440 - 1440 * (672.0 / 3088)) / 2 / 1440 // where the strip starts, as 0–1 of the video
        val (lx, ly) = ScreenMap.toScreen(0.0, top, 3088, 1440, 672, 1440)!!
        near(0f, lx); near(0f, ly)
        // A tap on the black bar above it does nothing.
        assertNull(ScreenMap.toScreen(0.5, 0.1, 3088, 1440, 672, 1440))
        // But a finger sliding off the picture still lifts (clamped to the edge).
        val (_, uy) = ScreenMap.toScreen(0.5, 0.1, 3088, 1440, 672, 1440, clamp = true)!!
        near(0f, uy)
    }

    @Test fun landscapeVideoOfAPortraitScreen() {
        // Started in a landscape game (video 1440×672), then back to portrait: pillarboxed, bars left and right.
        val (x, y) = ScreenMap.toScreen(0.5, 0.5, 1440, 3088, 1440, 672)!!
        near(720f, x); near(1544f, y)
        assertNull(ScreenMap.toScreen(0.05, 0.5, 1440, 3088, 1440, 672))
    }
}
