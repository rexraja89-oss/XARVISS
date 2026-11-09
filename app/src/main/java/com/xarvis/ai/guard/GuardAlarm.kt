package com.xarvis.ai.guard

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.xarvis.ai.hands.HandsBridge
import java.util.Locale

/**
 * The intruder alarm: XARVIS's British voice, deep and at alarm volume (even on silent), says "You are not
 * authorized to touch this device" over and over, listening between warnings for "It's alright XARVIS".
 * Hearing it, the guard screen asks for Rex's fingerprint; with it, XARVIS says "I thought it's a thief"
 * and laughs. The red screen keeps coming back if someone leaves it. The intruder's photo and the phone's
 * location go to the linked phone at once.
 */
object GuardAlarm {
    enum class Phase { IDLE, WARNING, LISTENING, FINGER, RELIEF }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var photo: Uri? = null
        private set
    /** The guard screen redraws itself on each change. */
    @Volatile var onChange: ((Phase) -> Unit)? = null

    private const val WARNING = "You are not authorized to touch this device."
    private const val LISTEN_MS = 6_000L

    private val ui = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    @Volatile var screenShowing = false

    fun start(context: Context, photoUri: Uri?, jpeg: ByteArray?) {
        val c = context.applicationContext
        app = c
        photo = photoUri
        HandsBridge.log("Guard mode: INTRUDER — alarm on.")
        alertLinkedPhone(c, jpeg)
        voice(c) { ui.post { warn() } }
    }

    private fun set(p: Phase) {
        phase = p
        onChange?.invoke(p)
    }

    // ---- the voice ----

    private fun voice(c: Context, ready: () -> Unit) {
        if (tts != null && ttsReady) {
            ready()
            return
        }
        val google = "com.google.android.tts".takeIf { runCatching { c.packageManager.getPackageInfo(it, 0) }.isSuccess }
        tts = TextToSpeech(c, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    // XARVIS's own British voice (Rex's pick in ☰, else a British male one).
                    val chosen = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("voiceEnglish", null)
                    val voices = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
                    val british = voices.filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
                    val pick = voices.firstOrNull { it.name == chosen }
                        ?: british.firstOrNull { it.name.contains("rjs", true) || (it.name.contains("male", true) && !it.name.contains("female", true)) }
                        ?: british.firstOrNull()
                    pick?.let { t.voice = it } ?: t.setLanguage(Locale.UK)
                    t.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "warn") ui.post { listen() }
                            if (utteranceId == "laugh-end") ui.post { finishRelief() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == "warn") ui.post { listen() }
                        }
                    })
                    ttsReady = true
                    ready()
                }
            }
        }, google)
    }

    private fun loud(c: Context) {
        runCatching {
            val audio = c.getSystemService(AudioManager::class.java)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    private fun say(text: String, id: String, pitch: Float, rate: Float, flush: Boolean) {
        val t = tts ?: return
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    /** One warning, deep and slow, then listen. */
    private fun warn() {
        val c = app ?: return
        if (phase == Phase.FINGER || phase == Phase.RELIEF) return
        set(Phase.WARNING)
        loud(c)
        if (!screenShowing) GuardMode.launch(c, "alarm") // someone left the red screen: bring it back
        say(WARNING, "warn", pitch = 0.5f, rate = 0.82f, flush = true)
    }

    // ---- listening for "It's alright XARVIS" ----

    private val listenTimeout = Runnable { heard(null) }

    private fun listen() {
        val c = app ?: return
        if (phase != Phase.WARNING) return
        set(Phase.LISTENING)
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            ui.postDelayed({ heard(null) }, 2_500)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(c)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) = heard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (partial?.any(::isPhrase) == true) heard(partial)
            }
            override fun onError(error: Int) = heard(null)
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
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
        )
        ui.postDelayed(listenTimeout, LISTEN_MS)
    }

    private fun heard(words: List<String>?) {
        if (phase != Phase.LISTENING) return
        ui.removeCallbacks(listenTimeout)
        recognizer?.let { r -> ui.post { runCatching { r.destroy() } } }
        recognizer = null
        val c = app ?: return
        if (words?.any(::isPhrase) == true) {
            HandsBridge.log("Guard mode: heard the phrase — asking for the fingerprint.")
            set(Phase.FINGER)
            GuardMode.launch(c, "finger")
        } else {
            ui.postDelayed({ warn() }, 300)
        }
    }

    private fun isPhrase(text: String): Boolean = com.xarvis.ai.agent.GuardCommands.isDisarmPhrase(text)

    // ---- the fingerprint, then relief ----

    /** Rex's fingerprint matched: alarm off, guard off, and a laugh. */
    fun fingerOk(context: Context) {
        val c = context.applicationContext
        ui.removeCallbacks(listenTimeout)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        GuardMode.disarm()
        set(Phase.RELIEF)
        HandsBridge.log("Guard mode: Rex's fingerprint — alarm off.")
        voice(c) {
            say("I thought it's a thief.", "relief", pitch = 1f, rate = 0.95f, flush = true)
            // A laugh in the same British voice, about four seconds.
            say("Ha ha ha ha!", "laugh1", pitch = 1.15f, rate = 1.3f, flush = false)
            say("Ha ha ha!", "laugh2", pitch = 1.05f, rate = 1.4f, flush = false)
            say("Hah... ha ha ha.", "laugh-end", pitch = 0.95f, rate = 1.2f, flush = false)
        }
    }

    /** No fingerprint (cancelled, wrong too often): back to the alarm. */
    fun fingerFailed() {
        if (phase != Phase.FINGER) return
        ui.postDelayed({ warn() }, 500)
        phase = Phase.WARNING
    }

    private fun finishRelief() {
        set(Phase.IDLE)
        photo = null
    }

    /** Stop everything at once (e.g. Rex disarmed from the guard screen another way). */
    fun stop() {
        ui.removeCallbacks(listenTimeout)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        tts?.stop()
        set(Phase.IDLE)
    }

    // ---- telling the other phone ----

    private fun alertLinkedPhone(c: Context, jpeg: ByteArray?) {
        val core = (c as? com.xarvis.ai.XarvisApp)?.core ?: return
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val link = core.deviceLink
                    val peer = link.reachable().firstOrNull()?.first ?: return@runBlocking
                    val time = java.text.SimpleDateFormat("d MMM, HH:mm:ss", Locale.US).format(java.util.Date())
                    val where = runCatching { com.xarvis.ai.tools.LocationTool(c).read() }.getOrDefault("location unknown")
                    link.sendNote(peer, "🚨 Someone touched your ${link.deviceName} at $time — it wasn't you. The alarm is on.\n$where")
                    if (jpeg != null) {
                        link.sendFile(peer, "intruder-" + System.currentTimeMillis() + ".jpg", "image/jpeg", jpeg)
                    }
                }
            }.onFailure { HandsBridge.log("Guard mode: couldn't alert the other phone: ${it.message}") }
        }.start()
    }
}
