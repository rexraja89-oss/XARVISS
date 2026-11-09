package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xarvis.ai.jobs.Countries
import com.xarvis.ai.jobs.JobHunter
import com.xarvis.ai.jobs.JobItem
import com.xarvis.ai.jobs.JobKind
import com.xarvis.ai.jobs.JobStatus
import com.xarvis.ai.jobs.JobStore
import com.xarvis.ai.jobs.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ☰ → Job Hunter: Rex's CVs and details, the hunt buttons, the drafts to approve (each with its trust verdict,
 * what it asks for, the salary in rupees and the email it would send), "Send approved", the jobs to apply to on
 * the site himself, what was sent and who replied, and his trusted / blocked lists. Nothing is sent from here
 * without his tick and his "send".
 */
class JobHunterActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: JobStore
    private lateinit var progress: TextView
    private lateinit var body: LinearLayout
    private var showScams = false
    private var pickingFor: JobKind? = null

    private val teal = Color.parseColor("#0B6E8C")
    private val green = Color.parseColor("#1B8A3A")
    private val amber = Color.parseColor("#B26A00")
    private val red = Color.parseColor("#C62828")
    private val grey = Color.parseColor("#5F6B73")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = JobStore(this)
        val pad = dp(16)
        progress = TextView(this).apply { textSize = 14f; setTextColor(teal); setPadding(0, dp(4), 0, dp(4)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@JobHunterActivity).apply { text = "💼 Job Hunter"; textSize = 24f; setTextColor(teal) })
            addView(progress)
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        scope.launch {
            // CVs added before the PDF reader was fixed: read them again, then rewrite the waiting drafts.
            val reread = withContext(Dispatchers.IO) {
                runCatching { store.rereadCvs { com.xarvis.ai.files.PdfText.read(it, 6) } }.getOrDefault(false)
                    .also { if (it) JobHunter.rewriteDrafts(this@JobHunterActivity) }
            }
            if (reread) {
                render()
                toast("Read your CVs again — check 👤 My details.")
            }
        }
        scope.launch {
            JobHunter.progress.collect { p ->
                progress.text = p.line
                progress.visibility = if (p.line.isBlank()) View.GONE else View.VISIBLE
                if (!p.busy) render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---- building the screen ----

    private fun render() {
        body.removeAllViews()
        val jobs = store.jobs()
        val keys = com.xarvis.ai.llm.BrainKeys(this)

        section("SETUP")
        val gmail = keys.get("brief_email")
        line(if (gmail.isNullOrBlank() || !keys.has("brief_apppw")) "⚠ Gmail not connected: ☰ → Morning briefing → Gmail + app password" else "✓ Gmail: $gmail", if (gmail.isNullOrBlank()) red else green)
        line(
            "Keys: Tavily ${if (keys.has(com.xarvis.ai.llm.BrainKeys.TAVILY)) "✓" else "✗"} · Jooble ${if (keys.has(com.xarvis.ai.llm.BrainKeys.JOOBLE)) "✓" else "✗"}" +
                "   (☰ → BRAIN → Job Hunter keys)",
            grey,
        )
        for (kind in JobKind.values()) {
            val name = store.cvName(kind)
            row(
                text("CV · ${kind.label}: ${name ?: "not added"}", if (name == null) amber else Color.BLACK),
                button(if (name == null) "Add PDF" else "Change") { pickCv(kind) },
            )
        }
        row(button("👤 My details") { editDetails() }, button("🛡 Trusted / blocked") { editLists() })
        line(
            "Used: Tavily ${store.tavilyUsed()}/${JobStore.TAVILY_MONTH} this month · Jooble ${store.joobleUsed()}/500 · sent today ${store.sentToday()}",
            grey,
        )

        section("HUNT")
        val busy = JobHunter.hunting()
        row(
            button("🔎 Quick (top ${JobHunter.QUICK_COUNTRIES})") { hunt(null, false) }.apply { isEnabled = !busy },
            button("🌍 All 33") { hunt(null, true) }.apply { isEnabled = !busy },
            button("📍 One country") { chooseCountry() }.apply { isEnabled = !busy },
        )
        row(button("📩 Check replies") { checkReplies() })

        val drafts = jobs.filter { it.status == JobStatus.NEW || it.status == JobStatus.APPROVED }.sortedByDescending { it.score }
        val approved = drafts.count { it.status == JobStatus.APPROVED }
        section("DRAFTS (${drafts.size}) — tick ✓ the ones to send")
        if (drafts.isNotEmpty()) {
            row(
                button("✓ Approve all ✅ authentic") { approveAuthentic(drafts) },
                button(if (JobHunter.sending()) "⏹ Stop sending" else "📨 Send approved ($approved)") {
                    if (JobHunter.sending()) { JobHunter.stopSending(); toast("Stopped.") } else sendDialog(approved)
                },
            )
        } else {
            line("No drafts yet. Tap 🔎 Quick, or say \"hunt jobs\".", grey)
        }
        drafts.take(80).forEach { draftCard(it) }

        val links = jobs.filter { it.status == JobStatus.LINK }.sortedByDescending { it.score }
        if (links.isNotEmpty()) {
            section("APPLY ON THE SITE YOURSELF (${links.size}) — no email published")
            links.take(40).forEach { linkCard(it) }
        }

        val sent = store.sent().sortedByDescending { it.at }
        if (sent.isNotEmpty()) {
            section("SENT (${sent.size}) · replied ${sent.count { it.replied }}")
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.US)
            sent.take(25).forEach { s ->
                line("${if (s.replied) "📩 replied · " else ""}${fmt.format(Date(s.at))} · ${s.title}${s.company.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()} → ${s.email}", if (s.replied) green else grey)
            }
        }

        val scams = jobs.filter { it.status == JobStatus.SKIPPED && it.verdict == Verdict.SCAM }
        if (scams.isNotEmpty()) {
            section("🚫 SCAMS SKIPPED (${scams.size})")
            if (showScams) scams.take(30).forEach { j -> line("${j.title} · ${j.company.ifBlank { "?" }} — ${j.reasons.joinToString("; ")}", red) }
            row(button(if (showScams) "Hide" else "Show why") { showScams = !showScams; render() })
        }
        val failed = jobs.filter { it.status == JobStatus.FAILED }
        if (failed.isNotEmpty()) {
            section("FAILED TO SEND (${failed.size})")
            failed.take(20).forEach { j ->
                row(text("${j.title} → ${j.email}: ${j.error.orEmpty().take(80)}", red), button("Retry") { setStatus(j, JobStatus.APPROVED) })
            }
        }
    }

    private fun draftCard(j: JobItem) {
        val card = card()
        card.addView(text(j.title, Color.BLACK, 16f, bold = true))
        card.addView(text(listOf(j.company.ifBlank { "company not named" }, j.location.ifBlank { j.country }).joinToString(" · "), Color.BLACK))
        card.addView(text("${j.salaryText} · found on ${j.source}", grey, 12f))
        card.addView(text("${j.verdict.label}: ${j.reasons.joinToString("; ")}", verdictColor(j.verdict), 13f))
        if (j.warnings.isNotEmpty()) card.addView(text(j.warnings.joinToString(" · "), amber, 13f))
        card.addView(text("To: ${j.email} · CV: ${j.kind.label}", grey, 12f))
        val approvedNow = j.status == JobStatus.APPROVED
        card.addView(
            row(
                button(if (approvedNow) "✓ Approved" else "✓ Approve") { setStatus(j, if (approvedNow) JobStatus.NEW else JobStatus.APPROVED) }
                    .apply { setTextColor(if (approvedNow) green else Color.BLACK) },
                button("👁 Email") { preview(j) },
                button("✎") { edit(j) },
                button("🔗") { open(j.url) },
                button("✗") { setStatus(j, JobStatus.SKIPPED) },
                parent = null,
            ),
        )
        body.addView(card)
    }

    private fun linkCard(j: JobItem) {
        val card = card()
        card.addView(text(j.title, Color.BLACK, 15f, bold = true))
        card.addView(text(listOf(j.company.ifBlank { "company not named" }, j.location.ifBlank { j.country }, j.salaryText).joinToString(" · "), grey, 13f))
        card.addView(text("${j.verdict.label}: ${j.reasons.joinToString("; ")}", verdictColor(j.verdict), 12f))
        if (j.warnings.isNotEmpty()) card.addView(text(j.warnings.joinToString(" · "), amber, 12f))
        card.addView(row(button(if (j.url.contains("linkedin.com")) "Open in LinkedIn" else "Open the ad") { open(j.url) }, button("✗ Not interested") { setStatus(j, JobStatus.SKIPPED) }, parent = null))
        body.addView(card)
    }

    private fun verdictColor(v: Verdict) = when (v) { Verdict.AUTHENTIC -> green; Verdict.SUSPICIOUS -> amber; Verdict.SCAM -> red }

    // ---- actions ----

    private fun hunt(country: String?, full: Boolean) {
        toast(JobHunter.startHunt(this, country, full))
        render()
    }

    private fun chooseCountry() {
        val names = Countries.ranked.map { "${it.rank}. ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Hunt in one country").setItems(names) { _, i -> hunt(Countries.ranked[i].name, false) }.show()
    }

    private fun checkReplies() {
        toast("Checking Gmail…")
        scope.launch {
            val lines = withContext(Dispatchers.IO) { runCatching { JobHunter.checkReplies(this@JobHunterActivity, force = true) }.getOrElse { listOf("⚠ ${it.message}") } }
            AlertDialog.Builder(this@JobHunterActivity).setTitle("Replies")
                .setMessage(lines.joinToString("\n\n").ifBlank { "No new replies yet." }).setPositiveButton("OK", null).show()
            render()
        }
    }

    private fun setStatus(j: JobItem, s: JobStatus) {
        store.update(j.id) { it.copy(status = s, error = if (s == JobStatus.APPROVED) null else it.error) }
        render()
    }

    private fun approveAuthentic(drafts: List<JobItem>) {
        val ids = drafts.filter { it.verdict == Verdict.AUTHENTIC && it.status == JobStatus.NEW }.map { it.id }.toSet()
        store.saveJobs(store.jobs().map { if (it.id in ids) it.copy(status = JobStatus.APPROVED) else it })
        toast("Approved ${ids.size}. Check the ⚠️ ones yourself.")
        render()
    }

    private fun sendDialog(approved: Int) {
        if (approved == 0) { toast("Tick ✓ Approve on the drafts you want first."); return }
        val box = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(approved.toString()) }
        AlertDialog.Builder(this)
            .setTitle("How many to send now?")
            .setMessage("Best first, about two minutes apart, from your Gmail, each with the right CV. Sent today: ${store.sentToday()}.")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val n = box.text.toString().toIntOrNull()?.coerceAtLeast(1)
                toast(JobHunter.startSend(this, n))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun preview(j: JobItem) {
        val withLetter = if (j.body.isBlank()) JobHunter.withLetter(store, j) else j
        val cv = store.cvFor(j.kind)?.let { store.cvName(it) } ?: "⚠ no CV added"
        AlertDialog.Builder(this)
            .setTitle(withLetter.subject.ifBlank { j.title })
            .setMessage("To: ${j.email}\nAttached: $cv\n\n${withLetter.body.ifBlank { "(Add your CV first — the letter is written from it.)" }}")
            .setPositiveButton("OK", null)
            .setNeutralButton("Edit") { _, _ -> edit(withLetter) }
            .show()
    }

    private fun edit(j: JobItem) {
        val withLetter = if (j.body.isBlank()) JobHunter.withLetter(store, j) else j
        val to = EditText(this).apply { setText(j.email.orEmpty()); hint = "Email" }
        val subject = EditText(this).apply { setText(withLetter.subject); hint = "Subject" }
        val text = EditText(this).apply { setText(withLetter.body); minLines = 8; gravity = android.view.Gravity.TOP }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(to); addView(subject); addView(text)
        }
        AlertDialog.Builder(this).setTitle("Edit the application").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                store.update(j.id) { it.copy(email = to.text.toString().trim().ifBlank { null }, subject = subject.text.toString(), body = text.text.toString()) }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editDetails() {
        val p = store.profile(store.cvFor(JobKind.SUPERVISOR) ?: JobKind.SUPERVISOR)
        val t = store.profile(store.cvFor(JobKind.TRAINER) ?: JobKind.TRAINER)
        val name = EditText(this).apply { setText(p.name); hint = "Your name" }
        val email = EditText(this).apply { setText(p.email); hint = "Email for the signature" }
        val phones = EditText(this).apply { setText(p.phones.joinToString("\n")); hint = "Phone numbers, one per line" }
        val sup = EditText(this).apply { setText(p.summary); hint = "About you — supervisor letters"; minLines = 4 }
        val tr = EditText(this).apply { setText(t.summary); hint = "About you — instructor / trainer letters"; minLines = 4 }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            listOf("Name" to name, "Email" to email, "Phones" to phones, "About you (supervisor)" to sup, "About you (trainer)" to tr).forEach { (l, v) ->
                addView(text(l, grey, 12f)); addView(v)
            }
        }
        AlertDialog.Builder(this).setTitle("My details (read from your CV)").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                store.saveProfile(name.text.toString(), email.text.toString(), phones.text.toString(), JobKind.SUPERVISOR, sup.text.toString())
                store.saveProfile(name.text.toString(), email.text.toString(), phones.text.toString(), JobKind.TRAINER, tr.text.toString())
                JobHunter.rewriteDrafts(this)
                toast("Saved. Waiting drafts were rewritten.")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editLists() {
        val trusted = EditText(this).apply { setText(store.trusted().sorted().joinToString("\n")); hint = "One per line: a name or a domain"; minLines = 3 }
        val blocked = EditText(this).apply { setText(store.blocked().sorted().joinToString("\n")); hint = "One per line: a name or a domain"; minLines = 3 }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(text("✅ Trusted (always authentic)", green, 13f)); addView(trusted)
            addView(text("🚫 Blocked (never emailed)", red, 13f)); addView(blocked)
        }
        AlertDialog.Builder(this).setTitle("Agencies and companies").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                (store.trusted() + store.blocked()).forEach { store.setTrust(it, null) }
                trusted.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { store.setTrust(it, true) }
                blocked.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { store.setTrust(it, false) }
                JobHunter.retrust(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(url: String) {
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Couldn't open it: ${it.message}") }
    }

    // ---- CVs ----

    private fun pickCv(kind: JobKind) {
        pickingFor = kind
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
        runCatching { startActivityForResult(pick, PICK_CV) }.onFailure { toast("No file picker on this phone.") }
    }

    @Deprecated("A plain Activity's result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val kind = pickingFor ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("couldn't read the file")
                    if (bytes.size > 8_000_000) error("that PDF is over 8 MB — too big to attach")
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "CV.pdf"
                    val text = runCatching { com.xarvis.ai.files.PdfText.read(bytes, 6) }.getOrDefault("")
                    store.saveCv(kind, name, bytes, text)
                    JobHunter.rewriteDrafts(this@JobHunterActivity)
                    store.profile(kind)
                }
            }
            result.onSuccess { p ->
                AlertDialog.Builder(this@JobHunterActivity)
                    .setTitle("CV added · ${kind.label}")
                    .setMessage(
                        "From your CV:\n\nName: ${p.name.ifBlank { "?" }}\nPhones: ${p.phones.joinToString(", ").ifBlank { "?" }}\nEmail: ${p.email.ifBlank { "?" }}\n\n" +
                            "About you: ${p.summary.ifBlank { "(not found — add it in 👤 My details)" }.take(400)}\n\nIf anything is wrong, fix it in 👤 My details.",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }.onFailure { toast("⚠ ${it.message}") }
            render()
        }
    }

    // ---- little view helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, color: Int = Color.BLACK, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(2), 0, dp(2))
    }

    private fun line(t: String, color: Int = Color.BLACK) { body.addView(text(t, color)) }

    private fun section(t: String) {
        body.addView(text(t, teal, 13f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
    }

    /** A wrapping row of views (added to the page unless [parent] is null). */
    private fun row(vararg views: View, parent: LinearLayout? = body): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { v ->
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (v is TextView && v !is Button) 2f else 1f))
        }
        parent?.addView(r)
        return r
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(6))
        setBackgroundColor(Color.parseColor("#F1F5F7"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PICK_CV = 7601
    }
}
