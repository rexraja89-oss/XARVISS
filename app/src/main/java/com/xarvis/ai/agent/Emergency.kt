package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step

/**
 * Recognises an explicit emergency-call request ("XARVIS, call the police", "I need an ambulance")
 * and maps it to the right number for the country the phone is in. Deliberately requires a clear
 * call/connect word AND a named service, so an ordinary mention ("the police were there") never
 * dials. The call is placed directly and handsfree — no "Ask me" card to slow things down in a
 * crisis — so the trigger has to be unmistakable.
 */
object Emergency {

    private val WANTS = Regex("""(?i)\b(call|dial|connect|phone|ring|get me|reach|contact|need|send|bula\w*|lagao|lga do|karo)\b""")

    private val POLICE = Regex("""(?i)\b(police|cops?|police\s*station|thana|emergency services?)\b""")
    private val AMBULANCE = Regex("""(?i)\b(ambulance|paramedics?|medical\s*(?:help|emergency)|emt)\b""")
    private val FIRE = Regex("""(?i)\b(fire\s*(?:brigade|service|department|engine|truck|fighters?))\b""")
    private val GENERAL = Regex("""(?i)\b(emergency|sos|999|998|997|112|911|help me|bachao|madad)\b""")

    /** The service to dial, or null if this isn't an emergency-call request. [countryIso] like "ae", "in". */
    fun detect(message: String, countryIso: String?): Step.EmergencyCall? {
        if (!WANTS.containsMatchIn(message)) return null
        val c = (countryIso ?: "").lowercase()
        return when {
            FIRE.containsMatchIn(message) -> Step.EmergencyCall(fire(c), "the fire service")
            AMBULANCE.containsMatchIn(message) -> Step.EmergencyCall(ambulance(c), "an ambulance")
            POLICE.containsMatchIn(message) -> Step.EmergencyCall(police(c), "the police")
            GENERAL.containsMatchIn(message) -> Step.EmergencyCall(general(c), "emergency services")
            else -> null
        }
    }

    // 112 is the universal GSM emergency number (works on most networks, even with no SIM), so it's
    // the safe default when the country is unknown.
    private fun police(c: String) = when (c) {
        "ae" -> "999"; "in" -> "100"; "us", "ca" -> "911"; "gb", "ie" -> "999"; "au" -> "000"; else -> "112"
    }
    private fun ambulance(c: String) = when (c) {
        "ae" -> "998"; "in" -> "102"; "us", "ca" -> "911"; "gb", "ie" -> "999"; "au" -> "000"; else -> "112"
    }
    private fun fire(c: String) = when (c) {
        "ae" -> "997"; "in" -> "101"; "us", "ca" -> "911"; "gb", "ie" -> "999"; "au" -> "000"; else -> "112"
    }
    private fun general(c: String) = when (c) {
        "ae" -> "999"; "us", "ca" -> "911"; "au" -> "000"; else -> "112"
    }
}
