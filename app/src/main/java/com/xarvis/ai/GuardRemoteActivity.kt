package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Guard remote, on Rex's other phone (the benco): the S22's guard status and the intruder's photo, and
 * Rex's voice there — what he says or types is said out loud on the S22 at alarm volume (the benco build
 * may not use the microphone itself, so speech goes through Google's speech screen, and a real voice clip
 * through the phone's recorder app). Stopping the alarm first asks for this phone's own screen lock.
 */
class GuardRemoteActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var photo: ImageView
    private var peer: Peer? = null

    private val link get() = (application as XarvisApp).core.deviceLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 16f; setOnClickListener { onClick() }
        }
        status = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, pad / 2, 0, pad / 2) }
        result = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#555555")); setPadding(0, pad / 2, 0, 0) }
        photo = ImageView(this).apply { adjustViewBounds = true }
        val recorder = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        val hasRecorder = recorder.resolveActivity(packageManager) != null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@GuardRemoteActivity).apply { text = "🛡 Guard remote"; textSize = 24f; setTextColor(Color.parseColor("#0B6E8C")) })
            addView(status)
            addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@GuardRemoteActivity).apply {
                text = "YOUR VOICE ON IT — said out loud at full volume"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0B6E8C")); setPadding(0, pad, 0, pad / 2)
            })
            addView(button("🎤 Speak to them") { speak() })
            addView(button("⌨ Type a warning") { type() })
            addView(button(if (hasRecorder) "🎙 Send my real voice" else "🎙 Real voice (no recorder app on this phone)") {
                if (hasRecorder) runCatching { startActivityForResult(recorder, RECORD) }
            }.apply { isEnabled = hasRecorder })
            addView(TextView(this@GuardRemoteActivity).apply {
                text = "THE ALARM"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0B6E8C")); setPadding(0, pad, 0, pad / 2)
            })
            addView(button("🛑 Stop the alarm") { stopAlarm() })
            addView(button("🚨 Sound the alarm now") { soundNow() })
            addView(result)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        showPhoto()
        scope.launch {
            peer = withContext(Dispatchers.IO) { runCatching { link.reachable().firstOrNull()?.first }.getOrNull() }
            if (peer == null) {
                status.text = "⚠ I can't reach your other phone. Make sure XARVIS is running on it with Tailscale on."
                return@launch
            }
            ui.post(poll)
            when (intent.getStringExtra(EXTRA_ACTION)) {
                "stop" -> stopAlarm()
                "panic" -> soundNow()
            }
        }
    }

    private fun showPhoto() {
        val f = java.io.File(cacheDir, "guard/intruder.jpg")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
        photo.setImageBitmap(bmp)
        photo.visibility = if (bmp != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** The other phone's guard state, every few seconds. */
    private val poll = object : Runnable {
        override fun run() {
            val p = peer ?: return
            scope.launch {
                val s = runCatching { withContext(Dispatchers.IO) { link.remoteGuard(p, "status") } }
                s.onSuccess { j ->
                    val alarm = j.optString("state") == "ALARM"
                    status.text = when (j.optString("state")) {
                        "ALARM" -> "🚨 ${p.name}: ALARM RINGING"
                        "CHECKING" -> "👁 ${p.name}: checking who touched it…"
                        "ARMED" -> "🛡 ${p.name}: guarding"
                        "ARMING" -> "🛡 ${p.name}: guard starting…"
                        else -> "${p.name}: guard mode off"
                    }
                    status.setTextColor(Color.parseColor(if (alarm) "#C62828" else "#1B8A3A"))
                    showPhoto()
                }.onFailure { status.text = "⚠ ${p.name}: ${it.message ?: "not reachable"}" }
            }
            ui.postDelayed(this, 3_000)
        }
    }

    private fun send(action: String, body: JSONObject = JSONObject()) {
        val p = peer ?: return
        result.text = "…"
        scope.launch {
            result.text = runCatching { withContext(Dispatchers.IO) { link.remoteGuard(p, action, body) } }
                .fold({ it.optString("text", "Done.") }, { "⚠ ${it.message ?: it.javaClass.simpleName}" })
        }
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        send("say", JSONObject().put("text", text))
    }

    private fun speak() {
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your warning — it's said out loud on your other phone")
        runCatching { startActivityForResult(ask, SPEAK) }.onFailure { result.text = "⚠ No speech screen on this phone: type it instead." }
    }

    private fun type() {
        val box = EditText(this).apply { hint = "e.g. Put my phone down right now!" }
        AlertDialog.Builder(this)
            .setTitle("Say out loud on ${peer?.name ?: "the other phone"}")
            .setView(box)
            .setPositiveButton("Say it") { _, _ -> say(box.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Only Rex: this phone's own lock (fingerprint/PIN) first, then stop it there. */
    private fun stopAlarm() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure == true) {
            @Suppress("DEPRECATION")
            val confirm = keyguard.createConfirmDeviceCredentialIntent("Stop the alarm", "Unlock to confirm it's you")
            if (confirm != null) {
                runCatching { startActivityForResult(confirm, CONFIRM_STOP) }.onFailure { send("stop") }
                return
            }
        }
        AlertDialog.Builder(this)
            .setMessage("Stop the alarm on ${peer?.name ?: "your other phone"}?")
            .setPositiveButton("Stop it") { _, _ -> send("stop") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun soundNow() {
        AlertDialog.Builder(this)
            .setMessage("Sound the alarm on ${peer?.name ?: "your other phone"} now? It takes a photo of whoever holds it and starts the alarm.")
            .setPositiveButton("Sound it") { _, _ -> send("panic") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("A plain Activity's result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SPEAK -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { resultCode == RESULT_OK }?.let { say(it) }
            CONFIRM_STOP -> {
                if (resultCode == RESULT_OK) {
                    send("stop")
                } else {
                    result.text = "Not stopped (this phone wasn't unlocked)."
                }
            }
            RECORD -> {
                val uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                val p = peer ?: return
                result.text = "🎙 Sending your voice…"
                scope.launch {
                    result.text = runCatching {
                        withContext(Dispatchers.IO) {
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("couldn't read the recording")
                            if (bytes.size > MAX_CLIP_BYTES) error("the recording is too long — keep it under a minute")
                            link.remoteGuardVoice(p, bytes, extension(contentResolver.getType(uri))).optString("text", "Playing your voice.")
                        }
                    }.getOrElse { "⚠ ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }

    private fun extension(mime: String?): String = when {
        mime == null -> "m4a"
        "amr" in mime -> "amr"
        "3gp" in mime -> "3gp"
        "mpeg" in mime || "mp3" in mime -> "mp3"
        "ogg" in mime -> "ogg"
        "wav" in mime -> "wav"
        "aac" in mime -> "aac"
        else -> "m4a"
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** "stop" or "panic": do it as soon as the screen opens (from a voice command). */
        const val EXTRA_ACTION = "action"
        private const val SPEAK = 7501
        private const val CONFIRM_STOP = 7502
        private const val RECORD = 7503
        private const val MAX_CLIP_BYTES = 3_000_000
    }
}
