package com.xarvis.ai.files

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfTextTest {

    private fun pdf(vararg objects: String) = buildString {
        append("%PDF-1.4\n")
        objects.forEachIndexed { i, o -> append("${i + 1} 0 obj\n$o\nendobj\n") }
        append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
    }.toByteArray(Charsets.ISO_8859_1)

    private fun stream(content: ByteArray, compress: Boolean): String {
        val data = if (compress) {
            val d = Deflater().apply { setInput(content); finish() }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            out.toByteArray()
        } else {
            content
        }
        val filter = if (compress) " /Filter /FlateDecode" else ""
        return "<< /Length ${data.size}$filter >>\nstream\n" + String(data, Charsets.ISO_8859_1) + "\nendstream"
    }

    @Test fun readsPlainFontText() {
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
            stream("BT /F1 12 Tf 72 700 Td (Payslip \\(August\\)) Tj 0 -14 Td [(Tot) 20 (al) -300 (5,000)] TJ ET".toByteArray(), compress = false),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        assertEquals("Payslip (August)\nTotal 5,000", PdfText.read(bytes))
    }

    @Test fun readsCompressedTextThroughItsUnicodeMap() {
        val cmap = "begincmap\n2 beginbfchar\n<0001> <0052>\n<0002> <0065>\nendbfchar\n1 beginbfrange\n<0003> <0004> <0078>\nendbfrange\nendcmap"
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F7 5 0 R >> >> /Contents 4 0 R >>",
            stream("BT /F7 11 Tf <0001 0002 0003> Tj ET".toByteArray(), compress = true),
            "<< /Type /Font /Subtype /Type0 /BaseFont /Arial /ToUnicode 6 0 R >>",
            stream(cmap.toByteArray(), compress = true),
        )
        assertEquals("Rex", PdfText.read(bytes))
    }

    @Test(expected = PdfText.LockedPdf::class)
    fun lockedFilesSaySo() {
        PdfText.read("%PDF-1.4\n1 0 obj\n<< >>\nendobj\ntrailer\n<< /Root 1 0 R /Encrypt 2 0 R >>".toByteArray())
    }
}
