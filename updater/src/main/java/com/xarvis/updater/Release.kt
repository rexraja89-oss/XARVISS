package com.xarvis.updater

import org.json.JSONObject

/**
 * What GitHub offers for the newest release: patches.json (made by `.github/scripts/make_patches.py` a few minutes
 * after each release) — each APK's size and SHA-256, and the patches from earlier releases. Pure, unit-tested.
 */
data class Release(val version: String, val files: Map<String, FileInfo>, val patches: List<PatchInfo>) {
    data class FileInfo(val sha256: String, val size: Long)

    /** Turns the file with SHA-256 [fromSha256] (release [from]) into [file] of this release. */
    data class PatchInfo(val file: String, val from: String, val fromSha256: String, val asset: String, val size: Long, val sha256: String)

    /** The patch that starts from exactly the installed APK (its SHA-256), if GitHub made one. */
    fun patchFor(installedSha256: String): PatchInfo? =
        patches.firstOrNull { it.fromSha256.equals(installedSha256, ignoreCase = true) && files.containsKey(it.file) }

    companion object {
        fun parse(json: String): Release {
            val o = JSONObject(json)
            val files = HashMap<String, FileInfo>()
            o.optJSONObject("files")?.let { f ->
                for (name in f.keys()) {
                    val e = f.getJSONObject(name)
                    files[name] = FileInfo(e.getString("sha256").lowercase(), e.getLong("size"))
                }
            }
            val patches = ArrayList<PatchInfo>()
            o.optJSONArray("patches")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    patches += PatchInfo(
                        p.getString("file"), p.optString("from"), p.getString("fromSha256").lowercase(),
                        p.getString("asset"), p.getLong("size"), p.getString("sha256").lowercase(),
                    )
                }
            }
            return Release(o.getString("version"), files, patches)
        }

        /** ".../releases/tag/v1.0.263" → "v1.0.263". */
        fun tagFromRedirect(location: String?): String? =
            location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.takeIf { it.isNotBlank() }

        /** "1.0.263" is newer than "1.0.261"; tags that aren't plain versions never are. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** What to do for one installed app. */
sealed interface Choice {
    data object UpToDate : Choice

    /** Download [patch] (small) and rebuild [file] ([target] says what the result must be). */
    data class ViaPatch(val patch: Release.PatchInfo, val file: String, val target: Release.FileInfo) : Choice

    /** No patch starts from this phone's APK: the whole [file] ([target] null = GitHub hasn't listed it yet). */
    data class Full(val file: String, val target: Release.FileInfo?) : Choice

    companion object {
        /**
         * [installedVersion] / [installedSha256]: the app on this phone; [file]: its APK's name on GitHub (XARVIS.apk on
         * the S22, XARVIS-benco.apk on the benco); [latest]: the newest release's version; [release]: its patches.json,
         * or null while GitHub is still making it.
         */
        fun of(installedVersion: String, installedSha256: String?, file: String, latest: String, release: Release?): Choice {
            if (!Release.isNewer(latest, installedVersion)) return UpToDate
            val r = release?.takeIf { it.version == latest } ?: return Full(file, null)
            installedSha256?.let { r.patchFor(it) }?.let { p -> return ViaPatch(p, p.file, r.files.getValue(p.file)) }
            return Full(file, r.files[file])
        }
    }
}
