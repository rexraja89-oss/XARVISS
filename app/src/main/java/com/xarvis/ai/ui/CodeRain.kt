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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The welcome effect: Matrix-style 0s and 1s raining down behind a new chat, brightening as they
 * fall and fading out just above the command bar, with a slow green-to-cyan shimmer, drifting
 * glow orbs and a soft light beam. Every [CodeRainTiming.CALM] seconds a face made of the digits
 * pushes out of the screen for [CodeRainTiming.FACE] seconds, as if through a stretched membrane,
 * while the rain scrambles, wobbles and glitches. Drawn behind the chat bubbles; the screen fades
 * it out and removes it once Rex sends his first command, so it costs nothing after that.
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

/** When the face appears: [CALM] seconds of plain rain, then [FACE] seconds of the face. */
internal object CodeRainTiming {
    const val CALM = 15f
    const val FACE = 3f

    /** Progress 0..1 through the face event at [time] seconds, or -1 when there's no face. */
    fun faceProgress(time: Float): Float {
        val t = time % (CALM + FACE)
        return if (t < CALM) -1f else (t - CALM) / FACE
    }

    /** How far the face is pushed out (0..1): rises, strains in pulses, then sinks back. */
    fun push(p: Float): Float {
        if (p < 0f || p > 1f) return 0f
        val rise = smooth(0f, 0.35f, p)
        val fall = 1f - smooth(0.72f, 1f, p)
        return rise * fall * (0.9f + 0.1f * sin(p * 45f))
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * A procedural face relief: height 0..1 at normalised head coordinates (u right, v down, -1..1
 * across the head). Raised forehead, brow, nose, cheekbones, lips and chin; sunken eye sockets and
 * mouth line; an egg-shaped outline. [shade] lights it from the upper left so the shape reads.
 */
internal object FaceRelief {
    private fun g(u: Float, v: Float, cu: Float, cv: Float, su: Float, sv: Float): Float {
        val du = (u - cu) / su
        val dv = (v - cv) / sv
        return exp(-(du * du + dv * dv) / 2f)
    }

    /** Squared distance from the centre of an egg-shaped head (narrow jaw); 1 at the outline. */
    private fun outline(u: Float, v: Float): Float {
        val w = if (v > 0f) 1f - 0.38f * v else 1f - 0.08f * -v
        val uu = u / w
        return uu * uu + v * v
    }

    fun height(u: Float, v: Float): Float {
        val r = outline(u, v)
        if (r >= 1f) return 0f
        val h = 0.50f * sqrt(1f - r) + detail(u, v) // the dome of the head, plus the features
        val edge = ((1f - r) / 0.15f).coerceIn(0f, 1f) // soft rim
        return (h * edge).coerceIn(0f, 1f)
    }

    /** The features alone (positive = raised, negative = sunken), used to carve them out with light. */
    fun detail(u: Float, v: Float): Float {
        var d = 0.20f * g(u, v, 0f, -0.30f, 0.50f, 0.09f) // brow ridge
        d -= 0.36f * g(u, v, -0.33f, -0.12f, 0.16f, 0.11f) // eye sockets
        d -= 0.36f * g(u, v, 0.33f, -0.12f, 0.16f, 0.11f)
        d += 0.10f * g(u, v, -0.33f, -0.11f, 0.06f, 0.045f) // eyeballs
        d += 0.10f * g(u, v, 0.33f, -0.11f, 0.06f, 0.045f)
        d += 0.34f * g(u, v, 0f, 0.08f, 0.07f, 0.20f) // nose ridge
        d += 0.16f * g(u, v, 0f, 0.27f, 0.11f, 0.07f) // nose tip
        d -= 0.10f * g(u, v, -0.09f, 0.31f, 0.04f, 0.03f) // nostrils
        d -= 0.10f * g(u, v, 0.09f, 0.31f, 0.04f, 0.03f)
        d += 0.12f * g(u, v, -0.42f, 0.14f, 0.15f, 0.11f) // cheekbones
        d += 0.12f * g(u, v, 0.42f, 0.14f, 0.15f, 0.11f)
        d += 0.11f * g(u, v, 0f, 0.47f, 0.22f, 0.05f) // upper lip
        d -= 0.14f * g(u, v, 0f, 0.53f, 0.25f, 0.025f) // mouth line
        d += 0.10f * g(u, v, 0f, 0.59f, 0.18f, 0.05f) // lower lip
        d += 0.12f * g(u, v, 0f, 0.82f, 0.20f, 0.10f) // chin
        d -= 0.08f * g(u, v, -0.55f, -0.30f, 0.12f, 0.18f) // temples
        d -= 0.08f * g(u, v, 0.55f, -0.30f, 0.12f, 0.18f)
        return d
    }

    /** Lambert lighting 0..1 from the upper left. */
    fun shade(u: Float, v: Float): Float {
        val e = 0.02f
        val dx = (height(u + e, v) - height(u - e, v)) / (2 * e)
        val dy = (height(u, v + e) - height(u, v - e)) / (2 * e)
        val nx = -dx * 0.9f
        val ny = -dy * 0.9f
        val len = sqrt(nx * nx + ny * ny + 1f)
        return ((nx * -0.45f + ny * -0.55f + 0.70f) / len).coerceIn(0f, 1f)
    }
}

private class Rain {
    private class Drop(var x: Float, var y: Float, var speed: Float, var length: Int, val glyphs: CharArray)
    private class Orb(var x: Float, var y: Float, var speed: Float, var radius: Float, var phase: Float)
    private class FacePoint(val x: Float, val y: Float, val h: Float, val shade: Float, val detail: Float, var glyph: Char)
    private class Tear(var y0: Float, var y1: Float, var dx: Float)

    private val random = Random(System.nanoTime())
    private val drops = mutableListOf<Drop>()
    private val orbs = mutableListOf<Orb>()
    private val face = mutableListOf<FacePoint>()
    private val tears = MutableList(4) { Tear(0f, 0f, 0f) }
    private var tearClock = 0f
    private var width = 0f
    private var height = 0f
    private var cell = 0f
    private var textSize = 0f
    private var time = 0f
    // Where the face sits and how big it is.
    private var cx = 0f
    private var cy = 0f
    private var faceW = 0f
    private var faceH = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private fun glyph() = if (random.nextInt(10) < 5) '0' else '1'

    private fun setUp(w: Float, h: Float, density: Float) {
        width = w
        height = h
        cell = 12f * density
        textSize = 11.5f * density
        paint.textSize = textSize
        drops.clear()
        var x = cell / 2
        while (x < w) {
            // Most columns carry two or three streams, for a dense, layered look.
            val streams = 1 + (if (random.nextInt(2) == 0) 1 else 0) + (if (random.nextInt(5) == 0) 1 else 0)
            repeat(streams) { drops += newDrop(x, initial = true) }
            x += cell
        }
        orbs.clear()
        repeat(12) { orbs += newOrb(initial = true) }

        faceH = minOf(h * 0.52f, w * 1.15f)
        faceW = faceH * 0.70f
        cx = w / 2
        cy = h * 0.40f
        face.clear()
        var fy = cy - faceH / 2
        while (fy <= cy + faceH / 2) {
            var fx = cx - faceW / 2
            while (fx <= cx + faceW / 2) {
                val u = (fx - cx) / (faceW / 2)
                val v = (fy - cy) / (faceH / 2)
                val fh = FaceRelief.height(u, v)
                if (fh > 0.03f) face += FacePoint(fx, fy, fh, FaceRelief.shade(u, v), FaceRelief.detail(u, v), glyph())
                fx += cell
            }
            fy += cell
        }
    }

    private fun newDrop(x: Float, initial: Boolean): Drop {
        val length = 8 + random.nextInt(23)
        return Drop(
            x = x,
            y = if (initial) random.nextFloat() * height * 1.2f - height * 0.4f else -random.nextFloat() * height * 0.5f,
            speed = cell * (5f + random.nextFloat() * 12f),
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
        val event = CodeRainTiming.faceProgress(time) >= 0f
        // Digits flicker as they fall; while the face pushes through they scramble wildly.
        val flicker = if (event) 2 else 12
        for (i in drops.indices) {
            val d = drops[i]
            d.y += d.speed * dt
            if (random.nextInt(flicker) == 0) d.glyphs[random.nextInt(d.length)] = glyph()
            if (d.y - d.length * cell > height) drops[i] = newDrop(d.x, initial = false)
        }
        for (i in orbs.indices) {
            val o = orbs[i]
            o.y += o.speed * dt
            if (o.y > height + o.radius * 4) orbs[i] = newOrb(initial = false)
        }
        if (event) {
            for (p in face) if (random.nextInt(4) == 0) p.glyph = glyph()
            // New glitch tears every tenth of a second.
            tearClock -= dt
            if (tearClock <= 0f) {
                tearClock = 0.1f
                for (t in tears) {
                    t.y0 = random.nextFloat() * height
                    t.y1 = t.y0 + cell * (1 + random.nextInt(4))
                    t.dx = (random.nextFloat() * 2f - 1f) * cell * 2.5f
                }
            }
        }
    }

    /** Sideways jump for a glitch tear at [y] (0 outside the tears). */
    private fun tear(y: Float): Float {
        for (t in tears) if (y >= t.y0 && y < t.y1) return t.dx
        return 0f
    }

    fun draw(scope: DrawScope, alpha: Float) = with(scope) {
        if (size.width != width || size.height != height) setUp(size.width, size.height, density)
        if (alpha <= 0f) return@with
        // Green <-> cyan shimmer.
        val shimmer = (sin(time * 0.6f) + 1f) / 2f
        val base = lerp(Color(0xFF16FF6A), Color(0xFF12E6FF), shimmer)
        val head = Color(0xFFE9FFF4)

        val p = CodeRainTiming.faceProgress(time)
        val push = CodeRainTiming.push(p)
        val event = p >= 0f
        val glitch = if (event) push * (0.55f + 0.45f * sin(p * 23f)) else 0f
        val sway = if (event) cell * 0.6f * sin(p * 17f) else 0f

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
                    var y = d.y - k * cell
                    if (y < -cell || y > height) continue
                    // Dim at the top, brightest two-thirds down, gone at the command bar.
                    val depth = (y / height).coerceIn(0f, 1f)
                    val fall = if (depth < 0.7f) 0.35f + 0.65f * (depth / 0.7f) else 1f - (depth - 0.7f) / 0.3f
                    val tail = 1f - k.toFloat() / d.length
                    var a = (fall * tail * alpha).coerceIn(0f, 1f)
                    if (a < 0.02f) continue
                    var x = d.x
                    if (event) {
                        // The rain gives way to the face and is shoved aside around the head.
                        val u = (x - cx) / (faceW / 2)
                        val v = (y - cy) / (faceH / 2)
                        val r = sqrt(u * u + v * v)
                        if (r < 1f) {
                            a *= 1f - 0.85f * push
                        } else {
                            val ring = push * cell * 2.2f * exp(-(r - 1f) * (r - 1f) / 0.05f)
                            x += u / r * ring
                            y += v / r * ring
                        }
                        // Wavy distortion and glitch tears.
                        x += sin(y * 0.02f + time * 14f) * cell * 0.35f * push + tear(y) * glitch
                        if (a < 0.02f) continue
                    }
                    paint.color = (if (k == 0) head else base).copy(alpha = if (k == 0) a else a * 0.85f).toArgb()
                    if (k == 0) paint.setShadowLayer(cell * 0.6f, 0f, 0f, base.copy(alpha = a).toArgb())
                    native.drawText(d.glyphs[k].toString(), x - cell * 0.3f, y, paint)
                    if (k == 0) paint.clearShadowLayer()
                }
            }

            if (event && push > 0.01f) drawFace(native, base, push, glitch, sway, alpha)
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

    /**
     * The face pushing out of the screen: points nearer the viewer (higher relief) spread out,
     * grow and brighten, like a membrane bulging towards you; lit from the upper left, with red and
     * cyan ghosting where the glitch hits.
     */
    private fun drawFace(native: android.graphics.Canvas, base: Color, push: Float, glitch: Float, sway: Float, alpha: Float) {
        val white = Color(0xFFF2FFF8)
        val ghostRed = Color(0xFFFF3355)
        val ghostCyan = Color(0xFF33E6FF)
        for (pt in face) {
            val z = pt.h * push
            val spread = 1f + z * 0.35f
            var x = cx + (pt.x - cx) * spread + sway
            val y = cy + (pt.y - cy) * spread
            x += tear(y) * glitch
            // Strong light contrast plus the features' own depth, so eyes go dark and the nose glows.
            val light = pt.shade.toDouble().pow(1.6).toFloat()
            val carve = (0.55f + pt.detail * 3f).coerceIn(0.08f, 1.4f)
            val a = (push * light * (0.25f + 0.75f * pt.h) * carve * alpha).coerceIn(0f, 1f)
            if (a < 0.03f) continue
            paint.textSize = textSize * (1f + z * 0.9f)
            val c = lerp(base, white, (pt.shade * pt.h * 0.9f).coerceIn(0f, 1f))
            val s = pt.glyph.toString()
            val off = cell * 0.18f * glitch
            if (off > 0.5f && abs(tear(y)) > 0f) {
                paint.color = ghostRed.copy(alpha = a * 0.45f).toArgb()
                native.drawText(s, x - off - cell * 0.3f, y, paint)
                paint.color = ghostCyan.copy(alpha = a * 0.45f).toArgb()
                native.drawText(s, x + off - cell * 0.3f, y, paint)
            }
            paint.color = c.copy(alpha = a).toArgb()
            if (z > 0.45f) paint.setShadowLayer(cell * 0.5f * z, 0f, 0f, c.copy(alpha = a * 0.8f).toArgb())
            native.drawText(s, x - cell * 0.3f, y, paint)
            if (z > 0.45f) paint.clearShadowLayer()
        }
        paint.textSize = textSize
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}
