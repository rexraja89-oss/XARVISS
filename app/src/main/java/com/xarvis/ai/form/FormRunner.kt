package com.xarvis.ai.form

import android.content.Context
import com.xarvis.ai.agent.PhoneAgent
import com.xarvis.ai.agent.screenFrom
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.jobs.JobKind
import com.xarvis.ai.jobs.JobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** "Fill this form" on this phone: Rex's CV (from the Job Hunter) + XARVIS Hands + the brain ([FormFill]). */
object FormRunner {
    @Volatile private var running = false

    suspend fun fillHere(context: Context, brain: suspend (String, String) -> String, report: (String) -> Unit): String {
        val hands = HandsBridge.service
            ?: return "I need XARVIS Hands to fill forms, sir: ☰ → XARVIS Hands → switch it on." +
                if (HandsBridge.builtIn) "" else " (It's the S22's job: say \"fill the form on my S22\".)"
        val store = JobStore(context)
        val kind = store.cvFor(JobKind.SUPERVISOR)
            ?: return "I don't have your CV yet, sir. Add it once in ☰ → 💼 Job Hunter (CVs), then ask me again."
        if (running) return "I'm already filling a form, sir."
        running = true
        try {
            val cv = store.cvText(kind)
            val p = store.profile(kind)
            val details = buildString {
                if (p.name.isNotBlank()) append("Name: ").append(p.name).append('\n')
                if (p.email.isNotBlank()) append("Email: ").append(p.email).append('\n')
                if (p.phones.isNotEmpty()) append("Phone: ").append(p.phones.joinToString(" / ")).append('\n')
                if (p.summary.isNotBlank()) append("Profile summary: ").append(p.summary).append('\n')
            }
            // Typed into XARVIS itself? Step back to the form underneath first.
            if (frontIsXarvis(hands, context)) {
                withContext(Dispatchers.Main) { hands.globalAction("back") }
                delay(1_300)
                if (frontIsXarvis(hands, context)) {
                    return "Open the form first, sir, then say \"Xarvis, fill this form\" while it's on screen."
                }
            }
            val env = object : FormFill.Env {
                private var h = 0
                override suspend fun screen(): PhoneAgent.Screen =
                    withContext(Dispatchers.IO) { screenFrom(hands.screenElements()).also { h = it.height } }
                override suspend fun setField(x: Int, y: Int, text: String): Boolean = withContext(Dispatchers.Main) {
                    if (hands.setField(x, y, text)) return@withContext true
                    // No direct way in (the separate Hands app): tap the box, then type.
                    hands.gesture("tap", x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60)
                    delay(350)
                    hands.typeText(text, false)
                }
                override suspend fun scrollDown() = withContext(Dispatchers.Main) {
                    val hh = (h.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels).toFloat()
                    val w = context.resources.displayMetrics.widthPixels / 2f
                    hands.gesture("swipe", w, hh * 0.72f, w, hh * 0.34f, 450).let { }
                }
                override suspend fun think(system: String, prompt: String) = brain(system, prompt)
                override fun progress(line: String) = report(line)
                override suspend fun pause(ms: Long) = delay(ms)
            }
            report("✍ Filling the form from your CV…")
            return FormFill.summary(FormFill.run(env, cv, details))
        } finally {
            running = false
        }
    }

    private suspend fun frontIsXarvis(hands: HandsBridge.Hands, context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching { JSONObject(hands.screenElements()).optString("pkg") == context.packageName }.getOrDefault(false)
    }
}
