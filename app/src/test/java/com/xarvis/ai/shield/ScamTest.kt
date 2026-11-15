package com.xarvis.ai.shield

import com.xarvis.ai.shield.Scam.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamTest {
    private fun lvl(t: String) = Scam.message(t).level

    @Test fun realScamsAreCaught() {
        assertEquals(Level.DANGER, lvl("Dear customer, your Emirates NBD account will be blocked today. Update your KYC at http://emiratesnbd-secure-update.top/login"))
        assertEquals(Level.DANGER, lvl("Hi this is bank officer, please share the OTP you received to verify your card"))
        assertEquals(Level.DANGER, lvl("Please install AnyDesk so I can fix your account"))
        assertTrue(lvl("Emirates Post: your parcel is on hold due to unpaid customs fee of 6 AED. Pay now: https://bit.ly/3xYz") >= Level.WARN)
        assertTrue(lvl("Congratulations! You have won AED 50,000 in the UAE lucky draw. Claim your prize now") >= Level.WARN)
        assertTrue(lvl("Part time job: earn 500 AED daily by liking YouTube videos. Contact on WhatsApp") >= Level.WARN)
        assertTrue(lvl("Invest in our crypto mining plan, guaranteed 30% profit weekly") >= Level.WARN)
        assertTrue(lvl("Papa my phone is broken this is my new number, please send money urgently") >= Level.WARN)
        assertTrue(lvl("Aapka SBI khata band ho jayega, KYC update karein turant") >= Level.WARN)
    }

    @Test fun ordinaryMessagesStayQuiet() {
        assertEquals(Level.SAFE, lvl("Hey Rex, are we meeting at 7 for dinner?"))
        assertEquals(Level.SAFE, lvl("Your salary of AED 9,500 has been credited to your account"))
        assertEquals(Level.SAFE, lvl("Meeting moved to tomorrow, see https://meet.google.com/abc-defg-hij"))
        assertEquals(Level.SAFE, lvl("Check this video https://www.youtube.com/watch?v=abc"))
    }

    @Test fun aRealCodeMessageIsOnlyAWarning() {
        val v = Scam.message("123456 is your WhatsApp code. Do not share this code with anyone.")
        assertEquals(Level.WARN, v.level)
        assertTrue(v.reasons.first().contains("didn't just ask"))
    }

    @Test fun links() {
        assertEquals(Level.DANGER, Scam.link("https://login.adcb-verify.com/x").level)
        assertEquals(Level.SAFE, Scam.link("https://www.adcb.com/en/personal").level)
        assertEquals(Level.SAFE, Scam.link("https://onlinesbi.sbi/").level)
        assertEquals(Level.DANGER, Scam.link("http://192.168.4.20/pay").level)
        assertEquals(Level.DANGER, Scam.link("https://xn--pypal-4ve.com").level)
        assertEquals(Level.WARN, Scam.link("https://bit.ly/abc").level)
        assertEquals(Level.WARN, Scam.link("https://free-gift-claim.xyz").level)
        assertEquals(Level.DANGER, Scam.link("https://www.paypal.com@evil.example/login").level)
        assertEquals("emiratesnbd.com", Scam.registrable("online.emiratesnbd.com"))
        assertEquals("dubaipolice.gov.ae", Scam.registrable("www.dubaipolice.gov.ae"))
        assertEquals(listOf("https://bit.ly/3xYz"), Scam.links("Pay now: https://bit.ly/3xYz."))
    }
}
