package com.xarvis.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import java.io.IOException

/**
 * One screen: each XARVIS app on this phone, its version, and an Update button that downloads only what changed.
 * Nothing happens without a tap, nothing installs unless the rebuilt APK is identical to GitHub's, and Android's own
 * installer still asks before it updates. XARVIS's own UPDATE button (the full download in Chrome) keeps working.
 */
class UpdaterActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var checking: Job? = null
    private var busy = false
    private var resumed = false
    private var waitingRow: Row? = null
    private var waitingVersion = ""
    private var pendingConfirm: Intent? = null
    private var lastCheck = 0L
    private val buttons = ArrayList<Button>()
    private val listener: (Installer.Outcome) -> Unit = { onInstall(it) }

    private class App(val label: String, val pkg: String)

    private val apps = listOf(
        App("XARVIS", "com.xarvis.ai"),
        App("XARVIS Remote", "com.xarvis.remote"),
        App("XARVIS Updater", "com.xarvis.updater"),
    )

    /** An app on this phone: its version, its installed APK and that APK's name on GitHub. */
    private class Installed(val app: App, val version: String, val apk: File, val file: String, val build: String?)

    private inner class Row(val box: LinearLayout, val note: TextView) {
        fun say(text: String) = runOnUiThread { note.text = text; note.visibility = View.VISIBLE }
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
                    "app. Your XARVIS is rebuilt here, checked to be identical to GitHub's, then Android asks you to install it as usual.",
                14f, color = Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, dp(12)) },
        )
        status = text("", 15f).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(status)
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
        Installer.listener = listener
        // Leftovers older than two days (a broken full download is resumed if it's newer).
        File(cacheDir, "update").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 48 * 3_600_000L) it.delete() }
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
        if (Installer.listener === listener) Installer.listener = null
        scope.cancel()
        super.onDestroy()
    }

    private fun check() {
        if (busy) return
        lastCheck = System.currentTimeMillis()
        checking?.cancel()
        list.removeAllViews()
        buttons.clear()
        status.text = "Checking GitHub…"
        checking = scope.launch {
            val found = apps.mapNotNull { installed(it) }
            val tag = withContext(Dispatchers.IO) { runCatching { Net.latestTag() } }.getOrElse {
                status.text = "⚠ Couldn't reach GitHub (${it.message}). Check the internet, then tap Check again."
                return@launch
            }
            val latest = tag.removePrefix("v")
            val release = withContext(Dispatchers.IO) {
                runCatching { Net.text(Net.assetUrl(tag, "patches.json"))?.let { Release.parse(it) } }.getOrNull()
            }
            status.text = "Newest on GitHub: v$latest" + if (release == null) {
                "\n⏳ Its small update is still being made on GitHub (about 10 minutes after each release). Check again soon."
            } else ""
            for (i in found) {
                val row = addRow(i)
                if (!Release.isNewer(latest, i.version)) {
                    row.say("✓ Up to date (v${i.version})")
                    continue
                }
                row.say("v${i.version} → v$latest · looking at the one on this phone…")
                // The installed APK's fingerprint picks the patch that starts from exactly this file.
                val sha = if (release?.patches?.isNotEmpty() == true) {
                    withContext(Dispatchers.IO) { runCatching { Patch.sha256(i.apk) }.getOrNull() }
                } else null
                show(row, i, tag, Choice.of(i.version, sha, i.file, latest, release), release)
            }
        }
    }

    private fun show(row: Row, i: Installed, tag: String, choice: Choice, release: Release?) {
        val latest = tag.removePrefix("v")
        when (choice) {
            Choice.UpToDate -> row.say("✓ Up to date (v${i.version})")
            is Choice.ViaPatch -> {
                row.say("v${i.version} → v$latest")
                addButton(row, "⬇ Update — download ${mb(choice.patch.size)} (instead of ${mb(choice.target.size)})") {
                    update(row, i, tag, choice)
                }
            }
            is Choice.Full -> {
                val size = choice.target?.size ?: i.apk.length()
                val patched = release?.patches?.any { it.file == choice.file } == true
                row.say(
                    "v${i.version} → v$latest" + when {
                        release == null -> "\nThe small update isn't ready yet — check again in a few minutes."
                        patched -> "\nNo small update starts from your version (it's several releases old, or not from GitHub)."
                        else -> ""
                    },
                )
                addButton(row, "⬇ Update — full download ${mb(size)}") {
                    if (size > 20_000_000) {
                        AlertDialog.Builder(this)
                            .setTitle("Full download")
                            .setMessage("This downloads the whole ${i.app.label} (${mb(size)}). Go ahead?")
                            .setPositiveButton("Download") { _, _ -> update(row, i, tag, choice) }
                            .setNegativeButton("Not now", null)
                            .show()
                    } else {
                        update(row, i, tag, choice)
                    }
                }
            }
        }
    }

    private fun update(row: Row, i: Installed, tag: String, choice: Choice) {
        if (busy) return
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
        busy = true
        buttons.forEach { it.isEnabled = false }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val latest = tag.removePrefix("v")
        scope.launch {
            val apk = withContext(Dispatchers.IO) { runCatching { prepare(i, tag, choice, row::say) } }
            apk.onSuccess { f ->
                row.say("Android will now ask you to update ${i.app.label} — tap Update.")
                waitingRow = row
                waitingVersion = latest
                withContext(Dispatchers.IO) { runCatching { Installer.install(applicationContext, f, i.app.pkg) } }
                    .onFailure { done(row, "⚠ Android's installer didn't start (${it.message}). Nothing changed.") }
            }.onFailure {
                done(row, "⚠ ${it.message ?: it.javaClass.simpleName}. Nothing was installed.")
            }
        }
    }

    /** Downloads the patch (or the whole APK) and returns the APK to install, checked against GitHub's fingerprint. */
    private fun prepare(i: Installed, tag: String, choice: Choice, say: (String) -> Unit): File {
        val dir = File(cacheDir, "update").apply { mkdirs() }
        val latest = tag.removePrefix("v")
        when (choice) {
            is Choice.ViaPatch -> {
                val p = choice.patch
                val patchFile = File(dir, p.asset)
                keepOnly(dir, patchFile)
                if (patchFile.length() > p.size) patchFile.delete()
                Net.download(Net.assetUrl(tag, p.asset), patchFile) { done, _ -> say("Downloading the changes: ${mb(done)} of ${mb(p.size)}") }
                if (Patch.sha256(patchFile) != p.sha256) {
                    patchFile.delete()
                    throw IOException("The patch arrived damaged — tap Update to try again")
                }
                say("Building the new ${i.app.label} from the one on this phone…")
                val out = File(dir, "v$latest-${choice.file}")
                out.delete()
                val sha = try {
                    Patch.apply(i.apk, patchFile, out) { done, total -> say("Building the new ${i.app.label}: ${done * 100 / maxOf(total, 1)}%") }
                } catch (e: Exception) {
                    out.delete()
                    throw IOException("The patch couldn't be applied (${e.message})")
                }
                patchFile.delete()
                if (sha != choice.target.sha256 || out.length() != choice.target.size) {
                    out.delete()
                    throw IOException("The rebuilt app wasn't identical to GitHub's")
                }
                say("✓ Checked: identical to GitHub's v$latest.")
                return out
            }
            is Choice.Full -> {
                val out = File(dir, "v$latest-${choice.file}")
                keepOnly(dir, out)
                choice.target?.let { if (out.length() > it.size) out.delete() }
                val size = choice.target?.size
                Net.download(Net.assetUrl(tag, choice.file), out) { done, total ->
                    say("Downloading: ${mb(done)} of ${mb(size ?: total)}")
                }
                choice.target?.let { t ->
                    if (out.length() != t.size || Patch.sha256(out) != t.sha256) {
                        out.delete()
                        throw IOException("The download arrived damaged — tap Update to try again")
                    }
                    say("✓ Checked: identical to GitHub's v$latest.")
                }
                return out
            }
            Choice.UpToDate -> throw IOException("already up to date")
        }
    }

    private fun onInstall(outcome: Installer.Outcome) {
        val row = waitingRow
        when (outcome) {
            is Installer.Outcome.Confirm -> if (resumed) startActivity(outcome.intent) else pendingConfirm = outcome.intent
            Installer.Outcome.Installed -> {
                done(row, "✅ Updated to v$waitingVersion.")
                cleanUp()
                status.postDelayed({ lastCheck = 0; if (!busy) check() }, 1_500)
            }
            Installer.Outcome.Cancelled -> done(row, "Cancelled — nothing changed. Tap Update to try again.")
            is Installer.Outcome.Failed -> done(row, "⚠ Android didn't install it: ${outcome.why}. Nothing changed.")
        }
    }

    private fun done(row: Row?, message: String) {
        row?.say(message)
        busy = false
        waitingRow = null
        buttons.forEach { it.isEnabled = true }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun installed(app: App): Installed? {
        val info: PackageInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(app.pkg, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return null
        val apk = File(info.applicationInfo?.sourceDir ?: return null)
        val (file, build) = when (app.pkg) {
            // The S22's build may place calls; the benco's build leaves that permission out.
            "com.xarvis.ai" -> if (info.requestedPermissions?.contains("android.permission.CALL_PHONE") == true) "XARVIS.apk" to "S22 build" else "XARVIS-benco.apk" to "benco build"
            "com.xarvis.remote" -> "XARVIS-Remote.apk" to null
            else -> "XARVIS-Updater.apk" to null
        }
        return Installed(app, info.versionName ?: "0", apk, file, build)
    }

    private fun addRow(i: Installed): Row {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(text(i.app.label + (i.build?.let { " ($it)" } ?: ""), 18f, bold = true))
        val note = text("", 14f, color = Color.DKGRAY)
        box.addView(note)
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

    /** Leftovers from earlier updates (a full XARVIS is ~107 MB). */
    private fun cleanUp() {
        File(cacheDir, "update").listFiles()?.forEach { it.delete() }
    }

    private fun keepOnly(dir: File, keep: File) {
        dir.listFiles()?.forEach { if (it.name != keep.name) it.delete() }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun mb(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1_000_000 -> "%.2f MB".format(bytes / 1e6)
            else -> "%.1f MB".format(bytes / 1e6)
        }
    }
}
