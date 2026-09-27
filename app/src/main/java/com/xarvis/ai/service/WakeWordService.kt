package com.xarvis.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xarvis.ai.MainActivity
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.tools.PermissionGate
import com.xarvis.ai.voice.VoiceCommand
import com.xarvis.ai.voice.WakeWordDetector
import kotlin.concurrent.thread

/**
 * "Hey Jarvis": listens with the microphone, screen on or off, for the wake word (offline, on
 * this phone). When it hears it, XARVIS beeps, listens for the command, answers aloud, then
 * goes back to listening. Android shows a notification and the green mic dot the whole time;
 * the notification's Turn off stops it. Android only lets the mic be used from the background
 * if this was started while XARVIS was open, so after a phone restart, open XARVIS once.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var handling = false
    private var worker: Thread? = null
    private var command: VoiceCommand? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWord.setEnabled(this, false)
            shutDown()
            return START_NOT_STICKY
        }
        if (!PermissionGate.has(this, Manifest.permission.RECORD_AUDIO)) {
            shutDown()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Android won't let the mic listen in the background now; open XARVIS to start it", e)
            shutDown()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker = thread(name = "xarvis-wakeword") { listenLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        command?.cancel()
        super.onDestroy()
    }

    private fun shutDown() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun listenLoop() {
        val detector = try {
            WakeWordDetector(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Wake word models couldn't load", e)
            main.post { shutDown() }
            return
        }
        val chunk = ShortArray(WakeWordDetector.CHUNK)
        var record: AudioRecord? = null
        try {
            while (running) {
                // Let go of the mic while XARVIS is listening for a command, thinking or talking
                // (so it doesn't hear itself), or while another XARVIS mic is in use.
                if (handling || WakeWord.paused || core().busy()) {
                    record?.let { it.stop(); it.release() }
                    record = null
                    Thread.sleep(300)
                    continue
                }
                if (record == null) {
                    record = openMic() ?: run { Thread.sleep(2000); null } ?: continue
                    detector.reset()
                }
                var read = 0
                while (read < chunk.size && running) {
                    val n = record.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < chunk.size) continue
                if (detector.process(chunk) >= WakeWordDetector.THRESHOLD) {
                    Log.i(TAG, "Heard the wake word")
                    handling = true
                    record.stop(); record.release()
                    record = null
                    main.post { onWake() }
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Throwable) {
            Log.e(TAG, "Wake word listening stopped", e)
        } finally {
            record?.let { runCatching { it.stop(); it.release() } }
            detector.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WakeWordDetector.CHUNK * 2 * 4),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.also { it.startRecording() }
        }.onFailure { Log.w(TAG, "Couldn't open the mic", it) }.getOrNull()
    }

    /** Beep, hear the command, hand it to XARVIS (which answers aloud), then listen again. */
    private fun onWake() {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }
        main.postDelayed({
            command = VoiceCommand(this, onDone = { heard ->
                command = null
                if (heard != null) core().submit(heard, spoken = true)
                // The loop waits while XARVIS thinks and talks, then listens again.
                main.postDelayed({ handling = false }, 500)
            }).also { it.start() }
        }, 300)
    }

    private fun core() = (application as XarvisApp).core

    private fun notification(): android.app.Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while XARVIS listens for \"Hey Jarvis\""
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 11, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("Listening for \"Hey Jarvis\"")
            .setContentText("Say \"Hey Jarvis\", wait for the beep, then speak.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "XarvisWakeWord"
        private const val CHANNEL_ID = "xarvis_wakeword"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.xarvis.ai.WAKEWORD_STOP"

        /** Starts listening (call while XARVIS is on screen, or Android refuses the mic). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start listening now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}

/** The "Hey Jarvis" switch, and a pause for when XARVIS's other mics are listening. */
object WakeWord {
    /** Set while the mic button or the voice screen is listening, so they get the mic. */
    @Volatile var paused = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wakeWord", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("wakeWord", on).apply()
        if (on) WakeWordService.start(context) else WakeWordService.stop(context)
        (context.applicationContext as XarvisApp).core.wakeWordChanged(on)
    }
}
