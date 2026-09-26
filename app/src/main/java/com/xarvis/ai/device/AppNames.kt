package com.xarvis.ai.device

/**
 * Matches what Rex calls an app ("chat gpt", "YT studio", "files", "calender") to the app's
 * real name or package on the phone, and knows the web apps he uses (Olive payslips).
 */
object AppNames {

    /** Rex's names -> words or package names that identify the app. */
    private val ALIASES: Map<String, List<String>> = listOf(
        listOf("chat gpt", "chatgpt", "gpt") to listOf("chatgpt", "com.openai.chatgpt"),
        listOf("files", "folders", "file manager", "my files") to
            listOf("myfiles", "com.sec.android.app.myfiles", "filesbygoogle", "com.google.android.apps.nbu.files", "files"),
        listOf("gallery", "photos", "pictures") to
            listOf("gallery", "com.sec.android.gallery3d", "photos", "com.google.android.apps.photos"),
        listOf("calendar", "calender") to listOf("calendar", "com.samsung.android.calendar", "com.google.android.calendar"),
        listOf("yt studio", "youtube studio", "studio") to listOf("ytstudio", "youtubestudio", "com.google.android.apps.youtube.creator"),
        listOf("youtube", "yt") to listOf("youtube", "com.google.android.youtube"),
        listOf("gmail", "email", "mail") to listOf("gmail", "com.google.android.gm"),
        listOf("drive", "google drive") to listOf("drive", "com.google.android.apps.docs"),
        listOf("messenger", "facebook messenger") to listOf("messenger", "com.facebook.orca"),
        listOf("teams", "microsoft teams", "ms teams") to listOf("teams", "com.microsoft.teams"),
        listOf("excel", "microsoft excel") to listOf("excel", "com.microsoft.office.excel"),
        listOf("zoom") to listOf("zoom", "us.zoom.videomeetings"),
        listOf("games", "game", "game launcher", "gaming hub") to
            listOf("gamelauncher", "gaminghub", "com.samsung.android.game.gamehome", "playgames", "com.google.android.play.games"),
        listOf("elevenlab", "elevenlabs", "eleven labs", "eleven lab") to listOf("elevenlabs", "elevenreader", "eleven"),
        listOf("kling", "klingai", "kling ai") to listOf("klingai", "kling"),
        listOf("camscanner", "cam scanner") to listOf("camscanner", "com.intsig.camscanner"),
        listOf("google cloud", "gcloud", "cloud console") to listOf("googlecloud", "cloudconsole", "com.google.android.apps.cloudconsole"),
        listOf("linkedin", "linked in") to listOf("linkedin", "com.linkedin.android"),
        listOf("naukri", "naukri gulf", "naukrigulf") to listOf("naukri", "naukrigulf"),
        listOf("github", "git hub") to listOf("github", "com.github.android"),
        listOf("capcut", "cap cut") to listOf("capcut", "com.lemon.lvoverseas"),
        listOf("picsart", "pics art") to listOf("picsart", "com.picsart.studio"),
        listOf("phonto") to listOf("phonto", "com.youthhr.phonto"),
        listOf("magicplan", "magic plan") to listOf("magicplan", "com.sensopia.magicplan"),
        listOf("hadith", "hadith collection", "hadees") to listOf("hadithcollection", "hadith"),
        listOf("intelligent cv", "intelligentcv", "cv app", "cv maker") to listOf("intelligentcv"),
        listOf("compass") to listOf("compass"),
    ).flatMap { (names, targets) -> names.map { normalize(it) to targets } }.toMap()

    /** Web apps: opened in the browser (or their app, if Android links them to one). */
    private val WEB_APPS: Map<String, String> = listOf(
        listOf("payslip", "payslips", "olive", "olive hrms", "olivehrms", "hrms", "salary slip") to "https://altrad.olivehrms.com",
    ).flatMap { (names, url) -> names.map { normalize(it) to url } }.toMap()

    /** Lower-case letters and digits only: "YT Studio" -> "ytstudio". */
    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun cleanQuery(name: String): String = normalize(
        name.lowercase().replace(Regex("""\b(?:the|my|app|application)\b"""), " ")
    )

    /** The installed app [name] means, or null. */
    fun best(name: String, apps: List<InstalledApp>): InstalledApp? {
        val q = cleanQuery(name)
        if (q.isEmpty()) return null
        val candidates = listOf(q) + ALIASES[q].orEmpty().map { if (it.contains('.')) it else normalize(it) }
        var bestApp: InstalledApp? = null
        var bestScore = 0
        for (app in apps) {
            val label = normalize(app.label)
            val score = candidates.maxOf { c -> score(c, label, app.packageName) }
            if (score > bestScore || (score == bestScore && score > 0 && label.length < normalize(bestApp!!.label).length)) {
                bestApp = app
                bestScore = score
            }
        }
        return bestApp
    }

    private fun score(candidate: String, label: String, pkg: String): Int = when {
        candidate.contains('.') -> if (pkg == candidate) 100 else 0 // a package name
        label == candidate -> 90
        candidate.length >= 3 && label.startsWith(candidate) -> 70
        candidate.length >= 3 && label.contains(candidate) -> 50
        label.length >= 4 && candidate.contains(label) -> 40
        candidate.length >= 4 && pkg.lowercase().contains(candidate) -> 30
        else -> 0
    }

    /** The web address for a web app Rex names ("payslip"), or null. */
    fun webApp(name: String): String? = WEB_APPS[cleanQuery(name)]

    /** Installed app names that look a bit like [name], to suggest when nothing matches. */
    fun similar(name: String, apps: List<InstalledApp>): List<String> {
        val q = cleanQuery(name).take(3)
        if (q.length < 3) return emptyList()
        return apps.filter { normalize(it.label).contains(q) }.map { it.label }.distinct().take(5)
    }
}
