package com.xarvis.ai.policy

import com.xarvis.ai.workflow.Step

/**
 * What kind of action a XARVIS step is, for Rex's permission settings (☰ → Permissions).
 * [alwaysManual] categories are ones XARVIS must never do by itself; none exist yet, and
 * [PolicyRules.canSet] keeps them from ever being set to Allow or Ask.
 */
enum class Category(val title: String, val description: String, val alwaysManual: Boolean = false) {
    PHONE_CALL("Phone calls", "Calling someone, or opening the dialer ready to call."),
    MESSAGING("Messages", "Opening WhatsApp or SMS with a message written for you (you still tap Send)."),
    OPEN_APP("Open apps & search", "Opening apps, maps, Google searches, searching inside an app, sharing text into an app."),
    JOB_SEARCH("Job search", "Opening job listings on LinkedIn, Naukri, Bayt and other job sites."),
    JOB_READING("Reading job ads", "Reading a job ad you share, to compare it with your profile."),
    FILES("Files", "Saving PDF/Word/Excel files XARVIS writes, converting and finding them."),
    MEMORY("Memory", "Remembering facts you tell XARVIS and listing them."),
    EMAIL_DRAFTING("Email drafts", "Writing email drafts for you to check and send yourself."),
    DEVICE_SETTINGS("Phone settings", "Wi-Fi, Bluetooth, flashlight, alarms and timers."),
}

/** How much XARVIS may do in a category. */
enum class Level(val label: String) {
    ALLOW("Allow"),
    ASK("Ask me"),
    DENY("Off"),
    /** XARVIS never does it; Rex always does it himself. */
    ALWAYS_MANUAL("Always me"),
}

/** What [PolicyLayer.check] says about an action. */
enum class Decision { ALLOW, ASK, BLOCK }

/** Rex's answer to an "Ask me" card. */
enum class Answer { ONCE, ALWAYS, NO, NO_ANSWER }

/** The permission rules, kept free of Android so they can be unit tested. */
object PolicyRules {

    /** Today's behaviour (Rex chose it): everything allowed, except calls and messages ask first. */
    fun defaultLevel(c: Category): Level = when {
        c.alwaysManual -> Level.ALWAYS_MANUAL
        c == Category.PHONE_CALL || c == Category.MESSAGING -> Level.ASK
        else -> Level.ALLOW
    }

    fun decide(level: Level): Decision = when (level) {
        Level.ALLOW -> Decision.ALLOW
        Level.ASK -> Decision.ASK
        Level.DENY, Level.ALWAYS_MANUAL -> Decision.BLOCK
    }

    /** An always-manual category can only be Always me or Off: never Allow, never Ask. */
    fun canSet(alwaysManual: Boolean, level: Level): Boolean =
        !alwaysManual || level == Level.ALWAYS_MANUAL || level == Level.DENY

    /** The levels offered in the picker for [c]. */
    fun choices(c: Category): List<Level> =
        if (c.alwaysManual) listOf(Level.ALWAYS_MANUAL, Level.DENY) else listOf(Level.ALLOW, Level.ASK, Level.DENY)

    /**
     * The category a step belongs to, or null for steps that only read or answer (time, battery,
     * location, replies), are done by the agent itself (lookup, recall) or are already protected
     * by their own security check (pairing phones).
     */
    fun categoryOf(step: Step): Category? = when (step) {
        is Step.Call, is Step.AppCall -> Category.PHONE_CALL
        is Step.WhatsApp, is Step.Sms -> Category.MESSAGING
        is Step.LaunchApp, is Step.FindInApp, is Step.AskApp, is Step.ShowMap, is Step.Search, is Step.TakePhoto -> Category.OPEN_APP
        is Step.Jobs -> Category.JOB_SEARCH
        is Step.MakeFile, is Step.ShowFiles, is Step.ConvertFile, is Step.SearchPhone, is Step.RemoteGallery -> Category.FILES
        is Step.Remember, Step.ListMemories -> Category.MEMORY
        is Step.Wifi, is Step.Bluetooth, is Step.Flashlight, is Step.Alarm, is Step.Timer -> Category.DEVICE_SETTINGS
        else -> null
    }

    /** A short description of the step for the Ask card and the activity log (no message bodies). */
    fun describe(step: Step): String = when (step) {
        is Step.Call -> if (step.direct) "Call ${step.target}" else "Open the dialer for ${step.target}"
        is Step.AppCall -> "${if (step.video) "Video-call" else "Call"} ${step.contact} in ${step.app} (via XARVIS Hands)"
        is Step.WhatsApp -> "Open WhatsApp to ${step.target}" + if (step.text != null) " with a message ready" else ""
        is Step.Sms -> "Open SMS to ${step.target} with a message ready"
        is Step.LaunchApp -> "Open ${step.appName}"
        is Step.FindInApp -> "Search ${step.app} for \"${step.query}\""
        is Step.AskApp -> "Share text into ${step.app}"
        is Step.ShowMap -> "Open Maps" + (step.place?.let { " at $it" } ?: "")
        is Step.Search -> "Google \"${step.query}\""
        is Step.Jobs -> "Open job listings: ${step.titles}" + (step.place?.let { " in $it" } ?: "") + (step.site?.let { " on $it" } ?: "")
        is Step.MakeFile -> "Save ${step.block.name}"
        is Step.ShowFiles -> "Show saved files" + if (step.query.isNotBlank()) " matching \"${step.query}\"" else ""
        is Step.ConvertFile -> "Convert the attached file to ${step.format}"
        is Step.SearchPhone -> "Search your folders for \"${step.query}\""
        is Step.RemoteGallery -> "Get recent photos from ${step.device}"
        is Step.TakePhoto -> if (step.selfie) "Open the front camera to take a selfie" else "Open the camera to take a photo"
        is Step.Remember -> "Remember: ${step.fact}"
        Step.ListMemories -> "List what I remember"
        is Step.Wifi -> "Turn Wi-Fi ${onOff(step.on)}"
        is Step.Bluetooth -> "Turn Bluetooth ${onOff(step.on)}"
        is Step.Flashlight -> "Turn the flashlight ${onOff(step.on)}"
        is Step.Alarm -> "Set an alarm for %02d:%02d".format(step.hour, step.minute)
        is Step.Timer -> "Set a timer for ${step.seconds / 60} min ${step.seconds % 60} s"
        else -> step::class.simpleName.orEmpty()
    }

    /** The app, site or person the step acts on, for the log. */
    fun targetOf(step: Step): String = when (step) {
        is Step.Call -> step.target
        is Step.AppCall -> "${step.app} · ${step.contact}"
        is Step.WhatsApp -> "WhatsApp · ${step.target}"
        is Step.Sms -> "SMS · ${step.target}"
        is Step.LaunchApp -> step.appName
        is Step.FindInApp -> step.app
        is Step.AskApp -> step.app
        is Step.ShowMap -> "Maps"
        is Step.Search -> "google.com"
        is Step.Jobs -> step.site ?: "LinkedIn"
        is Step.MakeFile, is Step.ShowFiles, is Step.ConvertFile -> "Downloads/XARVIS"
        is Step.SearchPhone -> "Your folders"
        is Step.RemoteGallery -> step.device
        is Step.TakePhoto -> "Camera"
        is Step.Remember, Step.ListMemories -> "XARVIS memory"
        is Step.Wifi -> "Wi-Fi"
        is Step.Bluetooth -> "Bluetooth"
        is Step.Flashlight -> "Flashlight"
        is Step.Alarm, is Step.Timer -> "Clock"
        else -> "XARVIS"
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private val SECRET_AFTER = Regex(
        """(?i)\b(pass(?:word|code)?|pwd|otp|pin|cvv|2fa|verification code|one[- ]time code|security code)\b(\s*(?:is|=|:)?\s*)\S+""",
    )
    private val CODE = Regex("""(?<![\d+])\d{5,8}(?!\d)""")

    /** Removes anything that looks like a password or code before it's written to the log. */
    fun scrub(text: String): String = text
        .replace(SECRET_AFTER) { "${it.groupValues[1]}${it.groupValues[2]}••••" }
        .replace(CODE, "••••")
}
