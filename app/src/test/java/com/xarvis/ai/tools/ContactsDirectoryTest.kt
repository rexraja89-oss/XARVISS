package com.xarvis.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsDirectoryTest {

    @Test fun keepsOnlyNameWords() {
        assertEquals(listOf("ali"), ContactsDirectory.nameWords("what's Ali's number?"))
        assertEquals(listOf("ahmed"), ContactsDirectory.nameWords("Ahmed ka number batao"))
        assertEquals(listOf("ali", "khan"), ContactsDirectory.nameWords("call Ali Khan"))
    }

    @Test fun wholeNamesBeatPrefixes() {
        val words = listOf("ali")
        assertTrue(ContactsDirectory.score("Ali Khan", words) > ContactsDirectory.score("Alina", words))
        assertEquals(0, ContactsDirectory.score("Sara", words))
    }

    @Test fun moreMatchingWordsScoreHigher() {
        val words = listOf("ali", "khan")
        assertTrue(ContactsDirectory.score("Ali Khan", words) > ContactsDirectory.score("Ali Raza", words))
    }
}
