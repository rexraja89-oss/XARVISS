package com.xarvis.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One screen: each XARVIS app on this phone, its version, and an Update button that downloads only what changed —
 * plus "⬆ Update all" (13 Nov 2026, Rex: "update all at one time"). Nothing installs unless the rebuilt APK is identical
 * to GitHub's. Android installs updates of apps the Updater installed last time without asking; otherwise it asks as
 * usual. Opened with [EXTRA_AUTO] (XARVIS's UPDATE button) it starts "Update all" by itself.
 */
class UpdaterActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var allButton: Button
    private var checking: Job? = null
    private var busy = false
    private var resumed = false
    private var auto = false
    private var pendingConfirm: Intent? = null
    private var lastCheck = 0L
    private var tag = ""
    private val buttons = ArrayList<Button>()
    private val todo = ArrayList<Item>()

    private inner class Row(val box: LinearLayout, val note: TextView) {
        fun say(text: String) = runOnUiThread { note.text = text; note.visibility = View.VISIBLE }
    }

    /** An app with an update waiting, shown in [row]. */
    private class Item(val row: Row, val i: UpdateEngine.Installed, val choice: Choice) {
        val size: Long get() = UpdateEngine.downloadSize(i, choice)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(text("⬇ XARVIS Updater", 24f, bold = true))
        root.addView(
            text(
                "Updates XARVIS by downloading only what changed — usually well under 1 MB instead of the whole " +
                    "app. Your XARVIS is rebuilt here and checked to be identical to GitHub's before it's installed.",
                14f, color = Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, dp(12)) },
        )
        status = text("", 15f).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(status)
        allButton = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { runUpdates(todo.toList()) }
        }
        root.addView(allButton)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(Button(this).apply {
            text = "🔄 Check again"
            isAllCaps = false
            setOnClickListener { check() }
        })
        root.addView(
            text(
                "If anything here fails, nothing is installed and nothing changes. XARVIS's own UPDATE button " +
                    "(the full download in Chrome) always works too.",
                13f, color = Color.GRAY,
            ).apply { setPadding(0, dp(12), 0, dp(4)) },
        )
        root.addView(text("Updater v${versionOf(packageName) ?: "?"}", 12f, color = Color.GRAY))
        setContentView(ScrollView(this).apply { addView(root) })
        auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        UpdateEngine.dropOld(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
            auto = true
            lastCheck = 0
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        pendingConfirm?.let { pendingConfirm = null; startActivity(it) }
        if (!busy && System.currentTimeMillis() - lastCheck > 30_000) check()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun check() {
        if (busy) return
        lastCheck = System.currentTimeMillis()
        checking?.cancel()
        list.removeAllViews()
        buttons.clear()
        todo.clear()
        allButton.visibility = View.GONE
        status.text = "Checking GitHub…"
        checking = scope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { UpdateEngine.latest() } }.getOrElse {
                status.text = "⚠ Couldn't reach GitHub (${it.message}). Check the internet, then tap Check again."
                auto = false
                return@launch
            }
            tag = latest.tag
            status.text = "Newest on GitHub: v${latest.version}" + if (latest.release == null) {
                "\n⏳ Its small update is still being made on GitHub (about 10 minutes after each release). Check again soon."
            } else ""
            for (app in UpdateEngine.APPS) {
                val i = UpdateEngine.installed(this@UpdaterActivity, app)
                if (i == null) {
                    // Not on this phone: offered (never part of "Update all" — a new app is Rex's choice).
                    if (app.offer != null) offer(app, latest)
                    continue
                }
                val row = addRow(i)
                if (Release.isNewer(latest.version, i.version)) row.say("v${i.version} → v${latest.version} · looking at the one on this phone…")
                val choice = withContext(Dispatchers.IO) { UpdateEngine.choose(i, latest) }
                show(row, i, latest, choice)
            }
            if (todo.size > 1) {
                allButton.text = "⬆ Update all — download ${UpdateEngine.mb(todo.sumOf { it.size })}"
                allButton.visibility = View.VISIBLE
            }
            if (auto) {
                auto = false
                if (todo.isEmpty()) status.append("\n✓ Everything here is up to date.") else runUpdates(todo.toList())
            }
        }
    }

    /** A row for [app], which isn't on this phone, with an Install button once GitHub lists its file. */
    private fun offer(app: UpdateEngine.App, latest: UpdateEngine.Latest) {
        val i = UpdateEngine.absent(app)
        val row = addRow(i)
        val target = latest.release?.takeIf { it.version == latest.version }?.files?.get(i.file)
        if (target == null) {
            row.say(app.offer + "\nNot ready to install yet — check again in a few minutes.")
            return
        }
        row.say(app.offer.orEmpty())
        val item = Item(row, i, Choice.Full(i.file, target))
        addButton(row, "⬇ Install — download ${UpdateEngine.mb(target.size)}") { runUpdates(listOf(item)) }
    }

    private fun show(row: Row, i: UpdateEngine.Installed, latest: UpdateEngine.Latest, choice: Choice) {
        val release = latest.release
        when (choice) {
            Choice.UpToDate -> row.say("✓ Up to date (v${i.version})")
            Choice.Unchanged -> row.say("✓ Up to date — nothing in it changed since v${i.version}, so nothing to download")
            is Choice.ViaPatch -> {
                row.say("v${i.version} → v${latest.version}")
                val item = Item(row, i, choice).also { todo += it }
                addButton(row, "⬇ Update — download ${UpdateEngine.mb(choice.patch.size)} (instead of ${UpdateEngine.mb(choice.target.size)})") {
                    runUpdates(listOf(item))
                }
            }
            is Choice.Full -> {
                val patched = release?.patches?.any { it.file == choice.file } == true
                row.say(
                    "v${i.version} → v${latest.version}" + when {
                        release == null -> "\nThe small update isn't ready yet — check again in a few minutes."
                        patched -> "\nNo small update starts from your version (it's several releases old, or not from GitHub)."
                        else -> ""
                    },
                )
                val item = Item(row, i, choice).also { todo += it }
                addButton(row, "⬇ Update — full download ${UpdateEngine.mb(item.size)}") { runUpdates(listOf(item)) }
            }
        }
    }

    /**
     * Downloads and checks each of [items], then installs them in [UpdateEngine.order] (the Updater itself last). A
     * full download over 20 MB asks first ([bigOk] = Rex said yes).
     */
    private fun runUpdates(items: List<Item>, bigOk: Boolean = false) {
        if (busy || items.isEmpty()) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("One-time step")
                .setMessage("Allow XARVIS Updater to install apps: on the next screen switch on \"Allow from this source\", then come back and tap Update again.")
                .setPositiveButton("Open the setting") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        val big = items.filter { it.choice is Choice.Full && it.size > UpdateEngine.BIG_DOWNLOAD }
        if (big.isNotEmpty() && !bigOk) {
            val d = AlertDialog.Builder(this)
                .setTitle("Full download")
                .setMessage(big.joinToString("\n") { "This downloads the whole ${it.i.app.label} (${UpdateEngine.mb(it.size)})." } + "\nGo ahead?")
                .setPositiveButton("Download") { _, _ -> runUpdates(items, bigOk = true) }
                .setNegativeButton("Not now", null)
            if (items.size > big.size) d.setNeutralButton("Only the small ones") { _, _ -> runUpdates(items - big.toSet(), bigOk = true) }
            d.show()
            return
        }
        if (!UpdateEngine.running.compareAndSet(false, true)) {
            status.text = "⏳ XARVIS asked for an update in the background — it's running now. Check again in a minute."
            return
        }
        busy = true
        buttons.forEach { it.isEnabled = false }
        allButton.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val version = tag.removePrefix("v")
        scope.launch {
            try {
                val ready = ArrayList<Pair<Item, File>>()
                for (item in items) {
                    withContext(Dispatchers.IO) { runCatching { UpdateEngine.prepare(applicationContext, item.i, tag, item.choice, item.row::say) } }
                        .onSuccess { ready += item to it }
                        .onFailure { item.row.say("⚠ ${it.message ?: it.javaClass.simpleName}. Nothing was installed.") }
                }
                for ((item, apk) in ready.sortedBy { UpdateEngine.order(it.first.i.app.pkg) }) {
                    val label = item.i.app.label
                    item.row.say(if (item.i.app.pkg == packageName) "Installing… (the Updater restarts itself — open it again after)" else "Installing…")
                    val outcome = runCatching {
                        Installer.installAndWait(applicationContext, apk, item.i.app.pkg, onConfirm = { confirm ->
                            item.row.say(if (item.i.absent) "Android asks you to install $label — tap Install." else "Android asks you to update $label — tap Update.")
                            if (resumed) startActivity(confirm) else pendingConfirm = confirm
                        })
                    }.getOrElse { Installer.Outcome.Failed("the installer didn't start (${it.message})") }
                    item.row.say(
                        when (outcome) {
                            Installer.Outcome.Installed -> if (item.i.absent) "✅ Installed v$version. Open $label from your apps to switch it on." else "✅ Updated to v$version."
                            Installer.Outcome.Cancelled -> "Cancelled — nothing changed. Tap Update to try again."
                            is Installer.Outcome.Failed -> "⚠ Android didn't install it: ${outcome.why}. Nothing changed."
                            else -> "⚠ Android wanted a tap. Nothing changed — tap Update to try again."
                        },
                    )
                    if (outcome == Installer.Outcome.Installed) UpdateEngine.cleanUp(applicationContext, item.i.app.pkg)
                }
            } finally {
                UpdateEngine.running.set(false)
                busy = false
                buttons.forEach { it.isEnabled = true }
                allButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            status.postDelayed({ lastCheck = 0; if (!busy) check() }, 2_500)
        }
    }

    private fun addRow(i: UpdateEngine.Installed): Row {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(text(i.app.label + (i.build?.let { " ($it)" } ?: "") + if (i.absent) " — not on this phone" else "", 18f, bold = true))
        val note = text("", 14f, color = Color.DKGRAY)
        box.addView(note)
        if (!i.absent) box.addView(text(UpdateEngine.quietNote(this, i), 12f, color = Color.GRAY))
        list.addView(box)
        return Row(box, note)
    }

    private fun addButton(row: Row, label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            isEnabled = !busy
            setOnClickListener { onClick() }
        }
        buttons += b
        row.box.addView(b)
    }

    private fun versionOf(pkg: String): String? = runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** XARVIS's UPDATE button: start "Update all" as soon as the check is done. */
        const val EXTRA_AUTO = "auto"
    }
}
