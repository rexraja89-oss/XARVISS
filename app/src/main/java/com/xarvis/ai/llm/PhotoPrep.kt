package com.xarvis.ai.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a photo Rex picked into a small, upright JPEG in XARVIS's cache for Gemma to look at.
 * Phone photos are 12+ MP; Gemma's vision works at about 1 MP, and a smaller file is much faster.
 */
object PhotoPrep {

    private const val MAX_SIDE = 1024

    suspend fun prepare(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("couldn't read that photo")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() } // keep the last 20
        File(dir, "photo-${System.currentTimeMillis()}.jpg").also { out ->
            out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
    }
}
