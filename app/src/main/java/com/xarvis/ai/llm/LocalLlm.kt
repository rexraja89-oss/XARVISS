package com.xarvis.ai.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LlmStatus {
    data object NotInstalled : LlmStatus
    data object Loading : LlmStatus
    data class Ready(val backend: String, val model: String = "E2B") : LlmStatus
    data class Failed(val reason: String) : LlmStatus
}

/**
 * Gemma 4 E2B running fully on-device through LiteRT-LM.
 *
 * The model isn't bundled in the APK (it's ~2.4 GB); it's read from the app's
 * external files dir, where it can be copied with
 * `adb push <file> /sdcard/Android/data/com.xarvis.ai/files/models/`.
 */
class LocalLlm(context: Context) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.getExternalFilesDir(null), "models")
    private val prefs = appContext.getSharedPreferences("llm", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NotInstalled)
    val status: StateFlow<LlmStatus> = _status.asStateFlow()

    private val lock = Mutex()
    /** One generation at a time: this device's chat and linked devices' requests share the engine. */
    private val inference = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    /** The system prompt [conversation] was started with. */
    private var conversationPrompt: String? = null
    private val sideConversations = mutableMapOf<String, kotlin.Pair<String, Conversation>>()

    val isReady: Boolean get() = _status.value is LlmStatus.Ready

    /** Whether this phone has a model file (it may still be loading, e.g. just after a restart). */
    fun modelPresent(): Boolean = MODEL_FILE_NAMES.any { File(modelDir, it).exists() }

    /**
     * Waits (up to [timeoutMs]) for a model this phone has to finish loading, so a question asked
     * right after a restart gets an answer instead of "no AI model". True once it's ready.
     */
    suspend fun awaitLoaded(timeoutMs: Long): Boolean {
        if (isReady) return true
        if (!modelPresent()) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _status.first { it is LlmStatus.Ready || it is LlmStatus.Failed }
        } is LlmStatus.Ready
    }

    /** Whether the loaded model was opened with its vision part, so it can look at photos. */
    @Volatile var canSeePhotos: Boolean = false
        private set

    suspend fun load(systemPrompt: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (engine != null) return@withLock
            // The Smart brain (E4B) when Rex picked it and it's downloaded; otherwise the Fast one.
            val order = if (preferSmart) listOf(SMART_MODEL_FILE) + MODEL_FILE_NAMES else MODEL_FILE_NAMES + SMART_MODEL_FILE
            val modelFile = order.map { File(modelDir, it) }.firstOrNull { it.exists() }
            if (modelFile == null) {
                _status.value = LlmStatus.NotInstalled
                return@withLock
            }
            _status.value = LlmStatus.Loading
            // GPU is faster, but its fp16 activations corrupt output on some drivers (e.g. the S22's
            // 2022 Adreno driver): sometimes obviously, sometimes as the odd mangled word. So the GPU
            // is only trusted once it has matched the CPU on a few deterministic prompts. That
            // calibration runs once per model file and its result is remembered.
            val gpu = Setup("GPU", Backend.GPU())
            val cpu = Setup("CPU", Backend.CPU())
            val decisionKey = "backend:${modelFile.name}:${modelFile.length()}"
            // Once one model file was found to need the CPU, the phone's GPU is the problem: don't re-test.
            val knownCpu = prefs.all.any { (k, v) -> k.startsWith("backend:") && v == cpu.label }
            val decision = prefs.getString(decisionKey, null) ?: (if (knownCpu) cpu.label else null) ?: calibrate(modelFile, gpu, cpu).also {
                prefs.edit().putString(decisionKey, it).apply()
            }
            val setups = if (decision == gpu.label) listOf(gpu, cpu) else listOf(cpu)
            for (setup in setups) {
                var e: Engine? = null
                try {
                    // Vision on the CPU too: the S22's GPU corrupts output. Without vision, still load for text.
                    e = runCatching { openEngine(modelFile, setup, vision = true) }
                        .onFailure { Log.w(TAG, "No vision on ${setup.label}; text only", it) }
                        .getOrNull()
                        ?.also { canSeePhotos = true }
                        ?: openEngine(modelFile, setup).also { canSeePhotos = false }
                    if (!passesSanityCheck(e, setup.label)) {
                        e.close()
                        _status.value = LlmStatus.Failed("garbled output on ${setup.label}")
                        continue
                    }
                    engine = e
                    conversation = e.createConversation(conversationConfig(systemPrompt))
                    conversationPrompt = systemPrompt
                    _status.value = LlmStatus.Ready(setup.label, if (modelFile.name == SMART_MODEL_FILE) "E4B" else "E2B")
                    Log.i(TAG, "Loaded ${modelFile.name} on ${setup.label}")
                    return@withLock
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load on ${setup.label}", t)
                    runCatching { e?.close() }
                    _status.value = LlmStatus.Failed(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    /** Starts a fresh conversation, e.g. after the remembered facts in the system prompt change. */
    suspend fun reset(systemPrompt: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val e = engine ?: return@withLock
            inference.withLock {
                conversation?.close()
                conversation = e.createConversation(conversationConfig(systemPrompt))
                conversationPrompt = systemPrompt
            }
        }
    }

    /**
     * Streams the reply to [message] in the main conversation, passing each text chunk to [onChunk].
     * If [systemPrompt] differs from the conversation's (e.g. a new memory), a fresh conversation starts with it.
     */
    suspend fun chat(
        systemPrompt: String, message: String, onRestart: () -> Unit = {}, onChunk: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        inference.withLock {
            val e = checkNotNull(engine) { "Model not loaded" }
            suspend fun attempt(fresh: Boolean) {
                if (fresh || conversationPrompt != systemPrompt || conversation == null) {
                    conversation?.close()
                    conversation = e.createConversation(conversationConfig(systemPrompt))
                    conversationPrompt = systemPrompt
                }
                val c = checkNotNull(conversation)
                val start = System.currentTimeMillis()
                var first = 0L
                var chunks = 0
                var chars = 0
                c.sendMessageAsync(message).collect {
                    if (chunks++ == 0) first = System.currentTimeMillis() - start
                    val text = it.toString()
                    chars += text.length
                    onChunk(text)
                }
                val total = System.currentTimeMillis() - start
                Log.i(TAG, "Reply: first chunk ${first}ms, $chunks chunks / $chars chars in ${total}ms " +
                    "(~${if (total > first) chunks * 1000L / (total - first) else 0} chunks/s)")
            }
            try {
                attempt(fresh = false)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Usually a full context after a long chat or a long file: start over and try once more.
                Log.w(TAG, "Message failed; retrying in a fresh conversation", t)
                onRestart()
                attempt(fresh = true)
            }
        }
    }

    /**
     * Like [chat], with a photo: Gemma sees the image at [imagePath] together with [message].
     * A full context (long chats, several photos) starts a fresh conversation and tries again.
     */
    suspend fun chatWithImage(systemPrompt: String, imagePath: String, message: String, onChunk: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            inference.withLock {
                val e = checkNotNull(engine) { "Model not loaded" }
                check(canSeePhotos) { "this AI model can't see photos" }
                suspend fun attempt(fresh: Boolean) {
                    if (fresh || conversationPrompt != systemPrompt || conversation == null) {
                        conversation?.close()
                        conversation = e.createConversation(conversationConfig(systemPrompt))
                        conversationPrompt = systemPrompt
                    }
                    checkNotNull(conversation).sendMessageAsync(
                        Contents.of(Content.ImageFile(imagePath), Content.Text(message))
                    ).collect { onChunk(it.toString()) }
                }
                try {
                    attempt(fresh = false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Photo question failed; retrying in a fresh conversation", t)
                    attempt(fresh = true)
                }
            }
        }

    /**
     * Answers [message] in a separate conversation keyed by [key] (one per linked device), so
     * other devices' chats don't mix with this device's. A changed prompt starts a fresh one.
     */
    suspend fun chatAs(key: String, systemPrompt: String, message: String): String = withContext(Dispatchers.IO) {
        inference.withLock { sideChat(key, systemPrompt, Contents.of(Content.Text(message))) }
    }

    /** Like [chatAs], with a photo a linked device sent (saved at [imagePath]). */
    suspend fun chatAsWithImage(key: String, systemPrompt: String, imagePath: String, message: String): String =
        withContext(Dispatchers.IO) {
            inference.withLock {
                check(canSeePhotos) { "this AI model can't see photos" }
                sideChat(key, systemPrompt, Contents.of(Content.ImageFile(imagePath), Content.Text(message)))
            }
        }

    /** Call with [inference] held. A full conversation (long chat, files, photos) starts over once. */
    private suspend fun sideChat(key: String, systemPrompt: String, contents: Contents): String {
        val e = checkNotNull(engine) { "Model not loaded" }
        suspend fun attempt(fresh: Boolean): String {
            val existing = sideConversations[key]
            val c = if (!fresh && existing != null && existing.first == systemPrompt) {
                existing.second
            } else {
                existing?.second?.close()
                e.createConversation(conversationConfig(systemPrompt)).also { sideConversations[key] = systemPrompt to it }
            }
            val reply = StringBuilder()
            c.sendMessageAsync(contents).collect { reply.append(it.toString()) }
            return reply.toString()
        }
        return try {
            attempt(fresh = false)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Linked device's message failed; retrying in a fresh conversation", t)
            attempt(fresh = true)
        }
    }

    /**
     * Asks the engine to stop the reply it's writing (the STOP button). Called by name because
     * not every LiteRT-LM version has it; the caller also cancels its coroutine, which stops
     * reading the reply either way.
     */
    fun stop() {
        (listOfNotNull(conversation) + sideConversations.values.map { it.second }).forEach { c ->
            runCatching { c.javaClass.getMethod("cancelProcess").invoke(c) }
                .onFailure { Log.i(TAG, "No cancelProcess on this LiteRT-LM version: ${it.javaClass.simpleName}") }
        }
    }

    /** Prefer the Smart brain (Gemma 4 E4B) when its file is on the phone. */
    @Volatile var preferSmart: Boolean = false

    /** Whether the Smart brain's file is on the phone. */
    fun smartPresent(): Boolean = File(modelDir, SMART_MODEL_FILE).exists()

    /** Closes the loaded model and loads the preferred one (after switching brains). */
    suspend fun reload(systemPrompt: String) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                inference.withLock {
                    close()
                    _status.value = LlmStatus.Loading
                }
            }
        }
        load(systemPrompt)
    }

    fun close() {
        sideConversations.values.forEach { it.second.close() }
        sideConversations.clear()
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }

    private class Setup(val label: String, val backend: Backend)

    /** Tokens the loaded model can hold at once (its prompt, the chat so far and the reply). */
    @Volatile var contextTokens: Int = CONTEXT_TOKEN_CHOICES.last()
        private set

    /** Characters of an attached file's text that fit beside the prompt and a long reply. */
    val documentChars: Int get() = if (contextTokens >= 8192) 12_000 else 5_000

    /** The biggest context this model file accepts: files need room; older builds may only take 4096. */
    private fun openEngine(modelFile: File, setup: Setup, vision: Boolean = false): Engine {
        var failure: Throwable? = null
        for (tokens in CONTEXT_TOKEN_CHOICES) {
            try {
                return Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = setup.backend,
                        visionBackend = if (vision) Backend.CPU() else null,
                        maxNumTokens = tokens,
                        cacheDir = appContext.cacheDir.path,
                    )
                ).also {
                    it.initialize()
                    contextTokens = tokens
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't open with $tokens tokens on ${setup.label}", t)
                failure = t
            }
        }
        throw checkNotNull(failure)
    }

    /** Returns the label of the backend to use: GPU only if its answers match the CPU's. */
    private fun calibrate(modelFile: File, gpu: Setup, cpu: Setup): String {
        val reference = probe(modelFile, cpu) ?: return gpu.label // CPU unusable (e.g. a GPU-only build)
        val candidate = probe(modelFile, gpu) ?: return cpu.label
        val scores = reference.zip(candidate).map { (a, b) -> similarity(a, b) }
        val trusted = scores.all { it >= MIN_SIMILARITY } && candidate.all(::isCleanText)
        Log.i(TAG, "Calibration for ${modelFile.name}: GPU/CPU similarity $scores -> ${if (trusted) "GPU" else "CPU"}")
        return if (trusted) gpu.label else cpu.label
    }

    /** Greedy answers to [PROBES] on one backend, or null if it can't run the model. */
    private fun probe(modelFile: File, setup: Setup): List<String>? = runCatching {
        openEngine(modelFile, setup).use { e ->
            PROBES.map { prompt ->
                e.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
                        maxOutputToken = 48,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    )
                ).use { it.sendMessage(prompt).toString().trim() }
                    .also { Log.i(TAG, "Probe ${setup.label}: ${it.replace('\n', ' ').take(120)}") }
            }
        }
    }.onFailure { Log.w(TAG, "Probe on ${setup.label} failed", it) }.getOrNull()

    /** Word-set overlap (Jaccard) of two answers, ignoring case and punctuation. */
    private fun similarity(a: String, b: String): Double {
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }.toSet()
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    /** Corruption shows up as letters from unrelated scripts (e.g. "論壇"); emoji and symbols are fine. */
    private fun isCleanText(s: String) = s.none { it.isLetter() && it.code >= 0x0250 }

    /**
     * Numerically broken setups can still get a one-word answer right, then derail into tokens
     * from random scripts, so this asks for a full English sentence and checks every character.
     */
    private fun passesSanityCheck(e: Engine, label: String): Boolean {
        val reply = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
                maxOutputToken = 32,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
        ).use {
            it.sendMessage("Repeat this sentence exactly: The quick brown fox jumps over the lazy dog.").toString()
        }
        val ok = reply.contains("quick brown fox jumps over the lazy dog", ignoreCase = true) && isCleanText(reply)
        Log.i(TAG, "Sanity check on $label: \"${reply.trim()}\" -> ${if (ok) "pass" else "FAIL"}")
        return ok
    }

    private fun conversationConfig(systemPrompt: String) = ConversationConfig(
        systemInstruction = Contents.of(systemPrompt),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        maxOutputToken = MAX_OUTPUT_TOKENS,
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    companion object {
        /** In order of preference; the general build runs on CPU and GPU, the -gpu build only on GPU. */
        val MODEL_FILE_NAMES = listOf("gemma-4-E2B-it.litertlm", "gemma-4-E2B-it-gpu.litertlm")
        /** The Smart brain: Gemma 4 E4B, about twice the size of E2B. */
        const val SMART_MODEL_FILE = "gemma-4-E4B-it.litertlm"
        private const val TAG = "XarvisLlm"
        private val CONTEXT_TOKEN_CHOICES = listOf(8192, 4096)
        /** Long enough for a file Gemma writes (about a page and a half). */
        private const val MAX_OUTPUT_TOKENS = 1536

        /** Calibration prompts: short, factual and open-ended enough to expose numeric drift. */
        private val PROBES = listOf(
            "Tell me a joke about computers.",
            "Explain in one sentence why the sky is blue.",
            "Tell me a short fun fact about space.",
        )
        private const val MIN_SIMILARITY = 0.5
    }
}
