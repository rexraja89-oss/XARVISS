package com.xarvis.ai.agent

import com.xarvis.ai.agent.JobCommands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobCommandsTest {
    @Test fun hunting() {
        assertEquals(Cmd.Hunt(null, false), JobCommands.parse("hunt jobs"))
        assertEquals(Cmd.Hunt(null, false), JobCommands.parse("XARVIS, start job hunt"))
        assertEquals(Cmd.Hunt("Nigeria", false), JobCommands.parse("find me jobs in Nigeria"))
        assertEquals(Cmd.Hunt("Kazakhstan", false), JobCommands.parse("hunt for jobs in Kazakhstan now"))
        assertEquals(Cmd.Hunt(null, true), JobCommands.parse("full job hunt"))
        assertEquals(Cmd.Hunt(null, true), JobCommands.parse("search jobs in all countries"))
        assertEquals(Cmd.Hunt(null, false), JobCommands.parse("find jobs for me"))
    }

    @Test fun draftsSendingAndLists() {
        assertEquals(Cmd.Drafts, JobCommands.parse("show my job drafts"))
        assertEquals(Cmd.Drafts, JobCommands.parse("open job hunter"))
        assertEquals(Cmd.Send(10), JobCommands.parse("send 10 job applications"))
        assertEquals(Cmd.Send(5), JobCommands.parse("Send five applications"))
        assertEquals(Cmd.Send(null), JobCommands.parse("send the approved applications"))
        assertEquals(Cmd.StopSending, JobCommands.parse("stop sending"))
        assertEquals(Cmd.Trust("Gulf Manpower", true), JobCommands.parse("trust agency Gulf Manpower"))
        assertEquals(Cmd.Trust("fakejobs.com", false), JobCommands.parse("block fakejobs.com"))
        assertEquals(Cmd.Status, JobCommands.parse("how many applications have I sent today"))
        assertEquals(Cmd.Replies, JobCommands.parse("any job replies?"))
        assertEquals(Cmd.LinkedIn("Kazakhstan"), JobCommands.parse("check LinkedIn jobs for Kazakhstan"))
    }

    @Test fun leavesOtherThingsAlone() {
        assertNull(JobCommands.parse("find jobs on Naukri"))
        assertNull(JobCommands.parse("send a message to Atiq"))
        assertNull(JobCommands.parse("what jobs suit me"))
        assertNull(JobCommands.parse("block this number"))
    }
}
