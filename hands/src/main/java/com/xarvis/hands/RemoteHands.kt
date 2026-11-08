package com.xarvis.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/**
 * Remote control of THIS phone (the benco) by Rex's other phone, through XARVIS. The XARVIS app on this
 * phone sends the commands (broadcast "com.xarvis.hands.action.REMOTE", signature-protected — only Rex's
 * same-key apps can) and gets answers back (broadcast to com.xarvis.ai, which only accepts them from
 * same-key apps). XARVIS on this phone decides whether remote control is allowed (Rex's switch there).
 *
 * While it runs, a red "… is controlling this phone · STOP" banner shows (it also keeps the screen on).
 */
object RemoteHands {
    const val ACTION = "com.xarvis.hands.action.REMOTE"
    private const val RESULT_ACTION = "com.xarvis.ai.action.HANDS_RESULT"
    private const val RESULT_PERMISSION = "com.xarvis.ai.permission.EYES"
    private const val XARVIS = "com.xarvis.ai"

    private val main = Handler(Looper.getMainLooper())

    fun handle(intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val id = intent.getLongExtra("id", 0L)
        val s = HandsService.instance
        if (s == null) {
            HandsService.log("Remote control asked ($op), but Hands is off — turn it on.")
            return
        }
        fun f(key: String) = intent.getFloatExtra(key, 0f)
        when (op) {
            "shot" -> shot(s, id, intent.getIntExtra("max", 1000))
            "gesture" -> gesture(s, intent.getStringExtra("kind") ?: "tap", f("x"), f("y"), f("x2"), f("y2"), intent.getLongExtra("ms", 300))
            "key" -> globalAction(s, intent.getStringExtra("name").orEmpty())
            "text" -> reply(s, id) { putExtra("ok", typeText(s, intent.getStringExtra("text").orEmpty(), intent.getBooleanExtra("enter", false))) }
            "down" -> finger.down(s, f("x"), f("y"))
            "move" -> finger.move(s, f("x"), f("y"))
            "up" -> finger.up(s, f("x"), f("y"))
            "banner" -> banner(s, intent.getStringExtra("by"))
            "read" -> reply(s, id) { putExtra("text", screenText(s)) }
            "tap" -> reply(s, id) { putExtra("ok", tapText(s, intent.getStringExtra("label").orEmpty())) }
            "selected" -> reply(s, id) { putExtra("text", selectedText(s)) }
            "elements" -> reply(s, id) { putExtra("text", elements(s)) }
            "ask" -> ask(s, intent.getStringExtra("question").orEmpty()) { yes -> reply(s, id) { putExtra("yes", yes) } }
            "open" -> reply(s, id) {
                val launch = s.packageManager.getLaunchIntentForPackage(intent.getStringExtra("pkg").orEmpty())
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("ok", launch != null && runCatching { s.startActivity(launch) }.isSuccess)
            }
        }
    }

    private fun reply(context: Context, id: Long, fill: Intent.() -> Unit) {
        context.sendBroadcast(Intent(RESULT_ACTION).setPackage(XARVIS).putExtra("id", id).apply(fill), RESULT_PERMISSION)
    }

    // ---- the screen ----

    private fun shot(s: HandsService, id: Long, maxSide: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reply(s, id) {}
            return
        }
        shoot(s, id, maxSide)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun shoot(s: HandsService, id: Long, maxSide: Int) {
        s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val jpeg = runCatching {
                    val full = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return@runCatching null
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(full.width, full.height))
                    val small = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
                    ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 55, it) }.toByteArray()
                }.getOrNull()
                buffer.close()
                reply(s, id) { jpeg?.let { putExtra("jpg", it) } }
            }

            override fun onFailure(errorCode: Int) = reply(s, id) {}
        })
    }

    private fun gesture(s: HandsService, kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply {
            moveTo(x, y)
            if (kind == "swipe") lineTo(x2, y2)
        }
        val duration = when (kind) {
            "tap" -> 60L
            "long" -> 800L
            else -> ms.coerceIn(80L, 3000L)
        }
        s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun globalAction(s: HandsService, name: String) {
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return
        }
        s.performGlobalAction(action)
    }

    private fun typeText(s: HandsService, text: String, enter: Boolean): Boolean {
        val field = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return false
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

    // ---- reading and tapping by label ----

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()

    private fun screenText(s: HandsService): String {
        val root = s.rootInActiveWindow ?: return ""
        val items = ArrayList<Pair<Rect, String>>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || items.size > 400) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty()) items += Rect().also { n.getBoundsInScreen(it) } to t
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val app = root.packageName?.toString()?.let { pkg ->
            runCatching { s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val lines = items.sortedWith(compareBy({ it.first.top }, { it.first.left })).map { it.second }.distinct()
        return ("[$app]\n" + lines.joinToString("\n")).take(6000)
    }

    private fun tapText(s: HandsService, wanted: String): Boolean {
        if (wanted.isBlank() || DANGER.containsMatchIn(wanted)) return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val t = label(n)
            if (t.isNotEmpty() && t.contains(wanted, ignoreCase = true) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val node = find(s.rootInActiveWindow) ?: return false
        if (DANGER.containsMatchIn(label(node))) return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        gesture(s, "tap", r.exactCenterX(), r.exactCenterY(), 0f, 0f, 60)
        return true
    }

    private fun selectedText(s: HandsService): String? {
        val field = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (field.isShowingHintText) return null
        val all = field.text?.toString() ?: return null
        val start = field.textSelectionStart
        val end = field.textSelectionEnd
        return if (start in 0 until end && end <= all.length) all.substring(start, end) else all
    }

    /** What's on the screen for XARVIS's brain: JSON {"app","w","h","els":[{"t","c","e","p","x","y"}]}. */
    private fun elements(s: HandsService): String {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        s.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        val out = org.json.JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels)
        val root = s.rootInActiveWindow ?: return out.put("app", "").put("els", org.json.JSONArray()).toString()
        class E(val r: Rect, val label: String, val tap: Boolean, val edit: Boolean, val pw: Boolean)
        val found = ArrayList<E>()
        fun tappable(n: AccessibilityNodeInfo): Boolean {
            var c: AccessibilityNodeInfo? = n
            var d = 0
            while (c != null && d < 6) {
                if (c.isClickable || c.isLongClickable) return true
                c = c.parent
                d++
            }
            return false
        }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || found.size > 150) return
            if (n.isVisibleToUser) {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val hint = n.hintText?.toString()?.trim().orEmpty()
                val label = when {
                    n.isEditable && (n.isShowingHintText || text.isEmpty()) -> hint.ifEmpty { desc.ifEmpty { "text box" } }
                    text.isNotEmpty() -> text
                    else -> desc
                }
                if (label.isNotEmpty() || n.isEditable) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (r.width() > 0 && r.height() > 0) found += E(r, label.replace('\n', ' ').take(80), tappable(n), n.isEditable, n.isPassword)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val app = root.packageName?.toString()?.let { pkg ->
            runCatching { s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val els = org.json.JSONArray()
        found.sortedWith(compareBy({ it.r.top }, { it.r.left })).distinctBy { it.label + it.r.centerX() / 20 + "," + it.r.centerY() / 20 }
            .take(60).forEach { e ->
                els.put(
                    org.json.JSONObject().put("t", e.label).put("c", e.tap).put("e", e.edit).put("p", e.pw)
                        .put("x", e.r.centerX()).put("y", e.r.centerY()),
                )
            }
        return out.put("app", app).put("els", els).toString()
    }

    /** A yes/no question over whatever app is open (XARVIS checking before Send, Delete…); no after 60 s. */
    private var askView: View? = null

    private fun ask(s: HandsService, question: String, onAnswer: (Boolean) -> Unit) {
        val wm = s.getSystemService(WindowManager::class.java)
        if (wm == null) {
            onAnswer(false)
            return
        }
        askView?.let { runCatching { wm.removeView(it) } }
        var answered = false
        fun answer(yes: Boolean) {
            if (answered) return
            answered = true
            askView?.let { runCatching { wm.removeView(it) } }
            askView = null
            HandsService.log("Rex answered ${if (yes) "YES" else "NO"}: $question")
            onAnswer(yes)
        }
        val d = s.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2101418"))
            setPadding(pad, pad, pad, pad)
            addView(TextView(s).apply { text = "🤖 XARVIS asks:\n$question"; setTextColor(Color.WHITE); textSize = 17f })
            addView(LinearLayout(s).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(android.widget.Button(s).apply { text = "✓ Yes"; setOnClickListener { answer(true) } },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.Button(s).apply { text = "✗ No"; setOnClickListener { answer(false) } },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(box, params); askView = box }.onFailure { answer(false) }
        main.postDelayed({ answer(false) }, 60_000)
    }

    private val DANGER = Regex("pay|otp|password|passcode|upi|send money|signature|bank|card number|cvv|pin\\b", RegexOption.IGNORE_CASE)

    // ---- the red banner (also keeps the screen on while it shows) ----

    private var banner: View? = null

    private fun banner(s: HandsService, by: String?) {
        val wm = s.getSystemService(WindowManager::class.java) ?: return
        banner?.let { runCatching { wm.removeView(it) } }
        banner = null
        if (by == null) return
        val d = s.resources.displayMetrics.density
        val pill = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E6C62828"))
            setPadding((12 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            addView(TextView(s).apply { text = "🖥 $by is controlling this phone  "; setTextColor(Color.WHITE); textSize = 13f })
            addView(TextView(s).apply {
                text = "  STOP  "; setTextColor(Color.parseColor("#C62828")); textSize = 13f
                setBackgroundColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    HandsService.log("STOP pressed: remote control ended.")
                    reply(s, 0L) { putExtra("stop", true) }
                    banner(s, null)
                }
            })
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (36 * d).toInt()
        }
        runCatching { wm.addView(pill, params); banner = pill }.onFailure { HandsService.log("Couldn't show the banner: ${it.message}") }
    }

    // ---- a live finger: one gesture continued as the finger moves ----

    private val finger = object {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var at = PointF()
        private var downAt = PointF()
        private var downTime = 0L
        private var lastTime = 0L
        private var busy = false
        private var broken = false
        private var pendingMove: PointF? = null
        private var pendingUp: PointF? = null
        private var service: HandsService? = null

        private val done = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                busy = false
                next()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                busy = false
                broken = true
                fallback()
            }
        }

        fun down(s: HandsService, x: Float, y: Float) {
            service = s
            pendingMove = null
            pendingUp = null
            broken = false
            at = PointF(x, y)
            downAt = PointF(x, y)
            downTime = SystemClock.uptimeMillis()
            lastTime = downTime
            val first = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
            stroke = first
            dispatch(first)
        }

        fun move(s: HandsService, x: Float, y: Float) {
            service = s
            pendingMove = PointF(x, y)
            if (!busy) next()
        }

        fun up(s: HandsService, x: Float, y: Float) {
            service = s
            pendingUp = PointF(x, y)
            if (!busy) next()
        }

        private fun dispatch(st: GestureDescription.StrokeDescription) {
            val s = service ?: return
            busy = true
            if (!s.dispatchGesture(GestureDescription.Builder().addStroke(st).build(), done, main)) {
                busy = false
                broken = true
                fallback()
            }
        }

        private fun next() {
            if (broken) {
                fallback()
                return
            }
            val previous = stroke ?: return
            val up = pendingUp
            val to = up ?: pendingMove ?: return
            val now = SystemClock.uptimeMillis()
            val path = Path().apply {
                moveTo(at.x, at.y)
                lineTo(to.x, to.y)
            }
            val nextStroke = try {
                previous.continueStroke(path, 0, (now - lastTime).coerceIn(8L, 100L), up == null)
            } catch (e: Exception) {
                broken = true
                fallback()
                return
            }
            stroke = if (up == null) nextStroke else null
            at = to
            lastTime = now
            pendingMove = null
            if (up != null) pendingUp = null
            dispatch(nextStroke)
        }

        private fun fallback() {
            val up = pendingUp ?: return
            val s = service ?: return
            pendingUp = null
            stroke = null
            pendingMove = null
            val held = SystemClock.uptimeMillis() - downTime
            val moved = kotlin.math.hypot(up.x - downAt.x, up.y - downAt.y) > 20f
            gesture(s, if (moved) "swipe" else if (held > 600) "long" else "tap", downAt.x, downAt.y, up.x, up.y, held)
        }
    }
}
