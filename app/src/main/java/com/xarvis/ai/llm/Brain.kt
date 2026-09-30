package com.xarvis.ai.llm

/**
 * One AI brain XARVIS can talk to over the internet (Gemini, Groq, Cerebras, OpenRouter, OpenAI…).
 * [BrainChain] tries them in order and falls to the next when one runs out of free quota, so Rex
 * never hits a dead end. Every brain stores its own key encrypted in the Android Keystore
 * ([BrainKeys]); a key is entered by Rex in ☰ → BRAIN and only ever sent to that brain's provider.
 */
interface Brain {
    /** The [BrainKeys] id / provider id, e.g. "gemini", "groq". */
    val id: String

    /** The name shown to Rex and in the "via …" tag under a reply. */
    val label: String

    /** Where Rex gets a (free) key for this brain, shown under its key field. */
    val keyHint: String

    fun hasKey(): Boolean
    fun saveKey(key: String)
    fun clearKey()

    /** Answers [message]. Throws [QuotaReached] when the free limit is used up, or another error. */
    suspend fun chat(systemPrompt: String, message: String): String
}

/** A brain's state for the settings UI (no key material). */
data class BrainInfo(val id: String, val label: String, val keyHint: String, val hasKey: Boolean)
