package com.xarvis.ai.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sin
import kotlin.random.Random

/**
 * The welcome effect: Matrix-style 0s and 1s raining down behind a new chat, brightening as they
 * fall and fading out just above the command bar, with a slow green-to-cyan shimmer, drifting
 * glow orbs and a soft light beam. Drawn behind the chat bubbles; the screen fades it out and
 * removes it once Rex sends his first command, so it costs nothing after that.
 */
@Composable
fun CodeRain(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val rain = remember { Rain() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                rain.step(((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
                last = now
                frame = now
            }
        }
    }
    Canvas(modifier) {
        if (frame >= 0) rain.draw(this, alpha) // reading frame redraws every frame
    }
}

private class Rain {
    private class Drop(var x: Float, var y: Float, var speed: Float, var length: Int, val glyphs: CharArray)
    private class Orb(var x: Float, var y: Float, var speed: Float, var radius: Float, var phase: Float)

    private val random = Random(System.nanoTime())
    private val drops = mutableListOf<Drop>()
    private val orbs = mutableListOf<Orb>()
    private var width = 0f
    private var height = 0f
    private var cell = 0f
    private var time = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private fun glyph() = if (random.nextInt(10) < 5) '0' else '1'

    private fun setUp(w: Float, h: Float, density: Float) {
        width = w
        height = h
        cell = 15f * density
        paint.textSize = 13f * density
        drops.clear()
        var x = cell / 2
        while (x < w) {
            // A few columns carry two drops, for a denser, more layered look.
            repeat(if (random.nextInt(4) == 0) 2 else 1) { drops += newDrop(x, initial = true) }
            x += cell
        }
        orbs.clear()
        repeat(9) { orbs += newOrb(initial = true) }
    }

    private fun newDrop(x: Float, initial: Boolean): Drop {
        val length = 6 + random.nextInt(18)
        return Drop(
            x = x,
            y = if (initial) random.nextFloat() * height * 1.2f - height * 0.4f else -random.nextFloat() * height * 0.5f,
            speed = cell * (5f + random.nextFloat() * 11f),
            length = length,
            glyphs = CharArray(length) { glyph() },
        )
    }

    private fun newOrb(initial: Boolean) = Orb(
        x = random.nextFloat() * width,
        y = if (initial) random.nextFloat() * height else -cell * 3,
        speed = cell * (1.5f + random.nextFloat() * 3f),
        radius = cell * (0.15f + random.nextFloat() * 0.35f),
        phase = random.nextFloat() * 6.28f,
    )

    fun step(dt: Float) {
        if (width == 0f) return
        time += dt
        for (i in drops.indices) {
            val d = drops[i]
            d.y += d.speed * dt
            // Digits flicker as they fall.
            if (random.nextInt(12) == 0) d.glyphs[random.nextInt(d.length)] = glyph()
            if (d.y - d.length * cell > height) drops[i] = newDrop(d.x, initial = false)
        }
        for (i in orbs.indices) {
            val o = orbs[i]
            o.y += o.speed * dt
            if (o.y > height + o.radius * 4) orbs[i] = newOrb(initial = false)
        }
    }

    fun draw(scope: DrawScope, alpha: Float) = with(scope) {
        if (size.width != width || size.height != height) setUp(size.width, size.height, density)
        if (alpha <= 0f) return@with
        // Green <-> cyan shimmer.
        val shimmer = (sin(time * 0.6f) + 1f) / 2f
        val base = lerp(Color(0xFF16FF6A), Color(0xFF12E6FF), shimmer)
        val head = Color(0xFFE9FFF4)

        // A soft beam of light down the middle, breathing slowly.
        val beam = 0.10f + 0.06f * sin(time * 1.3f)
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.5f to base.copy(alpha = beam * alpha), 1f to Color.Transparent,
                startX = width * 0.3f, endX = width * 0.7f,
            ),
        )

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (d in drops) {
                for (k in 0 until d.length) {
                    val y = d.y - k * cell
                    if (y < -cell || y > height) continue
                    // Dim at the top, brightest two-thirds down, gone at the command bar.
                    val depth = (y / height).coerceIn(0f, 1f)
                    val fall = if (depth < 0.7f) 0.35f + 0.65f * (depth / 0.7f) else 1f - (depth - 0.7f) / 0.3f
                    val tail = 1f - k.toFloat() / d.length
                    val a = (fall * tail * alpha).coerceIn(0f, 1f)
                    if (a < 0.02f) continue
                    paint.color = (if (k == 0) head else base).copy(alpha = if (k == 0) a else a * 0.85f).toArgb()
                    if (k == 0) paint.setShadowLayer(cell * 0.6f, 0f, 0f, base.copy(alpha = a).toArgb())
                    native.drawText(d.glyphs[k].toString(), d.x - cell * 0.3f, y, paint)
                    if (k == 0) paint.clearShadowLayer()
                }
            }
        }

        // Glowing orbs drifting down, twinkling.
        for (o in orbs) {
            val depth = (o.y / height).coerceIn(0f, 1f)
            val fade = if (depth < 0.8f) 1f else 1f - (depth - 0.8f) / 0.2f
            val twinkle = 0.55f + 0.45f * sin(time * 2.2f + o.phase)
            val a = (0.55f * twinkle * fade * alpha).coerceIn(0f, 1f)
            val c = lerp(Color(0xFFB8FF3C), base, 0.5f)
            drawCircle(c.copy(alpha = a * 0.25f), radius = o.radius * 4f, center = Offset(o.x, o.y))
            drawCircle(c.copy(alpha = a), radius = o.radius, center = Offset(o.x, o.y))
        }
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}
