package com.xarvis.ai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentParseTest {
    @Test fun readsOneActionLine() {
        assertEquals(PhoneAgent.Action.Open("WhatsApp"), PhoneAgent.parse("OPEN WhatsApp"))
        assertEquals(PhoneAgent.Action.Tap(7), PhoneAgent.parse("TAP 7"))
        assertEquals(PhoneAgent.Action.Tap(3), PhoneAgent.parse("Sure! I'll tap the chat.\nTAP [3]"))
        assertEquals(PhoneAgent.Action.Type(2, "hello there"), PhoneAgent.parse("TYPE 2 \"hello there\""))
        assertEquals(PhoneAgent.Action.Scroll(true), PhoneAgent.parse("**SCROLL DOWN**"))
        assertEquals(PhoneAgent.Action.Scroll(false), PhoneAgent.parse("scroll up"))
        assertEquals(PhoneAgent.Action.Back, PhoneAgent.parse("BACK"))
        assertEquals(PhoneAgent.Action.Done("Ali said: see you at 5"), PhoneAgent.parse("DONE: Ali said: see you at 5"))
        assertTrue(PhoneAgent.parse("FAIL no such chat") is PhoneAgent.Action.Fail)
        assertNull(PhoneAgent.parse("I think we should look around first."))
    }

    @Test fun safetyNeverTouchesMoneyOrPasswords() {
        listOf("Pay now", "Enter OTP", "Password", "UPI PIN", "Send money", "Bank account", "Card number", "Buy now", "Start free trial", "Go Pro")
            .forEach { assertEquals(it, PhoneAgent.Safety.Verdict.NEVER, PhoneAgent.Safety.check(it, "do anything you need")) }
        // Buttons that reach people or can't be undone: only when Rex's request asks for that kind of thing.
        listOf("Send", "Delete chat", "Post", "Voice call", "Log out", "Install")
            .forEach { assertEquals(it, PhoneAgent.Safety.Verdict.OUT_OF_SCOPE, PhoneAgent.Safety.check(it, "open Ninja VPN and connect")) }
        assertEquals(PhoneAgent.Safety.Verdict.OK, PhoneAgent.Safety.check("Send", "send hi to Ali on WhatsApp"))
        assertEquals(PhoneAgent.Safety.Verdict.OK, PhoneAgent.Safety.check("Upload", "upload the video to YouTube as private"))
        assertEquals(PhoneAgent.Safety.Verdict.OK, PhoneAgent.Safety.check("Delete", "delete the old screenshots"))
        // Signing in, Allow, OK, Connect: part of using an app, never asked.
        listOf("Chats", "Search", "Ali: send me the file when you get home please", "Settings", "Sign in", "Continue with Google", "OK", "Allow", "Connect")
            .forEach { assertEquals(it, PhoneAgent.Safety.Verdict.OK, PhoneAgent.Safety.check(it, "open Ninja VPN and connect")) }
        assertTrue(PhoneAgent.Safety.secret("482913"))
        assertTrue(PhoneAgent.Safety.secret("4111 1111 1111 1111"))
        assertFalse(PhoneAgent.Safety.secret("meet at 1030 near gate 2"))
    }

    @Test fun bankingAndPaymentAppsAreNeverUsed() {
        listOf("Emirates NBD", "HDFC Bank", "Google Pay", "PhonePe", "Paytm", "Samsung Wallet", "BHIM UPI", "com.phonepe.app", "PayPal", "Binance")
            .forEach { assertTrue(it, PhoneAgent.Safety.moneyApp(it)) }
        listOf("Ninja VPN", "WhatsApp", "CapCut", "YouTube", "Chrome", "Play Store", "payslip", "Spotify", "Settings")
            .forEach { assertFalse(it, PhoneAgent.Safety.moneyApp(it)) }
    }
}

class PhoneAgentRunTest {
    private fun el(n: Int, label: String, tap: Boolean = true, edit: Boolean = false, pw: Boolean = false) =
        PhoneAgent.Element(n, label, tap, edit, pw, n * 10, n * 20)

    /** A pretend phone: shows the scripted screens in turn and records what XARVIS did. */
    private class FakePhone(private val screens: List<PhoneAgent.Screen>) : PhoneAgent.Target {
        override val name = "Test phone"
        val did = mutableListOf<String>()
        var shown = 0
        var ended = false
        override suspend fun begin() { did += "begin" }
        override suspend fun end() { ended = true }
        override suspend fun screen() = screens[minOf(shown++, screens.size - 1)]
        override suspend fun tap(x: Int, y: Int) { did += "tap $x,$y" }
        override suspend fun type(text: String): Boolean { did += "type $text"; return true }
        override suspend fun scroll(down: Boolean) { did += "scroll" }
        override suspend fun key(name: String) { did += "key $name" }
        override suspend fun open(app: String): String { did += "open $app"; return "opened $app" }
        override suspend fun stopped() = false
    }

    private fun agent(phone: FakePhone, answers: List<String>): PhoneAgent {
        val queue = ArrayDeque(answers)
        return PhoneAgent(phone, think = { queue.removeFirstOrNull() }, progress = {}, pause = {})
    }

    @Test fun sendsAMessageWithoutAskingBecauseRexAskedForIt() = runBlocking {
        val home = PhoneAgent.Screen("XARVIS", listOf(el(1, "Ask XARVIS")), 1080, 2340)
        val chats = PhoneAgent.Screen("WhatsApp", listOf(el(1, "Search", edit = true), el(2, "Ali"), el(3, "Mom")), 1080, 2340)
        val chat = PhoneAgent.Screen("WhatsApp", listOf(el(1, "Message", edit = true), el(2, "Send")), 1080, 2340)
        val phone = FakePhone(listOf(home, chats, chat, chat, chat))
        val result = agent(phone, listOf("OPEN WhatsApp", "TAP 2", "TYPE 1 I'm on my way", "TAP 2", "DONE Sent \"I'm on my way\" to Ali."))
            .run("message Ali on WhatsApp that I'm on my way")
        assertEquals("Sent \"I'm on my way\" to Ali.", result)
        assertEquals(listOf("begin", "open WhatsApp", "tap 20,40", "tap 10,20", "type I'm on my way", "tap 20,40"), phone.did)
        assertTrue(phone.ended)
    }

    @Test fun skipsWhatTheRequestDidntAskFor() = runBlocking {
        // "Install" on an ad while connecting a VPN: not tapped, not asked about; XARVIS carries on.
        val vpn = PhoneAgent.Screen("Ninja VPN", listOf(el(1, "Install"), el(2, "Connect")), 1080, 2340)
        val phone = FakePhone(listOf(vpn, vpn, vpn))
        val result = agent(phone, listOf("TAP 1", "TAP 2", "DONE Ninja VPN is connected")).run("open Ninja VPN and connect")
        assertEquals("Ninja VPN is connected", result)
        assertEquals(listOf("begin", "tap 20,40"), phone.did)
    }

    @Test fun neverOpensBankingApps() = runBlocking {
        val phone = FakePhone(listOf(PhoneAgent.Screen("XARVIS", listOf(el(1, "Ask XARVIS")), 1080, 2340)))
        assertTrue(agent(phone, listOf("OPEN Emirates NBD")).run("check my balance").startsWith("I don't open banking"))
        assertFalse(phone.did.any { it.startsWith("open") })
    }

    @Test fun neverTypesPasswordsOrTapsPayments() = runBlocking {
        val login = PhoneAgent.Screen("Bank", listOf(el(1, "Password", tap = false, edit = true, pw = true), el(2, "Pay now")), 1080, 2340)
        val typed = agent(FakePhone(listOf(login)), listOf("TYPE 1 hunter2")).run("log in")
        assertTrue(typed.startsWith("I stopped"))
        val paid = FakePhone(listOf(login))
        assertTrue(agent(paid, listOf("TAP 2")).run("pay the bill").startsWith("I stopped"))
        assertFalse(paid.did.any { it.startsWith("tap") })
    }

    @Test fun givesUpAfterUnclearAnswers() = runBlocking {
        val phone = FakePhone(listOf(PhoneAgent.Screen("WhatsApp", listOf(el(1, "Chats")), 1080, 2340)))
        val result = agent(phone, listOf("hmm", "let me think", "not sure")).run("do something")
        assertTrue(result.startsWith("I couldn't work out"))
        assertTrue(phone.ended)
    }
}

class PhoneTaskTest {
    private val s22 = setOf("galaxy", "s22", "ultra")

    @Test fun jobsInApps() {
        listOf(
            "send my last photo to my wife on WhatsApp", "check if Ali replied on imo", "play Arijit Singh on YouTube",
            "turn on dark mode in settings", "hey xarvis, search for flights to Dubai on chrome",
            "can you check my unread emails in gmail", "message Ali on telegram that I'm late", "tell me who messaged me on whatsapp",
        ).forEach { m ->
            val t = PhoneTask.parse(m, s22)
            assertTrue(m, t != null && !t.onOtherPhone)
        }
        assertEquals(PhoneTask.Task("read my last message on whatsapp", true), PhoneTask.parse("read my last message on whatsapp on my s22", s22))
        assertEquals(PhoneTask.Task("open settings and turn on dark mode", false), PhoneTask.parse("do this for me: open settings and turn on dark mode", s22))
    }

    @Test fun notJobs() {
        listOf(
            "how do I turn on dark mode in settings", "call Ali on WhatsApp", "video call mom on imo", "open whatsapp",
            "what's the battery on my s22", "send hi to Ali", "turn on wifi", "set an alarm for 6", "what is whatsapp",
            "control my s22", "who is in photo",
        ).forEach { assertNull(it, PhoneTask.parse(it, s22)) }
    }
}
