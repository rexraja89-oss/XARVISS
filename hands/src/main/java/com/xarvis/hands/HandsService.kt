package com.xarvis.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * XARVIS Hands — the Accessibility service.
 *
 * It can now DO two things, both only when Rex asks from the Hands app and while he watches:
 *   1. a self-tap test ([dispatchTap]) — proof it can perform a gesture, and
 *   2. place a call inside imo ([startCallInApp]) — open imo, find the contact, tap the call button.
 *
 * Everything it does is written to [logLines], shown live on the Hands screen, so if an app's layout
 * is different we can see exactly what it saw and fix the selectors. Safety rail: it never taps a
 * button that looks like payment / OTP / password / bank ([isDangerous]).
 */
class HandsService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var task: CallTask? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("Service connected.")
        // A call that arrived from XARVIS before Hands was turned on: run it now.
        pendingCall?.let { (app, contact, video) ->
            pendingCall = null
            log("Running the call XARVIS asked for earlier: '$contact'.")
            handler.postDelayed({ startCallInApp(app, contact, video) }, 500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        eventCount++
        lastEventType = AccessibilityEvent.eventTypeToString(event.eventType)
        lastEventPackage = event.packageName?.toString() ?: "?"
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Capability 1: a single controlled tap at a screen point (the self-tap test) ----

    fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        log("tap ($x, $y) -> dispatched=$ok")
    }

    // ---- Capability 2: place a call inside an app (imo or WhatsApp) ----

    /** Launch [app]'s package, then walk its screens to find [contact] and tap the call button. */
    fun startCallInApp(app: String, contact: String, video: Boolean) {
        val name = contact.trim()
        if (name.isEmpty()) { log("No contact name given."); return }
        val whatsapp = app.trim().lowercase().let { it.contains("whats") || it == "wa" }
        val label = if (whatsapp) "WhatsApp" else "imo"
        log("TASK: ${if (video) "video-" else ""}call '$name' in $label")
        val candidates = if (whatsapp)
            listOf("com.whatsapp", "com.whatsapp.w4b")
        else
            listOf("com.imo.android.imoim", "com.imo.android.imoimbeta", "com.imo.android.imoimhd")
        var launched: String? = null
        for (pkg in candidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                launched = pkg
                break
            }
        }
        if (launched == null) { log("$label not found on this phone (or not visible)."); return }
        log("Launched $launched — waiting for it to open…")
        // The package prefix we expect the app's windows under, to know its screen is in front.
        task = CallTask(name, if (whatsapp) "com.whatsapp" else "com.imo", video)
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, 1300)
    }

    fun cancelTask() {
        task = null
        handler.removeCallbacks(runner)
        log("Task cancelled.")
    }

    private enum class State { FIND_CONTACT, OPEN_SEARCH, TYPE_SEARCH, FIND_CALL }

    private inner class CallTask(val name: String, val pkg: String, val video: Boolean) {
        var state = State.FIND_CONTACT
        var tries = 0
        var searched = false
    }

    private val runner = object : Runnable {
        override fun run() {
            val t = task ?: return
            t.tries++
            if (t.tries > 30) { log("Gave up after ${t.tries} tries."); task = null; return }

            val root = rootInActiveWindow
            if (root == null) { log("No window yet…"); handler.postDelayed(this, 600); return }
            val pkgNow = root.packageName?.toString() ?: ""
            if (!pkgNow.startsWith(t.pkg)) {
                log("Waiting for the app (on $pkgNow)…"); handler.postDelayed(this, 700); return
            }

            when (t.state) {
                State.FIND_CONTACT -> {
                    val hit = findByText(root, t.name)
                    when {
                        hit != null -> {
                            log("Found '${t.name}'. Opening the chat.")
                            clickNode(hit); t.state = State.FIND_CALL; t.tries = 0
                        }
                        !t.searched -> {
                            val search = findByKeywords(root, listOf("search"))
                            if (search != null) { log("Opening search."); clickNode(search); t.state = State.TYPE_SEARCH }
                            else log("'${t.name}' not on screen. Visible: ${sampleTexts(root)}")
                        }
                        else -> log("Still can't find '${t.name}'. Visible: ${sampleTexts(root)}")
                    }
                }
                State.TYPE_SEARCH -> {
                    val edit = findEditable(root)
                    if (edit != null) {
                        log("Typing '${t.name}' into search."); setText(edit, t.name)
                        t.searched = true; t.state = State.FIND_CONTACT; t.tries = 0
                    } else log("Waiting for the search box…")
                }
                State.FIND_CALL -> {
                    val call = findCallButton(root, t.video)
                    if (call != null) {
                        log("Found the call button — placing the call.")
                        clickNode(call); log("DONE ✓"); task = null; return
                    } else log("Looking for the call button… Visible: ${sampleTexts(root)}")
                }
                State.OPEN_SEARCH -> {}
            }
            handler.postDelayed(this, 750)
        }
    }

    // ---- node helpers ----

    private fun text(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()

    private fun findFirst(
        root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val r = findFirst(root.getChild(i), pred)
            if (r != null) return r
        }
        return null
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = node
        while (c != null) { if (c.isClickable) return c; c = c.parent }
        return null
    }

    private fun findByText(root: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
        val match = findFirst(root) { n -> text(n).contains(name, ignoreCase = true) && text(n).isNotEmpty() }
        return match?.let { clickableSelfOrAncestor(it) ?: it }
    }

    private fun findByKeywords(root: AccessibilityNodeInfo?, keys: List<String>): AccessibilityNodeInfo? {
        val match = findFirst(root) { n ->
            val s = text(n).lowercase(); s.isNotEmpty() && keys.any { s.contains(it) } && !isDangerous(s)
        }
        return match?.let { clickableSelfOrAncestor(it) ?: it }
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findFirst(root) { it.isEditable || (it.className?.contains("EditText") == true) }

    private fun findCallButton(root: AccessibilityNodeInfo?, video: Boolean): AccessibilityNodeInfo? {
        val want = if (video) listOf("video call", "video") else listOf("audio call", "voice call", "call")
        val match = findFirst(root) { n ->
            val s = text(n).lowercase()
            if (s.isEmpty() || isDangerous(s)) return@findFirst false
            if (video) want.any { s.contains(it) }
            else want.any { s.contains(it) } && !s.contains("video") && !s.contains("missed") && !s.contains("call log")
        }
        return match?.let { clickableSelfOrAncestor(it) ?: it }
    }

    private fun sampleTexts(root: AccessibilityNodeInfo?): String {
        val out = ArrayList<String>()
        fun rec(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= 8) return
            val s = text(n)
            if (s.isNotEmpty() && (n.isClickable || clickableSelfOrAncestor(n) != null)) out.add(s.take(24))
            for (i in 0 until n.childCount) rec(n.getChild(i))
        }
        rec(root)
        return if (out.isEmpty()) "(nothing tappable)" else out.joinToString(" | ")
    }

    private fun clickNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val label = text(node)
        if (isDangerous(label)) { log("SAFETY: refused to tap '$label'."); return }
        val target = if (node.isClickable) node else clickableSelfOrAncestor(node)
        if (target != null && target.isClickable) {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log("click '${text(target).take(20)}' -> $ok")
            if (ok) return
        }
        val r = Rect(); (target ?: node).getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) dispatchTap(r.exactCenterX(), r.exactCenterY())
    }

    private fun setText(node: AccessibilityNodeInfo, s: String) {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        log("setText '$s' -> $ok")
    }

    private fun isDangerous(s: String): Boolean = DANGER.containsMatchIn(s)

    companion object {
        private const val TAG = "XarvisHands"
        private val DANGER = Regex(
            "pay|otp|password|passcode|upi|send money|signature|bank|card number|cvv|pin\\b",
            RegexOption.IGNORE_CASE,
        )

        @Volatile var instance: HandsService? = null; private set
        /** A call (app, contact, video) requested before the service was connected; run on connect. */
        @Volatile var pendingCall: Triple<String, String, Boolean>? = null
        @Volatile var eventCount: Long = 0L; private set
        @Volatile var lastEventType: String = ""; private set
        @Volatile var lastEventPackage: String = ""; private set
        @Volatile var lastEventTime: Long = 0L; private set

        fun isRunning(): Boolean = instance != null

        /** A live log of what Hands did, newest last; the Hands screen shows it. */
        val logLines: MutableList<String> = Collections.synchronizedList(ArrayList())

        fun log(m: String) {
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + m
            synchronized(logLines) {
                logLines.add(line)
                while (logLines.size > 60) logLines.removeAt(0)
            }
            Log.i(TAG, m)
        }
    }
}
