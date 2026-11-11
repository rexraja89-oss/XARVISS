package com.xarvis.ai.agent

/**
 * XARVIS using a phone by itself to reach Rex's goal ("send my last photo to my wife on WhatsApp", "check if
 * Ali replied on imo"): look at the screen (XARVIS Hands reads it as a numbered list), ask the brain for ONE
 * action, do it, repeat — at most [MAX_STEPS] times.
 *
 * Rex's request is his permission for every step it needs (Rex, Nov 2026: "when I already gave a task, I already
 * authorised the steps required to complete it"): no Yes/No questions along the way. Safety, always: it never touches
 * money, passwords, OTPs, PINs or bank/card details, and never opens banking or payment apps (it stops instead);
 * buttons that reach other people or can't be undone (Send, Call, Delete, Post, Install…) are pressed only when the
 * request asks for that kind of thing — otherwise they're skipped, without asking. A red banner on that phone shows
 * while it works and its STOP ends it, as does STOP in the chat.
 */
class PhoneAgent(
    private val target: Target,
    private val think: suspend (String) -> String?,
    private val progress: (String) -> Unit,
    private val pause: suspend (Long) -> Unit,
) {
    /** The phone being used: this one, or the linked one over the link. */
    interface Target {
        val name: String
        suspend fun begin()
        suspend fun end()
        suspend fun screen(): Screen
        suspend fun tap(x: Int, y: Int)
        suspend fun type(text: String): Boolean
        suspend fun scroll(down: Boolean)
        suspend fun key(name: String)
        suspend fun open(app: String): String
        /** Whether Rex pressed STOP on that phone's banner. */
        suspend fun stopped(): Boolean
    }

    data class Element(
        val n: Int, val label: String, val tappable: Boolean, val editable: Boolean, val password: Boolean, val x: Int, val y: Int,
    )

    data class Screen(val app: String, val elements: List<Element>, val width: Int, val height: Int)

    sealed interface Action {
        data class Open(val app: String) : Action
        data class Tap(val n: Int) : Action
        data class Type(val n: Int, val text: String) : Action
        data class Scroll(val down: Boolean) : Action
        data object Back : Action
        data object Home : Action
        data class Done(val text: String) : Action
        data class Fail(val why: String) : Action
    }

    suspend fun run(goal: String): String {
        target.begin()
        try {
            val did = mutableListOf<String>()
            var confused = 0
            for (step in 1..MAX_STEPS) {
                if (target.stopped()) return "Stopped — you pressed STOP on ${target.name}."
                val screen = target.screen()
                if (Safety.moneyApp(screen.app)) {
                    target.key("home")
                    return "I stopped: ${screen.app} is a banking or payment app, and I never use those for you."
                }
                progress("🤖 Step $step on ${target.name}: thinking… (${screen.app})")
                val raw = think(prompt(goal, did, screen)) ?: return "My brain isn't available right now, so I stopped."
                val action = parse(raw)
                if (action == null) {
                    if (++confused >= 3) return "I couldn't work out what to do next on ${target.name}. " + summary(did)
                    did += "(an unclear answer — answer with one line in the format)"
                    continue
                }
                when (action) {
                    is Action.Done -> return action.text.ifBlank { "Done." }
                    is Action.Fail -> return "I couldn't finish: ${action.why}. " + summary(did)
                    is Action.Open -> {
                        if (Safety.moneyApp(action.app)) return "I don't open banking or payment apps (${action.app}), sir — that one's yours alone."
                        progress("🤖 Step $step: opening ${action.app}…")
                        did += "OPEN ${action.app} → ${target.open(action.app)}"
                    }
                    is Action.Tap -> {
                        val el = screen.elements.firstOrNull { it.n == action.n }
                        if (el == null) {
                            did += "TAP ${action.n} → there is no [${action.n}] on this screen"
                            continue
                        }
                        when (Safety.check(el.label, goal)) {
                            Safety.Verdict.NEVER -> return "I stopped: \"${el.label}\" looks like a payment, password or bank step, and I never do those for you."
                            Safety.Verdict.OUT_OF_SCOPE -> {
                                did += "TAP ${el.n} \"${el.label.take(40)}\" → skipped: that isn't part of what Rex asked"
                                continue
                            }
                            Safety.Verdict.OK -> {}
                        }
                        progress("🤖 Step $step: tapping \"${el.label.take(40)}\"")
                        target.tap(el.x, el.y)
                        did += "TAP ${el.n} \"${el.label.take(40)}\""
                    }
                    is Action.Type -> {
                        val el = screen.elements.firstOrNull { it.n == action.n }
                        if (el == null || !el.editable) {
                            did += "TYPE ${action.n} → [${action.n}] isn't a text box"
                            continue
                        }
                        if (el.password || Safety.check(el.label, goal) == Safety.Verdict.NEVER || Safety.secret(action.text)) {
                            return "I stopped: that box looks like a password, PIN, OTP or payment detail, and I never type those."
                        }
                        progress("🤖 Step $step: typing \"${action.text.take(40)}\"")
                        target.tap(el.x, el.y)
                        pause(TYPE_SETTLE_MS)
                        val ok = target.type(action.text)
                        did += "TYPE ${el.n} \"${action.text.take(40)}\"" + if (ok) "" else " → failed (no text box got it)"
                    }
                    is Action.Scroll -> {
                        target.scroll(action.down)
                        did += if (action.down) "SCROLL DOWN" else "SCROLL UP"
                    }
                    Action.Back -> {
                        target.key("back")
                        did += "BACK"
                    }
                    Action.Home -> {
                        target.key("home")
                        did += "HOME"
                    }
                }
                pause(SETTLE_MS) // let the app react before looking again
            }
            return "I stopped after $MAX_STEPS steps without finishing. " + summary(did)
        } finally {
            // Also after STOP in the chat (this coroutine cancelled): the banner on that phone must go away.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { target.end() } }
        }
    }

    private fun summary(did: List<String>): String =
        if (did.isEmpty()) "" else "What I did: " + did.takeLast(6).joinToString(" → ")

    companion object {
        const val MAX_STEPS = 15
        private const val SETTLE_MS = 1_300L
        private const val TYPE_SETTLE_MS = 500L
        private const val MAX_ELEMENTS = 45

        /** The brain's standing instructions for operating a phone. */
        const val SYSTEM = "You operate Rex's Android phone for him, one action at a time. Each turn you get his GOAL, " +
            "what you already did, and the SCREEN as a numbered list (tap) = can be tapped, (type) = a text box. " +
            "Reply with exactly ONE line, nothing else, in one of these forms:\n" +
            "OPEN <app name>\nTAP <number>\nTYPE <number> <text>\nSCROLL DOWN\nSCROLL UP\nBACK\nHOME\n" +
            "DONE <short answer or result for Rex>\nFAIL <short reason>\n" +
            "Rules: start with OPEN if the right app isn't on the screen. Only TAP numbers marked (tap); only TYPE into " +
            "numbers marked (type). When the goal is to find something out and the answer is on the screen, reply DONE " +
            "with the answer. When the goal is done, reply DONE. Rex's goal is his permission for the steps it needs: just " +
            "do them. Don't press things the goal didn't ask for (an ad's Install, invite contacts) — close or skip them. " +
            "Never enter passwords, PINs, OTPs or payment details; never buy or subscribe; never open banking or payment apps."

        fun prompt(goal: String, did: List<String>, screen: Screen): String = buildString {
            append("GOAL: ").append(goal).append('\n')
            append("DONE SO FAR:\n")
            if (did.isEmpty()) append("(nothing yet)\n") else did.takeLast(8).forEachIndexed { i, d -> append(i + 1).append(". ").append(d).append('\n') }
            append("SCREEN — ").append(screen.app.ifBlank { "unknown app" }).append(":\n")
            if (screen.elements.isEmpty()) append("(nothing readable — the screen may be off, locked or loading)\n")
            screen.elements.take(MAX_ELEMENTS).forEach { e ->
                append('[').append(e.n).append("] ")
                if (e.editable) append("(type) ") else if (e.tappable) append("(tap) ")
                append('"').append(e.label.take(70)).append("\"\n")
            }
            append("Your one line:")
        }

        private val LINE = Regex("""(?im)^[\s*>`"'\-•]*(OPEN|TAP|TYPE|SCROLL|BACK|HOME|DONE|FAIL)\b[\s:]*(.*)$""")

        /** The brain's answer as an action: the first line in the expected form, ignoring decoration. */
        fun parse(raw: String): Action? {
            val m = LINE.find(raw) ?: return null
            val rest = m.groupValues[2].trim().trim('`', '*').trim()
            return when (m.groupValues[1].uppercase()) {
                "OPEN" -> rest.trim('"', '\'').takeIf { it.isNotBlank() }?.let { Action.Open(it) }
                "TAP" -> Regex("""\[?(\d+)""").find(rest)?.groupValues?.get(1)?.toIntOrNull()?.let { Action.Tap(it) }
                "TYPE" -> Regex("""^\[?(\d+)\]?\s*[:,]?\s*(.+)$""").find(rest)?.let {
                    Action.Type(it.groupValues[1].toInt(), it.groupValues[2].trim().trim('"', '\'', '“', '”'))
                }
                "SCROLL" -> Action.Scroll(!rest.startsWith("up", ignoreCase = true))
                "BACK" -> Action.Back
                "HOME" -> Action.Home
                "DONE" -> Action.Done(rest)
                "FAIL" -> Action.Fail(rest.ifBlank { "no reason given" })
                else -> null
            }
        }
    }

    /** What XARVIS may tap or type by itself (Task mode uses the same rules). */
    object Safety {
        enum class Verdict { OK, OUT_OF_SCOPE, NEVER }

        private val NEVER_WORDS = Regex(
            """(?i)\b(?:pay|payment|pay now|otp|one[- ]time|password|passcode|pin|upi|send money|transfer money|money transfer|""" +
                """bank|card number|cvv|wallet|net ?banking|buy|buy now|purchase|checkout|check out|place order|order now|""" +
                """subscribe|subscription|upgrade|go pro|get pro|premium|free trial|start (?:free )?trial|add card|billing)\b""",
        )

        /**
         * Buttons that reach other people or can't be undone, each with the words in Rex's request that allow it: "send
         * hi to Ali" allows Send, "upload it as private" allows Upload — an ad's "Install" in "connect Ninja VPN" doesn't.
         */
        private val SCOPED = listOf(
            Regex("""(?i)\b(?:send|forward|reply)\b""") to
                Regex("""(?i)\b(?:send|sends|sending|sent|message|messages|msg|text|whatsapp|imo|telegram|reply|forward|mail|email|tell|wish|say)\b"""),
            Regex("""(?i)\b(?:post|publish|upload|share|go live)\b""") to Regex("""(?i)\b(?:post|publish|upload|share|go live)\b"""),
            Regex("""(?i)\b(?:delete|remove|clear|erase|reset|uninstall)\b""") to Regex("""(?i)\b(?:delete|remove|clear|erase|reset|uninstall|clean)\b"""),
            Regex("""(?i)\binstall\b""") to Regex("""(?i)\b(?:install|download)\b"""),
            Regex("""(?i)\b(?:call|video call|voice call)\b""") to Regex("""(?i)\b(?:call|ring|phone)\b"""),
            Regex("""(?i)\b(?:block|report|unfriend|unfollow)\b""") to Regex("""(?i)\b(?:block|report|unfriend|unfollow)\b"""),
            Regex("""(?i)\b(?:sign out|log out|logout)\b""") to Regex("""(?i)\b(?:sign out|log out|logout)\b"""),
        )

        /**
         * NEVER for anything about money, passwords or codes; OUT_OF_SCOPE for a button that reaches people or can't be
         * undone when [request] didn't ask for that (short labels only — a chat row like "Ali: send me the file" is
         * just text to open); OK otherwise — including signing in with an account, Allow, OK and Accept.
         */
        fun check(label: String, request: String): Verdict = when {
            label.isBlank() -> Verdict.OK
            NEVER_WORDS.containsMatchIn(label) && label.length <= LONG_TEXT -> Verdict.NEVER
            label.length <= BUTTON_MAX && SCOPED.any { (button, asked) -> button.containsMatchIn(label) && !asked.containsMatchIn(request) } -> Verdict.OUT_OF_SCOPE
            else -> Verdict.OK
        }

        /** Text that is just a code or card number (only digits, 4 or more — an OTP, PIN or card). */
        fun secret(text: String): Boolean = Regex("""^[\d\s-]*\d{4,}[\d\s-]*$""").matches(text.trim())

        /** Banking, payment and money apps (by name or package): never opened or used, even when asked (Rex's rule). */
        fun moneyApp(app: String): Boolean = MONEY_APP.containsMatchIn(app)

        private val MONEY_APP = Regex(
            """(?i)(?:\bbank|banking|\bpay(?!slip)|wallet|\bupi\b|bhim|phonepe|gpay|paisa|paytm|paypal|venmo|cash ?app|""" +
                """binance|crypto|coinbase|\bcred\b|western union|exchange house|yono|\bnbd\b|adcb|mashreq|rakbank|hdfc|icici|kotak|""" +
                """\bsbi\b|axis bank|emirates islamic|\bfab\b|al ansari|lulu exchange|\bwise\b|revolut|payoneer|skrill)""",
        )

        private const val BUTTON_MAX = 30
        /** Long text (a paragraph that merely mentions "premium") isn't a button. */
        private const val LONG_TEXT = 60
    }
}
