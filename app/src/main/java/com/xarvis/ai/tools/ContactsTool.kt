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

    override fun matches(message: String) = TOPIC.containsMatchIn(message.lowercase())

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
        return found ?: if (message.contains("contact", ignoreCase = true)) {
            "Contacts: no contact with a phone number matches " + words.joinToString(" or ") { "\"$it\"" } + "."
        } else {
            null
        }
    }

    /** The matching contacts as one line, or null if none match. */
    private fun describe(words: List<String>): String? {
        val best = directory.search(words).take(MAX_RESULTS)
        if (best.isEmpty()) return null
        val emails = directory.emails(best.map { it.id })
        return "Contacts matching the message: " + best.joinToString("; ") { p ->
            buildString {
                append(p.name).append(": ").append(p.numbers.joinToString(", ") { "${it.value} (${it.label})" })
                emails[p.id]?.let { append(", email ").append(it.joinToString(", ")) }
            }
        }
    }

    private companion object {
        const val READ = Manifest.permission.READ_CONTACTS
        const val MAX_RESULTS = 5

        val TOPIC = Regex(
            """\b(contacts?|phone numbers?|mobile numbers?|cell numbers?|numbers? for|""" +
                """whatsapp number|email of|email address of|\w+'s (?:number|phone|mobile|email)|""" +
                """ka (?:number|nmbr|phone|mobile)|ki (?:number|email))\b"""
        )
    }
}
