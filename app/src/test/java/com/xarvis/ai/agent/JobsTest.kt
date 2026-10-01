package com.xarvis.ai.agent

import com.xarvis.ai.workflow.JobSites
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class JobsTest {

    @Test fun readsTheJobsTool() {
        assertEquals(
            listOf(Step.Jobs("painting supervisor OR painting superintendent")),
            ToolCalls.parse("TOOL: jobs painting supervisor OR painting superintendent"),
        )
        assertEquals(Step.Jobs("QC inspector", "Saudi Arabia", "naukri gulf"), ToolCalls.jobs("QC inspector in Saudi Arabia on naukri gulf"))
        assertEquals(Step.Jobs("painting supervisor", null, "linkedin"), ToolCalls.jobs("linkedin: painting supervisor jobs"))
        assertEquals(null, ToolCalls.jobs("painter jobs in the world").place) // "the world" means anywhere
    }

    @Test fun aWebSearchForJobsBecomesTheJobsTool() {
        // From Rex's screenshot: Gemma chose a Google search instead.
        val msg = "don't break anything just search in LinkedIn for any Painting Supervisor or Painting Superintendent requirements. where i can apply"
        val steps = XarvisAgent.forUser(msg, listOf(Step.Search("LinkedIn Painting Supervisor or Painting Superintendent requirements")))
        assertEquals(listOf(Step.Jobs("Painting Supervisor or Painting Superintendent")), steps)
        // Not about jobs: a plain search now answers in-app (Rex asked to see results here), not Google.
        assertEquals(listOf(Step.WebSearch("weather Dubai")), XarvisAgent.forUser("weather in Dubai", listOf(Step.Search("weather Dubai"))))
    }

    @Test fun linkedInSearchesTheWholeWorld() {
        assertEquals(
            "https://www.linkedin.com/jobs/search/?keywords=painting%20supervisor%20OR%20painting%20superintendent&location=Worldwide",
            JobSites.url(JobSites.LINKEDIN, "painting supervisor OR painting superintendent", null),
        )
        assertEquals(
            "https://www.naukrigulf.com/qc-inspector-jobs-in-saudi-arabia",
            JobSites.url(JobSites.pick("naukri gulf"), "QC inspector", "Saudi Arabia"),
        )
    }
}
