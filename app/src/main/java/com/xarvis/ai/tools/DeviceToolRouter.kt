package com.xarvis.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Something on the phone XARVIS can read when a message asks about it: location now,
 * battery, time, Bluetooth or contacts later. To add one, implement this and list it in
 * [DeviceToolRouter]'s tools in XarvisCore.
 */
interface DeviceTool {
    /** Shown while the tool runs: "Checking your <label>…". */
    val label: String

    /** Whether [message] asks about what this tool reads. Keep it cheap: it runs on every message. */
    fun matches(message: String): Boolean

    /**
     * Reads the data as one line for the model, e.g. "Current location: ...". When the data
     * can't be read, say why in the line instead of throwing, so the model can explain it.
     */
    suspend fun read(): String
}

/**
 * Runs before a free-form message goes to the language model: every tool whose topic the
 * message mentions reads live data, which is put in front of the message as
 * `[DEVICE DATA]` lines so the model answers with real values instead of guessing.
 */
class DeviceToolRouter(private val tools: List<DeviceTool>) {

    /** Data lines for [message] (empty if no tool matches); [onProgress] shows what's being read. */
    suspend fun gather(message: String, onProgress: (String) -> Unit = {}): List<String> {
        val matched = tools.filter { it.matches(message) }
        if (matched.isEmpty()) return emptyList()
        onProgress("Checking your ${matched.joinToString(" and ") { it.label }}…")
        return coroutineScope {
            matched.map { tool ->
                async {
                    runCatching { tool.read() }
                        .getOrElse { "${tool.label.replaceFirstChar { it.uppercase() }}: unavailable (${it.message})" }
                }
            }.awaitAll()
        }
    }

    companion object {
        const val TAG = "[DEVICE DATA]"

        /** The message as the model sees it: data lines first, then what the user typed. */
        fun withDeviceData(message: String, data: List<String>): String =
            if (data.isEmpty()) message else data.joinToString("\n") { "$TAG $it" } + "\n\n" + message
    }
}
