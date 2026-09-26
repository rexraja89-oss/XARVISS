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

    @Test fun repliesThatSkipATool() {
        // Gemma's real reply to "where am I" in Rex's screenshot.
        assertTrue(XarvisAgent.skippedTool("I do not have access to your current location. I can use the location tool if you ask me to."))
        assertTrue(XarvisAgent.skippedTool("I cannot directly take you to a map."))
        assertFalse(XarvisAgent.skippedTool("I'm glad to hear that."))
        assertFalse(XarvisAgent.skippedTool("Why did the computer go to the doctor? It had a virus!"))
    }
}
