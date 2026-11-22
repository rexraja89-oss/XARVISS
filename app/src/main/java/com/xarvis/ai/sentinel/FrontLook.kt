package com.xarvis.ai.sentinel

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A few quiet pictures from the front camera (no preview, no sound), for the Sentinel to see who opened XARVIS.
 * The same Camera2 way as Guard mode; works only while XARVIS is on screen (Android's rule for the camera).
 */
object FrontLook {
    fun possible(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Up to [max] JPEGs, ~[gapMs] apart after the exposure settles, within [timeoutMs]. [enough] sees each picture as
     * it arrives and can end early (Rex recognised). Empty if the camera is busy or refused.
     */
    @SuppressLint("MissingPermission")
    suspend fun pictures(
        context: Context, max: Int = 5, gapMs: Long = 450, timeoutMs: Long = 4_000, enough: (ByteArray) -> Boolean = { false },
    ): List<ByteArray> = suspendCancellableCoroutine { cont ->
        val cm = context.getSystemService(CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        }.getOrNull()
        if (id == null || !possible(context)) { cont.resume(emptyList()); return@suspendCancellableCoroutine }
        val thread = HandlerThread("sentinel-camera").apply { start() }
        val handler = Handler(thread.looper)
        val sizes = runCatching {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
        }.getOrNull()
        val size = sizes?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: sizes?.firstOrNull()
        val reader = ImageReader.newInstance(size?.width ?: 1280, size?.height ?: 960, ImageFormat.JPEG, 3)
        val frames = ArrayList<ByteArray>()
        var device: CameraDevice? = null
        var done = false
        val started = System.currentTimeMillis()
        var last = 0L

        fun finish() {
            if (done) return
            done = true
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
            if (cont.isActive) cont.resume(frames.toList())
        }

        reader.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bytes = runCatching { val b = img.planes[0].buffer; ByteArray(b.remaining()).also { b.get(it) } }.getOrNull()
            img.close()
            val now = System.currentTimeMillis()
            if (done || bytes == null || now - started < 600 || now - last < gapMs) return@setOnImageAvailableListener
            last = now
            frames += bytes
            if (frames.size >= max || enough(bytes)) finish()
        }, handler)
        handler.postDelayed({ finish() }, timeoutMs)
        cont.invokeOnCancellation { handler.post { finish() } }
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    if (done) { camera.close(); return }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            runCatching {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                }
                                session.setRepeatingRequest(req.build(), null, handler)
                            }.onFailure { finish() }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) = finish()
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); finish() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); finish() }
            }, handler)
        }.onFailure { finish() }
    }

    /** The picture the right way up (front cameras write their turn in EXIF), at half size. */
    fun upright(jpeg: ByteArray): Bitmap? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@runCatching null
        val turn = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg)).rotationDegrees
        if (turn == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    }.getOrNull()
}
