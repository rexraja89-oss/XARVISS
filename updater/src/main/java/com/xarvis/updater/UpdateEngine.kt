package com.xarvis.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The update work without a screen: what's on this phone, what GitHub has, rebuilding/checking the new APK and
 * installing it. Used by the Updater's screen and by [UpdateService] (XARVIS asking in the background — Rex on the
 * benco saying "update my S22").
 */
object UpdateEngine {
    /** [offer]: what the screen says to offer installing it on a phone that doesn't have it (null: only updated). */
    class App(val label: String, val pkg: String, val offer: String? = null)

    /** As the screen lists them. */
    val APPS = listOf(
        App("XARVIS", "com.xarvis.ai"),
        App("XARVIS Remote", "com.xarvis.remote"),
        App(
            "XARVIS Ears", "com.xarvis.ears",
            offer = "Optional. Hears \"Xarvis\" with the screen on or off and lets you talk with XARVIS hands-free — for a " +
                "phone whose XARVIS can't use the microphone itself (the benco). The S22's XARVIS listens by itself " +
                "(☰ → Say \"Xarvis\") and doesn't need it.",
        ),
        App("XARVIS Updater", "com.xarvis.updater"),
    )

    /** An app's APK on GitHub (XARVIS's depends on the phone's build, see [installed]). */
    fun fileOf(pkg: String): String = when (pkg) {
        "com.xarvis.remote" -> "XARVIS-Remote.apk"
        "com.xarvis.ears" -> "XARVIS-Ears.apk"
        "com.xarvis.updater" -> "XARVIS-Updater.apk"
        else -> "XARVIS.apk"
    }

    /**
     * Install order: XARVIS Remote first, then XARVIS (installing it restarts XARVIS), the Updater itself last
     * (installing it ends this app, so nothing after it would run).
     */
    fun order(pkg: String): Int = when (pkg) {
        "com.xarvis.remote" -> 0
        "com.xarvis.ai" -> 1
        "com.xarvis.updater" -> 3
        else -> 2
    }

    /** One update at a time, whoever starts it (the screen or XARVIS). */
    val running = AtomicBoolean(false)

    /** A full download bigger than this is never started without Rex saying so on the screen. */
    const val BIG_DOWNLOAD = 20_000_000L

    /**
     * An app on this phone: its version, its installed APK and that APK's name on GitHub. [installer]: the app that
     * installed it last (its "installer of record"), null if Android won't say.
     */
    class Installed(
        val app: App, val version: String, val apk: File, val file: String, val build: String?, val src: String?,
        val installer: String? = null,
        /** Not on this phone yet: [version] is "0" and [apk] doesn't exist (a first install of an [App.offer]). */
        val absent: Boolean = false,
    ) {
        /** Android installs this app's next update without its "update?" prompt (see [Installer]). */
        fun quiet(updater: String): Boolean = installer == updater || app.pkg == updater
    }

    fun installed(context: Context, app: App): Installed? {
        val info: PackageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(app.pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        }.getOrNull() ?: return null
        val apk = File(info.applicationInfo?.sourceDir ?: return null)
        val (file, build) = when (app.pkg) {
            // The S22's build may place calls; the benco's build leaves that permission out.
            "com.xarvis.ai" -> if (info.requestedPermissions?.contains("android.permission.CALL_PHONE") == true) "XARVIS.apk" to "S22 build" else "XARVIS-benco.apk" to "benco build"
            else -> fileOf(app.pkg) to null
        }
        val src = info.applicationInfo?.metaData?.getString("xarvis.src")?.takeIf { it.isNotBlank() }
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { context.packageManager.getInstallSourceInfo(app.pkg).installingPackageName }.getOrNull()
        } else null
        return Installed(app, info.versionName ?: "0", apk, file, build, src, installer)
    }

    /** [app] as something to install for the first time (it isn't on this phone). */
    fun absent(app: App): Installed = Installed(app, "0", File("/nonexistent/${app.pkg}.apk"), fileOf(app.pkg), null, null, absent = true)

    /** The newest release ([tag], "v1.0.282") and its patches.json ([release] null while GitHub is still making it). */
    class Latest(val tag: String, val release: Release?) {
        val version: String get() = tag.removePrefix("v")
    }

    /** Blocking (network). Throws when GitHub can't be reached. */
    fun latest(): Latest {
        val tag = Net.latestTag()
        val release = runCatching { Net.text(Net.assetUrl(tag, "patches.json"))?.let { Release.parse(it) } }.getOrNull()
        return Latest(tag, release)
    }

    /** What to do for [i]. Blocking (reads the installed APK when a patch might fit). */
    fun choose(i: Installed, latest: Latest): Choice {
        val release = latest.release
        if (!Release.isNewer(latest.version, i.version)) return Choice.UpToDate
        // Nothing it's built from changed: no download at all.
        if (!i.src.isNullOrBlank() && release?.takeIf { it.version == latest.version }?.files?.get(i.file)?.src == i.src) return Choice.Unchanged
        // The installed APK's fingerprint picks the patch that starts from exactly this file.
        val sha = if (release?.patches?.isNotEmpty() == true) runCatching { Patch.sha256(i.apk) }.getOrNull() else null
        return Choice.of(i.version, sha, i.file, latest.version, release, i.src)
    }

    /** How much [choice] downloads for [i]. */
    fun downloadSize(i: Installed, choice: Choice): Long = when (choice) {
        is Choice.ViaPatch -> choice.patch.size
        is Choice.Full -> choice.target?.size ?: i.apk.length()
        else -> 0
    }

    /** Where [pkg]'s download and rebuilt APK go (one folder per app, so "Update all" keeps each one's file). */
    private fun dirFor(context: Context, pkg: String) = File(File(context.cacheDir, "update"), pkg).apply { mkdirs() }

    /** Downloads the patch (or the whole APK) and returns the APK to install, checked against GitHub's fingerprint. */
    fun prepare(context: Context, i: Installed, tag: String, choice: Choice, say: (String) -> Unit): File {
        val dir = dirFor(context, i.app.pkg)
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
                    throw IOException("The patch arrived damaged — try again")
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
                        throw IOException("The download arrived damaged — try again")
                    }
                    say("✓ Checked: identical to GitHub's v$latest.")
                }
                return out
            }
            Choice.UpToDate, Choice.Unchanged -> throw IOException("already up to date")
        }
    }

    /** After [pkg] installed: its download and rebuilt APK aren't needed any more. */
    fun cleanUp(context: Context, pkg: String) {
        File(File(context.cacheDir, "update"), pkg).deleteRecursively()
    }

    /** Leftovers older than two days (a broken full download is resumed if it's newer). */
    fun dropOld(context: Context) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, "update").walkBottomUp().forEach {
            if (it.isFile && now - it.lastModified() > 48 * 3_600_000L) it.delete()
            else if (it.isDirectory && it.list()?.isEmpty() == true) it.delete()
        }
    }

    private fun keepOnly(dir: File, keep: File) {
        dir.listFiles()?.forEach { if (it.name != keep.name) it.deleteRecursively() }
    }

    /**
     * "Update everything" with no screen (XARVIS asked — Rex on the other phone): small updates only (a full download
     * over [BIG_DOWNLOAD] is left for Rex to start on the screen), installed without Android's prompt where Android
     * allows it. [say] gets each milestone; the answer is the last line ("🏁 …", or "✓ …"/"⚠ …" when nothing changed).
     * Blocking: run it off the main thread.
     */
    suspend fun updateAll(context: Context, say: (String) -> Unit): String {
        if (!running.compareAndSet(false, true)) return "⏳ XARVIS Updater is already updating — wait for it to finish."
        val lock = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis-updater:update").apply { acquire(20 * 60_000L) }
        }.getOrNull()
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                return "⚠ XARVIS Updater may not install apps on this phone yet: open it there once and tap Update " +
                    "(Android asks for \"Install unknown apps\" one time)."
            }
            val latest = try {
                latest()
            } catch (e: Exception) {
                return "⚠ Couldn't reach GitHub (${e.message ?: e.javaClass.simpleName}). Nothing changed."
            }
            if (latest.release?.version != latest.version) {
                return "⏳ v${latest.version}'s small update is still being made on GitHub (about 10 minutes after a release). Ask again in a few minutes."
            }
            val todo = APPS.mapNotNull { installed(context, it) }
                .map { it to choose(it, latest) }
                .filter { (_, c) -> c is Choice.ViaPatch || c is Choice.Full }
            if (todo.isEmpty()) return "✓ Everything here is up to date (v${latest.version}) — nothing to download."
            say("⬆ Updating to v${latest.version}: " + todo.joinToString(", ") { (i, c) -> "${i.app.label} ${mb(downloadSize(i, c))}" })
            val lines = ArrayList<String>()
            val ready = ArrayList<Pair<Installed, File>>()
            for ((i, c) in todo) {
                val size = downloadSize(i, c)
                if (c is Choice.Full && size > BIG_DOWNLOAD) {
                    lines += "⚠ ${i.app.label}: no small update starts from v${i.version}, it needs the whole ${mb(size)} — " +
                        "open XARVIS Updater on this phone if you want that."
                    say(lines.last())
                    continue
                }
                runCatching { prepare(context, i, latest.tag, c) {} }
                    .onSuccess { ready += i to it }
                    .onFailure {
                        lines += "⚠ ${i.app.label}: ${it.message ?: it.javaClass.simpleName}. Nothing changed."
                        say(lines.last())
                    }
            }
            for ((i, apk) in ready.sortedBy { order(it.first.app.pkg) }) {
                when (i.app.pkg) {
                    "com.xarvis.ai" -> say("⬆ Installing XARVIS v${latest.version} — XARVIS restarts and says when it's back.")
                    context.packageName -> say("⬆ Installing XARVIS Updater v${latest.version} last (it restarts itself).")
                }
                val outcome = runCatching { Installer.installAndWait(context, apk, i.app.pkg, onConfirm = null) }
                    .getOrElse { Installer.Outcome.Failed(it.message ?: it.javaClass.simpleName) }
                lines += when (outcome) {
                    Installer.Outcome.Installed -> "✅ ${i.app.label} v${latest.version}"
                    Installer.Outcome.NeedsTap -> "⚠ ${i.app.label}: Android wants a tap to install it — open XARVIS Updater on this phone and tap Update."
                    Installer.Outcome.Cancelled -> "⚠ ${i.app.label}: cancelled. Nothing changed."
                    is Installer.Outcome.Failed -> "⚠ ${i.app.label}: Android didn't install it (${outcome.why}). Nothing changed."
                    is Installer.Outcome.Confirm -> "⚠ ${i.app.label}: Android wants a tap to install it."
                }
                say(lines.last())
                if (outcome == Installer.Outcome.Installed) cleanUp(context, i.app.pkg)
            }
            return "🏁 Update finished: " + lines.joinToString(" · ")
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
            running.set(false)
        }
    }

    /**
     * One line under each app: whether its next update goes in without Android's prompt, and if not, why (13 Nov 2026:
     * on the first "Update all" only the S22's XARVIS went in quietly — the others had last been installed some other
     * way, and Android only skips the prompt for apps the Updater installed last).
     */
    fun quietNote(context: Context, i: Installed): String {
        val me = context.packageName
        if (android.os.Build.VERSION.SDK_INT < 31) return "Android asks before each update (this Android is older than 12)."
        if (i.quiet(me)) return "Next updates go in without Android asking."
        val by = i.installer?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + " ($pkg)"
            }.getOrDefault(pkg)
        } ?: "unknown"
        return "Last installed by $by, so Android asks once — after that, no more."
    }

    fun mb(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1_000_000 -> "%.2f MB".format(bytes / 1e6)
        else -> "%.1f MB".format(bytes / 1e6)
    }
}
