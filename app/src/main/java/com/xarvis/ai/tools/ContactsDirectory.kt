package com.xarvis.ai.tools

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Finds contacts by the words of a name, for both answering ("what's Ali's number?") and
 * acting ("call Ali"). Callers must hold READ_CONTACTS; queries block, so call off the main thread.
 */
class ContactsDirectory(context: Context) {

    private val appContext = context.applicationContext

    class Number(val value: String, val type: Int, val label: String)

    class Contact(val id: Long, val name: String, val score: Int) {
        val numbers = mutableListOf<Number>()

        /** The number to call or message: a mobile one if there is one. */
        val bestNumber: Number? get() = numbers.firstOrNull { it.type == Phone.TYPE_MOBILE } ?: numbers.firstOrNull()
    }

    /** Contacts with a phone number whose name matches [words], best match first. */
    fun search(words: List<String>): List<Contact> {
        if (words.isEmpty()) return emptyList()
        val found = LinkedHashMap<Long, Contact>()
        appContext.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val score = score(name, words)
                if (score == 0) continue
                val type = c.getInt(3)
                val label = Phone.getTypeLabel(appContext.resources, type, c.getString(4)).toString().lowercase()
                val contact = found.getOrPut(c.getLong(0)) { Contact(c.getLong(0), name, score) }
                if (contact.numbers.none { same(it.value, number) }) contact.numbers += Number(number, type, label)
            }
        }
        return found.values.sortedByDescending { it.score }
    }

    fun emails(ids: Collection<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, MutableList<String>>()
        appContext.contentResolver.query(
            Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS),
            "${Email.CONTACT_ID} IN (${ids.joinToString(",")})", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                val list = out.getOrPut(c.getLong(0)) { mutableListOf() }
                if (address !in list) list += address
            }
        }
        return out
    }

    private fun same(a: String, b: String) = a.filter { it.isDigit() } == b.filter { it.isDigit() }

    companion object {
        /** Words that are never part of a name in these messages. */
        private val STOP = setOf(
            "what", "whats", "what's", "is", "are", "the", "a", "an", "my", "me", "of", "for", "to", "give", "show",
            "tell", "find", "get", "send", "please", "pls", "plz", "can", "you", "i", "need", "want", "do", "have",
            "number", "numbers", "phone", "mobile", "cell", "contact", "contacts", "email", "address", "whatsapp",
            "and", "or", "with", "in", "on", "saved", "his", "her", "their", "ka", "ki", "ke", "ko", "hai", "kya",
            "mujhe", "batao", "bata", "dikhao", "nmbr", "call", "message", "text", "sms", "xarvis", "hey", "hi",
            "karo", "kro", "lagao", "now", "right", "abhi",
        )

        private val WORD_SPLIT = Regex("""[^\p{L}\p{N}]+""")

        fun nameWords(message: String): List<String> = message.lowercase()
            .replace(Regex("""'s\b"""), "")
            .split(WORD_SPLIT)
            .filter { it.length >= 2 && it !in STOP }
            .distinct()

        /** 2 points per word that is a whole word of [name], 1 per word that starts one; 0 means no match. */
        fun score(name: String, words: List<String>): Int {
            val parts = name.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }
            return words.sumOf { w ->
                when {
                    parts.any { it == w } -> 2
                    w.length >= 3 && parts.any { it.startsWith(w) } -> 1
                    else -> 0
                }.toInt()
            }
        }
    }
}
