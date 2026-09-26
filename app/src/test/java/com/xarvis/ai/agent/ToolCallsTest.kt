package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemma picks the tool; these check XARVIS reads its choice correctly, however it's written. */
class ToolCallsTest {

    @Test fun eachTool() {
        assertEquals(listOf(Step.ReportTime), ToolCalls.parse("TOOL: time"))
        assertEquals(listOf(Step.Remember("your sister's name is Sara")), ToolCalls.parse("TOOL: remember my sister's name is Sara"))
        assertEquals(listOf(Step.LaunchApp("youtube")), ToolCalls.parse("TOOL: open youtube"))
        assertEquals(listOf(Step.FindContact("Atiq Qc")), ToolCalls.parse("TOOL: contact Atiq Qc"))
    }

    @Test fun looseFormatsSmallModelsWrite() {
        assertEquals(listOf(Step.ReportTime), ToolCalls.parse("**TOOL: time**"))
        assertEquals(listOf(Step.ReportTime), ToolCalls.parse("`TOOL: date`"))
        assertEquals(listOf(Step.FindContact("Atiq")), ToolCalls.parse("TOOL: contact <Atiq>"))
        assertEquals(listOf(Step.FindContact("Atiq")), ToolCalls.parse("TOOL: contact: Atiq's number"))
        assertEquals(listOf(Step.FindContact("Ahmed")), ToolCalls.parse("ACTION: find_contact Ahmed"))
        assertEquals(listOf(Step.LaunchApp("WhatsApp")), ToolCalls.parse("tool: open WhatsApp app."))
    }

    @Test fun textAroundToolLinesIsKeptForTheUser() {
        val reply = "Sure, let me check.\nTOOL: contact Atiq"
        assertEquals(listOf(Step.FindContact("Atiq")), ToolCalls.parse(reply))
        assertEquals("Sure, let me check.", ToolCalls.visibleText(reply))
        assertEquals("", ToolCalls.visibleText("TOO")) // a tool line still being streamed isn't shown
    }

    @Test fun plainAnswersHaveNoTools() {
        assertEquals(emptyList<Step>(), ToolCalls.parse("Why did the computer go to the doctor? It had a virus!"))
        assertEquals(emptyList<Step>(), ToolCalls.parse("TOOL: fly to the moon"))
        assertEquals(emptyList<Step>(), ToolCalls.parse("TOOL: contact"))
    }

    @Test fun linkingCommandsStayExact() {
        assertEquals(Step.PairWith("benco"), LinkCommands.parse("pair with benco"))
        assertEquals(Step.PairCode("123456"), LinkCommands.parse("code 123456"))
        assertEquals(Step.ListDevices, LinkCommands.parse("devices"))
        assertEquals(Step.Unlink("benco"), LinkCommands.parse("unlink benco"))
        assertEquals(null, LinkCommands.parse("what time is it"))
        assertEquals(null, LinkCommands.parse("code 12345"))
        // While pairing, the code alone is enough; otherwise a number is just a message for Gemma.
        assertEquals(Step.PairCode("010141"), LinkCommands.parse("010141", pairing = true))
        assertEquals(null, LinkCommands.parse("010141"))
    }
}
