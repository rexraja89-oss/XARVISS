package com.xarvis.ai.hands

import com.xarvis.ai.hands.LockPad.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPadTest {
    private val w = 1080
    private val h = 2316

    private fun screen(vararg kids: Node) = Node("android.widget.FrameLayout", "", "", 0, 0, w, h, kids.toList())

    @Test fun patternDotsFromTheViewsOwnCells() {
        // Like Android's LockPatternView: Accessibility shows its 9 dots as children (here given out of order).
        val cells = (0..8).shuffled(java.util.Random(3)).map { i ->
            val cx = 240 + (i % 3) * 300
            val cy = 1300 + (i / 3) * 300
            Node("", "", "Cell ${i + 1}", cx - 60, cy - 60, cx + 60, cy + 60)
        }
        val pad = LockPad.find(screen(Node("com.android.internal.widget.LockPatternView", "", "", 90, 1150, 990, 2050, cells)), w, h)
        assertNotNull(pad)
        assertEquals(9, pad!!.cells.size)
        // Row by row, left to right.
        assertEquals(240.0 / w, pad.cells[0].first, 1e-9)
        assertEquals(1300.0 / h, pad.cells[0].second, 1e-9)
        assertEquals(840.0 / w, pad.cells[2].first, 1e-9)
        assertEquals(1900.0 / h, pad.cells[8].second, 1e-9)
    }

    @Test fun patternDotsFromTheViewsBoxWhenItHasNoCells() {
        // Samsung's own view, found by its id: the dots sit in the middle of a 3×3 grid of equal squares.
        val pad = LockPad.find(screen(Node("android.view.View", "com.android.settings:id/lockPattern", "", 90, 1200, 990, 2100)), w, h)!!
        assertEquals(9, pad.cells.size)
        assertEquals(240.0 / w, pad.cells[0].first, 1e-9)
        assertEquals(1350.0 / h, pad.cells[0].second, 1e-9)
        assertEquals(540.0 / w, pad.cells[4].first, 1e-9)
        assertEquals(1950.0 / h, pad.cells[8].second, 1e-9)
    }

    @Test fun pinKeysAndTheFingerprintScreensButtons() {
        val keys = (0..9).map { d -> Node("android.widget.Button", "", "$d", 100 * d, 1500, 100 * d + 90, 1590) } +
            Node("android.widget.Button", "", "OK", 800, 1700, 1000, 1790)
        val pin = LockPad.find(screen(*keys.toTypedArray()), w, h)!!
        assertTrue(pin.cells.isEmpty())
        assertEquals(11, pin.keys.size)
        assertTrue(pin.keys.any { it.label == "OK" })

        val fingerprint = LockPad.find(screen(Node("android.widget.Button", "", "Use pattern", 100, 2000, 500, 2100), Node("android.widget.Button", "", "Cancel", 600, 2000, 1000, 2100)), w, h)!!
        assertEquals(listOf("Use pattern", "Cancel"), fingerprint.keys.map { it.label })
    }

    @Test fun anOrdinaryScreenHasNoPad() {
        // A calculator's digits alone, or an OK button, aren't a lock screen… unless all 10 digits are there.
        assertNull(LockPad.find(screen(Node("android.widget.Button", "", "OK", 0, 0, 100, 100), Node("android.widget.TextView", "", "7", 0, 200, 50, 250)), w, h))
        assertNull(LockPad.find(screen(), 0, 0))
    }

    @Test fun travelsAsJson() {
        val pad = LockPad.find(screen(Node("android.view.View", "x:id/lock_pattern", "", 90, 1200, 990, 2100), Node("android.widget.Button", "", "Cancel", 0, 2200, 300, 2300)), w, h)!!
        assertEquals(pad, LockPad.fromJson(LockPad.toJson(pad)))
    }
}
