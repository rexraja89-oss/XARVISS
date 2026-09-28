package com.xarvis.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Rex's own voice: Piper voices trained on his recordings (training/XARVIS_voice_training.ipynb
 * makes xarvis-voice-hi.zip / -en.zip), played offline by sherpa-onnx. Installed voices live in
 * filesDir/ownvoice/<hi|en>/ (model.onnx, tokens.txt, espeak-ng-data/).
 */
object OwnVoice {
    private const val TAG = "XarvisOwnVoice"

    /** "ready (1.13.8)" when the engine loads on this phone, else why not. */
    val status: String by lazy {
        try {
            "ready (${VersionInfo.version})"
        } catch (e: Throwable) {
            Log.w(TAG, "Voice engine didn't load", e)
            "not working (${e.javaClass.simpleName})"
        }
    }

    private fun dir(context: Context, hindi: Boolean) = File(context.filesDir, "ownvoice/${if (hindi) "hi" else "en"}")

    fun installed(context: Context, hindi: Boolean) = File(dir(context, hindi), "model.onnx").exists()

    fun enabled(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("ownVoice", true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("ownVoice", on).apply()

    /** Whether replies in that language should be spoken in Rex's voice. */
    fun use(context: Context, hindi: Boolean) = enabled(context) && installed(context, hindi) && status.startsWith("ready")

    /**
     * Unpacks a voice zip from the training page. Returns true for Hindi, false for English;
     * throws with a message Rex can read if it isn't a voice file.
     */
    fun install(context: Context, uri: Uri): Boolean {
        val tmp = File(context.filesDir, "ownvoice/incoming")
        tmp.deleteRecursively()
        tmp.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    require(out.path.startsWith(tmp.canonicalPath)) { "that zip has odd paths" }
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        require(File(tmp, "model.onnx").exists() && File(tmp, "tokens.txt").exists()) {
            "that isn't a XARVIS voice file (pick xarvis-voice-hi.zip or xarvis-voice-en.zip)"
        }
        val lang = File(tmp, "lang.txt").takeIf { it.exists() }?.readText()?.trim()
        val hindi = lang == "hi"
        synchronized(this) {
            engines.remove(hindi)?.release()
            val target = dir(context, hindi)
            target.deleteRecursively()
            require(tmp.renameTo(target)) { "couldn't save the voice" }
        }
        return hindi
    }

    private val engines = mutableMapOf<Boolean, OfflineTts>()

    private fun engine(context: Context, hindi: Boolean): OfflineTts = synchronized(this) {
        engines.getOrPut(hindi) {
            val d = dir(context, hindi)
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(d, "model.onnx").path,
                            tokens = File(d, "tokens.txt").path,
                            dataDir = File(d, "espeak-ng-data").path,
                        ),
                        numThreads = 4,
                    ),
                ),
            )
        }
    }

    @Volatile private var generation = 0
    @Volatile var isSpeaking = false
        private set
    private var track: AudioTrack? = null

    /** Says [text] in Rex's voice, sentence by sentence (the next one is made while one plays). */
    fun speak(context: Context, text: String, hindi: Boolean) {
        stop()
        val mine = ++generation
        isSpeaking = true
        val sentences = text.split(Regex("""(?<=[.!?।])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val queue = LinkedBlockingQueue<FloatArray>()
        val end = FloatArray(0)
        thread(name = "xarvis-ownvoice-make") {
            try {
                val tts = engine(context, hindi)
                for (s in sentences) {
                    if (generation != mine) break
                    queue.put(tts.generate(s, sid = 0, speed = 1.0f).samples)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Own voice failed", e)
            } finally {
                queue.put(end)
            }
        }
        thread(name = "xarvis-ownvoice-play") {
            val rate = runCatching { engine(context, hindi).sampleRate() }.getOrDefault(22050)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            synchronized(this) { track = t }
            try {
                t.play()
                while (generation == mine) {
                    val samples = queue.take()
                    if (samples === end) break
                    t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    // A short breath between sentences.
                    val pause = FloatArray(rate / 6)
                    t.write(pause, 0, pause.size, AudioTrack.WRITE_BLOCKING)
                }
                if (generation == mine) Thread.sleep(300) // let the last words drain
            } catch (e: Throwable) {
                Log.w(TAG, "Playback stopped", e)
            } finally {
                runCatching { t.stop() }
                t.release()
                synchronized(this) { if (track === t) track = null }
                if (generation == mine) isSpeaking = false
            }
        }
    }

    fun stop() {
        generation++
        isSpeaking = false
        synchronized(this) { runCatching { track?.stop() } } // a blocked write returns once stopped
    }
}
