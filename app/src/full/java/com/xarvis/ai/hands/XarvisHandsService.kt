package com.xarvis.ai.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * XARVIS Hands, inside XARVIS (S22 build): the Accessibility service, ported from the separate
 * XARVIS Hands app. It places a call inside imo or WhatsApp ([startCallInApp]: open the app, find the
 * contact, tap the call button), only when Rex asks. Everything it does goes to [HandsBridge.log],
 * shown on ☰ → XARVIS Hands, so if an app's layout changes we can see what it saw. Safety rail: it
 * never taps anything that looks like payment / OTP / password / bank ([isDangerous]).
 */
class XarvisHandsService : AccessibilityService(), HandsBridge.Hands {

    private val handler = Handler(Looper.getMainLooper())
    private var task: CallTask? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        HandsBridge.service = this
        log("Service connected.")
        // A call Rex asked for before Hands was switched on: run it now.
        HandsBridge.pendingCall?.let { (app, contact, video) ->
            HandsBridge.pendingCall = null
            log("Running the call XARVIS asked for earlier: '$contact'.")
            handler.postDelayed({ startCallInApp(app, contact, video) }, 500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Guard mode: someone tapping, scrolling or typing on the phone counts as touching it.
        if (event != null && com.xarvis.ai.guard.GuardMode.armed) when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> com.xarvis.ai.guard.GuardMode.trigger(this, "screen touched")
        }
        // Teach mode ("show me once"): what Rex does goes to the recorder (never a password's text).
        val teach = HandsBridge.teach
        if (event != null && teach != null) runCatching { teachEvent(event)?.let(teach) }
    }

    private val labels = HashMap<String, String>()

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun screenSize(): Pair<Float, Float> {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return dm.widthPixels.coerceAtLeast(1).toFloat() to dm.heightPixels.coerceAtLeast(1).toFloat()
    }

    /** The name a node shows (its own text, else the first text inside it, as the screen listing names it) and its id. */
    private fun nameOf(node: AccessibilityNodeInfo?): Pair<String, String> {
        if (node == null) return "" to ""
        fun own(n: AccessibilityNodeInfo): String =
            (n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }).replace('\n', ' ').take(80)
        fun id(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
        own(node).takeIf { it.isNotEmpty() }?.let { return it to id(node) }
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to 1) }
        while (queue.isNotEmpty()) {
            val (n, d) = queue.removeFirst()
            own(n).takeIf { it.isNotEmpty() }?.let { return it to id(n) }
            if (d < 3) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to d + 1) }
        }
        return "" to id(node)
    }

    private fun teachEvent(e: AccessibilityEvent): com.xarvis.ai.task.TeachEvent? {
        val pkg = e.packageName?.toString() ?: return null
        val now = System.currentTimeMillis()
        return when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> com.xarvis.ai.task.TeachEvent.Window(now, pkg, appLabel(pkg))
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = e.source
                var (label, id) = nameOf(node)
                if (label.isEmpty()) label = (e.text?.joinToString(" ")?.trim().orEmpty().ifEmpty { e.contentDescription?.toString().orEmpty() }).take(80)
                val r = Rect().also { node?.getBoundsInScreen(it) }
                val (w, h) = screenSize()
                com.xarvis.ai.task.TeachEvent.Click(
                    now, pkg, appLabel(pkg), label, id, (r.centerX() / w).coerceIn(0f, 1f), (r.centerY() / h).coerceIn(0f, 1f),
                    long = e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                )
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = e.source
                val password = e.isPassword || node?.isPassword == true
                val field = node?.hintText?.toString()?.trim().orEmpty()
                    .ifEmpty { node?.contentDescription?.toString()?.trim().orEmpty() }
                val r = Rect().also { node?.getBoundsInScreen(it) }
                val (w, h) = screenSize()
                com.xarvis.ai.task.TeachEvent.Text(
                    now, pkg, appLabel(pkg), field.take(80), node?.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                    if (password) "" else e.text?.joinToString("").orEmpty().take(500), password,
                    (r.centerX() / w).coerceIn(0f, 1f), (r.centerY() / h).coerceIn(0f, 1f),
                )
            }
            else -> null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        banner?.let { v -> runCatching { getSystemService(android.view.WindowManager::class.java)?.removeView(v) } }
        if (HandsBridge.service === this) HandsBridge.service = null
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

    // ---- Capability 3: remote control by the linked phone (ScreenHost drives these) ----

    override fun screenshot(onFrame: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) shoot(onFrame) else onFrame(null)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun shoot(onFrame: (android.graphics.Bitmap?) -> Unit) {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val frame = runCatching {
                    android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                buffer.close()
                onFrame(frame)
            }

            override fun onFailure(errorCode: Int) = onFrame(null)
        })
    }

    override fun gesture(kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            if (kind == "swipe") lineTo(x2, y2)
        }
        val duration = when (kind) {
            "tap" -> 60L
            "long" -> 800L
            else -> ms.coerceIn(80L, 3000L)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    override fun typeText(text: String, enter: Boolean): Boolean {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return false
        val before = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + text)
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return false
        }
        if (enter && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    // ---- Capability 5: XARVIS acting here for the linked phone (read the screen, tap by label, copy) ----

    override fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
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
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val lines = items.sortedWith(compareBy({ it.first.top }, { it.first.left })).map { it.second }.distinct()
        log("read the screen of $app: ${lines.size} lines")
        return ("[$app]\n" + lines.joinToString("\n")).take(6000)
    }

    override fun tapText(label: String): Boolean {
        val node = findByText(rootInActiveWindow, label) ?: return false.also { log("tap '$label': not on screen") }
        if (isDangerous(label) || isDangerous(text(node))) {
            log("tap '$label': refused (looks like payment / password / OTP)")
            return false
        }
        clickNode(node)
        return true
    }

    override fun screenElements(): String {
        val root = rootInActiveWindow
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        val out = org.json.JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels)
        if (root == null) return out.put("app", "").put("els", org.json.JSONArray()).toString()
        class E(val r: Rect, val label: String, val tap: Boolean, val edit: Boolean, val pw: Boolean, val id: String)
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
                    if (r.width() > 0 && r.height() > 0) {
                        found += E(r, label.replace('\n', ' ').take(80), tappable(n), n.isEditable, n.isPassword, n.viewIdResourceName?.substringAfter(":id/").orEmpty())
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val app = root.packageName?.toString()?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.orEmpty()
        val els = org.json.JSONArray()
        found.sortedWith(compareBy({ it.r.top }, { it.r.left })).distinctBy { it.label + it.r.centerX() / 20 + "," + it.r.centerY() / 20 }
            .take(60).forEach { e ->
                els.put(
                    org.json.JSONObject().put("t", e.label).put("c", e.tap).put("e", e.edit).put("p", e.pw)
                        .put("x", e.r.centerX()).put("y", e.r.centerY()).apply { if (e.id.isNotEmpty()) put("i", e.id) },
                )
            }
        // Teach mode looks every few seconds: not worth a log line each time.
        if (HandsBridge.teach == null) log("listed the screen of $app: ${els.length()} things")
        return out.put("app", app).put("pkg", root.packageName?.toString().orEmpty()).put("els", els).toString()
    }

    private var askView: android.view.View? = null

    override fun ask(question: String, onAnswer: (Boolean) -> Unit) {
        handler.post {
            val wm = getSystemService(android.view.WindowManager::class.java)
            if (wm == null) {
                onAnswer(false)
                return@post
            }
            askView?.let { runCatching { wm.removeView(it) } }
            var answered = false
            fun answer(yes: Boolean) {
                if (answered) return
                answered = true
                askView?.let { runCatching { wm.removeView(it) } }
                askView = null
                log("Rex answered ${if (yes) "YES" else "NO"}: $question")
                onAnswer(yes)
            }
            val d = resources.displayMetrics.density
            val pad = (16 * d).toInt()
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#F2101418"))
                setPadding(pad, pad, pad, pad)
                addView(android.widget.TextView(this@XarvisHandsService).apply {
                    text = "🤖 XARVIS asks:\n$question"
                    setTextColor(android.graphics.Color.WHITE); textSize = 17f
                })
                addView(android.widget.LinearLayout(this@XarvisHandsService).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(android.widget.Button(this@XarvisHandsService).apply { text = "✓ Yes"; setOnClickListener { answer(true) } },
                        android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(android.widget.Button(this@XarvisHandsService).apply { text = "✗ No"; setOnClickListener { answer(false) } },
                        android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
            val params = android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = android.view.Gravity.CENTER }
            runCatching { wm.addView(box, params); askView = box }.onFailure { answer(false) }
            handler.postDelayed({ answer(false) }, 60_000)
        }
    }

    override fun openApp(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { startActivity(launch) }.isSuccess
    }

    override fun openUrl(url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Chrome when it's there (sites behave as Rex knows them), else any browser.
        return runCatching { startActivity(Intent(view).setPackage("com.android.chrome")) }.isSuccess ||
            runCatching { startActivity(view) }.isSuccess
    }

    override fun lockPad(w: Int, h: Int): String? = runCatching {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        runCatching { windows }.getOrNull()?.forEach { win -> win.root?.let { roots += it } }
        com.xarvis.ai.hands.LockPad.scan(roots, w, h)?.toString()
    }.getOrNull()

    override fun selectedText(): String? {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (field.isShowingHintText) return null
        val all = field.text?.toString() ?: return null
        val start = field.textSelectionStart
        val end = field.textSelectionEnd
        return if (start in 0 until end && end <= all.length) all.substring(start, end) else all
    }

    // ---- Capability 4: a live finger (smooth remote control) — one gesture continued as the finger moves ----

    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveAt = android.graphics.PointF()
    private var liveDown = android.graphics.PointF()
    private var liveDownTime = 0L
    private var liveLastTime = 0L
    private var liveBusy = false
    private var liveBroken = false
    private var liveMove: android.graphics.PointF? = null
    private var liveUp: android.graphics.PointF? = null

    private val liveDone = object : AccessibilityService.GestureResultCallback() {
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

    override fun touchDown(x: Float, y: Float) {
        handler.post {
            liveMove = null
            liveUp = null
            liveBroken = false
            liveAt = android.graphics.PointF(x, y)
            liveDown = android.graphics.PointF(x, y)
            liveDownTime = android.os.SystemClock.uptimeMillis()
            liveLastTime = liveDownTime
            // The finger goes down here and stays down (willContinue) until the next piece of the gesture.
            val s = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
            liveStroke = s
            liveDispatch(s)
        }
    }

    override fun touchMove(x: Float, y: Float) {
        handler.post {
            liveMove = android.graphics.PointF(x, y) // only the newest point matters
            if (!liveBusy) liveNext()
        }
    }

    override fun touchUp(x: Float, y: Float) {
        handler.post {
            liveUp = android.graphics.PointF(x, y)
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

    /** Continue the gesture to the newest finger position (and lift it if the finger is up). */
    private fun liveNext() {
        if (liveBroken) {
            liveFallback()
            return
        }
        val previous = liveStroke ?: return
        val up = liveUp
        val to = up ?: liveMove ?: return
        val now = android.os.SystemClock.uptimeMillis()
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
        val held = android.os.SystemClock.uptimeMillis() - liveDownTime
        val moved = kotlin.math.hypot(up.x - liveDown.x, up.y - liveDown.y) > 20f
        gesture(if (moved) "swipe" else if (held > 600) "long" else "tap", liveDown.x, liveDown.y, up.x, up.y, held)
    }

    private var banner: android.view.View? = null

    private var bannerShrink: Runnable? = null

    override fun controlBanner(by: String?, onStop: () -> Unit) {
        handler.post {
            val wm = getSystemService(android.view.WindowManager::class.java) ?: return@post
            banner?.let { runCatching { wm.removeView(it) } }
            banner = null
            bannerShrink?.let { handler.removeCallbacks(it) }
            bannerShrink = null
            if (by == null) return@post
            val d = resources.displayMetrics.density
            // Rex asked for it "star size": a small red ✱ in the status bar, beside the camera hole. Tap it to see
            // who is controlling the phone and the STOP button (for 5 s); everything else on screen stays free.
            val red = android.graphics.Color.parseColor("#FF1744")
            val star = android.widget.TextView(this).apply {
                text = "✱"; textSize = 14f; setTextColor(red); setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                contentDescription = "$by is controlling this phone"
            }
            val label = android.widget.TextView(this).apply {
                text = "$by is controlling this phone  "; setTextColor(android.graphics.Color.WHITE); textSize = 12f
                gravity = android.view.Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE
            }
            val stop = android.widget.TextView(this).apply {
                text = "  STOP  "; setTextColor(android.graphics.Color.parseColor("#C62828")); textSize = 12f
                setBackgroundColor(android.graphics.Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE
                setOnClickListener { onStop() }
            }
            val pill = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, (4 * d).toInt(), 0)
                addView(star, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                addView(label)
                addView(stop)
            }
            fun open(show: Boolean) {
                label.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                stop.visibility = label.visibility
                star.setTextColor(if (show) android.graphics.Color.WHITE else red)
                pill.setBackgroundColor(if (show) android.graphics.Color.parseColor("#E6C62828") else android.graphics.Color.TRANSPARENT)
            }
            star.setOnClickListener {
                open(true)
                bannerShrink?.let { handler.removeCallbacks(it) }
                bannerShrink = Runnable { open(false) }.also { handler.postDelayed(it, 5_000) }
            }
            val barId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val barHeight = if (barId > 0) resources.getDimensionPixelSize(barId) else (24 * d).toInt()
            val params = android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, barHeight,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                x = (30 * d).toInt() // just right of the S22's centre camera hole
                y = 0
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            runCatching { wm.addView(pill, params); banner = pill }.onFailure { log("Couldn't show the control banner: ${it.message}") }
        }
    }

    // ---- Capability 2: place a call inside an app (imo or WhatsApp) ----

    /** Launch [app]'s package, then walk its screens to find [contact] and tap the call button. */
    override fun startCallInApp(app: String, contact: String, video: Boolean) {
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

    override fun cancelTask() {
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
        // Only the real button, whose label is short ("Call", "Voice call"). WhatsApp's
        // "Messages and calls are end-to-end encrypted…" banner also contains "call" but is a long
        // sentence that opens the encryption screen — skip long text and these giveaway words.
        val bad = listOf("encrypt", "end-to-end", "learn more", "verify", "tap to", "missed", "call log", "block", "search", "message")
        val match = findFirst(root) { n ->
            val s = text(n).lowercase().trim()
            if (s.isEmpty() || s.length > 16 || isDangerous(s)) return@findFirst false
            if (bad.any { s.contains(it) }) return@findFirst false
            if (video) want.any { s.contains(it) }
            else want.any { s.contains(it) } && !s.contains("video")
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
        private val DANGER = Regex(
            "pay|otp|password|passcode|upi|send money|signature|bank|card number|cvv|pin\\b",
            RegexOption.IGNORE_CASE,
        )

        private fun log(m: String) = HandsBridge.log(m)
    }
}
