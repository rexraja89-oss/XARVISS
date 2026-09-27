package com.xarvis.ai.update

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
}
