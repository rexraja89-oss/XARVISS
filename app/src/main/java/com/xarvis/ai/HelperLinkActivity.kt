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
import kotlinx.coroutines.withContext

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

    /**
     * The invite, made easy for someone who isn't technical (Rex, 14 Nov 2026: his wife couldn't install from the
     * link). Two WhatsApp messages: first the app FILE itself (no web page, no browser), with every tap spelled out in
     * Hindi and English, including Android's two warning screens; then the invite, which she only has to copy — the
     * Helper links by itself when it opens and finds it.
     */
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
        val wait = Toast.makeText(this, "Getting XARVIS Helper to send…", Toast.LENGTH_LONG).also { it.show() }
        scope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = java.net.URL(HELPER_APK).openStream().use { it.readBytes() }
                    com.xarvis.ai.files.FileStore(this@HelperLinkActivity)
                        .saveBytes("XARVIS-Helper.apk", "application/vnd.android.package-archive", bytes)
                }
            }.getOrNull()
            wait.cancel()
            if (apk == null) {
                // No internet for the download: fall back to the link.
                share(INSTALL_STEPS + "\n\nDownload: $HELPER_APK", null) { sendInvite(code) }
                return@launch
            }
            share(INSTALL_STEPS, android.net.Uri.parse(apk.uri)) { sendInvite(code) }
        }
    }

    /** Message 2: the invite itself, to copy. */
    private fun sendInvite(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Step 2 of 2")
            .setMessage("Now send the invite. When the app is installed she just long-presses that message, taps Copy, and opens XARVIS Helper — it links by itself.")
            .setPositiveButton("Send the invite") { _, _ ->
                val message = "Is message ko daba ke rakhein (long-press) → Copy ⧉ dabayein → phir XARVIS Helper kholein. Bas, ho gaya! ❤\n" +
                    "Long-press this message → tap Copy ⧉ → open XARVIS Helper. That's all!\n\n$code"
                share(message, null) {}
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Shares [text] (and the file [stream]) on WhatsApp, else any app; [then] runs when Rex comes back. */
    private fun share(text: String, stream: android.net.Uri?, then: () -> Unit) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        if (stream != null) {
            send.setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, stream)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.setType("text/plain")
        }
        afterShare = then
        val whatsapp = Intent(send).setPackage("com.whatsapp")
        runCatching { startActivity(whatsapp) }.onFailure {
            runCatching { startActivity(Intent.createChooser(send, "Send it")) }
                .onFailure { afterShare = null; Toast.makeText(this, "No app to send it with.", Toast.LENGTH_LONG).show() }
        }
    }

    /** What to do when Rex is back from WhatsApp (the second message). */
    private var afterShare: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        afterShare?.let { afterShare = null; it() }
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

        /** Every tap, in Hindi (Roman letters, as Rex writes) and English, including Android's two warning screens. */
        private const val INSTALL_STEPS = "XARVIS Helper — mera app, taaki main aapke phone mein madad kar sakoon ❤\n\n" +
            "1. Is file par tap karein.\n" +
            "2. Agar \"Settings\" ya \"Install unknown apps\" aaye: Settings dabayein → \"Allow from this source\" ON karein → peeche (Back) aayein.\n" +
            "3. \"Install\" dabayein. Agar \"Unsafe app\" ya Play Protect likha aaye: \"More details\" → \"Install anyway\" dabayein.\n" +
            "4. \"Open\" dabayein. Phir mera agla message aayega.\n\n" +
            "1. Tap this file. 2. If Android asks, tap Settings → turn on \"Allow from this source\" → go back. " +
            "3. Tap Install (if it warns \"Unsafe app\": More details → Install anyway). 4. Tap Open. My next message comes after."
    }
}
