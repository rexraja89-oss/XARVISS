package com.xarvis.ai.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The living XARVIS logo on screen while Rex talks to it by voice ("Hey Xarvis") — over the lock screen and with
 * the screen off too (Rex, 14 Nov 2026: "the same XARVIS logo animation as at launch… while XARVIS wakes and
 * communicates… even when screen off… more high quality, tuned and refined"). It changes with what XARVIS does:
 * listening (a slow breathing glow and ripples), thinking (the ring spins, arcs orbit), speaking (waves pulse out).
 * Rex's words and the answer show underneath. It closes itself when the conversation ends; a tap hides it.
 */
class WakeOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WakeOverlay.cancelNotice(this)
        val core = (application as XarvisApp).core
        setContent {
            val state by core.state.collectAsState()
            val live by core.voiceLive.collectAsState()
            // What XARVIS is doing, read a few times a second (the speech engine has no "speaking" event).
            var phase by remember { mutableIntStateOf(VoiceTalk.SPEAKING) }
            LaunchedEffect(Unit) {
                while (true) {
                    phase = when (core.voiceState()) {
                        VoiceTalk.THINKING -> VoiceTalk.THINKING
                        VoiceTalk.SPEAKING -> VoiceTalk.SPEAKING
                        else -> VoiceTalk.IDLE // listening
                    }
                    delay(120)
                }
            }
            // The conversation is over: let the last words settle, then go.
            LaunchedEffect(live.active) {
                if (!live.active) {
                    delay(1500)
                    finish()
                }
            }
            val reply = state.messages.lastOrNull()?.takeIf { !it.fromUser && live.heard.isNotBlank() }?.text.orEmpty()
            WakeScreen(phase, live.heard, Voice.speakable(reply), onTap = ::finish)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WakeOverlay.cancelNotice(this)
    }
}

@Composable
private fun WakeScreen(phase: Int, heard: String, reply: String, onTap: () -> Unit) {
    // One clock for every motion; how fast and how strong each effect is eases between phases.
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now -> time += (now - last) / 1e9f; last = now }
    }
    val thinking by animateFloatAsState(if (phase == VoiceTalk.THINKING) 1f else 0f, tween(500), label = "thinking")
    val speaking by animateFloatAsState(if (phase == VoiceTalk.SPEAKING) 1f else 0f, tween(400), label = "speaking")
    val listening by animateFloatAsState(if (phase == VoiceTalk.IDLE) 1f else 0f, tween(500), label = "listening")
    // The ring's angle accumulates, so a change of speed never makes it jump.
    var angle by remember { mutableFloatStateOf(0f) }
    var lastTime by remember { mutableFloatStateOf(0f) }
    val dt = (time - lastTime).coerceIn(0f, 0.1f)
    lastTime = time
    angle += dt * (18f + 160f * thinking + 40f * speaking)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF071A2B), Color(0xFF02050A)), radius = 1400f))
            .clickable(onClick = onTap),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(340.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { aura(time, listening, thinking, speaking) }
                Logo(size = 220.dp, angle = angle, breathe = 1f + 0.035f * sin(time * (2.2f + 3f * speaking)) + 0.05f * speaking * pulse(time))
                Canvas(Modifier.size(260.dp)) { strands(time, 0.45f + 0.55f * maxOf(thinking, speaking), 1f + 1.6f * thinking) }
                Canvas(Modifier.fillMaxSize()) { orbits(time, thinking) }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when (phase) {
                    VoiceTalk.THINKING -> "Thinking…"
                    VoiceTalk.SPEAKING -> "XARVIS"
                    else -> "Listening…"
                },
                color = Color(0xFF7FEFFF), fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp,
            )
            if (heard.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("“$heard”", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
            if (reply.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    reply, color = Color(0xFFB9D4E6), fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** The launch logo: the core and its particle ring (the app's own artwork), turning and breathing. */
@Composable
private fun Logo(size: Dp, angle: Float, breathe: Float) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_xarvis_core), contentDescription = "XARVIS", modifier = Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.ic_xarvis_ring), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = angle; scaleX = breathe; scaleY = breathe },
        )
    }
}

/** A smooth beat for speech, like syllables: a few sines that never quite repeat. */
private fun pulse(t: Float) = (0.5f + 0.25f * sin(t * 9.1f) + 0.15f * sin(t * 13.7f + 1f) + 0.1f * sin(t * 5.3f + 2f)).coerceIn(0f, 1f)

private val CYAN = Color(0xFF17E0FF)
private val BLUE = Color(0xFF3FA9FF)
private val ICE = Color(0xFFB7F4FF)

/** The glow behind the logo, ripples while listening, and waves pushed out while speaking. */
private fun DrawScope.aura(t: Float, listening: Float, thinking: Float, speaking: Float) {
    val r = size.minDimension / 2
    val glow = 0.30f + 0.12f * sin(t * 1.6f) + 0.25f * speaking * pulse(t) + 0.15f * thinking
    drawCircle(Brush.radialGradient(listOf(CYAN.copy(alpha = glow), Color.Transparent), center, r), r, center)
    // Listening: slow rings that drift out and fade, two at a time.
    if (listening > 0.01f) for (k in 0 until 3) {
        val p = ((t * 0.35f + k / 3f) % 1f)
        drawCircle(CYAN, radius = r * (0.66f + 0.34f * p), center = center, alpha = listening * 0.35f * (1 - p), style = Stroke(r * 0.008f))
    }
    // Speaking: quicker waves, brighter on each "syllable".
    if (speaking > 0.01f) for (k in 0 until 4) {
        val p = ((t * 0.9f + k / 4f) % 1f)
        drawCircle(ICE, radius = r * (0.64f + 0.36f * p), center = center, alpha = speaking * (0.25f + 0.35f * pulse(t)) * (1 - p),
            style = Stroke(r * (0.004f + 0.012f * (1 - p))), blendMode = BlendMode.Plus)
    }
}

/** Thinking: three bright arcs orbiting the ring at different speeds and directions. */
private fun DrawScope.orbits(t: Float, thinking: Float) {
    if (thinking < 0.01f) return
    val r = size.minDimension / 2 * 0.86f
    val box = Size(r * 2, r * 2)
    val tl = Offset(center.x - r, center.y - r)
    listOf(Triple(110f, 70f, CYAN), Triple(-80f, 45f, BLUE), Triple(150f, 25f, ICE)).forEachIndexed { i, (speed, sweep, c) ->
        val start = (t * speed + i * 120f) % 360f
        val inset = i * r * 0.05f
        drawArc(c, start, sweep, false, Offset(tl.x + inset, tl.y + inset), Size(box.width - 2 * inset, box.height - 2 * inset),
            alpha = thinking * 0.85f, style = Stroke(r * 0.018f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    }
}

/** Strands of particles twisting around the ring (the chat avatar's, denser and smoother for a big screen). */
private fun DrawScope.strands(t: Float, energy: Float, speed: Float) {
    val r = size.minDimension / 2
    val colors = listOf(Color(0xFF8FF3FF), BLUE, ICE)
    val tt = t * speed
    for (k in 0 until 3) {
        val phase = k * 2.094f
        val spin = (0.35f + 0.2f * k) * if (k % 2 == 0) 1f else -1f
        for (i in 0 until 96) {
            val a = i * 2f * PI.toFloat() / 96
            val wobble = 0.06f * sin(5 * a + phase + tt * 2.4f) + 0.03f * sin(9 * a - tt * 3.6f + k) + 0.015f * sin(13 * a + tt * 5.9f)
            val rr = r * (0.84f + wobble)
            val ang = a + tt * spin
            val p = Offset(center.x + rr * cos(ang), center.y + rr * sin(ang))
            val twinkle = 0.5f + 0.5f * sin(t * 5f + i * 1.7f + k * 2.3f)
            val alpha = (0.2f + 0.8f * twinkle) * energy
            val dot = r * (0.009f + 0.008f * twinkle)
            drawCircle(colors[k], radius = dot * 3f, center = p, alpha = alpha * 0.18f, blendMode = BlendMode.Plus)
            drawCircle(colors[k], radius = dot, center = p, alpha = alpha, blendMode = BlendMode.Plus)
        }
    }
}

/**
 * Shows [WakeOverlayActivity]. Android lets XARVIS open a screen from the background when XARVIS Hands is on (S22);
 * otherwise a full-screen notice does it — it opens the screen when the phone is locked or the screen is off (S22
 * build only; on the benco, XARVIS Ears posts that notice).
 */
object WakeOverlay {
    private const val CHANNEL = "xarvis_wake_screen"
    const val NOTICE_ID = 77

    fun show(context: Context) {
        val intent = Intent(context, WakeOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { context.startActivity(intent) }
        // The benco's build doesn't declare the full-screen permission (there XARVIS Ears posts the notice instead).
        if (!declared(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "XARVIS talking", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens XARVIS's screen when you say \"Hey Xarvis\""
            setSound(null, null)
            enableVibration(false)
        })
        val pi = PendingIntent.getActivity(context, 77, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("XARVIS")
            .setContentText("Listening…")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { nm.notify(NOTICE_ID, n) }
    }

    fun cancelNotice(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) }
    }

    private fun declared(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.USE_FULL_SCREEN_INTENT)
    }.getOrDefault(false)
}
