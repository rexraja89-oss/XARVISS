package com.xarvis.remote

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Asks Android for screen sharing ("XARVIS Remote will start capturing…" → Start now) for live video. Rex taps it on
 * this phone, or from the other phone on the picture he's controlling. Nothing here ever presses it for him.
 */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finish()
            return
        }
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQUEST) }.onFailure { finish() }
    }

    @Deprecated("A one-off prompt: the plain Activity result is enough")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            RemoteService.giveConsent(this, resultCode, data)
        } else {
            Toast.makeText(this, "Live video not allowed: your other phone keeps seeing pictures.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7502
    }
}
