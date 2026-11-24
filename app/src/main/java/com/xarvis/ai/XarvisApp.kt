package com.xarvis.ai

import android.app.Application
import com.xarvis.ai.service.AlwaysOn
import com.xarvis.ai.service.XarvisService

class XarvisApp : Application() {
    val core: XarvisCore by lazy { XarvisCore(this) }

    override fun onCreate() {
        super.onCreate()
        com.xarvis.ai.mission.CrashLog.install(this) // keep XARVIS's own crashes for Claude's missions
        // Android 15+: keep the plain screens clear of the status and navigation bars (the chat handles them itself).
        com.xarvis.ai.view.BarInsets.install(this, skip = setOf(MainActivity::class.java.name))
        core // Start the link server and model load with the process, not the first screen.
        if (AlwaysOn.isEnabled(this)) XarvisService.start(this)
        com.xarvis.ai.brief.BriefingSchedule.schedule(this) // (re)arm the daily briefing alarm
        com.xarvis.ai.remind.Reminders.reschedule(this) // (re)arm any pending reminders
        com.xarvis.ai.remind.Nudges.schedulePoll(this) // (re)start the proactive nudge poll
        com.xarvis.ai.task.NightShiftRunner.schedule(this) // (re)arm the night shift
        com.xarvis.ai.task.NightShiftRunner.runDue(this) // a job whose time passed while XARVIS was restarting
    }
}
