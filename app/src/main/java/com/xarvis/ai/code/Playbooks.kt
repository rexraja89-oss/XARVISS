package com.xarvis.ai.code

/**
 * What XARVIS Code knows before it starts (13 Nov 2026; Rex: "recall how you built XARVIS from scratch, the problems
 * and challenges… teach XARVIS Code how to do it from beginning to end"). Expert guides written from building XARVIS,
 * put into the brain's instructions when a job needs them. Pure, tested.
 */
object Playbooks {
    /** How XARVIS itself was built — the habits that made it work, for any project. Always included (short). */
    val LESSONS = """
        LESSONS FROM BUILDING XARVIS (follow them):
        1. Build in stages. Stage 1 is the smallest thing that really works end to end; test it; only then add the next stage. Never build everything at once.
        2. Secrets (API keys, passwords, tokens) never go in code, a repo, a chat or a public page. The user types them into a settings box at run time and they're kept on that device only (localStorage in a web app).
        3. Don't hard-code AI model names — providers retire them and every call then fails. Ask the API which models exist, pick one, keep a fallback list.
        4. Free AI tiers have limits (HTTP 429). Wait, say so plainly and retry later; never invent an answer when a call failed.
        5. Keep instructions to an AI short and concrete; one example teaches more than ten rules. Small models ignore long prompts.
        6. Anything that must be reliable (commands, safety checks, parsing) is plain code, not left to the AI.
        7. Never put AI or user text into the page as raw HTML — escape it first.
        8. Mobile first: big touch targets, readable contrast, works one-handed on a phone; no keyboard-only controls.
        9. Every error the user sees says what happened and what to do next ("Your key was refused — tap ⚙ and paste it again").
        10. When something breaks, read the actual error and fix the cause; change one thing at a time.
        11. Prefer the platform's own features (fetch, localStorage, speechSynthesis) over big libraries.
        12. Keep notes of the plan, decisions and what's next — that's the memory between sessions.
        13. Only ask the user what only they can decide; otherwise pick a sensible default and say what you chose.
    """.trimIndent()

    /** Building an AI assistant (FRIDAY, a JARVIS-like helper, a chatbot) as a web app on GitHub Pages. */
    val AI_ASSISTANT = """
        PLAYBOOK — BUILDING AN AI ASSISTANT WEB APP (e.g. "FRIDAY"):
        The page is PUBLIC (GitHub Pages), so it must never contain an API key. Architecture: one index.html — chat UI → the user's own Gemini key from localStorage → Gemini REST API → reply shown (escaped) and optionally spoken.
        STAGES (put the plan in an HTML comment at the very top: <!-- XARVIS PLAN: 1 core chat ✓ · 2 personality+memory · 3 voice · 4 polish --> and mark each stage ✓ when it's built; build ONE stage per request unless asked for more):
        1. CORE: ⚙ settings panel with a password-type field for the Gemini API key (from aistudio.google.com/apikey, free), saved to localStorage ("friday.key"); if there's no key, open settings with a one-line how-to. Chat list + input + Send (Enter sends). Call Gemini; show a "thinking…" bubble; show errors clearly.
        2. PERSONALITY + MEMORY: a short system instruction (name, tone, 2–3 rules). Keep the last 12 turns in localStorage ("friday.history") and send them as contents; "remember <fact>" saves to "friday.facts" (sent in the system instruction); a "Clear memory" button.
        3. VOICE: replies spoken with speechSynthesis (voices load late: wait for the 'voiceschanged' event; let the user pick a voice in settings; prefer an English female voice for FRIDAY; speechSynthesis.cancel() before speaking; strip markdown/emoji; speak sentence by sentence — long single utterances get cut off on Android). 🎤 button with (window.SpeechRecognition || window.webkitSpeechRecognition); if missing, hide it and say voice input needs Chrome. Needs https (Pages is https) and the mic permission.
        4. POLISH: dark HUD look, a glowing status ring (idle / thinking / speaking), quick-reply chips, an "Add to Home screen" tip, safe-area padding.
        GEMINI REST (works straight from the browser):
        • Models: GET https://generativelanguage.googleapis.com/v1beta/models?key=KEY → models[].name like "models/gemini-2.5-flash" with supportedGenerationMethods containing "generateContent". Pick the newest name containing "flash" (avoid "preview", "pro", "tts", "image", "embedding"); remember it in localStorage; fall back to ["models/gemini-2.5-flash","models/gemini-2.0-flash"]. On 404 rediscover.
        • Ask: POST https://generativelanguage.googleapis.com/v1beta/MODEL_NAME:generateContent?key=KEY, header Content-Type: application/json, body {"systemInstruction":{"parts":[{"text":SYSTEM}]},"contents":[{"role":"user","parts":[{"text":"hi"}]},{"role":"model","parts":[{"text":"..."}]},…],"generationConfig":{"maxOutputTokens":1024,"temperature":0.8,"thinkingConfig":{"thinkingBudget":0}}}. thinkingBudget 0 matters: on 2.5 flash hidden "thinking" eats the budget and replies come back EMPTY. If a model rejects thinkingConfig (400 mentioning thinking), retry once without it.
        • Reply text: (data.candidates?.[0]?.content?.parts||[]).map(p=>p.text||"").join(""). Empty → data.promptFeedback?.blockReason or "no answer — try again".
        • Errors: 400/401/403 with "API key" → "Your key was refused — tap ⚙"; 429 → "Free limit reached for now — try again in a minute"; network failure → "No internet". Never pretend.
        SAFE DISPLAY: escape &<>" first, then turn **bold** and newlines into <b>/<br>. Never innerHTML raw model text.
        TEST BEFORE SAYING DONE: no key → settings opens with help; wrong key → clear message; "hello" → a real reply; reload → history still there; Clear memory works; voice on/off works; nothing overflows on a 360 px wide screen; the file contains NO key.
        LIMITS TO SAY HONESTLY: it can't run in the background, read the phone's notifications or act on other apps like XARVIS can — it's a web page. Free Gemini has daily limits.
    """.trimIndent()

    private val AI_WORDS = Regex(
        """(?i)\b(?:ai|a\.i\.|assistant|chat\s?bot|chatbot|jarvis|friday|gemini|gpt|llm|talk(?:s|ing)?\s+to\s+me|voice\s+assistant|companion|agent)\b""",
    )

    /** The guides for this job ([text]: the request plus the project's notes). */
    fun forTask(text: String): String {
        val parts = ArrayList<String>()
        if (AI_WORDS.containsMatchIn(text)) parts += AI_ASSISTANT
        parts += LESSONS
        return parts.joinToString("\n\n")
    }

    /** Names of the guides, for "what have you learned". */
    fun titles(): List<String> = listOf("Building an AI assistant web app (FRIDAY-style)", "Lessons from building XARVIS (13 habits)")
}
