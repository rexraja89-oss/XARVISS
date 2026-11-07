package com.xarvis.ai.faces

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The plain maths of XARVIS's face recognition (no Android, so it's unit-tested): decoding Google's
 * BlazeFace (short range) detector output, and comparing MobileFaceNet "faceprints". Ported from a
 * Python twin that was checked on public test photos in the sandbox: same person ≥ 0.42 similarity,
 * different people ≤ 0.31; group photos (Obama+Biden, Kit+Rose) named correctly, an untaught face
 * came out "unknown" (0.22).
 */
object FaceMath {
    const val DETECT_SIZE = 128
    const val FACE_SIZE = 112
    const val EMBEDDING = 192

    /** Similarity at or above this: it's them. */
    const val SURE = 0.42f
    /** Between this and [SURE]: probably them, not certain. Below: someone XARVIS doesn't know. */
    const val MAYBE = 0.33f

    /** ArcFace's 112×112 template eye positions (image-left eye first). */
    val TEMPLATE_EYES = floatArrayOf(38.29f, 51.70f, 73.53f, 51.50f)

    /** BlazeFace short-range anchors: 16×16 cells × 2, then 8×8 cells × 6 = 896 (centres, 0..1). */
    val anchors: Array<FloatArray> by lazy {
        val out = ArrayList<FloatArray>(896)
        for ((grid, perCell) in listOf(16 to 2, 8 to 6)) {
            for (y in 0 until grid) for (x in 0 until grid) repeat(perCell) {
                out += floatArrayOf((x + 0.5f) / grid, (y + 0.5f) / grid)
            }
        }
        out.toTypedArray()
    }

    /** A detected face, in the detector's 0..1 square: box (x0, y0, x1, y1) and 6 keypoints (eyes, nose, mouth, ears). */
    class Face(val score: Float, val box: FloatArray, val keypoints: FloatArray) {
        /** The two eyes as (x, y, x, y), image-left eye first. */
        fun eyes(): FloatArray {
            val a = floatArrayOf(keypoints[0], keypoints[1])
            val b = floatArrayOf(keypoints[2], keypoints[3])
            return if (a[0] <= b[0]) floatArrayOf(a[0], a[1], b[0], b[1]) else floatArrayOf(b[0], b[1], a[0], a[1])
        }
        val area: Float get() = (box[2] - box[0]) * (box[3] - box[1])
    }

    /** Faces from BlazeFace's raw output ([regressors] 896×16, [scores] 896 logits), best first, overlaps removed. */
    fun decode(regressors: Array<FloatArray>, scores: FloatArray, minScore: Float = 0.5f): List<Face> {
        val order = scores.indices.sortedByDescending { scores[it] }
        val faces = ArrayList<Face>()
        for (i in order) {
            val s = sigmoid(scores[i])
            if (s < minScore) break
            val r = regressors[i]
            val (cx, cy) = anchors[i].let { it[0] to it[1] }
            val bx = r[0] / DETECT_SIZE + cx
            val by = r[1] / DETECT_SIZE + cy
            val bw = r[2] / DETECT_SIZE
            val bh = r[3] / DETECT_SIZE
            val box = floatArrayOf(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
            if (faces.any { iou(it.box, box) > 0.3f }) continue
            val kp = FloatArray(12) { k -> r[4 + k] / DETECT_SIZE + if (k % 2 == 0) cx else cy }
            faces += Face(s, box, kp)
        }
        return faces
    }

    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x.coerceIn(-100f, 100f)))

    fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Who a faceprint belongs to, among [known] (name → their taught faceprints). */
    data class Match(val name: String?, val similarity: Float, val sure: Boolean)

    fun bestMatch(face: FloatArray, known: Map<String, List<FloatArray>>): Match {
        var bestName: String? = null
        var best = -1f
        for ((name, prints) in known) for (p in prints) {
            val s = cosine(face, p)
            if (s > best) { best = s; bestName = name }
        }
        return when {
            bestName == null || best < MAYBE -> Match(null, best, false)
            best < SURE -> Match(bestName, best, false)
            else -> Match(bestName, best, true)
        }
    }

    /** What XARVIS says about the faces it saw (left to right). */
    fun describe(matches: List<Match>): String = when {
        matches.isEmpty() -> "I can't see a face, sir. Point the camera at their face, in good light."
        matches.size == 1 -> one(matches[0])
        else -> "I see ${matches.size} people, sir. From the left: " + matches.joinToString("; ") { short(it) } + "."
    }

    private fun pct(m: Match) = "${(m.similarity.coerceIn(0f, 1f) * 100).toInt()}% match"

    private fun one(m: Match): String = when {
        m.name == null -> "I don't recognise this person, sir."
        m.sure -> "That's ${m.name}, sir — ${pct(m)}."
        else -> "That looks like ${m.name}, sir, but I'm not certain (${pct(m)}). Try better light, facing the camera."
    }

    private fun short(m: Match): String = when {
        m.name == null -> "someone I don't know"
        m.sure -> "${m.name} (${pct(m)})"
        else -> "possibly ${m.name} (${pct(m)})"
    }
}
