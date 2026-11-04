package com.xarvis.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRainTest {

    @Test fun faceAppearsAfter15sFor3s() {
        assertEquals(-1f, CodeRainTiming.faceProgress(0f), 0f)
        assertEquals(-1f, CodeRainTiming.faceProgress(14.9f), 0f)
        assertEquals(0f, CodeRainTiming.faceProgress(15f), 0.001f)
        assertEquals(0.5f, CodeRainTiming.faceProgress(16.5f), 0.001f)
        assertEquals(-1f, CodeRainTiming.faceProgress(18.1f), 0f) // back to calm
        assertTrue(CodeRainTiming.faceProgress(34f) >= 0f) // and again 15 s later
    }

    @Test fun pushRisesAndSinksBack() {
        assertEquals(0f, CodeRainTiming.push(0f), 0.001f)
        assertTrue(CodeRainTiming.push(0.5f) > 0.8f)
        assertEquals(0f, CodeRainTiming.push(1f), 0.001f)
    }

    @Test fun faceHasFeatures() {
        val nose = FaceRelief.height(0f, 0.2f)
        val eye = FaceRelief.height(-0.33f, -0.12f)
        val cheek = FaceRelief.height(-0.42f, 0.14f)
        assertTrue("nose stands out above the eye sockets", nose > eye + 0.1f)
        assertTrue("cheekbones above the eye sockets", cheek > eye)
        assertTrue("eye sockets are sunken", FaceRelief.detail(-0.33f, -0.12f) < 0f)
        assertEquals("nothing outside the head", 0f, FaceRelief.height(0.95f, 0.9f), 0f)
    }
}
