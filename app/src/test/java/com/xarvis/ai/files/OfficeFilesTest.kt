package com.xarvis.ai.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeFilesTest {

    @Test fun wordFileRoundTrip() {
        val text = OfficeFiles.docxText(OfficeFiles.docx("# Packing list\n- Ihram & towel\nBring **two** <sets>"))
        assertEquals("Packing list\n• Ihram & towel\nBring two <sets>\n", text)
    }

    @Test fun excelFileRoundTrip() {
        val bytes = OfficeFiles.xlsx("Item, Amount\nRent, 3000\n\"Food, drinks\", 1200.50\nPhone, 050123")
        assertEquals("Item | Amount\nRent | 3000\nFood, drinks | 1200.50\nPhone | 050123", OfficeFiles.xlsxText(bytes))
    }

    @Test fun markdownTablesBecomeRows() {
        assertEquals(
            listOf(listOf("Item", "Amount"), listOf("Rent", "3000")),
            OfficeFiles.table("| Item | Amount |\n|---|---|\n| Rent | 3000 |"),
        )
    }

    @Test fun excelSharedStrings() {
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("xl/sharedStrings.xml"))
                z.write("<sst><si><t>Name</t></si><si><r><t>Re</t></r><r><t>x</t></r></si></sst>".toByteArray())
                z.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
                z.write("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\"><v>7</v></c></row><row r=\"2\"/></sheetData></worksheet>".toByteArray())
            }
        }.toByteArray()
        assertEquals("Name | Rex | 7", OfficeFiles.xlsxText(zip))
    }

    @Test fun columns() {
        assertEquals(listOf("A", "Z", "AA", "AB"), listOf(0, 25, 26, 27).map(OfficeFiles::column))
    }

    @Test fun entities() {
        assertEquals("a & b < c ' é", OfficeFiles.unescape("a &amp; b &lt; c &apos; &#233;"))
        assertEquals("&amp;lt;", OfficeFiles.escape("&lt;"))
    }

    @Test fun plainText() {
        assertEquals("hello", DocumentReader.plain("hello".toByteArray()))
        assertNull(DocumentReader.plain(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00)))
        val page = DocumentReader.plain("<html><style>p{}</style><p>Hi &amp; bye</p><script>x()</script></html>".toByteArray(), html = true)!!
        assertTrue(page, page.trim() == "Hi & bye")
    }
}
