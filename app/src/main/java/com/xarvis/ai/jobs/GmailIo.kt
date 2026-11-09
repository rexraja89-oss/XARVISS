package com.xarvis.ai.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

/**
 * Rex's Gmail for the Job Hunter, with the same app password as the morning briefing (no OAuth): sending an
 * application with his CV attached (SMTP — it lands in his Sent folder like any email he sends), and reading
 * LinkedIn's job-alert emails and replies from employers (IMAP, BODY.PEEK so nothing is marked read).
 * Small raw clients over TLS, no mail library (nothing heavy in the APK).
 */
object GmailIo {

    class MailException(message: String) : Exception(message)

    data class Attachment(val name: String, val mime: String, val bytes: ByteArray)

    // ---- sending ----

    /** Sends one email from [from] (display [fromName]) to [to]; returns its Message-ID. */
    suspend fun send(
        from: String, appPassword: String, fromName: String, to: String, subject: String, body: String, attachment: Attachment?,
    ): String = withContext(Dispatchers.IO) {
        val messageId = "<" + UUID.randomUUID() + "@xarvis.jobs>"
        val mime = buildMime(from, fromName, to, subject, body, attachment, messageId, Date())
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket("smtp.gmail.com", 465)
        socket.soTimeout = 60_000
        try {
            val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val out = socket.getOutputStream()
            fun reply(): Pair<Int, String> {
                val sb = StringBuilder()
                while (true) {
                    val l = r.readLine() ?: throw MailException("Gmail closed the connection")
                    sb.append(l).append('\n')
                    if (l.length < 4 || l[3] != '-') return (l.take(3).toIntOrNull() ?: 0) to sb.toString()
                }
            }
            fun cmd(line: String, ok: Int, what: String) {
                write(out, line)
                val (code, text) = reply()
                if (code != ok) throw MailException("$what failed: ${text.lines().firstOrNull().orEmpty().take(140)}")
            }
            reply() // 220 greeting
            cmd("EHLO xarvis", 250, "EHLO")
            write(out, "AUTH LOGIN")
            if (reply().first != 334) throw MailException("Gmail didn't accept the login")
            write(out, b64(from))
            if (reply().first != 334) throw MailException("Gmail didn't accept the email address")
            write(out, b64(appPassword.replace(" ", "")))
            val (auth, authText) = reply()
            if (auth != 235) throw MailException("Gmail login failed (check the app password in ☰ → Morning briefing): ${authText.lines().first().take(100)}")
            cmd("MAIL FROM:<$from>", 250, "Sender")
            cmd("RCPT TO:<$to>", 250, "Recipient $to")
            cmd("DATA", 354, "DATA")
            // Dot-stuffing: a line starting with "." gets another.
            out.write(mime.replace("\r\n.", "\r\n..").toByteArray(Charsets.UTF_8))
            cmd("\r\n.", 250, "Sending")
            runCatching { write(out, "QUIT") }
            messageId
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The whole email (headers, text, the CV as an attachment), lines ending in CRLF. */
    fun buildMime(
        from: String, fromName: String, to: String, subject: String, body: String, attachment: Attachment?, messageId: String, date: Date,
    ): String {
        val boundary = "xarvis-" + UUID.randomUUID().toString().replace("-", "")
        val enc = Base64.getMimeEncoder(76, "\r\n".toByteArray())
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append("\r\n")
        line("From: ${encodeWord(fromName)} <$from>")
        line("To: <$to>")
        line("Subject: ${encodeWord(subject)}")
        line("Date: " + SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(date))
        line("Message-ID: $messageId")
        line("MIME-Version: 1.0")
        val text = enc.encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        if (attachment == null) {
            line("Content-Type: text/plain; charset=UTF-8")
            line("Content-Transfer-Encoding: base64")
            line()
            line(text)
        } else {
            line("Content-Type: multipart/mixed; boundary=\"$boundary\"")
            line()
            line("--$boundary")
            line("Content-Type: text/plain; charset=UTF-8")
            line("Content-Transfer-Encoding: base64")
            line()
            line(text)
            line("--$boundary")
            line("Content-Type: ${attachment.mime}; name=\"${attachment.name}\"")
            line("Content-Disposition: attachment; filename=\"${attachment.name}\"")
            line("Content-Transfer-Encoding: base64")
            line()
            line(enc.encodeToString(attachment.bytes))
            line("--$boundary--")
        }
        return sb.toString()
    }

    /** Non-ASCII header text as RFC 2047 ("=?UTF-8?B?…?="); plain ASCII as is. */
    fun encodeWord(s: String): String =
        if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun write(out: OutputStream, line: String) {
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush()
    }

    // ---- reading ----

    data class Mail(val uid: String, val from: String, val subject: String, val text: String)

    /** LinkedIn job-alert emails from the last [days] days (newest first, at most [max]), their text decoded. */
    suspend fun linkedInAlerts(email: String, appPassword: String, days: Int = 10, max: Int = 12): List<Mail> =
        imap(email, appPassword) { cmd ->
            val since = SimpleDateFormat("d-MMM-yyyy", Locale.US).format(Date(System.currentTimeMillis() - days * 86_400_000L))
            val ids = search(cmd("UID SEARCH SINCE $since OR FROM \"jobalerts-noreply@linkedin.com\" FROM \"jobs-noreply@linkedin.com\""))
            ids.takeLast(max).reversed().mapNotNull { uid -> fetch(cmd, uid, 200_000) }
        }

    /** Emails that arrived since [sinceMs] from any of [senders] (replies to Rex's applications). */
    suspend fun repliesFrom(email: String, appPassword: String, senders: Collection<String>, sinceMs: Long): List<Mail> =
        imap(email, appPassword) { cmd ->
            val since = SimpleDateFormat("d-MMM-yyyy", Locale.US).format(Date(sinceMs))
            val out = ArrayList<Mail>()
            for (s in senders.take(60)) {
                val ids = search(cmd("UID SEARCH SINCE $since FROM \"${s.replace("\"", "")}\""))
                ids.takeLast(3).forEach { uid -> fetch(cmd, uid, 6_000)?.let { out += it } }
            }
            out
        }

    private suspend fun <T> imap(email: String, pw: String, work: (cmd: (String) -> List<String>) -> T): T = withContext(Dispatchers.IO) {
        if (email.isBlank() || pw.isBlank()) throw MailException("Gmail isn't set up: ☰ → Morning briefing → your Gmail and app password")
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket("imap.gmail.com", 993)
        socket.soTimeout = 30_000
        try {
            val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val out = socket.getOutputStream()
            r.readLine()
            var tag = 0
            val cmd: (String) -> List<String> = { c ->
                val t = "j${++tag}"
                out.write("$t $c\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush()
                val lines = ArrayList<String>()
                while (true) {
                    val l = r.readLine() ?: break
                    lines.add(l)
                    if (l.startsWith("$t ")) break
                }
                lines
            }
            val login = cmd("LOGIN \"$email\" \"${pw.replace(" ", "")}\"")
            if (login.lastOrNull()?.contains(" OK", true) != true) throw MailException("Gmail login failed (check the app password)")
            cmd("SELECT INBOX")
            val result = work(cmd)
            runCatching { cmd("LOGOUT") }
            result
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun search(lines: List<String>): List<String> =
        lines.firstOrNull { it.startsWith("* SEARCH") }?.removePrefix("* SEARCH")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()

    private fun fetch(cmd: (String) -> List<String>, uid: String, maxBytes: Int): Mail? = runCatching {
        val head = cmd("UID FETCH $uid (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT)])").joinToString("\n")
        val raw = cmd("UID FETCH $uid (BODY.PEEK[TEXT]<0.$maxBytes>)").drop(1).dropLast(2).joinToString("\n")
        Mail(uid, decodeWords(header(head, "From")), decodeWords(header(head, "Subject")), readableText(raw))
    }.getOrNull()

    private fun header(block: String, name: String): String =
        Regex("(?im)^$name:\\s*(.*(?:\\n[ \\t].*)*)").find(block)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /**
     * The readable text of a raw message body: each MIME part's quoted-printable or base64 decoded, HTML tags
     * removed. Rough, but enough to find job titles, links and a reply's first lines.
     */
    fun readableText(raw: String): String {
        val parts = raw.split(Regex("""(?m)^--[^\s]+\s*$"""))
        val out = StringBuilder()
        for (p in parts.ifEmpty { listOf(raw) }) {
            val split = p.indexOf("\n\n").takeIf { it >= 0 } ?: p.indexOf("\r\n\r\n")
            val headers = if (split > 0 && p.substring(0, split).contains(Regex("(?i)content-"))) p.substring(0, split) else ""
            var body = if (headers.isNotEmpty()) p.substring(split).trim() else p
            when {
                headers.contains(Regex("(?i)content-transfer-encoding:\\s*base64")) ->
                    body = runCatching { String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8) }.getOrDefault("")
                headers.contains(Regex("(?i)content-transfer-encoding:\\s*quoted-printable")) || body.contains("=\n") || body.contains("=3D") ->
                    body = quotedPrintable(body)
            }
            if (headers.contains(Regex("(?i)content-type:\\s*image|application/(pdf|octet)"))) continue
            if (headers.contains(Regex("(?i)text/html")) || body.contains(Regex("(?i)<(html|div|table|td)\\b"))) {
                body = body.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?i)<a\\s[^>]*href=\"([^\"]+)\"[^>]*>"), "\n$1\n")
                    .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|td|li|h\\d)>"), "\n")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&amp;", "&").replace("&nbsp;", " ").replace(Regex("&#?\\w+;"), " ")
            }
            out.append(body.lines().joinToString("\n") { it.trim() }).append('\n')
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun quotedPrintable(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        val text = s.replace("=\r\n", "").replace("=\n", "")
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 2 < text.length && text[i + 1].isHexDigit() && text[i + 2].isHexDigit()) {
                bytes.write(text.substring(i + 1, i + 3).toInt(16)); i += 3
            } else {
                val b = c.toString().toByteArray(Charsets.UTF_8); bytes.write(b, 0, b.size); i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    /** "=?UTF-8?B?…?=" / "=?…?Q?…?=" in a header, decoded. */
    fun decodeWords(s: String): String = runCatching {
        Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").replace(s) { m ->
            val bytes = if (m.groupValues[2].uppercase() == "B") Base64.getMimeDecoder().decode(m.groupValues[3])
            else quotedPrintable(m.groupValues[3].replace('_', ' ')).toByteArray(Charsets.UTF_8)
            String(bytes, charset(m.groupValues[1]))
        }
    }.getOrDefault(s)
}
