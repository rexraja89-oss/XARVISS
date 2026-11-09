package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
 * ☰ → Always connected: Rex's relay login (his free EMQX Serverless broker), its status, data used this month, and
 * which linked phones are online. Saved here once, it's shared with his other phones over the link, sealed.
 */
class RelayActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val link get() = (application as XarvisApp).core.deviceLink
    private lateinit var status: TextView
    private lateinit var usage: TextView
    private lateinit var phones: TextView
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText

    private val teal = Color.parseColor("#0B6E8C")
    private val grey = Color.parseColor("#5F6B73")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        fun label(t: String, color: Int = grey, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, dp(2))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
        status = label("", teal, 16f, bold = true)
        usage = label("")
        phones = label("", Color.BLACK, 14f)
        val cfg = link.relay.config()
        host = EditText(this).apply { hint = "e.g. ab12cd34.ala.asia-southeast1.emqxsl.com"; setText(cfg?.let { "${it.host}:${it.port}" }.orEmpty()); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        user = EditText(this).apply { hint = "username"; setText(cfg?.user.orEmpty()) }
        pass = EditText(this).apply {
            hint = "password"; setText(cfg?.pass.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@RelayActivity).apply { text = "🌐 Always connected"; textSize = 24f; setTextColor(teal) })
            addView(label("Reach your other phones anywhere — mobile data, another country — without Tailscale or a VPN (banking apps stay happy). At home on the same Wi-Fi they still talk directly.", grey, 13f))
            addView(status)
            addView(phones)
            addView(usage)
            addView(label("YOUR RELAY (free EMQX Serverless)", teal, 13f, bold = true))
            addView(label("Address")); addView(host)
            addView(label("Username")); addView(user)
            addView(label("Password")); addView(pass)
            addView(button("💾 Save and connect") { save() })
            addView(button("📲 Send it to my other phones") { share() })
            addView(button("Remove") { remove() })
            addView(label("HOW TO GET IT (once, about 5 minutes)", teal, 13f, bold = true))
            addView(
                label(
                    "1. Open EMQX Cloud and sign up (Google sign-in works).\n" +
                        "2. Make a project, then a new deployment: choose \"Serverless\" (free) and keep the spend limit at 0.\n" +
                        "3. In the deployment: Access Control → Authentication → + Add a username and password.\n" +
                        "4. Copy the Address from Deployment Overview (it ends in emqxsl.com) and paste all three above.\n" +
                        "Free: 1 GB a month for all your phones together. Messages and guard alerts use almost nothing; " +
                        "watching a screen through the relay stops at ${Relay.SCREEN_LIMIT_BYTES / 1_048_576} MB a month so it never runs out. " +
                        "Everything passing through is sealed with your phones' linking key.",
                    Color.BLACK, 13f,
                ),
            )
            addView(button("Open EMQX Cloud") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloud-intl.emqx.com/"))) } })
        }
        setContentView(ScrollView(this).apply { addView(col) })
        scope.launch {
            link.relay.status.collect { s ->
                status.text = when (s.state) {
                    Relay.State.CONNECTED -> "✓ Connected — your phones can reach this one anywhere"
                    Relay.State.CONNECTING -> "… Connecting to ${s.detail}"
                    Relay.State.ERROR -> "⚠ ${s.detail}"
                    Relay.State.OFF -> "Not set up yet"
                }
                status.setTextColor(
                    when (s.state) {
                        Relay.State.CONNECTED -> Color.parseColor("#1B8A3A")
                        Relay.State.ERROR -> Color.parseColor("#C62828")
                        else -> teal
                    },
                )
            }
        }
        scope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
    }

    private fun refresh() {
        val used = link.relay.usedThisMonth()
        val limit = Relay.SCREEN_LIMIT_BYTES / 1_048_576
        usage.text = "Data through the relay this month (this phone): ${"%.1f".format(used / 1_048_576.0)} MB of the free 1 GB" +
            if (used >= Relay.SCREEN_LIMIT_BYTES) "\nScreen watching through the relay is paused until next month (it passed $limit MB)."
            else "\nScreen watching through the relay stops at $limit MB."
        val peers = link.pairedPeers()
        phones.text = if (peers.isEmpty()) {
            "No linked phones yet."
        } else {
            peers.joinToString("\n") { p ->
                "${p.name}: " + when (link.relay.online(p.id)) {
                    true -> "🟢 on the relay"
                    false -> "⚪ offline"
                    null -> if (link.relay.connected) "… not seen yet (it needs this update and the relay too)" else "—"
                }
            }
        }
    }

    private fun save() {
        val h = host.text.toString().trim()
        val u = user.text.toString().trim()
        val p = pass.text.toString()
        if (h.isBlank() || u.isBlank() || p.isBlank()) {
            toast("Fill in all three: address, username and password.")
            return
        }
        val (hostName, port) = Relay.splitHost(h)
        link.relay.save(Relay.Config(hostName, port, u, p))
        toast("Saved. Connecting…")
        share(quiet = true)
    }

    private fun share(quiet: Boolean = false) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { link.shareRelayNow() }.getOrElse { it.message ?: "failed" } }
            if (!quiet) AlertDialog.Builder(this@RelayActivity).setTitle("Your other phones").setMessage(result).setPositiveButton("OK", null).show()
        }
    }

    private fun remove() {
        AlertDialog.Builder(this)
            .setMessage("Remove the relay from this phone? Away from home Wi-Fi it won't be reachable (unless Tailscale is on).")
            .setPositiveButton("Remove") { _, _ ->
                link.relay.save(null)
                host.setText(""); user.setText(""); pass.setText("")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
