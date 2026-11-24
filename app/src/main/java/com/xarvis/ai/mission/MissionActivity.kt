package com.xarvis.ai.mission

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.code.XarvisCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ☰ → 🛠 Claude's missions: connect the private bridge repo, switch missions on, see what ran. */
class MissionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var state: TextView
    private lateinit var history: TextView
    private lateinit var repoBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as XarvisApp).core
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(text("🛠 Claude's missions", 24f, bold = true))
        root.addView(
            text(
                "Claude (the AI that builds XARVIS) can send this phone a list of exact steps — open Settings, tap this, " +
                    "take a screenshot — through your own private GitHub repo. XARVIS does them with its Hands and writes " +
                    "a report back with screenshots, so you don't have to follow step-by-step guides.\n\n" +
                    "Your rules still hold: never your pattern, PIN, fingerprint or passwords, nothing with money or banking. " +
                    "The ✱ shows while it works and its STOP ends a mission.",
                14f, Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, dp(12)) },
        )

        val hasKey = core.githubKey() != null
        root.addView(
            text(
                if (hasKey) "✓ GitHub key: the one XARVIS Code uses." else "⚠ No GitHub key yet. Open ☰ → XARVIS Code and connect GitHub first.",
                15f, if (hasKey) Color.rgb(0, 120, 60) else Color.rgb(190, 60, 0),
            ),
        )

        root.addView(text("Bridge repo (owner/name)", 15f, bold = true).apply { setPadding(0, dp(14), 0, 0) })
        repoBox = EditText(this).apply {
            setText(MissionBridge.repo(this@MissionActivity))
            hint = "empty = <your GitHub name>/${BridgeRepo.DEFAULT_NAME}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        root.addView(repoBox)
        root.addView(button("💾 Save repo name") { saveRepo() })
        root.addView(button("➕ Create my private bridge repo") { createRepo() }.apply { isEnabled = hasKey })

        root.addView(Switch(this).apply {
            text = "Accept Claude's missions on this phone"
            textSize = 16f
            isChecked = MissionBridge.on(this@MissionActivity)
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                saveRepo()
                MissionBridge.setOn(this@MissionActivity, on)
                if (on) checkNow()
            }
        })
        root.addView(button("🔄 Check for missions now") { checkNow() })
        state = text("", 15f).apply { setTextIsSelectable(true); setPadding(0, dp(10), 0, dp(10)) }
        root.addView(state)
        root.addView(text("Recent", 18f, bold = true))
        history = text("", 14f, Color.DKGRAY).apply { setTextIsSelectable(true) }
        root.addView(history)

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(root) })
        scope.launch { MissionBridge.status.collectLatest { show() } }
        show()
    }

    private fun show() {
        val on = MissionBridge.on(this)
        state.text = (if (on) "🟢 On — looking every 30 s. " else "⚪ Off. ") + MissionBridge.status.value
        history.text = MissionBridge.history(this).joinToString("\n").ifBlank { "Nothing yet." }
    }

    private fun saveRepo() {
        val full = repoBox.text.toString().trim()
        if (full.isBlank()) return
        if (Mission.split(full) == null) { state.text = "⚠ Write it as owner/name, e.g. rexraja89-oss/${BridgeRepo.DEFAULT_NAME}"; return }
        if (full != MissionBridge.repo(this)) MissionBridge.setRepo(this, full)
        show()
    }

    private fun createRepo() {
        val token = (application as XarvisApp).core.githubKey() ?: return
        state.text = "Creating your private repo…"
        scope.launch {
            val (msg, full) = withContext(Dispatchers.IO) {
                val who = XarvisCode.verify(token)
                if (!who.ok || who.data.isNullOrBlank()) return@withContext "⚠ ${who.message}" to null
                val made = XarvisCode.createRepo(token, BridgeRepo.DEFAULT_NAME, "XARVIS ⇄ Claude missions (private)", private = true)
                val full = "${who.data}/${BridgeRepo.DEFAULT_NAME}"
                when {
                    made.ok -> "✓ Created $full (private)." to full
                    made.message.contains("already exists", true) -> "✓ $full already exists; using it." to full
                    else -> "⚠ ${made.message}" to null
                }
            }
            if (full != null) { repoBox.setText(full); MissionBridge.setRepo(this@MissionActivity, full) }
            state.text = msg + if (full != null) "\nNow switch on \"Accept Claude's missions\" and tell Claude the repo name." else ""
        }
    }

    private fun checkNow() {
        state.text = "Checking…"
        MissionBridge.checkInBackground(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }

    private fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
