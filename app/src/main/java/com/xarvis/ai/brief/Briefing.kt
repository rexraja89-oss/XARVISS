package com.xarvis.ai.brief

import android.content.Context
import com.xarvis.ai.tools.BatteryTool
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Assembles the morning briefing from the free sources XARVIS has: time of day, date, weather, battery,
 * today's calendar (iCal) and unread email (IMAP). Every part is best-effort — if one can't be read it's
 * simply left out, so the briefing always produces something sensible.
 */
object Briefing {

    suspend fun build(
        context: Context,
        email: String?, appPassword: String?, icalUrl: String?, city: String?,
    ): String {
        val sb = StringBuilder()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        sb.append("$part, sir. ")
        sb.append("It's ").append(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())).append(". ")

        if (!city.isNullOrBlank()) {
            val w = Weather.summary(city)
            if (w.isNotBlank()) sb.append("Weather in $city: $w. ")
        }

        runCatching {
            val bat = BatteryTool(context).read()
            bat.substringBefore(".").trim().removePrefix("Battery:").trim()
                .takeIf { it.isNotBlank() }?.let { sb.append("Battery is $it. ") }
        }

        if (!icalUrl.isNullOrBlank()) {
            val events = ICal.today(icalUrl)
            if (events.isEmpty()) sb.append("Nothing on your calendar today. ")
            else sb.append("Today you have ${events.size} thing${if (events.size == 1) "" else "s"}: ")
                .append(events.take(6).joinToString("; ") { ICal.format(it) }).append(". ")
        }

        if (!email.isNullOrBlank() && !appPassword.isNullOrBlank()) {
            val inbox = ImapMail.unread(email, appPassword)
            when {
                inbox.error != null -> sb.append("(Couldn't read email: ${inbox.error}.) ")
                inbox.unread == 0 -> sb.append("Your inbox is clear. ")
                else -> {
                    sb.append("You have ${inbox.unread} unread email${if (inbox.unread == 1) "" else "s"}")
                    if (inbox.items.isNotEmpty()) sb.append(", including: ").append(inbox.items.joinToString("; "))
                    sb.append(". ")
                }
            }
        }

        sb.append("That's your briefing, sir.")
        return sb.toString().trim()
    }
}
