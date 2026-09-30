package com.xarvis.ai.files

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Take a photo": a new picture in Pictures/XARVIS that the phone's own camera app shoots into
 * (ACTION_IMAGE_CAPTURE), so XARVIS needs no camera permission (which could make the S22's
 * installer refuse XARVIS). Rex taps the shutter; the photo comes back into the chat.
 */
object CameraShots {

    fun create(context: Context): Uri? = runCatching {
        val name = "XARVIS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XARVIS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    fun intent(uri: Uri, selfie: Boolean): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply {
            clipData = ClipData.newRawUri("photo", uri)
            if (selfie) {
                // Not standard, but most camera apps (Samsung's included) honour one of these.
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
        }

    /** The photo is taken: show it in the Gallery. */
    fun finish(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    /** Cancelled: remove the empty picture. */
    fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
