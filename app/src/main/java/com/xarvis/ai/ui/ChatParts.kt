package com.xarvis.ai.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xarvis.ai.R
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import com.xarvis.ai.memory.ChatSummary
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import com.xarvis.ai.llm.ModelDownload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * XARVIS's picture beside its messages: the app icon, whose particle ring comes alive while
 * XARVIS is thinking (the ring turns and breathes, and strands of particles twist and change
 * shape around it) and stands still where it stopped once the reply is done.
 */
@Composable
fun XarvisAvatar(thinking: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    // Time only moves while thinking, so everything freezes in place when the reply is finished.
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(thinking) {
        if (!thinking) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    Box(modifier.size(size).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_xarvis_core), contentDescription = "XARVIS", modifier = Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.ic_xarvis_ring), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                rotationZ = time * 45f
                val breathe = 1f + 0.04f * sin(time * 4.3f)
                scaleX = breathe
                scaleY = breathe
            },
        )
        Canvas(Modifier.fillMaxSize()) { particles(time, thinking) }
    }
}

/** Three strands of particles around the ring, each wobbling to its own rhythm and turning its own way. */
private fun DrawScope.particles(t: Float, active: Boolean) {
    val r = size.minDimension / 2
    val colors = listOf(Color(0xFF8FF3FF), Color(0xFF3FA9FF), Color(0xFFB7E4FF))
    for (k in 0 until STRANDS) {
        val phase = k * 2.094f
        val spin = (0.55f + 0.3f * k) * if (k % 2 == 0) 1f else -1f
        for (i in 0 until PARTICLES) {
            val a = i * 2f * PI.toFloat() / PARTICLES
            val wobble = 0.075f * sin(5 * a + phase + t * 3.1f) + 0.035f * sin(9 * a - t * 4.7f + k) +
                0.02f * sin(13 * a + t * 7.3f)
            val rr = r * (0.80f + wobble)
            val angle = a + t * spin
            val p = Offset(center.x + rr * cos(angle), center.y + rr * sin(angle))
            val twinkle = 0.5f + 0.5f * sin(t * 6f + i * 1.7f + k * 2.3f)
            val alpha = if (active) 0.35f + 0.65f * twinkle else 0.22f
            val dot = r * (0.016f + 0.014f * twinkle)
            drawCircle(colors[k], radius = dot * 2.6f, center = p, alpha = alpha * 0.22f, blendMode = BlendMode.Plus)
            drawCircle(colors[k], radius = dot, center = p, alpha = alpha, blendMode = BlendMode.Plus)
        }
    }
}

private const val STRANDS = 3
private const val PARTICLES = 56

/** Rex's picture beside his messages. */
@Composable
fun UserAvatar(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Image(
        painterResource(R.drawable.ic_avatar_rex), contentDescription = "You",
        modifier = modifier.size(size).clip(CircleShape).background(Color.Black)
            .border(1.dp, XarvisCyan.copy(alpha = 0.5f), CircleShape),
    )
}

/** The round "+" by the text box that opens [AttachSheet]. */
@Composable
fun PlusButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .border(1.5.dp, if (enabled) XarvisCyan else XarvisMuted, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 28.sp, color = if (enabled) XarvisCyan else XarvisMuted)
    }
}

/** One thing that can be added to a message. New capabilities go in the list in [AttachSheet]. */
class AttachOption(@DrawableRes val icon: Int, val title: String, val detail: String, val onPick: () -> Unit)

/** The pull-up menu from "+": photo, file, and whatever comes next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(options: List<AttachOption>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text(
            "Add to your message", style = MaterialTheme.typography.titleMedium, color = XarvisCyan,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        onDismiss()
                        option.onPick()
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(option.icon), contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(option.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(option.detail, style = MaterialTheme.typography.bodySmall, color = XarvisMuted)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** The ☰ menu: "New chat", then every past chat (newest first); tapping one reopens it. */
@Composable
fun ChatList(
    chats: List<ChatSummary>, current: String, enabled: Boolean,
    wakeWord: Boolean, onWakeWord: (Boolean) -> Unit, onAssistantSettings: () -> Unit, onRecordVoice: () -> Unit,
    onLoadVoice: () -> Unit, ownVoices: () -> String?, ownVoiceOn: () -> Boolean, onOwnVoice: (Boolean) -> Unit,
    smartBrain: Boolean, smartDownload: ModelDownload, hasModel: Boolean, onSmartBrain: (Boolean) -> Unit,
    voiceLabel: (Boolean) -> String, onNextVoice: (Boolean) -> String,
    onOpen: (String?) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        // The brain only matters on a phone that runs one itself (not the benco).
        if (hasModel || smartBrain) {
            Text(
                "BRAIN", style = MaterialTheme.typography.titleMedium, color = XarvisCyan,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            BrainOption("Fast", "Gemma 4 E2B: quick answers.", selected = !smartBrain, enabled = enabled) { onSmartBrain(false) }
            val detail = when (smartDownload) {
                is ModelDownload.Running -> {
                    val pct = if (smartDownload.total > 0) (smartDownload.done * 100 / smartDownload.total).toInt() else 0
                    "Downloading… $pct%. XARVIS switches when it's done."
                }
                is ModelDownload.Waiting -> "Download paused: ${smartDownload.why}."
                is ModelDownload.Failed -> "Download failed: ${smartDownload.why}. Tap to try again."
                else -> "Gemma 4 E4B: about twice as smart, replies about half as fast. First time: a ~4 GB download."
            }
            BrainOption("Smart", detail, selected = smartBrain, enabled = enabled) { onSmartBrain(true) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = XarvisMuted.copy(alpha = 0.3f))
        }
        Text(
            "VOICE", style = MaterialTheme.typography.titleMedium, color = XarvisCyan,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable { onWakeWord(!wakeWord) }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("\"Hey Jarvis\"", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Say \"Hey Jarvis\" (with a J), wait for the beep, then speak. Works with the screen off; after a phone restart, open XARVIS once. Uses some battery.",
                    style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                )
            }
            Switch(checked = wakeWord, onCheckedChange = onWakeWord)
        }
        // Pick XARVIS's voices by ear: each tap plays the next one.
        var english by remember { mutableStateOf(voiceLabel(false)) }
        var hindi by remember { mutableStateOf(voiceLabel(true)) }
        // The menu is drawn before the phone's speech engine has started: read the labels again once it has.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            repeat(10) {
                kotlinx.coroutines.delay(2000)
                english = voiceLabel(false)
                hindi = voiceLabel(true)
            }
        }
        VoiceButton("English voice", english) { english = onNextVoice(false) }
        VoiceButton("Hindi voice", hindi) { hindi = onNextVoice(true) }
        // Rex reads sentences aloud so XARVIS can learn to speak in his voice.
        val ownVoice = remember { com.xarvis.ai.voice.OwnVoice.status }
        Column(Modifier.fillMaxWidth().clickable(onClick = onRecordVoice).padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text("Record my voice ›", style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
            Text(
                "Read sentences aloud so XARVIS can learn to speak like you. Voice engine: $ownVoice",
                style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
            )
        }
        Column(Modifier.fillMaxWidth().clickable(onClick = onLoadVoice).padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text("Load my voice ›", style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
            Text(
                "Pick xarvis-voice-hi.zip or xarvis-voice-en.zip from the training page (Google Drive → XARVIS voice).",
                style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
            )
        }
        val loaded = ownVoices()
        if (loaded != null) {
            var mine by remember { mutableStateOf(ownVoiceOn()) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speak in my voice", style = MaterialTheme.typography.bodyLarge)
                    Text("Loaded: $loaded. Off = the phone's voices.", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
                }
                Switch(checked = mine, onCheckedChange = { mine = it; onOwnVoice(it) })
            }
        }
        Text(
            "Make XARVIS the phone's assistant ›", style = MaterialTheme.typography.bodyMedium, color = XarvisCyan,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAssistantSettings).padding(horizontal = 20.dp, vertical = 10.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = XarvisMuted.copy(alpha = 0.3f))
        Text(
            "CHATS", style = MaterialTheme.typography.titleMedium, color = XarvisCyan,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        Button(
            onClick = { onOpen(null) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("+  NEW CHAT") }
        Spacer(Modifier.height(12.dp))
        if (chats.isEmpty()) {
            Text(
                "Your chats will appear here.", style = MaterialTheme.typography.bodyMedium, color = XarvisMuted,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(chats, key = { it.id }) { chat ->
                val selected = chat.id == current
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) XarvisCyan.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = enabled) { onOpen(chat.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        chat.title, style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) XarvisCyan else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        CHAT_TIME.format(Date(chat.lastTime)) + " · ${chat.exchanges} message" + if (chat.exchanges == 1) "" else "s",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                    )
                }
            }
        }
    }
}

private val CHAT_TIME = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

@Composable
private fun VoiceButton(title: String, label: String, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onNext).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("$label · tap to hear the next one", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        }
        Text("▶", color = XarvisCyan, fontSize = 20.sp)
    }
}

@Composable
private fun BrainOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        }
    }
}

/**
 * The round black mic (Rex's picture). It lights up, with a pulsing cyan glow, only while it's
 * listening, so it's clear when the phone is hearing him.
 */
@Composable
fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val glow = if (listening) {
        val pulse = rememberInfiniteTransition(label = "mic")
        pulse.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow",
        ).value
    } else {
        0f
    }
    Box(
        Modifier.size(56.dp)
            .drawBehind {
                if (glow > 0f) {
                    drawCircle(XarvisCyan, radius = size.minDimension / 2 + 6.dp.toPx(), alpha = 0.25f * glow)
                    drawCircle(XarvisCyan, radius = size.minDimension / 2 + 2.dp.toPx(), alpha = 0.6f * glow)
                }
            }
            .clip(CircleShape)
            .background(Color.Black)
            .border(1.5.dp, if (listening) XarvisCyan else XarvisMuted.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_mic), contentDescription = "Speak to XARVIS",
            modifier = Modifier.fillMaxSize(),
            alpha = if (enabled) 1f else 0.4f,
        )
    }
}
