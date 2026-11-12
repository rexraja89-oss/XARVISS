package com.xarvis.ai.task

import android.content.Context
import org.json.JSONObject

/**
 * Task mode's practice ladder (Rex, Nov 2026: "train it with small multi-step tasks first, then slowly increase the
 * steps"; his first rung: open Ninja VPN, connect, let the ads finish, report Connected). Harmless jobs only — nothing
 * is sent, posted or bought. A task passes after [PASSES] clean runs (finished with no re-plan and no help from Rex);
 * a level opens when every task below it has passed. What it learns on the way goes into the LessonBook like any task.
 */
object Practice {
    /** [app]: the one app a simple drill happens in (it then runs as one step, without a planning call). */
    data class Drill(val id: String, val level: Int, val goal: String, val app: String? = null)

    data class Score(val runs: Int, val clean: Int) {
        val passed: Boolean get() = clean >= PASSES
    }

    const val PASSES = 2

    val ladder = listOf(
        Drill("vpn", 1, "Open Ninja VPN and connect it. Wait for any ads to finish and close them. When it shows it's connected, tell me \"Ninja VPN is connected\".", "Ninja VPN"),
        Drill("search", 1, "Open Chrome, search for \"Mars facts\" and open the first result. Tell me the page's title.", "Chrome"),
        Drill("timer", 1, "Open the Clock app, start a 1-minute timer, then cancel it. Tell me when it's cancelled.", "Clock"),
        Drill("note", 2, "Find 3 short facts about Mars in Chrome and save them in a new note called \"Mars facts\" (Samsung Notes, or a text file if there's no notes app)."),
        Drill("titles", 2, "Open YouTube, search \"how to make YouTube Shorts\", and save the titles of the top 3 videos in a file called shorts-titles.txt."),
        Drill("script", 3, "Write a 30-second YouTube Short script about Mars and save it. Then open CapCut, start a new project and add the script as text. Don't export."),
        Drill("picture", 3, "Make one picture of a red planet over a desert with a free AI image website (no payment, no sign-up that needs a password) and save it to the gallery."),
        Drill("draft", 4, "Make a 30-second YouTube Short about Mars as a CapCut draft: script, 3–5 AI pictures, an AI voice-over and captions. Export it to the gallery. Don't upload it."),
        Drill("private", 5, "Make a 30-second YouTube Short about Mars (script, AI pictures, voice-over, captions in CapCut), export it and upload it to YouTube as PRIVATE with a title, description and #shorts."),
    )

    /** The lowest level not fully passed: everything up to it can be practised. */
    fun openLevel(scores: Map<String, Score>): Int {
        for (level in ladder.map { it.level }.distinct().sorted()) {
            if (ladder.filter { it.level == level }.any { scores[it.id]?.passed != true }) return level
        }
        return ladder.maxOf { it.level }
    }

    /** A clean run: finished, no new plan needed, Rex didn't have to help. */
    fun clean(outcome: TaskRunner.Outcome?): Boolean = outcome != null && outcome.finished && outcome.replans == 0 && outcome.asked == 0

    fun scores(context: Context): Map<String, Score> {
        val o = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return ladder.associate { d -> d.id to (o.optJSONObject(d.id)?.let { Score(it.optInt("runs"), it.optInt("clean")) } ?: Score(0, 0)) }
    }

    fun record(context: Context, id: String, outcome: TaskRunner.Outcome?) {
        val o = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val s = o.optJSONObject(id) ?: JSONObject()
        s.put("runs", s.optInt("runs") + 1)
        if (clean(outcome)) s.put("clean", s.optInt("clean") + 1)
        o.put(id, s)
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    fun reset(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    private const val KEY = "practice"
}
