package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/** Gemma picks the tool; these check XARVIS reads its choice correctly, however it's written. */
class ToolCallsTest {

    @Test fun eachTool() {
        assertEquals(listOf(Step.ReportTime), ToolCalls.parse("TOOL: time"))
        assertEquals(listOf(Step.Remember("your sister's name is Sara")), ToolCalls.parse("TOOL: remember my sister's name is Sara"))
        assertEquals(listOf(Step.LaunchApp("youtube")), ToolCalls.parse("TOOL: open youtube"))
        assertEquals(listOf(Step.FindContact("Atiq Qc")), ToolCalls.parse("TOOL: contact Atiq Qc"))
    }

    @Test fun phoneTools() {
        val evening = LocalTime.of(20, 0)
        fun one(line: String) = ToolCalls.parse(line, evening).single()
        assertEquals(Step.Call("Ali"), one("TOOL: call Ali"))
        assertEquals(Step.WhatsApp("Sara", "I'm running late"), one("TOOL: whatsapp Sara: I'm running late"))
        assertEquals(Step.WhatsApp("Sara", null), one("TOOL: whatsapp Sara"))
        assertEquals(Step.Sms("mom", "I'm on my way"), one("TOOL: sms mom: I'm on my way"))
        assertEquals(Step.Location, one("TOOL: location"))
        assertEquals(Step.Battery, one("TOOL: battery"))
        assertEquals(Step.BluetoothStatus, one("TOOL: bluetooth"))
        assertEquals(Step.Bluetooth(true), one("TOOL: bluetooth on"))
        assertEquals(Step.Wifi(false), one("TOOL: wifi off"))
        assertEquals(Step.Flashlight(true), one("TOOL: flashlight on"))
        assertEquals(Step.Flashlight(false), one("TOOL: torch off"))
        assertEquals(Step.Alarm(6, 30, null), one("TOOL: alarm 6:30 am"))
        assertEquals(Step.Alarm(7, 0, null), one("TOOL: alarm 7")) // at 8 pm, "7" is the next 7 o'clock: 7 am
        assertEquals(Step.Alarm(6, 0, "gym"), one("TOOL: alarm 6 am for gym"))
        assertEquals(Step.Timer(600), one("TOOL: timer 10 minutes"))
        assertEquals(Step.Timer(5400), one("TOOL: timer 1 hour 30 min"))
        assertEquals(Step.Search("weather in Lahore"), one("TOOL: search weather in Lahore"))
        assertEquals(Step.ReportTime, one("TOOL: time")) // "time" and "timer" don't get mixed up
        assertEquals(Step.ShowMap(null), one("TOOL: map"))
        assertEquals(Step.FindInApp("gmail", "Adarsh.Kelathedath@madinagulf.com"), one("TOOL: find gmail: Adarsh.Kelathedath@madinagulf.com"))
        assertEquals(Step.FindContact("Atiq"), one("TOOL: find contact Atiq")) // not an app search
        assertEquals(Step.ShowMap("Dubai Mall"), one("TOOL: map Dubai Mall"))
    }

    @Test fun unclearPhoneToolsAreIgnored() {
        assertEquals(emptyList<Step>(), ToolCalls.parse("TOOL: alarm tomorrow"))
        assertEquals(emptyList<Step>(), ToolCalls.parse("TOOL: sms mom"))
        assertEquals(emptyList<Step>(), ToolCalls.parse("TOOL: wifi"))
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
