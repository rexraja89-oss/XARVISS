package com.xarvis.ai.jobs

/**
 * The application email, written on the phone from Rex's own CV (read from the PDF he picked, never sent to a
 * cloud brain): his profile paragraph, up to three lines of his CV that answer this ad's requirements
 * (offshore → his offshore projects, shutdown → his shutdowns…), and his signature.
 */
object JobLetter {

    /** What the letters need from a CV; Rex can correct any of it on the Job Hunter screen. */
    data class Profile(
        val name: String,
        val email: String,
        val phones: List<String>,
        val summary: String,
        /** The CV's lines, for picking the ones that match an ad. */
        val lines: List<String>,
    )

    private val EMAIL = Regex("""(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""")
    private val PHONE = Regex("""\+\d{1,3}[\d\s-]{7,16}\d""")
    private val HEADING = Regex("""(?i)^\s*(professional\s+)?(profile|summary|objective|about\s+me)\s*:?\s*$""")
    private val NEXT_HEADING = Regex(
        """(?i)^\s*(professional\s+)?(experience|employment|work\s+history|skills|education|training|technical\s+expertise|key\s+projects|projects|personal\s+details|contact|languages|certifications?|selected\s+project|site\s+access|coating\s+systems|insulation|computer)\b.*$""",
    )

    /** Reads name, contacts, the profile paragraph and the lines out of a CV's text. */
    fun profileFrom(cvText: String): Profile {
        val lines = cvText.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val name = lines.take(8).firstOrNull { l ->
            val words = l.split(' ')
            words.size in 2..4 && words.all { w -> w.length >= 2 && w.all { it.isLetter() || it == '.' || it == '-' } } &&
                !Regex("(?i)supervisor|superintendent|painting|resume|curriculum|cv\\b").containsMatchIn(l)
        }?.let(::titleCase).orEmpty()
        val email = EMAIL.find(cvText)?.value.orEmpty()
        val phones = PHONE.findAll(cvText).map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinctBy { it.filter(Char::isDigit) }.take(3).toList()
        val start = lines.indexOfFirst { HEADING.matches(it) }
        val summary = if (start >= 0) {
            lines.drop(start + 1).takeWhile { !NEXT_HEADING.matches(it) && !HEADING.matches(it) }.joinToString(" ").take(800)
        } else {
            ""
        }
        return Profile(name, email, phones, summary.trim(), cvLines(lines))
    }

    /** CV text broken into readable lines (bullets and over-long lines split into sentences). */
    private fun cvLines(lines: List<String>): List<String> = lines
        .flatMap { it.split(Regex("""\s*[•▪◆■]\s*""")) }
        .flatMap { if (it.length > 230) it.split(Regex("""(?<=[.;])\s+""")) else listOf(it) }
        .map { it.trim().trimStart('-', '–', '*').trim() }
        .filter { it.length in 30..230 }
        // Not headings ("TRAINING & WORKFORCE DEVELOPMENT", the title line under the name).
        .filter { l -> val letters = l.filter(Char::isLetter); letters.isEmpty() || letters.count(Char::isUpperCase) < letters.length * 0.6 }

    private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Ad requirement → the CV lines that answer it. */
    private val TOPICS = listOf(
        Regex("""(?i)offshore|platform|jacket|fpso|marine|vessel|helideck""") to Regex("""(?i)offshore|platform|jacket|helideck|marine"""),
        Regex("""(?i)refiner|petrochem|gas\s+plant|lng|gtl|process\s+plant|oil\s*&?\s*gas""") to Regex("""(?i)refiner|gtl|petrochem|dolphin|adnoc|lng"""),
        Regex("""(?i)shut\s*down|turnaround|outage|maintenance""") to Regex("""(?i)shut\s*down|outage"""),
        Regex("""(?i)insulat|cryogenic|cladding""") to Regex("""(?i)insulat|cryogenic"""),
        Regex("""(?i)fire\s*proof|pfp|intumescent|cementitious""") to Regex("""(?i)fire\s*proof|intumescent|cementitious"""),
        Regex("""(?i)nuclear|power\s+(plant|station)""") to Regex("""(?i)nuclear|barakah"""),
        Regex("""(?i)fertili[sz]er|ammonia|urea""") to Regex("""(?i)fertili[sz]er|ammonia|urea"""),
        Regex("""(?i)\btsa\b|thermal\s+spray|zinc|metali[sz]""") to Regex("""(?i)\btsa\b|thermal\s+spray|zinc"""),
        Regex("""(?i)train|instruct|coach|mentor|toolbox|competenc""") to Regex("""(?i)toolbox|coach|demonstrat|instruct|train"""),
        Regex("""(?i)tank|pipeline|piping|spool""") to Regex("""(?i)tanks?|piping|pipe\s+spools"""),
        Regex("""(?i)blast|surface\s+prep""") to Regex("""(?i)blast|surface\s+prep"""),
    )

    /** Up to [max] of Rex's CV lines that answer what [adText] asks for, most specific first. */
    fun highlights(profile: Profile, adText: String, max: Int = 3): List<String> {
        val picked = LinkedHashSet<String>()
        for ((need, has) in TOPICS) {
            if (picked.size >= max) break
            if (!need.containsMatchIn(adText)) continue
            profile.lines.firstOrNull { has.containsMatchIn(it) && it !in picked }?.let { picked += it }
        }
        return picked.toList()
    }

    /** The subject and body for [job] ([adText]: the ad, for the highlights). */
    fun write(job: JobItem, adText: String, profile: Profile): Pair<String, String> {
        val title = job.title.replace(Regex("\\s+"), " ").trim().take(90)
        val name = profile.name.ifBlank { "Applicant" }
        val subject = "Application: $title – $name"
        val where = buildString {
            if (job.company.isNotBlank()) append(" with ${job.company}")
            val place = job.location.ifBlank { job.country }
            if (place.isNotBlank() && place != "Europe") append(" in $place")
        }
        val seen = job.source.takeIf { it.isNotBlank() && !it.startsWith("Web") }?.let { ", as advertised on $it" }.orEmpty()
        val hl = highlights(profile, adText)
        val body = buildString {
            append("Dear Hiring Manager,\n\n")
            append("I would like to apply for the $title position$where$seen.\n\n")
            if (profile.summary.isNotBlank()) append(profile.summary.trim()).append("\n\n")
            if (hl.isNotEmpty()) {
                append("Relevant to this role:\n")
                hl.forEach { append("• ").append(it.trimEnd('.')).append(".\n") }
                append('\n')
            }
            append("I am open to rotation or long-term assignments, onshore or offshore, and can discuss joining dates at your convenience. ")
            append("My CV is attached.\n\n")
            append("Kind regards,\n").append(name).append('\n')
            if (profile.phones.isNotEmpty()) append(profile.phones.joinToString(" | ")).append('\n')
            if (profile.email.isNotBlank()) append(profile.email).append('\n')
        }
        return subject to body.trimEnd() + "\n"
    }
}
