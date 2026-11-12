package com.xarvis.updater

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Rebuilds a new APK from the installed one plus a small patch made on GitHub by `.github/scripts/make_patches.py`
 * (bsdiff's method; deflate instead of bzip2, so nothing beyond java.util.zip is needed). Streams from disk:
 * a few hundred KB of memory however big the APK is. The caller checks the result's SHA-256 against GitHub's
 * before anything is installed, so a damaged patch or an unexpected starting file can never install.
 *
 * Format "XVPATCH1": magic | new size | control length | diff length (int64 big-endian) | zlib(control) | zlib(diff)
 * | zlib(extra); control = triples (x, y, z): x bytes of diff added to the old file from its current position, then
 * y bytes copied from extra, then the old position moves by z.
 */
object Patch {
    private val MAGIC = "XVPATCH1".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 32L
    private const val CHUNK = 64 * 1024
    /** Nothing XARVIS ships comes near this; a bigger number means a damaged patch. */
    private const val MAX_SIZE = 2L * 1024 * 1024 * 1024

    class Damaged(message: String) : Exception(message)

    /**
     * Writes the new file from [old] and [patch] into [out] and returns its SHA-256 (lowercase hex).
     * [progress] gets (bytes written, new size).
     */
    fun apply(old: File, patch: File, out: File, progress: (Long, Long) -> Unit = { _, _ -> }): String {
        val total = patch.length()
        val (newSize, ctrlLen, diffLen) = DataInputStream(FileInputStream(patch)).use { h ->
            val magic = ByteArray(MAGIC.size)
            if (total < HEADER) throw Damaged("the patch is too short")
            h.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw Damaged("not an XARVIS patch")
            Triple(h.readLong(), h.readLong(), h.readLong())
        }
        if (newSize !in 0..MAX_SIZE || ctrlLen < 0 || diffLen < 0 || HEADER + ctrlLen + diffLen > total) {
            throw Damaged("the patch's header is damaged")
        }
        val ctrl = section(patch, HEADER, ctrlLen)
        val diff = section(patch, HEADER + ctrlLen, diffLen)
        val extra = section(patch, HEADER + ctrlLen + diffLen, total - HEADER - ctrlLen - diffLen)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            RandomAccessFile(old, "r").use { o ->
                BufferedOutputStream(FileOutputStream(out), CHUNK).use { w ->
                    val oldSize = o.length()
                    val buf = ByteArray(CHUNK)
                    val ob = ByteArray(CHUNK)
                    var newPos = 0L
                    var oldPos = 0L
                    var lastReport = 0L
                    while (newPos < newSize) {
                        val x = ctrl.readLong()
                        val y = ctrl.readLong()
                        val z = ctrl.readLong()
                        if (x < 0 || y < 0 || x > newSize - newPos || y > newSize - newPos - x) throw Damaged("the patch is damaged")
                        var left = x
                        while (left > 0) {
                            val n = minOf(left, CHUNK.toLong()).toInt()
                            diff.readFully(buf, 0, n)
                            readOld(o, oldSize, oldPos, ob, n)
                            for (i in 0 until n) buf[i] = (buf[i] + ob[i]).toByte()
                            w.write(buf, 0, n)
                            md.update(buf, 0, n)
                            oldPos += n
                            newPos += n
                            left -= n
                        }
                        left = y
                        while (left > 0) {
                            val n = minOf(left, CHUNK.toLong()).toInt()
                            extra.readFully(buf, 0, n)
                            w.write(buf, 0, n)
                            md.update(buf, 0, n)
                            newPos += n
                            left -= n
                        }
                        oldPos += z
                        if (newPos - lastReport >= 4L * 1024 * 1024 || newPos == newSize) {
                            lastReport = newPos
                            progress(newPos, newSize)
                        }
                    }
                }
            }
        } catch (e: java.io.EOFException) {
            throw Damaged("the patch ended too early")
        } catch (e: java.util.zip.ZipException) {
            throw Damaged("the patch is damaged (${e.message})")
        } finally {
            ctrl.close()
            diff.close()
            extra.close()
        }
        return hex(md.digest())
    }

    /** [n] bytes of the old file from [pos] into [into]; zeros where [pos] is outside it (as bsdiff does). */
    private fun readOld(o: RandomAccessFile, size: Long, pos: Long, into: ByteArray, n: Int) {
        java.util.Arrays.fill(into, 0, n, 0)
        val start = maxOf(pos, 0L)
        val end = minOf(pos + n, size)
        if (end <= start) return
        o.seek(start)
        o.readFully(into, (start - pos).toInt(), (end - start).toInt())
    }

    private fun section(file: File, start: Long, length: Long): DataInputStream =
        DataInputStream(BufferedInputStream(InflaterInputStream(Slice(file, start, length), Inflater(), CHUNK), CHUNK))

    /** [length] bytes of [file] from [start]. */
    private class Slice(file: File, start: Long, private var left: Long) : InputStream() {
        private val f = FileInputStream(file).also { it.channel.position(start) }
        override fun read(): Int {
            if (left <= 0) return -1
            val b = f.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = f.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = f.close()
    }

    /** SHA-256 of a file (lowercase hex). */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
