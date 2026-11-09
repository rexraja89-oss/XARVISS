package com.xarvis.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xarvis.ai.guard.GuardAlarm
import com.xarvis.ai.guard.GuardMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guard mode's screen (S22 build): shows over the lock screen. "check": a quick look through the front
 * camera — Rex → guard off; anyone else → the alarm. "alarm": flashing red WARNING with the intruder's
 * photo while GuardAlarm speaks; the fingerprint prompt opens by itself and stays one tap away ("👆 It's me"),
 * fingerprint only, never the PIN. "finger": Rex said "It's alright XARVIS" — the alarm goes quiet and asks for
 * the fingerprint. "disarm": "guard mode off" — the fingerprint turns guard mode off.
 */
class GuardActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var picture: ImageView
    private lateinit var fingerButton: android.widget.Button
    private var lastAutoAsk = 0L
    private var triedUnlock = false
    private var note: String? = null
    private var flashing = false
    private var red = true
    private var asking = false

    // camera (check mode)
    private var cameraThread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private val frames = ArrayList<ByteArray>()
    @Volatile private var checking = false
    @Volatile private var forced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        title = TextView(this).apply { textSize = 30f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        info = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, (16 * d).toInt(), 0, 0) }
        picture = ImageView(this).apply { adjustViewBounds = true; visibility = View.GONE }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
            setBackgroundColor(Color.BLACK)
            addView(title)
            addView(picture, LinearLayout.LayoutParams((220 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * d).toInt() })
            addView(info)
            fingerButton = android.widget.Button(this@GuardActivity).apply {
                text = "👆  It's me — fingerprint"
                textSize = 20f
                isAllCaps = false
                visibility = View.GONE
                setOnClickListener { note = null; askFinger(disarmOnly = false) }
            }
            addView(fingerButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * d).toInt()).apply { topMargin = (28 * d).toInt() })
        }
        setContentView(root)
        GuardAlarm.onChange = { runOnUiThread { render() } }
        start(intent.getStringExtra(GuardMode.EXTRA_MODE) ?: "check")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent.getStringExtra(GuardMode.EXTRA_MODE) ?: "alarm")
    }

    override fun onResume() {
        super.onResume()
        GuardAlarm.screenShowing = true
        if (alarmOn()) ui.postDelayed({ autoFinger() }, 600)
    }

    private fun alarmOn() = GuardAlarm.phase.let {
        it == GuardAlarm.Phase.WARNING || it == GuardAlarm.Phase.LISTENING || it == GuardAlarm.Phase.OWNER
    }

    /** The fingerprint prompt opens by itself on the alarm screen (again a while after it's cancelled). */
    private fun autoFinger() {
        if (!alarmOn() || asking || isFinishing) return
        if (System.currentTimeMillis() - lastAutoAsk < AUTO_ASK_GAP_MS) return
        askFinger(disarmOnly = false)
    }

    override fun onPause() {
        GuardAlarm.screenShowing = false
        super.onPause()
    }

    private fun start(mode: String) {
        com.xarvis.ai.guard.GuardNotice.cancel(this)
        when (mode) {
            "check" -> if (GuardMode.state == GuardMode.State.CHECKING) check() else render()
            // "Sound the alarm now" from Rex's other phone: take the photo, then the alarm whoever it is.
            "force" -> if (GuardMode.state == GuardMode.State.CHECKING) { forced = true; check() } else render()
            "finger" -> { render(); askFinger(disarmOnly = false) }
            "disarm" -> {
                title.text = "🛡 XARVIS Guard"
                info.text = "Touch the fingerprint sensor to turn guard mode off."
                askFinger(disarmOnly = true)
            }
            else -> render()
        }
    }

    // ---- what the screen shows ----

    private fun render() {
        when (GuardAlarm.phase) {
            GuardAlarm.Phase.WARNING, GuardAlarm.Phase.LISTENING, GuardAlarm.Phase.FINGER, GuardAlarm.Phase.OWNER -> {
                title.text = "⚠ WARNING\nILLEGAL ACTIVITY DETECTED"
                info.text = note ?: when (GuardAlarm.phase) {
                    GuardAlarm.Phase.LISTENING -> "You are not authorized to touch this device.\n🎤 listening…"
                    GuardAlarm.Phase.FINGER -> "Touch the fingerprint sensor, sir."
                    GuardAlarm.Phase.OWNER -> "🗣 A message from the owner."
                    else -> "You are not authorized to touch this device."
                }
                fingerButton.visibility = View.VISIBLE
                GuardAlarm.photo?.let { uri ->
                    runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
                        .getOrNull()?.let { picture.setImageBitmap(upright(it, uri)); picture.visibility = View.VISIBLE }
                }
                startFlashing()
                if (GuardAlarm.screenShowing) autoFinger()
            }
            GuardAlarm.Phase.RELIEF -> {
                fingerButton.visibility = View.GONE
                stopFlashing()
                root.setBackgroundColor(Color.parseColor("#0B3D1E"))
                title.text = "✓ Welcome back, sir"
                info.text = "I thought it's a thief. 😄"
                picture.visibility = View.GONE
                ui.postDelayed({ finish() }, 6_000)
            }
            GuardAlarm.Phase.IDLE -> if (GuardMode.state == GuardMode.State.CHECKING) {
                fingerButton.visibility = View.GONE
                root.setBackgroundColor(Color.BLACK)
                title.text = "🛡"
                info.text = "XARVIS Guard"
            } else if (!asking) {
                finish()
            }
        }
    }

    private val flash = object : Runnable {
        override fun run() {
            if (!flashing) return
            red = !red
            root.setBackgroundColor(Color.parseColor(if (red) "#D50000" else "#5A0000"))
            ui.postDelayed(this, 450)
        }
    }

    private fun startFlashing() {
        if (flashing) return
        flashing = true
        ui.post(flash)
    }

    private fun stopFlashing() {
        flashing = false
        ui.removeCallbacks(flash)
    }

    // ---- check: who is holding the phone? ----

    @SuppressLint("MissingPermission")
    private fun check() {
        if (checking) return
        checking = true
        render()
        val cm = getSystemService(CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        }.getOrNull()
        if (id == null || checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            decide(emptyList()) // can't look: treat as not Rex
            return
        }
        val thread = HandlerThread("guard-camera").apply { start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        val sizes = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
        val size = sizes?.filter { it.width <= 1600 }?.maxByOrNull { it.width * it.height } ?: sizes?.first()
        val r = ImageReader.newInstance(size?.width ?: 1280, size?.height ?: 960, ImageFormat.JPEG, 3)
        reader = r
        val started = System.currentTimeMillis()
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = runCatching { val b = img.planes[0].buffer; ByteArray(b.remaining()).also { b.get(it) } }.getOrNull()
            img.close()
            // Skip the first moments while the exposure settles; then keep a few frames.
            if (bytes != null && System.currentTimeMillis() - started > 700 && frames.size < 4 && System.currentTimeMillis() - lastFrame > 350) {
                lastFrame = System.currentTimeMillis()
                frames += bytes
                if (frames.size >= 4) ui.post { finishCamera(); decide(frames.toList()) }
            }
        }, handler)
        ui.postDelayed({ if (checking && device != null) { finishCamera(); decide(frames.toList()) } }, 4_000)
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            runCatching {
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(r.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                }
                                session.setRepeatingRequest(req.build(), null, handler)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) = ui.post { finishCamera(); decide(emptyList()) }.let { }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    ui.post { finishCamera(); decide(emptyList()) }
                }
            }, handler)
        }.onFailure { finishCamera(); decide(emptyList()) }
    }

    @Volatile private var lastFrame = 0L

    private fun finishCamera() {
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    /** Rex in any frame → guard off; otherwise the alarm, with the clearest frame as evidence. */
    private fun decide(shots: List<ByteArray>) {
        if (!checking) return
        checking = false
        Thread {
            val owner = GuardMode.owner(this)
            val prints = owner?.let { com.xarvis.ai.faces.FaceStore(this).all()[it] }.orEmpty()
            val engine = com.xarvis.ai.faces.FacePrintsProvider.create(this)
            var isRex = false
            var evidence: ByteArray? = shots.firstOrNull()
            for (jpeg in shots) {
                val bmp = uprightJpeg(jpeg) ?: continue
                val faces = runCatching { engine?.faces(bmp).orEmpty() }.getOrDefault(emptyList())
                if (faces.isNotEmpty() && evidence === shots.firstOrNull()) evidence = jpeg
                if (owner != null && faces.any { com.xarvis.ai.faces.FaceMath.bestMatch(it, mapOf(owner to prints)).sure }) {
                    isRex = true
                    break
                }
            }
            ui.post {
                if (isRex && !forced) {
                    GuardMode.ownerSeen()
                    title.text = "✓"
                    info.text = "Welcome back, sir. Guard off."
                    ui.postDelayed({ finish() }, 1_200)
                } else {
                    GuardMode.intruder()
                    val uri = evidence?.let { save(it) }
                    GuardAlarm.start(this, uri, evidence)
                    render()
                }
            }
        }.start()
    }

    private fun uprightJpeg(jpeg: ByteArray): Bitmap? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@runCatching null
        val turn = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg)).rotationDegrees
        if (turn == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    }.getOrNull()

    private fun upright(bmp: Bitmap, uri: Uri): Bitmap = runCatching {
        val turn = contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
        if (turn == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    }.getOrDefault(bmp)

    /** The intruder's photo, kept in Gallery › Pictures › XARVIS › Guard with the date and time. */
    private fun save(jpeg: ByteArray): Uri? = runCatching {
        val name = "intruder-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XARVIS/Guard")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
        uri
    }.getOrNull()

    // ---- the fingerprint ----

    private fun askFinger(disarmOnly: Boolean) {
        if (asking) return
        asking = true
        if (!disarmOnly) lastAutoAsk = System.currentTimeMillis()
        val askedAt = android.os.SystemClock.elapsedRealtime()
        val builder = BiometricPrompt.Builder(this)
            .setTitle("XARVIS Guard")
            .setSubtitle(if (disarmOnly) "Your fingerprint turns guard mode off" else "Your fingerprint, sir")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> fingerDone(false, disarmOnly) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG) // fingerprint, never the PIN
        }
        runCatching {
            builder.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = fingerDone(true, disarmOnly)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // 13 = the Cancel button (the framework keeps that constant hidden).
                    val userSaidNo = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == 13
                    val quick = android.os.SystemClock.elapsedRealtime() - askedAt < 1_500
                    val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
                    when {
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> {
                            note = "Too many wrong fingers — the sensor is locked for a while.\nStop it from your other phone: ☰ → 🛡 Guard my other phone."
                            fingerDone(false, disarmOnly)
                        }
                        // The prompt couldn't show over the lock screen: let Rex unlock the phone first, then ask again.
                        !userSaidNo && quick && locked && !triedUnlock -> {
                            triedUnlock = true
                            asking = false
                            unlockThenFinger(disarmOnly)
                        }
                        else -> fingerDone(false, disarmOnly)
                    }
                }
            })
        }.onFailure { fingerDone(false, disarmOnly) }
    }

    private fun unlockThenFinger(disarmOnly: Boolean) {
        val keyguard = getSystemService(android.app.KeyguardManager::class.java) ?: return fingerDone(false, disarmOnly)
        keyguard.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { ui.postDelayed({ askFinger(disarmOnly) }, 400) }
            override fun onDismissCancelled() = fingerDone(false, disarmOnly)
            override fun onDismissError() = fingerDone(false, disarmOnly)
        })
    }

    private fun fingerDone(ok: Boolean, disarmOnly: Boolean) {
        asking = false
        if (disarmOnly) {
            if (ok) {
                GuardMode.disarm()
                GuardAlarm.stop()
                info.text = "Guard mode off. ✓"
            } else {
                info.text = "Guard mode stays on."
            }
            ui.postDelayed({ finish() }, 1_200)
            return
        }
        if (ok) GuardAlarm.fingerOk(this) else GuardAlarm.fingerFailed()
        render()
    }

    companion object {
        private const val AUTO_ASK_GAP_MS = 12_000L
    }

    override fun onDestroy() {
        stopFlashing()
        finishCamera()
        if (GuardAlarm.onChange != null) GuardAlarm.onChange = null
        GuardAlarm.screenShowing = false
        super.onDestroy()
    }
}
