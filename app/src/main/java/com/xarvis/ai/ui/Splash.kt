package com.xarvis.ai.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.xarvis.ai.R

/**
 * The opening screen (Rex asked): XARVIS's logo comes alive the moment the app opens — the same
 * living ring that turns, breathes and trails particles while it thinks — large, glowing and crisp,
 * then fades into the chat. Reuses [XarvisAvatar] so it is vector-sharp and matches the chat avatar.
 */
@Composable
fun XarvisSplash() {
    val transition = rememberInfiniteTransition(label = "splash")
    // A gentle overall pulse ("vibrate"), on top of the avatar's own turning and breathing.
    val pulse by transition.animateFloat(
        initialValue = 0.97f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "pulse",
    )
    // A soft halo that swells behind the logo, for depth and an HD glow.
    val glow by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "glow",
    )
    Box(
        Modifier.fillMaxSize().background(colorResource(R.color.xarvis_background)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(300.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF17E0FF).copy(alpha = glow * 0.5f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        ),
                    )
                }
                .graphicsLayer { scaleX = pulse; scaleY = pulse },
            contentAlignment = Alignment.Center,
        ) {
            XarvisAvatar(thinking = true, size = 230.dp)
        }
    }
}
