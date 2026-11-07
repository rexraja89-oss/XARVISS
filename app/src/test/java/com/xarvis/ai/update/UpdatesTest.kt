package com.xarvis.ai.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersions() {
        assertTrue(Updates.isNewer("1.0.46", "1.0.45"))
        assertTrue(Updates.isNewer("1.0.100", "1.0.99"))
        assertFalse(Updates.isNewer("1.0.45", "1.0.45"))
        assertFalse(Updates.isNewer("1.0.44", "1.0.45"))
        assertFalse(Updates.isNewer("test-perms", "1.0.45"))
    }

    @Test fun readsTheTagFromTheWebsiteRedirect() {
        assertEquals("v1.0.189", Updates.tagFromRedirect("https://github.com/rexraja89-oss/XARVISS/releases/tag/v1.0.189"))
        assertEquals(null, Updates.tagFromRedirect(null))
        assertEquals(null, Updates.tagFromRedirect("https://github.com/rexraja89-oss/XARVISS/releases"))
    }
}
