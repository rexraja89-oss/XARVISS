package com.xarvis.eyes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

/**
 * The Eyes screen, built in code like XARVIS Hands: a live camera view and a few big buttons. Each
 * button takes one photo and asks the main XARVIS app about it; the answer shows here (and XARVIS
 * says it aloud if the switch is on). The camera only runs while this screen is open, and photos
 * aren't saved anywhere.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var answer: TextView
    private lateinit var thumb: ImageView
    private lateinit var questionBox: EditText
    private lateinit var speak: CheckBox
    private lateinit var lightBtn: Button
    private lateinit var updateBtn: Button

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var torchOn = false

    private var busy = false
    private var waitingFor = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        if (busy) {
            busy = false
            showAnswer("No answer from XARVIS yet. Open XARVIS once (so its brain is awake), then try again.")
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else render()
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
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        col.addView(TextView(this).apply {
            text = "Version v$version"; textSize = 13f; setTextColor(Color.parseColor("#888888"))
        })

        // ---- in-app update button (hidden until a newer Eyes is found on GitHub) ----
        updateBtn = Button(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#17A2B8"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!Updates.openDownload(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Couldn't open the browser.", Toast.LENGTH_LONG).show()
                }
            }
        }
        col.addView(updateBtn)

        status = TextView(this).apply { textSize = 16f; setPadding(0, gap, 0, gap) }
        col.addView(status)

        // ---- the live camera ----
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // works inside a scrolling screen
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        col.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (380 * d).toInt()))
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

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        Brain.onAnswer = { id, text -> runOnUiThread { onAnswer(id, text) } }
        Brain.pending?.let { (id, text) -> Brain.pending = null; onAnswer(id, text) }
        render()
        checkForUpdate()
    }

    override fun onPause() {
        super.onPause()
        Brain.onAnswer = null
        if (torchOn) toggleLight()
    }

    private fun render() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val (msg, ok) = when {
            !cam -> "✗ Camera permission needed — tap \"What do you see?\" to allow it" to false
            !Brain.xarvisInstalled(this) -> "✗ XARVIS isn't installed on this phone" to false
            !Brain.linkAllowed(this) -> "✗ Not linked to XARVIS — reinstall Eyes (XARVIS must be installed first)" to false
            else -> "✓ Connected to XARVIS" to true
        }
        status.text = msg
        status.setTextColor(Color.parseColor(if (ok) "#1B8A3A" else "#B00020"))
    }

    // ---- camera ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            bindCamera()
        }, mainExecutor)
    }

    private fun bindCamera() {
        val p = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lens).build()
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
            status.text = "✗ This phone has no ${if (lens == CameraSelector.LENS_FACING_BACK) "back" else "front"} camera"
            return
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val shot = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, selector, preview, shot)
            capture = shot
            torchOn = false
            lightBtn.text = "🔦 Light"
        } catch (e: Exception) {
            status.text = "✗ Couldn't start the camera: ${e.message}"
        }
        render()
    }

    private fun flip() {
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    private fun toggleLight() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "No light on this camera.", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        lightBtn.text = if (torchOn) "🔦 Light off" else "🔦 Light"
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askCamera.launch(Manifest.permission.CAMERA)
            return
        }
        render()
        if (!Brain.xarvisInstalled(this) || !Brain.linkAllowed(this)) {
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Still waiting for XARVIS's answer…", Toast.LENGTH_SHORT).show()
            return
        }
        val shot = capture ?: run {
            Toast.makeText(this, "The camera isn't ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        answer.text = "📸 Taking the photo…"
        shot.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = runCatching { image.toBitmap() }.getOrNull()
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                if (bitmap == null) { busy = false; showAnswer("Couldn't read the photo. Try again."); return }
                Thread {
                    val jpeg = Brain.prepare(bitmap, rotation)
                    runOnUiThread { send(jpeg, question) }
                }.start()
            }

            override fun onError(e: ImageCaptureException) {
                busy = false
                showAnswer("Couldn't take the photo: ${e.message}")
            }
        })
    }

    private fun send(jpeg: ByteArray, question: String) {
        thumb.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        thumb.visibility = View.VISIBLE
        answer.text = "👁 Looking… (on-device vision can take up to a minute)"
        try {
            waitingFor = Brain.ask(this, jpeg, question, speak.isChecked)
            ui.removeCallbacks(giveUp)
            ui.postDelayed(giveUp, 4 * 60_000L)
        } catch (e: Exception) {
            busy = false
            showAnswer("Couldn't reach XARVIS: ${e.message}")
        }
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

    /** Ask GitHub if there's a newer Eyes; if so, reveal the update button. Silent on failure. */
    private fun checkForUpdate() {
        val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        Thread {
            val newer = Updates.newerVersion(current)
            if (newer != null) runOnUiThread {
                updateBtn.text = "⬆  Update to v$newer — tap to install"
                updateBtn.visibility = View.VISIBLE
            }
        }.start()
    }

    private companion object {
        const val SEE = "Describe what you see in this photo, briefly and clearly, as if telling me what's in front of me."
        const val READ = "Read out all the text you can see in this photo, exactly as written. If it isn't English, translate it too."
        const val WHAT = "What is the main thing in this photo? Identify it and tell me something useful about it."
    }
}
