package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ☰ → 🤝 Help a family phone (Rex, Nov 2026: his wife "sometimes asks help solving some problems or setup, she
 * isn't very technical" — from the UAE to India and back). Rex sends an invite on WhatsApp; she installs XARVIS
 * Helper and pastes it; from then on, whenever she taps "Ask for help", Rex gets a notification and sees her
 * screen here (and taps for her, if she switched that on). Each session needs her consent on her phone.
 */
class HelperLinkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val link get() = (application as XarvisApp).core.deviceLink
    private lateinit var list: LinearLayout
    private val teal = Color.parseColor("#0B6E8C")
    private val grey = Color.parseColor("#5F6B73")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@HelperLinkActivity).apply { text = "🤝 Help a family phone"; textSize = 24f; setTextColor(teal) })
            addView(
                label(
                    "Help someone with their phone from anywhere (UAE ↔ India). They install the small XARVIS Helper app " +
                        "once. Whenever they tap \"Ask for help\", you get a notification here and see their screen — and " +
                        "can tap for them, if they switch that on. They allow it each time, and a STOP button is always on " +
                        "their screen. Android phones only.",
                ),
            )
            addView(label("THEIR PHONES", teal, 13f, bold = true))
            addView(list)
            addView(button("📨 Invite a phone (WhatsApp)") { invite() })
            addView(label("HOW IT WORKS", teal, 13f, bold = true))
            addView(
                label(
                    "1. Tap \"Invite a phone\" and send the message on WhatsApp.\n" +
                        "2. On their phone: tap the download link in the message, open the file and tap Install (allow it " +
                        "if Android asks).\n" +
                        "3. Back in WhatsApp: long-press your message → Copy. Open XARVIS Helper → \"Paste invite\". Done.\n" +
                        "4. To let you tap for them (not only see): in XARVIS Helper, \"✋ Allow tapping\" shows the two " +
                        "switches. You can guide them through it while you watch their screen.\n" +
                        "Each invite works for one phone, for 7 days. It holds your relay login, so send it only to family.",
                    Color.BLACK,
                ),
            )
        }
        setContentView(ScrollView(this).apply { addView(col) })
        scope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
    }

    private fun refresh() {
        list.removeAllViews()
        val phones = link.helperPeers()
        if (phones.isEmpty()) {
            list.addView(label("None yet. Invite one below.", Color.BLACK, 14f))
            return
        }
        for (p in phones) {
            val online = link.relay.online(p.id) == true
            list.addView(
                label(
                    "${p.name}: " + if (online) "🟢 XARVIS Helper is open" else "⚪ XARVIS Helper is closed (they open it and tap \"Ask for help\")",
                    Color.BLACK, 15f,
                ),
            )
            list.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("👀 Help now") { helpNow(p) })
                    addView(button("Remove") { remove(p) })
                },
            )
        }
    }

    private fun helpNow(p: Peer) {
        startActivity(Intent(this, RemoteScreenActivity::class.java).putExtra(RemoteScreenActivity.EXTRA_PEER_ID, p.id))
    }

    private fun invite() {
        val code = link.createInvite()
        if (code == null) {
            AlertDialog.Builder(this)
                .setMessage("First set up ☰ → 🌐 Always connected (your free relay). The invite uses it to reach their phone anywhere.")
                .setPositiveButton("Open it") { _, _ -> startActivity(Intent(this, RelayActivity::class.java)) }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        val message = "Hi! Install XARVIS Helper so I can help you with your phone from anywhere:\n\n" +
            "1) Download: $HELPER_APK — open the file and tap Install.\n" +
            "2) Then long-press THIS message → Copy, open XARVIS Helper and tap \"Paste invite\".\n\n" +
            "$code\n\n(This invite works once, for 7 days.)"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        // WhatsApp if it's there, else any app.
        val whatsapp = Intent(send).setPackage("com.whatsapp")
        runCatching { startActivity(whatsapp) }.onFailure {
            runCatching { startActivity(Intent.createChooser(send, "Send the invite")) }
                .onFailure { Toast.makeText(this, "No app to send it with.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun remove(p: Peer) {
        AlertDialog.Builder(this)
            .setMessage("Remove ${p.name}? You won't be able to help it until you invite it again.")
            .setPositiveButton("Remove") { _, _ ->
                link.unpair(p)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun label(t: String, color: Int = grey, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, dp(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val HELPER_APK = "https://github.com/rexraja89-oss/XARVISS/releases/latest/download/XARVIS-Helper.apk"
    }
}
