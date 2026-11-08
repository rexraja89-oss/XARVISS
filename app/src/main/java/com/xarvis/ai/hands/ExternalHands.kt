package com.xarvis.ai.hands

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The benco's hands for remote control: the separate XARVIS Hands app (its Accessibility service; the
 * benco build has none inside XARVIS — it refused such builds before). Commands go to it as broadcasts
 * ("com.xarvis.hands.action.REMOTE", signature-protected); answers (screen pictures, read text) come back
 * as broadcasts only same-key apps may send. Same interface as the S22's built-in Hands, so ScreenHost
 * works the same either way. No smooth video here: screen pictures, a few a second.
 */
class ExternalHands private constructor(private val context: Context) : HandsBridge.Hands {
    private val thread = HandlerThread("xarvis-hands-answers").apply { start() }
    private val handler = Handler(thread.looper)
    private val waiting = ConcurrentHashMap<Long, (Intent?) -> Unit>()
    private val ids = AtomicLong(1)
    @Volatile private var onStop: (() -> Unit)? = null

    private val answers = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getBooleanExtra("stop", false)) {
                onStop?.invoke()
                return
            }
            waiting.remove(intent.getLongExtra("id", 0L))?.invoke(intent)
        }
    }

    init {
        // Answers arrive on our own thread, so XARVIS may wait for one on the main thread.
        ContextCompat.registerReceiver(context, answers, IntentFilter(RESULT_ACTION), PERMISSION, handler, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun send(op: String, fill: Intent.() -> Unit = {}) {
        context.sendBroadcast(Intent(ACTION).setPackage(HANDS_PKG).putExtra("op", op).apply(fill))
    }

    /** Send [op] and wait (up to [timeoutMs]) for its answer. */
    private fun ask(op: String, timeoutMs: Long, fill: Intent.() -> Unit = {}): Intent? {
        val id = ids.getAndIncrement()
        val latch = CountDownLatch(1)
        var answer: Intent? = null
        waiting[id] = { answer = it; latch.countDown() }
        send(op) { putExtra("id", id); fill() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        waiting.remove(id)
        return answer
    }

    override fun screenshot(onFrame: (Bitmap?) -> Unit) {
        val id = ids.getAndIncrement()
        waiting[id] = { answer -> onFrame(answer?.getByteArrayExtra("jpg")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }) }
        handler.postDelayed({ waiting.remove(id)?.invoke(null) }, 5_000)
        send("shot") { putExtra("id", id); putExtra("max", 1000) }
    }

    override fun gesture(kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Long): Boolean {
        send("gesture") { putExtra("kind", kind); putExtra("x", x); putExtra("y", y); putExtra("x2", x2); putExtra("y2", y2); putExtra("ms", ms) }
        return true
    }

    override fun globalAction(name: String): Boolean {
        send("key") { putExtra("name", name) }
        return true
    }

    override fun typeText(text: String, enter: Boolean): Boolean =
        ask("text", 3_000) { putExtra("text", text); putExtra("enter", enter) }?.getBooleanExtra("ok", false) ?: false

    override fun controlBanner(by: String?, onStop: () -> Unit) {
        this.onStop = if (by != null) onStop else null
        send("banner") { putExtra("by", by) }
    }

    override fun touchDown(x: Float, y: Float) = send("down") { putExtra("x", x); putExtra("y", y) }
    override fun touchMove(x: Float, y: Float) = send("move") { putExtra("x", x); putExtra("y", y) }
    override fun touchUp(x: Float, y: Float) = send("up") { putExtra("x", x); putExtra("y", y) }

    override fun screenText(): String = ask("read", 4_000)?.getStringExtra("text").orEmpty()

    override fun tapText(label: String): Boolean =
        ask("tap", 3_000) { putExtra("label", label) }?.getBooleanExtra("ok", false) ?: false

    override fun selectedText(): String? = ask("selected", 3_000)?.getStringExtra("text")

    override fun openApp(packageName: String): Boolean =
        ask("open", 3_000) { putExtra("pkg", packageName) }?.getBooleanExtra("ok", false) ?: false

    // Calls go to the Hands app the usual way (WorkflowEngine.externalAppCall); not through here.
    override fun startCallInApp(app: String, contact: String, video: Boolean) {}
    override fun cancelTask() {}

    companion object {
        private const val HANDS_PKG = "com.xarvis.hands"
        private const val ACTION = "com.xarvis.hands.action.REMOTE"
        private const val RESULT_ACTION = "com.xarvis.ai.action.HANDS_RESULT"
        private const val PERMISSION = "com.xarvis.ai.permission.EYES"

        @Volatile private var instance: ExternalHands? = null

        /** The Hands app's hands, when it's installed and its Accessibility service is switched on; else null. */
        fun get(context: Context): ExternalHands? {
            val on = runCatching {
                context.packageManager.getPackageInfo(HANDS_PKG, 0)
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().contains("$HANDS_PKG/", ignoreCase = true)
            }.getOrDefault(false)
            if (!on) return null
            return instance ?: synchronized(this) {
                instance ?: ExternalHands(context.applicationContext).also { instance = it }
            }
        }
    }
}
