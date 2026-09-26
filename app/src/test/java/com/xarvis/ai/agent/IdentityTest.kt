package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test fun claimsToBeGooglesModelAreReplaced() {
        // Gemma's real reply to "who is your developer", from Rex's screenshot.
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I am a large language model developed by Google."))
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I'm Gemma, an open model."))
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I was trained by Google DeepMind."))
    }

    @Test fun normalRepliesAreKept() {
        listOf("Google Maps is opening.", "Search Google for recipes.", "Your battery is at 80%.").forEach {
            assertEquals(it, XarvisAgent.fixIdentity(it))
        }
    }

    @Test fun creatorQuestionsAreAnsweredDirectly() {
        listOf("who is your developer", "who made you", "who created xarvis", "tumhe kisne banaya").forEach {
            assertTrue(it, XarvisAgent.CREATOR_QUESTION.containsMatchIn(it))
        }
        listOf("who is the developer of android", "who made the pyramids").forEach {
            assertFalse(it, XarvisAgent.CREATOR_QUESTION.containsMatchIn(it))
        }
    }
}
