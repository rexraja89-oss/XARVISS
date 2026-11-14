package com.xarvis.ai.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XARVIS's face recognition, fully on the phone (S22 build): Google's BlazeFace finds faces and their
 * eyes, each face is levelled and cropped to 112×112 the way MobileFaceNet expects, and MobileFaceNet
 * turns it into a 192-number faceprint. Same TensorFlow Lite engine as the wake word, so no new library.
 * The maths ([FaceMath]) was checked on public test photos before it shipped.
 */
class FaceEngine(context: Context) : Closeable {

    private val detector = Interpreter(asset(context, "faces/face_detection_short_range.tflite"))
    private val embedder = Interpreter(asset(context, "faces/mobilefacenet.tflite"))
    private val regressorsOut: Int
    private val scoresOut: Int

    init {
        val names = (0 until detector.outputTensorCount).map { detector.getOutputTensor(it).name() }
        regressorsOut = names.indexOfFirst { it.contains("regress") }.takeIf { it >= 0 } ?: 0
        scoresOut = names.indexOfFirst { it.contains("class") }.takeIf { it >= 0 } ?: 1
    }

    /** A face found in a photo: its box and eyes in that photo's pixels, and its faceprint. */
    class Seen(val left: Float, val area: Float, val print: FloatArray)

    /** Every face in [photo], left to right, with its faceprint. */
    fun faces(photo: Bitmap): List<Seen> {
        val side = maxOf(photo.width, photo.height).toFloat()
        val found = detect(photo, side)
        return found.map { f ->
            val e = f.eyes()
            val eyes = floatArrayOf(e[0] * side, e[1] * side, e[2] * side, e[3] * side)
            Seen(f.box[0] * side, f.area * side * side, embed(align(photo, eyes)))
        }.sortedBy { it.left }
    }

    /**
     * The faceprint of the one clear face in [photo] (for teaching XARVIS someone): the only face, or a
     * face clearly bigger than the rest (the person the photo is of). Null if none, or it's ambiguous.
     */
    fun mainFace(photo: Bitmap): FloatArray? {
        val all = faces(photo).sortedByDescending { it.area }
        if (all.isEmpty()) return null
        if (all.size > 1 && all[0].area < all[1].area * 1.8f) return null
        return all[0].print
    }

    private fun detect(photo: Bitmap, side: Float): List<FaceMath.Face> {
        val n = FaceMath.DETECT_SIZE
        // The photo letterboxed into a square (top-left, black around), scaled to 128×128.
        val square = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.BLACK)
            drawBitmap(photo, Matrix().apply { setScale(n / side, n / side) }, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(n * n).also { square.getPixels(it, 0, n, 0, 0, n, n) }
        val input = Array(1) { Array(n) { y -> Array(n) { x ->
            val c = px[y * n + x]
            floatArrayOf(Color.red(c) / 127.5f - 1f, Color.green(c) / 127.5f - 1f, Color.blue(c) / 127.5f - 1f)
        } } }
        val regressors = Array(1) { Array(896) { FloatArray(16) } }
        val scores = Array(1) { Array(896) { FloatArray(1) } }
        detector.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf(regressorsOut to regressors, scoresOut to scores))
        return FaceMath.decode(regressors[0], FloatArray(896) { scores[0][it][0] })
    }

    /** The face levelled and scaled so its eyes land on MobileFaceNet's template, as a 112×112 picture. */
    private fun align(photo: Bitmap, eyes: FloatArray): Bitmap {
        val m = Matrix()
        m.setPolyToPoly(eyes, 0, FaceMath.TEMPLATE_EYES, 0, 2) // two points = rotate + scale + move
        val face = Bitmap.createBitmap(FaceMath.FACE_SIZE, FaceMath.FACE_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(face).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return face
    }

    private fun embed(face: Bitmap): FloatArray {
        val n = FaceMath.FACE_SIZE
        val px = IntArray(n * n).also { face.getPixels(it, 0, n, 0, 0, n, n) }
        val one = Array(n) { y -> Array(n) { x ->
            val c = px[y * n + x]
            floatArrayOf((Color.red(c) - 127.5f) / 128f, (Color.green(c) - 127.5f) / 128f, (Color.blue(c) - 127.5f) / 128f)
        } }
        // The model takes two faces at a time; give it the same one twice.
        val out = Array(2) { FloatArray(FaceMath.EMBEDDING) }
        embedder.run(arrayOf(one, one), out)
        return FaceMath.normalize(out[0])
    }

    override fun close() {
        detector.close()
        embedder.close()
    }

    companion object {
        private fun asset(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }

        /** A Gallery photo, upright (EXIF), at most [maxSide] px. */
        fun loadUpright(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? = runCatching {
            val r = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = r.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val turn = r.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
            if (turn == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        }.getOrNull()
    }
}
