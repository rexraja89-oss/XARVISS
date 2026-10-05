package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserIntentTest {

    @Test fun callsRexAsksForArePlacedDirectly() {
        // From Rex's screenshot: Gemma looked Atiq up instead of calling.
        assertEquals(listOf(Step.Call("Atiq qc", direct = true)), XarvisAgent.forUser("call Atiq qc", listOf(Step.FindContact("Atiq qc"))))
        assertEquals(listOf(Step.Call("Ali", direct = true)), XarvisAgent.forUser("Ali ko call karo", listOf(Step.Call("Ali"))))
        assertEquals(listOf(Step.Call("mom", direct = true)), XarvisAgent.forUser("please ring mom", listOf(Step.Call("mom"))))
    }

    @Test fun callsGemmaDecidesOnOnlyOpenTheDialer() {
        assertEquals(listOf(Step.Call("Ali")), XarvisAgent.forUser("I should talk to Ali", listOf(Step.Call("Ali"))))
        assertEquals(listOf(Step.FindContact("Atiq")), XarvisAgent.forUser("what is Atiq's number", listOf(Step.FindContact("Atiq"))))
    }

    @Test fun imoCallsGoThroughXarvisHands() {
        // "open imo & call baarish": route to XARVIS Hands, and drop the separate "open imo" step.
        assertEquals(
            listOf(Step.AppCall("imo", "baarish")),
            XarvisAgent.forUser("open imo & call baarish", listOf(Step.LaunchApp("imo"), Step.Call("baarish"))),
        )
        // The real failure: the cloud brain read it as a find-in-imo search, not a call.
        assertEquals(
            listOf(Step.AppCall("imo", "baarish")),
            XarvisAgent.forUser("open imo & call baarish", listOf(Step.FindInApp("imo", "baarish"))),
        )
        assertEquals(
            listOf(Step.AppCall("imo", "baarish")),
            XarvisAgent.forUser("open imo & call baarish", listOf(Step.LaunchApp("imo"), Step.FindInApp("imo", "baarish"))),
        )
        // Nothing usable from the brain — pull the name from the message.
        assertEquals(
            listOf(Step.AppCall("imo", "baarish")),
            XarvisAgent.forUser("open imo and call baarish", emptyList()),
        )
        // Hinglish, contact only.
        assertEquals(
            listOf(Step.AppCall("imo", "baarish")),
            XarvisAgent.forUser("imo pe baarish ko call karo", listOf(Step.Call("baarish"))),
        )
        // A video call in imo.
        assertEquals(
            listOf(Step.AppCall("imo", "mom", video = true)),
            XarvisAgent.forUser("video call mom on imo", listOf(Step.Call("mom"))),
        )
        // Without imo, a normal call stays a normal (direct) phone call, not routed to Hands.
        assertEquals(
            listOf(Step.Call("baarish", direct = true)),
            XarvisAgent.forUser("call baarish", listOf(Step.Call("baarish"))),
        )
    }

    @Test fun repliesThatSkipATool() {
        // Gemma's real reply to "where am I" in Rex's screenshot.
        assertTrue(XarvisAgent.skippedTool("I do not have access to your current location. I can use the location tool if you ask me to."))
        assertTrue(XarvisAgent.skippedTool("I cannot directly take you to a map."))
        assertFalse(XarvisAgent.skippedTool("I'm glad to hear that."))
        assertFalse(XarvisAgent.skippedTool("Why did the computer go to the doctor? It had a virus!"))
    }

    @Test fun timeOnlyWhenAsked() {
        val time = listOf(Step.ReportTime)
        assertTrue(XarvisAgent.mistakenTime("do you remember my every command from the time i build you", time))
        listOf("what time is it?", "kitne baje hain", "what's the date today", "aaj kya tarikh hai", "time", "time now")
            .forEach { assertFalse(it, XarvisAgent.mistakenTime(it, time)) }
        assertFalse(XarvisAgent.mistakenTime("from the time i built you", listOf(Step.ListMemories)))
    }

    @Test fun memoriesTool() {
        assertEquals(listOf(Step.ListMemories), ToolCalls.parse("TOOL: memories"))
        assertEquals(listOf(Step.Remember("your car is white")), ToolCalls.parse("TOOL: remember my car is white"))
    }
}
