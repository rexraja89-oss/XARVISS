package com.xarvis.ai

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xarvis.ai.service.WakeWord
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import com.xarvis.ai.ui.theme.XarvisTheme
import com.xarvis.ai.voice.VoiceRecorder
import com.xarvis.ai.voice.VoiceScript

/**
 * "Record my voice": shows one sentence at a time; Rex taps RECORD, reads it, taps STOP, can
 * PLAY it back, and moves on. Takes are kept, so he can stop any time and carry on later.
 * SEND RECORDINGS packs them into Downloads/XARVIS/xarvis-my-voice.zip for training.
 */
class RecordVoiceActivity : ComponentActivity() {

    private lateinit var recorder: VoiceRecorder
    private val hindi = mutableStateOf(false)
    private val index = mutableIntStateOf(0)
    private val recording = mutableStateOf(false)
    private val level = mutableFloatStateOf(0f)
    private val message = mutableStateOf("")
    private val version = mutableIntStateOf(0) // bumps when a take is saved, to redraw counts
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message.value = "XARVIS needs the microphone to record your voice."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = VoiceRecorder(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        index.intValue = firstMissing(false)
        setContent { XarvisTheme { Screen() } }
        if (!com.xarvis.ai.tools.PermissionGate.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        WakeWord.paused = true // "Hey Jarvis" lets go of the mic while Rex records
    }

    override fun onPause() {
        if (recording.value) { recorder.cancel(); recording.value = false }
        player?.release(); player = null
        WakeWord.paused = false
        super.onPause()
    }

    private fun lines() = if (hindi.value) VoiceScript.hindi else VoiceScript.english

    private fun firstMissing(inHindi: Boolean): Int {
        val all = if (inHindi) VoiceScript.hindi else VoiceScript.english
        return all.indices.firstOrNull { !recorder.has(inHindi, it) } ?: 0
    }

    private fun toggleRecord() {
        if (!recording.value) {
            player?.release(); player = null
            message.value = ""
            if (recorder.start { level.floatValue = it }) recording.value = true
            else message.value = "The microphone is busy. Close other apps using it and try again."
        } else {
            recording.value = false
            level.floatValue = 0f
            val problem = recorder.stop(hindi.value, index.intValue)
            version.intValue++
            if (problem != null) message.value = problem
            else {
                message.value = "Saved ✓"
                if (index.intValue < lines().size - 1) index.intValue++
            }
        }
    }

    private fun play() {
        val f = recorder.file(hindi.value, index.intValue)
        if (!f.exists()) return
        player?.release()
        player = MediaPlayer().apply { setDataSource(f.path); prepare(); start() }
    }

    private fun send() {
        try {
            val uri = recorder.export()
            val share = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "Send your recordings (Google Drive is easiest)"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't pack the recordings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val v = version.intValue
        val lines = lines()
        val i = index.intValue
        val line = lines[i]
        val done = recorder.count(hindi.value)
        val total = recorder.count(false) + recorder.count(true)
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(20.dp),
        ) {
            Text("RECORD MY VOICE", style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
            Text(
                "Read each sentence in your normal voice, like you're talking to a friend. A quiet room, phone about a hand away. You can stop any time; it remembers.",
                style = MaterialTheme.typography.bodySmall, color = XarvisMuted,
            )
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = if (hindi.value) 1 else 0, containerColor = Color.Transparent, contentColor = XarvisCyan) {
                listOf(false, true).forEach { h ->
                    Tab(
                        selected = hindi.value == h,
                        enabled = !recording.value,
                        onClick = { hindi.value = h; index.intValue = firstMissing(h); message.value = "" },
                        text = { Text(if (h) "Hindi ${recorder.count(true)}/${VoiceScript.hindi.size}" else "English ${recorder.count(false)}/${VoiceScript.english.size}") },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Sentence ${i + 1} of ${lines.size}" + if (recorder.has(hindi.value, i)) "  ·  recorded ✓" else "",
                style = MaterialTheme.typography.labelMedium, color = XarvisMuted,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(line.show, fontSize = 26.sp, lineHeight = 34.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            }
            // Loudness bar while recording.
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(XarvisMuted.copy(alpha = 0.2f))) {
                Box(
                    Modifier.fillMaxWidth(level.floatValue.coerceIn(0f, 1f)).height(8.dp)
                        .background(if (level.floatValue > 0.97f) Color(0xFFFF5252) else XarvisCyan),
                )
            }
            Text(message.value, style = MaterialTheme.typography.bodyMedium, color = XarvisCyan, modifier = Modifier.padding(vertical = 8.dp).height(40.dp))
            Button(
                onClick = ::toggleRecord, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (recording.value) Color(0xFFFF5252) else XarvisCyan),
            ) { Text(if (recording.value) "■  STOP" else "●  RECORD", fontSize = 20.sp) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { index.intValue = (i - 1).coerceAtLeast(0); message.value = "" }, enabled = !recording.value && i > 0, modifier = Modifier.weight(1f)) { Text("◀ BACK") }
                OutlinedButton(onClick = ::play, enabled = !recording.value && recorder.has(hindi.value, i), modifier = Modifier.weight(1f)) { Text("▶ PLAY") }
                OutlinedButton(onClick = { index.intValue = (i + 1).coerceAtMost(lines.size - 1); message.value = "" }, enabled = !recording.value && i < lines.size - 1, modifier = Modifier.weight(1f)) { Text("SKIP ▶") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = ::send, enabled = !recording.value && total > 0, modifier = Modifier.fillMaxWidth()) {
                Text("SEND RECORDINGS ($total)")
            }
            if (done == lines.size) Text("All ${if (hindi.value) "Hindi" else "English"} sentences done. Brilliant, sir.", color = XarvisCyan, style = MaterialTheme.typography.bodySmall)
        }
    }
}
