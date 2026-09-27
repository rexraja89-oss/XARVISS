package com.xarvis.ai

import com.xarvis.ai.files.FileBlock
import com.xarvis.ai.policy.Category
import com.xarvis.ai.policy.Decision
import com.xarvis.ai.policy.Level
import com.xarvis.ai.policy.PolicyRules
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test fun defaultsKeepTodaysBehaviour() {
        assertEquals(Level.ASK, PolicyRules.defaultLevel(Category.PHONE_CALL))
        assertEquals(Level.ASK, PolicyRules.defaultLevel(Category.MESSAGING))
        Category.entries.filter { it != Category.PHONE_CALL && it != Category.MESSAGING }
            .forEach { assertEquals(it.name, Level.ALLOW, PolicyRules.defaultLevel(it)) }
    }

    @Test fun levelsDecide() {
        assertEquals(Decision.ALLOW, PolicyRules.decide(Level.ALLOW))
        assertEquals(Decision.ASK, PolicyRules.decide(Level.ASK))
        assertEquals(Decision.BLOCK, PolicyRules.decide(Level.DENY))
        assertEquals(Decision.BLOCK, PolicyRules.decide(Level.ALWAYS_MANUAL))
    }

    @Test fun alwaysManualCanNeverBeAllowed() {
        assertFalse(PolicyRules.canSet(alwaysManual = true, Level.ALLOW))
        assertFalse(PolicyRules.canSet(alwaysManual = true, Level.ASK))
        assertTrue(PolicyRules.canSet(alwaysManual = true, Level.ALWAYS_MANUAL))
        assertTrue(PolicyRules.canSet(alwaysManual = false, Level.ALLOW))
    }

    @Test fun everyActionHasACategory() {
        assertEquals(Category.PHONE_CALL, PolicyRules.categoryOf(Step.Call("Atiq", direct = true)))
        assertEquals(Category.MESSAGING, PolicyRules.categoryOf(Step.WhatsApp("Ali", "hi")))
        assertEquals(Category.MESSAGING, PolicyRules.categoryOf(Step.Sms("Ali", "hi")))
        assertEquals(Category.OPEN_APP, PolicyRules.categoryOf(Step.LaunchApp("YouTube")))
        assertEquals(Category.OPEN_APP, PolicyRules.categoryOf(Step.Search("weather")))
        assertEquals(Category.JOB_SEARCH, PolicyRules.categoryOf(Step.Jobs("engineer")))
        assertEquals(Category.FILES, PolicyRules.categoryOf(Step.MakeFile(FileBlock("a.pdf", "x"))))
        assertEquals(Category.MEMORY, PolicyRules.categoryOf(Step.Remember("likes tea")))
        assertEquals(Category.DEVICE_SETTINGS, PolicyRules.categoryOf(Step.Timer(60)))
        // Answers, readouts and pairing aren't gated.
        assertNull(PolicyRules.categoryOf(Step.ReportTime))
        assertNull(PolicyRules.categoryOf(Step.Battery))
        assertNull(PolicyRules.categoryOf(Step.PairCode("123456")))
    }

    @Test fun theLogNeverKeepsCodesOrPasswords() {
        assertEquals("Remember: my password is ••••", PolicyRules.scrub("Remember: my password is hunter2"))
        assertEquals("OTP: ••••", PolicyRules.scrub("OTP: 4821"))
        assertEquals("code ••••", PolicyRules.scrub("code 482193"))
        // Phone numbers and years stay readable.
        assertEquals("Call +971501234567", PolicyRules.scrub("Call +971501234567"))
        assertEquals("report 2026.pdf", PolicyRules.scrub("report 2026.pdf"))
    }
}
