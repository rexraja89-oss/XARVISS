package com.xarvis.ai.tools

import android.Manifest
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What's Ali's number?": looks up contacts whose name appears in the message and returns
 * their phone numbers and emails. Only the matching contacts are given to the model.
 */
class ContactsTool(context: Context) : DeviceTool {

    private val appContext = context.applicationContext
    private val directory = ContactsDirectory(appContext)

    override val label = "contacts"

    override fun matches(message: String) = isAbout(message)

    override suspend fun read(message: String): String? {
        if (!PermissionGate.has(appContext, READ)) PermissionGate.request(READ)
        if (!PermissionGate.has(appContext, READ)) {
            return "Contacts: unavailable, because XARVIS isn't allowed to read contacts. " +
                "The user can allow it when XARVIS asks, or in Settings > Apps > XARVIS > Permissions."
        }
        val words = ContactsDirectory.nameWords(message)
        if (words.isEmpty()) return "Contacts: the message doesn't name anyone, so no contact was looked up."
        val found = withContext(Dispatchers.IO) { describe(words) }
        // "number for pizza" with no such contact: only report the miss if contacts were asked about by name.
        return found ?: if (SAYS_CONTACT.containsMatchIn(message)) {
            "Contacts: no contact with a phone number matches " + words.joinToString(" or ") { "\"$it\"" } + "."
        } else {
            null
        }
    }

    /** The matching contacts as one line, or null if none match. */
    private fun describe(words: List<String>): String? {
        // Only the best matches: other words in the message ("check my benco...") mustn't drag in strangers.
        val found = directory.search(words)
        val best = found.filter { it.score == found.first().score }.take(MAX_RESULTS)
        if (best.isEmpty()) return null
        val emails = directory.emails(best.map { it.id })
        return "$FOUND " + best.joinToString("; ") { p ->
            buildString {
                append(p.name).append(": ").append(p.numbers.joinToString(", ") { "${it.value} (${it.label})" })
                emails[p.id]?.let { append(", email ").append(it.joinToString(", ")) }
            }
        }
    }

    internal companion object {
        fun isAbout(message: String) = TOPIC.containsMatchIn(message.lowercase())

        const val READ = Manifest.permission.READ_CONTACTS
        const val MAX_RESULTS = 5

        /** Starts the line when contacts were found. */
        const val FOUND = "Contacts matching the message:"

        /** "contact", "contacts", and the usual misspellings ("contack", "contect"). */
        val SAYS_CONTACT = Regex("""\bconta?[ck]+t?s?\b|\bcontects?\b""", RegexOption.IGNORE_CASE)

        val TOPIC = Regex(
            """\b(contacts?|contack|contect|contac|kontakt|(?:his|her|their)\s+(?:phone\s+|mobile\s+)?number|phone numbers?|mobile numbers?|cell numbers?|numbers? for|""" +
                """whatsapp number|email of|email address of|\w+'s (?:number|phone|mobile|email)|""" +
                """ka (?:number|nmbr|phone|mobile)|ki (?:number|email))\b"""
        )
    }
}
