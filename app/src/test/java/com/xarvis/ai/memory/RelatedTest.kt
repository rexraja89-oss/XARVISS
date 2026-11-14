package com.xarvis.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val past = listOf(
        Exchange("I want a pendant with a hidden camera and a SIM", "An ESP32-CAM with a GSM module could work, sir.", now - 5 * day, "chat-a"),
        Exchange("what's the weather today", "Sunny, 31 degrees.", now - 2 * day, "chat-b"),
        Exchange("call my wife", "Calling your wife.", now - 1 * day, "chat-b"),
        Exchange("how do I fix the boiler pressure gauge", "Check the valve first.", now - 3 * day, "chat-c"),
        Exchange("remind me about the camera module order", "Reminder set for the camera module.", now - 4 * day, "chat-d"),
    )

    @Test fun findsTheEarlierChatOnTheSameTopic() {
        val found = Related.find(past, "chat-now", "find me the smallest camera module for my pendant", now)
        assertTrue(found.isNotEmpty())
        assertEquals("chat-a", found.first().chat)
    }

    @Test fun unrelatedTalkBringsNothing() {
        assertTrue(Related.find(past, "chat-now", "tell me a joke", now).isEmpty())
        assertTrue(Related.find(past, "chat-now", "yes", now).isEmpty())
    }

    @Test fun theCurrentChatIsNotRepeated() {
        assertTrue(Related.find(past, "chat-a", "pendant hidden camera SIM ESP32", now).none { it.chat == "chat-a" })
    }

    @Test fun oneCommonWordIsNotEnough() {
        assertTrue(Related.find(past, "chat-now", "what's the weather in Dubai", now).isEmpty())
    }
}
