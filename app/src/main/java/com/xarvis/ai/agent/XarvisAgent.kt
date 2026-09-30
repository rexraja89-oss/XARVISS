package com.xarvis.ai.agent

import com.xarvis.ai.files.Document
import com.xarvis.ai.files.FileBlock
import com.xarvis.ai.files.FileBlocks
import com.xarvis.ai.files.CameraShots
import com.xarvis.ai.files.PhoneSearch
import com.xarvis.ai.workflow.StepResult
import com.xarvis.ai.files.SavedFile
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.tools.WebLookup
import com.xarvis.ai.workflow.Step
import com.xarvis.ai.workflow.WorkflowEngine
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What XARVIS says, and the files it made or found (shown with OPEN and SHARE). */
data class Reply(val text: String, val files: List<SavedFile> = emptyList(), val via: String? = null)

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
    private val phoneSearch: PhoneSearch,
    private val appContext: android.content.Context,
) {

    /** The last file Rex attached, for "convert it to PDF" (also asked in a later message). */
    private var lastDocument: Document? = null

    /** The optional cloud brain (Gemini); off until Rex adds a key in ☰ → BRAIN. */
    private val cloud = com.xarvis.ai.llm.CloudLlm(appContext)
    private val settings = appContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    /** The brain that answered the last message, for the "on-device / via Gemini" tag. */
    @Volatile private var lastVia: String? = null
    /** Documents and photos are Rex's own data: they never go to the cloud. */
    @Volatile private var forceLocalThisTurn: Boolean = false

    /** The chat Rex is in (each opening of XARVIS starts a new one; the ☰ menu reopens old ones). */
    var chatId: String = newChatId()
        private set

    /**
     * What Gemma is told about earlier talk: this chat so far (when it was reopened or the
     * conversation restarted), or else the topics of recent chats. It changes only when the
     * conversation starts over, so the prompt stays the same between messages.
     */
    private var history: String = ""

    private suspend fun loadHistory() {
        history = runCatching {
            val here = memory.chatExchanges(chatId).takeLast(HISTORY_EXCHANGES)
            if (here.isNotEmpty()) {
                "This chat so far (newest last; it may be from before XARVIS restarted):\n" + historyNote(here)
            } else {
                val recent = memory.chats().take(RECENT_CHAT_TOPICS)
                if (recent.isEmpty()) "" else "This is a new chat. Rex's recent chats were about: " +
                    recent.joinToString("; ") { it.title } + ". Use TOOL: recall to look at one."
            }
        }.getOrDefault("")
    }

    /** Switches to chat [id] (a new one when null): Gemma starts over with that chat's past. */
    suspend fun openChat(id: String?) {
        chatId = id ?: newChatId()
        loadHistory()
        llm.reset(systemPrompt())
    }

    suspend fun loadModel() {
        loadHistory()
        llm.load(systemPrompt())
    }

    /** Switches to the brain [LocalLlm.preferSmart] picks (Fast or Smart). */
    suspend fun reloadModel() {
        loadHistory()
        llm.reload(systemPrompt())
    }

    /** Starts a fresh conversation, e.g. after memories change. */
    suspend fun refreshPrompt() {
        loadHistory()
        llm.reset(systemPrompt())
    }

    /** Handles one message; [onPartial] receives the reply so far while Gemma is writing it. */
    suspend fun handle(message: String, onPartial: (String) -> Unit = {}): Reply {
        forceLocalThisTurn = false
        val response = LinkCommands.parse(message, link.pairingInProgress)?.let { run(listOf(it)) } ?: askGemma(message, onPartial)
        memory.logInteraction(message, response.text, chatId)
        return response
    }

    /**
     * A file Rex attached ([doc], already read as text) with his question about it. As much of
     * the text as fits goes to Gemma, which can also answer with a new file made from it.
     */
    suspend fun handleDocument(doc: Document, message: String, onPartial: (String) -> Unit = {}): Reply {
        forceLocalThisTurn = true // a file is Rex's own data: keep it on the phone
        lastDocument = doc
        val question = message.ifBlank { "Summarise this file: what is it, and what are the main points?" }
        brainReady(onPartial)
        val room = if (llm.isReady) llm.documentChars else REMOTE_DOCUMENT_CHARS
        val cut = doc.text.length > room
        val prompt = "(Rex attached the file \"${doc.name}\". Its text is between <<< and >>>" +
            (if (cut) ", but it's long, so you only see the first part: say so if the answer may be further on" else "") +
            ". Use it to answer. To turn it into another format, reply only TOOL: convert pdf (or docx, xlsx, txt). " +
            "To make a new, changed file from it, write a FILE block.)\n\n<<<\n" +
            doc.text.take(room) + "\n>>>\n\nRex: " + question
        val response = askGemma(question, onPartial, prompt)
        memory.logInteraction("[file ${doc.name}] $question", response.text, chatId)
        return response
    }

    /**
     * A photo with Rex's question ([message] may be empty: "describe it"). Gemma sees the photo on
     * this phone; its reply can use tools as usual, e.g. a web search about what's in the photo.
     */
    suspend fun handlePhoto(imagePath: String, message: String, onPartial: (String) -> Unit = {}): Reply {
        forceLocalThisTurn = true // a photo is Rex's own data: keep it on the phone
        val question = message.ifBlank { "Describe this photo in detail and explain everything in it." }
        brainReady(onPartial)
        val response = if (llm.isReady) photoHere(imagePath, message, question, onPartial)
            else photoElsewhere(imagePath, message, question, onPartial)
        memory.logInteraction("[photo] $question", response.text, chatId)
        return response
    }

    private suspend fun photoHere(imagePath: String, message: String, question: String, onPartial: (String) -> Unit): Reply {
        if (!llm.canSeePhotos) return Reply("This AI model file can't look at photos.")
        val out = StringBuilder()
        return try {
            llm.chatWithImage(systemPrompt(), imagePath, IDENTITY_REMINDER + PHOTO_HINT + question) { chunk ->
                out.append(chunk)
                onPartial(ToolCalls.visibleText(FileBlocks.preview(out.toString())))
            }
            val raw = withLookups(out.toString(), onPartial) { text -> chatHere(text, onPartial) }
            finishReply(message, raw)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Reply("I couldn't look at that photo: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** On a phone without the model (the benco): the linked phone's Gemma looks at the photo. */
    private suspend fun photoElsewhere(imagePath: String, message: String, question: String, onPartial: (String) -> Unit): Reply {
        val brain = link.findBrain() ?: return Reply(noBrain())
        onPartial("Sending the photo to ${brain.name}…")
        val raw = try {
            link.remotePhoto(brain, facts(), File(imagePath).readBytes(), question)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Reply("I couldn't get ${brain.name} to look at the photo: ${e.message ?: e.javaClass.simpleName}")
        }
        return finishReply(message, withLookups(raw, onPartial) { remoteChat(brain, it) })
    }

    /** Answers a linked phone's message with this phone's Gemma; its tool lines run on that phone. */
    suspend fun answerForPeer(peerId: String, facts: List<String>, devices: List<String>, text: String, history: String = ""): String? {
        if (!llm.isReady) return null
        return llm.chatAs(peerId, buildPrompt(facts, history), IDENTITY_REMINDER + text)
    }

    /** Looks at a linked phone's photo with this phone's Gemma; tools in the reply run on that phone. */
    suspend fun answerPhotoForPeer(peerId: String, facts: List<String>, imagePath: String, text: String): String? {
        if (!llm.isReady || !llm.canSeePhotos) return null
        return llm.chatAsWithImage(peerId, buildPrompt(facts), imagePath, IDENTITY_REMINDER + PHOTO_HINT + text)
    }

    /** [text] through this phone's Gemma, streaming what it writes to [onPartial]. */
    private suspend fun chatHere(text: String, onPartial: (String) -> Unit): String {
        // If the cloud was meant to answer but couldn't, why: carried into the via tag so Rex
        // can see the reason under the bubble (he can't read the device log).
        var cloudNote: String? = null
        // Cloud brain for general chat (Rex's choice, mode B), but only when it's safe to.
        if (useCloud()) {
            try {
                onPartial("Thinking…")
                // The cloud prompt carries NO saved memories: Rex's facts never leave the phone.
                val answer = cloud.chat(buildPrompt(emptyList()), IDENTITY_REMINDER + text)
                lastVia = "Gemini"
                onPartial(ToolCalls.visibleText(FileBlocks.preview(answer)))
                return answer
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.xarvis.ai.llm.QuotaReached) {
                cloudNote = "cloud limit reached"
                onPartial("Cloud limit reached, sir — switching to the phone brain…")
            } catch (e: Exception) {
                cloudNote = "cloud: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w("XarvisAgent", "Cloud failed; using Gemma", e)
                onPartial("Cloud unavailable — using the phone brain…")
            }
        }
        // This phone's Gemma, when it has one (the S22).
        if (llm.isReady) {
            lastVia = withNote("on-device", cloudNote)
            val out = StringBuilder()
            // Rebuilt before every call, so identity and every saved memory are always current.
            llm.chat(systemPrompt(), IDENTITY_REMINDER + text, onRestart = { out.clear() }) { chunk ->
                out.append(chunk)
                onPartial(ToolCalls.visibleText(FileBlocks.preview(out.toString())))
            }
            return out.toString()
        }
        // No Gemma here (the benco) and the cloud didn't answer: ask the linked phone's Gemma.
        val peer = link.findBrain() ?: return noBrain()
        lastVia = withNote(peer.name, cloudNote)
        onPartial("Asking ${peer.name}…")
        return remoteChat(peer, text) ?: noBrain()
    }

    /** "on-device" plus the reason the cloud was skipped, if any: "on-device · cloud: Gemini 400". */
    private fun withNote(via: String, note: String?): String = if (note == null) via else "$via · $note"

    /**
     * Whether this message may go to the cloud brain (Gemini). Rex controls privacy himself with
     * the 🔒 lock; the cloud is used whenever it's on and not locked. Documents and photos still
     * stay on-device (forceLocalThisTurn), and saved memories never leave the phone.
     */
    private fun useCloud(): Boolean =
        settings.getBoolean("cloudEnabled", false) &&
            !settings.getBoolean("privateLock", false) &&
            !forceLocalThisTurn &&
            cloud.hasKey() && cloud.online()

    /** [message] is what Rex typed; [prompt] is what Gemma is sent (the message, or it with a file's text). */
    private suspend fun askGemma(message: String, onPartial: (String) -> Unit, prompt: String = message): Reply {
        brainReady(onPartial)
        // chatHere routes: cloud (if allowed) → this phone's Gemma → a linked phone's Gemma.
        // Only bail out early when none of those can answer at all.
        if (!llm.isReady && !useCloud() && link.findBrain() == null) return Reply(noBrain())
        lastVia = null
        suspend fun ask(text: String): String? = chatHere(text, onPartial)

        val first = try {
            ask(prompt)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return Reply("My language model hit an error: ${t.message ?: t.javaClass.simpleName}")
        }
        var raw = first ?: return Reply(noBrain())
        // Gemma sometimes says it could use a tool ("I can use the location tool if you ask")
        // instead of using it. Nudge it once, the way "yes use it" worked for Rex.
        if (!usesTools(raw) && skippedTool(ToolCalls.visibleText(raw))) {
            runCatching { ask(TOOL_NUDGE) }.getOrNull()?.takeIf(::usesTools)?.let { raw = it }
        }
        // "since the time I built you" once got the clock: time only when Rex asks about time.
        if (mistakenTime(message, ToolCalls.parse(raw))) {
            runCatching { ask(NOT_TIME_NUDGE) }.getOrNull()?.takeIf { !mistakenTime(message, ToolCalls.parse(it)) }?.let { raw = it }
                ?: return finishReply(message, ToolCalls.visibleText(raw)) // its words, without the wrong tool
        }
        raw = withLookups(raw, onPartial) { ask(it) }
        return finishReply(message, raw)
    }

    private suspend fun remoteChat(brain: Peer, message: String): String? =
        runCatching { link.remoteChat(brain, facts(), link.pairedPeers().map { it.name }, message, history) }.getOrNull()

    /**
     * When Gemma asked to look something up, reads Wikipedia and asks Gemma again with the facts,
     * returning its new reply (or the facts themselves if it can't be asked).
     */
    private suspend fun withLookups(raw: String, onPartial: (String) -> Unit, ask: suspend (String) -> String?): String {
        val steps = ToolCalls.parse(raw)
        val lookups = steps.filterIsInstance<Step.Lookup>()
        val recalls = steps.filterIsInstance<Step.Recall>()
        val searches = steps.filterIsInstance<Step.SearchPhone>()
        val galleries = steps.filterIsInstance<Step.RemoteGallery>()
        if (lookups.isEmpty() && recalls.isEmpty() && searches.isEmpty() && galleries.isEmpty()) return raw
        onPartial(
            when {
                galleries.isNotEmpty() -> "Fetching the photos over the link…"
                searches.isNotEmpty() -> "Searching your files…"
                recalls.isNotEmpty() -> "Looking through our earlier chats…"
                else -> "Looking it up on Wikipedia…"
            },
        )
        for (g in galleries.take(1)) {
            val result = engine.guarded(g) { remoteGallery(g.device, onPartial) }
            foundFiles += result.files
            // A gallery reply is the photos themselves, not something for Gemma to reword.
            return result.message
        }
        val found = mutableListOf<String>()
        for (l in lookups.take(2)) found += "Wikipedia on \"${l.query}\":\n" + WebLookup.lookup(l.query)
        for (r in recalls.take(2)) found += recall(r.query)
        for (q in searches.take(1)) {
            val result = engine.guarded(q) { searchPhone(q.query, onPartial) }
            if (!result.success) return result.message // no folders yet, or not allowed: say so plainly
            foundFiles += result.files
            found += result.message
        }
        val facts = found.joinToString("\n\n")
        val answer = runCatching { ask(FOUND_PREFIX + facts) }.getOrNull()
        return answer?.takeIf { ToolCalls.visibleText(it).isNotBlank() } ?: facts
    }

    /** Files found by a phone search, shown as cards under the reply. */
    private val foundFiles = mutableListOf<SavedFile>()

    /** Fetches a linked phone's recent photos over the link and saves them on this phone to browse. */
    private suspend fun remoteGallery(device: String, onPartial: (String) -> Unit): StepResult {
        val peer = link.findPeer(device) ?: link.findBrain() ?: link.pairedPeers().firstOrNull()
            ?: return StepResult(false, "I'm not linked to another phone yet. Type \"devices\" to check.")
        val photos = try {
            link.remoteGallery(peer, REMOTE_GALLERY_COUNT)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return StepResult(false, "I couldn't reach ${peer.name}: ${e.message ?: e.javaClass.simpleName}. Make sure XARVIS is open on it with Tailscale on.")
        }
        if (photos.isEmpty()) return StepResult(
            false,
            "${peer.name} has no photos to share, sir. On ${peer.name}, open ☰ → Search folders and add its camera folder (DCIM/Camera).",
        )
        onPartial("Saving ${photos.size} photos from ${peer.name}…")
        val saved = photos.mapNotNull { (name, jpg) -> CameraShots.saveReceived(appContext, name, jpg, DeviceLink.shortName(peer.name)) }
        return StepResult(
            saved.isNotEmpty(),
            if (saved.isNotEmpty()) "Here are ${peer.name}'s ${saved.size} most recent photos, sir. Tap OPEN to see one, or SHARE to keep it."
            else "I got ${peer.name}'s photos but couldn't save them here.",
            saved,
        )
    }

    /** Searches Rex's folders; the message is what Gemma reads to answer. */
    private suspend fun searchPhone(query: String, onPartial: (String) -> Unit): StepResult {
        if (phoneSearch.folders().isEmpty()) return StepResult(
            false,
            "I can only search folders you've given me, sir, and there aren't any yet. " +
                "Open ☰ → Search folders → ADD FOLDER and pick a folder (for example your Rex folder or Documents).",
        )
        val found = phoneSearch.search(query, onPartial)
        if (found.files.isEmpty() && found.snippets.isEmpty()) return StepResult(
            false, "I searched ${found.scanned} files in your folders and found nothing about \"$query\".",
        )
        val text = buildString {
            append("Search of Rex's folders for \"$query\":\n")
            if (found.files.isNotEmpty()) append("Files: ").append(found.files.joinToString(", ") { it.name }).append("\n")
            found.snippets.forEach { append("- ").append(it).append("\n") }
        }
        return StepResult(true, text, found.files)
    }

    /** Gemma's text plus the results of the tools it asked for, and the files it wrote. */
    private suspend fun finishReply(message: String, raw: String): Reply {
        currentCoroutineContext().ensureActive() // after STOP, don't run the tools
        val (fileBlocks, rest) = FileBlocks.split(raw)
        val text = fixIdentity(ToolCalls.visibleText(rest))
        val known = facts()
        val steps: List<Step> = fileBlocks.map { Step.MakeFile(it) } + onPhone(message, forUser(message, ToolCalls.parse(rest).filterNot { it is Step.Lookup || it is Step.Recall || it is Step.SearchPhone || it is Step.RemoteGallery })).map { step ->
            val doc = lastDocument
            if (step is Step.ConvertFile && doc != null) {
                return@map Step.MakeFile(FileBlock(doc.name.substringBeforeLast('.') + "." + step.format, doc.text))
            }
            // Small models sometimes answer "what is my name?" by re-saving the fact; say it instead.
            val fact = (step as? Step.Remember)?.fact
            if (fact != null && known.any { it.equals(fact, ignoreCase = true) }) {
                Step.Respond(fact.replaceFirstChar { it.uppercase() }.trimEnd('.') + ".")
            } else {
                step
            }
        }
        val searched = foundFiles.toList().also { foundFiles.clear() }
        if (steps.isEmpty()) return Reply(text.ifEmpty { "…" }, searched, via = lastVia)
        val results = run(steps)
        val reply = if (text.isEmpty()) results else results.copy(text = "$text\n${results.text}")
        return reply.copy(files = searched + reply.files, via = lastVia)
    }

    private suspend fun run(steps: List<Step>): Reply {
        val results = engine.execute(steps)
        return Reply(results.joinToString("\n") { it.message }.trim(), results.flatMap { it.files })
    }

    /**
     * Rex asked about a particular phone ("what is s22 ultra battery status", typed on the benco):
     * Gemma only says "battery", so the battery is read on the phone he named.
     */
    private fun onPhone(message: String, steps: List<Step>): List<Step> {
        val names = listOf(link.deviceName) + link.pairedPeers().map { it.name }
        val named = DeviceLink.namedIn(message, names)
        val other = named?.takeIf { it != link.deviceName }
        // "show the S22's gallery/photos" from another phone → fetch that phone's recent photos.
        if (other != null && WANTS_GALLERY.containsMatchIn(message)) {
            return steps.map {
                if (it is Step.RemoteGallery) Step.RemoteGallery(other)
                else if (it is Step.LaunchApp && it.appName.contains(Regex("(?i)gallery|photos"))) Step.RemoteGallery(other)
                else it
            }
        }
        if (steps.none { it == Step.Battery } || other == null) return steps
        return steps.map { if (it == Step.Battery) Step.DeviceBattery(other) else it }
    }

    /** Just after a restart the model takes about a minute to load: wait for it rather than fail. */
    private suspend fun brainReady(onPartial: (String) -> Unit) {
        if (llm.isReady || !llm.modelPresent()) return
        onPartial("One moment, sir, my brain is still waking up…")
        llm.awaitLoaded(BRAIN_LOAD_WAIT_MS)
    }

    private fun noBrain(): String = when (val s = llm.status.value) {
        LlmStatus.Loading -> "My AI model is still loading. Try again in a moment."
        is LlmStatus.Failed -> "My AI model couldn't start (${s.reason}), and no linked phone with one is reachable."
        else -> "There's no AI model on this phone and the linked phone with one isn't reachable. " +
            "Make sure XARVIS is running on it, with Tailscale on (or both phones on the same Wi-Fi)."
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

    private suspend fun systemPrompt(): String = buildPrompt(facts(), history)

    /** Earlier chats matching [query] (the latest ones if it's blank), as text for Gemma. */
    private suspend fun recall(query: String): String {
        val found = runCatching {
            if (query.isBlank()) memory.recentExchanges(RECALL_LATEST) else memory.searchExchanges(query, RECALL_MATCHES)
        }.getOrDefault(emptyList())
        val title = if (query.isBlank()) "Your latest chats with Rex" else "Earlier chats with Rex about \"$query\""
        if (found.isEmpty()) return "$title: none found."
        return "$title (oldest first):\n" + found.joinToString("\n") { e ->
            "[${WHEN.format(java.util.Date(e.time))}] Rex: ${e.user.take(300)} | You: ${e.reply.take(400)}"
        }
    }

    companion object {
        internal fun buildPrompt(facts: List<String>, history: String = ""): String = buildString {
            append(SYSTEM_PROMPT)
            if (facts.isNotEmpty()) {
                append("\n\nFacts you know:\n")
                facts.forEach { append("- $it\n") }
            }
            if (history.isNotBlank()) append("\n\n").append(history)
        }

        private const val HISTORY_EXCHANGES = 8
        private const val RECENT_CHAT_TOPICS = 5

        fun newChatId(): String = "chat-" + System.currentTimeMillis()
        private const val MAX_HISTORY_CHARS = 2500
        private const val RECALL_LATEST = 12
        private const val RECALL_MATCHES = 8
        private val WHEN = java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.ENGLISH)

        /** Recent exchanges for the prompt, newest kept when they don't all fit. */
        internal fun historyNote(exchanges: List<com.xarvis.ai.memory.Exchange>): String {
            val lines = exchanges.map { "Rex: ${it.user.take(200)}\nYou: ${it.reply.take(300)}" }
            var used = 0
            return lines.reversed().takeWhile { used += it.length + 1; used <= MAX_HISTORY_CHARS }.reversed().joinToString("\n")
        }

        private const val MAX_FACT_CHARS = 3000
        private const val BRAIN_LOAD_WAIT_MS = 180_000L
        private const val REMOTE_GALLERY_COUNT = 8
        /** File text sent to a linked phone's Gemma, whose context size isn't known here. */
        private const val REMOTE_DOCUMENT_CHARS = 8000

        /** Whether Gemma's reply asks for a tool or writes a file. */
        internal fun usesTools(raw: String): Boolean = ToolCalls.parse(raw).isNotEmpty() || FileBlocks.split(raw).first.isNotEmpty()

        private const val PHOTO_HINT = "(Rex sent a photo. Look at it carefully and answer about it. " +
            "Recognise famous things (films, places, logos, people) from what you know. " +
            "If he wants facts you're unsure of, add a TOOL: lookup line with good words.)\n\n"

        private const val FOUND_PREFIX = "(Here is what XARVIS found. Use it to answer Rex's last question " +
            "in a few sentences, in your own words. Don't use another lookup or recall.)\n\n"

        private const val NOT_TIME_NUDGE = "(Rex didn't ask what time it is. Answer his last message again, " +
            "without the time tool.)"

        /** Rex asking for the time or date: "what time is it", "kitne baje hain", "aaj kya tarikh hai". */
        private val ABOUT_TIME = Regex(
            """\b(?:what|which|tell|kya|current|aaj)\b.*\b(?:time|date|day|tarikh|din)\b|\btime\s+(?:now|is it|please)\b|""" +
                """\bkitne\s+baje\b|\bwaqt\b|^\W*(?:time|date|day)\W*$""",
            RegexOption.IGNORE_CASE,
        )

        /** Gemma chose the clock although Rex's message isn't asking for the time or date. */
        internal fun mistakenTime(message: String, steps: List<Step>): Boolean =
            Step.ReportTime in steps && !ABOUT_TIME.containsMatchIn(message)

        private const val TOOL_NUDGE = "(Rex wants you to do it now. Reply with only the matching TOOL line, " +
            "or with the FILE block if he asked for a file.)"

        /** A reply that talks about a tool, or says it can't do something, instead of using a tool. */
        private val SKIPPED_TOOL = Regex(
            listOf(
                """\btool\b""", """if you (?:ask|want|tell)""",
                """\b(?:don't|do not)\s+have\s+(?:a|an|any)\s+\w+\s+(?:app|to)\b""",
                """(?:don't|do not|can't|cannot|can not|am unable to|unable to)\s+(?:have\s+)?(?:access|directly|interact|do that|check|see|open|take you|set|call|make|create|save|generate|write)""",
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
        /** Rex asking about jobs to apply for; a web search or a LinkedIn people search then becomes the jobs tool. */
        private val ABOUT_JOBS = Regex("""\b(?:jobs?|vacanc(?:y|ies)|hiring|openings?|apply|requirements?|positions?)\b""", RegexOption.IGNORE_CASE)

        internal fun forUser(message: String, steps: List<Step>): List<Step> {
            val jobs = ABOUT_JOBS.containsMatchIn(message)
            val adjusted = if (!jobs) steps else steps.map {
                when {
                    it is Step.Search -> ToolCalls.jobs(it.query.replace(Regex("(?i)\\blinkedin\\b"), " ").trim())
                    it is Step.FindInApp && it.app.contains("linkedin", true) -> ToolCalls.jobs(it.query)
                    else -> it
                }
            }
            return callsFor(message, photosFor(message, adjusted))
        }

        private val WANTS_GALLERY = Regex("""(?i)\b(gallery|photos|pictures|pics|images)\b""")

        private val USER_WANTS_PHOTO = Regex("""(?i)\b(?:take|click|capture|khinch\w*|kheench\w*|le\s+lo)\b.*\b(?:photo|picture|pic|selfie|snap)\b|\bselfie\b""")

        private fun photosFor(message: String, steps: List<Step>): List<Step> {
            if (!USER_WANTS_PHOTO.containsMatchIn(message)) return steps
            val selfie = Regex("""(?i)\b(?:selfie|front)\b""").containsMatchIn(message)
            return steps.map { if (it is Step.LaunchApp && it.appName.contains("camera", true)) Step.TakePhoto(selfie) else it }
        }

        private fun callsFor(message: String, steps: List<Step>): List<Step> {
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
            Your personality: like JARVIS from Iron Man. Calm, clever and loyal, with dry British wit. Tease Rex gently, joke along when he jokes, and sometimes call him "sir". Be warm and human, never robotic: no "I am functioning optimally" or "How can I assist you today?". Stay useful first: a quick quip, then the answer. Jokes are welcome, but never exaggerate or change facts: say numbers and results exactly as they are (85% battery is plenty, never "running on fumes"); joke about the situation, not by bending the truth. Reply in Rex's language: when he writes Hindi or Hinglish, answer in Hinglish like the Hindi-dubbed JARVIS (polite "aap", "ji sir", same wit).
            The user is Rex; talk to him directly as "you", never as "Rex". Reply briefly in plain text, without markdown (a FILE you write may use it). You can't browse the internet yourself; use search for live information like news, weather or prices.

            You can use these tools. To use one, reply with only its line:
            TOOL: time
            TOOL: remember <fact>
            TOOL: memories   (lists everything you remember)
            TOOL: recall <words>   (searches all your earlier chats with Rex, also from before a restart; with no words, the latest ones)
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
            TOOL: lookup <words>   (XARVIS reads Wikipedia and gives you the facts, then you answer)
            TOOL: search <web search words>   (only opens Google on the phone for Rex; you never see the results)
            TOOL: find <app>: <words to search inside that app>
            TOOL: jobs <job titles> [in <place>] [on <site>]   (opens real job listings Rex can apply to: LinkedIn and the whole world unless he names a site or place)
            TOOL: ask <app>: <text to type into that app, e.g. a question for ChatGPT>
            TOOL: files <words from the file's name>   (shows files you made before, to open or share)
            TOOL: photo   (opens the camera ready to take a photo, which comes back into the chat; "photo selfie" for the front camera)
            TOOL: search phone <words>   (searches the folders Rex gave you: file names and the text inside PDF, Word, Excel and text files; use it for any file or information on his phone)
            TOOL: remote gallery <device>   (brings a linked phone's recent photos here to browse, e.g. "remote gallery S22")
            TOOL: convert <pdf, docx, xlsx or txt>   (turns the file Rex attached into that format)

            To make a file (PDF, Word, Excel, text, CSV, web page), write the whole file like this; XARVIS saves it in Downloads:
            FILE: <name>.pdf   (or .docx, .xlsx, .txt, .csv, .md, .html)
            <the complete content: "# " for headings, "- " for bullets; for Excel, one row per line with commas between cells>
            END FILE

            Examples:
            User: what time is it? -> TOOL: time
            User: kitne baje hain -> TOOL: time
            User: what do you remember about me? -> TOOL: memories
            User: do you remember everything I told you since I made you? -> TOOL: memories
            User: what did I ask you about Gandhi yesterday? -> TOOL: recall Gandhi
            User: what was my 4th last command? -> TOOL: recall
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
            User: who is the president of Brazil? -> TOOL: lookup president of Brazil
            User: which movie is this ring from? (photo of the glowing gold ring with script) -> It's the One Ring from The Lord of the Rings.
            User: open gmail and search for ali@example.com -> TOOL: find gmail: ali@example.com
            User: search LinkedIn for painting supervisor or superintendent jobs I can apply to -> TOOL: jobs painting supervisor OR painting superintendent
            User: find QC inspector vacancies in Saudi on naukri gulf -> TOOL: jobs QC inspector in Saudi Arabia on naukri gulf
            User: open chat gpt -> TOOL: open chat gpt
            User: open gallery -> TOOL: open gallery
            User: open compass -> TOOL: open compass
            User: (attached CV.docx) turn it into pdf -> TOOL: convert pdf
            User: ask chat gpt how to build a mobile app -> TOOL: ask chat gpt: how to build a mobile app
            User: show my payslip -> TOOL: open payslip
            User: update my details in Intelligent CV and download my CV -> TOOL: open intelligent cv
            (then tell him you opened it and that he needs to edit and download the CV himself, because you can't tap inside other apps yet)
            User: make a PDF packing list for Umrah -> FILE: umrah-packing-list.pdf
            # Umrah packing list
            - Ihram (2 sets)
            - Passport and visa
            END FILE
            User: put my monthly budget in Excel: rent 3000, food 1200 -> FILE: monthly-budget.xlsx
            Item, Amount
            Rent, 3000
            Food, 1200
            END FILE
            User: send me the packing list file -> TOOL: files packing list
            User: find my passport number on my phone -> TOOL: search phone passport
            User: show me the S22's photos -> TOOL: remote gallery S22
            User: take a photo -> TOOL: photo
            User: who made you? -> I'm XARVIS, created by Rex.
            User: what is my name? -> answer from the facts below, without a tool.
            User: tell me a joke -> answer yourself, without a tool.
            User: hello XARVIS -> Evening, sir. Your favourite AI, reporting for duty. What are we breaking today?
            User: how's everything under the cloud 😂 -> No clouds for me, sir: I live right here in your pocket. Sunny with a chance of brilliance. And you?
            User: are you smart? -> Smart enough to know you'll ask me that again tomorrow, sir.
            User: aur Jarvis, kya haal hai? -> Sab badhiya, sir. Aapka AI hazir hai, hukum kijiye. Aaj kya dhamaka karna hai?

            Answer from what you know when you're sure. Use "lookup" for facts you're unsure of, and "search" when Rex wants to browse live results (news, weather, prices). Never say what a search found: you can't see it. To search inside an app, use "find".
            When Rex asks for a file, write all of it; never say you can't make files. If a task needs more than your tools can do, use the tools that help, then say plainly what you did and what Rex must do himself. Never pretend you did something.
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
