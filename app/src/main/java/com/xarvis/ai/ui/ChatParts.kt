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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
        Modifier.size(InputHeight).clip(InputShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, if (enabled) XarvisCyan else XarvisMuted, InputShape)
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

/** The ☰ menu (left): brain, voice and control settings. Chats are in the right-hand menu ([ChatSessions]). */
@Composable
fun SettingsMenu(
    enabled: Boolean,
    wakeWord: Boolean, onWakeWord: (Boolean) -> Unit, wakeHere: Boolean = true, wakeNote: String = "", onWakeScreen: () -> Unit = {},
    onAssistantSettings: () -> Unit, onRecordVoice: () -> Unit,
    onLoadVoice: () -> Unit, ownVoices: () -> String?, ownVoiceOn: () -> Boolean, onOwnVoice: (Boolean) -> Unit,
    onPermissions: () -> Unit, onActivityLog: () -> Unit, onFolders: () -> Unit, onXarvisCode: () -> Unit, onGameMod: () -> Unit,
    onHelp: () -> Unit, onBriefing: () -> Unit, onEyes: () -> Unit, onHands: () -> Unit, onRemote: () -> Unit = {}, onGuardRemote: () -> Unit = {}, onJobHunter: () -> Unit = {}, onRelay: () -> Unit = {}, onHelper: () -> Unit = {}, onTask: () -> Unit = {}, onAware: () -> Unit = {},
    smartBrain: Boolean, smartDownload: ModelDownload, hasModel: Boolean, onSmartBrain: (Boolean) -> Unit,
    modelDownload: ModelDownload, onDownloadModel: () -> Unit,
    cloudEnabled: Boolean, hasCloudKey: () -> Boolean, onCloud: (Boolean) -> Unit, onSaveKey: (String) -> Unit, onClearKey: () -> Unit,
    councilEnabled: Boolean, onCouncil: (Boolean) -> Unit,
    cloudMemory: Boolean, onCloudMemory: (Boolean) -> Unit,
    backups: () -> List<com.xarvis.ai.llm.BrainInfo>, onSaveBackup: (String, String) -> Unit, onClearBackup: (String) -> Unit,
    searchKeyHas: (String) -> Boolean = { false }, onSaveSearchKey: (String, String) -> Unit = { _, _ -> },
    onClearSearchKey: (String) -> Unit = {},
    voiceLabel: (Boolean) -> String, onNextVoice: (Boolean) -> String,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        // Rex, 14 Nov 2026: "it's very much crowded… hide the menu inside each header". So the drawer shows only a
        // few headers; tapping one opens a pop-up with that section's options.
        var open by remember { mutableStateOf<String?>(null) }
        // A row that opens another screen: the pop-up closes first.
        @Composable
        fun Go(title: String, detail: String, onClick: () -> Unit) = MenuLink(title, detail) { open = null; onClick() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
            Text(
                "XARVIS", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            MenuHeader("✦", "What can I do?", "Everything XARVIS can do, in plain words.", onClick = onHelp)
            MenuHeader("🛠", "Tools", "Briefing, Eyes, Hands, Task mode, Job Hunter") { open = "tools" }
            MenuHeader("📱", "My devices", "Control, guard and reach your other phones; Situation") { open = "devices" }
            MenuHeader("🧠", "Brain", "Fast or Smart, cloud brains and their keys") { open = "brain" }
            MenuHeader("🎙", "Voice", "Say \"Xarvis\", voices, your own voice") { open = "voice" }
            MenuHeader("⚙", "Control", "XARVIS Code, Mod, folders, permissions, log") { open = "control" }
        }
        when (open) {
            "tools" -> MenuPopup("🛠 Tools", onClose = { open = null }) {
                Go("🌅 Morning briefing ›", "Connect Gmail + Calendar for a daily briefing.", onBriefing)
                Go("👁 XARVIS Eyes ›", "Point the camera and ask what I see. Or just say \"look\".", onEyes)
                Go("🤲 XARVIS Hands ›", "Lets me tap inside apps, like an imo or WhatsApp call. Switch it on here.", onHands)
                Go("🧠 Task mode ›", "Big jobs on this phone, planned and done by XARVIS: YouTube videos, comics… (say \"task: …\").", onTask)
                Go("💼 Job Hunter ›", "Your worldwide job search: drafts to approve, sent emails, replies, your CVs.", onJobHunter)
            }
            "devices" -> MenuPopup("📱 My devices", onClose = { open = null }) {
                Go("🛰 Situation ›", "All your devices at a glance: battery, network, who's using them, missed calls. Say \"status report\".", onAware)
                Go("🖥 Control linked phone ›", "See and control your other phone's screen, like TeamViewer.", onRemote)
                Go("🛡 Guard my other phone ›", "Its intruder alarm: speak through it, stop it, or sound it now.", onGuardRemote)
                Go("🌐 Always connected ›", "Reach your other phones anywhere — mobile data, another country — without Tailscale or a VPN.", onRelay)
                Go("🤝 Help a family phone ›", "See and fix a family member's phone from anywhere, when they ask (XARVIS Helper).", onHelper)
            }
            "brain" -> MenuPopup("🧠 Brain", onClose = { open = null }) {
                // Fast/Smart only matter on a phone that runs Gemma itself (the S22, not the benco).
                if (hasModel || smartBrain) {
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
                }
                // No on-device model here (e.g. the benco): the download lives in the menu so it doesn't
                // cover the chat. It's optional — the cloud brains and the linked phone already answer.
                if (!hasModel) {
                    Text(
                        "On-device AI (Gemma) — optional",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    )
                    Text(
                        "For offline and fully-private answers on this phone. You already have the cloud brains and your linked phone, so this isn't required.",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        com.xarvis.ai.ui.ModelSetup(modelDownload, onDownloadModel)
                    }
                }
                // Cloud works on any phone with internet (including the benco), so it's always shown.
                CloudBrain(cloudEnabled, hasCloudKey, onCloud, onSaveKey, onClearKey, councilEnabled, onCouncil, cloudMemory, onCloudMemory)
                if (cloudEnabled) BackupBrains(backups, onSaveBackup, onClearBackup)
                JobHunterKeys(searchKeyHas, onSaveSearchKey, onClearSearchKey)
            }
            "voice" -> MenuPopup("🎙 Voice", onClose = { open = null }) {
                // "Xarvis", the wake word. On the S22 XARVIS listens itself (the switch); on a phone whose XARVIS may
                // not use the microphone (the benco) the small XARVIS Ears app does, and the row opens it.
                Row(
                    Modifier.fillMaxWidth().clickable { if (wakeHere) onWakeWord(!wakeWord) else onWakeScreen() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Say \"Xarvis\"", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (wakeHere) "Say \"Xarvis\" any time, screen on or off, wait for \"Yes, sir?\", then talk. It keeps listening after each answer. Uses some battery."
                            else wakeNote,
                            style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                        )
                    }
                    if (wakeHere) Switch(checked = wakeWord, onCheckedChange = onWakeWord)
                    else Text("›", style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
                }
                if (wakeHere) Text(
                    "Practice and settings ›", style = MaterialTheme.typography.labelLarge, color = XarvisCyan,
                    modifier = Modifier.fillMaxWidth().clickable { open = null; onWakeScreen() }.padding(horizontal = 20.dp, vertical = 6.dp),
                )
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
                // The benco build has no voice engine, so these only show where it can work.
                if (!ownVoice.startsWith("not in")) {
                Column(Modifier.fillMaxWidth().clickable { open = null; onRecordVoice() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text("Record my voice ›", style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
                    Text(
                        "Read sentences aloud so XARVIS can learn to speak like you. Voice engine: $ownVoice",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                    )
                }
                Column(Modifier.fillMaxWidth().clickable { open = null; onLoadVoice() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text("Load my voice ›", style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
                    Text(
                        "Pick xarvis-voice-hi.zip or xarvis-voice-en.zip from the training page (Google Drive → XARVIS voice).",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                    )
                }
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
                    modifier = Modifier.fillMaxWidth().clickable { open = null; onAssistantSettings() }.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            "control" -> MenuPopup("⚙ Control", onClose = { open = null }) {
                Go("XARVIS Code ›", "Build separate projects with AI — your XARVIS is never changed.", onXarvisCode)
                Go("XARVIS Mod ›", "Hand me an offline game's file (OBB/APK/ZIP) — I open, edit and rebuild it in the cloud.", onGameMod)
                Go("Search folders ›", "Folders XARVIS may search when you ask \"search my phone for …\".", onFolders)
                Go("Permissions ›", "Choose what XARVIS may do by itself, what it asks first, and what's off.", onPermissions)
                Go("Activity log ›", "Everything XARVIS did for you, newest first.", onActivityLog)
            }
        }
    }
}

/** The right-hand menu (swipe from the right edge, or the 💬 button): New chat, then every past chat, newest first. */
@Composable
fun ChatSessions(chats: List<ChatSummary>, current: String, enabled: Boolean, onOpen: (String?) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
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
        Modifier.size(InputHeight)
            .drawBehind {
                // Glows only while listening.
                if (glow > 0f) {
                    fun ring(grow: Float, alpha: Float) = drawRoundRect(
                        XarvisCyan, topLeft = Offset(-grow, -grow), size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(12.dp.toPx() + grow), alpha = alpha,
                    )
                    ring(6.dp.toPx(), 0.25f * glow)
                    ring(2.dp.toPx(), 0.6f * glow)
                }
            }
            .clip(InputShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, if (listening || enabled) XarvisCyan else XarvisMuted, InputShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_mic), contentDescription = "Speak to XARVIS",
            modifier = Modifier.fillMaxSize().padding(6.dp).clip(CircleShape),
            alpha = if (enabled) 1f else 0.4f,
        )
    }
}

/** "XARVIS wants to: Call Atiq" with ALLOW ONCE / ALWAYS / NO, for actions set to "Ask me". */
@Composable
fun AskCard(ask: com.xarvis.ai.PendingAsk, onAnswer: (com.xarvis.ai.policy.Answer) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(XarvisCyan.copy(alpha = 0.12f)).padding(12.dp),
    ) {
        Text("XARVIS wants to:", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        Text(ask.action, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("${ask.category.title} is set to \"Ask me\".", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(com.xarvis.ai.policy.Answer.ONCE) }, modifier = Modifier.weight(1f)) { Text("ALLOW") }
            OutlinedButton(onClick = { onAnswer(com.xarvis.ai.policy.Answer.ALWAYS) }, modifier = Modifier.weight(1f)) { Text("ALWAYS") }
            OutlinedButton(onClick = { onAnswer(com.xarvis.ai.policy.Answer.NO) }, modifier = Modifier.weight(1f)) { Text("NO") }
        }
    }
}

/** ☰ → BRAIN: turn on the Gemini cloud brain and store the key. Private things still stay on the phone. */
@Composable
private fun CloudBrain(
    cloudEnabled: Boolean, hasCloudKey: () -> Boolean,
    onCloud: (Boolean) -> Unit, onSaveKey: (String) -> Unit, onClearKey: () -> Unit,
    councilEnabled: Boolean, onCouncil: (Boolean) -> Unit,
    cloudMemory: Boolean, onCloudMemory: (Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(cloudEnabled) }
    var hasKey by remember { mutableStateOf(hasCloudKey()) }
    var showKey by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var council by remember { mutableStateOf(councilEnabled) }
    var memory by remember { mutableStateOf(cloudMemory) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cloud brain (Gemini)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Much smarter for general questions. Needs internet and a free Google key. Private things and your files still stay on the phone; use the 🔒 in the header to force a private chat.",
                    style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    if (it && !hasKey) { showKey = true } else { enabled = it; onCloud(it) }
                },
            )
        }
        if (enabled || showKey) {
            if (!hasKey || showKey) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Paste your Gemini key (AIza…)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (key.isNotBlank()) {
                            onSaveKey(key.trim()); hasKey = true; key = ""; showKey = false; enabled = true; onCloud(true)
                        }
                    }) { Text("SAVE KEY") }
                    TextButton(onClick = { showKey = false; if (!hasKey) enabled = false }) { Text("Cancel") }
                }
                Text(
                    "Get a free key at aistudio.google.com → Get API key. No card needed.",
                    style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                )
            } else {
                Text("Key saved ✓", style = MaterialTheme.typography.labelSmall, color = XarvisCyan, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { onClearKey(); hasKey = false; enabled = false; council = false; onCouncil(false); onCloud(false) }) { Text("Remove key") }
            }
        }
        // Solo ↔ AI council (Rex asked for a switch he can flip anytime). Only useful with the cloud on.
        if (enabled && hasKey && !showKey) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = XarvisMuted.copy(alpha = 0.25f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (council) "AI council" else "Solo", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (council)
                            "A second AI reviews and improves each cloud answer before you see it. Better answers, but uses about twice your free quota. Flip off anytime."
                        else
                            "XARVIS answers with one brain. Turn on AI council to have a second AI review and improve each answer (uses about twice the free quota).",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                    )
                }
                Switch(checked = council, onCheckedChange = { council = it; onCouncil(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Remember me on the cloud", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The cloud brain uses the facts you saved with \"remember\" so it recalls them (like ChatGPT). Only those saved facts are shared — not your whole chat history. The 🔒 lock stops it anytime.",
                        style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                    )
                }
                Switch(checked = memory, onCheckedChange = { memory = it; onCloudMemory(it) })
            }
        }
    }
}

/**
 * ☰ → BRAIN → backup brains: extra free AIs XARVIS falls back to when Gemini's daily limit runs out,
 * in order, then Rex's OpenAI credit. Each key is stored encrypted and only sent to its provider.
 */
@Composable
private fun BackupBrains(
    backups: () -> List<com.xarvis.ai.llm.BrainInfo>,
    onSaveBackup: (String, String) -> Unit, onClearBackup: (String) -> Unit,
) {
    val list = remember { backups() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("Backup brains", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Used automatically, in order, when Gemini's free limit runs out. All optional — add the ones you want. With two or more brains, the AI council asks two different ones.",
            style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
        )
        list.forEach { ProviderKeyRow(it, onSaveBackup, onClearBackup) }
    }
}

/** ☰ → BRAIN: the Job Hunter's keys — Tavily (web search + reading job pages) and Jooble (job listings). */
@Composable
private fun JobHunterKeys(has: (String) -> Boolean, onSave: (String, String) -> Unit, onClear: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp), color = XarvisMuted.copy(alpha = 0.3f))
        Text("Job Hunter keys", style = MaterialTheme.typography.bodyLarge)
        Text(
            "For finding blasting & painting jobs worldwide. Both are free and need no card.",
            style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
        )
        NamedKey(
            id = com.xarvis.ai.llm.BrainKeys.TAVILY, title = "Tavily",
            help = "Web search + reading job pages. Free 1,000 credits a month: sign up at tavily.com (Google account works), copy the key that starts with tvly-.",
            hint = "Paste Tavily key (tvly-…)", has = has, onSave = onSave, onClear = onClear,
        )
        NamedKey(
            id = com.xarvis.ai.llm.BrainKeys.JOOBLE, title = "Jooble",
            help = "Job listings in many countries. Free: fill the form at jooble.org/api/about and the key comes by email.",
            hint = "Paste Jooble key", has = has, onSave = onSave, onClear = onClear,
        )
    }
}

/** One named key: what it's for, a paste field, or "Key saved ✓" with Remove. */
@Composable
private fun NamedKey(
    id: String, title: String, help: String, hint: String,
    has: (String) -> Boolean, onSave: (String, String) -> Unit, onClear: (String) -> Unit,
) {
    var hasKey by remember { mutableStateOf(has(id)) }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
        Text(help, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        if (hasKey) {
            Text("Key saved ✓", style = MaterialTheme.typography.labelSmall, color = XarvisCyan, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { onClear(id); hasKey = false }) { Text("Remove key") }
        } else {
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(hint) },
                singleLine = true,
            )
            Button(onClick = { if (key.isNotBlank()) { onSave(id, key.trim()); key = ""; hasKey = true } }) { Text("SAVE KEY") }
        }
    }
}

/** One backup brain: its name, a paste field to add a key, or "Key saved ✓" with Remove. */
@Composable
private fun ProviderKeyRow(
    brain: com.xarvis.ai.llm.BrainInfo,
    onSave: (String, String) -> Unit, onClear: (String) -> Unit,
) {
    var hasKey by remember { mutableStateOf(brain.hasKey) }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(brain.label, style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
        if (hasKey) {
            Text("Key saved ✓", style = MaterialTheme.typography.labelSmall, color = XarvisCyan)
            TextButton(onClick = { onClear(brain.id); hasKey = false }) { Text("Remove key") }
        } else {
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text("Paste ${brain.label} key (optional)") },
                singleLine = true,
            )
            Row {
                Button(onClick = { if (key.isNotBlank()) { onSave(brain.id, key.trim()); key = ""; hasKey = true } }) {
                    Text("SAVE")
                }
            }
            Text(brain.keyHint, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        }
    }
}

/** One header in the ☰ menu: an icon, a name and what's inside; tapping it opens its pop-up. */
@Composable
private fun MenuHeader(icon: String, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(XarvisCyan.copy(alpha = 0.07f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
    }
}

/** A row inside a pop-up that opens another screen. */
@Composable
private fun MenuLink(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
    }
}

/** A header's options, in a pop-up over the menu (scrolls when long). */
@Composable
private fun MenuPopup(title: String, onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, XarvisCyan.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = XarvisCyan, modifier = Modifier.weight(1f))
                    Text("✕", fontSize = 20.sp, color = XarvisMuted, modifier = Modifier.clickable(onClick = onClose).padding(12.dp))
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), content = content)
            }
        }
    }
}
