package com.xarvis.ai.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file Rex attached, as text for Gemma. */
data class Document(val name: String, val text: String)

/** Why a file couldn't be read, in words for Rex. */
class UnreadableFile(message: String) : Exception(message)

/**
 * Reads the text of a file Rex picks with the file button: PDF, Word, Excel, PowerPoint, and any
 * text file (txt, csv, html, json, code...). Scanned PDFs have no text; those need the photo button.
 */
object DocumentReader {

    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val MAX_PDF_PAGES = 40
    /** Far more than Gemma can read at once; the agent cuts it to what fits. */
    private const val MAX_CHARS = 60_000

    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    suspend fun read(context: Context, uri: Uri): Document = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (size > MAX_BYTES) throw UnreadableFile("\"$name\" is too big for me to read (over 40 MB).")
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: throw UnreadableFile("I couldn't open \"$name\". Try picking it again.")

        val text = when {
            ext == "pdf" || mime == "application/pdf" -> pdf(context, name, bytes)
            ext == "docx" || mime.contains("wordprocessingml") -> office(name) { OfficeFiles.docxText(bytes) }
            ext == "xlsx" || mime.contains("spreadsheetml") -> office(name) { OfficeFiles.xlsxText(bytes) }
            ext == "pptx" || mime.contains("presentationml") -> office(name) { OfficeFiles.pptxText(bytes) }
            ext in setOf("doc", "xls", "ppt") -> throw UnreadableFile(
                "\"$name\" is in the old Office format (.$ext), which I can't read. Save it as .${ext}x and send that."
            )
            mime.startsWith("image/") -> throw UnreadableFile("That's a photo: send it with the lens button instead.")
            else -> plain(bytes, html = ext in setOf("html", "htm") || mime == "text/html")
                ?: throw UnreadableFile("I can't read this kind of file (\"$name\"). I can read PDF, Word, Excel, PowerPoint and text files.")
        }
        val tidy = text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (tidy.isBlank()) throw UnreadableFile(
            if (ext == "pdf") "\"$name\" has no text in it: it's probably a scanned page. Take a screenshot or photo of it and send it with the lens button."
            else "\"$name\" is empty."
        )
        Document(name, tidy.take(MAX_CHARS))
    }

    private fun pdf(context: Context, name: String, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(bytes).use { doc ->
                PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, MAX_PDF_PAGES)
                }.getText(doc)
            }
        } catch (e: InvalidPasswordException) {
            throw UnreadableFile("\"$name\" is locked with a password, so I can't read it.")
        } catch (e: Exception) {
            throw UnreadableFile("I couldn't read the PDF \"$name\" (${e.message ?: "damaged file"}).")
        }
    }

    private fun office(name: String, read: () -> String): String = try {
        read()
    } catch (e: Exception) {
        throw UnreadableFile("I couldn't read \"$name\" (${e.message ?: "damaged file"}).")
    }

    /** UTF-8 text, or null when the bytes aren't text. Web pages lose their tags. */
    internal fun plain(bytes: ByteArray, html: Boolean = false): String? {
        if (bytes.take(8000).any { it == 0.toByte() }) return null
        var text = bytes.toString(Charsets.UTF_8)
        val bad = text.take(8000).count { it == '�' }
        if (bad > 20) return null
        if (html) {
            text = text.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
                .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>|</h\d>|</tr>"""), "\n")
                .replace(Regex("""<[^>]+>"""), " ")
                .let(OfficeFiles::unescape).replace("&nbsp;", " ")
                .lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
        }
        return text
    }
}
