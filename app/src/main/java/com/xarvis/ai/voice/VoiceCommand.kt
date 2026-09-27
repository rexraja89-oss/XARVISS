package com.xarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens for one spoken command with the phone's speech recognizer, without showing anything:
 * used after "Hey Jarvis" (screen off) and by the voice screen. Create and use it on the main thread.
 */
class VoiceCommand(
    private val context: Context,
    private val onHearing: (String) -> Unit = {},
    private val onDone: (String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "No speech recognizer on this phone")
            finish(null)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) = finish(best(results))
            override fun onPartialResults(partial: Bundle?) {
                best(partial)?.let(onHearing)
            }
            override fun onError(error: Int) {
                Log.i(TAG, "Recognizer error $error")
                finish(null)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )
    }

    fun cancel() = finish(null)

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun finish(text: String?) {
        if (finished) return
        finished = true
        runCatching { recognizer?.destroy() }
        recognizer = null
        onDone(text)
    }

    private companion object {
        const val TAG = "XarvisVoiceCommand"
    }
}
