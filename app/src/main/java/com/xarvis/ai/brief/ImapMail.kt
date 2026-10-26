package com.xarvis.ai.brief

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import javax.net.ssl.SSLSocketFactory

/**
 * Reads the Gmail inbox over IMAP with an APP PASSWORD (no OAuth, needs 2-Step on the account).
 * A tiny raw IMAP client over TLS — no mail library, so nothing heavy is added to the APK. Uses
 * BODY.PEEK so reading the briefing never marks mail as read. Everything is best-effort: on any
 * problem it returns an error string and the briefing simply skips email.
 */
object ImapMail {

    data class Inbox(val unread: Int, val items: List<String>, val error: String? = null)

    suspend fun unread(email: String, appPassword: String, host: String = "imap.gmail.com", max: Int = 5): Inbox =
        withContext(Dispatchers.IO) {
            if (email.isBlank() || appPassword.isBlank()) return@withContext Inbox(0, emptyList(), "no email/password set")
            runCatching { read(email, appPassword, host, max) }
                .getOrElse { Inbox(0, emptyList(), it.message ?: "couldn't reach the mailbox") }
        }

    private fun read(email: String, pw: String, host: String, max: Int): Inbox {
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, 993)
        socket.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val out = socket.getOutputStream()
        try {
            reader.readLine() // server greeting
            var tag = 0
            fun cmd(c: String): List<String> {
                val t = "a${++tag}"
                send(out, "$t $c")
                val lines = ArrayList<String>()
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.startsWith("$t ")) { lines.add(l); break }
                    lines.add(l)
                }
                return lines
            }

            val login = cmd("LOGIN \"${email}\" \"${pw}\"")
            if (login.lastOrNull()?.contains(" OK", true) != true) return Inbox(0, emptyList(), "login failed (check the app password)")
            cmd("SELECT INBOX")
            val search = cmd("SEARCH UNSEEN")
            val ids = search.firstOrNull { it.startsWith("* SEARCH") }
                ?.removePrefix("* SEARCH")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
                ?: emptyList()
            val unread = ids.size
            val items = ArrayList<String>()
            for (id in ids.takeLast(max).reversed()) {
                val f = cmd("FETCH $id (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT)])").joinToString("\n")
                val from = header(f, "From").let { it.substringBefore('<').trim().ifBlank { it } }.take(40)
                val subj = decode(header(f, "Subject")).ifBlank { "(no subject)" }.take(70)
                if (from.isNotBlank() || subj.isNotBlank()) items.add("$from — $subj")
            }
            cmd("LOGOUT")
            return Inbox(unread, items)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun send(out: OutputStream, line: String) { out.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1)); out.flush() }

    private fun header(block: String, name: String): String {
        val re = Regex("(?im)^" + name + ":\\s*(.*)$")
        return re.find(block)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** Light decode of RFC 2047 encoded subjects (=?UTF-8?B?..?= / ?Q?..?=); leaves plain text as-is. */
    private fun decode(s: String): String = runCatching {
        Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").replace(s) { m ->
            val charset = m.groupValues[1]
            val enc = m.groupValues[2].uppercase()
            val data = m.groupValues[3]
            val bytes = if (enc == "B") android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            else data.replace('_', ' ').let { q ->
                val b = ArrayList<Byte>(); var i = 0
                while (i < q.length) { if (q[i] == '=' && i + 2 < q.length) { b.add(q.substring(i + 1, i + 3).toInt(16).toByte()); i += 3 } else { b.add(q[i].code.toByte()); i++ } }
                b.toByteArray()
            }
            String(bytes, charset(charset))
        }
    }.getOrDefault(s)
}
