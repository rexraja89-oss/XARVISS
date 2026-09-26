package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TimeSpecsTest {
    private val evening = LocalTime.of(20, 0)

    @Test fun alarmTimes() {
        assertEquals(Step.Alarm(21, 0, null), TimeSpecs.alarm("9", evening)) // at 8 pm, "9" is 9 pm
        assertEquals(Step.Alarm(19, 30, null), TimeSpecs.alarm("7:30 pm", evening))
        assertEquals(Step.Alarm(19, 15, null), TimeSpecs.alarm("19:15", evening))
        assertEquals(Step.Alarm(5, 45, null), TimeSpecs.alarm("5.45 tomorrow", evening))
        assertEquals(Step.Alarm(20, 0, null), TimeSpecs.alarm("8 tonight", evening))
        assertEquals(Step.Alarm(0, 0, null), TimeSpecs.alarm("12 am", evening))
        assertEquals(Step.Alarm(12, 0, null), TimeSpecs.alarm("12 pm", evening))
        assertEquals(null, TimeSpecs.alarm("7:75", evening))
    }

    @Test fun timerDurations() {
        assertEquals(90, TimeSpecs.seconds("90 sec"))
        assertEquals(300, TimeSpecs.seconds("5"))
        assertEquals(1800, TimeSpecs.seconds("half an hour"))
        assertEquals(null, TimeSpecs.seconds("a while"))
    }
}
