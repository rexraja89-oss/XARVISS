package com.xarvis.ai.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import com.xarvis.ai.R

/**
 * The Sentinel face (Rex, 22 Nov 2026: "something mind-blowing", from the hooded-figure film he liked): a new chat
 * opens on a hooded figure made of 0s and 1s that raises its head, turns, looks at you, and its left eye ignites red,
 * inside Matrix rain with real depth (far digits fall behind it, near ones in front, out of focus) and lightning.
 * It reacts: the rain races and the eye pulses while XARVIS thinks; the eye brightens while Rex types.
 *
 * Everything is drawn by the graphics chip (an AGSL shader, Android 13+) from a 190 KB atlas of 48 pre-made poses
 * (training/sentinel/make_atlas.py), so it stays smooth. ☰ → 🎙 Voice → "Sentinel face" switches it off; then (and
 * below Android 13) the plain [CodeRain] shows as before.
 */
object SentinelFace {
    private const val PREFS = "settings"
    private const val KEY = "sentinel"
    fun enabled(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun set(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    fun available(): Boolean = Build.VERSION.SDK_INT >= 33

    /** Where the left eye sits in each pose (x, y as 0–1 of the figure's picture); from make_atlas.py. */
    internal val EYE = floatArrayOf(
        0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4558f, 0.5925f, 0.4548f, 0.5925f, 0.4530f, 0.5925f, 0.4507f, 0.5925f, 0.4478f, 0.5925f, 0.4445f, 0.5925f, 0.4409f, 0.5925f, 0.4369f, 0.5925f, 0.4328f, 0.5925f, 0.4273f, 0.5925f, 0.4206f, 0.5925f, 0.4134f, 0.5925f, 0.4071f, 0.5925f, 0.4030f, 0.5925f, 0.4019f, 0.5925f, 0.4034f, 0.5925f, 0.4065f, 0.5925f, 0.4094f, 0.5925f, 0.4110f, 0.5914f, 0.4111f, 0.5853f, 0.4111f, 0.5755f, 0.4111f, 0.5637f, 0.4111f, 0.5514f, 0.4111f, 0.5400f, 0.4111f, 0.5310f, 0.4111f, 0.5255f, 0.4111f, 0.5246f, 0.4111f, 0.5246f, 0.4111f, 0.5246f, 0.4111f, 0.5246f, 0.4111f, 0.5246f, 0.4111f, 0.5293f, 0.4111f, 0.5387f, 0.4111f, 0.5510f, 0.4111f, 0.5647f, 0.4111f, 0.5776f, 0.4111f, 0.5876f, 0.4111f, 0.5924f, 0.4111f, 0.5925f, 0.4111f,
    )
}

@Composable
fun Sentinel(modifier: Modifier, alpha: Float, thinking: Boolean, typing: Boolean) {
    if (Build.VERSION.SDK_INT < 33) {
        CodeRain(modifier, alpha); return
    }
    SentinelShader(modifier, alpha, thinking, typing)
}

@RequiresApi(33)
@Composable
private fun SentinelShader(modifier: Modifier, alpha: Float, thinking: Boolean, typing: Boolean) {
    val context = LocalContext.current
    // If this phone's graphics driver refuses the program, fall back to the plain rain instead of crashing.
    var broken by remember { mutableStateOf(false) }
    val shader = remember {
        runCatching { RuntimeShader(SENTINEL_AGSL) }.onFailure { android.util.Log.w("Sentinel", "shader refused", it) }.getOrNull()?.also { s ->
            val bmp = BitmapFactory.decodeResource(
                context.resources, R.drawable.sentinel_atlas,
                BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 },
            )
            val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
            s.setInputShader("atlas", bs)
        }
    }
    if (shader == null || broken) {
        CodeRain(modifier, alpha); return
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    val think by rememberUpdatedState(thinking)
    val type by rememberUpdatedState(typing)
    var time by remember { mutableFloatStateOf(0f) }
    var rainClock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                time = (now - start) / 1e9f
                rainClock += dt * SentinelTimeline.rainSpeed(think)
            }
        }
    }
    Canvas(modifier) {
        val t = time
        val w = size.width; val h = size.height
        val box = SentinelTimeline.box(w, h)
        val pose = SentinelTimeline.pose(t)
        val i0 = pose.toInt().coerceIn(0, SentinelTimeline.POSES - 1)
        val i1 = (i0 + 1).coerceAtMost(SentinelTimeline.POSES - 1)
        val f = pose - i0
        val ex = SentinelFace.EYE[i0 * 2] * (1 - f) + SentinelFace.EYE[i1 * 2] * f
        val ey = SentinelFace.EYE[i0 * 2 + 1] * (1 - f) + SentinelFace.EYE[i1 * 2 + 1] * f
        val cellW = (w / 72f).coerceAtLeast(8f)
        try {
        shader.setFloatUniform("iRes", w, h)
        shader.setFloatUniform("iTime", t)
        shader.setFloatUniform("iRain", rainClock)
        shader.setFloatUniform("iPose", pose)
        shader.setFloatUniform("iFlash", SentinelTimeline.flash(t))
        shader.setFloatUniform("iEye", SentinelTimeline.eye(t, think, type))
        shader.setFloatUniform("iEyeUV", ex, ey)
        shader.setFloatUniform("iBox", box[0], box[1], box[2], box[3])
        shader.setFloatUniform("iCell", cellW, cellW * 1.45f)
        shader.setFloatUniform("iFade", SentinelTimeline.fade(t) * alpha)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("Sentinel", "uniform refused", e)
            broken = true
            return@Canvas
        }
        drawRect(brush = brush, blendMode = BlendMode.Plus)
    }
}

/** The picture, drawn per pixel on the graphics chip. Output is added on top of the chat's dark background. */
private const val SENTINEL_AGSL = """
uniform shader atlas;
uniform float2 iRes;
uniform float iTime;
uniform float iRain;
uniform float iPose;
uniform float iFlash;
uniform float iEye;
uniform float2 iEyeUV;
uniform float4 iBox;
uniform float2 iCell;
uniform float iFade;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float sdSeg(float2 p, float2 a, float2 b) {
    float2 pa = p - a; float2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// a "0" or "1" inside one cell; q = 0..1 in the cell, soft = edge softness (bigger = out of focus)
float glyph(float2 q, float one, float soft) {
    if (one < 0.5) {
        float2 c = (q - 0.5) / float2(0.30, 0.40);
        float r = length(c);
        return 1.0 - smoothstep(0.16 - soft * 3.0, 0.16 + soft * 3.0, abs(r - 0.78));
    }
    float d = sdSeg(q, float2(0.55, 0.14), float2(0.55, 0.86));
    d = min(d, sdSeg(q, float2(0.55, 0.14), float2(0.36, 0.30)));
    return 1.0 - smoothstep(0.065 - soft, 0.065 + soft, d);
}

float2 posePx(float frame, float2 uv) {
    float col = mod(frame, 8.0);
    float row = floor(frame / 8.0);
    uv = clamp(uv, 0.0, 1.0);
    return float2(col * 100.0 + 0.5 + uv.x * 99.0, row * 90.0 + 0.5 + uv.y * 89.0);
}

// the figure at uv (0..1 of its picture): r = brightness, g = "the figure is here"
half4 figure(float2 uv) {
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return half4(0.0);
    float f0 = floor(iPose);
    float f1 = min(f0 + 1.0, 47.0);
    half4 a = atlas.eval(posePx(f0, uv));
    half4 b = atlas.eval(posePx(f1, uv));
    // fade out at the picture's edges, so no box ever shows (the hood's top touches the picture's top)
    float edge = smoothstep(0.0, 0.10, uv.x) * smoothstep(1.0, 0.90, uv.x) * smoothstep(0.0, 0.16, uv.y) * smoothstep(1.0, 0.88, uv.y);
    return mix(a, b, half(iPose - f0)) * half(edge);
}

float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float3 rainLayer(float2 p, float scale, float density, float speedRows, float lenMin, float lenMax, float br, float soft, float layer) {
    float2 cs = iCell * scale;
    float2 g = floor(p / cs);
    float2 q = fract(p / cs);
    float colH = hash(float2(g.x, layer * 7.13));
    if (colH > density) return float3(0.0);
    float rows = iRes.y / cs.y;
    float len = lenMin + (lenMax - lenMin) * hash(float2(g.x, layer + 1.3));
    float sp = speedRows * (0.7 + 0.6 * hash(float2(g.x, layer + 2.9)));
    float head = fract(hash(float2(g.x, layer + 5.7)) + iRain * sp / (rows + len)) * (rows + len);
    float d = head - g.y;
    if (d < 0.0 || d >= len) return float3(0.0);
    float a = pow(1.0 - d / len, 1.3) * br;
    float isHead = step(d, 1.0);
    float one = step(0.5, hash(g + floor(iTime * 7.0 + hash(g) * 5.0)));
    float gl = glyph(q, one, soft);
    float3 tint = mix(float3(0.25, 1.0, 0.45), float3(0.85, 1.0, 0.9), isHead);
    return tint * gl * (a + isHead * br * 0.8);
}

half4 main(float2 p) {
    // the figure, as a grid of digits
    float2 g = floor(p / iCell);
    float2 q = fract(p / iCell);
    float2 center = (g + 0.5) * iCell;
    half4 fc = figure((center - iBox.xy) / iBox.zw);
    half4 fp = figure((p - iBox.xy) / iBox.zw);
    float v = float(fc.r);
    float mask = float(fp.g);
    float flick = floor(iTime * (1.5 + 6.0 * hash(g)) + hash(g + 3.1) * 7.0);
    float one = step(0.5, hash(g + flick * 0.37));
    float bright = (0.02 + 0.98 * v) * (1.0 + 2.6 * iFlash) + iFlash * 0.10 * mask;  // lightning lights the figure: its own shapes, brighter

    // the eye's place, and how much it outshines the digits under it
    float s = iBox.z / 400.0;
    float2 ep = iBox.xy + iEyeUV * iBox.zw;
    float2 dv = p - ep;
    float d2 = dot(dv, dv);
    float under = clamp(iEye * exp(-d2 / (2.0 * (12.0 * s) * (12.0 * s))), 0.0, 1.0);

    float3 col = float3(0.15, 1.0, 0.35) * bright * glyph(q, one, 0.06) * (1.0 - 0.85 * under);

    // code rain at three distances: far (small, behind the figure), middle, near (big, out of focus, in front)
    col += rainLayer(p, 0.70, 0.55, 6.0, 10.0, 26.0, 0.32, 0.05, 1.0) * (1.0 - 0.95 * mask);
    col += rainLayer(p, 1.25, 0.28, 11.0, 8.0, 18.0, 0.60, 0.06, 2.0) * (1.0 - 0.70 * mask);
    col += rainLayer(p, 2.60, 0.07, 22.0, 5.0, 10.0, 0.85, 0.16, 3.0);
    col *= (1.0 + 1.5 * iFlash);

    // lightning lights storm clouds behind the figure
    float cl = noise(p / 160.0 + float2(iTime * 0.02, 0.0)) * 0.7 + noise(p / 60.0) * 0.3;
    col += iFlash * (0.04 + 0.35 * cl) * (1.0 - mask) * float3(0.55, 1.0, 0.7) * (1.1 - p.y / iRes.y);

    col *= iFade;

    // the red eye: soft halo, sharp core, a short glint (the same look as the film)
    float r1 = 10.0 * s; float r2 = 3.5 * s; float r3 = 1.5 * s; float r4 = 30.0 * s;
    float halo = exp(-d2 / (2.0 * r1 * r1)) * 0.5 + exp(-d2 / (2.0 * r2 * r2)) * 1.3 + exp(-d2 / (2.0 * r4 * r4)) * 0.3;
    float streak = exp(-(dv.y * dv.y) / (2.0 * (0.8 * s) * (0.8 * s))) * exp(-abs(dv.x) / (12.0 * s));
    float core = exp(-d2 / (2.0 * r3 * r3));
    float e = iEye * iFade;
    col += e * (halo + streak) * float3(1.0, 0.07, 0.03) + e * core * float3(1.0, 0.75, 0.65);

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
