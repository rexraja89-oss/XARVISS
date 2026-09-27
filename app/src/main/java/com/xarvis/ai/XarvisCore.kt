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
    /** A newer XARVIS on GitHub ("1.0.46"), shown as an UPDATE button. */
    val update: String? = null,
)

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

    private val agent: XarvisAgent =
        XarvisAgent(WorkflowEngine(context, device, link, memorySync, contacts, FileStore(context)), memory, llm, link)

    private val _state: MutableStateFlow<XarvisUiState> = MutableStateFlow(
        XarvisUiState(
            messages = listOf(ChatMessage(false, WELCOME)),
            capabilities = device.capabilities(),
            linkedCount = link.pairedPeers().size,
            speakReplies = settings.getBoolean("speakReplies", false),
            wakeWord = settings.getBoolean("wakeWord", false),
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
        scope.launch { link.events.collect(::post) }
        scope.launch { agent.loadModel() }
        downloader.resume()
        scope.launch {
            // Check for a newer XARVIS now and then (it's one small request).
            while (true) {
                checkForUpdate()
                delay(UPDATE_CHECK_INTERVAL_MS)
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

    /** The reply being worked on, so STOP can cancel it. */
    private var replyJob: Job? = null

    /** The STOP button: XARVIS stops writing, and keeps what it wrote so far. */
    fun stop() {
        voice.stop()
        llm.stop()
        replyJob?.cancel()
    }

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
    }

    @Volatile private var lastUpdateCheck = 0L

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

    }
}
