package com.xarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted

/** ☰ → "What can I do?" — a plain-language tour of everything XARVIS can do, so nothing is forgotten. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
        Text("What can I do?", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)
        Text(
            "Just talk to me normally, sir — in English or Hinglish. Here's the map of my powers; you never have to memorise exact commands.",
            style = MaterialTheme.typography.bodySmall, color = XarvisMuted, modifier = Modifier.padding(vertical = 8.dp),
        )

        Section("🗣️ Talk & think", listOf(
            "Ask me anything — facts, advice, maths, ideas, explanations.",
            "I remember our past chats and the facts you tell me (\"remember my sister is Sara\").",
            "I reply with a bit of wit, in Hinglish when you write Hindi, and calmly when you sound stressed.",
            "Brains: Gemma on the phone, plus free cloud brains in ☰ → BRAIN. 🔒 keeps a chat on the phone; the AI council double-checks answers.",
        ))
        Section("🎙️ Say \"Xarvis\" — any time, screen on or off", listOf(
            "Say \"Hey Xarvis\" (most reliable) or just \"Xarvis\" — Zarvis or Jarvis both work. I answer \"Yes, sir?\" — after the beep, talk. I answer aloud and keep listening, so you can go on without saying my name again.",
            "To end it: say nothing, or \"that's all\" / \"thank you\" / \"bas\". Say \"Xarvis\" while I'm talking to interrupt me.",
            "When I ask \"Shall I call Atiq, sir?\", just say yes or no.",
            "S22: ☰ → Say \"Xarvis\" (the switch); Practice and settings shows what I hear and how easily I wake.",
            "benco: the small XARVIS Ears app listens (install it from XARVIS Updater, open it, Turn on). After a phone restart, open XARVIS once — Android gives the microphone back then.",
            "It runs on the phone, offline; nothing is recorded. Anyone who says \"Xarvis\" can talk to me, also on a locked phone, and listening all day uses some battery.",
        ))
        Section("🎙️ Voice", listOf(
            "Tap the mic to speak instead of type; I can speak my answers back.",
            "Long-press the XARVIS icon → \"Talk to XARVIS\", or make me the phone's assistant (☰).",
            "British voice, your own trained voice, or Hindi — set in ☰ → voice options.",
        ))
        Section("🚨 Emergency", listOf(
            "\"Call the police\" / \"I need an ambulance\" — I dial your country's number at once, on speaker.",
        ))
        Section("📞 Calls & messages", listOf(
            "\"Call Atiq\" — places the call. \"Ali ko call karo\" works too.",
            "\"WhatsApp Sara: running late\" / \"text mum I'm on my way\" — opens it pre-typed.",
            "\"Call baarish on imo\" or a WhatsApp call — I tap through the app myself (XARVIS Hands).",
        ))
        Section("📱 Apps & phone", listOf(
            "\"Open YouTube\", \"directions to Dubai Mall\", \"turn on the torch\".",
            "\"Set an alarm for 6:30\", \"timer 10 minutes\", \"how much battery?\".",
            "Wi-Fi / Bluetooth / flashlight on or off.",
        ))
        Section("🤖 I use the phone myself", listOf(
            "\"Send hi to Atiq on WhatsApp\", \"check my last message on imo\", or \"do this for me: …\" — I look at the screen and tap through the app.",
            "Add \"on my S22\" and I do it on your other phone.",
            "I ask before Send, Call, Delete or Buy, and never touch payments, OTPs or passwords. A red STOP shows while I work.",
        ))
        Section("🌅 Briefing & reminders", listOf(
            "Morning briefing (☰): weather, calendar, Gmail and battery — spoken every day at your time.",
            "\"Remind me to call Atiq at 6\", \"my reminders\", \"cancel my reminders\".",
            "I warn you before calendar events and when the battery is low.",
        ))
        Section("💼 Job Hunter (☰ → Job Hunter)", listOf(
            "\"Hunt jobs\" (top 10 countries), \"hunt jobs in Nigeria\", \"full job hunt\" (all 33) — Blasting & Painting Supervisor, Superintendent, Instructor and Trainer jobs.",
            "Every job is checked: ✅ authentic / ⚠️ suspicious / 🚫 scam with reasons, salary in ₹ (under ₹1.5 lakh skipped), and what it asks that you lack.",
            "\"Show my job drafts\" — read each email, edit, ✓ approve. Nothing is sent without your tick.",
            "\"Send 10 job applications\" — from your Gmail, the right CV attached, never the same address twice. \"Stop sending\" anytime.",
            "\"Any job replies?\" — I also tell you myself when an employer replies. \"Job hunter status\" for the totals.",
            "\"Trust agency X\" / \"block X.com\". Your LinkedIn job-alert emails are read in every hunt.",
        ))
        Section("🌐 Web & research", listOf(
            "\"Search the web for…\" — I answer right here with real results.",
            "\"Read this link and summarise it\" (paste a URL).",
            "\"Best phones under 30000 with pictures\" — specs, prices and photos.",
        ))
        Section("👁 Eyes, photos & faces", listOf(
            "\"Look\" opens XARVIS Eyes — point the camera and ask what I see, or read text.",
            "\"Take a photo\" / \"take a selfie\" — I count down and shoot it myself.",
            "\"Who is this?\" / \"who am I?\" — I recognise faces you taught me (Eyes → ➕ Teach a face).",
            "Send a photo and ask \"what's this?\" or \"who is in this photo?\". \"Show me pictures of…\" fetches real images.",
        ))
        Section("🛡 Guard mode (S22)", listOf(
            "\"Guard my phone\" — if anyone else picks it up: their photo, a red warning and a deep voice alarm.",
            "To stop it: your fingerprint (the 👆 button), or say \"It's alright XARVIS\" after the beep, then the fingerprint. \"Guard mode off\" ends guarding.",
            "Your benco gets an alert with the intruder's photo.",
        ))
        Section("🔗 Your other phone", listOf(
            "Link phones (\"pair with…\"); ask one phone the other's battery or gallery. The benco uses the S22's brain.",
            "\"Take a photo\" on the benco uses the S22's camera — even with its screen off.",
            "\"Who is this?\" on the benco — the S22 recognises the faces.",
            "☰ → 🛡 Guard my other phone: speak or type a warning out loud on the S22, send your voice, stop the alarm (with your benco's lock) or sound it now. Or say \"stop the alarm on my S22\", \"warn the intruder: …\".",
            "☰ → 🌐 Always connected: reach your other phones anywhere (mobile data, another country) through your own free relay — no VPN, banking apps stay happy. Guard alerts wait there if the other phone is offline.",
            "⚡ XARVIS Direct: away from home, remote control and files go straight between the phones when the networks allow (fast, and no relay data). The remote view shows ⚡ Direct or 🌐 Relay.",
        ))
        Section("🧠 Task mode — jobs done for you, from one message", listOf(
            "\"task: open Ninja VPN and connect, tell me when it's connected\" — or something big: \"task: make a 45-second YouTube Short about Mars with AI pictures and voice, edit in CapCut, upload as private\".",
            "One message is enough: XARVIS plans it and does it on the S22 by itself — opening apps and sites, tapping, typing, waiting out ads and AI generators — for hours if needed. Your task is the permission; it doesn't ask along the way.",
            "From the benco: \"task on my S22: …\". The S22 does it; its progress, any question and the result come to the benco's chat. Reply \"answer: …\", or \"stop the task\" / \"task status\".",
            "It comes to you only for what only you can do (a password, a captcha). It never pays, subscribes, types passwords or opens banking apps, and it skips buttons the task didn't ask for (an ad's Install). STOP any time.",
            "🎓 Practice ladder on the Task screen: small jobs first (Ninja VPN, a search, a timer), bigger ones unlock as it passes. It learns tips about each app and uses them next time.",
        ))
        Section("🎓 Skills — show me once · 🌙 Night shift", listOf(
            "Task mode → 🎓 Skills → 🔴 Teach: XARVIS goes Home and watches. Do the job once, your way (open Ninja VPN, Connect, close the ad), then tap ✅ Done in the notification and give it a name.",
            "\"do my VPN\" (also from the benco) replays it in seconds — no brain, no free-limit waits. It finds the buttons by their names and waits for what you waited for. If a screen looks different, the brain does just that step.",
            "A text that changes: tick \"changes each time\" when saving, then \"do my search: dew point tables\". \"my skills\" lists them. Big tasks use your skills as building blocks.",
            "🌙 \"night shift: do my VPN at 2 am\" (or ➕ on the Skills screen): every night on the S22, with a report to the benco (and in the morning briefing). If the S22 is locked then, the benco is told and it waits for you to unlock it with XARVIS Remote.",
            "Never recorded or replayed: passwords (it stops for you to type them), banking and payment apps, and money buttons.",
        ))
        Section("🛰 Situation — XARVIS knows your devices", listOf(
            "\"status report\" (or \"what's going on\", \"sitrep\") — every phone at a glance: battery, charging, Wi-Fi or mobile data, locked or in use, missed calls, what XARVIS is doing there. ☰ → 🛰 Situation shows the same.",
            "Ask anything about them: \"is my S22 charging?\", \"when did I last use the S22?\" — XARVIS answers from live facts. \"what's new on my S22\" reads its latest notifications; \"any missed calls?\".",
            "It speaks up by itself: a phone low on battery, too hot or short of storage, a missed call on the S22, the S22 unlocked while you're away from it, or a phone that stopped answering.",
            "Notifications are read on the S22 (XARVIS Hands); codes are hidden and banking apps left out. Both can be switched off on the Situation screen.",
        ))
        Section("⬆ Updates — small and all at once", listOf(
            "UPDATE (or \"update everything\") opens XARVIS Updater, which updates XARVIS, XARVIS Remote and itself at once with only what changed — usually under 1 MB. After the first time, Android doesn't ask \"Update?\" for each one.",
            "From the benco: \"update my S22\" — the S22 updates itself, its progress comes here, and it says when it's back. \"update both phones\" does both.",
            "An app whose code didn't change isn't downloaded again. A full 107 MB download is never started without you saying so on the Updater's screen.",
        ))
        Section("🤝 Help a family phone", listOf(
            "☰ → 🤝 Help a family phone → Invite a phone: send the invite on WhatsApp. They install the small XARVIS Helper app and paste it.",
            "When they tap \"Ask for help\", you get a notification: tap it to see their screen (and tap for them, if they switched on \"Allow tapping\").",
            "They allow it every time and can press STOP any time. Works UAE ↔ India. Their phone can't see or touch yours.",
        ))
        Section("🛟 XARVIS Remote — the emergency way in", listOf(
            "A separate small app (XARVIS-Remote.apk) for your own phones: it keeps working when XARVIS on the S22 is down or being updated.",
            "On the benco: XARVIS Remote → ➕ Link a phone to control → send the invite. On the S22: paste it, then do the short checklist (Accessibility, battery Unrestricted).",
            "Then from anywhere: XARVIS Remote → 🖥 Control Galaxy S22 Ultra — the same screen as here, nobody needs to be at the S22. Tap ⚡ for live video, and \"Start now\" on the picture.",
        ))
        Section("🖥 Remote control (from XARVIS)", listOf(
            "\"Control my S22\" or ☰ → Control linked phone — see its screen and use it with your finger.",
            "⚡ Make it smooth for live video. Type, paste to it, copy from it, send a file. Share any file → \"Send to my other phone\".",
            "🔒 Lock, then 🔓 Unlock and draw its pattern on the picture.",
            "\"Open WhatsApp on my S22 and read my last message\", \"tap Settings on S22\", \"what's on my S22 screen?\".",
            "Only after you tick \"Let my linked phone see and control this phone\" on that phone (☰ → XARVIS Hands). It shows a small red ✱ — tap it for STOP.",
        ))
        Section("📄 Files & documents", listOf(
            "I read your PDFs, Word, Excel and text files — attach with +.",
            "\"Make a PDF packing list\", \"put my budget in Excel\" — I create the file.",
            "\"Search my phone for my passport number\" — searches folders you allow.",
        ))
        Section("🧠 Memory & health", listOf(
            "\"Remember…\" keeps facts for good; \"what do you remember about me?\".",
            "\"Add to my health record…\", \"show/export my health record\".",
        ))
        Section("🔐 Your control (☰ → CONTROL)", listOf(
            "Permissions: choose what I may do alone, ask first, or never. Everything I do is in the activity log.",
        ))
        Section("💻 XARVIS Code (☰ → CONTROL)", listOf(
            "Build web apps & games, now with 3D (Three.js) — it publishes them live.",
            "Open any of your GitHub repos and edit the code; it works on a safe branch.",
            "Research the web and make a PDF/report; attach photos or files with +.",
            "It remembers: each project keeps notes (XARVIS_NOTES.md) it reads before every change, a project already on GitHub is continued instead of rebuilt, big apps are built in stages (\"next stage\"), and it learns from its own mistakes. Ask it \"what have you learned\".",
            "It never puts an API key into a page or repo — an app that needs one (like an AI assistant) asks you for it in its own settings.",
        ))
        Section("✋ XARVIS Hands (☰ → XARVIS Hands)", listOf(
            "Built into XARVIS on the S22 (switch it on once); the benco uses the separate Hands app.",
            "It lets me tap inside apps — calls, remote control, doing tasks for you. Never payments, OTPs or passwords.",
        ))
        Section("⬆ Updates", listOf(
            "When a new version is ready an UPDATE button appears — tap it, download, install.",
        ))

        Text(
            "Not sure how to say something? Just ask in your own words — I'll figure it out.",
            style = MaterialTheme.typography.bodySmall, color = XarvisMuted, modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun Section(title: String, items: List<String>) {
    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 8.dp), color = XarvisMuted.copy(alpha = 0.25f))
    Text(title, style = MaterialTheme.typography.titleMedium, color = XarvisCyan)
    items.forEach {
        Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}
