package com.xarvis.helper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * XARVIS Helper's only screen, made for someone who isn't technical: paste the invite once, then one big
 * "🙋 Ask for help" button, and a big STOP while being helped. "✋ Allow tapping" explains the one-time switch.
 */
class HelperActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val link: DeviceLink get() = HelperApp.link(this)
    private lateinit var body: LinearLayout
    private lateinit var versionLine: TextView
    private var note = ""
    private var offerShown: String? = null

    private val green = Color.parseColor("#1B8A3A")
    private val red = Color.parseColor("#C62828")
    private val teal = Color.parseColor("#0B6E8C")
    private val grey = Color.parseColor("#5F6B73")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        versionLine = text("", grey, 12f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(text("XARVIS Helper", teal, 30f, bold = true))
            addView(versionLine)
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showVersion()
        handleIntent(intent)
        scope.launch { HelperSession.status.collect { render() } }
        scope.launch { HelperSession.askedBy.collect { from -> if (from != null) offer(from) } }
        scope.launch {
            while (isActive) {
                delay(2_000)
                render()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** An invite shared from WhatsApp, or "X wants to help you" tapped in a notification. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { linkWith(it) }
        intent?.getStringExtra(EXTRA_OFFER)?.let { offer(it) }
    }

    private fun render() {
        body.removeAllViews()
        val owners = link.pairedPeers()
        if (owners.isEmpty()) {
            body.addView(text("Rex will send you an invite on WhatsApp.", Color.BLACK, 18f))
            body.addView(text("In WhatsApp, long-press his message → Copy. Then tap:", Color.BLACK, 18f))
            body.addView(big("📋 Paste invite", teal) { pasteInvite() })
            if (note.isNotEmpty()) body.addView(text(note, grey, 15f))
            return
        }
        val names = owners.joinToString(" and ") { it.name }
        val relay = link.relay.status.value
        body.addView(text("Linked with $names ✓", green, 17f, bold = true))
        body.addView(
            text(
                when (relay.state) {
                    Relay.State.CONNECTED -> "Online — your helper can reach this phone."
                    Relay.State.CONNECTING -> "Connecting to the internet…"
                    Relay.State.ERROR -> "Not online: ${relay.detail}"
                    Relay.State.OFF -> "Not set up — paste the invite again."
                },
                grey, 13f,
            ),
        )
        val s = HelperSession.status.value
        when (s.state) {
            HelperSession.State.IDLE -> {
                body.addView(big("🙋 Ask for help", green) { askForHelp() })
                body.addView(text("Your helper will see your screen until you press STOP. Android asks you to allow it each time.", grey, 14f))
                val last = s.note.ifEmpty { note }
                if (last.isNotEmpty()) body.addView(text(last, grey, 14f))
            }
            HelperSession.State.WAITING -> {
                body.addView(text("⏳ Waiting for $names…", Color.BLACK, 20f, bold = true))
                if (s.note.isNotEmpty()) body.addView(text(s.note, grey, 15f))
                body.addView(big("STOP", red) { HelperSession.end(this, "You pressed STOP.", stopped = true) })
            }
            HelperSession.State.HELPING -> {
                body.addView(text("🟢 ${s.by ?: names} is helping you now.", green, 20f, bold = true))
                body.addView(text("You can watch what they do. Press STOP any time to end it.", Color.BLACK, 16f))
                body.addView(big("STOP", red) { HelperSession.end(this, "You pressed STOP.", stopped = true) })
            }
        }
        // The one-time switch that lets the helper tap, not only see.
        body.addView(text("✋ Allow tapping", teal, 17f, bold = true))
        if (HelperHands.instance != null) {
            body.addView(text("✓ Your helper can tap for you during help. (To turn it off: Settings → Accessibility → XARVIS Helper.)", green, 14f))
        } else {
            body.addView(
                text(
                    "Now your helper can only SEE your screen. To let them also tap for you, switch on \"XARVIS Helper\" in " +
                        "Accessibility (once). Your helper can guide you while watching your screen." +
                        if (Build.VERSION.SDK_INT >= 33) "\n\nIf the switch is grey: tap \"App info\" below → ⋮ (top right) → \"Allow restricted settings\", then try again." else "",
                    Color.BLACK, 14f,
                ),
            )
            body.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(small("Accessibility") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                    addView(small("App info") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
                },
            )
        }
        body.addView(View(this).apply { minimumHeight = dp(24) })
        body.addView(small("Remove the link") { unlink() })
    }

    private fun askForHelp() {
        HelperService.clearAsk(this)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: offer only the whole screen (sharing a single app wouldn't show the helper enough).
        val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(ask, REQ_SHARE)
    }

    @Deprecated("Activity result for the screen-sharing consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SHARE) return
        if (resultCode == RESULT_OK && data != null) {
            note = ""
            HelperService.start(this, resultCode, data)
        } else {
            note = "You didn't allow it, so nothing was shared."
            render()
        }
    }

    /** A helping phone wants to help (it tried to connect before she asked). */
    private fun offer(from: String) {
        if (HelperSession.active || offerShown == from || isFinishing) return
        offerShown = from
        AlertDialog.Builder(this)
            .setTitle("$from wants to help you")
            .setMessage("Let them see your screen? You can press STOP any time.")
            .setPositiveButton("Yes, allow") { _, _ -> askForHelp() }
            .setNegativeButton("Not now", null)
            .setOnDismissListener {
                offerShown = null
                HelperSession.askedBy.value = null
            }
            .show()
    }

    private fun pasteInvite() {
        val text = getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (DeviceLink.parseInvite(text) == null) {
            note = "Nothing to paste yet. In WhatsApp, long-press Rex's message → Copy (the ⧉ icon at the top), then come back and tap \"Paste invite\"."
            render()
            return
        }
        linkWith(text)
    }

    private fun linkWith(text: String) {
        note = "Linking… (a few seconds)"
        render()
        scope.launch {
            note = runCatching { withContext(Dispatchers.IO) { link.acceptInvite(text) } }
                .fold({ "Linked with $it ✓" }, { "Couldn't link: ${it.message}" })
            render()
        }
    }

    private fun unlink() {
        AlertDialog.Builder(this)
            .setMessage("Remove the link? Your helper won't be able to help until they send a new invite.")
            .setPositiveButton("Remove") { _, _ ->
                link.pairedPeers().forEach { link.unpair(it) }
                note = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVersion() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        versionLine.text = "v$version"
        scope.launch {
            val newer = withContext(Dispatchers.IO) { Updates.newerVersion(version) } ?: return@launch
            versionLine.text = "v$version · ⬆ Update to v$newer (tap)"
            versionLine.setTextColor(teal)
            versionLine.setOnClickListener { Updates.openDownload(this@HelperActivity) }
        }
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i) }
    }

    private fun text(t: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun big(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 22f; isAllCaps = false; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        minHeight = dp(76)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(6)
        }
    }

    private fun small(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OFFER = "offer"
        private const val REQ_SHARE = 11
    }
}
