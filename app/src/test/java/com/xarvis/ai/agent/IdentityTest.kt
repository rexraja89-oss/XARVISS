package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test fun claimsToBeGooglesModelAreReplaced() {
        // Gemma's real replies to "who is your developer", from Rex's screenshots.
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I am a large language model developed by Google."))
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I am an AI assistant developed by Google."))
        assertEquals(XarvisAgent.IDENTITY, XarvisAgent.fixIdentity("I'm Gemma, an open model."))
    }

    @Test fun normalRepliesAreKept() {
        listOf("Google Maps is a great app.", "Your sister's name is Sara.", "Here's a joke about computers.").forEach {
            assertEquals(it, XarvisAgent.fixIdentity(it))
        }
    }

    @Test fun promptStartsWithWhoXarvisIsAndListsEveryMemory() {
        val prompt = XarvisAgent.buildPrompt(listOf("your name is Rex", "your car is red"))
        assertTrue(prompt.startsWith("You are XARVIS, a personal AI assistant created by Rex. " +
            "You run on-device on Rex's Samsung S22 Ultra. Never say you were made by Google."))
        assertTrue(prompt.contains("Facts you know:\n- your name is Rex\n- your car is red\n"))
        listOf("TOOL: time", "TOOL: remember", "TOOL: open", "TOOL: contact").forEach { assertTrue(it, prompt.contains(it)) }
    }
}
