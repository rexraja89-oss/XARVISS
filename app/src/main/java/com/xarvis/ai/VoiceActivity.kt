package com.xarvis.ai

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xarvis.ai.service.WakeWord
import com.xarvis.ai.tools.PermissionGate
import com.xarvis.ai.ui.MicButton
import com.xarvis.ai.ui.XarvisAvatar
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import com.xarvis.ai.ui.theme.XarvisTheme
import com.xarvis.ai.voice.VoiceCommand
import kotlinx.coroutines.delay

/**
 * "XARVIS Voice": opens already listening, over the lock screen too. It's what the phone's
 * assistant button or gesture opens once XARVIS is the phone's assistant app, and it has its own
 * icon (for the S22's side-button "Open app"). Rex speaks, XARVIS answers aloud, and it closes.
 */
class VoiceActivity : ComponentActivity() {

    private val heard = mutableStateOf("")
    private val listening = mutableStateOf(false)
    private val asked = mutableStateOf(false)
    private var command: VoiceCommand? = null

    // Without the microphone permission (see the manifest), Google's recognizer listens for us.
    private val recognize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        heardCommand(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val core = (application as XarvisApp).core
        setContent {
            XarvisTheme {
                val state by core.state.collectAsState()
                val reply = state.messages.lastOrNull()?.takeIf { !it.fromUser && asked.value }?.text.orEmpty()
                val thinking = asked.value && state.isProcessing
                LaunchedEffect(asked.value, state.isProcessing) {
                    // Close a little after the answer has been said.
                    if (asked.value && !state.isProcessing) {
                        delay(1500)
                        while (core.busy()) delay(300)
                        delay(2500)
                        finish()
                    }
                }
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).clickable { finish() }.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (asked.value) XarvisAvatar(thinking, size = 72.dp)
                    else MicButton(listening = listening.value, enabled = true, onClick = ::listen)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        when {
                            heard.value.isNotBlank() -> "“${heard.value}”"
                            listening.value -> "Listening…"
                            else -> "Tap the mic to speak"
                        },
                        style = MaterialTheme.typography.titleMedium, color = XarvisCyan, textAlign = TextAlign.Center,
                    )
                    if (reply.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            reply, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center, modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("Tap anywhere to close", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
                }
            }
        }
        listen()
    }

    private fun listen() {
        if (listening.value) return
        heard.value = ""
        listening.value = true
        WakeWord.paused = true
        if (!PermissionGate.has(this, Manifest.permission.RECORD_AUDIO)) {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to XARVIS")
            try {
                recognize.launch(intent)
            } catch (e: Exception) {
                heardCommand(null)
            }
            return
        }
        command = VoiceCommand(this, onHearing = { heard.value = it }, onDone = { text ->
            command = null
            heardCommand(text)
        }).also { it.start() }
    }

    private fun heardCommand(text: String?) {
        listening.value = false
        WakeWord.paused = false
        if (text != null) {
            heard.value = text
            asked.value = true
            (application as XarvisApp).core.submit(text, spoken = true)
        }
    }

    override fun onDestroy() {
        command?.cancel()
        WakeWord.paused = false
        super.onDestroy()
    }
}
