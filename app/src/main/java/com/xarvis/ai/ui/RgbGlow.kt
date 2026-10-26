package com.xarvis.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A continuously running RGB "LED" glow around the edge of a box (the command prompt). A single bright
 * point of colour travels around the border leaving a fading trail, while the colour itself cycles
 * through the whole spectrum (red↔green↔blue, millions of mixes). Runs always, idle or not. Put it
 * before clip()/background() so the glow sits on the rounded edge.
 */
@Composable
fun Modifier.rgbGlow(corner: Dp, width: Dp = 3.dp): Modifier {
    val t = rememberInfiniteTransition(label = "rgb")
    val head by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "head")
    val hue by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "hue")
    return this.drawBehind {
        val n = 48
        val base = hue % 360f
        val colors = (0..n).map { i ->
            val pos = i.toFloat() / n
            val d = (head - pos + 1f) % 1f           // 0 at the moving head, grows going backward
            val a = (1f - d / 0.5f).coerceIn(0f, 1f)  // the trail fades over half the perimeter
            Color.hsv(base, 1f, 1f, a * a)            // one cycling colour; a*a sharpens the head
        }
        val brush = Brush.sweepGradient(colors)
        val cr = CornerRadius(corner.toPx(), corner.toPx())
        val w = width.toPx()
        // Layered halo — brighter at each ring, but the sharp bright core keeps its thin width.
        drawRoundRect(brush = brush, cornerRadius = cr, style = Stroke(width = w * 6f), alpha = 0.30f)
        drawRoundRect(brush = brush, cornerRadius = cr, style = Stroke(width = w * 3.5f), alpha = 0.55f)
        drawRoundRect(brush = brush, cornerRadius = cr, style = Stroke(width = w * 2f), alpha = 0.85f)
        drawRoundRect(brush = brush, cornerRadius = cr, style = Stroke(width = w)) // sharp bright core (unchanged width)
    }
}
