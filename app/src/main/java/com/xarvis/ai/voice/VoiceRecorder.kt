package com.xarvis.ai.voice

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Records Rex reading [VoiceScript] lines, one WAV per line (22,050 Hz mono, what Piper voice
 * training uses), kept in the app's own folder, and packs them for training (LJSpeech layout:
 * en/ and hi/, each with metadata.csv "id|text" and wavs/).
 */
class VoiceRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "myvoice")

    @Volatile private var recording = false
    private var worker: Thread? = null

    fun file(hindi: Boolean, index: Int) = File(root, "${lang(hindi)}/wavs/${id(index)}.wav")

    fun has(hindi: Boolean, index: Int) = file(hindi, index).length() > 44

    fun count(hindi: Boolean) = lines(hindi).indices.count { has(hindi, it) }

    /** Starts recording; [onLevel] gets the loudness (0..1) as Rex speaks. */
    @SuppressLint("MissingPermission") // the screen asks for the mic first
    fun start(onLevel: (Float) -> Unit): Boolean {
        if (recording) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return false
        pcm.reset()
        recording = true
        worker = thread(name = "xarvis-myvoice") {
            val buf = ShortArray(RATE / 20)
            record.startRecording()
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var peak = 0
                    for (i in 0 until n) { bytes.putShort(buf[i]); peak = maxOf(peak, abs(buf[i].toInt())) }
                    synchronized(pcm) { pcm.write(bytes.array()) }
                    onLevel(peak / 32767f)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
        return true
    }

    private val pcm = ByteArrayOutputStream()

    /** Stops and saves the take for that line. Returns a problem to tell Rex, or null if it's good. */
    fun stop(hindi: Boolean, index: Int): String? {
        recording = false
        worker?.join(2000)
        worker = null
        val samples = synchronized(pcm) { pcm.toByteArray() }.let { b ->
            ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        }
        val clip = trim(samples)
        val peak = clip.maxOfOrNull { abs(it.toInt()) } ?: 0
        val problem = when {
            clip.size < RATE / 2 -> "I didn't hear anything. Tap record and read the sentence."
            peak < 2500 -> "Too quiet. Hold the phone closer and try again."
            clip.count { abs(it.toInt()) > 32000 } > 20 -> "Too loud, it crackles. Hold the phone a bit further away."
            else -> null
        }
        if (problem == null) writeWav(file(hindi, index), clip)
        return problem
    }

    fun cancel() {
        recording = false
        worker?.join(2000)
        worker = null
    }

    /** Packs every take into Downloads/XARVIS/xarvis-my-voice.zip and returns it. */
    fun export(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "xarvis-my-voice.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XARVIS")
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("the phone wouldn't let me save to Downloads")
        resolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                for (hindi in listOf(false, true)) {
                    val lines = lines(hindi)
                    val done = lines.indices.filter { has(hindi, it) }
                    if (done.isEmpty()) continue
                    zip.putNextEntry(ZipEntry("${lang(hindi)}/metadata.csv"))
                    zip.write(done.joinToString("\n", postfix = "\n") { "${id(it)}|${lines[it].say.replace("|", " ")}" }.toByteArray())
                    zip.closeEntry()
                    for (i in done) {
                        zip.putNextEntry(ZipEntry("${lang(hindi)}/wavs/${id(i)}.wav"))
                        file(hindi, i).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        return uri
    }

    private fun lines(hindi: Boolean) = if (hindi) VoiceScript.hindi else VoiceScript.english

    private fun lang(hindi: Boolean) = if (hindi) "hi" else "en"

    private fun id(index: Int) = "rex_%04d".format(index + 1)

    companion object {
        const val RATE = 22050

        /** Cuts the silence before and after the words, keeping a quarter second of room. */
        fun trim(samples: ShortArray, threshold: Int = 900): ShortArray {
            val first = samples.indexOfFirst { abs(it.toInt()) > threshold }
            if (first < 0) return ShortArray(0)
            val last = samples.indexOfLast { abs(it.toInt()) > threshold }
            val pad = RATE / 4
            return samples.copyOfRange(maxOf(0, first - pad), minOf(samples.size, last + pad))
        }

        fun writeWav(file: File, samples: ShortArray) {
            file.parentFile?.mkdirs()
            val data = samples.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            samples.forEach { b.putShort(it) }
            file.writeBytes(b.array())
        }
    }
}
