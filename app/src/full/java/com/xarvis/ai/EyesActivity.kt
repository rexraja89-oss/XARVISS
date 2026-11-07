package com.xarvis.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream

/**
 * XARVIS Eyes, built into XARVIS (S22 build; ☰ → XARVIS Eyes, or say "look"). Built in code like the
 * separate XARVIS Hands/Eyes screens: a live camera view and a few big buttons. Each button takes one
 * photo and Gemma looks at it ([XarvisCore.lookAt]); the answer shows here and is spoken if the switch
 * is on. The camera only runs while this screen is open, and the photo is deleted after Gemma looks.
 *
 * Uses Android's own camera API (Camera2) rather than the CameraX library: with CameraX in it the S22
 * refused to install XARVIS ("package appears to be invalid", install test test-eyes-v1.0.186), while
 * the same build without it installed. Camera2 adds nothing to the package.
 */
class EyesActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var texture: TextureView
    private lateinit var answer: TextView
    private lateinit var thumb: ImageView
    private lateinit var questionBox: EditText
    private lateinit var speak: CheckBox
    private lateinit var lightBtn: Button

    // ---- camera state (Camera2), all used on the camera thread except where noted ----
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var previewSize = Size(1440, 1080)
    private var sensorOrientation = 90
    private var hasFlash = false
    private var backCamera = true
    private var torchOn = false
    @Volatile private var opening = false

    private var busy = false
    private var waitingFor = 0L
    private var pendingQuestion: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        if (busy) {
            busy = false
            showAnswer("No answer yet — XARVIS's brain may still be loading. Try again in a moment.")
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        render()
        if (granted && texture.isAvailable) openCamera()
    }
    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            questionBox.setText(heard)
            look(heard)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val gap = (8 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B6E8C")); setPadding(0, pad, 0, gap)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 16f; setOnClickListener { onClick() }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        col.addView(TextView(this).apply {
            text = "XARVIS Eyes"; textSize = 24f; setTextColor(Color.parseColor("#0B6E8C"))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, gap, 0, gap) }
        col.addView(status)

        // ---- the live camera ----
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitPreview() }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        col.addView(texture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (380 * d).toInt()))
        lightBtn = button("🔦 Light") { toggleLight() }
        col.addView(row(button("⟲ Flip camera") { flip() }, lightBtn))

        // ---- ask ----
        col.addView(header("ASK XARVIS ABOUT WHAT THE CAMERA SEES"))
        col.addView(button("👁  What do you see?") { look(SEE) })
        col.addView(button("📖  Read the text") { look(READ) })
        col.addView(button("❓  What is this?") { look(WHAT) })
        questionBox = EditText(this).apply { hint = "Or ask your own question…"; textSize = 16f }
        col.addView(questionBox)
        col.addView(row(
            button("Ask") {
                val q = questionBox.text.toString().trim()
                if (q.isEmpty()) Toast.makeText(this, "Type a question first, or use a button above.", Toast.LENGTH_SHORT).show()
                else look(q)
            },
            button("🎤 Ask by voice") { askByVoice() },
        ))
        speak = CheckBox(this).apply { text = "🔊 XARVIS says the answer aloud"; isChecked = true; textSize = 15f }
        col.addView(speak)

        // ---- the answer ----
        col.addView(header("WHAT XARVIS SAYS"))
        thumb = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true }
        col.addView(thumb, LinearLayout.LayoutParams((120 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        answer = TextView(this).apply {
            text = "(nothing yet)"; textSize = 16f
            setTextColor(Color.parseColor("#222222")); setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(gap, gap, gap, gap); setTextIsSelectable(true)
        }
        col.addView(answer)

        setContentView(ScrollView(this).apply { addView(col) })
        if (!hasCameraPermission()) askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        render()
        startThread()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopThread()
        super.onPause()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val (msg, ok) = if (hasCameraPermission()) "✓ Ready — point the camera and ask" to true
        else "✗ Camera permission needed — tap \"What do you see?\" to allow it" to false
        status.text = msg
        status.setTextColor(Color.parseColor(if (ok) "#1B8A3A" else "#B00020"))
    }

    // ---- camera (Camera2) ----

    private fun startThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("xarvis-eyes").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopThread() {
        cameraThread?.quitSafely()
        runCatching { cameraThread?.join(500) }
        cameraThread = null
        cameraHandler = null
    }

    /** Opens the chosen camera into the preview, with an ImageReader ready for one JPEG at a time. */
    @SuppressLint("MissingPermission") // checked just below
    private fun openCamera() {
        if (!hasCameraPermission() || device != null || opening) return
        val handler = cameraHandler ?: return
        val manager = getSystemService(CameraManager::class.java) ?: return
        val facing = if (backCamera) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = runCatching {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        }.getOrNull()
        if (id == null) {
            status.text = "✗ This phone has no ${if (backCamera) "back" else "front"} camera"
            return
        }
        val chars = manager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        // A 4:3 preview no bigger than 1440 px, and a JPEG no bigger than ~2 MP (Gemma gets 1024 px anyway).
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1440 && it.width * 3 == it.height * 4 }
            .maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).minByOrNull { kotlin.math.abs(it.width - 1280) } ?: Size(1280, 960)
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG)
            .filter { maxOf(it.width, it.height) <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(ImageFormat.JPEG).minByOrNull { it.width * it.height } ?: Size(1280, 960)
        reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    image.close()
                }
                onPhoto(bytes)
            }, handler)
        }
        runOnUiThread { fitPreview() }
        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { opening = false; camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close(); device = null
                    runOnUiThread { status.text = "✗ The camera couldn't start (error $error). Close other camera apps and reopen." }
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            status.text = "✗ Couldn't start the camera: ${e.message}"
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List, …) is fine back to Android 9 (minSdk 28)
    private fun startPreview() {
        val cam = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = Surface(st)
        val still = reader?.surface ?: return
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequest = builder
            cam.createCaptureSession(listOf(preview, still), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) return
                    session = s
                    applyPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { status.text = "✗ The camera preview couldn't start." }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            runOnUiThread { status.text = "✗ Couldn't start the preview: ${e.message}" }
        }
    }

    /** (Re)starts the live preview with the light on or off. */
    private fun applyPreview() {
        val s = session ?: return
        val b = previewRequest ?: return
        b.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        runCatching { s.setRepeatingRequest(b.build(), null, cameraHandler) }
    }

    /** The preview arrives sideways-sized (landscape buffer); scale it to fill the portrait view without stretching. */
    private fun fitPreview() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val content = previewSize.height.toFloat() / previewSize.width // width/height of the upright picture
        val view = vw / vh
        val m = Matrix()
        if (content > view) m.setScale(content / view, 1f, vw / 2, vh / 2)
        else m.setScale(1f, view / content, vw / 2, vh / 2)
        texture.setTransform(m)
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        torchOn = false
        lightBtn.text = "🔦 Light"
    }

    private fun flip() {
        backCamera = !backCamera
        closeCamera()
        if (texture.isAvailable) openCamera()
    }

    private fun toggleLight() {
        if (!hasFlash) {
            Toast.makeText(this, "No light on this camera.", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        lightBtn.text = if (torchOn) "🔦 Light off" else "🔦 Light"
        cameraHandler?.post { applyPreview() }
    }

    // ---- asking ----

    private fun askByVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask XARVIS about what the camera sees")
        try {
            listen.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "This phone has no speech recognizer (install the Google app).", Toast.LENGTH_LONG).show()
        }
    }

    /** Takes one photo and asks XARVIS [question] about it. */
    private fun look(question: String) {
        if (!hasCameraPermission()) {
            askCamera.launch(Manifest.permission.CAMERA)
            return
        }
        if (busy) {
            Toast.makeText(this, "Still waiting for XARVIS's answer…", Toast.LENGTH_SHORT).show()
            return
        }
        val cam = device
        val s = session
        val still = reader?.surface
        if (cam == null || s == null || still == null) {
            Toast.makeText(this, "The camera isn't ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        pendingQuestion = question
        answer.text = "📸 Taking the photo…"
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(still)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            s.capture(req.build(), null, cameraHandler)
        } catch (e: Exception) {
            busy = false
            showAnswer("Couldn't take the photo: ${e.message}")
        }
    }

    /** Camera thread: the JPEG arrived — turn it upright, shrink it, and hand it to XARVIS. */
    private fun onPhoto(jpeg: ByteArray) {
        val question = pendingQuestion ?: return
        pendingQuestion = null
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) {
            runOnUiThread { busy = false; showAnswer("Couldn't read the photo. Try again.") }
            return
        }
        // The screen is locked to portrait, so the photo needs the sensor's own rotation.
        val prepared = prepare(bitmap, sensorOrientation)
        runOnUiThread { send(prepared, question) }
    }

    private fun send(jpeg: ByteArray, question: String) {
        thumb.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        thumb.visibility = View.VISIBLE
        answer.text = "👁 Looking… (on-device vision can take up to a minute)"
        val id = System.currentTimeMillis()
        waitingFor = id
        val file = java.io.File(cacheDir, "photos/eyes-$id.jpg")
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(jpeg)
        } catch (e: Exception) {
            busy = false
            showAnswer("Couldn't keep the photo for XARVIS: ${e.message}")
            return
        }
        ui.removeCallbacks(giveUp)
        ui.postDelayed(giveUp, 4 * 60_000L)
        (application as XarvisApp).core.lookAt(file, question, speak.isChecked) { text ->
            runOnUiThread { onAnswer(id, text) }
        }
    }

    /** Upright, at most 1024 px, as a JPEG for Gemma. */
    private fun prepare(photo: Bitmap, rotationDegrees: Int): ByteArray {
        val scale = minOf(1f, 1024f / maxOf(photo.width, photo.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, m, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun onAnswer(id: Long, text: String) {
        if (id != waitingFor) return
        ui.removeCallbacks(giveUp)
        busy = false
        showAnswer(text)
    }

    private fun showAnswer(text: String) {
        answer.text = text
    }

    private companion object {
        const val SEE = "Describe what you see in this photo, briefly and clearly, as if telling me what's in front of me."
        const val READ = "Read out all the text you can see in this photo, exactly as written. If it isn't English, translate it too."
        const val WHAT = "What is the main thing in this photo? Identify it and tell me something useful about it."
    }
}
