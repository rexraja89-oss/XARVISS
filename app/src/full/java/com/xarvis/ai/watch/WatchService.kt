package com.xarvis.ai.watch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Range
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.faces.FaceMath
import com.xarvis.ai.faces.FacePrintsProvider
import com.xarvis.ai.faces.FaceStore
import com.xarvis.ai.hands.HandsBridge
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watch mode on the S22 (see [Watch]): the front camera looks about twice a second at low resolution, with the screen
 * off, and [Motion] decides when the room changed. Then a photo (and who it is, if a face is clear) goes to Rex's other
 * phones. Silent on this phone — nothing is shown or said here, by Rex's choice. Stops by itself after the hours asked.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var wake: PowerManager.WakeLock? = null
    private val motion = Motion()
    private var lastLook = 0L
    private var lastAlert = 0L
    private var rotation = 270
    @Volatile private var snapWanted = false
    @Volatile private var lastFrame: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish("Watch mode stopped."); return START_NOT_STICKY }
            ACTION_SNAP -> { snapWanted = true; if (camera == null) begin(intent); return START_NOT_STICKY }
        }
        begin(intent)
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        val hours = intent?.getIntExtra(EXTRA_HOURS, WatchCommands.DEFAULT_HOURS) ?: WatchCommands.DEFAULT_HOURS
        until = System.currentTimeMillis() + hours * 3_600_000L
        val notice = notice()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTICE_ID, notice)
        running = true
        if (camera != null) return
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis:watch").apply { acquire(hours * 3_600_000L + 60_000) }
        thread = HandlerThread("xarvis-watch").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.postDelayed({ finish("⏱ Watch mode ended after $hours hour${if (hours == 1) "" else "s"}.") }, hours * 3_600_000L)
        open()
    }

    @SuppressLint("MissingPermission") // CAMERA is checked by Watch.start before this service starts
    private fun open() {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return finish("⚠ Watch mode: this phone has no front camera.")
        val chars = cm.getCameraCharacteristics(id)
        rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val size = sizes.filter { it.width <= 800 && it.width >= 320 }.minByOrNull { kotlin.math.abs(it.width - 640) } ?: sizes.last()
        // The slowest frame rate the camera offers: less heat and battery for a scene looked at twice a second.
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().minByOrNull { it.upper * 100 + it.lower }
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { r ->
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
        }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader!!.surface)
                                fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper)) }
                            }.build()
                            runCatching { s.setRepeatingRequest(req, null, handler) }
                            HandsBridge.log("Watch mode: camera on (${size.width}x${size.height}).")
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = finish("⚠ Watch mode: the camera wouldn't start.")
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) {
                    // Another app took the camera (a video call, the camera app): try again in a minute.
                    closeCamera()
                    handler?.postDelayed({ if (running) open() }, 60_000)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    closeCamera()
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                        finish("⚠ Watch mode: Android didn't let me use the camera in the background. Open XARVIS on the S22 once (or keep XARVIS Hands on), then ask again.")
                    } else handler?.postDelayed({ if (running) open() }, 30_000)
                }
            }, handler)
        } catch (e: Exception) {
            finish("⚠ Watch mode: couldn't open the camera (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastLook < LOOK_EVERY_MS && !snapWanted) return
            lastLook = now
            val w = img.width
            val h = img.height
            val yPlane = img.planes[0]
            val y = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
            val event = motion.feed(y, w, h, yPlane.rowStride)
            val wantPhoto = snapWanted || (event != null && now - lastAlert > COOLDOWN_MS)
            if (!wantPhoto) return
            val jpeg = jpeg(img) ?: return
            lastFrame = jpeg
            if (snapWanted) {
                snapWanted = false
                send("📷 The room now (${clock(now)}).", jpeg)
            } else if (event != null) {
                lastAlert = now
                val what = describe(event, jpeg) ?: return // it's Rex: no alert
                send("👁 $what (${clock(now)}).", jpeg)
            }
        } catch (e: Exception) {
            HandsBridge.log("Watch mode: frame failed: ${e.message}")
        } finally {
            img.close()
        }
    }

    /** What happened, in words; null when the only face seen is Rex's own. */
    private fun describe(event: Motion.Event, jpeg: ByteArray): String? {
        val side = if (event.where == "everywhere") "" else " on the ${event.where}"
        if (event.kind == Motion.Kind.LIGHTS) return "The lights in the room changed"
        val faces = runCatching {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            FacePrintsProvider.create(this)?.faces(bmp).orEmpty()
        }.getOrDefault(emptyList())
        if (faces.isEmpty()) return "Movement in the room$side"
        val known = FaceStore(this).all()
        val owner = com.xarvis.ai.guard.GuardMode.owner(this)
        val matches = faces.map { FaceMath.bestMatch(it, known) }
        if (owner != null && matches.all { it.sure && it.name == owner }) return null
        val names = matches.map { m -> if (m.sure) m.name!! else "someone I don't know" }
        return when (names.size) {
            1 -> if (names[0] == "someone I don't know") "Unknown person in the room$side" else "${names[0]} is in the room$side"
            else -> "${names.size} people in the room$side: ${names.joinToString(", ")}"
        }
    }

    /** The frame as an upright JPEG (front camera: rotated by the sensor's angle, mirrored like a selfie). */
    private fun jpeg(img: android.media.Image): ByteArray? = runCatching {
        val nv21 = nv21(img)
        val raw = ByteArrayOutputStream().also { YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null).compressToJpeg(Rect(0, 0, img.width, img.height), 80, it) }.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(-1f, 1f) }
        val up = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        ByteArrayOutputStream().also { up.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }.getOrNull()

    private fun nv21(img: android.media.Image): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        var o = 0
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(out, o, w)
            o += w
        }
        val u = img.planes[1]
        val v = img.planes[2]
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val vi = row * v.rowStride + col * v.pixelStride
            val ui = row * u.rowStride + col * u.pixelStride
            out[o++] = v.buffer.get(vi)
            out[o++] = u.buffer.get(ui)
        }
        return out
    }

    /** To every linked phone of Rex's: the photo into its chat, and the words as a notification. */
    private fun send(text: String, jpeg: ByteArray) {
        val link = (application as XarvisApp).core.deviceLink
        val peers = link.pairedPeers()
        HandsBridge.log("Watch mode: $text")
        scope.launch {
            for (p in peers) {
                runCatching { link.sendFile(p, "watch-${System.currentTimeMillis()}.jpg", "image/jpeg", jpeg) }
                runCatching { link.sendTaskNews(p, text) }
            }
        }
    }

    private fun finish(why: String) {
        if (running) {
            val link = (application as XarvisApp).core.deviceLink
            val peers = link.pairedPeers()
            scope.launch { peers.forEach { p -> runCatching { link.sendTaskNews(p, "👁 $why") } } }
        }
        running = false
        closeCamera()
        handler?.removeCallbacksAndMessages(null)
        runCatching { wake?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null; camera = null; reader = null
        motion.reset()
    }

    override fun onDestroy() {
        running = false
        closeCamera()
        thread?.quitSafely()
        runCatching { wake?.release() }
        // Let the last alerts go out before the scope goes.
        handler?.postDelayed({ scope.cancel() }, 5_000) ?: scope.cancel()
        super.onDestroy()
    }

    private fun notice(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watch mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("👁 Watch mode")
            .setContentText("Watching the room until ${clock(until)}")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun clock(at: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at))

    companion object {
        const val ACTION_STOP = "com.xarvis.ai.watch.STOP"
        const val ACTION_SNAP = "com.xarvis.ai.watch.SNAP"
        const val EXTRA_HOURS = "hours"
        private const val CHANNEL = "xarvis_watch"
        private const val NOTICE_ID = 7811
        private const val LOOK_EVERY_MS = 500L
        private const val COOLDOWN_MS = 60_000L

        @Volatile var running = false
            private set
        @Volatile var until = 0L
            private set
    }
}
