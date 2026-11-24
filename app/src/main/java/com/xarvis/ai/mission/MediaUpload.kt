package com.xarvis.ai.mission

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.xarvis.ai.tools.PermissionGate
import java.io.File

/**
 * The `upload` mission step (25 Nov 2026, for the YouTube channel): the phone's newest screen recording / video /
 * picture / screenshot goes to the private bridge repo's "media" release, where Claude edits it into a video.
 * Only the single newest item of the kind the mission names, only to Rex's private repo.
 */
object MediaUpload {
    private const val MAX_BYTES = 1_500L * 1024 * 1024 // GitHub takes up to 2 GB an asset

    private class Item(val uri: Uri, val name: String, val size: Long)

    fun permissionFor(what: String) =
        if (what == "recording" || what == "video") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES

    /** Uploads; a line for the report. Throws with a plain reason when it can't. */
    fun upload(c: Context, gh: BridgeRepo, missionId: String, what: String): String {
        val perm = permissionFor(what)
        if (Build.VERSION.SDK_INT >= 33 && !PermissionGate.has(c, perm)) {
            // Android's own permission box; Hands keeps XARVIS allowed to open screens from the background.
            c.startActivity(Intent(c, MissionActivity::class.java).putExtra(MissionActivity.EXTRA_ASK, perm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw IllegalStateException("XARVIS may not see ${if (perm.endsWith("VIDEO")) "videos" else "pictures"} yet: Android's permission box is open now (tap Allow, then upload again)")
        }
        val item = newest(c, what) ?: throw IllegalStateException("no $what found on this phone")
        if (item.size > MAX_BYTES) throw IllegalStateException("${item.name} is ${item.size / 1_048_576} MB, more than GitHub takes")
        val dir = File(c.cacheDir, "mission-upload").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val safe = item.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val file = File(dir, "$missionId-$safe")
        try {
            c.contentResolver.openInputStream(item.uri)?.use { input -> file.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: throw IllegalStateException("Android wouldn't open ${item.name}")
            val r = gh.uploadAsset(TAG, file)
            if (!r.ok) throw IllegalStateException("GitHub upload failed: ${r.message}")
            return "uploaded ${item.name} (${"%.1f".format(file.length() / 1_048_576.0)} MB) as release \"$TAG\" asset ${file.name}"
        } finally {
            file.delete()
        }
    }

    private fun newest(c: Context, what: String): Item? {
        val video = what == "recording" || what == "video"
        val table = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val where = when (what) {
            "recording" -> "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            "screenshot" -> "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            else -> null
        }
        val args = when (what) {
            "recording" -> arrayOf("%Screen recordings%")
            "screenshot" -> arrayOf("%Screenshots%")
            else -> null
        }
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        c.contentResolver.query(table, cols, where, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur ->
            if (!cur.moveToFirst()) return null
            val id = cur.getLong(0)
            return Item(ContentUris.withAppendedId(table, id), cur.getString(1) ?: "item-$id", cur.getLong(2))
        }
        return null
    }

    const val TAG = "media"
}
