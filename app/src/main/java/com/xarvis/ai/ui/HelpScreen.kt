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
            "I reply with a bit of wit, and in Hinglish when you write Hindi.",
        ))
        Section("🎙️ Voice", listOf(
            "Say \"Hey Jarvis\" to wake me with the screen off (S22).",
            "Tap the mic to speak instead of type; I can speak my answers back.",
            "British voice, your own trained voice, or Hindi — set in ☰ → voice options.",
        ))
        Section("📞 Calls & messages", listOf(
            "\"Call Atiq\" — places the call. \"Ali ko call karo\" works too.",
            "\"WhatsApp Sara: running late\" / \"text mum I'm on my way\" — opens it pre-typed.",
            "\"Open imo and call baarish\" — taps through the app itself (needs XARVIS Hands).",
        ))
        Section("📱 Apps & phone", listOf(
            "\"Open YouTube\", \"directions to Dubai Mall\", \"turn on the torch\".",
            "\"Set an alarm for 6:30\", \"timer 10 minutes\", \"how much battery?\".",
            "Wi-Fi / Bluetooth / flashlight on or off.",
        ))
        Section("🌐 Web & research", listOf(
            "\"Search the web for…\" — I answer right here with real results.",
            "\"Read this link and summarise it\" (paste a URL).",
            "\"Best phones under 30000 with pictures\" — specs, prices and photos.",
            "\"Find painting supervisor jobs\" — opens real listings to apply.",
        ))
        Section("📷 Photos & seeing", listOf(
            "Send a photo or a screenshot (the 📷 chip or +) and ask \"what's this?\".",
            "\"Take a photo\" / \"take a selfie\" — opens the camera, then ask about it.",
            "\"Show me pictures of…\" — fetches real images.",
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
        Section("🔗 Your other phone", listOf(
            "Link phones (\"pair with…\"); ask one phone the other's battery or gallery.",
        ))
        Section("💻 XARVIS Code (☰ → CONTROL)", listOf(
            "Build web apps & games, now with 3D (Three.js) — it publishes them live.",
            "Open any of your GitHub repos and edit the code; it works on a safe branch.",
            "Research the web and make a PDF/report; attach photos or files with +.",
        ))
        Section("✋ XARVIS Hands (separate app)", listOf(
            "Lets me tap inside imo / WhatsApp to place a call for you.",
            "It never taps anything to do with payments, OTPs or passwords.",
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
