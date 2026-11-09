package com.xarvis.ai.jobs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * XARVIS Job Hunter (Rex asked, Nov 2026): finds Blasting & Painting Supervisor / Superintendent / Instructor /
 * Trainer jobs in his 33 countries, keeps only ones with an application email published in the ad (or found on
 * the company's own site), checks each for scams, writes the application from his CV, and — only after he
 * approves — sends it from his Gmail with the right CV attached. Hunting and sending run only when he asks.
 *
 * Sources: Tavily web search (with each page's text), Jooble listings, LinkedIn job-alert emails in his Gmail
 * (Loop 1), public LinkedIn posts through search (Loop 2), and company careers emails for jobs that have none
 * (Loop 3). Reading public ads may use the cloud brain; his CV and letters stay on the phone.
 */
object JobHunter {

    /** What the hunter needs from the rest of XARVIS (set by XarvisCore). */
    interface Env {
        /** A line in XARVIS's chat. */
        fun post(text: String)
        /** A notification (and spoken when [speak]). */
        fun notify(title: String, text: String, speak: Boolean = false)
        /** A brain for reading public job ads: the cloud when Rex has a key, else this phone's Gemma; null: none. */
        suspend fun brain(system: String, text: String): String?
    }

    @Volatile var env: Env? = null

    data class Progress(val busy: Boolean = false, val line: String = "")

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var huntJob: Job? = null
    @Volatile private var sendJob: Job? = null

    fun hunting(): Boolean = huntJob?.isActive == true
    fun sending(): Boolean = sendJob?.isActive == true

    /** How many countries a quick hunt covers (from the top of Rex's list); "full" covers all 33. */
    const val QUICK_COUNTRIES = 10
    private const val SEND_GAP_MS = 120_000L
    private val SUPERVISOR_WORDS = "blasting painting supervisor"

    private fun say(line: String) { _progress.value = Progress(true, line) }

    // ---- hunting ----

    /** Starts a hunt in the background; returns what XARVIS tells Rex right away. */
    fun startHunt(context: Context, country: String?, full: Boolean): String {
        val app = context.applicationContext
        if (hunting()) return "I'm already hunting, sir — ${_progress.value.line}"
        val keys = com.xarvis.ai.llm.BrainKeys(app)
        if (!keys.has(com.xarvis.ai.llm.BrainKeys.TAVILY) && !keys.has(com.xarvis.ai.llm.BrainKeys.JOOBLE)) {
            return "I need your Tavily or Jooble key first, sir: ☰ → BRAIN → Job Hunter keys."
        }
        val place = country?.let { Countries.byName(it) ?: return "\"$it\" isn't one of your 33 countries, sir." }
        val targets = when {
            place != null -> listOf(place)
            full -> Countries.ranked
            else -> Countries.top(QUICK_COUNTRIES)
        }
        huntJob = scope.launch {
            val summary = runCatching { hunt(app, targets, single = place != null, full = full) }
                .getOrElse { "The job hunt stopped: ${it.message ?: it.javaClass.simpleName}" }
            _progress.value = Progress(false, summary)
            env?.post("💼 $summary")
            env?.notify("Job Hunter", summary)
        }
        val what = place?.name ?: if (full) "all 33 countries" else "your top $QUICK_COUNTRIES countries (${targets.first().name} to ${targets.last().name})"
        return "Hunting in $what, sir. I'll tell you when it's done — then say \"show my job drafts\"."
    }

    private suspend fun hunt(app: Context, targets: List<Country>, single: Boolean, full: Boolean): String {
        val store = JobStore(app)
        val keys = com.xarvis.ai.llm.BrainKeys(app)
        val tavily = keys.get(com.xarvis.ai.llm.BrainKeys.TAVILY)
        val jooble = keys.get(com.xarvis.ai.llm.BrainKeys.JOOBLE)
        val gmail = keys.get("brief_email")
        val gmailPw = keys.get("brief_apppw")
        val raw = ArrayList<Pair<RawJob, Country?>>()
        val notes = ArrayList<String>()

        fun tavilyLeft() = JobStore.TAVILY_MONTH - JobStore.TAVILY_RESERVE - store.tavilyUsed()

        suspend fun search(query: String, country: Country?, domains: List<String> = emptyList(), label: String = "Web") {
            if (tavily == null || tavilyLeft() <= 0) return
            val hits = runCatching { JobSources.tavily(tavily, query, domains = domains) }
                .onFailure { notes += "Tavily: ${it.message?.take(80)}" }.getOrDefault(emptyList())
            store.addTavily()
            hits.forEach { h ->
                val src = if (label == "Web") siteName(h.url) else label
                raw += RawJob(h.title, guessCompany(h.title, h.url), "", "", h.url, src, h.text.ifBlank { h.snippet }) to country
            }
        }

        // 1. Web search, country by country in Rex's order.
        for ((i, c) in targets.withIndex()) {
            say("Searching ${c.name} (${i + 1}/${targets.size})…")
            search("$SUPERVISOR_WORDS job vacancy ${c.searchName} send CV email", c)
            if (single || full) search("blasting painting instructor trainer job ${c.searchName} email CV", c)
            if (single) search("coating insulation superintendent job ${c.searchName} apply email", c)
        }
        // 2. Recruiter posts on LinkedIn (public, no login) — Loop 2.
        say("Reading recruiters' LinkedIn posts…")
        val where = if (single) " ${targets.first().searchName}" else ""
        search("hiring blasting painting supervisor$where send CV", if (single) targets.first() else null, listOf("linkedin.com"), "LinkedIn post")
        if (full || single) search("urgent requirement painting coating supervisor trainer$where email CV", if (single) targets.first() else null, listOf("linkedin.com"), "LinkedIn post")

        // 3. Jooble listings (its own allowance), then each relevant ad's page for the email (free).
        if (jooble != null) {
            val joobleCountries = targets.take(if (single) 1 else if (full) 15 else 8)
            for (c in joobleCountries) {
                if (store.joobleUsed() >= JobStore.JOOBLE_LIMIT) { notes += "Jooble allowance used up"; break }
                say("Jooble: ${c.name}…")
                val jobs = runCatching { JobSources.jooble(jooble, SUPERVISOR_WORDS, c.searchName) }
                    .onFailure { notes += "Jooble: ${it.message?.take(80)}" }.getOrDefault(emptyList())
                store.addJooble()
                jobs.filter { JobChecks.relevant(it.title) }.take(6).forEach { j ->
                    raw += RawJob(j.title, j.company, j.location, j.salary, j.link, "Jooble" + (j.source.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), j.snippet) to c
                }
            }
        }

        // 4. LinkedIn job-alert emails in Rex's Gmail — Loop 1.
        if (!gmail.isNullOrBlank() && !gmailPw.isNullOrBlank()) {
            say("Reading your LinkedIn job alerts…")
            runCatching { GmailIo.linkedInAlerts(gmail, gmailPw) }
                .onFailure { notes += "LinkedIn alerts: ${it.message?.take(80)}" }
                .getOrDefault(emptyList())
                .flatMap { JobSources.parseLinkedInAlert(it.text) }
                .forEach { raw += it to null }
        }

        // 5. Keep Rex's kind of job; a brain reads the ads that don't say who's hiring.
        val relevant = raw.filter { (r, _) -> JobChecks.relevant(r.title, r.text) }
            .distinctBy { (r, _) -> r.url.substringBefore('?').ifBlank { r.title + r.company } }
        var notHis = raw.size - relevant.size
        say("Reading ${relevant.size} ads…")
        val read = enrich(relevant.map { it.first }).zip(relevant.map { it.second })

        // 6. Check each one.
        say("Checking ${read.size} jobs…")
        val rates = Money.liveRates()
        val existing = store.jobs().associateBy { it.id }
        val found = LinkedHashMap<String, JobItem>()
        var below = 0; var scams = 0; var applied = 0; var lookups = 0
        val mxCache = HashMap<String, Boolean?>()
        for ((r, searched) in read) {
            val text = (r.text + " " + r.salaryText).trim()
            val country = Countries.detect(r.location, r.title, text) ?: searched
            if (country == null) { notHis++; continue }
            if (single && country != targets.first()) continue
            // Jooble's snippet rarely has the email: read the ad itself (free).
            var adText = text
            if (r.source.startsWith("Jooble") && JobChecks.emails(adText).isEmpty() && r.url.isNotBlank()) {
                adText = (adText + "\n" + JobSources.pageText(r.url)).trim()
            }
            val salary = Money.parse(r.salaryText.ifBlank { adText })
            val inr = salary?.inrPerMonth(rates)
            if (inr != null && inr < Money.MIN_INR_MONTH) { below++; continue }
            var email = JobChecks.bestEmail(JobChecks.emails(adText), r.company, r.url)
            var source = r.source
            // Loop 3: no email in the ad → the company's own careers email.
            if (email == null && r.company.isNotBlank() && tavily != null && lookups < (if (full) 12 else 6) && tavilyLeft() > 0) {
                lookups++
                say("Looking for ${r.company}'s careers email…")
                val hits = runCatching { JobSources.tavily(tavily, "\"${r.company}\" careers recruitment email send CV", max = 5, timeRange = null) }
                    .getOrDefault(emptyList())
                store.addTavily()
                val words = r.company.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
                val companyMails = hits.flatMap { JobChecks.emails(it.text + " " + it.snippet) }
                    .filter { e -> !JobChecks.isFreeMail(e) && words.any { w -> JobChecks.domainOf(e).contains(w) } }
                email = JobChecks.bestEmail(companyMails, r.company)
                if (email != null) source = "$source + company site"
            }
            if (email != null && store.alreadyEmailed(email)) { applied++; continue }
            val works = email?.let { e -> mxCache.getOrPut(JobChecks.domainOf(e)) { JobSources.mailDomainWorks(JobChecks.domainOf(e)) } }
            val trust = JobChecks.trust(r.company, email, adText, inr, store.trusted(), store.blocked(), works)
            val warnings = JobChecks.warnings(adText)
            val kind = JobChecks.kind(r.title)
            val id = JobItem.idFor(r.title, r.company, email, r.url)
            if (id in existing || id in found) continue
            val salaryText = when {
                inr != null -> Money.inr(inr) + "/month" + (if (rates.isEmpty()) " ≈" else "") + " (" + r.salaryText.ifBlank { describe(salary) } + ")"
                else -> "salary not stated"
            }
            var item = JobItem(
                id = id, title = r.title.take(140), company = r.company, country = country.name, countryRank = country.rank,
                location = r.location.ifBlank { country.name }, salaryText = salaryText, salaryInr = inr, url = r.url, source = source,
                email = email, kind = kind, verdict = trust.verdict, reasons = trust.reasons, warnings = warnings,
                score = JobChecks.score(country.rank, trust.verdict, email != null, inr, warnings.size),
                status = when {
                    trust.verdict == Verdict.SCAM -> JobStatus.SKIPPED
                    email == null -> JobStatus.LINK
                    else -> JobStatus.NEW
                },
                snippet = adText.replace(Regex("\\s+"), " ").take(900),
            )
            if (trust.verdict == Verdict.SCAM) scams++
            if (item.status == JobStatus.NEW) item = withLetter(store, item, adText)
            found[id] = item
        }
        store.saveJobs(store.jobs() + found.values)
        store.lastHunt = System.currentTimeMillis()

        val drafts = found.values.filter { it.status == JobStatus.NEW }
        val links = found.values.count { it.status == JobStatus.LINK }
        val authentic = drafts.count { it.verdict == Verdict.AUTHENTIC }
        return buildString {
            append("Job hunt done: ${drafts.size} new drafts ($authentic ✅ authentic, ${drafts.size - authentic} ⚠️ to check)")
            if (links > 0) append(", $links to apply on the site yourself")
            append(".")
            val skipped = listOfNotNull(
                scams.takeIf { it > 0 }?.let { "$it 🚫 scams" },
                below.takeIf { it > 0 }?.let { "$it below ₹1.5 lakh" },
                applied.takeIf { it > 0 }?.let { "$it already applied" },
            )
            if (skipped.isNotEmpty()) append(" Skipped: ${skipped.joinToString(", ")}.")
            append(" Tavily ${store.tavilyUsed()}/${JobStore.TAVILY_MONTH} this month")
            if (jooble != null) append(", Jooble ${store.joobleUsed()}/500")
            append(".")
            if (store.cvName(JobKind.SUPERVISOR) == null && store.cvName(JobKind.TRAINER) == null) {
                append(" ⚠ Add your CVs in Job Hunter before sending.")
            }
            if (notes.isNotEmpty()) append(" (Problems: ${notes.distinct().take(3).joinToString("; ")})")
            if (drafts.isNotEmpty()) append(" Say \"show my job drafts\" when you're ready.")
        }
    }

    private const val EXTRACT_SYSTEM =
        "You read job advertisements. For each AD reply with ONLY a JSON array, one object per ad: " +
            "[{\"n\":1,\"company\":\"the hiring company or recruitment agency\",\"title\":\"the job title\",\"location\":\"city, country\"}]. " +
            "Use only what the ad says; use an empty string when it isn't stated. No other text."

    /** Fills in who's hiring (and where) for ads that don't say it in the title, with the brain; unchanged without one. */
    private suspend fun enrich(cands: List<RawJob>): List<RawJob> {
        val e = env ?: return cands
        val need = cands.withIndex().filter { it.value.company.isBlank() && it.value.text.length > 80 }.take(18)
        if (need.isEmpty()) return cands
        val out = cands.toMutableList()
        for (chunk in need.chunked(6)) {
            val prompt = chunk.mapIndexed { k, iv ->
                "AD ${k + 1}:\nTITLE: ${iv.value.title}\nURL: ${iv.value.url}\nTEXT: ${iv.value.text.replace(Regex("\\s+"), " ").take(1_500)}"
            }.joinToString("\n\n")
            val reply = runCatching { e.brain(EXTRACT_SYSTEM, prompt) }.getOrNull() ?: continue
            val arr = parseJsonArray(reply) ?: continue
            for (j in 0 until arr.length()) {
                val o = arr.optJSONObject(j) ?: continue
                val iv = chunk.getOrNull(o.optInt("n", j + 1) - 1) ?: continue
                val r = out[iv.index]
                fun ok(v: String) = v.trim().takeIf { it.length in 2..60 && !it.equals("unknown", true) && !it.equals("null", true) && !it.equals("n/a", true) }
                out[iv.index] = r.copy(
                    company = r.company.ifBlank { ok(o.optString("company")).orEmpty() },
                    location = r.location.ifBlank { ok(o.optString("location")).orEmpty() },
                    title = if (JobChecks.relevant(r.title)) r.title else ok(o.optString("title")) ?: r.title,
                )
            }
        }
        return out
    }

    private fun describe(s: Money.Salary?): String = s?.let {
        val n = if (it.low == it.high) "%,.0f".format(it.high) else "%,.0f–%,.0f".format(it.low, it.high)
        "${it.currency} $n"
    }.orEmpty()

    /** Writes (or rewrites) [item]'s application from the CV for its kind. */
    fun withLetter(store: JobStore, item: JobItem, adText: String = item.snippet): JobItem {
        val cv = store.cvFor(item.kind) ?: return item
        val (subject, body) = JobLetter.write(item, adText, store.profile(cv))
        return item.copy(subject = subject, body = body)
    }

    /** Rewrites every waiting draft (after Rex adds or changes a CV or his details). */
    fun rewriteDrafts(context: Context) {
        val store = JobStore(context)
        store.saveJobs(store.jobs().map { if (it.status == JobStatus.NEW || it.status == JobStatus.APPROVED) withLetter(store, it) else it })
    }

    /** Re-checks every waiting job against Rex's trusted / blocked lists (after "trust X" / "block Y"). */
    fun retrust(context: Context) {
        val store = JobStore(context)
        val trusted = store.trusted()
        val blocked = store.blocked()
        store.saveJobs(
            store.jobs().map { j ->
                if (j.status !in setOf(JobStatus.NEW, JobStatus.APPROVED, JobStatus.LINK, JobStatus.SKIPPED)) return@map j
                if (j.status == JobStatus.SKIPPED && j.verdict != Verdict.SCAM) return@map j
                val t = JobChecks.trust(j.company, j.email, j.snippet, j.salaryInr, trusted, blocked)
                val status = when {
                    t.verdict == Verdict.SCAM -> JobStatus.SKIPPED
                    j.status == JobStatus.SKIPPED -> if (j.email == null) JobStatus.LINK else JobStatus.NEW
                    else -> j.status
                }
                j.copy(verdict = t.verdict, reasons = t.reasons, status = status, score = JobChecks.score(j.countryRank, t.verdict, j.email != null, j.salaryInr, j.warnings.size))
            },
        )
    }

    private val BOARDS = Regex("""(?i)linkedin|indeed|bayt|naukri|gulftalent|rigzone|jobberman|myjobmag|oilandgasjobsearch|glassdoor|jooble|careerjet|hh\.(kz|ru)|energyjobline|oilcareers|monster|jobstreet|jobsdb|seek\.|careers24|pnet|jobrapido|talent\.com|simplyhired|ziprecruiter|facebook|twitter|x\.com|reddit|youtube|google|wikipedia""")

    /** The site's name for the "found on" line ("rigzone.com"). */
    private fun siteName(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { "Web" }

    /** "Painting Supervisor at Saipem" → Saipem; a company's own careers site → its name. */
    fun guessCompany(title: String, url: String): String {
        Regex("""(?i)\s(?:at|@)\s+([A-Z][\w&.\- ]{1,40}?)(?:\s*[-|–(,]|$)""").find(title)?.let { return it.groupValues[1].trim() }
        Regex("""\s[-|–]\s([A-Z][\w&.\- ]{2,40}?)\s[-|–]\s""").find(title)?.let { m ->
            val v = m.groupValues[1].trim()
            if (Countries.detect(v) == null && !JobChecks.relevant(v)) return v
        }
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
        if (host.isBlank() || BOARDS.containsMatchIn(host)) return ""
        val root = host.split('.').let { p -> if (p.size >= 3 && p[p.size - 2].length <= 3) p[p.size - 3] else p.getOrElse(p.size - 2) { host } }
        return root.removePrefix("careers").removePrefix("jobs").ifBlank { root }.replaceFirstChar { it.uppercase() }
    }

    // ---- sending (only what Rex approved) ----

    /** Sends up to [count] approved applications (all when null), ~2 minutes apart; returns what to tell Rex. */
    fun startSend(context: Context, count: Int?): String {
        val app = context.applicationContext
        if (sending()) return "I'm already sending, sir — ${_progress.value.line}"
        val store = JobStore(app)
        val keys = com.xarvis.ai.llm.BrainKeys(app)
        if (keys.get("brief_email").isNullOrBlank() || keys.get("brief_apppw").isNullOrBlank()) {
            return "Your Gmail isn't connected, sir: ☰ → Morning briefing → your Gmail address and app password."
        }
        val approved = store.jobs().filter { it.status == JobStatus.APPROVED && it.email != null }.sortedByDescending { it.score }
        if (approved.isEmpty()) return "Nothing is approved yet, sir. Open the Job Hunter (\"show my job drafts\"), tick ✓ on the ones you want, then tell me how many to send."
        if (store.cvFor(JobKind.SUPERVISOR) == null) return "Add your CV first, sir: Job Hunter → CVs."
        val batch = approved.take(count ?: approved.size)
        sendJob = scope.launch { sendBatch(app, batch) }
        val more = if (count != null && count > approved.size) " (only ${approved.size} are approved)" else ""
        return "Sending ${batch.size} application${if (batch.size == 1) "" else "s"}$more from your Gmail, sir, about two minutes apart. Sent today so far: ${store.sentToday()}."
    }

    fun stopSending() { sendJob?.cancel() }

    private suspend fun sendBatch(app: Context, batch: List<JobItem>) {
        val store = JobStore(app)
        val keys = com.xarvis.ai.llm.BrainKeys(app)
        val from = keys.get("brief_email").orEmpty()
        val pw = keys.get("brief_apppw").orEmpty()
        var sent = 0
        var failed = 0
        var stopReason: String? = null
        for ((i, job0) in batch.withIndex()) {
            kotlinx.coroutines.yield() // stops here when Rex cancels
            val email = job0.email ?: continue
            if (store.alreadyEmailed(email)) { store.update(job0.id) { it.copy(status = JobStatus.SKIPPED, error = "already applied to this address") }; continue }
            val job = if (job0.body.isBlank()) withLetter(store, job0) else job0
            val cvKind = store.cvFor(job.kind) ?: break
            say("Sending ${i + 1}/${batch.size}: ${job.title} → $email")
            val attachment = GmailIo.Attachment(store.cvName(cvKind) ?: "CV.pdf", "application/pdf", store.cvFile(cvKind).readBytes())
            val profile = store.profile(cvKind)
            val result = runCatching { GmailIo.send(from, pw, profile.name.ifBlank { from }, email, job.subject, job.body, attachment) }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (result.isSuccess) {
                sent++
                store.logSent(email, job.title, job.company)
                store.update(job.id) { it.copy(status = JobStatus.SENT, sentAt = System.currentTimeMillis(), subject = job.subject, body = job.body, error = null) }
            } else {
                failed++
                val why = result.exceptionOrNull()?.message ?: "failed"
                store.update(job.id) { it.copy(status = JobStatus.FAILED, error = why) }
                if (why.contains("login", true) || why.contains("password", true)) { stopReason = why; break }
            }
            if (i < batch.size - 1) delay(SEND_GAP_MS + (0..30_000L).random())
        }
        val msg = buildString {
            append("Applications sent: $sent")
            if (failed > 0) append(", $failed failed")
            append(". Sent today: ${store.sentToday()}.")
            stopReason?.let { append(" Stopped: $it") }
            append(" I'll tell you when anyone replies.")
        }
        _progress.value = Progress(false, msg)
        env?.post("📨 $msg")
        env?.notify("Job Hunter", msg)
    }

    // ---- replies (no follow-ups: just tell Rex) ----

    /** New replies from employers and agencies Rex wrote to (checked at most every 45 minutes). */
    suspend fun checkReplies(context: Context, force: Boolean = false): List<String> {
        val app = context.applicationContext
        val store = JobStore(app)
        if (!force && System.currentTimeMillis() - store.lastReplyCheck < 45 * 60_000L) return emptyList()
        val sent = store.sent().filter { !it.replied && System.currentTimeMillis() - it.at < 60L * 86_400_000L }
        if (sent.isEmpty()) return emptyList()
        val keys = com.xarvis.ai.llm.BrainKeys(app)
        val gmail = keys.get("brief_email") ?: return emptyList()
        val pw = keys.get("brief_apppw") ?: return emptyList()
        store.lastReplyCheck = System.currentTimeMillis()
        val mails = runCatching { GmailIo.repliesFrom(gmail, pw, sent.map { it.email }.distinct(), sent.minOf { it.at }) }.getOrDefault(emptyList())
        val out = ArrayList<String>()
        for (m in mails) {
            if (store.seenReply(m.uid)) continue
            store.markReplySeen(m.uid)
            val who = sent.firstOrNull { m.from.contains(it.email, true) } ?: continue
            store.markReplied(who.email)
            val gist = m.text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(">") }.joinToString(" ").take(220)
            out += "📩 Reply about \"${who.title}\"${who.company.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()} from ${m.from.substringBefore('<').trim().ifBlank { who.email }}: \"${m.subject}\" — $gist"
        }
        return out
    }

    // ---- status ----

    fun status(context: Context): String {
        val store = JobStore(context)
        val jobs = store.jobs()
        fun n(s: JobStatus) = jobs.count { it.status == s }
        val replies = store.sent().count { it.replied }
        return "Job Hunter: ${n(JobStatus.NEW)} drafts waiting, ${n(JobStatus.APPROVED)} approved, ${n(JobStatus.LINK)} to apply on the site, " +
            "${store.sent().size} sent in total (${store.sentToday()} today), $replies replied. " +
            "Tavily ${store.tavilyUsed()}/${JobStore.TAVILY_MONTH} this month, Jooble ${store.joobleUsed()}/500."
    }

    /** The brain-free fallback for lists the screen shows. */
    fun parseJsonArray(text: String): JSONArray? = runCatching {
        JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
    }.getOrNull()
}
