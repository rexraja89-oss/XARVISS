package com.xarvis.ai.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup chain: fall to the next brain on quota/error, and let a different brain review. */
class BrainChainTest {

    /** A fake brain that returns a fixed answer, or throws, so the chain logic is testable offline. */
    private class Fake(
        override val label: String,
        private val reply: String? = null,
        private val error: Exception? = null,
    ) : Brain {
        override val id = label.lowercase()
        override val keyHint = ""
        override fun hasKey() = true
        override fun saveKey(key: String) {}
        override fun clearKey() {}
        override suspend fun chat(systemPrompt: String, message: String, maxTokens: Int): String =
            reply ?: throw (error ?: IllegalStateException("no reply"))
    }

    @Test fun fallsToTheNextBrainWhenOneIsOutOfQuota() = runBlocking {
        val chain = listOf(Fake("Gemini", error = QuotaReached("limit")), Fake("Groq", reply = "hello from groq"))
        val answer = BrainChain.firstAnswer(chain, "sys", "hi") {}
        assertEquals("hello from groq", answer.text)
        assertEquals("Groq", answer.via)
    }

    @Test fun throwsWhenEveryBrainFails() {
        val chain = listOf(Fake("Gemini", error = QuotaReached("limit")), Fake("Groq", error = QuotaReached("limit")))
        try {
            runBlocking { BrainChain.firstAnswer(chain, "sys", "hi") {} }
            throw AssertionError("expected an error when all brains fail")
        } catch (e: QuotaReached) {
            // expected: the agent then drops to on-device Gemma
        }
    }

    @Test fun councilDraftsWithOneBrainAndReviewsWithAnother() = runBlocking {
        val chain = listOf(Fake("Gemini", reply = "draft answer"), Fake("Groq", reply = "reviewed answer"))
        val answer = BrainChain.councilOf(chain, "sys", "question") {}
        assertEquals("reviewed answer", answer.text)
        assertEquals("Gemini + Groq", answer.via)
    }

    @Test fun councilWithOneBrainReviewsItselfAndNeverFailsToTheDraft() = runBlocking {
        val chain = listOf(Fake("Groq", reply = "the answer"))
        val answer = BrainChain.councilOf(chain, "sys", "question") {}
        assertEquals("the answer", answer.text)
        assertTrue(answer.via.contains("council"))
    }
}
