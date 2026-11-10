package com.xarvis.ai.hands

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * Smooth remote control: this screen as live video. Android's screen sharing (MediaProjection, the
 * "Start now" prompt — allowed once per XARVIS run on Android 13, [attach]) mirrors the screen into the
 * phone's hardware H.264 encoder, ~60 pictures a second at a few Mbit/s; [Session.pump] hands each
 * encoded frame to the stream. Without the permission, remote control falls back to Accessibility
 * screenshots (~3 a second, ScreenHost.frame).
 */
object ScreenVideo {
    @Volatile var projection: MediaProjection? = null
        private set

    val ready: Boolean get() = projection != null

    /** Rex allowed screen sharing ("Start now"): keep it for every remote session until XARVIS stops. */
    fun attach(p: MediaProjection, onStopped: () -> Unit) {
        projection?.takeIf { it !== p }?.let { runCatching { it.stop() } }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) {
                    projection = null
                    HandsBridge.log("Smooth screen sharing stopped.")
                    onStopped()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun release() {
        val p = projection
        projection = null
        runCatching { p?.stop() }
    }

    /** One remote session's video: the mirrored screen going through the encoder. */
    class Session internal constructor(
        val width: Int, val height: Int, private val codec: MediaCodec, private val display: VirtualDisplay,
    ) {
        @Volatile private var running = true

        /** The codec setup, kept to resend when the viewer's decoder (re)starts. */
        @Volatile var config: ByteArray? = null
            private set

        /** Encoded output as (StreamMsg type, bytes) until [stop]. Blocks: run it on its own thread. */
        fun pump(out: (Int, ByteArray) -> Unit) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val i = try {
                    codec.dequeueOutputBuffer(info, 20_000)
                } catch (e: IllegalStateException) {
                    break // stopped
                }
                if (i < 0) continue
                val buf = codec.getOutputBuffer(i)
                if (buf == null || info.size <= 0) {
                    codec.releaseOutputBuffer(i, false)
                    continue
                }
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                codec.releaseOutputBuffer(i, false)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) config = bytes
                out(if (isConfig) StreamMsg.VIDEO_CONFIG else StreamMsg.VIDEO_FRAME, bytes)
            }
        }

        /** A new bit rate, live (the stream follows what the connection carries: BitrateControl). */
        fun setBitrate(bps: Int) {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
        }

        /** Make the next frame a full picture (a new viewer can start from it). */
        fun keyFrame() {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        fun stop() {
            running = false
            runCatching { display.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Start mirroring the screen into a new encoder, at most [maxSide] px on its long side; null without permission. */
    fun start(context: Context, maxSide: Int, bitrate: Int): Session? {
        val p = projection ?: return null
        val (w, h) = realSize(context)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val vw = ((w * scale).toInt() / 16).coerceAtLeast(1) * 16
        val vh = ((h * scale).toInt() / 16).coerceAtLeast(1) * 16
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            // A still screen produces no new frames; repeat the last one now and then so the stream keeps flowing.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val display = p.createVirtualDisplay(
                "xarvis-remote", vw, vh, context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            ) ?: throw IllegalStateException("Android gave no screen mirror")
            Session(vw, vh, codec, display)
        } catch (e: Exception) {
            HandsBridge.log("Smooth video couldn't start: ${e.message ?: e.javaClass.simpleName}")
            runCatching { codec.release() }
            null
        }
    }

    /** The whole screen's size in pixels, status and navigation bars included. */
    @Suppress("DEPRECATION")
    fun realSize(context: Context): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }
}
