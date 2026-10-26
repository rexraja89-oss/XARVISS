package com.xarvis.ai.tools

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Finds the screenshot Rex just took, so XARVIS can offer to attach it (auto screenshot paste).
 * Reads MediaStore (needs READ_MEDIA_IMAGES on Android 13+); returns null when there's no recent one
 * or the permission isn't granted. Only looks at the Screenshots folder, and only very recent shots,
 * so it never surprises Rex with an old photo.
 */
object ScreenshotFinder {

    /** The most recent screenshot taken within [withinSeconds], or null. */
    fun recent(context: Context, withinSeconds: Long = 120): Uri? = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)"
        val args = arrayOf("%Screenshots%", "Screenshots")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val added = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) // seconds
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec - added > withinSeconds) return null
            ContentUris.withAppendedId(collection, id)
        }
    }.getOrNull()
}
