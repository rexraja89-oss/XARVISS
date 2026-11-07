package com.xarvis.ai.eyes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xarvis.ai.XarvisApp
import java.io.File

/**
 * XARVIS Eyes (the separate camera app) sends one photo + a question here. Only an app holding the
 * EYES signature permission (Rex's own same-key apps) can. The photo is written to the cache and
 * handed to the core, which has Gemma look at it and sends the answer back to Eyes.
 */
class EyesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jpeg = intent.getByteArrayExtra("jpeg") ?: return
        val question = intent.getStringExtra("question").orEmpty()
        val speak = intent.getBooleanExtra("speak", true)
        val id = intent.getLongExtra("id", 0L)
        val file = File(context.cacheDir, "photos/eyes-$id.jpg")
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(jpeg)
        }.onFailure { return }
        (context.applicationContext as XarvisApp).core.eyesLook(file, question, speak, id)
    }

    companion object {
        const val EYES_PKG = "com.xarvis.eyes"
        const val PERMISSION = "com.xarvis.ai.permission.EYES"
        const val ACTION_ANSWER = "com.xarvis.eyes.action.ANSWER"
    }
}
