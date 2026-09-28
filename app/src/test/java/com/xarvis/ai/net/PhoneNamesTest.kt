package com.xarvis.ai.net

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNamesTest {
    private val phones = listOf("benco V91s Plus", "Galaxy S22 Ultra")

    @Test fun findsThePhoneRexNamed() {
        assertEquals("Galaxy S22 Ultra", DeviceLink.namedIn("what is s22 ultra battery status", phones))
        assertEquals("Galaxy S22 Ultra", DeviceLink.namedIn("S22 ki battery kitni hai?", phones))
        assertEquals("benco V91s Plus", DeviceLink.namedIn("battery of the benco", phones))
        assertNull(DeviceLink.namedIn("what's my battery?", phones))
        // "plus" and "galaxy" alone don't pick a phone.
        assertNull(DeviceLink.namedIn("battery plus galaxy", phones))
    }

    @Test fun sameDevice() {
        assertTrue(DeviceLink.sameDevice("s22 ultra", "Galaxy S22 Ultra"))
        assertTrue(DeviceLink.sameDevice("my benco", "benco V91s Plus"))
    }

    @Test fun batteryOfANamedPhone() {
        assertEquals(listOf(Step.Battery), ToolCalls.parse("TOOL: battery"))
        assertEquals(listOf(Step.Battery), ToolCalls.parse("TOOL: battery status"))
        assertEquals(listOf(Step.DeviceBattery("s22")), ToolCalls.parse("TOOL: battery s22"))
    }
}
