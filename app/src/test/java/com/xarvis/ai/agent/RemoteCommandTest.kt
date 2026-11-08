package com.xarvis.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTest {
    @Test fun asksToControlTheLinkedPhone() {
        listOf(
            "control my s22", "remote control", "Remote control my S22.", "take full control of my other phone",
            "show s22 screen", "show me the S22's screen", "open benco screen", "I want to control my linked phone",
            "hey xarvis control the s22", "mirror my s22",
        ).forEach { assertTrue(it, XarvisAgent.remoteCommand(it)) }
        // A phone named only by its model ("benco V91s Plus"): its name words come from the link.
        assertTrue(XarvisAgent.remoteCommand("control v91s", setOf("v91s", "plus")))
    }

    @Test fun otherSentencesDont() {
        listOf(
            "control the volume", "how do I control my phone", "show my phone", "lock my phone", "open the camera",
            "what is on my screen", "turn off the screen", "take control", "show my screen time", "control",
        ).forEach { assertFalse(it, XarvisAgent.remoteCommand(it)) }
    }
}
