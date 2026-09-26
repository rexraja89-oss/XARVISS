package com.xarvis.ai.files

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBlocksTest {

    @Test fun readsAFileBlock() {
        val (files, rest) = FileBlocks.split("Here you go.\nFILE: umrah-list.pdf\n# Umrah list\n- Ihram\nEND FILE\nSafe travels!")
        assertEquals(listOf(FileBlock("umrah-list.pdf", "# Umrah list\n- Ihram")), files)
        assertEquals("Here you go.\n\nSafe travels!", rest)
    }

    @Test fun toleratesMarkdownAndAMissingEnd() {
        val (files, rest) = FileBlocks.split("**FILE: budget.xlsx**\n```csv\nItem, Amount\nRent, 3000\n```")
        assertEquals(listOf(FileBlock("budget.xlsx", "Item, Amount\nRent, 3000")), files)
        assertEquals("", rest)
    }

    @Test fun readsTwoFiles() {
        val (files, _) = FileBlocks.split("FILE: a.txt\none\nEND FILE\nFILE: b.md\ntwo\nEND")
        assertEquals(listOf("a.txt", "b.md"), files.map { it.name })
    }

    @Test fun cleansNames() {
        assertEquals("Umrah list.pdf", FileBlocks.cleanName("**Umrah list.pdf** (PDF)"))
        assertEquals("notes.txt", FileBlocks.cleanName("notes"))
        assertEquals("a-b.docx", FileBlocks.cleanName("a/b.docx"))
    }

    @Test fun noBlockMeansNoFiles() {
        val (files, rest) = FileBlocks.split("I made a file for you? No, just text.")
        assertTrue(files.isEmpty())
        assertEquals("I made a file for you? No, just text.", rest)
    }

    @Test fun fileBlocksCountAsUsingATool() {
        assertTrue(XarvisAgent.usesTools("FILE: x.pdf\nhello\nEND FILE"))
        assertTrue(XarvisAgent.usesTools("TOOL: time"))
        assertFalse(XarvisAgent.usesTools("I can't create files."))
        assertTrue(XarvisAgent.skippedTool("Sorry, I can't create files."))
    }

    @Test fun filesTool() {
        assertEquals(listOf(Step.ShowFiles("packing list")), ToolCalls.parse("TOOL: files packing list"))
        assertEquals(listOf(Step.ShowFiles("")), ToolCalls.parse("TOOL: files"))
        assertEquals(listOf(Step.FindContact("Atiq")), ToolCalls.parse("TOOL: find contact Atiq"))
    }

    @Test fun convertTool() {
        assertEquals(listOf(Step.ConvertFile("pdf")), ToolCalls.parse("TOOL: convert pdf"))
        assertEquals(listOf(Step.ConvertFile("docx")), ToolCalls.parse("TOOL: convert to a Word file"))
        assertEquals(listOf(Step.ConvertFile("xlsx")), ToolCalls.parse("TOOL: save as excel"))
        assertTrue(ToolCalls.parse("TOOL: convert").isEmpty())
    }

    @Test fun fileTextIsHiddenWhileBeingWritten() {
        assertEquals("Here it is.\nWriting essay.pdf… (4 words)", FileBlocks.preview("Here it is.\nFILE: essay.pdf\n# Gandhi\nwas born in"))
        assertEquals("no file here", FileBlocks.preview("no file here"))
    }

    @Test fun refusingToOpenAnAppIsNudged() {
        assertTrue(XarvisAgent.skippedTool("I do not have a gallery app to open."))
        assertTrue(XarvisAgent.skippedTool("I do not have a gallery to share."))
    }
}
