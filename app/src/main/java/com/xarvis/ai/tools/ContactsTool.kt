package com.xarvis.ai.tools

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What's Ali's number?": looks up contacts whose name appears in the message and returns
 * their phone numbers and emails. Only the matching contacts are given to the model.
 */
class ContactsTool(context: Context) : DeviceTool {

    private val appContext = context.applicationContext

    override val label = "contacts"

    override fun matches(message: String) = TOPIC.containsMatchIn(message.lowercase())

    override suspend fun read(message: String): String? {
        if (!PermissionGate.has(appContext, READ)) PermissionGate.request(READ)
        if (!PermissionGate.has(appContext, READ)) {
            return "Contacts: unavailable, because XARVIS isn't allowed to read contacts. " +
                "The user can allow it when XARVIS asks, or in Settings > Apps > XARVIS > Permissions."
        }
        val words = nameWords(message)
        if (words.isEmpty()) return "Contacts: the message doesn't name anyone, so no contact was looked up."
        val found = withContext(Dispatchers.IO) { lookUp(words) }
        // "number for pizza" with no such contact: only report the miss if contacts were asked about by name.
        return found ?: if (message.contains("contact", ignoreCase = true)) {
            "Contacts: no contact with a phone number matches " + words.joinToString(" or ") { "\"$it\"" } + "."
        } else {
            null
        }
    }

    /** The matching contacts as one line, or null if none match. */
    private fun lookUp(words: List<String>): String? {
        val people = LinkedHashMap<Long, Person>()
        appContext.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val score = score(name, words)
                if (score == 0) continue
                val type = Phone.getTypeLabel(appContext.resources, c.getInt(3), c.getString(4)).toString().lowercase()
                people.getOrPut(c.getLong(0)) { Person(name, score) }.numbers += "${c.getString(2)} ($type)"
            }
        }
        if (people.isEmpty()) return null

        val best = people.entries.sortedByDescending { it.value.score }.take(MAX_RESULTS)
        val ids = best.joinToString(",") { it.key.toString() }
        appContext.contentResolver.query(
            Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS), "${Email.CONTACT_ID} IN ($ids)", null, null,
        )?.use { c ->
            while (c.moveToNext()) people[c.getLong(0)]?.emails?.add(c.getString(1) ?: continue)
        }
        return "Contacts matching the message: " + best.joinToString("; ") { (_, p) ->
            buildString {
                append(p.name).append(": ").append(p.numbers.distinct().joinToString(", "))
                if (p.emails.isNotEmpty()) append(", email ").append(p.emails.distinct().joinToString(", "))
            }
        }
    }

    private class Person(val name: String, val score: Int) {
        val numbers = mutableListOf<String>()
        val emails = mutableListOf<String>()
    }

    private companion object {
        const val READ = Manifest.permission.READ_CONTACTS
        const val MAX_RESULTS = 5

        val TOPIC = Regex(
            """\b(contacts?|phone numbers?|mobile numbers?|cell numbers?|numbers? for|""" +
                """whatsapp number|email of|email address of|\w+'s (?:number|phone|mobile|email)|""" +
                """ka (?:number|nmbr|phone|mobile)|ki (?:number|email))\b"""
        )

        /** Words that are never part of a name in these questions. */
        val STOP = setOf(
            "what", "whats", "what's", "is", "are", "the", "a", "an", "my", "me", "of", "for", "to", "give", "show",
            "tell", "find", "get", "send", "please", "pls", "plz", "can", "you", "i", "need", "want", "do", "have",
            "number", "numbers", "phone", "mobile", "cell", "contact", "contacts", "email", "address", "whatsapp",
            "and", "or", "with", "in", "on", "saved", "his", "her", "their", "ka", "ki", "ke", "hai", "kya", "do",
            "mujhe", "batao", "bata", "dikhao", "nmbr", "call", "message", "text", "xarvis", "hey", "hi",
        )

        fun nameWords(message: String): List<String> = message.lowercase()
            .replace(Regex("""'s\b"""), "")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 && it !in STOP }
            .distinct()

        /** How many of [words] start one of the words in [name]; 0 means no match. */
        fun score(name: String, words: List<String>): Int {
            val parts = name.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
            return words.count { w -> parts.any { it == w || (w.length >= 3 && it.startsWith(w)) } }
        }
    }
}
