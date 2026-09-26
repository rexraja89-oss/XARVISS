package com.xarvis.ai.agent

import android.net.Uri
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.tools.ContactsTool
import com.xarvis.ai.tools.DeviceToolRouter
import com.xarvis.ai.workflow.Step
import com.xarvis.ai.workflow.Workflow
import com.xarvis.ai.workflow.WorkflowEngine

/**
 * Turns natural-language commands into workflows and runs them.
 *
 * Exact commands ("open spotify", "remember ...") go through the rule parser, which is
 * instant. Anything else goes to an LLM: this device's own, or a linked device's when this
 * one has none. The LLM either chats back or answers with `ACTION:` lines, which are parsed
 * by the same rules and always run on this device. Before a message reaches the LLM, the
 * [tools] router reads any live phone data it asks about (e.g. location) and adds it as
 * `[DEVICE DATA]` lines.
 */
class XarvisAgent(
    private val engine: WorkflowEngine,
    private val memory: MemorySystem,
    private val llm: LocalLlm,
    private val link: DeviceLink,
    private val tools: DeviceToolRouter,
    /** Whether an installed app answers to this name; "open <whole sentence>" isn't an app. */
    private val appExists: (String) -> Boolean,
) {

    suspend fun loadModel() = llm.load(systemPrompt())

    /** Rebuilds the LLM's prompt after remembered facts or linked devices change. */
    suspend fun refreshPrompt() = llm.reset(systemPrompt())

    /** Handles one message; [onPartial] receives the reply so far while the LLM is streaming. */
    suspend fun handle(command: String, onPartial: (String) -> Unit = {}): String {
        // Only look for a linked "brain" when there's no local model, so normal use never waits on the network.
        val brain = if (llm.isReady) null else link.findBrain()
        val workflow = plan(command, strict = llm.isReady || brain != null)
        val response = if (workflow != null) {
            run(workflow)
        } else {
            // Phone data is read where the message points: a linked device it names ("check my benco"),
            // otherwise this one, even when a linked device's LLM writes the answer.
            val peer = link.mentionedPeer(command)
            val data = if (peer != null) peerData(peer, command, onPartial) else withLinkedContacts(command, tools.gather(command, onPartial), onPartial)
            val prompt = DeviceToolRouter.withDeviceData(command, data)
            when {
                llm.isReady -> chat(prompt, command, data, onPartial)
                brain != null -> remoteChat(brain, prompt, command, data)
                data.isNotEmpty() -> data.joinToString("\n") // no AI anywhere: show the data itself
                else -> unknownCommand(command)
            }
        }
        memory.logInteraction(command, response)
        return response
    }

    /** Answers a linked device's free-form message with this device's LLM; null if it has none. */
    suspend fun answerForPeer(peerId: String, facts: List<String>, devices: List<String>, text: String): String? {
        if (!llm.isReady) return null
        return llm.chatAs(peerId, buildPrompt(facts, devices), text)
    }

    /**
     * Splits compound commands ("open maps then search coffee") into ordered steps.
     * Returns null if any part isn't a known command.
     */
    fun plan(command: String, strict: Boolean): Workflow? {
        // A message's text may itself contain "then" ("text ali: see you then"); don't split it.
        PhoneCommands.parse(command.trim(), fromUser = true)
            ?.takeIf { it is Step.WhatsApp || it is Step.Sms }
            ?.let { return Workflow(command, listOf(it)) }
        val parts = command
            .split(Regex("""\s*(?:,\s*)?\b(?:and then|then)\b\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val steps = parts.map { parseStep(it, strict, fromUser = true) ?: return null }
        return Workflow(command, steps)
    }

    private suspend fun run(workflow: Workflow): String {
        val results = engine.execute(workflow)
        if (workflow.steps.any { it is Step.Remember || it is Step.ClearMemory || it is Step.Unlink }) {
            refreshPrompt()
        }
        return results.joinToString("\n") { it.message }
    }

    /**
     * A contact asked about but not saved on this phone may be saved on a linked one ("atiq's number"
     * typed on the S22 when Atiq is only in the benco): ask them, and use what they find instead.
     */
    private suspend fun withLinkedContacts(message: String, local: List<String>, onPartial: (String) -> Unit): List<String> {
        if (!ContactsTool.isAbout(message) || local.any { it.startsWith(ContactsTool.FOUND) }) return local
        val found = link.pairedPeers().flatMap { p ->
            peerData(p, message, onPartial).filter { it.startsWith("On ${p.name}: ${ContactsTool.FOUND}") }
        }
        return if (found.isEmpty()) local else local.filterNot { it.startsWith("Contacts:") } + found
    }

    /** Reads the phone data [message] asks about on [peer], labelled with where it came from. */
    private suspend fun peerData(peer: Peer, message: String, onPartial: (String) -> Unit): List<String> {
        onPartial("Checking ${peer.name}…")
        return try {
            link.remoteDeviceData(peer, message).map { "On ${peer.name}: $it" }
        } catch (e: Exception) {
            listOf(
                "${peer.name}: couldn't be checked (${e.message ?: e.javaClass.simpleName}). It needs XARVIS running " +
                    "and up to date, on the same Wi-Fi."
            )
        }
    }

    /** [prompt] is what the LLM sees ([message] plus any device data); [message] is what the user typed. */
    private suspend fun chat(prompt: String, message: String, data: List<String>, onPartial: (String) -> Unit): String {
        val raw = StringBuilder()
        try {
            // Rebuilt before every call so identity and every saved memory are always current.
            llm.chat(systemPrompt(), IDENTITY_REMINDER + prompt) { chunk ->
                raw.append(chunk)
                onPartial(visibleText(raw.toString()))
            }
        } catch (t: Throwable) {
            return "My language model hit an error: ${t.message ?: t.javaClass.simpleName}"
        }
        return finishReply(message, raw.toString(), data)
    }

    private suspend fun remoteChat(brain: Peer, prompt: String, message: String, data: List<String>): String {
        val raw = try {
            link.remoteChat(brain, facts(), linkedDeviceNames(), prompt)
        } catch (e: Exception) {
            return "I couldn't reach ${brain.name}'s AI model: ${e.message ?: e.javaClass.simpleName}"
        }
        return finishReply(message, raw, data)
    }

    /**
     * Shows the reply's text and runs any ACTION lines in it. When device [data] was read, the answer
     * is in it: an "open <app>" the user didn't ask for (e.g. opening Contacts instead of reading
     * the number) is dropped, and a reply that ignores the data is replaced by the data itself.
     */
    private suspend fun finishReply(message: String, raw: String, data: List<String>): String {
        val hadData = data.isNotEmpty()
        val modelText = visibleText(raw).trim()
        val text = fixIdentity(if (hadData && ignoresData(modelText, data)) dataAnswer(data) else modelText)
        val known = facts()
        val actions = ACTION_LINE.findAll(raw)
            .mapNotNull { parseStep(it.groupValues[1].trim(), strict = true, fromUser = false) }
            .map { step ->
                // Small models sometimes answer "what is my name?" by re-saving the fact; say it instead.
                val fact = (step as? Step.Remember)?.fact
                if (fact != null && known.any { it.equals(fact, ignoreCase = true) }) {
                    Step.Respond(fact.replaceFirstChar { it.uppercase() }.trimEnd('.') + ".")
                } else {
                    step
                }
            }
            .filterNot { hadData && it is Step.LaunchApp && !message.contains("open", ignoreCase = true) }
            .toList()
        if (actions.isEmpty()) return text.ifEmpty { "…" }

        val results = run(Workflow(message, actions))
        return if (text.isEmpty()) results else "$text\n$results"
    }

    /** The reply minus ACTION lines, including a half-streamed line that may become one. */
    private fun visibleText(raw: String): String = raw.lines().filterNot { line ->
        val t = line.trimStart()
        t.startsWith("ACTION", ignoreCase = true) || (t.isNotEmpty() && "ACTION:".startsWith(t, ignoreCase = true))
    }.joinToString("\n").trim()

    private fun unknownCommand(command: String): String {
        val why = when (val s = llm.status.value) {
            LlmStatus.NotInstalled -> "There's no AI model on this device and no linked device with one is reachable, " +
                "so I only understand exact commands."
            LlmStatus.Loading -> "My language model is still loading. Try again in a moment."
            is LlmStatus.Failed -> "My language model couldn't start (${s.reason}) and no linked device with one is reachable."
            is LlmStatus.Ready -> ""
        }
        return "I don't know how to \"$command\" yet. $why Type \"help\" for commands.".replace("  ", " ")
    }

    /**
     * Every saved memory, oldest first. Only if they'd crowd out the 4096-token context are the
     * oldest left out ([MAX_FACT_CHARS] is roughly a fifth of it).
     */
    private suspend fun facts(): List<String> {
        val newestFirst = memory.allFacts().sortedByDescending { it.timestamp }.map { it.content }
        var used = 0
        return newestFirst.takeWhile { used += it.length + 3; used <= MAX_FACT_CHARS }.reversed()
    }

    private fun linkedDeviceNames(): List<String> = link.pairedPeers().map { it.name }

    private suspend fun systemPrompt(): String = buildPrompt(facts(), linkedDeviceNames())

    private fun buildPrompt(facts: List<String>, devices: List<String>): String = buildString {
        append(SYSTEM_PROMPT)
        if (devices.isNotEmpty()) {
            append("\n\nThe user's other linked devices: ${devices.joinToString()}. Extra actions for them:\n")
            append("ACTION: status of <device>\n")
            append("ACTION: send to <device>: <text>\n")
        }
        if (facts.isNotEmpty()) {
            append("\n\nFacts you know:\n")
            facts.forEach { append("- $it\n") }
        }
    }

    /**
     * Parses one command; [fromUser] is false for the LLM's ACTION lines. In [strict] mode (an LLM is available) only exact commands match,
     * so free-form messages that merely mention "time" or "battery" reach the LLM instead.
     */
    private fun parseStep(text: String, strict: Boolean, fromUser: Boolean): Step? {
        val t = text.trim().trimEnd('.', '!', '?')
        val lower = t.lowercase()

        // Phone actions first: "call ali" and "text mom: hi" would otherwise look like other commands.
        PhoneCommands.parse(t, fromUser)?.let { return it }

        // Linked devices first: "send ... to <device>" and "<device> status" would otherwise look like other commands.
        if (lower in DEVICES_COMMANDS) return Step.ListDevices
        if (lower in setOf("always on", "stay on", "background on")) return Step.SetAlwaysOn(true)
        if (lower in setOf("always off", "stay off", "background off")) return Step.SetAlwaysOn(false)
        match(t, """^(?:pair|link)\s+with\s+(.+)$""")?.let { return Step.PairWith(it) }
        match(t, """^code\s+(\d{6})$""")?.let { return Step.PairCode(it) }
        match(t, """^(?:unlink|unpair|forget device)\s+(.+)$""")?.let { return Step.Unlink(it) }
        Regex("""^link\s+(.+?)\s+at\s+(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$""", RegexOption.IGNORE_CASE)
            .find(t)?.let { m ->
                return Step.SetAddress(m.groupValues[1], m.groupValues[2], m.groupValues[3].toIntOrNull() ?: DeviceLink.PORT)
            }
        match(t, """^send\s+to\s+(.+?):\s*(.+)$""", group = 1)?.let { device ->
            return Step.SendNote(device, match(t, """^send\s+to\s+.+?:\s*(.+)$""")!!)
        }
        Regex("""^send\s+(.+)\s+to\s+(?:my\s+)?(\S+)$""", RegexOption.IGNORE_CASE).find(t)
            ?.takeIf { link.findPeer(it.groupValues[2]) != null }
            ?.let { return Step.SendNote(it.groupValues[2], it.groupValues[1]) }
        match(t, """^(?:status of|battery (?:on|of))\s+(.+)$""")?.let { return Step.RemoteStatus(it) }
        if (lower !in STATUS_COMMANDS) {
            match(t, """^(.+?)\s+(?:status|battery)$""")?.takeIf { link.findPeer(it) != null }
                ?.let { return Step.RemoteStatus(it) }
        }

        match(t, """^(?:please\s+)?(?:open|launch|start|run)\s+(?:the\s+)?(.+?)(?:\s+app)?$""")
            ?.takeIf { !strict || appExists(it) } // otherwise the LLM works out what was meant
            ?.let { return Step.LaunchApp(it) }

        // "search benco for Atiq's number" is about the user's own phones, not the web.
        match(t, """^(?:search|google|look up)(?:\s+for)?\s+(.+)$""")
            ?.takeUnless { ContactsTool.isAbout(it) || link.mentionedPeer(it) != null }
            ?.let { return Step.OpenUrl("https://www.google.com/search?q=${Uri.encode(it)}", "Searching for \"$it\".") }

        match(t, """^(?:navigate|directions)\s+to\s+(.+)$""")
            ?.let { return Step.OpenUrl("geo:0,0?q=${Uri.encode(it)}", "Navigating to $it.") }

        match(t, """^remember(?:\s+that)?\s+(.+)$""")
            ?.let { return Step.Remember(toSecondPerson(it)) }

        match(t, """^(?:what do you remember|recall)(?:\s+about)?\s*(.*)$""")
            ?.let { return Step.Recall(it.ifBlank { null }?.let(::toSecondPerson)) }

        return when {
            lower in setOf("forget everything", "clear memory", "wipe memory") -> Step.ClearMemory
            lower in STATUS_COMMANDS -> Step.ReportDevice
            lower in TIME_COMMANDS -> Step.ReportTime
            lower == "help" || HELP_QUESTION.containsMatchIn(lower) -> Step.Respond(HELP)
            CREATOR_QUESTION.containsMatchIn(lower) -> Step.Respond(IDENTITY)
            strict -> null
            lower.contains("status") || lower.contains("device") ||
                lower.contains("capabilit") || lower.contains("battery") -> Step.ReportDevice
            lower.contains("time") || lower.contains("date") -> Step.ReportTime
            lower.startsWith("what can you do") -> Step.Respond(HELP)
            lower in setOf("hi", "hello", "hey", "hey xarvis", "hello xarvis") ->
                Step.Respond("Online and ready. Type \"help\" to see what I can do.")
            else -> null
        }
    }

    private fun match(text: String, pattern: String, group: Int = 1): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(group)?.trim()

    companion object {
        /** "what can you do", "what help can you do", "what are your features". */
        private const val MAX_FACT_CHARS = 3000

        /** Put before each message: Gemma's template shows the system prompt only once, at the start of a chat. */
        private const val IDENTITY_REMINDER = "(You are XARVIS, created by Rex. Never say you were made by Google.)\n\n"

        /** Gemma's own idea of who made it, which it falls back to despite the system prompt. */
        private val MAKER_CLAIM = Regex(
            """\b(?:made|created|developed|built|trained|designed)\s+by\s+(?:google|deepmind|google deepmind)\b|\bi(?: am|'m)\s+(?:gemma|a large language model)\b""",
            RegexOption.IGNORE_CASE,
        )
        internal const val IDENTITY = "I'm XARVIS, your personal AI assistant, created by Rex. I run on-device on your Samsung S22 Ultra."

        /** A reply claiming to be Google's model is replaced by who XARVIS really is. */
        internal fun fixIdentity(reply: String): String = if (MAKER_CLAIM.containsMatchIn(reply)) IDENTITY else reply

        /** Stock refusals a small model gives even when the data is right there in its prompt. */
        private val REFUSAL = Regex(
            listOf(
                """(?:don't|do not|can't|cannot|can not|am unable to|unable to)\s+(?:have\s+)?(?:access|see|check|know|track|determine|find|get)""",
                """no access""", """language model""", """physical location""", """as an ai""",
                """open the contacts""", """need to open""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
        private val DATA_NUMBER = Regex("""\d{2,}""")

        /**
         * Whether a reply ignores the device data it was given: it refuses, or the data has numbers
         * (a phone number, a battery level, coordinates, the time) and the reply mentions none of them.
         */
        internal fun ignoresData(reply: String, data: List<String>): Boolean {
            if (reply.isBlank() || REFUSAL.containsMatchIn(reply)) return true
            val numbers = data.flatMap { line -> DATA_NUMBER.findAll(line).map { it.value }.toList() }.toSet()
            val replyDigits = reply.filter { it.isDigit() || it.isWhitespace() }
            return numbers.isNotEmpty() && numbers.none { it in reply || it in replyDigits.replace(" ", "") }
        }

        /** The device data as a reply, for when the model's own answer can't be trusted. */
        internal fun dataAnswer(data: List<String>): String = data.joinToString("\n") { line ->
            line.replace(ContactsTool.FOUND, "Contacts found:")
        }

        /** "who is your developer", "who made you", "tumhe kisne banaya". */
        internal val CREATOR_QUESTION = Regex(
            """^(?:who|whom)\s+(?:is|are|was)\s+your\s+(?:developer|creator|maker|owner|programmer|father|boss)|""" +
                """^who\s+(?:made|created|built|developed|programmed|designed|invented)\s+(?:you|xarvis)|""" +
                """\b(?:tumhe|tumhein|tujhe|aapko|apko)\s+(?:kisne|kis ne)\s+(?:banaya|bnaya)"""
        )

        internal val HELP_QUESTION = Regex(
            """^(?:what|which)\s+(?:help|things|features|commands)\b.*\b(?:can you|do you)|^what can you do\b|^what are your (?:features|abilities|commands|skills)"""
        )
        private val ACTION_LINE = Regex("""(?im)^\s*ACTION:\s*(.+)$""")
        private val DEVICES_COMMANDS = setOf("devices", "list devices", "linked devices", "show devices", "my devices")
        private val STATUS_COMMANDS = setOf("status", "device status", "device", "capabilities", "battery")
        private val TIME_COMMANDS = setOf("time", "date", "what time is it", "what's the time", "what is the time",
            "what's the date", "what is the date", "what's today's date")

        private val SYSTEM_PROMPT = """
            You are XARVIS, a personal AI assistant created by Rex. You run on-device on Rex's Samsung S22 Ultra. Never say you were made by Google.
            The user is Rex. Be friendly and concise. You run entirely offline and cannot browse the internet yourself.
            The facts at the end were told to you by Rex: "you" and "your" in them mean Rex, except that you, XARVIS, were created by Rex.

            You can control the phone. When the user wants one of these things done, reply with only the matching line(s), one per line, and no other text:
            ACTION: open <app name>
            ACTION: search <web search query>
            ACTION: navigate to <place>
            ACTION: remember <fact, in the user's own words, e.g. "my birthday is June 3">
            ACTION: status
            ACTION: time
            ACTION: call <contact name or number>
            ACTION: whatsapp <contact name or number>: <message>
            ACTION: sms <contact name or number>: <message>
            ACTION: flashlight on
            ACTION: flashlight off
            ACTION: alarm <time, e.g. 7:30 am, or 6 am for gym>
            ACTION: timer <duration, e.g. 10 minutes>
            ACTION: bluetooth on
            ACTION: bluetooth off
            ACTION: wifi on
            ACTION: wifi off

            Only use "remember" when the user tells you something new and wants you to keep it. When the user asks a question, such as "what is my name?", answer it in plain text using what you know, and never use an ACTION line for it.
            Only use "search" when the user asks you to search or look something up, or needs live information such as news, weather, prices or opening hours. Answer general knowledge, facts, jokes, explanations and advice yourself.

            When the user asks for someone's phone number, never open the Contacts app and never search the web for it: the number comes in [DEVICE DATA]. If the data says no contact matched, say you couldn't find that contact.
            A message may start with lines beginning with [DEVICE DATA]. They are live readings from the user's phone, taken just now, such as its location, battery, date and time, Bluetooth devices or the user's contacts. When [DEVICE DATA] is given, use it to answer in plain text. Never say you have no access to the phone's location or data if data is provided. If a [DEVICE DATA] line says something is unavailable, tell the user why in simple words. Lines starting with "On <device name>:" were read on that linked device, not this one.

            For anything else, chat naturally in plain text, in a few sentences at most. Don't use markdown. Never invent other actions, and never claim you did something on the phone unless you used an ACTION line.
        """.trimIndent()

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

        val HELP = """
            Commands:
              open <app>            launch an installed app
              search <query>        web search
              navigate to <place>   open maps
              remember <fact>       store a memory
              what do you remember [about <topic>]
              status                device capabilities
              time                  current date and time
              forget everything     clear memory
              call <name or number> call someone
              whatsapp <name>: <message>   (you tap Send)
              text <name>: <message>       (you tap Send)
              flashlight on / off
              alarm 7:30 am [for <label>]
              timer 10 minutes
              bluetooth on / off, wifi on / off
            Linked devices (same Wi-Fi, XARVIS open on both):
              devices               list linked devices
              pair with <device>    link another device (it shows a code)
              code <6 digits>       finish pairing
              <device> status       battery/storage of a linked device
              send to <device>: <text>
              unlink <device>
              always on / always off   keep running in the background (on by default)
            Ask about your location, battery, date/time, Bluetooth devices
            or a contact's number, and the AI answers from live phone data.
            Memories are shared with linked devices.
            Chain steps with "then", e.g. "open spotify then search lofi beats".
            Anything else is answered by the on-device AI model (or a linked device's).
        """.trimIndent()
    }
}
