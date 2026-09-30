package com.xarvis.ai.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException

/**
 * The backup brain chain (Rex asked, Phase 1): Gemini first, then other free brains, then Rex's
 * OpenAI credit. [chat] tries them in order and falls to the next when one runs out of free quota
 * or errors, so XARVIS keeps answering; if they all fail the agent drops to on-device Gemma.
 *
 * Every brain keeps its own key in the Keystore. Keys are entered by Rex in ☰ → BRAIN and only ever
 * sent to their own provider. Nothing is hard-coded or logged.
 */
class BrainChain(context: Context) {

    private val appContext = context.applicationContext

    /** Gemini is special (its own API + model discovery + two-model council); the rest are OpenAI-style. */
    val gemini = CloudLlm(appContext)

    private val backupBrains: List<Brain> = listOf(
        OpenAiCompat(
            appContext, "groq", "Groq", "https://api.groq.com/openai/v1",
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            "console.groq.com → API Keys. Free, no card.",
        ),
        OpenAiCompat(
            appContext, "deepseek", "DeepSeek", "https://api.deepseek.com",
            listOf("deepseek-chat", "deepseek-reasoner"),
            "platform.deepseek.com → API keys. Small paid credit; excellent at coding.",
        ),
        OpenAiCompat(
            appContext, "mistral", "Mistral", "https://api.mistral.ai/v1",
            listOf("codestral-latest", "mistral-large-latest"),
            "console.mistral.ai → API Keys. Free tier; Codestral is built for code.",
        ),
        OpenAiCompat(
            appContext, "together", "Together", "https://api.together.xyz/v1",
            listOf("Qwen/Qwen2.5-Coder-32B-Instruct", "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"),
            "api.together.ai → Settings → API Keys. Free credit; strong coding models.",
        ),
        OpenAiCompat(
            appContext, "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            listOf("qwen/qwen-2.5-coder-32b-instruct:free", "meta-llama/llama-3.3-70b-instruct:free"),
            "openrouter.ai → Keys. Free models, no card.",
            mapOf("X-Title" to "XARVIS"),
        ),
        OpenAiCompat(
            appContext, "github", "GitHub Models", "https://models.github.ai/inference",
            listOf("openai/gpt-4o-mini", "gpt-4o-mini"),
            "github.com → Settings → Developer settings → tokens. Free.",
        ),
        OpenAiCompat(
            appContext, "openai", "OpenAI", "https://api.openai.com/v1",
            listOf("gpt-4o-mini", "gpt-4.1-mini"),
            "platform.openai.com → API keys. Uses your paid credit.",
        ),
    )

    private fun all(): List<Brain> = listOf(gemini) + backupBrains

    /** Brains with a key, in try-order. */
    private fun enabled(): List<Brain> = all().filter { it.hasKey() }

    /** True if any brain (Gemini or a backup) has a key. */
    fun anyKey(): Boolean = all().any { it.hasKey() }

    /** Connectivity, provider-agnostic (checks all networks so a VPN isn't read as offline). */
    fun online(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
    }.getOrDefault(false)

    // ---- key management for the settings UI ----
    /** The backup brains (not Gemini, which has its own section) for ☰ → BRAIN. */
    fun backups(): List<BrainInfo> = backupBrains.map { BrainInfo(it.id, it.label, it.keyHint, it.hasKey()) }
    fun saveBackupKey(id: String, key: String) { backupBrains.firstOrNull { it.id == id }?.saveKey(key) }
    fun clearBackupKey(id: String) { backupBrains.firstOrNull { it.id == id }?.clearKey() }

    /** One brain's answer and which brain(s) gave it (for the "via …" tag). */
    data class Answer(val text: String, val via: String)

    /** Solo: the first brain that answers, falling through the chain on quota/error. */
    suspend fun chat(systemPrompt: String, message: String, onStage: (String) -> Unit): Answer =
        firstAnswer(enabled(), systemPrompt, message, onStage)

    /**
     * Council: one brain drafts, a different brain reviews and writes the best final answer. With
     * only Gemini, it uses Gemini's two models; with more brains, it's a real multi-company review.
     */
    suspend fun council(systemPrompt: String, message: String, onStage: (String) -> Unit): Answer {
        val e = enabled()
        if (e.size == 1 && e[0] === gemini) {
            return Answer(gemini.council(systemPrompt, message, onStage), "Gemini · council")
        }
        return councilOf(e, systemPrompt, message, onStage)
    }

    companion object {
        /** Tries each brain in order; returns the first answer. Shared so it can be unit-tested. */
        internal suspend fun firstAnswer(
            brains: List<Brain>, systemPrompt: String, message: String, onStage: (String) -> Unit,
        ): Answer {
            if (brains.isEmpty()) throw IllegalStateException("no cloud brain")
            var lastError: Exception? = null
            for ((i, b) in brains.withIndex()) {
                try {
                    onStage(if (i == 0) "Thinking…" else "Switching to ${b.label}…")
                    return Answer(b.chat(systemPrompt, message), b.label)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e // quota or error: try the next brain in the chain
                }
            }
            throw lastError ?: IllegalStateException("no cloud brain answered")
        }

        /** Draft with the first working brain, review with a different one (self-review if only one). */
        internal suspend fun councilOf(
            brains: List<Brain>, systemPrompt: String, message: String, onStage: (String) -> Unit,
        ): Answer {
            if (brains.isEmpty()) throw IllegalStateException("no cloud brain")
            onStage("Drafting an answer…")
            var drafter: Brain? = null
            var draft: String? = null
            var lastError: Exception? = null
            for (b in brains) {
                try {
                    draft = b.chat(systemPrompt, message); drafter = b; break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            val d = draft ?: throw (lastError ?: IllegalStateException("no draft"))
            val dr = drafter!!
            onStage("A second AI is reviewing the answer…")
            val reviewers = brains.filter { it !== dr }.ifEmpty { listOf(dr) }
            for (b in reviewers) {
                try {
                    val review = b.chat(systemPrompt, CloudLlm.reviewMessage(message, d))
                    return Answer(review, if (b === dr) "${dr.label} · council" else "${dr.label} + ${b.label}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // this reviewer couldn't; try the next, else keep the draft below
                }
            }
            return Answer(d, "${dr.label} · council")
        }
    }
}
