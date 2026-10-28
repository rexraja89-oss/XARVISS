package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmergencyTest {

    private fun call(msg: String, country: String?) = Emergency.detect(msg, country)

    @Test fun distressPoliceRequestInUae() {
        val c = call("XARVIS I'm in a situation, call the nearest police station", "ae")!!
        assertEquals("999", c.number)
        assertEquals("the police", c.label)
    }

    @Test fun ambulanceAndFire() {
        assertEquals("998", call("I need an ambulance now", "ae")!!.number)
        assertEquals("997", call("call the fire brigade", "ae")!!.number)
    }

    @Test fun countrySpecificNumbers() {
        assertEquals("100", call("call police", "in")!!.number)
        assertEquals("911", call("call the police", "us")!!.number)
        assertEquals("112", call("call police", null)!!.number) // unknown country → universal
    }

    @Test fun generalEmergency() {
        assertEquals("999", call("call emergency", "ae")!!.number)
    }

    @Test fun notAnEmergency() {
        assertNull(call("the police came to my street today", "ae")) // no call verb
        assertNull(call("can you call Atiq", "ae")) // a contact, not a service
        assertNull(call("what's the weather", "ae"))
    }

    @Test fun emergencyCallIsNotGated() {
        // Safety: an emergency dial must not sit behind an "Ask me" card.
        assertNull(com.xarvis.ai.policy.PolicyRules.categoryOf(Step.EmergencyCall("999", "the police")))
    }
}
