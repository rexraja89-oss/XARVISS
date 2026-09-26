package com.xarvis.ai.files

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** A file XARVIS made, shown in the chat with OPEN and SHARE. */
data class SavedFile(val name: String, val uri: String, val mime: String)

/**
 * Saves the files Gemma writes into Downloads/XARVIS (PDF, Word, Excel or text) and remembers
 * them, so Rex can ask for one again later ("send me the packing list").
 */
class FileStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("files", Context.MODE_PRIVATE)

    suspend fun save(block: FileBlock): SavedFile = withContext(Dispatchers.IO) {
        val (name, mime, bytes) = render(block)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XARVIS")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("the phone wouldn't let me save to Downloads")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val file = File(appContext.getExternalFilesDir("XARVIS"), name)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
        // The phone may rename a duplicate ("list (1).pdf").
        val saved = SavedFile(DocumentReader.displayName(appContext, uri).takeIf { it != "file" } ?: name, uri.toString(), mime)
        remember(saved)
        saved
    }

    /** Files XARVIS made whose names match [query] (all words), newest first; the latest few if [query] is blank. */
    suspend fun find(query: String): List<SavedFile> = withContext(Dispatchers.IO) {
        val all = index().filter(::exists)
        val words = query.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 && it !in FILLER }
        if (words.isEmpty()) all.take(5)
        else all.filter { f -> words.all { f.name.lowercase().contains(it) } }
            .ifEmpty { all.filter { f -> words.any { f.name.lowercase().contains(it) } } }
            .take(5)
    }

    private fun exists(f: SavedFile): Boolean = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(Uri.parse(f.uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun index(): List<SavedFile> = runCatching {
        val a = JSONArray(prefs.getString("index", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { SavedFile(it.getString("n"), it.getString("u"), it.getString("m")) }
    }.getOrDefault(emptyList())

    private fun remember(f: SavedFile) {
        val list = listOf(f) + index().filterNot { it.uri == f.uri }
        val a = JSONArray()
        list.take(MAX_REMEMBERED).forEach { a.put(JSONObject().put("n", it.name).put("u", it.uri).put("m", it.mime)) }
        prefs.edit().putString("index", a.toString()).apply()
    }

    private fun render(block: FileBlock): Triple<String, String, ByteArray> {
        val ext = block.name.substringAfterLast('.', "txt").lowercase()
        val base = block.name.substringBeforeLast('.')
        return when (ext) {
            "pdf" -> Triple(block.name, "application/pdf", pdf(block.content))
            "docx", "doc" -> Triple("$base.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", OfficeFiles.docx(block.content))
            "xlsx", "xls" -> Triple("$base.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", OfficeFiles.xlsx(block.content))
            else -> Triple(block.name, TEXT_TYPES[ext] ?: "text/plain", block.content.toByteArray(Charsets.UTF_8))
        }
    }

    /** An A4 PDF of Gemma's text: "# " lines are headings, "- " lines bullets, long lines wrap. */
    private fun pdf(content: String): ByteArray {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val width = PAGE_W - 2 * MARGIN
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let(doc::finishPage)
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
            y = MARGIN.toFloat()
        }
        newPage()
        for (raw in content.lines()) {
            val line = raw.trimEnd()
            val heading = Regex("""^\s*(#{1,6})\s+(.*)$""").find(line)
            val size = when (heading?.groupValues?.get(1)?.length) { null -> 11f; 1 -> 20f; 2 -> 16f; else -> 13f }
            paint.textSize = size
            paint.typeface = if (heading != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val text = (heading?.groupValues?.get(2) ?: line.replaceFirst(Regex("""^(\s*)[-*]\s+"""), "$1• ")).replace("**", "")
            if (text.isBlank()) {
                y += size * 0.8f
                continue
            }
            if (heading != null) y += size * 0.5f
            for (part in wrap(text, paint, width.toFloat())) {
                if (y + size > PAGE_H - MARGIN) newPage()
                y += size
                page!!.canvas.drawText(part, MARGIN.toFloat(), y, paint)
                y += size * 0.4f
            }
        }
        page?.let(doc::finishPage)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            // A word wider than the page is split.
            var rest = word
            while (paint.measureText(rest) > width) {
                val n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                lines += rest.substring(0, n)
                rest = rest.substring(n)
            }
            current = rest
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private companion object {
        const val PAGE_W = 595 // A4 in points
        const val PAGE_H = 842
        const val MARGIN = 50
        const val MAX_REMEMBERED = 200
        val FILLER = setOf("the", "file", "files", "my", "me", "send", "show", "give", "open", "share", "pdf", "docx", "xlsx", "word", "excel")
        val TEXT_TYPES = mapOf(
            "csv" to "text/csv", "md" to "text/markdown", "html" to "text/html", "htm" to "text/html",
            "json" to "application/json", "xml" to "text/xml", "txt" to "text/plain",
        )
    }
}
