package com.xarvis.ai.files

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Reads the text of a PDF, page by page, without a PDF library (pdfbox's crypto code got the
 * APK rejected as "invalid"). It handles what phones and office apps produce: compressed
 * streams, object streams, fonts with a ToUnicode map, and text inside form XObjects.
 * Scanned PDFs are pictures, so they have no text to read.
 */
class PdfText private constructor(private val data: String) {

    private class Ref(val num: Int)
    private class Name(val name: String)
    private class Str(val bytes: ByteArray)
    private class Op(val op: String)
    private class Stream(val dict: Map<String, Any?>, val raw: String)

    /** Where an object's value starts: in the file, or in an uncompressed object stream. */
    private class Loc(val src: String, val start: Int)

    private val objects = HashMap<Int, Loc>()
    private val parsed = HashMap<Int, Any?>()
    private val cmaps = HashMap<Int, Map<Int, String>?>()

    init {
        OBJ.findAll(data).forEach { m -> objects[m.groupValues[1].toInt()] = Loc(data, m.range.last + 1) }
        // Newer PDFs keep most objects inside compressed object streams.
        for (num in objects.keys.toList()) {
            val s = obj(num) as? Stream ?: continue
            if ((s.dict["Type"] as? Name)?.name != "ObjStm") continue
            val body = runCatching { decode(s) }.getOrNull() ?: continue
            val n = (s.dict["N"] as? Number)?.toInt() ?: continue
            val first = (s.dict["First"] as? Number)?.toInt() ?: continue
            val header = Regex("""\d+""").findAll(body.substring(0, minOf(first, body.length))).map { it.value.toInt() }.toList()
            for (i in 0 until minOf(n, header.size / 2)) {
                val objNum = header[2 * i]
                val start = first + header[2 * i + 1]
                if (start < body.length && objNum !in objects) objects[objNum] = Loc(body, start)
            }
        }
    }

    private fun text(maxPages: Int): String {
        if (Regex("""/Encrypt\s+\d+\s+\d+\s+R""").containsMatchIn(data)) throw LockedPdf()
        val pages = mutableListOf<Pair<Map<String, Any?>, Map<String, Any?>?>>()
        val root = ROOT.findAll(data).lastOrNull()?.groupValues?.get(1)?.toInt()
        val catalog = root?.let { obj(it) } as? Map<*, *>
        val tree = catalog?.get("Pages")
        if (tree != null) collectPages(tree, null, pages, 0)
        if (pages.isEmpty()) {
            // A damaged or unusual file: take every page object in file order.
            objects.keys.sorted().forEach { n ->
                val d = asDict(obj(n))
                if ((d?.get("Type") as? Name)?.name == "Page") pages += d to null
            }
        }
        return pages.take(maxPages).joinToString("\n\n") { (page, inherited) ->
            val resources = asDict(resolve(page["Resources"])) ?: inherited
            val out = StringBuilder()
            contents(page["Contents"]).forEach { runContent(it, resources, out, 0) }
            out.toString().lines().joinToString("\n") { it.replace(Regex(""" {2,}"""), " ").trim() }.trim()
        }
    }

    private fun collectPages(node: Any?, inherited: Map<String, Any?>?, out: MutableList<Pair<Map<String, Any?>, Map<String, Any?>?>>, depth: Int) {
        if (depth > 50) return
        val d = asDict(resolve(node)) ?: return
        val resources = asDict(resolve(d["Resources"])) ?: inherited
        when ((d["Type"] as? Name)?.name) {
            "Pages" -> (resolve(d["Kids"]) as? List<*>)?.forEach { collectPages(it, resources, out, depth + 1) }
            else -> if (d.containsKey("Contents") || d.containsKey("MediaBox")) out += d to resources
        }
    }

    private fun contents(value: Any?): List<String> = when (val v = resolve(value)) {
        is Stream -> listOfNotNull(runCatching { decode(v) }.getOrNull())
        is List<*> -> v.flatMap { contents(it) }
        else -> emptyList()
    }

    /** Runs a page's (or form's) drawing commands, writing out the text they show. */
    private fun runContent(content: String, resources: Map<String, Any?>?, out: StringBuilder, depth: Int) {
        val fonts = asDict(resolve(resources?.get("Font")))
        val xobjects = asDict(resolve(resources?.get("XObject")))
        var font: Map<Int, String>? = null
        var twoByte = false
        val operands = mutableListOf<Any?>()
        fun show(s: Str) = out.append(decodeText(s.bytes, font, twoByte))
        fun newLine() { if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
        var lastY: Double? = null
        val p = Parser(content)
        while (true) {
            val token = p.next() ?: break
            if (token !is Op) {
                operands += token
                continue
            }
            when (token.op) {
                "Tf" -> {
                    val fontRef = fonts?.get((operands.getOrNull(0) as? Name)?.name)
                    val fontDict = asDict(resolve(fontRef))
                    font = (fontRef as? Ref)?.let { cmapFor(it.num, fontDict) } ?: fontDict?.let { cmapOf(it) }
                    twoByte = (fontDict?.get("Subtype") as? Name)?.name == "Type0"
                }
                "Tj", "'", "\"" -> {
                    if (token.op != "Tj") newLine()
                    (operands.lastOrNull() as? Str)?.let(::show)
                }
                "TJ" -> (operands.lastOrNull() as? List<*>)?.forEach { item ->
                    when (item) {
                        is Str -> show(item)
                        is Number -> if (item.toDouble() < -200 && out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    }
                }
                "Td", "TD" -> {
                    val ty = (operands.getOrNull(1) as? Number)?.toDouble() ?: 0.0
                    if (ty != 0.0) newLine() else if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                }
                "T*" -> newLine()
                "Tm" -> {
                    val y = (operands.getOrNull(5) as? Number)?.toDouble()
                    if (y != null && lastY != null && y != lastY) newLine()
                    else if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                    lastY = y
                }
                "ET" -> if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                "Do" -> if (depth < 5) {
                    val form = resolve(xobjects?.get((operands.lastOrNull() as? Name)?.name)) as? Stream
                    if (form != null && (form.dict["Subtype"] as? Name)?.name == "Form") {
                        val inner = runCatching { decode(form) }.getOrNull()
                        if (inner != null) runContent(inner, asDict(resolve(form.dict["Resources"])) ?: resources, out, depth + 1)
                    }
                }
                "ID" -> p.skipInlineImage()
            }
            operands.clear()
        }
    }

    private fun cmapFor(num: Int, fontDict: Map<String, Any?>?): Map<Int, String>? =
        cmaps.getOrPut(num) { fontDict?.let { cmapOf(it) } }

    /** A font's ToUnicode map (character code -> text); null means "use the codes as Latin-1". */
    private fun cmapOf(font: Map<String, Any?>): Map<Int, String>? {
        val stream = resolve(font["ToUnicode"]) as? Stream ?: return null
        val text = runCatching { decode(stream) }.getOrNull() ?: return null
        val map = HashMap<Int, String>()
        Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
            Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]*)>""").findAll(block.groupValues[1]).forEach {
                map[it.groupValues[1].toInt(16)] = utf16(it.groupValues[2])
            }
        }
        Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
            Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]*>|\[[^\]]*])""").findAll(block.groupValues[1]).forEach { m ->
                val lo = m.groupValues[1].toLong(16).toInt()
                val hi = m.groupValues[2].toLong(16).toInt()
                if (hi - lo > 65535 || hi < lo) return@forEach
                val dst = m.groupValues[3]
                if (dst.startsWith("[")) {
                    Regex("""<([0-9A-Fa-f]*)>""").findAll(dst).forEachIndexed { i, d -> if (lo + i <= hi) map[lo + i] = utf16(d.groupValues[1]) }
                } else {
                    val start = dst.trim('<', '>')
                    val base = utf16(start)
                    for (code in lo..hi) {
                        // Only the last character counts up through the range.
                        val last = base.lastOrNull() ?: break
                        map[code] = base.dropLast(1) + (last + (code - lo))
                    }
                }
            }
        }
        return map.ifEmpty { null }
    }

    private fun decodeText(bytes: ByteArray, cmap: Map<Int, String>?, twoByte: Boolean): String {
        val out = StringBuilder()
        if (twoByte) {
            var i = 0
            while (i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                out.append(cmap?.get(code) ?: "")
                i += 2
            }
        } else {
            for (b in bytes) {
                val code = b.toInt() and 0xFF
                out.append(cmap?.get(code) ?: WIN_ANSI[code] ?: code.toChar().toString())
            }
        }
        return out.toString()
    }

    private fun utf16(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        return String(bytes, Charsets.UTF_16BE)
    }

    private fun asDict(v: Any?): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return when (v) {
            is Stream -> v.dict
            is Map<*, *> -> v as Map<String, Any?>
            else -> null
        }
    }

    private fun resolve(v: Any?, depth: Int = 0): Any? = if (v is Ref && depth < 20) resolve(obj(v.num), depth + 1) else v

    private fun obj(num: Int): Any? {
        if (parsed.containsKey(num)) return parsed[num]
        parsed[num] = null // a reference loop reads as nothing
        val loc = objects[num] ?: return null
        val value = runCatching {
            val p = Parser(loc.src, loc.start)
            val value = p.next()
            if (value is Map<*, *> && p.atStream()) {
                @Suppress("UNCHECKED_CAST")
                val dict = value as Map<String, Any?>
                val start = p.streamStart()
                val text = loc.src
                val fallback = text.indexOf("endstream", start).let { if (it < 0) text.length else it }
                val length = (resolve(dict["Length"]) as? Number)?.toInt()
                val end = if (length != null && length >= 0 && start + length <= text.length &&
                    text.indexOf("endstream", start + length) in (start + length)..(start + length + 4)
                ) start + length else fallback
                Stream(dict, text.substring(start, end))
            } else {
                value
            }
        }.getOrNull()
        parsed[num] = value
        return value
    }

    /** A stream's data, uncompressed. */
    private fun decode(s: Stream): String {
        val filters = when (val f = resolve(s.dict["Filter"])) {
            is Name -> listOf(f.name)
            is List<*> -> f.mapNotNull { (resolve(it) as? Name)?.name }
            else -> emptyList()
        }
        var bytes = latin1(s.raw)
        for (f in filters) {
            bytes = when (f) {
                "FlateDecode", "Fl" -> inflate(bytes, asDict(resolve(s.dict["DecodeParms"])))
                "ASCIIHexDecode", "AHx" -> hex(String(bytes, Charsets.ISO_8859_1).substringBefore('>'))
                "ASCII85Decode", "A85" -> ascii85(String(bytes, Charsets.ISO_8859_1))
                else -> throw IllegalArgumentException("unsupported filter $f")
            }
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun inflate(bytes: ByteArray, parms: Map<String, Any?>?): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } catch (e: java.util.zip.DataFormatException) {
            if (out.size() == 0) throw e // keep what could be read from a slightly damaged stream
        } finally {
            inflater.end()
        }
        val predictor = (parms?.get("Predictor") as? Number)?.toInt() ?: 1
        return if (predictor >= 10) png(out.toByteArray(), (parms?.get("Columns") as? Number)?.toInt() ?: 1) else out.toByteArray()
    }

    /** Undoes PNG row prediction (used in cross-reference streams; rare in text). */
    private fun png(data: ByteArray, columns: Int): ByteArray {
        val row = columns + 1
        val out = ByteArrayOutputStream()
        val prev = ByteArray(columns)
        var i = 0
        while (i + row <= data.size) {
            val type = data[i].toInt()
            val cur = ByteArray(columns) { data[i + 1 + it] }
            for (c in 0 until columns) {
                val left = if (c > 0) cur[c - 1].toInt() and 0xFF else 0
                val up = prev[c].toInt() and 0xFF
                val v = cur[c].toInt() and 0xFF
                cur[c] = when (type) {
                    1 -> v + left
                    2 -> v + up
                    3 -> v + (left + up) / 2
                    4 -> {
                        val ul = if (c > 0) prev[c - 1].toInt() and 0xFF else 0
                        val p = left + up - ul
                        val pa = Math.abs(p - left); val pb = Math.abs(p - up); val pc = Math.abs(p - ul)
                        v + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else ul
                    }
                    else -> v
                }.toByte()
            }
            out.write(cur)
            cur.copyInto(prev)
            i += row
        }
        return out.toByteArray()
    }

    /** Reads PDF values and content-stream operators from text. */
    private class Parser(val s: String, var pos: Int = 0) {

        fun atStream(): Boolean {
            skipSpace()
            return s.startsWith("stream", pos)
        }

        fun streamStart(): Int {
            var p = pos + 6
            if (p < s.length && s[p] == '\r') p++
            if (p < s.length && s[p] == '\n') p++
            return p
        }

        fun skipInlineImage() {
            // The image data runs until "EI" on its own.
            val m = Regex("""\sEI(?:\s|$)""").find(s, pos)
            pos = m?.range?.last?.plus(1) ?: s.length
        }

        private fun skipSpace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == '%') {
                    while (pos < s.length && s[pos] != '\n' && s[pos] != '\r') pos++
                } else if (c.isWhitespace() || c == '\u0000') {
                    pos++
                } else {
                    break
                }
            }
        }

        fun next(): Any? {
            skipSpace()
            if (pos >= s.length) return null
            val c = s[pos]
            return when {
                c == '(' -> literal()
                c == '<' && s.startsWith("<<", pos) -> { pos += 2; dict() }
                c == '<' -> hexString()
                c == '[' -> { pos++; array() }
                c == '/' -> Name(regular(pos + 1).also { pos = it.length + pos + 1 }.let(::unescapeName))
                c == ']' || c == '>' || c == ')' || c == '{' || c == '}' -> { pos++; next() }
                c.isDigit() || c == '-' || c == '+' || c == '.' -> number()
                else -> {
                    val word = regular(pos)
                    pos += word.length.coerceAtLeast(1)
                    when (word) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> Op(word)
                    }
                }
            }
        }

        private fun number(): Any? {
            val word = regular(pos)
            pos += word.length.coerceAtLeast(1)
            val n = word.toDoubleOrNull() ?: return Op(word)
            // "12 0 R" is a reference to object 12.
            if (word.all { it.isDigit() }) {
                refEnd(pos)?.let {
                    pos = it
                    return Ref(word.toIntOrNull() ?: 0)
                }
                return word.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: n
            }
            return n
        }

        /** The end of " 0 R" at [from], if an object reference's tail is there. */
        private fun refEnd(from: Int): Int? {
            var p = from
            fun spaces(): Boolean { val s0 = p; while (p < s.length && s[p].isWhitespace()) p++; return p > s0 }
            if (!spaces()) return null
            val d0 = p
            while (p < s.length && s[p].isDigit()) p++
            if (p == d0 || !spaces()) return null
            if (p >= s.length || s[p] != 'R') return null
            p++
            if (p < s.length && !s[p].isWhitespace() && s[p] !in DELIMITERS) return null
            return p
        }

        private fun array(): List<Any?> {
            val items = mutableListOf<Any?>()
            while (true) {
                skipSpace()
                if (pos >= s.length) break
                if (s[pos] == ']') { pos++; break }
                val item = next()
                if (item is Op && item.op == "R") continue
                items += item
            }
            return items
        }

        private fun dict(): Map<String, Any?> {
            val d = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (pos >= s.length) break
                if (s.startsWith(">>", pos)) { pos += 2; break }
                val key = next()
                if (key !is Name) continue
                d[key.name] = next()
            }
            return d
        }

        private fun literal(): Str {
            pos++ // (
            val out = ByteArrayOutputStream()
            var depth = 1
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '\\' -> {
                        if (pos >= s.length) break
                        val e = s[pos++]
                        when (e) {
                            'n' -> out.write('\n'.code); 'r' -> out.write('\r'.code); 't' -> out.write('\t'.code)
                            'b' -> out.write(8); 'f' -> out.write(12)
                            '\r' -> if (pos < s.length && s[pos] == '\n') pos++
                            '\n' -> {}
                            in '0'..'7' -> {
                                var v = e - '0'
                                repeat(2) { if (pos < s.length && s[pos] in '0'..'7') v = v * 8 + (s[pos++] - '0') }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e.code and 0xFF)
                        }
                    }
                    '(' -> { depth++; out.write(c.code) }
                    ')' -> { if (--depth == 0) break; out.write(c.code) }
                    else -> out.write(c.code and 0xFF)
                }
            }
            return Str(out.toByteArray())
        }

        private fun hexString(): Str {
            val end = s.indexOf('>', pos).let { if (it < 0) s.length else it }
            val h = s.substring(pos + 1, end).filter { it.isLetterOrDigit() }
            pos = end + 1
            return Str(hex(if (h.length % 2 == 1) h + "0" else h))
        }

        private fun regular(from: Int): String {
            var p = from
            while (p < s.length && !s[p].isWhitespace() && s[p] !in DELIMITERS) p++
            return s.substring(from, p)
        }

        private fun unescapeName(n: String) = n.replace(Regex("""#([0-9A-Fa-f]{2})""")) { it.groupValues[1].toInt(16).toChar().toString() }
    }

    class LockedPdf : Exception("locked with a password")

    companion object {
        private val OBJ = Regex("""(?<![0-9])(\d+)\s+\d+\s+obj\b""")
        private val ROOT = Regex("""/Root\s+(\d+)\s+\d+\s+R""")
        private const val DELIMITERS = "()<>[]{}/%"

        /** The text of the first [maxPages] pages of [bytes]; throws [LockedPdf] for a locked one. */
        fun read(bytes: ByteArray, maxPages: Int = 40): String = PdfText(String(bytes, Charsets.ISO_8859_1)).text(maxPages)

        private fun latin1(s: String) = s.toByteArray(Charsets.ISO_8859_1)

        private fun hex(h: String): ByteArray {
            val clean = h.filter { it.isLetterOrDigit() }.let { if (it.length % 2 == 1) it + "0" else it }
            return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }

        private fun ascii85(text: String): ByteArray {
            val s = text.substringAfter("<~", text).substringBefore("~>").filterNot { it.isWhitespace() }
            val out = ByteArrayOutputStream()
            var group = 0L
            var count = 0
            for (c in s) {
                if (c == 'z' && count == 0) { repeat(4) { out.write(0) }; continue }
                if (c !in '!'..'u') continue
                group = group * 85 + (c - '!')
                if (++count == 5) {
                    for (shift in intArrayOf(24, 16, 8, 0)) out.write(((group shr shift) and 0xFF).toInt())
                    group = 0; count = 0
                }
            }
            if (count > 0) {
                val filled = count
                while (count < 5) { group = group * 85 + 84; count++ }
                for (i in 0 until filled - 1) out.write(((group shr (24 - 8 * i)) and 0xFF).toInt())
            }
            return out.toByteArray()
        }

        /** Windows-1252 codes that differ from Latin-1 (curly quotes, dashes, euro...). */
        private val WIN_ANSI = mapOf(
            0x80 to "€", 0x82 to "‚", 0x84 to "„", 0x85 to "…", 0x91 to "‘", 0x92 to "’", 0x93 to "“", 0x94 to "”",
            0x95 to "•", 0x96 to "–", 0x97 to "—", 0x99 to "™",
        )
    }
}
