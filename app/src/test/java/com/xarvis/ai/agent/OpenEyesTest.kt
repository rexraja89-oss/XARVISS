package com.xarvis.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenEyesTest {
    @Test fun opensEyes() {
        listOf("look", "Look", "take a look", "open your eyes", "open eyes", "XARVIS Eyes", "hey jarvis, look", "Jarvis look")
            .forEach { assertTrue(it, XarvisAgent.OPEN_EYES.matches(it)) }
    }

    @Test fun ordinarySentencesDont() {
        listOf("look at this code", "what does it look like", "open youtube", "my eyes hurt")
            .forEach { assertFalse(it, XarvisAgent.OPEN_EYES.matches(it)) }
    }
}
