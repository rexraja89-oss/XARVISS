package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The health-record commands parse deterministically, so Rex's medical history behaves exactly. */
class HealthRecordTest {

    @Test fun addsToTheHealthRecord() {
        assertEquals(Step.HealthAdd("fasting sugar 120 mg/dL"), HealthCommands.parse("add to my health record: fasting sugar 120 mg/dL"))
        assertEquals(Step.HealthAdd("grade 3 fatty liver"), HealthCommands.parse("update my health record grade 3 fatty liver"))
    }

    @Test fun showsExportsAndDeletes() {
        assertEquals(Step.HealthShow, HealthCommands.parse("show my health record"))
        assertEquals(Step.HealthShow, HealthCommands.parse("my health record"))
        assertEquals(Step.HealthExport, HealthCommands.parse("export my health record"))
        assertEquals(Step.HealthClear, HealthCommands.parse("delete my health record"))
    }

    @Test fun ordinaryHealthQuestionsAreNotCommands() {
        assertNull(HealthCommands.parse("is it okay for me to eat cake?"))
        assertNull(HealthCommands.parse("how is my health"))
        // The tool-line form also parses, for when the brain writes it.
        assertEquals(Step.HealthAdd("tiredness after little work"), ToolCalls.parseOne("health add tiredness after little work"))
        assertEquals(Step.HealthShow, ToolCalls.parseOne("health"))
    }
}
