package com.xarvis.ai.files

/** A file Gemma wrote into its reply, to be saved as [name] (the extension picks the format). */
data class FileBlock(val name: String, val content: String)

/**
 * Reads the files Gemma writes in a reply:
 *
 *     FILE: packing-list.pdf
 *     # Packing list
 *     - Passport
 *     END FILE
 *
 * Small models wrap it in markdown or forget the end line (or run out of words), so both are allowed.
 */
object FileBlocks {

    private val START = Regex("""(?im)^[\s*`>#-]*FILE\s*(?:NAME)?\s*:\s*(.+?)[\s*`]*$""")
    private val END = Regex("""(?im)^[\s*`>-]*END\s*(?:OF\s*)?(?:FILE)?[\s*`.]*$""")
    private val FENCE = Regex("""^\s*```[\w-]*\s*$""")

    val EXTENSIONS = listOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "txt", "md", "html", "htm", "json", "xml")
    private val NAME_END = Regex("""(?i)\.(?:${EXTENSIONS.joinToString("|")})\b""")

    /** The files in [reply], and the reply without them. */
    fun split(reply: String): Pair<List<FileBlock>, String> {
        val files = mutableListOf<FileBlock>()
        val rest = StringBuilder()
        var pos = 0
        while (pos < reply.length) {
            val start = START.find(reply, pos) ?: break
            rest.append(reply, pos, start.range.first)
            val bodyStart = minOf(start.range.last + 1, reply.length)
            val end = END.find(reply, bodyStart)
            val body = reply.substring(bodyStart, end?.range?.first ?: reply.length)
            cleanBody(body).takeIf { it.isNotBlank() }?.let { files += FileBlock(cleanName(start.groupValues[1]), it) }
            pos = end?.let { it.range.last + 1 } ?: reply.length
        }
        if (pos < reply.length) rest.append(reply, pos, reply.length)
        return files to rest.toString().trim()
    }

    /** "**Umrah list.pdf** (PDF)" -> "Umrah list.pdf"; a name without a known extension becomes a .txt. */
    fun cleanName(raw: String): String {
        var n = raw.trim().trim('*', '`', '"', '\'', '[', ']').trim()
        val ext = NAME_END.findAll(n).lastOrNull()
        n = if (ext != null) n.substring(0, ext.range.last + 1) else "$n.txt"
        n = n.replace(Regex("""[\\/:*?"<>|]"""), "-").trim()
        if (n.startsWith(".")) n = "xarvis$n"
        return if (n.length > 80) n.takeLast(80) else n
    }

    private fun cleanBody(body: String): String {
        val lines = body.lines().toMutableList()
        while (lines.isNotEmpty() && (lines.first().isBlank() || FENCE.matches(lines.first()))) lines.removeAt(0)
        while (lines.isNotEmpty() && (lines.last().isBlank() || FENCE.matches(lines.last()))) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n") { it.trimEnd() }
    }
}
