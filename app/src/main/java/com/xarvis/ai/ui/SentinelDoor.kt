package com.xarvis.ai.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.sentinel.Gatekeeper
import com.xarvis.ai.sentinel.SentinelGate.Ui
import kotlinx.coroutines.delay

private val Blood = Color(0xFFFF2A2A)
private val Ash = Color(0xFFB9B9B9)

/** What the chat's Sentinel shows while the door is being watched (its eye scans, then greets). */
fun sentinelMood(ui: Ui): Int = when (ui) {
    Ui.Looking -> SentinelFace.LOOKING
    is Ui.Welcome, is Ui.Granted -> SentinelFace.OWNER
    is Ui.Challenge, is Ui.Locked -> SentinelFace.STRANGER
    else -> SentinelFace.CALM
}

/** The Sentinel's greeting for Rex: one quiet line low on the screen, never a bubble over the figure. */
@Composable
fun SentinelCaption(ui: Ui, modifier: Modifier) {
    val text = (ui as? Ui.Welcome)?.text ?: (ui as? Ui.Granted)?.text
    AnimatedVisibility(text != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text.orEmpty(), color = Color(0xFFE8E8E8), fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        )
    }
}

/**
 * The door itself: over everything while a stranger must give the code (or after they didn't). Nothing behind it can
 * be touched. The code can be said (it listens by itself) or typed on the keypad; Rex can use his fingerprint.
 */
@Composable
fun SentinelDoor() {
    val context = LocalContext.current
    val gate = remember { (context.applicationContext as XarvisApp).core.gate }
    val ui by gate.ui.collectAsState()
    if (ui !is Ui.Challenge && ui !is Ui.Locked) return
    var typed by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui is Ui.Challenge) { while (true) { now = System.currentTimeMillis(); delay(200) } }

    Box(
        Modifier.fillMaxSize().background(Color(0xF2000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}, // nothing behind is reachable
    ) {
        Sentinel(Modifier.fillMaxSize(), 1f, thinking = false, typing = typed.isNotEmpty(), mood = SentinelFace.STRANGER)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            when (val s = ui) {
                is Ui.Challenge -> {
                    val left = ((s.deadline - now) / 1000).coerceAtLeast(0)
                    Text(if (s.seen == Gatekeeper.Seen.UNSURE) "I CAN'T QUITE MAKE YOU OUT" else "HELLO, STRANGER",
                        color = Blood, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Are you authorised to use this device?", color = Ash, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Text(s.note, color = Ash, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("0:%02d".format(left), color = if (left <= 10) Blood else Color.White, fontSize = 34.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            s.listening && s.heard.isNotBlank() -> "🎙 \"${s.heard}\""
                            s.listening -> "🎙 Listening — say the code"
                            else -> "Tries left: ${s.triesLeft}"
                        },
                        color = Ash, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    CodeBoxes(typed)
                    Spacer(Modifier.height(10.dp))
                    Keypad(
                        onDigit = { d ->
                            if (typed.length < 4) typed += d
                            if (typed.length == 4) { gate.tried(typed); typed = "" }
                        },
                        onBack = { typed = typed.dropLast(1) },
                    )
                }
                is Ui.Locked -> {
                    Text("ACCESS DENIED", color = Blood, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (s.phoneLocked) "The phone is locked. Rex has been told." else "XARVIS stays closed. Rex has been told.",
                        color = Ash, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                }
                else -> {}
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "👆  I'm Rex — fingerprint",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(22.dp)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp))
                    .clickable { askFinger(context) { gate.ownerProved() } }.padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun CodeBoxes(typed: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { i ->
            Box(
                Modifier.size(44.dp, 52.dp).border(1.5.dp, if (i < typed.length) Blood else Color(0x55FFFFFF), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (i < typed.length) "●" else "", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onBack: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in rows) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for (k in r) Box(
                Modifier.size(58.dp).clip(CircleShape)
                    .then(if (k.isEmpty()) Modifier else Modifier.background(Color(0x22FFFFFF)).clickable { if (k == "⌫") onBack() else onDigit(k) }),
                contentAlignment = Alignment.Center,
            ) { Text(k, color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

/** Rex's fingerprint only (never the PIN: whoever holds the phone may have watched it typed). */
private fun askFinger(context: android.content.Context, ok: () -> Unit) {
    val activity = context as? android.app.Activity ?: return
    val b = BiometricPrompt.Builder(activity).setTitle("XARVIS Sentinel").setSubtitle("Your fingerprint, sir")
        .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    runCatching {
        b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = ok()
        })
    }
}
