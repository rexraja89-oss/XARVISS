package com.xarvis.ai.aware

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JARVIS-style awareness (13 Nov 2026, Rex: "I want Jarvis of Iron Man like awareness… it knows everything going on
 * on the benco, the S22, a future laptop… situational awareness"). Every XARVIS keeps a small status [Card] of its
 * device and swaps it with the others ([Board]), so each one knows the state of all of them: status reports in
 * seconds, the brain answering device questions from live facts, and watchers that speak up by themselves.
 * Pure (no Android), unit-tested.
 */
object Situation {
    /** One notification seen on a device (S22: through XARVIS Hands). Codes are masked before it's kept. */
    data class Note(val app: String, val title: String, val text: String, val at: Long) {
        val missedCall: Boolean get() = MISSED_CALL.containsMatchIn("$title $text")

        fun toJson(): JSONObject = JSONObject().put("a", app).put("t", title).put("x", text).put("at", at)

        companion object {
            fun from(o: JSONObject) = Note(o.optString("a"), o.optString("t"), o.optString("x"), o.optLong("at"))
        }
    }

    /** A device's state at [at]. Unknown numbers are -1 / null. */
    data class Card(
        val id: String,
        val name: String,
        val at: Long,
        val app: String = "",
        val battery: Int = -1,
        val charging: Boolean = false,
        val tempC: Double? = null,
        /** "wifi", "mobile" or "none". */
        val net: String = "",
        val screenOn: Boolean = false,
        val locked: Boolean = true,
        /** When someone last used it (unlocked it, or turned the screen on while unlocked). */
        val usedAt: Long = 0,
        val unlockedAt: Long = 0,
        /** The app in front, while the screen is on. */
        val front: String = "",
        val lat: Double? = null,
        val lon: Double? = null,
        val locAt: Long = 0,
        /** "Gemma ready", "cloud brain"… */
        val brain: String = "",
        /** What XARVIS is busy with there ("task: …", "guard armed", "night shift 23:45"…). */
        val doing: List<String> = emptyList(),
        val freeGb: Double? = null,
        val notes: List<Note> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("v", 1).put("id", id).put("n", name).put("at", at).put("app", app)
            put("b", battery).put("c", charging)
            tempC?.let { put("tc", it) }
            put("net", net).put("on", screenOn).put("lk", locked).put("u", usedAt).put("ul", unlockedAt).put("fr", front)
            if (lat != null && lon != null) put("la", lat).put("lo", lon).put("lat", locAt)
            put("br", brain).put("do", JSONArray(doing))
            freeGb?.let { put("gb", it) }
            put("nt", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        }

        companion object {
            fun from(o: JSONObject): Card = Card(
                id = o.optString("id"),
                name = o.optString("n"),
                at = o.optLong("at"),
                app = o.optString("app"),
                battery = o.optInt("b", -1),
                charging = o.optBoolean("c"),
                tempC = if (o.has("tc")) o.optDouble("tc") else null,
                net = o.optString("net"),
                screenOn = o.optBoolean("on"),
                locked = o.optBoolean("lk", true),
                usedAt = o.optLong("u"),
                unlockedAt = o.optLong("ul"),
                front = o.optString("fr"),
                lat = if (o.has("la")) o.optDouble("la") else null,
                lon = if (o.has("lo")) o.optDouble("lo") else null,
                locAt = o.optLong("lat"),
                brain = o.optString("br"),
                doing = o.optJSONArray("do")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                freeGb = if (o.has("gb")) o.optDouble("gb") else null,
                notes = o.optJSONArray("nt")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Note::from) } }.orEmpty(),
            )

            fun parse(text: String): Card? = runCatching { from(JSONObject(text)) }.getOrNull()?.takeIf { it.id.isNotBlank() }
        }
    }

    /** A device is "lost" when nothing was heard from it for this long (cards come every 15 minutes). */
    const val LOST_MS = 40 * 60_000L
    const val LOW_BATTERY = 15
    const val CRITICAL_BATTERY = 5
    const val HOT_C = 45.0
    const val LOW_STORAGE_GB = 2.0
    /** Rex counts as "with" a phone he used this recently. */
    const val WITH_REX_MS = 30 * 60_000L
    /** Locations older than this aren't compared. */
    private const val LOC_FRESH_MS = 3 * 3_600_000L
    /** In a card's [Card.doing] while XARVIS's remote view is controlling that phone. */
    const val CONTROLLED_BY = "controlled by"
    const val NOTES_KEPT = 10
    const val NOTES_FOR_MS = 6 * 3_600_000L

    private val MISSED_CALL = Regex("""(?i)\bmissed\s+(?:voice\s+|video\s+|audio\s+)?calls?\b""")
    private val CODE_WORDS = Regex("""(?i)\b(?:code|otp|one[- ]time|verification|verify|pin|password|passcode)\b""")
    private val DIGITS = Regex("""\b\d[\d\s-]{2,9}\d\b""")

    /** A notification as XARVIS keeps it: codes masked, short. Null for nothing worth keeping. */
    fun note(app: String, title: String, text: String, at: Long): Note? {
        val t = title.trim().take(60)
        var x = text.replace('\n', ' ').trim()
        if (t.isEmpty() && x.isEmpty()) return null
        if (CODE_WORDS.containsMatchIn("$t $x")) x = DIGITS.replace(x, "••••")
        return Note(app.trim().take(30), t, x.take(120), at)
    }

    /** Adds [n] to [notes]: newest first, the same one only once, at most [NOTES_KEPT] from the last [NOTES_FOR_MS]. */
    fun addNote(notes: List<Note>, n: Note): List<Note> =
        (listOf(n) + notes.filterNot { it.app == n.app && it.title == n.title && it.text == n.text })
            .filter { n.at - it.at <= NOTES_FOR_MS }
            .take(NOTES_KEPT)

    // ---- words ----

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h${if (m % 60 >= 5) " ${m % 60} min" else ""} ago"
            else -> "${m / (24 * 60)} day${if (m >= 48 * 60) "s" else ""} ago"
        }
    }

    fun clock(at: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }.format(Date(at))

    /** Kilometres between the two devices, when both know where they were in the last 3 hours. */
    fun distanceKm(a: Card, b: Card, now: Long): Double? {
        if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) return null
        if (now - a.locAt > LOC_FRESH_MS || now - b.locAt > LOC_FRESH_MS) return null
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /**
     * Whether [other] is away from [me]: by location when both are known (> 1 km), else true when this phone is on
     * mobile data and the other on Wi-Fi (Rex at the plant on 4G, the S22 at home), else unknown (null).
     */
    fun apart(me: Card, other: Card, now: Long): Boolean? {
        distanceKm(me, other, now)?.let { return it > 1.0 }
        if (me.net == "mobile" && other.net == "wifi") return true
        return null
    }

    private fun battery(c: Card): String = when {
        c.battery < 0 -> ""
        c.charging -> "🔋 ${c.battery}% ⚡ charging"
        else -> "🔋 ${c.battery}%"
    }

    private fun network(c: Card): String = when (c.net) {
        "wifi" -> "🌐 Wi-Fi"
        "mobile" -> "📶 mobile data"
        "none" -> "⛔ no internet"
        else -> ""
    }

    private fun screen(c: Card, now: Long): String {
        val state = when {
            !c.screenOn -> "screen off"
            c.locked -> "🔒 locked"
            c.front.isNotBlank() -> "in use: ${c.front}"
            else -> "in use"
        }
        val used = if (c.usedAt > 0 && !(c.screenOn && !c.locked)) " · last used ${ago(now - c.usedAt)}" else ""
        return state + used
    }

    private fun km(d: Double): String = if (d < 10) "%.1f km".format(Locale.US, d) else "${d.toInt()} km"

    /** The block for one device in a status report. */
    fun block(c: Card, me: Card?, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val here = me == null || me.id == c.id
        val title = if (here) "📱 ${c.name} (this phone)" else "📱 ${c.name} · seen ${ago(now - c.at)}"
        val lines = ArrayList<String>()
        lines += listOf(battery(c), network(c), if (here) "" else screen(c, now)).filter { it.isNotBlank() }.joinToString(" · ")
        val xarvis = listOf(if (c.app.isNotBlank()) "XARVIS v${c.app}" else "", c.brain).filter { it.isNotBlank() } + c.doing
        if (xarvis.isNotEmpty()) lines += xarvis.joinToString(" · ")
        val calls = c.notes.filter { it.missedCall }
        val others = c.notes.filterNot { it.missedCall }
        if (calls.isNotEmpty()) lines += "📞 Missed: " + calls.take(3).joinToString(", ") { "${it.title.ifBlank { it.app }} (${clock(it.at, zone)})" }
        if (others.isNotEmpty()) lines += "📩 ${others.size} notification${if (others.size > 1) "s" else ""}: " +
            others.take(3).joinToString(", ") { "${it.app}${if (it.title.isNotBlank()) " — ${it.title}" else ""}" }
        if (!here && me != null) distanceKm(me, c, now)?.let { lines += "📍 ${km(it)} from you" }
        c.tempC?.takeIf { it >= HOT_C }?.let { lines += "🌡 running hot: ${it.toInt()}°C" }
        c.freeGb?.takeIf { it < LOW_STORAGE_GB }?.let { lines += "💾 only %.1f GB free".format(Locale.US, it) }
        return title + lines.filter { it.isNotBlank() }.joinToString("") { "\n   $it" }
    }

    /** "Status report" — every device, this one last, plus anything that needs Rex. */
    fun report(me: Card, others: List<Card>, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder("🛰 Status report, sir — ${clock(now, zone)}\n")
        if (others.isEmpty()) sb.append("\nNo other device of yours has reported in yet.\n")
        for (c in others.sortedBy { it.name }) sb.append('\n').append(block(c, me, now, zone)).append('\n')
        sb.append('\n').append(block(me, me, now, zone)).append('\n')
        val notes = ArrayList<String>()
        for (c in others) {
            if (now - c.at > LOST_MS) notes += "📡 No word from the ${c.name} for ${ago(now - c.at).removeSuffix(" ago")}."
            if (c.app.isNotBlank() && me.app.isNotBlank() && c.app != me.app) {
                notes += "⬆ The ${c.name} runs v${c.app} and this phone v${me.app}."
            }
        }
        sb.append('\n').append(if (notes.isEmpty()) "All systems normal." else notes.joinToString("\n"))
        return sb.toString().trim()
    }

    /** What the brain gets for a question about Rex's devices: short, facts only. */
    fun brief(me: Card, others: List<Card>, now: Long, zone: TimeZone = TimeZone.getDefault(), max: Int = 900): String {
        fun facts(c: Card, here: Boolean): String {
            val f = ArrayList<String>()
            if (!here) f += "seen ${ago(now - c.at)}"
            if (c.battery >= 0) f += "battery ${c.battery}%" + if (c.charging) " charging" else " not charging"
            when (c.net) { "wifi" -> f += "on Wi-Fi"; "mobile" -> f += "on mobile data"; "none" -> f += "offline" }
            if (!here) f += screen(c, now)
            if (c.app.isNotBlank()) f += "XARVIS v${c.app}"
            if (c.brain.isNotBlank()) f += c.brain
            f += c.doing
            if (!here) distanceKm(me, c, now)?.let { f += "${km(it)} from this phone" }
            if (c.notes.isNotEmpty()) f += "recent notifications: " + c.notes.take(5).joinToString("; ") {
                "${it.app}${if (it.title.isNotBlank()) " ${it.title}" else ""}${if (it.text.isNotBlank()) ": \"${it.text.take(60)}\"" else ""} (${clock(it.at, zone)})"
            }
            return f.joinToString(", ")
        }
        val sb = StringBuilder(
            "Rex's devices right now (live from XARVIS, ${clock(now, zone)}; for a device not seen \"just now\", " +
                "say how old the facts are):",
        )
        for (c in others) sb.append("\n- ${c.name}: ").append(facts(c, false))
        sb.append("\n- ${me.name} (this phone, where Rex is typing): ").append(facts(me, true))
        return sb.toString().take(max)
    }

    /** "What's new on my S22" — its recent notifications. */
    fun notesReport(c: Card, now: Long, zone: TimeZone = TimeZone.getDefault()): String =
        if (c.notes.isEmpty()) "📩 Nothing new on the ${c.name} (seen ${ago(now - c.at)})."
        else "📩 On the ${c.name}:\n" + c.notes.joinToString("\n") {
            "• ${clock(it.at, zone)} ${if (it.missedCall) "📞 " else ""}${it.app}${if (it.title.isNotBlank()) " — ${it.title}" else ""}" +
                if (it.text.isNotBlank() && !it.missedCall) ": ${it.text}" else ""
        }

    // ---- watchers ----

    /**
     * What XARVIS says by itself when [cur] arrives from another device whose last card was [prev] ([me]: this
     * phone now). Nothing on the first card from a device (no history to compare).
     */
    fun alerts(me: Card, prev: Card?, cur: Card, now: Long, zone: TimeZone = TimeZone.getDefault()): List<String> {
        if (prev == null) return emptyList()
        val out = ArrayList<String>()
        val n = cur.name
        if (cur.battery in 0..CRITICAL_BATTERY && !cur.charging && (prev.battery > CRITICAL_BATTERY || prev.charging)) {
            out += "🔋 Sir, the $n is down to ${cur.battery}% and not charging — it will switch off soon."
        } else if (cur.battery in 0..LOW_BATTERY && !cur.charging && (prev.battery > LOW_BATTERY || prev.charging)) {
            out += "🔋 Sir, the $n is at ${cur.battery}% and not charging."
        }
        if ((cur.tempC ?: 0.0) >= HOT_C && (prev.tempC ?: 0.0) < HOT_C) {
            out += "🌡 Sir, the $n is running hot: ${cur.tempC!!.toInt()}°C."
        }
        // Unlocked while Rex is busy with this phone somewhere else. Not during XARVIS's own remote session; XARVIS
        // Remote's can't be seen from here, hence the "if that wasn't you" (13 Nov 2026).
        if (cur.unlockedAt > prev.unlockedAt && now - cur.unlockedAt < 10 * 60_000L &&
            now - me.usedAt < WITH_REX_MS && apart(me, cur, now) == true && cur.doing.none { it.contains(CONTROLLED_BY) }
        ) {
            val where = distanceKm(me, cur, now)?.let { " — you're about ${km(it)} away" } ?: " while you're away from it"
            out += "🔓 Sir, the $n was just unlocked (${clock(cur.unlockedAt, zone)})$where. If that wasn't you through the remote view, someone is using it."
        }
        val seen = prev.notes.map { it.at to it.title }.toSet()
        // New since the last card (a notification can show up a little late; older ones aren't news any more).
        cur.notes.filter { it.missedCall && (it.at to it.title) !in seen && it.at > prev.at - 30 * 60_000L }
            .forEach { out += "📞 Missed call on the $n: ${it.title.ifBlank { it.app }} (${clock(it.at, zone)})." }
        if ((cur.freeGb ?: 99.0) < LOW_STORAGE_GB && (prev.freeGb ?: 99.0) >= LOW_STORAGE_GB) {
            out += "💾 Sir, the $n has only %.1f GB of storage left.".format(Locale.US, cur.freeGb)
        }
        return out
    }

    fun lostLine(c: Card, now: Long): String =
        "📡 I've lost contact with the ${c.name}, sir — last seen ${ago(now - c.at)}" +
            (if (c.battery >= 0) " at ${c.battery}%${if (c.charging) ", charging" else ""}" else "") + "."

    fun backLine(c: Card): String =
        "📡 The ${c.name} is back in touch" + (if (c.battery >= 0) " (${c.battery}%${if (c.charging) ", charging" else ""})" else "") + "."
}
