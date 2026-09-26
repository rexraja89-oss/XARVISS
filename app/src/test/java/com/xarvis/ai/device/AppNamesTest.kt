package com.xarvis.ai.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rex's names for his apps, against the labels and packages those apps really have. */
class AppNamesTest {

    private val phone = listOf(
        InstalledApp("Gmail", "com.google.android.gm"),
        InstalledApp("LinkedIn", "com.linkedin.android"),
        InstalledApp("naukrigulf", "com.naukrigulf.app"),
        InstalledApp("ChatGPT", "com.openai.chatgpt"),
        InstalledApp("My Files", "com.sec.android.app.myfiles"),
        InstalledApp("Gallery", "com.sec.android.gallery3d"),
        InstalledApp("Messenger", "com.facebook.orca"),
        InstalledApp("Drive", "com.google.android.apps.docs"),
        InstalledApp("Game Launcher", "com.samsung.android.game.gamehome"),
        InstalledApp("Teams", "com.microsoft.teams"),
        InstalledApp("Zoom", "us.zoom.videomeetings"),
        InstalledApp("Intelligent CV", "com.intelligentcv.app"),
        InstalledApp("Phonto", "com.youthhr.phonto"),
        InstalledApp("Picsart", "com.picsart.studio"),
        InstalledApp("magicplan", "com.sensopia.magicplan"),
        InstalledApp("Hadith Collection", "com.hadith.collection"),
        InstalledApp("Calendar", "com.samsung.android.calendar"),
        InstalledApp("Google Cloud", "com.google.android.apps.cloudconsole"),
        InstalledApp("Excel", "com.microsoft.office.excel"),
        InstalledApp("ElevenLabs", "io.elevenlabs.app"),
        InstalledApp("Kling AI", "com.kling.ai"),
        InstalledApp("Compass", "com.samsung.compass"),
        InstalledApp("CamScanner", "com.intsig.camscanner"),
        InstalledApp("GitHub", "com.github.android"),
        InstalledApp("YT Studio", "com.google.android.apps.youtube.creator"),
        InstalledApp("CapCut", "com.lemon.lvoverseas"),
        InstalledApp("YouTube", "com.google.android.youtube"),
    )

    private fun open(name: String) = AppNames.best(name, phone)?.label

    @Test fun rexsNamesFindTheRightApp() {
        assertEquals("Gmail", open("gmail"))
        assertEquals("LinkedIn", open("LinkedIn"))
        assertEquals("naukrigulf", open("naukri"))
        assertEquals("ChatGPT", open("chat gpt"))
        assertEquals("My Files", open("files"))
        assertEquals("My Files", open("folders"))
        assertEquals("Gallery", open("gallery"))
        assertEquals("Messenger", open("messenger"))
        assertEquals("Drive", open("drive"))
        assertEquals("Game Launcher", open("games"))
        assertEquals("Teams", open("teams"))
        assertEquals("Zoom", open("zoom"))
        assertEquals("Intelligent CV", open("intelligent CV"))
        assertEquals("Phonto", open("phonto"))
        assertEquals("Picsart", open("picsart"))
        assertEquals("magicplan", open("magicplan"))
        assertEquals("Hadith Collection", open("hadith collection"))
        assertEquals("Calendar", open("calender"))
        assertEquals("Google Cloud", open("Google cloud"))
        assertEquals("Excel", open("excel"))
        assertEquals("ElevenLabs", open("elevenlab"))
        assertEquals("Kling AI", open("KlingAI"))
        assertEquals("Compass", open("Compass"))
        assertEquals("CamScanner", open("Camscanner"))
        assertEquals("GitHub", open("github"))
        assertEquals("YT Studio", open("YT studio"))
        assertEquals("CapCut", open("Capcut"))
        assertEquals("YouTube", open("youtube"))
        assertEquals("YouTube", open("the youtube app"))
    }

    @Test fun unknownAppsAndWebApps() {
        assertNull(open("spotify"))
        assertEquals("https://altrad.olivehrms.com", AppNames.webApp("payslip"))
        assertEquals("https://altrad.olivehrms.com", AppNames.webApp("olive"))
        assertNull(AppNames.webApp("youtube"))
    }
}
