package com.xarvis.ai.shield

import java.net.URI

/**
 * XARVIS Shield's judgement of messages and links (Rex, 15 Nov 2026: "an adamantium shield… always on alert, my
 * personal invisible bodyguard"). Pure rules, no network, nothing leaves the phone: most money is lost through a
 * fake message, a lookalike link or a "share your OTP" call, and those have tell-tale signs.
 */
object Scam {

    enum class Level { SAFE, WARN, DANGER }

    /** [codeOnly]: just a real login code arriving — worth a word when asked, not an alarm every time. */
    data class Verdict(val level: Level, val reasons: List<String>, val codeOnly: Boolean = false) {
        val headline: String get() = when (level) {
            Level.DANGER -> "⛔ Danger — this looks like a scam."
            Level.WARN -> "⚠ Careful — this has signs of a scam."
            Level.SAFE -> "✅ No warning signs found (that's not a promise it's safe)."
        }
        fun text(): String = (listOf(headline) + reasons.map { "• $it" }).joinToString("\n")
    }

    // ---- messages ----

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val CODE_WORD = re("""\b(otp|one[- ]time (?:password|code|pin)|verification code|security code|pin|cvv|cvc|passcode|password|login code|6[- ]digit code)\b""")
    private val ASK_SHARE = re("""\b(share|send|tell|give|forward|read out|provide|reply with|batao|bhejo|bata do|bhej do)\b""")
    private val NEVER_SHARE = re("""\b(do not|don't|dont|never|not to)\s+(share|disclose|give|tell)|kisi ko (?:na|mat)|share (?:na|mat) kar""")
    private val CODE_MESSAGE = re("""\b(otp|one[- ]time|verification code|login code|security code|is your .{0,20}code)\b""")
    private val ACCOUNT_THREAT = re("""\b(blocked|suspended|deactivated|frozen|locked|restricted|closed|expire[sd]?|expiring|will be (?:blocked|closed|suspended)|band ho|block ho)\b""")
    private val ACCOUNT_WORD = re("""\b(account|card|kyc|emirates id|eid|pan|aadhaar|aadhar|uae ?pass|sim|bank|wallet|khata)\b""")
    private val UPDATE_ASK = re("""\b(update|verify|confirm|re-?activate|unlock|validate)\b.{0,30}\b(kyc|account|details|card|emirates id|pan|aadhaar|information|info)\b""")
    private val PRIZE = re("""\b(you (?:have )?won|winner|lottery|lucky draw|prize|jackpot|congratulations.{0,40}(?:won|selected|reward)|claim your|inaam|lottery lagi)\b""")
    private val PARCEL = re("""\b(parcel|package|shipment|delivery|courier)\b""")
    private val PARCEL_TRAP = re("""\b(fee|customs|pay|held|on hold|failed|unable to deliver|incomplete address|update (?:your )?address|reschedule)\b""")
    private val JOB = re("""\b(part[- ]time|work from home|daily (?:income|salary|earning)|earn (?:aed|rs|₹|\$)?\s?\d+|like (?:youtube )?videos|simple tasks?|telegram task|review tasks?)\b""")
    private val INVEST = re("""\b(crypto|bitcoin|btc|usdt|forex|trading signal|investment plan|mining)\b""")
    private val INVEST_HOOK = re("""\b(guaranteed|double your|daily profit|\d{2,3}% (?:profit|return)|risk[- ]free|returns? (?:daily|weekly))\b""")
    private val PAY = re("""\b(send money|transfer|pay now|make (?:a )?payment|payment link|gift cards?|recharge|aed|rs\.?|inr|₹|\$|usdt)\b""")
    private val URGENT = re("""\b(urgent|urgently|immediately|right now|within \d+ ?(?:hours?|hrs?|minutes?|mins?)|today only|last chance|final (?:notice|warning)|abhi|turant|jaldi)\b""")
    private val REMOTE_APP = re("""\b(anydesk|any desk|teamviewer|quick ?support|rustdesk|airdroid|install (?:this|the) app|download (?:this|the) app|\.apk)\b""")
    private val FAMILY_NEW_NUMBER = re("""\b(new number|lost my phone|phone (?:is )?broken)\b.{0,80}\b(send|transfer|money|pay|help)\b""")

    /**
     * A message (an SMS, WhatsApp, email notification…): [app] is the app's name, for the wording only.
     * Ordinary messages come back SAFE with no reasons.
     */
    fun message(text: String, app: String = ""): Verdict {
        val t = text.replace('\n', ' ')
        val reasons = ArrayList<String>()
        var danger = false
        var score = 0

        val asksCode = CODE_WORD.containsMatchIn(t) && ASK_SHARE.containsMatchIn(t) && !NEVER_SHARE.containsMatchIn(t)
        if (asksCode) {
            danger = true
            reasons += "It asks you to share a code or PIN. No real bank, company or government office ever asks for that."
        } else if (CODE_MESSAGE.containsMatchIn(t)) {
            // A real code message: harmless by itself, but the classic set-up for "I sent you a code by mistake".
            reasons += "It's a login/verification code. If you didn't just ask for one, someone may be trying to get into your account — never share it, even with someone who calls \"from the bank\"."
            return Verdict(Level.WARN, reasons, codeOnly = true)
        }
        if (REMOTE_APP.containsMatchIn(t)) {
            danger = true
            reasons += "It asks you to install an app or a remote-control tool (AnyDesk, TeamViewer…). That's how they take over phones."
        }
        if ((ACCOUNT_THREAT.containsMatchIn(t) && ACCOUNT_WORD.containsMatchIn(t)) || UPDATE_ASK.containsMatchIn(t)) {
            score += 2
            reasons += "It threatens that your account/card/KYC will be blocked or asks you to \"update\" details — a favourite scam trick."
        }
        if (PRIZE.containsMatchIn(t)) { score += 2; reasons += "It says you won a prize or lottery you never entered." }
        if (PARCEL.containsMatchIn(t) && PARCEL_TRAP.containsMatchIn(t)) { score += 2; reasons += "A \"parcel is held / pay a small fee\" message — the most common fake-delivery scam." }
        if (JOB.containsMatchIn(t)) { score += 2; reasons += "An easy-money job offer (liking videos, small tasks, daily pay) — these end with you paying them." }
        if (INVEST.containsMatchIn(t) && INVEST_HOOK.containsMatchIn(t)) { score += 2; reasons += "It promises guaranteed or very high profits — real investments never do." }
        if (FAMILY_NEW_NUMBER.containsMatchIn(t)) { score += 2; reasons += "\"New number / lost my phone, send money\" — call the person on their old number first." }
        if (PAY.containsMatchIn(t) && URGENT.containsMatchIn(t)) { score += 1; reasons += "It pushes you to pay or act urgently." } else if (URGENT.containsMatchIn(t) && score > 0) score += 1

        val links = links(t)
        for (url in links) {
            val v = link(url)
            if (v.level == Level.DANGER) danger = true
            if (v.level != Level.SAFE) { score += 1; reasons += v.reasons }
        }
        if (links.isNotEmpty() && score >= 2) reasons += "Don't tap its link. Open your bank's or the company's own app instead."

        val level = when {
            danger -> Level.DANGER
            score >= 3 -> Level.DANGER
            score >= 2 -> Level.WARN
            else -> Level.SAFE
        }
        return Verdict(level, if (level == Level.SAFE) emptyList() else reasons.distinct())
    }

    // ---- links ----

    private val URL = re("""\b((?:https?://|www\.)[^\s<>"']+|[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|top|xyz|click|link|live|shop|icu|buzz|cfd|sbs|ae|in|co|me|ly|io|app|site|online|vip|cc)(?:/[^\s<>"']*)?)""")

    /** The links in [text] (with or without http://). */
    fun links(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?', ';', ':') }
        .filter { it.contains('.') && !it.contains('@') || it.startsWith("http") }.distinct().take(5).toList()

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.ly", "cutt.ly", "is.gd", "rb.gy", "shorturl.at", "goo.gl", "tiny.cc", "s.id",
        "rebrand.ly", "ow.ly", "t.co", "bl.ink", "short.gy", "v.gd", "qrco.de", "linktr.ee",
    )
    private val RISKY_TLDS = setOf(
        "top", "xyz", "click", "link", "live", "shop", "icu", "buzz", "rest", "cfd", "sbs", "monster", "zip", "mov",
        "tk", "ml", "ga", "cf", "gq", "work", "support", "vip", "cyou", "bond", "lol", "quest", "beauty", "hair",
    )

    /** Brand name in a link → the brand's own registrable domains. A brand name anywhere else = pretending. */
    private val BRANDS: Map<String, List<String>> = mapOf(
        "emiratesnbd" to listOf("emiratesnbd.com"), "enbd" to listOf("emiratesnbd.com"),
        "adcb" to listOf("adcb.com"), "bankfab" to listOf("bankfab.com"), "mashreq" to listOf("mashreq.com", "mashreqbank.com"),
        "rakbank" to listOf("rakbank.ae"), "dib" to listOf("dib.ae"), "adib" to listOf("adib.ae"), "cbd" to listOf("cbd.ae"),
        "etisalat" to listOf("etisalat.ae", "eand.com"), "eand" to listOf("eand.com", "etisalat.ae"),
        "emiratespost" to listOf("emiratespost.ae"), "aramex" to listOf("aramex.com"), "dhl" to listOf("dhl.com"),
        "fedex" to listOf("fedex.com"), "uaepass" to listOf("uaepass.ae"), "dubaipolice" to listOf("dubaipolice.gov.ae"),
        "salik" to listOf("salik.ae"), "adnoc" to listOf("adnoc.ae"), "tamm" to listOf("tamm.abudhabi"),
        "sbi" to listOf("sbi.co.in", "onlinesbi.sbi", "sbi"), "onlinesbi" to listOf("onlinesbi.sbi", "sbi.co.in"),
        "hdfc" to listOf("hdfcbank.com", "hdfc.com"), "icici" to listOf("icicibank.com", "icici.com"),
        "axisbank" to listOf("axisbank.com"), "kotak" to listOf("kotak.com"), "paytm" to listOf("paytm.com"),
        "phonepe" to listOf("phonepe.com"), "indiapost" to listOf("indiapost.gov.in"), "incometax" to listOf("incometax.gov.in"),
        "paypal" to listOf("paypal.com"), "binance" to listOf("binance.com"), "coinbase" to listOf("coinbase.com"),
        "amazon" to listOf("amazon.com", "amazon.ae", "amazon.in", "amzn.to"), "apple" to listOf("apple.com"),
        "icloud" to listOf("icloud.com", "apple.com"), "google" to listOf("google.com", "google.ae", "google.co.in"),
        "microsoft" to listOf("microsoft.com", "live.com"), "whatsapp" to listOf("whatsapp.com", "wa.me"),
        "facebook" to listOf("facebook.com", "fb.com"), "instagram" to listOf("instagram.com"), "netflix" to listOf("netflix.com"),
        "linkedin" to listOf("linkedin.com"), "telegram" to listOf("telegram.org", "t.me"),
    )
    private val SECOND_LEVEL = setOf("co", "com", "gov", "ac", "org", "net", "edu", "nic", "res", "mil")

    /** "login.secure-emiratesnbd.co.ae/x" → "secure-emiratesnbd.co.ae". */
    fun registrable(host: String): String {
        val p = host.lowercase().trimEnd('.').split('.')
        if (p.size <= 2) return p.joinToString(".")
        val n = if (p[p.size - 2] in SECOND_LEVEL && p.last().length == 2) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    fun host(url: String): String? = runCatching {
        val u = if (url.contains("://")) url else "http://$url"
        URI(u.replace(" ", "%20")).host?.lowercase()
    }.getOrNull() ?: Regex("""^(?:https?://)?([^/:?#\s]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()

    fun link(url: String): Verdict {
        val host = host(url) ?: return Verdict(Level.WARN, listOf("I can't read where this link goes."))
        val reasons = ArrayList<String>()
        var danger = false
        var warn = false
        val reg = registrable(host)
        val tld = host.substringAfterLast('.')
        if (url.substringAfter("://", url).substringBefore('/').contains('@')) {
            danger = true; reasons += "The link hides its real address behind an \"@\"."
        }
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) {
            danger = true; reasons += "The link is a bare number address ($host), not a company's website."
        }
        if (host.split('.').any { it.startsWith("xn--") }) {
            danger = true; reasons += "The link uses look-alike foreign letters to imitate a real website."
        }
        val squashed = host.replace("-", "").replace(".", "")
        for ((brand, official) in BRANDS) {
            if (!squashed.contains(brand)) continue
            val isOfficial = official.any { reg == it || host == it || host.endsWith(".$it") }
            if (!isOfficial && !(brand.length <= 3 && !host.split('.', '-').contains(brand))) {
                danger = true
                reasons += "It pretends to be ${brand.replaceFirstChar { it.uppercase() }}, but goes to $reg — not their real website."
                break
            }
        }
        if (reg in SHORTENERS || host in SHORTENERS) { warn = true; reasons += "It's a short link ($host): it hides where it really goes." }
        if (tld in RISKY_TLDS) { warn = true; reasons += "It ends in .$tld, a cheap web address scammers use a lot." }
        if (host.count { it == '-' } >= 3 || host.split('.').size >= 5) { warn = true; reasons += "The address is unusually long and messy ($host)." }
        if (url.startsWith("http://", ignoreCase = true) && Regex("login|verify|account|update|secure|bank|pay", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            warn = true; reasons += "It asks for log-ins without a secure (https) connection."
        }
        val level = if (danger) Level.DANGER else if (warn) Level.WARN else Level.SAFE
        return Verdict(level, if (level == Level.SAFE) listOf("Goes to $reg.") else reasons.distinct())
    }
}
