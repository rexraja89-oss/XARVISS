package com.xarvis.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/** What the ears need from XARVIS: in the same app on the S22, over a private link from XARVIS Ears. */
interface WakeHost {
    /** What XARVIS is doing now: [IDLE], [THINKING], [SPEAKING] or [HOLD] (another XARVIS microphone is in use). */
    fun state(): Int

    /**
     * "Xarvis" was heard ([interrupting]: while XARVIS was talking). XARVIS answers aloud ("Yes, sir?");
     * [done] once it has said it — false when XARVIS couldn't be reached.
     */
    fun wake(interrupting: Boolean, done: (Boolean) -> Unit)

    /** Rex said [text]. XARVIS answers aloud; [done] once it has — true to keep listening for more. */
    fun heard(text: String, done: (Boolean) -> Unit)

    /** Rex said nothing (more): the conversation is over. */
    fun over()

    companion object {
        const val IDLE = 0
        const val THINKING = 1
        const val SPEAKING = 2
        const val HOLD = 3
    }
}

/** Rex's choices on the wake word screen, kept in the app that listens. */
class WakeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xarvis_wake", Context.MODE_PRIVATE)

    var sensitivity: Sensitivity
        get() = Sensitivity.of(prefs.getInt("sensitivity", Sensitivity.NORMAL.ordinal))
        set(value) = prefs.edit().putInt("sensitivity", value.ordinal).apply()

    /** After an answer, listen again without the wake word (a conversation), until Rex stops talking. */
    var followUp: Boolean
        get() = prefs.getBoolean("followUp", true)
        set(value) = prefs.edit().putBoolean("followUp", value).apply()

    /** "Xarvis" while XARVIS is talking stops it and gets its attention. */
    var bargeIn: Boolean
        get() = prefs.getBoolean("bargeIn", true)
        set(value) = prefs.edit().putBoolean("bargeIn", value).apply()
}

/**
 * XARVIS's ears: listens with the microphone, screen on or off, for "Xarvis" (offline). On hearing
 * it XARVIS answers "Yes, sir?", the ears hear what Rex says, XARVIS answers aloud, and they keep
 * listening for more until Rex says nothing — then it's back to waiting for "Xarvis". The
 * microphone is let go while XARVIS thinks, and while another XARVIS microphone is in use.
 */
class WakeEars(context: Context, private val host: WakeHost) {

    private val appContext = context.applicationContext
    private val settings = WakeSettings(appContext)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    /** A conversation is going on: the wake word waits. */
    @Volatile private var handling = false
    private var worker: Thread? = null
    private var turn: VoiceTurn? = null
    private var turns = 0
    private var step = 0
    private var tone: ToneGenerator? = null

    /** The wake word screen's practice: a "Xarvis" only beeps and counts, no conversation. */
    @Volatile var practice = false

    /** Every step's score (0..1), the sound level and whether the models ran, for the wake word screen. */
    @Volatile var onScore: ((score: Float, level: Float, awake: Boolean) -> Unit)? = null

    /** "Xarvis" was heard, with its score. */
    @Volatile var onHit: ((Float) -> Unit)? = null

    /** Android is giving this app silence instead of the microphone (it was started in the background). */
    @Volatile var onMicBlocked: (() -> Unit)? = null

    /** In a few words, for the notification and the wake word screen. */
    @Volatile var status: String = "Starting…"
        private set

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        current = this
        worker = thread(name = "xarvis-ears") { listenLoop() }
    }

    fun stop() {
        running = false
        if (current === this) current = null
        main.post {
            turn?.cancel()
            turn = null
            runCatching { tone?.release() }
            tone = null
        }
        worker?.interrupt()
        worker = null
        status = "Off"
    }

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before starting the ears
    private fun listenLoop() {
        val detector = try {
            WakeDetector(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "The wake word models couldn't load", e)
            status = "The wake word models couldn't load (${e.message ?: e.javaClass.simpleName})"
            running = false
            return
        }
        val chunk = ShortArray(WakeDetector.CHUNK)
        val kept = ArrayDeque<ShortArray>()
        val gate = SoundGate()
        val rule = HitRule()
        var record: AudioRecord? = null
        var deadChunks = 0
        try {
            while (running) {
                val state = if (handling) WakeHost.HOLD else host.state()
                val interrupting = state == WakeHost.SPEAKING
                // Stay off the microphone while XARVIS hears a command or thinks, and while it talks unless
                // Rex wants "Xarvis" to interrupt it.
                if (!(state == WakeHost.IDLE || (interrupting && settings.bargeIn))) {
                    record?.let { runCatching { it.stop(); it.release() } }
                    record = null
                    if (!handling) status = if (state == WakeHost.THINKING) "XARVIS is thinking" else "Waiting for the microphone"
                    Thread.sleep(250)
                    continue
                }
                if (record == null) {
                    record = openMic() ?: run { status = "Couldn't open the microphone"; Thread.sleep(2000); null } ?: continue
                    detector.reset()
                    gate.reset()
                    rule.reset()
                    kept.clear()
                    deadChunks = 0
                    status = LISTENING
                }
                var read = 0
                while (read < chunk.size && running) {
                    val n = record.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < chunk.size) {
                    // The microphone was taken away or failed: open it again in a moment.
                    runCatching { record.stop(); record.release() }
                    record = null
                    Thread.sleep(1000)
                    continue
                }
                val level = SoundGate.level(chunk)
                // A background app that may not use the microphone gets pure silence, not an error.
                if (level == 0f) {
                    if (++deadChunks == DEAD_CHUNKS && silenced(record)) {
                        status = "Android is keeping the microphone from XARVIS"
                        main.post { onMicBlocked?.invoke() }
                    }
                } else {
                    if (deadChunks >= DEAD_CHUNKS) status = LISTENING
                    deadChunks = 0
                }
                // While XARVIS itself is talking, be surer before interrupting it.
                val threshold = if (interrupting) maxOf(settings.sensitivity.threshold, INTERRUPT_THRESHOLD) else settings.sensitivity.threshold
                val wasAwake = gate.open
                val awake = gate.feed(level)
                var score = 0f
                var hit = false
                if (!awake) {
                    kept.addLast(chunk.copyOf())
                    if (kept.size > SoundGate.RING) kept.removeFirst()
                } else {
                    if (!wasAwake) {
                        // Sound after a quiet spell: start fresh and catch up on the moment before it.
                        detector.reset()
                        rule.reset()
                        for (old in kept) if (rule.feed(detector.process(old), threshold)) hit = true
                        kept.clear()
                    }
                    score = detector.process(chunk)
                    if (rule.feed(score, threshold)) hit = true
                }
                onScore?.invoke(score, level, awake)
                if (hit) {
                    Log.i(TAG, "Heard the wake word ($score)")
                    handling = true
                    runCatching { record.stop(); record.release() }
                    record = null
                    main.post { onWake(score, interrupting) }
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Throwable) {
            Log.e(TAG, "The ears stopped", e)
            status = "Stopped: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            record?.let { runCatching { it.stop(); it.release() } }
            detector.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            WakeDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WakeDetector.CHUNK * 2 * 4),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.also { it.startRecording() }
        }.onFailure { Log.w(TAG, "Couldn't open the mic", it) }.getOrNull()
    }

    private fun silenced(record: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= 29 && runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

    // ---- The conversation (main thread) ----

    private fun onWake(score: Float, interrupting: Boolean) {
        onHit?.invoke(score)
        if (!running) return
        if (practice) {
            beep(ToneGenerator.TONE_PROP_BEEP2, 150)
            main.postDelayed({ handling = false }, 900)
            return
        }
        status = "Talking with you"
        turns = 0
        val now = watch()
        host.wake(interrupting) { ok ->
            main.post {
                if (now != step || !running) return@post
                if (!ok) {
                    // XARVIS itself isn't answering: two low beeps, so Rex knows he was heard but not served.
                    beep(ToneGenerator.TONE_PROP_NACK, 300)
                    end()
                } else {
                    listen()
                }
            }
        }
    }

    /** Hears one thing Rex says and hands it to XARVIS; again after the answer, until he says nothing. */
    private fun listen() {
        beep(ToneGenerator.TONE_PROP_BEEP, 80) // "speak now"
        val now = watch()
        main.postDelayed({
            if (now != step || !running) return@postDelayed
            turn = VoiceTurn(appContext, onDone = { text ->
                turn = null
                if (now != step || !running) return@VoiceTurn
                if (text == null) {
                    host.over()
                    end()
                    return@VoiceTurn
                }
                val asked = watch()
                host.heard(text) { more ->
                    main.post {
                        if (asked != step || !running) return@post
                        if (more && settings.followUp && ++turns < MAX_TURNS) listen() else {
                            host.over()
                            end()
                        }
                    }
                }
            }).also { it.start() }
        }, 200)
    }

    /** Back to waiting for "Xarvis". */
    private fun end() {
        step++
        main.postDelayed({
            handling = false
            if (running) status = LISTENING
        }, 400)
    }

    /**
     * Each step of a conversation gets a number; if XARVIS never answers one (it crashed, or the link to it
     * dropped) the ears go back to listening by themselves.
     */
    private fun watch(): Int {
        val now = ++step
        main.postDelayed({
            if (now == step && handling) {
                Log.w(TAG, "XARVIS didn't answer in time; listening for the wake word again")
                turn?.cancel()
                turn = null
                end()
            }
        }, STEP_TIMEOUT_MS)
        return now
    }

    private fun beep(kind: Int, ms: Int) {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            t.startTone(kind, ms)
        }
    }

    companion object {
        private const val TAG = "XarvisEars"
        private const val LISTENING = "Listening for \"Xarvis\""
        /** 5 s of pure digital silence before asking Android whether the microphone is being withheld. */
        private const val DEAD_CHUNKS = 62
        private const val INTERRUPT_THRESHOLD = 0.8f
        private const val MAX_TURNS = 40
        /** The longest XARVIS may take over one step (a slow answer read aloud in full). */
        private const val STEP_TIMEOUT_MS = 6 * 60_000L

        /** The ears running in this app, for the wake word screen. */
        @Volatile var current: WakeEars? = null
            private set
    }
}
