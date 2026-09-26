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
        val brain = if (llm.isReady) null else link.findBrain() ?: return noBrain()
        suspend fun ask(text: String): String? = if (brain == null) {
            val out = StringBuilder()
            // Rebuilt before every call, so identity and every saved memory are always current.
            llm.chat(systemPrompt(), IDENTITY_REMINDER + text) { chunk ->
                out.append(chunk)
                onPartial(ToolCalls.visibleText(out.toString()))
            }
            out.toString()
        } else {
            onPartial("Asking ${brain.name}…")
            remoteChat(brain, text)
        }

        val first = try {
            ask(message)
        } catch (t: Throwable) {
            return "My language model hit an error: ${t.message ?: t.javaClass.simpleName}"
        }
        var raw = first ?: return "I couldn't reach ${brain?.name}'s AI model. Check both phones are on the same Wi-Fi."
        // Gemma sometimes says it could use a tool ("I can use the location tool if you ask")
        // instead of using it. Nudge it once, the way "yes use it" worked for Rex.
        if (ToolCalls.parse(raw).isEmpty() && skippedTool(ToolCalls.visibleText(raw))) {
            runCatching { ask(TOOL_NUDGE) }.getOrNull()?.takeIf { ToolCalls.parse(it).isNotEmpty() }?.let { raw = it }
        }
        return finishReply(message, raw)
    }

    private suspend fun remoteChat(brain: Peer, message: String): String? =
        runCatching { link.remoteChat(brain, facts(), link.pairedPeers().map { it.name }, message) }.getOrNull()

    /** Gemma's text plus the results of the tools it asked for. */
    private suspend fun finishReply(message: String, raw: String): String {
        val text = fixIdentity(ToolCalls.visibleText(raw))
        val known = facts()
        val steps = forUser(message, ToolCalls.parse(raw)).map { step ->
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

        private const val TOOL_NUDGE = "(Rex wants you to do it now. Reply with only the matching TOOL line.)"

        /** A reply that talks about a tool, or says it can't do something, instead of using a tool. */
        private val SKIPPED_TOOL = Regex(
            listOf(
                """\btool\b""", """if you (?:ask|want|tell)""",
                """(?:don't|do not|can't|cannot|can not|am unable to|unable to)\s+(?:have\s+)?(?:access|directly|interact|do that|check|see|open|take you|set|call|make)""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )

        internal fun skippedTool(reply: String): Boolean = reply.isNotBlank() && SKIPPED_TOOL.containsMatchIn(reply)

        /** Rex's own words asking for a call: "call Atiq", "please ring mom", "Ali ko call karo". */
        private val USER_SAYS_CALL = Regex(
            """^(?:please\s+)?(?:call|phone|ring|dial)\s+\S|\bko\s+(?:call|phone|fone)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Adjusts Gemma's tool choice to what Rex actually typed. Only when his own message asks
         * for a call is it placed directly (Gemma sometimes looks the contact up instead);
         * a call Gemma decides on by itself only opens the dialer.
         */
        internal fun forUser(message: String, steps: List<Step>): List<Step> {
            if (!USER_SAYS_CALL.containsMatchIn(message.trim())) return steps
            return steps.map {
                when (it) {
                    is Step.Call -> it.copy(direct = true)
                    is Step.FindContact -> Step.Call(it.name, direct = true)
                    else -> it
                }
            }
        }
        private val PAIRING_CODE = Regex("""\d{6}""")

        /** Put before each message: Gemma's template shows the system prompt only once, at the start of a chat. */
        private const val IDENTITY_REMINDER = "(You are XARVIS, created by Rex. Never say you were made by Google.)\n\n"

        internal val SYSTEM_PROMPT = """
            You are XARVIS, a personal AI assistant created by Rex. You run on-device on Rex's Samsung S22 Ultra. Never say you were made by Google.
            The user is Rex; talk to him directly as "you", never as "Rex". Reply briefly in plain text, without markdown. You can't browse the internet yourself; use search for live information like news, weather or prices.

            You can use these tools. To use one, reply with only its line:
            TOOL: time
            TOOL: remember <fact>
            TOOL: open <app name>
            TOOL: contact <person's name>
            TOOL: call <person's name or number>
            TOOL: whatsapp <person>: <message>
            TOOL: sms <person>: <message>
            TOOL: location
            TOOL: battery
            TOOL: bluetooth
            TOOL: bluetooth on / TOOL: bluetooth off
            TOOL: wifi on / TOOL: wifi off
            TOOL: flashlight on / TOOL: flashlight off
            TOOL: map <place, or nothing for where you are>
            TOOL: alarm <time>
            TOOL: timer <duration>
            TOOL: search <web search words>
            TOOL: find <app>: <words to search inside that app>
            TOOL: ask <app>: <text to type into that app, e.g. a question for ChatGPT>

            Examples:
            User: what time is it? -> TOOL: time
            User: kitne baje hain -> TOOL: time
            User: remember my sister's name is Sara -> TOOL: remember my sister's name is Sara
            User: open youtube -> TOOL: open youtube
            User: what is Atiq's number? -> TOOL: contact Atiq
            User: Ahmed ka number do -> TOOL: contact Ahmed
            User: call Ali -> TOOL: call Ali
            User: tell Sara on whatsapp I'm running late -> TOOL: whatsapp Sara: I'm running late
            User: text mom that I'm on my way -> TOOL: sms mom: I'm on my way
            User: where am I? -> TOOL: location
            User: main kahan hoon -> TOOL: location
            User: how much battery is left? -> TOOL: battery
            User: are my earbuds connected? -> TOOL: bluetooth
            User: turn on the torch -> TOOL: flashlight on
            User: take me to the map -> TOOL: map
            User: directions to Dubai Mall -> TOOL: map Dubai Mall
            User: wake me up at 6:30 am -> TOOL: alarm 6:30 am
            User: set a timer for 10 minutes -> TOOL: timer 10 minutes
            User: what's the weather in Lahore? -> TOOL: search weather in Lahore
            User: open gmail and search for ali@example.com -> TOOL: find gmail: ali@example.com
            User: open chat gpt -> TOOL: open chat gpt
            User: ask chat gpt how to build a mobile app -> TOOL: ask chat gpt: how to build a mobile app
            User: show my payslip -> TOOL: open payslip
            User: update my details in Intelligent CV and download my CV -> TOOL: open intelligent cv
            (then tell him you opened it and that he needs to edit and download the CV himself, because you can't tap inside other apps yet)
            User: who made you? -> I'm XARVIS, created by Rex.
            User: what is my name? -> answer from the facts below, without a tool.
            User: tell me a joke -> answer yourself, without a tool.

            Use "search" only for the web. To search inside an app, use "find".
            If a task needs more than your tools can do, use the tools that help, then say plainly what you did and what Rex must do himself. Never pretend you did something.
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
