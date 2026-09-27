package com.xarvis.ai.workflow

import java.net.URLEncoder

/** Job search pages XARVIS can open, already searching (the "jobs" tool). */
object JobSites {

    class Site(val key: String, val label: String, val appName: String)

    val LINKEDIN = Site("linkedin", "LinkedIn", "linkedin")
    private val SITES = listOf(
        LINKEDIN,
        Site("naukrigulf", "Naukri Gulf", "naukri gulf"),
        Site("naukri", "Naukri", "naukri"),
        Site("indeed", "Indeed", "indeed"),
        Site("bayt", "Bayt", "bayt"),
        Site("gulftalent", "GulfTalent", "gulftalent"),
    )

    /** The site Rex named ("on naukri"), or LinkedIn. */
    fun pick(name: String?): Site {
        val n = name?.lowercase()?.replace(" ", "").orEmpty()
        return SITES.firstOrNull { n.isNotEmpty() && n.contains(it.key) } ?: LINKEDIN
    }

    /** The search page for [titles] in [place] (null means anywhere in the world). */
    fun url(site: Site, titles: String, place: String?): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        // Sites without an OR search get the first title.
        val first = titles.split(Regex("""(?i)\s+or\s+|,|/""")).first().trim()
        fun slug(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        return when (site.key) {
            "naukrigulf" -> "https://www.naukrigulf.com/${slug(first)}-jobs" + (place?.let { "-in-${slug(it)}" } ?: "")
            "naukri" -> "https://www.naukri.com/${slug(first)}-jobs" + (place?.let { "-in-${slug(it)}" } ?: "")
            "indeed" -> "https://www.indeed.com/jobs?q=${enc(titles)}" + (place?.let { "&l=${enc(it)}" } ?: "")
            "bayt" -> "https://www.bayt.com/en/international/jobs/${slug(first)}-jobs/"
            "gulftalent" -> "https://www.gulftalent.com/jobs/search?keywords=${enc(first)}"
            // LinkedIn Jobs: OR works in keywords; "Worldwide" searches every country.
            else -> "https://www.linkedin.com/jobs/search/?keywords=${enc(titles)}&location=${enc(place ?: "Worldwide")}"
        }
    }
}
