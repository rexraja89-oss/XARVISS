package com.xarvis.ai.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldCommandsTest {
    @Test fun commands() {
        assertEquals(ShieldCommands.Cmd.Checkup, ShieldCommands.parse("security check"))
        assertEquals(ShieldCommands.Cmd.Checkup, ShieldCommands.parse("Xarvis, is my phone hacked?"))
        assertEquals(ShieldCommands.Cmd.Checkup, ShieldCommands.parse("scan my phone"))
        val c = ShieldCommands.parse("is this link safe https://adcb-verify.com/login")
        assertTrue(c is ShieldCommands.Cmd.Check)
        assertTrue(ShieldCommands.judge((c as ShieldCommands.Cmd.Check).text).contains("Danger"))
        assertNull(ShieldCommands.parse("is this safe?"))
        assertNull(ShieldCommands.parse("what's the weather"))
    }
}
