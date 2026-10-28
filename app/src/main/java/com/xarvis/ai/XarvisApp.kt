package com.xarvis.ai

import android.app.Application
import com.xarvis.ai.service.AlwaysOn
import com.xarvis.ai.service.XarvisService

class XarvisApp : Application() {
    val core: XarvisCore by lazy { XarvisCore(this) }

    override fun onCreate() {
        super.onCreate()
        core // Start the link server and model load with the process, not the first screen.
        if (AlwaysOn.isEnabled(this)) XarvisService.start(this)
        com.xarvis.ai.brief.BriefingSchedule.schedule(this) // (re)arm the daily briefing alarm
        com.xarvis.ai.remind.Reminders.reschedule(this) // (re)arm any pending reminders
        com.xarvis.ai.remind.Nudges.schedulePoll(this) // (re)start the proactive nudge poll
    }
}
