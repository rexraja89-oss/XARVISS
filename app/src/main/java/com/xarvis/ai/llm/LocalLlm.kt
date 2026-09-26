package com.xarvis.ai.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LlmStatus {
    data object NotInstalled : LlmStatus
    data object Loading : LlmStatus
    data class Ready(val backend: String) : LlmStatus
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
    private val sideConversations = mutableMapOf<String, kotlin.Pair<String, Conversation>>()

    val isReady: Boolean get() = _status.value is LlmStatus.Ready

    suspend fun load(systemPrompt: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (engine != null) return@withLock
            val modelFile = MODEL_FILE_NAMES.map { File(modelDir, it) }.firstOrNull { it.exists() }
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
            val decision = prefs.getString(decisionKey, null) ?: calibrate(modelFile, gpu, cpu).also {
                prefs.edit().putString(decisionKey, it).apply()
            }
            val setups = if (decision == gpu.label) listOf(gpu, cpu) else listOf(cpu)
            for (setup in setups) {
                var e: Engine? = null
                try {
                    e = openEngine(modelFile, setup)
                    if (!passesSanityCheck(e, setup.label)) {
                        e.close()
                        _status.value = LlmStatus.Failed("garbled output on ${setup.label}")
                        continue
                    }
                    engine = e
                    conversation = e.createConversation(conversationConfig(systemPrompt))
                    _status.value = LlmStatus.Ready(setup.label)
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
            }
        }
    }

    /** Streams the reply to [message] in the main conversation, passing each text chunk to [onChunk]. */
    suspend fun chat(message: String, onChunk: (String) -> Unit) = withContext(Dispatchers.IO) {
        inference.withLock {
            val c = checkNotNull(conversation) { "Model not loaded" }
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
    }

    /**
     * Answers [message] in a separate conversation keyed by [key] (one per linked device), so
     * other devices' chats don't mix with this device's. A changed prompt starts a fresh one.
     */
    suspend fun chatAs(key: String, systemPrompt: String, message: String): String = withContext(Dispatchers.IO) {
        inference.withLock {
            val e = checkNotNull(engine) { "Model not loaded" }
            val existing = sideConversations[key]
            val c = if (existing != null && existing.first == systemPrompt) {
                existing.second
            } else {
                existing?.second?.close()
                e.createConversation(conversationConfig(systemPrompt)).also { sideConversations[key] = systemPrompt to it }
            }
            val reply = StringBuilder()
            c.sendMessageAsync(message).collect { reply.append(it.toString()) }
            reply.toString()
        }
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

    private fun openEngine(modelFile: File, setup: Setup): Engine = Engine(
        EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = setup.backend,
            maxNumTokens = MAX_CONTEXT_TOKENS,
            cacheDir = appContext.cacheDir.path,
        )
    ).also { it.initialize() }

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
        private const val TAG = "XarvisLlm"
        private const val MAX_CONTEXT_TOKENS = 4096
        private const val MAX_OUTPUT_TOKENS = 512

        /** Calibration prompts: short, factual and open-ended enough to expose numeric drift. */
        private val PROBES = listOf(
            "Tell me a joke about computers.",
            "Explain in one sentence why the sky is blue.",
            "Tell me a short fun fact about space.",
        )
        private const val MIN_SIMILARITY = 0.5
    }
}
