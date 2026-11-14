package com.xarvis.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeModelTest {
    /** The detector that ships in the app (assets/wake/xarvis.bin). */
    private fun shipped(): InputStream {
        val file = File("src/main/assets/wake/xarvis.bin")
        return if (file.exists()) file.inputStream() else javaClass.getResourceAsStream("/wake/xarvis.bin")!!
    }

    /**
     * Real feature windows with the score PyTorch gave each when the detector was trained (training/wake/export.py):
     * the Kotlin network must give the same, or the phone would hear something the training never checked.
     */
    @Test fun scoresMatchTheTrainedNetwork() {
        val model = shipped().use { WakeModel.read(it) }
        assertEquals(16, model.frames)
        assertEquals(96, model.dim)
        val b = ByteBuffer.wrap(javaClass.getResourceAsStream("/wake_vectors.bin")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.int
        assertTrue(count >= 8)
        var woke = 0
        var slept = 0
        repeat(count) {
            val features = List(model.frames) { FloatArray(model.dim) { b.float } }
            val expected = b.float
            val score = model.score(features)
            assertEquals("vector $it", expected, score, 2e-4f)
            if (score > 0.5f) woke++
            if (score < 0.1f) slept++
        }
        assertTrue("some of the test windows wake it ($woke)", woke >= 2)
        assertTrue("and some leave it asleep ($slept)", slept >= 2)
    }

    @Test fun aDamagedFileIsRefused() {
        val bytes = shipped().use { it.readBytes() }
        try {
            WakeModel.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4) + bytes.copyOfRange(4, bytes.size)))
            fail("a file without the header was read")
        } catch (e: IllegalArgumentException) {
            // refused
        }
        try {
            WakeModel.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)))
            fail("half a file was read")
        } catch (e: Exception) {
            // refused
        }
    }

    @Test fun itNeedsExactlyTwoSecondsOfHistory() {
        val model = shipped().use { WakeModel.read(it) }
        try {
            model.score(List(model.frames - 1) { FloatArray(model.dim) })
            fail("scored with too little history")
        } catch (e: IllegalArgumentException) {
            // refused
        }
    }
}
