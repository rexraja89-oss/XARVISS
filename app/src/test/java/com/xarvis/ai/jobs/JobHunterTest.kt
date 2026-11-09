package com.xarvis.ai.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    private val rates = emptyMap<String, Double>() // the fallback table

    @Test fun readsSalariesInAnyCurrency() {
        val usd = Money.parse("Salary: USD 3,500 - 4,500 per month, rotation 28/28")!!
        assertEquals("USD", usd.currency)
        assertEquals(3500.0, usd.low, 0.1)
        assertEquals(4500.0, usd.high, 0.1)
        assertEquals(396_000L, usd.inrPerMonth(rates))

        val aed = Money.parse("Package AED 9000 + accommodation")!!
        assertEquals("AED", aed.currency)
        assertEquals(216_000L, aed.inrPerMonth(rates))

        val naira = Money.parse("Remuneration: ₦1,500,000 monthly")!!
        assertEquals("NGN", naira.currency)
        assertEquals(87_000L, naira.inrPerMonth(rates)) // below Rex's minimum

        val day = Money.parse("Day rate \$250 per day")!!
        assertEquals(250.0 * 26 * 88, day.inrPerMonth(rates)!!.toDouble(), 1.0)

        val year = Money.parse("£48,000 per annum")!!
        assertEquals(48_000.0 / 12 * 116, year.inrPerMonth(rates)!!.toDouble(), 1.0)
    }

    @Test fun noCurrencyMeansNotStated() {
        assertNull(Money.parse("Salary: negotiable"))
        assertNull(Money.parse("Salary 4500 depending on experience")) // dollars or naira? not guessed
        assertNull(Money.parse("Minimum 10 years experience in blasting and painting"))
    }

    @Test fun rupeesInIndianGrouping() {
        assertEquals("₹1,50,000", Money.inr(150_000))
        assertEquals("₹3,96,000", Money.inr(396_000))
        assertEquals("₹12,34,567", Money.inr(1_234_567))
    }
}

class CountriesTest {
    @Test fun findsRexsCountries() {
        assertEquals("Nigeria", Countries.detect("Lagos, Nigeria")?.name)
        assertEquals("Kazakhstan", Countries.detect("", "Painting Supervisor – Atyrau")?.name)
        assertEquals("UAE", Countries.detect("Ruwais")?.name)
        assertEquals("Europe", Countries.detect("Stavanger, Norway")?.name)
        assertEquals("Russia", Countries.detect("Yamal LNG, Siberia")?.name)
        assertEquals(1, Countries.byName("nigeria")?.rank)
        assertEquals("Russia", Countries.byName("Siberia")?.name)
        assertNull(Countries.detect("Mumbai, India"))
        assertNull(Countries.detect("", "", "our duke office")) // "UK" only in capitals
    }
}

class JobChecksTest {
    @Test fun keepsOnlyHisKindOfJob() {
        assertTrue(JobChecks.relevant("Blasting & Painting Supervisor"))
        assertTrue(JobChecks.relevant("Painting Superintendent - Offshore"))
        assertTrue(JobChecks.relevant("Coating Foreman (Rotation 28/28)"))
        assertTrue(JobChecks.relevant("Blasting and Painting Instructor"))
        assertTrue(JobChecks.relevant("Insulation Supervisor"))
        assertFalse(JobChecks.relevant("Painting Inspector NACE Level 2"))
        assertFalse(JobChecks.relevant("Painter"))
        assertFalse(JobChecks.relevant("Scaffolding Supervisor"))
        assertEquals(JobKind.TRAINER, JobChecks.kind("Blasting & Painting Trainer"))
        assertEquals(JobKind.SUPERVISOR, JobChecks.kind("Painting Supervisor"))
    }

    @Test fun findsTheApplicationEmail() {
        val ad = "Send your CV to careers@saipem.com or hr.nigeria@saipem.com. Questions: info@jobboard.com. Logo: icon@2x.png"
        val emails = JobChecks.emails(ad)
        assertTrue("careers@saipem.com" in emails)
        assertFalse(emails.any { it.endsWith(".png") })
        assertEquals("careers@saipem.com", JobChecks.bestEmail(emails, "Saipem"))
        assertEquals(listOf("jobs@gulfmanpower.ae"), JobChecks.emails("email: jobs [at] gulfmanpower [dot] ae"))
        assertEquals("hr@company.com", JobChecks.bestEmail(listOf("someone@gmail.com", "hr@company.com")))
        assertTrue(JobChecks.emails("noreply@linkedin.com no-reply@site.com").isEmpty())
    }

    @Test fun trustSystem() {
        val good = JobChecks.trust("Saipem", "careers@saipem.com", "Painting Supervisor. No recruitment fees.", 300_000)
        assertEquals(Verdict.AUTHENTIC, good.verdict)

        val scam = JobChecks.trust("Global Jobs", "globaljobs@gmail.com", "Selected candidates must pay visa processing fee of 500 USD", null)
        assertEquals(Verdict.SCAM, scam.verdict)
        assertTrue(scam.reasons.any { it.contains("money") })

        val free = JobChecks.trust("ABC Manpower", "abcmanpower@gmail.com", "Urgent requirement", null)
        assertEquals(Verdict.SUSPICIOUS, free.verdict)

        val covered = JobChecks.trust("Altrad", "jobs@altrad.com", "Visa cost covered by the company. Ticket fee paid by employer.", null)
        assertEquals(Verdict.AUTHENTIC, covered.verdict)

        assertEquals(Verdict.SCAM, JobChecks.trust("Saipem", "careers@saipem.com", "", null, blocked = listOf("saipem.com")).verdict)
        assertEquals(Verdict.AUTHENTIC, JobChecks.trust("ABC Manpower", "abc@gmail.com", "", null, trusted = listOf("abc manpower")).verdict)
        assertEquals(Verdict.SUSPICIOUS, JobChecks.trust("", null, "Contact on WhatsApp +234...", null).verdict)
    }

    @Test fun warnsAboutWhatHeLacks() {
        val w = JobChecks.warnings("Must hold NACE Level 2 and BOSIET. Minimum 15 years experience. Nigerian nationals only.")
        assertTrue(w.any { it.contains("NACE") })
        assertTrue(w.any { it.contains("BOSIET") })
        assertTrue(w.any { it.contains("15+ years") })
        assertTrue(w.any { it.contains("Nigerian") })
        assertTrue(JobChecks.warnings("Minimum 10 years experience").isEmpty())
    }

    @Test fun scoreFollowsHisOrder() {
        val nigeria = JobChecks.score(1, Verdict.AUTHENTIC, true, null, 0)
        val canada = JobChecks.score(33, Verdict.AUTHENTIC, true, null, 0)
        assertTrue(nigeria > canada)
        assertTrue(JobChecks.score(1, Verdict.SCAM, true, null, 0) < 0)
    }
}

class JobSourcesTest {
    @Test fun parsesTavilyAndJooble() {
        val t = JobSources.parseTavily("""{"results":[{"title":"Painting Supervisor - Lagos","url":"https://x.com/j/1","content":"snip","raw_content":"Send CV to hr@x.com"}]}""")
        assertEquals(1, t.size)
        assertEquals("Send CV to hr@x.com", t[0].text)
        val j = JobSources.parseJooble("""{"totalCount":1,"jobs":[{"title":"<b>Painting</b> Supervisor","location":"Atyrau","snippet":"rotation","salary":"","source":"hh.kz","link":"https://jooble.org/away/1","company":"KPI"}]}""")
        assertEquals("Painting Supervisor", j[0].title)
        assertEquals("KPI", j[0].company)
    }

    @Test fun readsLinkedInAlerts() {
        val mail = """
            Your job alert for blasting painting supervisor in Nigeria
            12 new jobs match your preferences.

            Blasting & Painting Supervisor
            Saipem
            Lagos, Nigeria
            View job: https://www.linkedin.com/comm/jobs/view/3987654321/?trackingId=abc

            Painting Superintendent
            Daewoo E&C
            Bonny Island, Rivers, Nigeria
            Easy Apply
            View job: https://www.linkedin.com/comm/jobs/view/3987654322/?trk=x
        """.trimIndent()
        val jobs = JobSources.parseLinkedInAlert(mail)
        assertEquals(2, jobs.size)
        assertEquals("Blasting & Painting Supervisor", jobs[0].title)
        assertEquals("Saipem", jobs[0].company)
        assertEquals("Lagos, Nigeria", jobs[0].location)
        assertEquals("https://www.linkedin.com/jobs/view/3987654321/", jobs[0].url)
        assertEquals("Daewoo E&C", jobs[1].company)
    }

    @Test fun decodesMail() {
        assertEquals("Café = ok", GmailIo.quotedPrintable("Caf=C3=A9 =3D ok"))
        assertEquals("Axis Bank Alerts", GmailIo.decodeWords("=?UTF-8?B?QXhpcyBCYW5rIEFsZXJ0cw==?="))
    }

    @Test fun buildsTheEmailWithTheCv() {
        val mime = GmailIo.buildMime(
            "me@gmail.com", "Sajjad Raja", "hr@x.com", "Application: Painting Supervisor – Sajjad Raja", "Dear Hiring Manager,\nHello",
            GmailIo.Attachment("CV.pdf", "application/pdf", "%PDF-1.4 test".toByteArray()), "<1@xarvis.jobs>", java.util.Date(0),
        )
        assertTrue(mime.contains("From: Sajjad Raja <me@gmail.com>\r\n"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?")) // the dash isn't ASCII
        assertTrue(mime.contains("Content-Disposition: attachment; filename=\"CV.pdf\""))
        assertTrue(mime.contains("multipart/mixed"))
        assertFalse(mime.contains("\n\n.")) // nothing for SMTP to misread as the end
    }
}

class JobLetterTest {
    private val cv = """
        SAJJAD RAJA
        SENIOR SUPERVISOR | BLASTING & PAINTING | WORKSITE INSTRUCTOR & TRAINER
        UAE • rexraja89@gmail.com • Primary: +971 56 630 9286
        Secondary: +971 56 280 2520 • WhatsApp: +91 86929 75316
        PROFESSIONAL PROFILE
        Blasting and painting senior supervisor with an industrial career since 2010 across the UAE, Qatar, Nigeria and India.
        TRAINING & WORKFORCE DEVELOPMENT
        • Conduct toolbox talks and practical safety briefings for painting and blasting crews.
        PROFESSIONAL EXPERIENCE
        • Executed and supported supervision of coating work on offshore platform modules, jackets and marine structures.
        • Led painting and insulation work on the Dangote Petroleum Refinery & Fertilizer project, Lagos.
    """.trimIndent()

    @Test fun readsTheCv() {
        val p = JobLetter.profileFrom(cv)
        assertEquals("Sajjad Raja", p.name)
        assertEquals("rexraja89@gmail.com", p.email)
        assertEquals(3, p.phones.size)
        assertTrue(p.summary.startsWith("Blasting and painting senior supervisor"))
    }

    @Test fun writesATailoredLetter() {
        val p = JobLetter.profileFrom(cv)
        val job = JobItem(id = "1", title = "Painting Supervisor", company = "Saipem", country = "Nigeria", location = "Bonny Island", source = "rigzone.com", email = "careers@saipem.com")
        val (subject, body) = JobLetter.write(job, "Offshore platform painting supervisor needed for a refinery shutdown", p)
        assertEquals("Application: Painting Supervisor – Sajjad Raja", subject)
        assertTrue(body.contains("Painting Supervisor position with Saipem in Bonny Island"))
        assertTrue(body.contains("offshore platform modules"))
        assertTrue(body.contains("Dangote"))
        assertTrue(body.trimEnd().endsWith("rexraja89@gmail.com"))
        assertNotNull(JobLetter.highlights(p, "training of blasters").firstOrNull { it.contains("toolbox") })
    }
}
