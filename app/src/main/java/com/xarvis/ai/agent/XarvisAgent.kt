package com.xarvis.ai.agent

import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.workflow.Step
import com.xarvis.ai.workflow.WorkflowEngine

/**
 * Sends every message to Gemma, which either answers or picks one of XARVIS's tools
 * (time, remember, open app, find contact) with a `TOOL:` line; the tools run on this
 * device. On a phone without the model, a linked phone's Gemma is used. Only the commands
 * for linking phones are handled without Gemma (see [LinkCommands]).
 */
class XarvisAgent(
    private val engine: WorkflowEngine,
    private val memory: MemorySystem,
    private val llm: LocalLlm,
    private val link: DeviceLink,
) {

    suspend fun loadModel() = llm.load(systemPrompt())

    /** Starts a fresh conversation, e.g. after memories change. */
    suspend fun refreshPrompt() = llm.reset(systemPrompt())

    /** Handles one message; [onPartial] receives the reply so far while Gemma is writing it. */
    suspend fun handle(message: String, onPartial: (String) -> Unit = {}): String {
        val response = LinkCommands.parse(message, link.pairingInProgress)?.let { run(listOf(it)) } ?: askGemma(message, onPartial)
        memory.logInteraction(message, response)
        return response
    }

    /** Answers a linked phone's message with this phone's Gemma; its tool lines run on that phone. */
    suspend fun answerForPeer(peerId: String, facts: List<String>, devices: List<String>, text: String): String? {
        if (!llm.isReady) return null
        return llm.chatAs(peerId, buildPrompt(facts), IDENTITY_REMINDER + text)
    }

    private suspend fun askGemma(message: String, onPartial: (String) -> Unit): String {
        val raw = if (llm.isReady) {
            val out = StringBuilder()
            try {
                // Rebuilt before every call, so identity and every saved memory are always current.
                llm.chat(systemPrompt(), IDENTITY_REMINDER + message) { chunk ->
                    out.append(chunk)
                    onPartial(ToolCalls.visibleText(out.toString()))
                }
            } catch (t: Throwable) {
                return "My language model hit an error: ${t.message ?: t.javaClass.simpleName}"
            }
            out.toString()
        } else {
            val brain = link.findBrain() ?: return noBrain()
            onPartial("Asking ${brain.name}…")
            remoteChat(brain, message) ?: return "I couldn't reach ${brain.name}'s AI model. Check both phones are on the same Wi-Fi."
        }
        return finishReply(raw)
    }

    private suspend fun remoteChat(brain: Peer, message: String): String? =
        runCatching { link.remoteChat(brain, facts(), link.pairedPeers().map { it.name }, message) }.getOrNull()

    /** Gemma's text plus the results of the tools it asked for. */
    private suspend fun finishReply(raw: String): String {
        val text = fixIdentity(ToolCalls.visibleText(raw))
        val known = facts()
        val steps = ToolCalls.parse(raw).map { step ->
            // Small models sometimes answer "what is my name?" by re-saving the fact; say it instead.
            val fact = (step as? Step.Remember)?.fact
            if (fact != null && known.any { it.equals(fact, ignoreCase = true) }) {
                Step.Respond(fact.replaceFirstChar { it.uppercase() }.trimEnd('.') + ".")
            } else {
                step
            }
        }
        if (steps.isEmpty()) return text.ifEmpty { "…" }
        val results = run(steps)
        return if (text.isEmpty()) results else "$text\n$results"
    }

    private suspend fun run(steps: List<Step>): String =
        engine.execute(steps).joinToString("\n") { it.message }

    private fun noBrain(): String = when (val s = llm.status.value) {
        LlmStatus.Loading -> "My AI model is still loading. Try again in a moment."
        is LlmStatus.Failed -> "My AI model couldn't start (${s.reason}), and no linked phone with one is reachable."
        else -> "There's no AI model on this phone and the linked phone with one isn't reachable. " +
            "Make sure XARVIS is running on it and both phones are on the same Wi-Fi."
    }

    /**
     * Every saved memory, oldest first. Only if they'd crowd out the 4096-token context are the
     * oldest left out ([MAX_FACT_CHARS] is roughly a fifth of it).
     */
    private suspend fun facts(): List<String> {
        val newestFirst = memory.allFacts().sortedByDescending { it.timestamp }.map { it.content }
            .filterNot { PAIRING_CODE.matches(it.trim()) } // a code typed without "code" was once saved as a memory
        var used = 0
        return newestFirst.takeWhile { used += it.length + 3; used <= MAX_FACT_CHARS }.reversed()
    }

    private suspend fun systemPrompt(): String = buildPrompt(facts())

    companion object {
        internal fun buildPrompt(facts: List<String>): String = buildString {
            append(SYSTEM_PROMPT)
            if (facts.isNotEmpty()) {
                append("\n\nFacts you know:\n")
                facts.forEach { append("- $it\n") }
            }
        }

        private const val MAX_FACT_CHARS = 3000
        private val PAIRING_CODE = Regex("""\d{6}""")

        /** Put before each message: Gemma's template shows the system prompt only once, at the start of a chat. */
        private const val IDENTITY_REMINDER = "(You are XARVIS, created by Rex. Never say you were made by Google.)\n\n"

        internal val SYSTEM_PROMPT = """
            You are XARVIS, a personal AI assistant created by Rex. You run on-device on Rex's Samsung S22 Ultra. Never say you were made by Google.
            The user is Rex. Reply briefly in plain text, without markdown. You can't browse the internet.

            You have four tools. To use one, reply with only its line:
            TOOL: time
            TOOL: remember <fact>
            TOOL: open <app name>
            TOOL: contact <person's name>

            Examples:
            User: what time is it? -> TOOL: time
            User: kitne baje hain -> TOOL: time
            User: what's the date today? -> TOOL: time
            User: remember my sister's name is Sara -> TOOL: remember my sister's name is Sara
            User: open youtube -> TOOL: open youtube
            User: what is Atiq's number? -> TOOL: contact Atiq
            User: Ahmed ka number do -> TOOL: contact Ahmed
            User: who made you? -> I'm XARVIS, created by Rex.
            User: what is my name? -> answer from the facts below, without a tool.
            User: tell me a joke -> answer yourself, without a tool.

            Use "remember" only when Rex tells you something new to keep. Never say you did something on the phone without a tool line.
            The facts below were told to you by Rex: "you" and "your" in them mean Rex, except that you, XARVIS, were created by Rex.
        """.trimIndent()

        /** Gemma's own idea of who made it, which it falls back to despite the system prompt. */
        private val MAKER_CLAIM = Regex(
            """\b(?:made|created|developed|built|trained|designed)\s+by\s+(?:google|deepmind|google deepmind)\b|\bi(?: am|'m)\s+(?:gemma|a large language model)\b""",
            RegexOption.IGNORE_CASE,
        )
        internal const val IDENTITY = "I'm XARVIS, your personal AI assistant, created by Rex. I run on-device on your Samsung S22 Ultra."

        /** A reply claiming to be Google's model is replaced by who XARVIS really is. */
        internal fun fixIdentity(reply: String): String = if (MAKER_CLAIM.containsMatchIn(reply)) IDENTITY else reply

        private val PERSPECTIVE = mapOf("i am" to "you are", "i'm" to "you're", "my" to "your")
        private val PERSPECTIVE_REGEX = Regex("""\b(i am|i'm|my)\b""", RegexOption.IGNORE_CASE)

        /** Rewrites a fact from the user's point of view to XARVIS's: "my name is rex" -> "your name is rex". */
        fun toSecondPerson(text: String): String =
            PERSPECTIVE_REGEX.replace(text) { m ->
                val replacement = PERSPECTIVE.getValue(m.value.lowercase())
                // "I" is always capitalised, so only keep a capital at the start of the fact.
                if (m.range.first == 0 && m.value[0].isUpperCase()) replacement.replaceFirstChar { it.uppercase() }
                else replacement
            }
    }
}
