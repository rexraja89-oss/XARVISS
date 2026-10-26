package com.xarvis.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun stripsBoldMarkers() {
        assertEquals("hi there", mdText("**hi** there").text)
    }

    @Test fun turnsDashesIntoBullets() {
        assertEquals("•  a\n•  b", mdText("- a\n- b").text)
    }

    @Test fun stripsHeadingHashes() {
        assertEquals("Title", mdText("# Title").text)
    }

    @Test fun leavesPlainTextAlone() {
        assertEquals("just a line", mdText("just a line").text)
    }
}
