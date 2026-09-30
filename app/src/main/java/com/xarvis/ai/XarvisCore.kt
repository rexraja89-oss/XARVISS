package com.xarvis.ai

import android.content.Context
import com.xarvis.ai.agent.Reply
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.files.DocumentReader
import com.xarvis.ai.files.FileStore
import com.xarvis.ai.files.SavedFile
import com.xarvis.ai.files.UnreadableFile
import com.xarvis.ai.device.Capability
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.llm.ModelDownload
import com.xarvis.ai.llm.ModelDownloader
import com.xarvis.ai.llm.PhotoPrep
import android.net.Uri
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.tools.ContactFinder
import com.xarvis.ai.update.Updates
import com.xarvis.ai.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One chat bubble. [imagePath] is a photo Rex sent with it, [attachment] the name of a file he
 * sent, and [files] the files XARVIS made or found (with OPEN and SHARE buttons).
 */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val imagePath: String? = null,
    val attachment: String? = null,
    val files: List<SavedFile> = emptyList(),
    val id: Long = nextMessageId.incrementAndGet(),
)

private val nextMessageId = java.util.concurrent.atomic.AtomicLong()

data class XarvisUiState(
    val messages: List<ChatMessage> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val memoryCount: Int = 0,
    val linkedCount: Int = 0,
    val isProcessing: Boolean = false,
    val llmStatus: LlmStatus = LlmStatus.NotInstalled,
    val modelDownload: ModelDownload = ModelDownload.Idle,
    /** Past chats for the ☰ menu, most recent first, and the one on screen. */
    val chats: List<com.xarvis.ai.memory.ChatSummary> = emptyList(),
    val chatId: String = "",
    /** Read every reply aloud (the 🔊 switch); replies to the mic are always spoken. */
    val speakReplies: Boolean = false,
    /** "Hey Jarvis" listening is switched on. */
    val wakeWord: Boolean = false,
    /** The Smart brain (Gemma 4 E4B) is chosen, and its download when it's being fetched. */
    val smartBrain: Boolean = false,
    val smartDownload: ModelDownload = ModelDownload.Idle,
    /** A newer XARVIS on GitHub ("1.0.46"), shown as an UPDATE button. */
    val update: String? = null,
    /** An action waiting for Rex's OK (its category is set to "Ask me"). */
    val ask: PendingAsk? = null,
    /** The camera should open (TOOL: photo); the screen opens it and clears this. */
    val camera: CameraRequest? = null,
    /** Tailscale is installed but not connected: the screen shows TURN ON TAILSCALE. */
    val tailscaleOff: Boolean = false,
)

data class CameraRequest(val selfie: Boolean, val id: Long = System.nanoTime())

data class PendingAsk(val category: com.xarvis.ai.policy.Category, val action: String)

/**
 * Everything that should outlive the screen: the loaded model, the device-link server and
 * the chat. It lives as long as the app process, so pressing Back or rotating doesn't drop
 * linked devices or reload the ~2.4 GB model.
 */
class XarvisCore(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val memory = MemorySystem(context)
    private val device = DeviceCapabilityManager(context)
    private val llm = LocalLlm(context)
    private val downloader = ModelDownloader(context) { agent.loadModel() }
    private val smartDownloader = ModelDownloader(context, LocalLlm.SMART_MODEL_FILE) {
        if (llm.preferSmart) agent.reloadModel()
    }

    private val contacts = ContactFinder(context)
    private val voice = com.xarvis.ai.voice.Voice(context)
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val link: DeviceLink = DeviceLink(context, object : DeviceLink.Handler {
        override suspend fun status(): String {
            val ai = when (val s = llm.status.value) {
                is LlmStatus.Ready -> "AI model: ready (${s.backend})"
                LlmStatus.Loading -> "AI model: loading"
                LlmStatus.NotInstalled -> "AI model: not installed"
                is LlmStatus.Failed -> "AI model: unavailable"
            }
            return device.summary() + "\n  " + ai
        }

        override suspend fun battery(): String = com.xarvis.ai.tools.BatteryTool(appContext).read()

        override suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String, history: String): String? =
            agent.answerForPeer(peerId, facts, devices, text, history)

        override suspend fun brainPhoto(peerId: String, facts: List<String>, jpeg: ByteArray, text: String): String? {
            val file = java.io.File(appContext.cacheDir, "photos/linked-${System.currentTimeMillis()}.jpg")
            return try {
                file.parentFile?.mkdirs()
                file.writeBytes(jpeg)
                agent.answerPhotoForPeer(peerId, facts, file.path, text)
            } finally {
                file.delete()
            }
        }

        override fun llmReady(): Boolean = llm.isReady

        override fun onNote(from: String, text: String) {
            post("Note from $from:\n$text")
        }

        override fun onPeersChanged() {
            scope.launch {
                agent.refreshPrompt()
                _state.update { it.copy(linkedCount = link.pairedPeers().size) }
                memorySync.syncAll()
            }
        }

        override suspend fun memorySnapshot(): JSONObject = memorySync.snapshot()
        override suspend fun memoryAdd(content: String, timestamp: Long) = memorySync.receiveFact(content, timestamp)
        override suspend fun memoryClear(timestamp: Long) = memorySync.receiveClear(timestamp)
        override suspend fun findContacts(name: String): List<String> = contacts.find(name).orEmpty()
    })

    private val memorySync: MemorySync = MemorySync(memory, link) {
        agent.refreshPrompt()
        _state.update { it.copy(memoryCount = memory.factCount()) }
    }

    /** Rex's permissions and the activity log (☰ → Permissions / Activity log). */
    val policy = com.xarvis.ai.policy.PolicyLayer(context)

    private val engine = WorkflowEngine(context, device, link, memorySync, contacts, FileStore(context), policy).also {
        it.confirm = ::askRex
        it.takePhoto = { selfie ->
            screenVisible.also { visible -> if (visible) _state.update { s -> s.copy(camera = CameraRequest(selfie)) } }
        }
    }

    /** Whether XARVIS's main screen is showing (only it can open the camera and get the photo back). */
    @Volatile var screenVisible = false

    /** The folders Rex lets XARVIS search (☰ → Search folders). */
    val phoneSearch = com.xarvis.ai.files.PhoneSearch(context)

    private val agent: XarvisAgent = XarvisAgent(engine, memory, llm, link, phoneSearch)

    private val _state: MutableStateFlow<XarvisUiState> = MutableStateFlow(
        XarvisUiState(
            messages = listOf(ChatMessage(false, WELCOME)),
            capabilities = device.capabilities(),
            linkedCount = link.pairedPeers().size,
            speakReplies = settings.getBoolean("speakReplies", false),
            wakeWord = settings.getBoolean("wakeWord", false),
            smartBrain = settings.getBoolean("smartBrain", false),
        )
    )
    val state: StateFlow<XarvisUiState> = _state.asStateFlow()

    init {
        link.start()
        scope.launch {
            _state.update { it.copy(memoryCount = memory.factCount()) }
        }
        scope.launch { llm.status.collect { s -> _state.update { it.copy(llmStatus = s) } } }
        scope.launch { downloader.state.collect { d -> _state.update { it.copy(modelDownload = d) } } }
        scope.launch { smartDownloader.state.collect { d -> _state.update { it.copy(smartDownload = d) } } }
        smartDownloader.resume()
        scope.launch { link.events.collect(::post) }
        llm.preferSmart = settings.getBoolean("smartBrain", false)
        scope.launch { agent.loadModel() }
        downloader.resume()
        scope.launch {
            // Check for a newer XARVIS now and then (it's one small request).
            while (true) {
                checkForUpdate()
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
        // Keep Tailscale on (Rex asked), so the linked phones reach each other anywhere.
        scope.launch {
            while (true) {
                keepTailscaleOn()
                delay(TAILSCALE_CHECK_INTERVAL_MS)
            }
        }
        scope.launch {
            delay(MEMORY_SYNC_START_DELAY_MS) // give mDNS a moment to find linked devices' current addresses
            while (true) {
                memorySync.syncAll()
                delay(MEMORY_SYNC_INTERVAL_MS)
            }
        }
    }

    /** Downloads the AI model onto this phone (the "Download AI model" button). */
    fun downloadModel() = downloader.start()

    /**
     * Handles a message, with a [photo] or a [document] if Rex attached one (an empty question
     * means "describe it" / "summarise it").
     */
    fun submit(command: String, photo: Uri? = null, document: Uri? = null, spoken: Boolean = false) {
        val text = command.trim()
        if ((text.isEmpty() && photo == null && document == null) || _state.value.isProcessing) return
        val shown = text.ifEmpty { if (photo != null) "What's in this photo?" else "What's in this file?" }
        val question = ChatMessage(true, shown)
        val answer = ChatMessage(false, "")
        _state.update {
            it.copy(
                messages = it.messages + question + answer,
                isProcessing = true,
            )
        }
        val onPartial = { partial: String -> replaceMessage(answer.id, partial) }
        replySpoken = spoken
        replyJob = scope.launch {
            val reply = try {
                when {
                    photo != null -> {
                        val file = PhotoPrep.prepare(appContext, photo)
                        updateMessage(question.id) { it.copy(imagePath = file.path) }
                        agent.handlePhoto(file.path, text, onPartial)
                    }
                    document != null -> {
                        updateMessage(question.id) { it.copy(attachment = DocumentReader.displayName(appContext, document)) }
                        onPartial("Reading the file…")
                        agent.handleDocument(DocumentReader.read(appContext, document), text, onPartial)
                    }
                    else -> agent.handle(text, onPartial)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // STOP: keep what XARVIS wrote so far.
                val sofar = _state.value.messages.firstOrNull { it.id == answer.id }?.text.orEmpty()
                Reply(if (sofar.isBlank()) "Stopped." else "$sofar\n\n(stopped)")
            } catch (e: UnreadableFile) {
                Reply(e.message ?: "I couldn't read that file.")
            } catch (e: Exception) {
                Reply("Something went wrong: ${e.message}")
            }
            updateMessage(answer.id) { it.copy(text = reply.text, files = reply.files) }
            if ((spoken || _state.value.speakReplies) && !reply.text.endsWith("(stopped)")) voice.speak(reply.text)
            // Also after STOP, which has cancelled this coroutine.
            withContext(NonCancellable) {
                val memories = runCatching { memory.factCount() }.getOrDefault(_state.value.memoryCount)
                _state.update {
                    it.copy(
                        capabilities = device.capabilities(),
                        memoryCount = memories,
                        linkedCount = link.pairedPeers().size,
                        isProcessing = false,
                    )
                }
            }
        }
    }

    private var askAnswer: kotlinx.coroutines.CompletableDeferred<com.xarvis.ai.policy.Answer>? = null
    @Volatile private var replySpoken = false

    /**
     * An "Ask me" action: shows the card (in the chat and on the voice screen) and waits up to
     * a minute for Rex's answer; a spoken request also hears the question.
     */
    private suspend fun askRex(category: com.xarvis.ai.policy.Category, action: String): com.xarvis.ai.policy.Answer {
        val answer = kotlinx.coroutines.CompletableDeferred<com.xarvis.ai.policy.Answer>()
        askAnswer = answer
        _state.update { it.copy(ask = PendingAsk(category, action)) }
        if (replySpoken) voice.speak("Shall I ${action.replaceFirstChar { it.lowercase() }}? Tap allow on the screen.")
        return try {
            kotlinx.coroutines.withTimeoutOrNull(ASK_WAIT_MS) { answer.await() } ?: com.xarvis.ai.policy.Answer.NO_ANSWER
        } finally {
            askAnswer = null
            _state.update { it.copy(ask = null) }
        }
    }

    /** Rex's tap on the Ask card. */
    fun answerAsk(answer: com.xarvis.ai.policy.Answer) {
        askAnswer?.complete(answer)
    }

    /** The reply being worked on, so STOP can cancel it. */
    private var replyJob: Job? = null

    /** The STOP button: XARVIS stops writing, and keeps what it wrote so far. */
    fun stop() {
        voice.stop()
        llm.stop()
        replyJob?.cancel()
    }

    /**
     * The brain switch: Smart (Gemma 4 E4B, downloaded the first time it's chosen) or Fast (E2B).
     * XARVIS switches as soon as the chosen one is on the phone.
     */
    fun setSmartBrain(on: Boolean) {
        if (_state.value.isProcessing) return
        settings.edit().putBoolean("smartBrain", on).apply()
        llm.preferSmart = on
        _state.update { it.copy(smartBrain = on) }
        when {
            on && !llm.smartPresent() -> smartDownloader.start()
            on || (llm.status.value as? LlmStatus.Ready)?.model == "E4B" -> scope.launch { agent.reloadModel() }
        }
    }

    /** The ☰ menu's voice buttons: next voice, with a sample; returns its label. */
    fun nextVoice(hindi: Boolean): String = voice.nextVoice(hindi) ?: "none on this phone"

    fun voiceLabel(hindi: Boolean): String = voice.currentLabel(hindi)

    /** "Load my voice": installs a voice file from the training page and says hello in it. */
    fun loadOwnVoice(uri: android.net.Uri, done: (String) -> Unit) {
        scope.launch {
            val msg = try {
                val hindi = kotlinx.coroutines.withContext(Dispatchers.IO) { com.xarvis.ai.voice.OwnVoice.install(appContext, uri) }
                com.xarvis.ai.voice.OwnVoice.setEnabled(appContext, true)
                voice.speak(if (hindi) "Namaste sir, main XARVIS hoon. Ab main aapki awaaz mein bol raha hoon." else "Hello sir. XARVIS here, speaking in your voice now.")
                if (hindi) "Your Hindi voice is loaded ✓" else "Your English voice is loaded ✓"
            } catch (e: Exception) {
                "Couldn't load that voice: ${e.message}"
            }
            done(msg)
        }
    }

    /** The "Speak in my voice" switch. */
    fun ownVoiceOn(): Boolean = com.xarvis.ai.voice.OwnVoice.enabled(appContext)

    fun setOwnVoice(on: Boolean) {
        voice.stop()
        com.xarvis.ai.voice.OwnVoice.setEnabled(appContext, on)
    }

    /** Which own voices are loaded, for the menu ("Hindi" / "English" / "Hindi + English"), or null. */
    fun ownVoices(): String? = listOfNotNull(
        "Hindi".takeIf { com.xarvis.ai.voice.OwnVoice.installed(appContext, true) },
        "English".takeIf { com.xarvis.ai.voice.OwnVoice.installed(appContext, false) },
    ).joinToString(" + ").ifEmpty { null }

    /** XARVIS is thinking or talking: the wake word waits. */
    fun busy(): Boolean = _state.value.isProcessing || voice.isSpeaking

    fun wakeWordChanged(on: Boolean) {
        _state.update { it.copy(wakeWord = on) }
    }

    /** The 🔊 switch: read every reply aloud, or only replies to the mic. */
    fun toggleSpeaker() {
        val on = !_state.value.speakReplies
        settings.edit().putBoolean("speakReplies", on).apply()
        if (!on) voice.stop()
        _state.update { it.copy(speakReplies = on) }
    }

    /** Reloads the chat list (when the ☰ menu opens). */
    fun loadChats() {
        scope.launch {
            val chats = runCatching { memory.chats() }.getOrDefault(emptyList())
            _state.update { it.copy(chats = chats, chatId = agent.chatId) }
        }
    }

    /** Opens past chat [id] on screen, or a new empty chat when null. XARVIS picks up where it left off. */
    fun openChat(id: String?) {
        if (_state.value.isProcessing) return
        scope.launch {
            agent.openChat(id)
            val past = if (id == null) emptyList() else runCatching { memory.chatExchanges(id) }.getOrDefault(emptyList())
            val messages = if (past.isEmpty()) listOf(ChatMessage(false, WELCOME))
                else past.flatMap { listOf(ChatMessage(true, it.user), ChatMessage(false, it.reply)) }
            _state.update { it.copy(messages = messages, chatId = agent.chatId) }
        }
    }

    /** Refreshes things that can change while the screen is away, like battery level. */
    fun refresh() {
        _state.update { it.copy(capabilities = device.capabilities()) }
        // Also each time XARVIS is opened: the always-on service keeps this process alive, so
        // "closing" the app doesn't restart it.
        if (System.currentTimeMillis() - lastUpdateCheck > MIN_UPDATE_CHECK_GAP_MS) scope.launch { checkForUpdate() }
        scope.launch { keepTailscaleOn() }
    }

    @Volatile private var lastUpdateCheck = 0L

    /** Asks Tailscale to reconnect when it's off, and shows the TURN ON button until it is. */
    private suspend fun keepTailscaleOn() {
        val t = com.xarvis.ai.net.Tailscale
        if (!t.installed(appContext)) return _state.update { it.copy(tailscaleOff = false) }
        if (!t.connected(appContext)) {
            t.requestConnect(appContext)
            delay(8_000)
        }
        val off = !t.connected(appContext)
        _state.update { it.copy(tailscaleOff = off) }
    }

    /** The screen has opened the camera. */
    fun cameraOpened() = _state.update { it.copy(camera = null) }

    /** The TURN ON TAILSCALE button. */
    fun turnOnTailscale() {
        com.xarvis.ai.net.Tailscale.requestConnect(appContext)
        com.xarvis.ai.net.Tailscale.open(appContext)
        scope.launch { delay(15_000); keepTailscaleOn() }
    }

    private suspend fun checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis()
        Updates.newerVersion()?.let { v -> _state.update { it.copy(update = v) } }
    }

    private fun post(text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(false, text)) }
    }

    private fun replaceMessage(id: Long, text: String) = updateMessage(id) { it.copy(text = text) }

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(messages = s.messages.map { m -> if (m.id == id) change(m) else m })
        }
    }

    private companion object {
        const val MEMORY_SYNC_START_DELAY_MS = 5_000L
        const val MEMORY_SYNC_INTERVAL_MS = 5 * 60_000L
        const val UPDATE_CHECK_INTERVAL_MS = 30 * 60_000L
        const val WELCOME = "XARVIS online. Ask me anything."
        const val MIN_UPDATE_CHECK_GAP_MS = 60_000L
        /** How long an "Ask me" card waits for Rex before the answer counts as no. */
        const val ASK_WAIT_MS = 60_000L
        const val TAILSCALE_CHECK_INTERVAL_MS = 2 * 60_000L

    }
}
