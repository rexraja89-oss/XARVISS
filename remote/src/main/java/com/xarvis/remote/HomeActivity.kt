package com.xarvis.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.net.Relay
import com.xarvis.ai.view.RemoteViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * XARVIS Remote's one screen. On the benco: "🖥 Control Galaxy S22 Ultra". On the S22: the switch that lets the
 * linked phones in, and a checklist (Accessibility, live video, battery) so it keeps working with nobody there.
 * Linking: the controlling phone makes an invite, the other phone pastes it (once, at home).
 */
class HomeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val link: DeviceLink get() = RemoteApp.link(this)
    private lateinit var body: LinearLayout
    private lateinit var live: TextView
    private lateinit var updateButton: Button

    private val red = Color.parseColor("#B71C1C")
    private val grey = Color.parseColor("#5F6B73")
    private val green = Color.parseColor("#2E7D32")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        live = label("", Color.BLACK, 14f)
        updateButton = button("") { Updates.openDownload(this) }.apply { visibility = View.GONE }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@HomeActivity).apply { text = "🛟 XARVIS Remote"; textSize = 24f; setTextColor(red) })
            addView(label("v${version()} · for emergencies: reach your phone when XARVIS on it is down or updating.", grey, 13f))
            addView(updateButton)
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        intent?.let { pastedInvite(it) }
        build()
        // The checklist follows what's happening (relay, Accessibility, live video, who's connected).
        scope.launch {
            while (isActive) {
                live.text = liveStatus()
                delay(2_000)
            }
        }
        scope.launch {
            val newer = withContext(Dispatchers.IO) { Updates.newerVersion(version()) }
            if (newer != null) {
                updateButton.text = "⬆ UPDATE TO v$newer"
                updateButton.visibility = View.VISIBLE
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pastedInvite(intent)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ---- the screen ----

    private fun build() {
        body.removeAllViews()
        (live.parent as? LinearLayout)?.removeView(live)

        // Controlling: the phones this one may control.
        body.addView(heading("CONTROL A PHONE"))
        val targets = link.helperPeers()
        if (targets.isEmpty()) {
            body.addView(label("No phone to control yet. On this phone tap ➕ below and send the invite to the other phone (it needs XARVIS Remote too).", grey, 13f))
        }
        targets.forEach { p ->
            body.addView(
                button("🖥 Control ${p.name}") {
                    startActivity(Intent(this, ViewerActivity::class.java).putExtra(RemoteViewActivity.EXTRA_PEER_ID, p.id))
                }.apply { textSize = 18f },
            )
            body.addView(small("Remove ${p.name}") { confirmRemove(p) })
        }
        body.addView(button("➕ Link a phone to control") { invite() })

        // Being controlled: this phone.
        body.addView(heading("THIS PHONE CAN BE CONTROLLED"))
        body.addView(
            Switch(this).apply {
                text = "Let my linked phones control this phone (no one needs to be here to allow it)"
                textSize = 15f
                isChecked = RemotePrefs.hosting(this@HomeActivity)
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, on ->
                    RemotePrefs.setHosting(this@HomeActivity, on)
                    build()
                }
            },
        )
        body.addView(live)
        if (RemotePrefs.hosting(this)) {
            if (RemoteHands.instance == null) {
                body.addView(button("✋ Open Accessibility settings") { openAccessibility() })
                body.addView(
                    label(
                        "There: Installed apps → XARVIS Remote → On. If the switch is greyed out: tap “App info” below → ⋮ (top right) → " +
                            "“Allow restricted settings”, then try again.",
                        grey, 13f,
                    ),
                )
            }
            if (!RemoteHost.videoReady) {
                body.addView(button("⚡ Allow live video now") { startActivity(Intent(this, ConsentActivity::class.java)) })
                body.addView(label("Android asks again after each restart of this phone. You can also do it from the other phone: ⚡ Make it smooth, then tap “Start now” on the picture.", grey, 13f))
            }
            body.addView(button("🔋 App info (set Battery → Unrestricted)") { openAppInfo() })
            val owners = link.pairedPeers()
            body.addView(label(if (owners.isEmpty()) "No phone may control this one yet: paste its invite below." else "May control this phone:", Color.BLACK, 14f, bold = true))
            owners.forEach { p ->
                body.addView(label("• ${p.name}", Color.BLACK, 14f))
                body.addView(small("Remove ${p.name}") { confirmRemove(p) })
            }
        }
        val box = EditText(this).apply {
            hint = "Paste the invite from your other phone here"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        body.addView(box)
        body.addView(button("🔗 Link with that phone") { accept(box.text.toString()) })

        // The relay login (needed to make an invite; an invite brings it to the other phone).
        body.addView(heading("RELAY LOGIN"))
        val cfg = link.relay.config()
        body.addView(label(if (cfg != null) "Set: ${cfg.host} (user ${cfg.user})." else "Not set yet. Copy it from XARVIS (one tap) or type it in.", if (cfg != null) green else red, 13f))
        body.addView(button("📋 Copy it from XARVIS on this phone") { copyFromXarvis() })
        body.addView(small("Type it in instead…") { typeLogin() })
    }

    private suspend fun liveStatus(): String = withContext(Dispatchers.Default) {
        val relay = link.relay.status.value
        val lines = ArrayList<String>()
        lines += when (relay.state) {
            Relay.State.CONNECTED -> "🌐 Relay: connected ✓"
            Relay.State.OFF -> "🌐 Relay: not set up (see RELAY LOGIN below)"
            else -> "🌐 Relay: connecting… ${relay.detail}".trimEnd()
        }
        if (RemotePrefs.hosting(this@HomeActivity)) {
            lines += if (RemoteHands.instance != null) "✋ Accessibility: on ✓" else "✋ Accessibility: OFF — needed for taps and pictures"
            lines += if (RemoteHost.videoReady) "⚡ Live video: ready ✓" else "⚡ Live video: not yet (pictures until “Start now”)"
            lines += RemoteHost.controller?.let { "🔴 $it is controlling this phone now (✱ at the top → STOP)" } ?: "Nobody is connected."
        }
        lines.joinToString("\n")
    }

    // ---- linking ----

    /** On the controlling phone: an invite for the phone to control (shared, and copied). */
    private fun invite() {
        if (link.relay.config() == null) {
            AlertDialog.Builder(this).setTitle("Relay login first")
                .setMessage("The invite carries the relay login to the other phone, so this phone needs it first: copy it from XARVIS, or type it in (RELAY LOGIN below).")
                .setPositiveButton("Copy from XARVIS") { _, _ -> copyFromXarvis() }
                .setNegativeButton("Later", null).show()
            return
        }
        val code = link.createInvite() ?: return
        val message = "XARVIS Remote invite (for my own phone). On the phone to control: install XARVIS Remote " +
            "(${Updates.APK_URL}), open it and paste this whole message under THIS PHONE CAN BE CONTROLLED.\n\n$code"
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("XARVIS Remote invite", message))
        AlertDialog.Builder(this)
            .setTitle("Invite ready (copied)")
            .setMessage(
                "Send it to the phone to control — WhatsApp to yourself, Quick Share, or XARVIS's “Send to my other phone”. " +
                    "It works once, for 7 days. It holds your relay login: send it only to your own phones.",
            )
            .setPositiveButton("Share…") { _, _ ->
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message), "Send the invite"))
                }
            }
            .setNegativeButton("Done", null).show()
    }

    /** On the phone to control: link with the phone whose invite was pasted (or shared here). */
    private fun accept(text: String) {
        if (DeviceLink.parseInvite(text) == null) {
            toast("That isn't a XARVIS Remote invite: paste the whole message.")
            return
        }
        val wait = AlertDialog.Builder(this).setTitle("Linking…").setMessage("Talking to the other phone through the relay.").setCancelable(false).show()
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { link.acceptInvite(text) } }
            wait.dismiss()
            result.fold(
                { name ->
                    // This is the phone to control: switch it on (Rex can still turn it off).
                    if (!RemotePrefs.hosting(this@HomeActivity)) RemotePrefs.setHosting(this@HomeActivity, true)
                    AlertDialog.Builder(this@HomeActivity).setTitle("Linked with $name")
                        .setMessage("$name can now control this phone with XARVIS Remote. Finish the checklist here (Accessibility, live video, battery) so it works with nobody at the phone.")
                        .setPositiveButton("OK", null).show()
                },
                { e -> AlertDialog.Builder(this@HomeActivity).setTitle("Couldn't link").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show() },
            )
            build()
        }
    }

    private fun pastedInvite(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (DeviceLink.parseInvite(text) != null) accept(text)
    }

    private fun confirmRemove(p: Peer) {
        AlertDialog.Builder(this).setTitle("Remove ${p.name}?")
            .setMessage("It won't be able to connect (or be controlled) through XARVIS Remote until you link again with a new invite.")
            .setPositiveButton("Remove") { _, _ ->
                link.unpair(p)
                build()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- relay login ----

    private fun copyFromXarvis() {
        val ask = Intent().setClassName("com.xarvis.ai", "com.xarvis.ai.RelayLoginExportActivity")
        runCatching { startActivityForResult(ask, COPY_LOGIN) }.onFailure {
            toast("XARVIS on this phone can't share it (update XARVIS to the newest version), or type it in instead.")
        }
    }

    @Deprecated("A plain Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != COPY_LOGIN) return
        val host = data?.getStringExtra("host")
        val user = data?.getStringExtra("user")
        val pass = data?.getStringExtra("pass")
        if (resultCode != RESULT_OK || host.isNullOrBlank() || user == null || pass == null) {
            toast("XARVIS on this phone has no relay login (☰ → 🌐 Always connected), or type it in instead.")
            return
        }
        link.relay.save(Relay.Config(host, data.getIntExtra("port", 8883), user, pass))
        toast("Relay login copied from XARVIS.")
        build()
    }

    private fun typeLogin() {
        val cfg = link.relay.config()
        val host = EditText(this).apply { hint = "Address (e.g. xxxx.emqxsl.com:8883)"; setText(cfg?.let { "${it.host}:${it.port}" }.orEmpty()) }
        val user = EditText(this).apply { hint = "User name"; setText(cfg?.user.orEmpty()) }
        val pass = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(host); addView(user); addView(pass)
        }
        AlertDialog.Builder(this).setTitle("Relay login").setMessage("The same as in XARVIS (☰ → 🌐 Always connected).").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val (h, port) = Relay.splitHost(host.text.toString().trim())
                if (h.isNotBlank() && user.text.isNotBlank() && pass.text.isNotEmpty()) {
                    link.relay.save(Relay.Config(h, port, user.text.toString().trim(), pass.text.toString()))
                    build()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- settings shortcuts ----

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openAppInfo() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    // ---- small helpers ----

    private fun version(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun heading(t: String) = label(t, red, 13f, bold = true).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun label(t: String, color: Int = grey, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, dp(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun small(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(grey); setPadding(0, dp(2), 0, dp(8))
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val COPY_LOGIN = 7503
    }
}
