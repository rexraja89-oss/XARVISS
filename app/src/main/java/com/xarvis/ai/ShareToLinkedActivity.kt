package com.xarvis.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.xarvis.ai.files.DocumentReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share → "Send to my other phone", from any app: files go to the linked phone's Downloads › XARVIS (and
 * show in its chat), shared text goes onto its clipboard. Over XARVIS's encrypted link, either way round.
 */
class ShareToLinkedActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = streams(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) {
            finish()
            return
        }
        Toast.makeText(this, "Sending to your other phone…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = runCatching { send(uris, text) }.getOrElse { "Couldn't send: ${it.message ?: it.javaClass.simpleName}" }
            Toast.makeText(applicationContext, result, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private suspend fun send(uris: List<Uri>, text: String?): String = withContext(Dispatchers.IO) {
        val link = (application as XarvisApp).core.deviceLink
        val peer = link.reachable().firstOrNull()?.first
            ?: return@withContext "I can't reach your other phone. Make sure XARVIS is running on it with Tailscale on."
        if (uris.isEmpty()) {
            link.sendClip(peer, text.orEmpty())
            return@withContext "Sent to ${peer.name}: it's on its clipboard."
        }
        var sent = 0
        val problems = mutableListOf<String>()
        for (u in uris) {
            val name = DocumentReader.displayName(this@ShareToLinkedActivity, u)
            val size = runCatching { contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: -1L
            if (size > MAX_BYTES) {
                problems += "$name is over ${MAX_BYTES / 1_000_000} MB"
                continue
            }
            val bytes = runCatching { contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.size > MAX_BYTES) {
                problems += "couldn't read $name"
                continue
            }
            runCatching { link.sendFile(peer, name, contentResolver.getType(u) ?: "application/octet-stream", bytes) }
                .onSuccess { sent++ }
                .onFailure { problems += "$name: ${it.message ?: it.javaClass.simpleName}" }
        }
        buildString {
            if (sent > 0) append("Sent $sent file${if (sent == 1) "" else "s"} to ${peer.name} (Downloads › XARVIS).")
            if (problems.isNotEmpty()) append(if (sent > 0) " " else "").append("Not sent: ").append(problems.joinToString("; "))
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Files go whole over the link (base64 in one message), so keep them phone-sized. */
        const val MAX_BYTES = 25_000_000L
    }
}
