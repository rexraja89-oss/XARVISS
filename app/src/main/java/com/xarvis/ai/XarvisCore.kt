package com.xarvis.ai

import android.content.Context
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.device.Capability
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.llm.ModelDownload
import com.xarvis.ai.llm.ModelDownloader
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.tools.ContactFinder
import com.xarvis.ai.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ChatMessage(val fromUser: Boolean, val text: String)

data class XarvisUiState(
    val messages: List<ChatMessage> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val memoryCount: Int = 0,
    val linkedCount: Int = 0,
    val isProcessing: Boolean = false,
    val llmStatus: LlmStatus = LlmStatus.NotInstalled,
    val modelDownload: ModelDownload = ModelDownload.Idle,
)

/**
 * Everything that should outlive the screen: the loaded model, the device-link server and
 * the chat. It lives as long as the app process, so pressing Back or rotating doesn't drop
 * linked devices or reload the ~2.4 GB model.
 */
class XarvisCore(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val memory = MemorySystem(context)
    private val device = DeviceCapabilityManager(context)
    private val llm = LocalLlm(context)
    private val downloader = ModelDownloader(context) { agent.loadModel() }

    private val contacts = ContactFinder(context)

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

        override suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String): String? =
            agent.answerForPeer(peerId, facts, devices, text)

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
        _state.update { it.copy(memoryCount = memory.count()) }
    }

    private val agent: XarvisAgent =
        XarvisAgent(WorkflowEngine(context, device, link, memorySync, contacts), memory, llm, link)

    private val _state: MutableStateFlow<XarvisUiState> = MutableStateFlow(
        XarvisUiState(
            messages = listOf(ChatMessage(false, "XARVIS online. Ask me anything.")),
            capabilities = device.capabilities(),
            linkedCount = link.pairedPeers().size,
        )
    )
    val state: StateFlow<XarvisUiState> = _state.asStateFlow()

    init {
        link.start()
        scope.launch { _state.update { it.copy(memoryCount = memory.count()) } }
        scope.launch { llm.status.collect { s -> _state.update { it.copy(llmStatus = s) } } }
        scope.launch { downloader.state.collect { d -> _state.update { it.copy(modelDownload = d) } } }
        scope.launch { link.events.collect(::post) }
        scope.launch { agent.loadModel() }
        downloader.resume()
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

    fun submit(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _state.value.isProcessing) return
        var replyIndex = 0
        _state.update {
            replyIndex = it.messages.size + 1
            it.copy(
                messages = it.messages + ChatMessage(true, text) + ChatMessage(false, ""),
                isProcessing = true,
            )
        }
        scope.launch {
            val reply = runCatching { agent.handle(text) { partial -> replaceMessage(replyIndex, partial) } }
                .getOrElse { "Something went wrong: ${it.message}" }
            replaceMessage(replyIndex, reply)
            _state.update {
                it.copy(
                    capabilities = device.capabilities(),
                    memoryCount = memory.count(),
                    linkedCount = link.pairedPeers().size,
                    isProcessing = false,
                )
            }
        }
    }

    /** Refreshes things that can change while the screen is away, like battery level. */
    fun refresh() {
        _state.update { it.copy(capabilities = device.capabilities()) }
    }

    private fun post(text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(false, text)) }
    }

    private fun replaceMessage(index: Int, text: String) {
        _state.update { s ->
            s.copy(messages = s.messages.mapIndexed { i, m -> if (i == index) m.copy(text = text) else m })
        }
    }

    private companion object {
        const val MEMORY_SYNC_START_DELAY_MS = 5_000L
        const val MEMORY_SYNC_INTERVAL_MS = 5 * 60_000L
    }
}
