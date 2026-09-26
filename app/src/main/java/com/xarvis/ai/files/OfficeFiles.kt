package com.xarvis.ai.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Word, Excel and PowerPoint files are zips of XML. This reads their text for Gemma and writes
 * simple Word and Excel files from what Gemma wrote, without a big office library.
 */
object OfficeFiles {

    // ---- Reading ------------------------------------------------------------------------

    /** The paragraphs of a .docx. */
    fun docxText(bytes: ByteArray): String {
        val xml = unzip(bytes)["word/document.xml"] ?: throw IllegalArgumentException("this Word file has no text part")
        val out = StringBuilder()
        Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:tab\s*/>|<w:br\b[^>]*/>""").findAll(xml).forEach { m ->
            when {
                m.value.startsWith("</w:p") -> out.append('\n')
                m.value.startsWith("<w:tab") -> out.append('\t')
                m.value.startsWith("<w:br") -> out.append('\n')
                else -> out.append(unescape(m.groupValues[1]))
            }
        }
        return out.toString()
    }

    /** Every sheet of an .xlsx, one row per line with cells separated by " | ". */
    fun xlsxText(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { texts(it.groupValues[1]) }.toList()
        }.orEmpty()
        val sheets = parts.keys.mapNotNull { k -> SHEET.matchEntire(k)?.let { it.groupValues[1].toInt() to k } }.sortedBy { it.first }
        return sheets.joinToString("\n\n") { (n, key) ->
            val rows = ROW.findAll(parts.getValue(key)).map { row ->
                CELL.findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[3]
                    val value = Regex("""<v>([^<]*)</v>""").find(inner)?.groupValues?.get(1)
                    when {
                        attrs.contains("""t="s"""") -> value?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        attrs.contains("""t="inlineStr"""") -> texts(inner)
                        else -> value?.let(::unescape).orEmpty()
                    }
                }.joinToString(" | ").trimEnd(' ', '|')
            }.filter { it.isNotBlank() }.joinToString("\n")
            (if (sheets.size > 1) "Sheet $n:\n" else "") + rows
        }
    }

    /** The text of each slide of a .pptx. */
    fun pptxText(bytes: ByteArray): String {
        val parts = unzip(bytes)
        val slides = parts.keys.mapNotNull { k -> SLIDE.matchEntire(k)?.let { it.groupValues[1].toInt() to k } }.sortedBy { it.first }
        return slides.joinToString("\n\n") { (n, key) ->
            val text = StringBuilder()
            Regex("""<a:t>([^<]*)</a:t>|</a:p>""").findAll(parts.getValue(key)).forEach { m ->
                if (m.value.startsWith("</a:p")) text.append('\n') else text.append(unescape(m.groupValues[1]))
            }
            "Slide $n:\n" + text.toString().lines().filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    // ---- Writing ------------------------------------------------------------------------

    /** A Word file from Gemma's text: "# " lines become headings, "- " lines bullets, **words** bold. */
    fun docx(content: String): ByteArray {
        val body = StringBuilder()
        for (line in content.lines()) {
            val t = line.trim()
            val heading = Regex("""^(#{1,6})\s+(.*)$""").find(t)
            body.append("<w:p>")
            when {
                heading != null -> {
                    val size = when (heading.groupValues[1].length) { 1 -> 36; 2 -> 30; else -> 26 }
                    body.append(run(heading.groupValues[2].replace("**", ""), bold = true, size = size))
                }
                BULLET.containsMatchIn(t) -> {
                    body.append("<w:pPr><w:ind w:left=\"360\"/></w:pPr>")
                    runs(body, "• " + t.replaceFirst(BULLET, ""))
                }
                else -> runs(body, t)
            }
            body.append("</w:p>")
        }
        return zip(
            "[Content_Types].xml" to XML_HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                "</Types>",
            "_rels/.rels" to XML_HEAD + rels("officeDocument", "word/document.xml"),
            "word/document.xml" to XML_HEAD +
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" + body +
                """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""" +
                "</w:body></w:document>",
        )
    }

    /** An Excel file from rows Gemma wrote as CSV (or a markdown table). Numbers stay numbers. */
    fun xlsx(content: String): ByteArray {
        val rows = table(content)
        val sheet = StringBuilder()
        rows.forEachIndexed { r, cells ->
            sheet.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, value ->
                val ref = column(c) + (r + 1)
                if (NUMBER.matches(value)) sheet.append("<c r=\"$ref\"><v>$value</v></c>")
                else if (value.isNotEmpty()) sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")
            }
            sheet.append("</row>")
        }
        return zip(
            "[Content_Types].xml" to XML_HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                "</Types>",
            "_rels/.rels" to XML_HEAD + rels("officeDocument", "xl/workbook.xml"),
            "xl/workbook.xml" to XML_HEAD +
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to XML_HEAD + rels("worksheet", "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml" to XML_HEAD +
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet</sheetData></worksheet>""",
        )
    }

    /** Rows from CSV lines or a markdown table ("| a | b |", with its "---" line skipped). */
    fun table(content: String): List<List<String>> = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .filterNot { it.contains("--") && it.all { ch -> ch in "|:- " } }
        .map { line ->
            if (line.startsWith("|")) line.trim('|').split('|').map { it.trim().replace("**", "") }
            else csvLine(if (!line.contains(',') && line.contains('\t')) line.replace('\t', ',') else line)
        }

    private fun csvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { cells += cell.toString().trim(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        cells += cell.toString().trim()
        return cells
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    fun column(index: Int): String {
        var n = index + 1
        val s = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            s.append('A' + r)
            n = (n - 1) / 26
        }
        return s.reverse().toString()
    }

    private fun runs(out: StringBuilder, text: String) {
        // "**bold**" pieces: every odd piece is bold.
        text.split("**").forEachIndexed { i, piece -> if (piece.isNotEmpty()) out.append(run(piece, bold = i % 2 == 1)) }
    }

    private fun run(text: String, bold: Boolean = false, size: Int? = null): String {
        val props = (if (bold) "<w:b/>" else "") + (size?.let { "<w:sz w:val=\"$it\"/>" } ?: "")
        return "<w:r>" + (if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else "") +
            "<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"
    }

    private fun rels(type: String, target: String) =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/>""" +
            "</Relationships>"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** The XML parts of a zip, by path. */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".xml")) parts[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        if (parts.isEmpty()) throw IllegalArgumentException("this file isn't a readable Office file")
        return parts
    }

    private fun texts(xml: String) = Regex("""<t(?:\s[^>]*)?>([^<]*)</t>""").findAll(xml).joinToString("") { unescape(it.groupValues[1]) }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("""&#(x?)([0-9a-fA-F]+);""")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&amp;", "&")

    private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private val SHEET = Regex("""xl/worksheets/sheet(\d+)\.xml""")
    private val SLIDE = Regex("""ppt/slides/slide(\d+)\.xml""")
    private val ROW = Regex("""<row\b[^>]*?(?<!/)>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val BULLET = Regex("""^[-*•]\s+""")
    private val NUMBER = Regex("""-?(?:0|[1-9]\d{0,14})(?:\.\d+)?""")
}
