package com.xarvis.ai.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Searches the folders Rex gave XARVIS (☰ → Search folders; Android's own folder picker, so no
 * storage permission is needed and the installer has nothing new to object to): file names, and
 * the text inside PDF, Word, Excel, PowerPoint and text files. Text read once is kept in a small
 * cache in XARVIS's own storage, so later searches are quick. Nothing leaves the phone.
 */
class PhoneSearch(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("searchFolders", Context.MODE_PRIVATE)
    private val cache = File(appContext.filesDir, "phoneindex")

    data class Folder(val uri: String, val name: String)

    data class Found(val files: List<SavedFile>, val snippets: List<String>, val scanned: Int)

    private class Entry(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long)

    fun folders(): List<Folder> = prefs.getStringSet("uris", emptySet()).orEmpty().map { Folder(it, folderName(Uri.parse(it))) }
        .sortedBy { it.name.lowercase() }

    /** Keeps access to a folder Rex picked (survives restarts). */
    fun add(tree: Uri) {
        appContext.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putStringSet("uris", prefs.getStringSet("uris", emptySet()).orEmpty() + tree.toString()).apply()
    }

    fun remove(uri: String) {
        runCatching { appContext.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putStringSet("uris", prefs.getStringSet("uris", emptySet()).orEmpty() - uri).apply()
    }

    private fun folderName(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Phone storage" } }
            .getOrDefault(tree.lastPathSegment ?: "Folder")

    /** Files whose names match [query], and lines inside files that mention it. */
    suspend fun search(query: String, onProgress: (String) -> Unit = {}): Found = withContext(Dispatchers.IO) {
        val words = words(query)
        val all = folders().flatMap { list(Uri.parse(it.uri)) }
        val byName = all.filter { e -> words.isNotEmpty() && nameMatches(e.name, words) }
            .sortedByDescending { it.modified }.take(MAX_FILE_CARDS)
        val snippets = mutableListOf<String>()
        val inside = mutableListOf<Entry>()
        val readable = all.filter { readableKind(it.name) && it.size in 1..MAX_READ_BYTES }.sortedByDescending { it.modified }
        val started = System.currentTimeMillis()
        for ((i, e) in readable.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (i >= MAX_READ_FILES || System.currentTimeMillis() - started > MAX_READ_MS) break
            if (i % 10 == 0) onProgress("Searching your files… ${i + 1} of ${readable.size.coerceAtMost(MAX_READ_FILES)}")
            val text = textOf(e) ?: continue
            val hits = snippetsIn(text, words)
            if (hits.isNotEmpty()) {
                inside += e
                hits.take(2).forEach { snippets += "In \"${e.name}\": $it" }
            }
            if (snippets.size >= MAX_SNIPPETS) break
        }
        val cards = (byName + inside).distinctBy { it.uri }.take(MAX_FILE_CARDS)
            .map { SavedFile(it.name, it.uri.toString(), it.mime.ifBlank { "application/octet-stream" }) }
        Found(cards, snippets.take(MAX_SNIPPETS), readable.size)
    }

    /** Every file under [tree], depth-first, up to [MAX_LISTED]. */
    private fun list(tree: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return out
        val pending = ArrayDeque(listOf(rootId to 0))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && out.size < MAX_LISTED) {
            val (id, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            try {
                appContext.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH && !name.startsWith(".")) pending += childId to depth + 1
                        } else {
                            out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, childId), name, mime, c.getLong(3), c.getLong(4))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list a folder", e)
            }
        }
        return out
    }

    /** The file's text, from the cache when the file hasn't changed. */
    private suspend fun textOf(e: Entry): String? {
        val key = sha1("${e.uri}|${e.modified}|${e.size}")
        val cached = File(cache, "$key.txt")
        if (cached.exists()) return cached.readText().ifBlank { null }
        val text = try {
            DocumentReader.read(appContext, e.uri).text
        } catch (x: Exception) {
            "" // unreadable (scanned PDF, locked...): remember that too
        }
        runCatching { cache.mkdirs(); cached.writeText(text) }
        return text.ifBlank { null }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "XarvisPhoneSearch"
        private const val MAX_LISTED = 5000
        private const val MAX_DEPTH = 8
        private const val MAX_READ_FILES = 300
        private const val MAX_READ_MS = 40_000L
        private const val MAX_READ_BYTES = 8L * 1024 * 1024
        private const val MAX_FILE_CARDS = 8
        private const val MAX_SNIPPETS = 8

        private val FILLER = setOf(
            "the", "a", "an", "my", "in", "on", "of", "for", "to", "and", "or", "file", "files", "folder", "phone",
            "find", "search", "show", "me", "is", "what", "where", "which", "from", "with", "please", "document", "documents",
        )

        /** The words of a search worth matching ("my passport number" -> passport, number). */
        fun words(query: String): List<String> =
            query.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 && it !in FILLER }.distinct()

        fun nameMatches(name: String, words: List<String>): Boolean {
            val n = name.lowercase()
            return words.all { n.contains(it) }
        }

        fun readableKind(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("pdf", "docx", "xlsx", "pptx", "txt", "csv", "md", "json", "html", "htm", "xml", "log")

        /**
         * Lines of [text] that mention the search: all its words on one line first, else any of the
         * rarer (longer) ones. Each with a little of the line after it, for context.
         */
        fun snippetsIn(text: String, words: List<String>): List<String> {
            if (words.isEmpty()) return emptyList()
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            fun around(i: Int) = (lines[i] + (lines.getOrNull(i + 1)?.let { " / $it" } ?: "")).take(240)
            val all = lines.indices.filter { i -> words.all { lines[i].lowercase().contains(it) } }
            if (all.isNotEmpty()) return all.take(3).map(::around)
            if (words.size == 1) return emptyList()
            val key = words.filter { it.length >= 4 }
            return lines.indices.filter { i -> key.any { lines[i].lowercase().contains(it) } }.take(2).map(::around)
        }
    }
}
