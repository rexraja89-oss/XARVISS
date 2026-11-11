package com.xarvis.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * XARVIS Remote's hands on this phone: taps, a live finger, typing, Back/Home/…/Lock, and screenshots (pictures
 * mode, before live video is allowed). Also the red ✱ in the status bar while someone is connected — tap it for
 * who and STOP. (The same tricks as XARVIS Hands; this copy lives here so it works when XARVIS doesn't.)
 */
class RemoteHands : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        // A phone that's set to be controlled comes back online with its hands (Android may have restarted us).
        if (RemotePrefs.hosting(this)) RemoteService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- taps, swipes, keys, text ----

    fun gesture(kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            if (kind == "swipe") lineTo(x2, y2)
        }
        val duration = when (kind) {
            "tap" -> 60L
            "long" -> 800L
            else -> ms.coerceIn(80L, 3000L)
        }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun typeText(text: String, enter: Boolean): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return false
        val before = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + text)
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return false
        }
        if (enter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    /** The selected text in the focused box (or all of it), for the viewer's "Copy from it". */
    fun selectedText(): String? {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (field.isShowingHintText) return null
        val all = field.text?.toString() ?: return null
        val start = field.textSelectionStart
        val end = field.textSelectionEnd
        return if (start in 0 until end && end <= all.length) all.substring(start, end) else all
    }

    /** Where a pattern's dots / PIN keys are (Android hides those screens from pictures, not from Accessibility). */
    fun lockPad(w: Int, h: Int): String? = runCatching {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        runCatching { windows }.getOrNull()?.forEach { win -> win.root?.let { roots += it } }
        com.xarvis.ai.hands.LockPad.scan(roots, w, h)?.toString()
    }.getOrNull()

    // ---- a picture of the screen (Android 11+; Android allows about 3 a second) ----

    fun screenshot(onFrame: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onFrame(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val frame = runCatching {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                buffer.close()
                onFrame(frame)
            }

            override fun onFailure(errorCode: Int) = onFrame(null)
        })
    }

    // ---- a live finger: one gesture, continued as the other phone's finger moves ----

    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveAt = PointF()
    private var liveDown = PointF()
    private var liveDownTime = 0L
    private var liveLastTime = 0L
    private var liveBusy = false
    private var liveBroken = false
    private var liveMove: PointF? = null
    private var liveUp: PointF? = null

    private val liveDone = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            liveBusy = false
            liveNext()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            liveBusy = false
            liveBroken = true
            liveFallback()
        }
    }

    fun touchDown(x: Float, y: Float) {
        handler.post {
            liveMove = null
            liveUp = null
            liveBroken = false
            liveAt = PointF(x, y)
            liveDown = PointF(x, y)
            liveDownTime = SystemClock.uptimeMillis()
            liveLastTime = liveDownTime
            val s = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
            liveStroke = s
            liveDispatch(s)
        }
    }

    fun touchMove(x: Float, y: Float) {
        handler.post {
            liveMove = PointF(x, y)
            if (!liveBusy) liveNext()
        }
    }

    fun touchUp(x: Float, y: Float) {
        handler.post {
            liveUp = PointF(x, y)
            if (!liveBusy) liveNext()
        }
    }

    private fun liveDispatch(stroke: GestureDescription.StrokeDescription) {
        liveBusy = true
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), liveDone, handler)) {
            liveBusy = false
            liveBroken = true
            liveFallback()
        }
    }

    private fun liveNext() {
        if (liveBroken) {
            liveFallback()
            return
        }
        val previous = liveStroke ?: return
        val up = liveUp
        val to = up ?: liveMove ?: return
        val now = SystemClock.uptimeMillis()
        val duration = (now - liveLastTime).coerceIn(8L, 100L)
        val path = Path().apply {
            moveTo(liveAt.x, liveAt.y)
            lineTo(to.x, to.y)
        }
        val next = try {
            previous.continueStroke(path, 0, duration, up == null)
        } catch (e: Exception) {
            liveBroken = true
            liveFallback()
            return
        }
        liveStroke = if (up == null) next else null
        liveAt = to
        liveLastTime = now
        liveMove = null
        if (up != null) liveUp = null
        liveDispatch(next)
    }

    /** If Android dropped the live gesture: do the whole touch at once when the finger lifts. */
    private fun liveFallback() {
        val up = liveUp ?: return
        liveUp = null
        liveStroke = null
        liveMove = null
        val held = SystemClock.uptimeMillis() - liveDownTime
        val moved = Math.hypot((up.x - liveDown.x).toDouble(), (up.y - liveDown.y).toDouble()) > 20.0
        gesture(if (moved) "swipe" else if (held > 600) "long" else "tap", liveDown.x, liveDown.y, up.x, up.y, held)
    }

    // ---- the red ✱ while someone is connected ----

    private var banner: View? = null
    private var bannerShrink: Runnable? = null

    /**
     * A small red ✱ in the status bar while [by] controls this phone (null hides it). Tapping it shows who, and STOP
     * for 5 s; the rest of the screen stays free (like XARVIS's own ✱, which Rex asked to be "star size").
     */
    fun controlBanner(by: String?, onStop: () -> Unit) {
        handler.post {
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            banner?.let { runCatching { wm.removeView(it) } }
            banner = null
            bannerShrink?.let { handler.removeCallbacks(it) }
            bannerShrink = null
            if (by == null) return@post
            val d = resources.displayMetrics.density
            val red = Color.parseColor("#FF1744")
            val star = TextView(this).apply {
                text = "✱"; textSize = 14f; setTextColor(red); setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                contentDescription = "$by is controlling this phone"
            }
            val label = TextView(this).apply {
                text = "$by is controlling this phone  "; setTextColor(Color.WHITE); textSize = 12f
                gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            }
            val stop = TextView(this).apply {
                text = "  STOP  "; setTextColor(Color.parseColor("#C62828")); textSize = 12f
                setBackgroundColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
                setOnClickListener { onStop() }
            }
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, (4 * d).toInt(), 0)
                addView(star, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(label)
                addView(stop)
            }
            fun open(show: Boolean) {
                label.visibility = if (show) View.VISIBLE else View.GONE
                stop.visibility = label.visibility
                star.setTextColor(if (show) Color.WHITE else red)
                pill.setBackgroundColor(if (show) Color.parseColor("#E6C62828") else Color.TRANSPARENT)
            }
            star.setOnClickListener {
                open(true)
                bannerShrink?.let { handler.removeCallbacks(it) }
                bannerShrink = Runnable { open(false) }.also { handler.postDelayed(it, 5_000) }
            }
            val barId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val barHeight = if (barId > 0) resources.getDimensionPixelSize(barId) else (24 * d).toInt()
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, barHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Left of centre: XARVIS's own ✱ sits just right of the S22's camera hole.
                x = (-30 * d).toInt()
                y = 0
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            runCatching { wm.addView(pill, params); banner = pill }
        }
    }

    companion object {
        /** The running service, or null while its Accessibility switch is off. */
        @Volatile var instance: RemoteHands? = null
            private set
    }
}
