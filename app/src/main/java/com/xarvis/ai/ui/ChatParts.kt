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
