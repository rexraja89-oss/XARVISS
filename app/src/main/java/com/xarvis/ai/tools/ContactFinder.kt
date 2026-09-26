package com.xarvis.ai.tools

import android.Manifest
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The "find contact" tool: this phone's contacts matching a name, as "Name: number (type)" lines. */
class ContactFinder(context: Context) {

    private val appContext = context.applicationContext
    private val directory = ContactsDirectory(appContext)

    /** Matching contacts (best matches only, up to 5); null if XARVIS isn't allowed to read contacts. */
    suspend fun find(name: String): List<String>? {
        if (!PermissionGate.has(appContext, READ)) PermissionGate.request(READ)
        if (!PermissionGate.has(appContext, READ)) return null
        val words = ContactsDirectory.nameWords(name)
        if (words.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val found = directory.search(words)
            found.filter { it.score == found.first().score }.take(MAX_RESULTS).map { c ->
                c.name + ": " + c.numbers.joinToString(", ") { "${it.value} (${it.label})" }
            }
        }
    }

    private companion object {
        const val READ = Manifest.permission.READ_CONTACTS
        const val MAX_RESULTS = 5
    }
}
