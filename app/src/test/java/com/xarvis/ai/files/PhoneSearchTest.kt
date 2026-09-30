package com.xarvis.ai.files

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSearchTest {
    @Test fun keepsTheWordsThatMatter() {
        assertEquals(listOf("passport", "number"), PhoneSearch.words("find my passport number in my files"))
    }

    @Test fun matchesFileNames() {
        assertTrue(PhoneSearch.nameMatches("Rex_Passport_Scan.pdf", listOf("passport")))
        assertFalse(PhoneSearch.nameMatches("Visa.pdf", listOf("passport")))
    }

    @Test fun findsTheLineInsideAFile() {
        val text = "Name: Rex\nPassport No: Z1234567\nExpiry: 03/2029"
        assertEquals(listOf("Passport No: Z1234567 / Expiry: 03/2029"), PhoneSearch.snippetsIn(text, listOf("passport")))
        assertTrue(PhoneSearch.snippetsIn(text, listOf("salary")).isEmpty())
    }

    @Test fun toolLine() {
        assertEquals(listOf(Step.SearchPhone("passport")), ToolCalls.parse("TOOL: search phone passport"))
        assertEquals(listOf(Step.SearchPhone("passport")), ToolCalls.parse("TOOL: search my files for passport"))
    }
}
