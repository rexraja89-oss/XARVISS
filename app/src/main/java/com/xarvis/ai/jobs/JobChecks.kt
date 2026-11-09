package com.xarvis.ai.jobs

/**
 * The checks every job goes through before Rex sees it: is it his kind of job, which CV it needs, the
 * application email (only one published in the ad), the agency trust verdict, what it asks for that he lacks,
 * and a score for sorting. All plain rules, so they're fast, free and testable; the reasons are shown to Rex.
 */
object JobChecks {

    // ---- is it Rex's job? ----

    private val TRADE = Regex("""(?i)\b(blast\w*|paint\w*|coating\w*|insulat\w*|fire\s*proof\w*|fireproof\w*|surface\s+prep\w*|corrosion|protective\s+coat\w*|sand\s*blast\w*|grit\s*blast\w*)""")
    private val LEVEL = Regex("""(?i)\b(supervisor|superintendent|foreman|forman|charge\s*hand|chargehand|in[\s-]?charge|lead|coordinator|instructor|trainer|training|tutor|assessor|teacher)\b""")
    private val TRAINER = Regex("""(?i)\b(instructor|trainer|tutor|assessor|teacher|training\s+(officer|coordinator|specialist|lead))\b""")
    private val NOT_HIS = Regex("""(?i)\b(inspector|engineer|estimator|planner|sales|marketing|painter\s+helper|helper|labou?rer|intern)\b""")

    /** Blasting / painting / coating / insulation at supervisor-or-trainer level (not plain painter, not inspector). */
    fun relevant(title: String, text: String = ""): Boolean {
        val t = title.ifBlank { text.take(160) }
        if (!TRADE.containsMatchIn(t) || !LEVEL.containsMatchIn(t)) return false
        // "Painting Inspector", "Coating Engineer": not what Rex asked for, unless a supervisor role is also named.
        return !(NOT_HIS.containsMatchIn(t) && !Regex("""(?i)supervisor|superintendent|foreman|instructor|trainer""").containsMatchIn(t))
    }

    fun kind(title: String): JobKind = if (TRAINER.containsMatchIn(title)) JobKind.TRAINER else JobKind.SUPERVISOR

    // ---- the application email ----

    private val EMAIL = Regex("""(?i)\b[a-z0-9][a-z0-9._%+-]{0,63}@[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,24}\b""")
    private val OBFUSCATED = Regex("""(?i)\b([a-z0-9][a-z0-9._%+-]{1,63})\s*[\[(]\s*at\s*[\])]\s*([a-z0-9-]+(?:\s*[\[(]\s*dot\s*[\])]\s*[a-z0-9-]+)+)""")
    val FREE_MAIL = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "yahoo.co.uk", "ymail.com", "hotmail.com", "outlook.com",
        "live.com", "msn.com", "aol.com", "icloud.com", "me.com", "rediffmail.com", "mail.ru", "yandex.ru", "yandex.com",
        "proton.me", "protonmail.com", "gmx.com", "gmx.de", "zoho.com", "inbox.ru", "bk.ru", "list.ru", "qq.com", "163.com",
    )
    private val JUNK = Regex("""(?i)(^|[._-])(no-?reply|do-?not-?reply|noreply|mailer-daemon|postmaster|abuse|privacy|gdpr|unsubscribe|webmaster|support|sentry|example|test)([._-]|@)|@(example|domain|email|yourcompany|company)\.|\.(png|jpe?g|gif|webp|svg)$|@\d|wixpress|sentry\.io|linkedin\.com$|indeed\.com$|jooble\.org$""")
    private val PREFERRED = Regex("""(?i)^(careers?|jobs?|recruit\w*|hr|hiring|talent|cv|resumes?|apply|applications?|vacanc\w*|manpower|staffing|employment)[._-]?\w*@""")

    /** Every email written in the ad (including "name [at] company [dot] com"), cleaned, junk dropped. */
    fun emails(text: String): List<String> {
        val found = LinkedHashSet<String>()
        EMAIL.findAll(text).forEach { found += it.value.trimEnd('.').lowercase() }
        OBFUSCATED.findAll(text).forEach { m ->
            found += (m.groupValues[1] + "@" + m.groupValues[2].replace(Regex("""(?i)\s*[\[(]\s*dot\s*[\])]\s*"""), ".")).lowercase()
        }
        return found.filterNot { JUNK.containsMatchIn(it) }
    }

    fun domainOf(email: String): String = email.substringAfter('@').lowercase()

    fun isFreeMail(email: String): Boolean = domainOf(email) in FREE_MAIL

    /**
     * The address to apply to: a recruitment-looking address (careers@, hr@, jobs@…) on the company's own domain
     * first, then any company-domain address, then a free-mail one (which the trust check marks). Null: none.
     */
    fun bestEmail(emails: List<String>, company: String = "", siteUrl: String = ""): String? {
        if (emails.isEmpty()) return null
        val companyWords = company.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
        val siteDomain = siteUrl.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
        fun matchesCompany(e: String): Boolean {
            val d = domainOf(e)
            return companyWords.any { d.contains(it) } || (siteDomain.isNotEmpty() && (d == siteDomain || d.endsWith(".$siteDomain")))
        }
        return emails.sortedWith(
            compareByDescending<String> { !isFreeMail(it) }
                .thenByDescending { matchesCompany(it) }
                .thenByDescending { PREFERRED.containsMatchIn(it) },
        ).first()
    }

    // ---- the agency trust system ----

    private val FEE = Regex(
        """(?i)\b(visa|processing|registration|medical|service|security|application|training|documentation|document|placement|recruitment|agency|interview|ticket|deployment|mobili[sz]ation|refundable)\s+(fee|fees|charge|charges|cost|deposit|payment)\b|\bpay\s+(?:a|the|an)?\s*(?:small\s+)?(fee|deposit|amount)\b|\b(western\s+union|moneygram)\b""",
    )
    private val FEE_NEGATED = Regex("""(?i)\b(no|zero|free\s+of|without|never|not|don'?t|do\s+not|nil)\b[^.]{0,25}$""")
    private val FEE_COVERED = Regex("""(?i)^[^.]{0,30}\b(covered|paid\s+by\s+(the\s+)?(company|employer|client)|borne\s+by|provided\s+by|reimbursed|free)\b""")
    private val WHATSAPP = Regex("""(?i)whats\s*app|telegram|viber|imo\b""")
    private val PRESSURE = Regex("""(?i)\b(urgent(ly)?|immediate(ly)?\s+(joining|start)|limited\s+(slots|seats)|apply\s+now\s+or|last\s+chance)\b""")

    data class Trust(val verdict: Verdict, val reasons: List<String>)

    /**
     * Authentic / suspicious / scam, with the reasons. [trusted] / [blocked] are Rex's own lists ("trust X",
     * "block Y": a name or a domain), which always win.
     */
    fun trust(
        company: String,
        email: String?,
        text: String,
        salaryInr: Long?,
        trusted: Collection<String> = emptyList(),
        blocked: Collection<String> = emptyList(),
        mailDomainWorks: Boolean? = null,
    ): Trust {
        val reasons = ArrayList<String>()
        val domain = email?.let { domainOf(it) }.orEmpty()
        fun listed(list: Collection<String>) = list.any { l ->
            val n = l.trim().lowercase()
            n.isNotEmpty() && (company.lowercase().contains(n) || domain == n || domain.endsWith(".$n") || (n.contains('@') && email.equals(n, true)))
        }
        if (listed(blocked)) return Trust(Verdict.SCAM, listOf("on your blocked list"))
        if (listed(trusted)) return Trust(Verdict.AUTHENTIC, listOf("on your trusted list"))

        var scam = false
        FEE.findAll(text).forEach { m ->
            val before = text.substring(maxOf(0, m.range.first - 40), m.range.first)
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 45))
            if (!FEE_NEGATED.containsMatchIn(before) && !FEE_COVERED.containsMatchIn(after)) {
                scam = true
                reasons += "asks for money (\"${m.value.trim()}\")"
            }
        }
        if (salaryInr != null && salaryInr > 1_500_000) {
            reasons += "salary looks too good to be true (${Money.inr(salaryInr)}/month)"
        }
        var suspicious = reasons.isNotEmpty()
        if (company.isBlank()) { suspicious = true; reasons += "no company name" }
        if (email == null) {
            if (WHATSAPP.containsMatchIn(text)) { suspicious = true; reasons += "WhatsApp/Telegram contact only" }
        } else {
            if (isFreeMail(email)) { suspicious = true; reasons += "uses a free email ($domain), not a company address" }
            if (mailDomainWorks == false) { suspicious = true; reasons += "the email's domain doesn't receive mail" }
        }
        if (PRESSURE.containsMatchIn(text) && company.isBlank()) reasons += "pressure words (urgent / immediate)"
        val verdict = when {
            scam -> Verdict.SCAM
            suspicious -> Verdict.SUSPICIOUS
            else -> Verdict.AUTHENTIC
        }
        if (verdict == Verdict.AUTHENTIC) {
            reasons += if (email != null) "company email ($domain)" else "named company"
            reasons += "no fee requested"
        }
        return Trust(verdict, reasons.distinct())
    }

    // ---- what the ad asks for that Rex doesn't have ----

    private val CERTS = linkedMapOf(
        "NACE / AMPP" to Regex("""(?i)\b(nace|ampp|cip\s*level|coating\s+inspector\s+program)\b"""),
        "FROSIO" to Regex("""(?i)\bfrosio\b"""),
        "BGAS / CSWIP" to Regex("""(?i)\b(bgas|cswip)\b"""),
        "ICorr" to Regex("""(?i)\bicorr\b"""),
        "SSPC" to Regex("""(?i)\bsspc\b"""),
        "BOSIET / FOET / HUET" to Regex("""(?i)\b(bosiet|foet|huet|ca-?ebs|t-?bosiet|opito)\b"""),
        "NEBOSH / IOSH" to Regex("""(?i)\b(nebosh|iosh)\b"""),
        "GWO" to Regex("""(?i)\bgwo\b"""),
    )
    private val YEARS = Regex("""(?i)\b(?:minimum\s+(?:of\s+)?|min\.?\s+|at\s+least\s+)?(\d{1,2})\s*(?:\+|plus)?\s*(?:-\s*\d{1,2}\s*)?(?:years?|yrs?)\b""")
    private val NATIONALS = Regex("""(?i)\b(?:only\s+)?([A-Z][a-z]+)\s+nationals?\s+only\b|\bonly\s+([A-Z][a-z]+)\s+nationals?\b|\b(locals?|citizens?)\s+only\b""")

    /** Rex's documented experience (May 2010 – today, gaps excluded), years. */
    const val REX_YEARS = 13

    /** "asks for NACE / AMPP ✗", "asks for 15+ years (you have 13) – borderline", "Nigerian nationals only". */
    fun warnings(text: String): List<String> {
        val out = ArrayList<String>()
        CERTS.forEach { (name, re) -> if (re.containsMatchIn(text)) out += "asks for $name ✗" }
        YEARS.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 3..40 }.maxOrNull()?.let { y ->
            if (y > REX_YEARS) out += "asks for $y+ years (you have $REX_YEARS) – borderline"
        }
        NATIONALS.find(text)?.let { m ->
            val who = m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] }
            out += "$who nationals only?".replace("locals nationals", "locals").replace("citizens nationals", "citizens")
        }
        return out
    }

    // ---- sorting ----

    /** Higher is better: Rex's country order first, then trust, an email to apply to, salary, and fewer gaps. */
    fun score(countryRank: Int, verdict: Verdict, hasEmail: Boolean, salaryInr: Long?, warnings: Int): Int {
        var s = if (countryRank in 1..33) (34 - countryRank) * 3 else 0
        s += when (verdict) { Verdict.AUTHENTIC -> 30; Verdict.SUSPICIOUS -> 8; Verdict.SCAM -> -200 }
        if (hasEmail) s += 15
        s += when {
            salaryInr == null -> 3
            salaryInr >= Money.MIN_INR_MONTH * 2 -> 15
            salaryInr >= Money.MIN_INR_MONTH -> 10
            else -> -50
        }
        return s - 6 * warnings
    }
}
