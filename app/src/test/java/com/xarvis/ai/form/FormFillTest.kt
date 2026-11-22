package com.xarvis.ai.form

import com.xarvis.ai.agent.PhoneAgent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFillTest {
    private fun el(n: Int, label: String, y: Int, edit: Boolean = false, pw: Boolean = false, hint: String = "", value: String = "") =
        PhoneAgent.Element(n, label, tappable = edit, editable = edit, password = pw, x = 300, y = y, hint = hint, value = value)

    @Test
    fun commands() {
        assertEquals(FormCommands.Cmd(false), FormCommands.parse("fill this form"))
        assertEquals(FormCommands.Cmd(false), FormCommands.parse("Xarvis, fill in the form with my CV please"))
        assertEquals(FormCommands.Cmd(false), FormCommands.parse("autofill this form"))
        assertEquals(FormCommands.Cmd(true), FormCommands.parse("fill the form on my S22"))
        assertEquals(FormCommands.Cmd(true), FormCommands.parse("fill this application form on my s22 ultra from my cv"))
        assertNull(FormCommands.parse("fill the bottle with water"))
        assertNull(FormCommands.parse("what is a form"))
    }

    @Test
    fun findsBoxesWithTheirLabelsAndSkipsPrivateOnes() {
        val screen = PhoneAgent.Screen(
            "Chrome", listOf(
                el(1, "First name", 100), el(2, "text box", 160, edit = true),
                el(3, "Email address", 240), el(4, "you@example.com", 300, edit = true, hint = "you@example.com"),
                el(5, "ID number", 380), el(6, "text box", 440, edit = true),
                el(7, "Password", 520), el(8, "text box", 580, edit = true, pw = true),
            ), 1080, 2300,
        )
        val f = FormFill.fields(screen)
        assertEquals(3, f.size)                                  // the password box isn't even listed
        assertEquals("First name", f[0].near)
        assertEquals("Email address", f[1].near)
        assertTrue(FormFill.sensitive(f[2]))
        assertFalse(FormFill.sensitive(f[0]))
    }

    @Test
    fun answersAreReadTolerantly() {
        val a = FormFill.answers("Sure!\n```json\n{\"1\": \"Sajjad Raja\", \"2\": null, \"3\": \"\", \"x\": \"y\"}\n```")
        assertEquals(mapOf(1 to "Sajjad Raja"), a)
        assertTrue(FormFill.answers("no json here").isEmpty())
    }

    @Test
    fun fillsFromTheCvScrollsAndNeverTouchesPrivateOrFilledBoxes() = runBlocking {
        val page1 = PhoneAgent.Screen("Chrome", listOf(
            el(1, "Full name", 100), el(2, "text box", 160, edit = true),
            el(3, "Phone", 240), el(4, "text box", 300, edit = true),
            el(5, "Passport number", 380), el(6, "text box", 440, edit = true),
        ), 1080, 2300)
        val page2 = PhoneAgent.Screen("Chrome", listOf(
            el(1, "Date of birth", 100), el(2, "text box", 160, edit = true),
            el(3, "City", 240), el(4, "Dubai", 300, edit = true, value = "Dubai"),
        ), 1080, 2300)
        val pages = listOf(page1, page2, page2)
        var i = 0
        val typed = ArrayList<String>()
        val env = object : FormFill.Env {
            override suspend fun screen() = pages[minOf(i, pages.size - 1)]
            override suspend fun setField(x: Int, y: Int, text: String): Boolean { typed += "$y=$text"; return true }
            override suspend fun scrollDown() { i++ }
            override suspend fun think(system: String, prompt: String): String {
                assertFalse(prompt.contains("Passport"))           // private boxes never go to the brain
                return if (prompt.contains("Full name")) """{"2": "Sajjad Raja", "4": "+971 50 000 0000"}""" else """{"2": null}"""
            }
            override fun progress(line: String) {}
            override suspend fun pause(ms: Long) {}
        }
        val r = FormFill.run(env, cv = "Sajjad Raja … Blasting & Painting Supervisor", details = "Phone: +971 50 000 0000")
        assertEquals(listOf("160=Sajjad Raja", "300=+971 50 000 0000"), typed)
        assertEquals(listOf("Full name", "Phone"), r.filled)
        assertEquals(listOf("Passport number"), r.sensitive)
        assertEquals(listOf("Date of birth"), r.notInCv)
        assertEquals(listOf("City"), r.kept)
        val s = FormFill.summary(r)
        assertTrue(s.contains("Filled 2") && s.contains("Submit yourself"))
    }
}
