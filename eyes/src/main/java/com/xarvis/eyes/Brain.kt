package com.xarvis.eyes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Eyes has no AI of its own: it hands one photo + a question to the main XARVIS app (whose Gemma
 * looks at it) and gets the answer back. Both directions are explicit broadcasts guarded by the
 * main app's signature permission, so only Rex's own same-key apps can take part.
 */
object Brain {
    const val XARVIS_PKG = "com.xarvis.ai"
    const val PERMISSION = "com.xarvis.ai.permission.EYES"
    private const val ACTION_LOOK = "com.xarvis.ai.action.LOOK"
    const val ACTION_ANSWER = "com.xarvis.eyes.action.ANSWER"

    // Kept under ~450 KB: a broadcast's data has to fit Android's ~1 MB transaction limit.
    private const val MAX_SIDE = 1024
    private const val MAX_BYTES = 450_000

    /** Set by the screen while it's open; an answer that arrives while it's closed waits in [pending]. */
    @Volatile var onAnswer: ((Long, String) -> Unit)? = null
    @Volatile var pending: Pair<Long, String>? = null

    fun xarvisInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(XARVIS_PKG, 0) }.isSuccess

    /** Granted when Eyes was installed after a XARVIS that defines it (same signing key). */
    fun linkAllowed(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Sends the photo and question to XARVIS. Returns the request id (to match the answer). */
    fun ask(context: Context, jpeg: ByteArray, question: String, speak: Boolean): Long {
        val id = System.currentTimeMillis()
        context.sendBroadcast(
            Intent(ACTION_LOOK).setPackage(XARVIS_PKG)
                .putExtra("jpeg", jpeg).putExtra("question", question)
                .putExtra("speak", speak).putExtra("id", id),
            PERMISSION,
        )
        return id
    }

    /** Upright, at most [MAX_SIDE] px, as a JPEG small enough to send. */
    fun prepare(photo: Bitmap, rotationDegrees: Int): ByteArray {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(photo.width, photo.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, m, true)
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BYTES || quality <= 40) return out.toByteArray()
            quality -= 10
        }
    }
}

/** XARVIS's answer arrives here and goes to the screen (or waits until it's open again). */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        val text = intent.getStringExtra("text") ?: return
        val listener = Brain.onAnswer
        if (listener != null) listener(id, text) else Brain.pending = id to text
    }
}
