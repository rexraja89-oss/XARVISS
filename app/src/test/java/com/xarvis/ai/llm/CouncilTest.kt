package com.xarvis.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI council helpers: pick a different reviewer model, and frame a proper review prompt. */
class CouncilTest {

    @Test fun reviewerPrefersADifferentModel() {
        // The model that drafted goes last, so a different one reviews when the key has several.
        assertEquals(
            listOf("gemini-2.0-flash", "gemini-flash-lite", "gemini-2.5-flash"),
            CloudLlm.alternateOrder(listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-lite"), "gemini-2.5-flash"),
        )
    }

    @Test fun oneModelKeyReviewsWithItself() {
        assertEquals(listOf("only-model"), CloudLlm.alternateOrder(listOf("only-model"), "only-model"))
    }

    @Test fun noDraftYetKeepsOrder() {
        assertEquals(listOf("a", "b"), CloudLlm.alternateOrder(listOf("a", "b"), null))
    }

    @Test fun reviewPromptCarriesQuestionAndDraftAndAsksForFinalOnly() {
        val msg = CloudLlm.reviewMessage("how does orbit work", "a draft answer")
        assertTrue(msg.contains("how does orbit work"))
        assertTrue(msg.contains("a draft answer"))
        assertTrue(msg.contains("FINAL"))
        assertTrue(msg.contains("ONLY the final answer"))
    }
}
