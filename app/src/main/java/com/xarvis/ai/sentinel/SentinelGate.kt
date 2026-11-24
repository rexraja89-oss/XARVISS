package com.xarvis.ai.sentinel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.xarvis.ai.faces.FaceMath
import com.xarvis.ai.faces.FacePrintsProvider
import com.xarvis.ai.faces.FaceStore
import com.xarvis.ai.guard.GuardMode
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.sentinel.Gatekeeper.Seen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Sentinel at the door (see [Gatekeeper]). Each time XARVIS opens on the S22 it looks through the front camera:
 * Rex → greeted by name; nobody in view (the phone on a table, Rex in through the remote view) → nothing; anyone
 * else → "Hello, stranger…", a code goes to Rex's other phone(s) with the stranger's picture, and it must be said or
 * typed within a minute, or the phone locks (XARVIS's Hands). Rex's fingerprint always lets him straight in.
 * The benco has no camera: [possible] is false there and nothing changes.
 * Switch: ☰ → 🎙 Voice → "Sentinel watches the door" (prefs settings/sentinelGate, default on).
 */
class SentinelGate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: DeviceLink,
    private val io: Io,
) {
    interface Io {
        /** Rex is controlling this phone through XARVIS's remote view right now. */
        fun remoteControlled(): Boolean
        fun speak(text: String, pitch: Float = 1f, rate: Float = 1f)
        suspend fun afterSpeaking()
        fun stopSpeaking()
        /** A line in this phone's chat. */
        fun post(text: String)
    }

    sealed class Ui {
        object Idle : Ui()
        /** The camera is looking (the eye scans). */
        object Looking : Ui()
        data class Welcome(val text: String) : Ui()
        data class Challenge(
            val seen: Seen, val deadline: Long, val triesLeft: Int, val sentTo: String?, val note: String,
            val listening: Boolean = false, val heard: String = "",
        ) : Ui()
        data class Granted(val text: String) : Ui()
        /** Out of time / tries. [phoneLocked] = the screen lock went on; else XARVIS just stays shut. */
        data class Locked(val phoneLocked: Boolean) : Ui()
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** Whether the door is shut: no chatting with XARVIS (typed or "Xarvis") until it opens. */
    fun closed(): Boolean = _ui.value is Ui.Challenge || _ui.value is Ui.Locked

    @Volatile private var verifiedAt = 0L
    @Volatile private var lockedSince = false
    @Volatile private var leftAt = 0L
    private var code = ""
    private var job: Job? = null
    private var listener: com.xarvis.ai.voice.VoiceCommand? = null
    private val main = Handler(Looper.getMainLooper())
    private var greetings = 0

    init {
        // The screen going off (or locking) means the next open is looked at again.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { lockedSince = true }
            }, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun enabled(): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setEnabled(on: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (!on) letIn(null)
    }

    /** This phone can do it: a front camera, face recognition (S22 build) and Rex's face taught. */
    fun possible(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT) &&
            HandsBridge.builtIn && GuardMode.owner(context) != null // the S22 build has the face engine

    /** XARVIS went out of sight. */
    fun left() {
        leftAt = System.currentTimeMillis()
        if (_ui.value is Ui.Welcome || _ui.value is Ui.Granted) _ui.value = Ui.Idle
        stopListening()
    }

    /** XARVIS came on screen. */
    fun opened() {
        val now = System.currentTimeMillis()
        val away = if (leftAt == 0L) Long.MAX_VALUE else now - leftAt
        val state = _ui.value
        if (state is Ui.Challenge) { if (!state.listening) listen(); return }
        if (state is Ui.Locked && !lockedSince) return
        if (state is Ui.Looking) return
        if (!enabled() || !possible()) return
        if (!Gatekeeper.needsCheck(now, verifiedAt, lockedSince, away)) return
        if (io.remoteControlled()) { letIn(null); return } // Rex, through the remote view
        _ui.value = Ui.Looking
        job?.cancel()
        job = scope.launch {
            runCatching { look() }.onFailure { android.util.Log.w("Sentinel", "look failed", it) }
            if (_ui.value == Ui.Looking) _ui.value = Ui.Idle
        }
    }

    private suspend fun look() {
        val owner = GuardMode.owner(context)
        val prints = owner?.let { FaceStore(context).all()[it] }.orEmpty()
        val engine = FacePrintsProvider.create(context)
        if (owner == null || prints.isEmpty() || engine == null) { _ui.value = Ui.Idle; return }
        if (!FrontLook.possible(context)) {
            runCatching { com.xarvis.ai.tools.PermissionGate.request(android.Manifest.permission.CAMERA) }
            if (!FrontLook.possible(context)) { _ui.value = Ui.Idle; return }
        }
        val sims = ArrayList<List<Float>>()
        var evidence: ByteArray? = null
        withContext(Dispatchers.Default) {
            FrontLook.pictures(context) { jpeg ->
                val bmp = FrontLook.upright(jpeg)
                val faces = bmp?.let { runCatching { engine.faces(it) }.getOrDefault(emptyList()) }.orEmpty()
                val s = faces.map { f -> FaceMath.bestMatch(f, mapOf(owner to prints)).similarity }
                synchronized(sims) { sims += s }
                if (s.isNotEmpty() && evidence == null) evidence = jpeg
                s.any { it >= FaceMath.SURE } // Rex: no need for more pictures
            }
        }
        if (_ui.value != Ui.Looking) return // switched off meanwhile
        when (val seen = Gatekeeper.verdict(synchronized(sims) { sims.toList() })) {
            Seen.OWNER -> welcome()
            Seen.NOBODY -> _ui.value = Ui.Idle // nobody to ask; looked at again next time
            else -> challenge(seen, evidence)
        }
    }

    private fun welcome(text: String = Gatekeeper.greeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), greetings++)) {
        letIn(null)
        _ui.value = Ui.Welcome(text)
        io.speak(text, pitch = 0.95f)
        main.postDelayed({ if (_ui.value == Ui.Welcome(text)) _ui.value = Ui.Idle }, 5_000)
    }

    private fun letIn(granted: String?) {
        job?.cancel()
        stopListening()
        verifiedAt = System.currentTimeMillis()
        lockedSince = false
        code = ""
        setWakeHold(false)
        _ui.value = if (granted != null) Ui.Granted(granted) else Ui.Idle
        if (granted != null) main.postDelayed({ if (_ui.value == Ui.Granted(granted)) _ui.value = Ui.Idle }, 4_000)
    }

    private suspend fun challenge(seen: Seen, evidence: ByteArray?) {
        code = Gatekeeper.newCode()
        lockedSince = false
        setWakeHold(true)
        _ui.value = Ui.Challenge(seen, System.currentTimeMillis() + Gatekeeper.DEADLINE_MS, Gatekeeper.MAX_TRIES, null, "Sending the code…")
        io.speak(Gatekeeper.opening(seen), pitch = 0.8f, rate = 0.92f)
        // The code (and who's asking) to every phone of Rex's; the first that takes it is named.
        val me = android.os.Build.MODEL ?: "this phone"
        val peers = link.pairedPeers()
        val line = "🔑 Someone opened XARVIS on $me. If you allow them in, the code is $code — good for one minute. " +
            "If not, do nothing: the phone locks itself."
        var sentTo: String? = null
        withContext(Dispatchers.IO) {
            for (p in peers) {
                val ok = withTimeoutOrNull(8_000) { runCatching { link.sendTaskNews(p, line) }.isSuccess } == true
                if (ok && sentTo == null) sentTo = p.name
            }
        }
        val photo = evidence
        if (photo != null) scope.launch(Dispatchers.IO) {
            peers.forEach { p -> runCatching { link.sendFile(p, "sentinel-${System.currentTimeMillis()}.jpg", "image/jpeg", photo) } }
        }
        val deadline = System.currentTimeMillis() + Gatekeeper.DEADLINE_MS
        val c = _ui.value as? Ui.Challenge ?: return
        _ui.value = c.copy(deadline = deadline, sentTo = sentTo,
            note = if (sentTo != null) "The code is on $sentTo." else "Rex's other phone didn't answer. Rex: your fingerprint.")
        io.afterSpeaking()
        io.speak(Gatekeeper.ask(sentTo), pitch = 0.8f, rate = 0.92f)
        io.afterSpeaking()
        listen()
        // The clock: out of time → lock.
        while (true) {
            val now = _ui.value as? Ui.Challenge ?: return
            if (System.currentTimeMillis() >= now.deadline) { shut(); return }
            delay(250)
        }
    }

    /** A code typed on the keypad or heard. */
    fun tried(said: String) {
        val c = _ui.value as? Ui.Challenge ?: return
        if (code.isEmpty()) return
        if (Gatekeeper.matches(said, code)) {
            tell("✅ The code was given on ${android.os.Build.MODEL}: they're in until the phone locks.")
            io.stopSpeaking()
            io.speak(Gatekeeper.GRANTED)
            letIn(Gatekeeper.GRANTED)
            return
        }
        if (Gatekeeper.digitsIn(said).length < 4) { if (!c.listening) listen(); return } // not a try: words, a cough
        val left = c.triesLeft - 1
        if (left <= 0) { scope.launch { shut() }; return }
        _ui.value = c.copy(triesLeft = left, heard = said)
        io.speak(Gatekeeper.wrong(left), pitch = 0.8f)
        scope.launch { io.afterSpeaking(); listen() }
    }

    /** Rex's fingerprint (or the unlock done by hand on a locked-out XARVIS). */
    fun ownerProved() {
        val wasLocked = _ui.value is Ui.Locked
        if (_ui.value is Ui.Challenge) tell("✅ It was you — fingerprint. Stand down.")
        io.stopSpeaking()
        if (wasLocked) letIn(null) else welcome(Gatekeeper.OWNER_BY_FINGER)
    }

    private suspend fun shut() {
        stopListening()
        code = ""
        val locked = HandsBridge.service?.globalAction("lock") == true
        _ui.value = Ui.Locked(locked)
        withContext(Dispatchers.Main) {
            if (!locked) io.speak(Gatekeeper.LOCKED_NO_HANDS, pitch = 0.8f) else io.speak(Gatekeeper.LOCKING, pitch = 0.8f)
        }
        tell(if (locked) "🔒 Nobody gave the code on ${android.os.Build.MODEL}: I locked the phone. Your pattern opens it."
            else "🔒 Nobody gave the code on ${android.os.Build.MODEL}. XARVIS Hands is off there, so I couldn't lock the phone — XARVIS stays shut.")
        io.post("🔒 Sentinel: someone tried to open XARVIS without the code.")
        setWakeHold(false)
    }

    private fun tell(text: String) {
        val peers = link.pairedPeers()
        scope.launch(Dispatchers.IO) { peers.forEach { p -> runCatching { link.sendTaskNews(p, text) } } }
    }

    /** Listens for the code, again and again until it's given or time is up. */
    private fun listen(): Boolean = main.post {
        val c = _ui.value as? Ui.Challenge ?: return@post
        if (c.listening || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@post
        _ui.value = c.copy(listening = true)
        listener = com.xarvis.ai.voice.VoiceCommand(context, onHearing = { part ->
            (_ui.value as? Ui.Challenge)?.let { _ui.value = it.copy(heard = part) }
        }) { said ->
            listener = null
            val now = _ui.value as? Ui.Challenge ?: return@VoiceCommand
            _ui.value = now.copy(listening = false)
            if (said != null) tried(said)
            else if (System.currentTimeMillis() < now.deadline) main.postDelayed({ listen() }, 400)
        }.also { it.start() }
    }

    private fun stopListening(): Boolean = main.post {
        listener?.cancel()
        listener = null
    }

    /** "Xarvis" stays off the microphone while the Sentinel listens for the code. */
    private var holding = false
    private fun setWakeHold(on: Boolean) {
        if (on == holding) return
        holding = on
        com.xarvis.ai.service.WakeWord.paused = on
    }

    private companion object {
        const val KEY = "sentinelGate"
    }
}
