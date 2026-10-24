package com.xarvis.ai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModPipelineTest {

    private val openedReport = """
        OPENED OK - 42 files inside the package.
        EDITABLE text/config files found: 2
        - assets/config.json (310 bytes)
        - data/levels.xml (1200 bytes)
        NOTE: some games check their OBB and refuse a rebuilt one.
    """.trimIndent()

    @Test fun pullsEditableFilePaths() {
        val files = GameModPipeline.editableFiles(openedReport)
        assertEquals(listOf("assets/config.json", "data/levels.xml"), files)
    }

    @Test fun ignoresNotesAndHeadings() {
        assertFalse(GameModPipeline.editableFiles(openedReport).any { it.contains("NOTE") || it.contains("OPENED") })
    }

    @Test fun detectsASealedPackage() {
        val sealed = "SEALED: this file is not an open archive, so nothing can be unpacked from it."
        assertTrue(GameModPipeline.isSealed(sealed))
        assertTrue(GameModPipeline.editableFiles(sealed).isEmpty())
    }

    @Test fun openedPackageIsNotSealed() {
        assertFalse(GameModPipeline.isSealed(openedReport))
    }

    @Test fun pipelineShipsBothWorkflowsAndScripts() {
        val f = GameModPipeline.files()
        assertTrue(f.containsKey(".github/workflows/inspect.yml"))
        assertTrue(f.containsKey(".github/workflows/repack.yml"))
        assertTrue(f.containsKey(".xarvis/inspect.py"))
        assertTrue(f.containsKey(".xarvis/repack.py"))
    }
}
