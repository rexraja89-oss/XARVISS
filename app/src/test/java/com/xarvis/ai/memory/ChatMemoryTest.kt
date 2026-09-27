package com.xarvis.ai.memory

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.workflow.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryTest {

    @Test fun readsNewAndOldLogEntries() {
        val json = JSONObject().put("u", "open gallery").put("x", "Opening Gallery.").toString()
        assertEquals(Exchange("open gallery", "Opening Gallery.", 5), exchange(MemoryEntry(category = "interaction", content = json, timestamp = 5)))
        assertEquals(Exchange("what time", "3:56 am", 7), exchange(MemoryEntry(category = "interaction", content = "what time => 3:56 am", timestamp = 7)))
    }

    @Test fun promptCarriesTheLatestChat() {
        val note = XarvisAgent.historyNote(listOf(Exchange("my car is blue", "Got it.", 1), Exchange("open compass", "Opening Compass.", 2)))
        assertEquals("Rex: my car is blue\nYou: Got it.\nRex: open compass\nYou: Opening Compass.", note)
        val prompt = XarvisAgent.buildPrompt(listOf("your name is Rex"), "This chat so far:\n$note")
        assertTrue(prompt.contains("- your name is Rex\n\nThis chat so far:\nRex: my car is blue"))
        assertTrue(prompt.endsWith("You: Opening Compass."))
    }

    @Test fun longHistoryKeepsTheNewest() {
        val many = (1..50).map { Exchange("question $it " + "x".repeat(100), "answer $it", it.toLong()) }
        val note = XarvisAgent.historyNote(many)
        assertTrue(note.length <= 2500)
        assertTrue(note.endsWith("answer 50"))
    }

    @Test fun recallTool() {
        assertEquals(listOf(Step.Recall("Gandhi")), ToolCalls.parse("TOOL: recall Gandhi"))
        assertEquals(listOf(Step.Recall("")), ToolCalls.parse("TOOL: recall"))
    }

    @Test fun chatsAreGroupedAndTitledByTheirFirstMessage() {
        val chats = chatsOf(
            listOf(
                Exchange("write an essay on Gandhi", "…", 10, "chat-1"),
                Exchange("make it a pdf", "Made it.", 20, "chat-1"),
                Exchange("open compass", "Opening Compass.", 30, "chat-2"),
            )
        )
        assertEquals(listOf("chat-2", "chat-1"), chats.map { it.id })
        assertEquals("write an essay on Gandhi", chats[1].title)
        assertEquals(2, chats[1].exchanges)
    }

    @Test fun oldEntriesWithoutAChatAreGroupedByDay() {
        val e = exchange(MemoryEntry(category = "interaction", content = "hi => hello", timestamp = 0))!!
        assertTrue(e.chat.startsWith("day-"))
    }
}
