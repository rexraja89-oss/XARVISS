package com.xarvis.ai.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMathTest {

    @Test fun blazeFaceHas896Anchors() {
        assertEquals(896, FaceMath.anchors.size)
        assertEquals(1f / 32, FaceMath.anchors[0][0], 1e-6f) // first cell centre of the 16×16 grid
    }

    @Test fun decodesOneConfidentFace() {
        val reg = Array(896) { FloatArray(16) }
        val scores = FloatArray(896) { -10f }
        // Anchor 0 at (1/32, 1/32): a 32-px-wide face shifted 10 px right and down.
        reg[0] = floatArrayOf(10f, 10f, 32f, 32f, 4f, 0f, 16f, 0f, 10f, 8f, 10f, 14f, 0f, 0f, 20f, 0f)
        scores[0] = 5f
        val faces = FaceMath.decode(reg, scores)
        assertEquals(1, faces.size)
        val f = faces[0]
        assertEquals(1f / 32 + 10f / 128, (f.box[0] + f.box[2]) / 2, 1e-5f)
        assertEquals(32f / 128, f.box[2] - f.box[0], 1e-5f)
        val e = f.eyes()
        assertTrue(e[0] < e[2]) // image-left eye first
    }

    @Test fun overlappingDetectionsCollapse() {
        val reg = Array(896) { FloatArray(16) }
        val scores = FloatArray(896) { -10f }
        reg[0] = floatArrayOf(0f, 0f, 30f, 30f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        reg[1] = floatArrayOf(1f, 1f, 30f, 30f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        scores[0] = 4f; scores[1] = 3f
        assertEquals(1, FaceMath.decode(reg, scores).size)
    }

    @Test fun matchesThePersonAboveTheThreshold() {
        val wife = FaceMath.normalize(FloatArray(192) { if (it < 96) 1f else 0f })
        val other = FaceMath.normalize(FloatArray(192) { if (it >= 96) 1f else 0f })
        val known = mapOf("Asma" to listOf(wife), "Ali" to listOf(other))
        val m = FaceMath.bestMatch(wife, known)
        assertEquals("Asma", m.name)
        assertTrue(m.sure)
        // A borderline face (~0.38 similarity): "looks like Asma, not certain".
        val borderline = FaceMath.normalize(FloatArray(192) { if (it < 44 || it >= 96) 1f else 0f })
        val b = FaceMath.bestMatch(borderline, mapOf("Asma" to listOf(wife)))
        assertEquals("Asma", b.name)
        assertFalse(b.sure)
        // Barely alike (~0.10): someone XARVIS doesn't know.
        val stranger = FaceMath.normalize(FloatArray(192) { if (it < 10 || it >= 96) 1f else 0f })
        assertNull(FaceMath.bestMatch(stranger, mapOf("Asma" to listOf(wife))).name)
        // Nobody taught.
        assertNull(FaceMath.bestMatch(wife, emptyMap()).name)
    }

    @Test fun describesWhatItSaw() {
        assertTrue(FaceMath.describe(emptyList()).contains("can't see a face"))
        assertTrue(FaceMath.describe(listOf(FaceMath.Match("Asma", 0.8f, true))).startsWith("That's Asma"))
        assertTrue(FaceMath.describe(listOf(FaceMath.Match(null, 0.1f, false))).contains("don't recognise"))
        assertTrue(FaceMath.describe(listOf(FaceMath.Match("Asma", 0.8f, true), FaceMath.Match(null, 0.1f, false))).contains("2 people"))
    }

    @Test fun storeRoundTrips() {
        val json = FaceStore.format(mapOf("Asma" to listOf(floatArrayOf(0.5f, -0.25f))))
        val back = FaceStore.parse(json)
        assertEquals(listOf("Asma"), back.keys.toList())
        assertEquals(-0.25f, back.getValue("Asma")[0][1], 1e-6f)
    }
}
