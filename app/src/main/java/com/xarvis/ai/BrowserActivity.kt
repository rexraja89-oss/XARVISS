package com.xarvis.ai

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * XARVIS Browser: open a job application page, tap FILL, and it fills the
 * plain text fields it clearly recognises from the profile saved on this phone.
 * It never touches buttons, checkboxes, radio buttons, passwords or CAPTCHAs,
 * and never submits. The user checks everything and taps Submit.
 */
class BrowserActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("xarvis_job_profile", MODE_PRIVATE) }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        address = EditText(this).apply {
            hint = "Paste a job link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }

        status = TextView(this).apply {
            text = "Open a job application, then tap FILL."
            setPadding(24, 12, 24, 12)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null) address.setText(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    val chooser = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    return try {
                        pickFile.launch(chooser)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(Button(context).apply { text = "PROFILE"; setOnClickListener { showProfile() } })
            addView(Button(context).apply { text = "FILL"; setOnClickListener { fill() } })
        }

        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(address, LinearLayout.LayoutParams(match, wrap))
            addView(web, LinearLayout.LayoutParams(match, 0, 1f))
            addView(status, LinearLayout.LayoutParams(match, wrap))
            addView(bar, LinearLayout.LayoutParams(match, wrap))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        // Opened from Chrome's Share menu: pick the link out of the shared text.
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val link = shared?.let { Regex("https?://\\S+").find(it)?.value }
        if (link != null) load(link)

        if (prefs.getString("profile", "").isNullOrBlank()) {
            status.text = "First tap PROFILE and fill in your details."
        }
    }

    private fun load(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        web.loadUrl(if (t.startsWith("http")) t else "https://$t")
    }

    private fun profileJson(): JSONObject {
        val keys = mapOf(
            "first name" to "first",
            "last name" to "last",
            "email" to "email",
            "phone" to "phone",
            "city" to "city",
            "country" to "country",
            "linkedin" to "linkedin",
            "current title" to "title",
            "current company" to "company",
            "years of experience" to "years"
        )
        val json = JSONObject()
        (prefs.getString("profile", "") ?: "").lines().forEach { line ->
            val key = keys[line.substringBefore(":").trim().lowercase()] ?: return@forEach
            val value = line.substringAfter(":", "").trim()
            if (value.isNotEmpty()) json.put(key, value)
        }
        val full = listOf(json.optString("first"), json.optString("last"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (full.isNotEmpty()) json.put("full", full)
        return json
    }

    private fun fill() {
        val host = Uri.parse(web.url ?: "").host ?: ""
        if (host.contains("linkedin.") || host.contains("indeed.")) {
            status.text = "FILL is switched off on LinkedIn and Indeed."
            return
        }
        val profile = profileJson()
        if (profile.length() == 0) {
            status.text = "Tap PROFILE and add your details first."
            return
        }
        web.evaluateJavascript(FILL_JS.replace("__PROFILE__", profile.toString())) { result ->
            val parts = (result ?: "").trim('"').split("|")
            status.text = when {
                parts.size != 2 ->
                    "Couldn't read this form."
                parts[0] == "0" && parts[1] == "0" ->
                    "No form found here. If the form sits inside a frame, open the form's own link."
                else ->
                    "Filled ${parts[0]} fields, ${parts[1]} need you (orange). Check everything and tap Submit yourself."
            }
        }
    }

    private fun showProfile() {
        val box = EditText(this).apply {
            setText(prefs.getString("profile", null) ?: TEMPLATE)
            minLines = 10
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("My profile (stays on this phone)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("profile", box.text.toString()).apply()
                status.text = "Profile saved. Open a form and tap FILL."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private val TEMPLATE = listOf(
            "First name", "Last name", "Email", "Phone", "City", "Country",
            "LinkedIn", "Current title", "Current company", "Years of experience"
        ).joinToString("\n") { "$it: " }

        // Runs inside the page. Only fills empty text fields it clearly
        // recognises. Never clicks, ticks or submits anything.
        private val FILL_JS = """
(function(){
  var P = __PROFILE__;
  var rules = [
    ['first', /first.?name|given.?name|fname/],
    ['last', /last.?name|surname|family.?name|lname/],
    ['email', /e-?mail/],
    ['phone', /phone|mobile|^tel$/],
    ['linkedin', /linkedin/],
    ['country', /country/],
    ['city', /\bcity\b|\btown\b|address-level2/],
    ['title', /current.?(job.?)?title|current.?role|current.?position/],
    ['company', /current.?(company|employer)|^employer$/],
    ['years', /years.?(of.?)?experience/],
    ['full', /^(full.?)?name\s*\*?$|your.?name|candidate.?name/]
  ];
  var never = /password|captcha|otp|one.?time|verification|card|cvv|iban|signature|salary|passport|social.?security/;
  function parts(el){
    var t = [];
    if (el.id) { var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) t.push(l.innerText); }
    var p = el.closest('label'); if (p) t.push(p.innerText);
    var lb = el.getAttribute('aria-labelledby');
    if (lb) lb.split(' ').forEach(function(i){ var e = document.getElementById(i); if (e) t.push(e.innerText); });
    ['aria-label','placeholder','name','id','autocomplete'].forEach(function(a){ var v = el.getAttribute(a); if (v) t.push(v); });
    return t.map(function(s){ return (s || '').toLowerCase().replace(/\s+/g, ' ').trim(); }).filter(Boolean);
  }
  function mark(el, c){ el.style.outline = '3px solid ' + c; el.style.outlineOffset = '1px'; }
  function setVal(el, v){
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    ['input','change','blur'].forEach(function(n){ el.dispatchEvent(new Event(n, {bubbles: true})); });
  }
  var filled = 0, left = 0, groups = {};
  document.querySelectorAll('input, textarea, select').forEach(function(el){
    var type = (el.getAttribute('type') || el.tagName).toLowerCase();
    if (['hidden','submit','button','reset','image'].indexOf(type) >= 0) return;
    if (el.offsetParent === null || el.disabled || el.readOnly) return;
    var textLike = el.tagName === 'TEXTAREA' ||
      ['input','text','email','tel','url','number','search'].indexOf(type) >= 0;
    if (!textLike) {
      if (type === 'radio' || type === 'checkbox') {
        if (el.required && !groups[el.name]) { groups[el.name] = 1; mark(el, 'orange'); left++; }
        return;
      }
      if (!el.value) { mark(el, 'orange'); left++; }
      return;
    }
    if (el.value) return;
    var ps = parts(el);
    if (never.test(ps.join(' | '))) { mark(el, 'orange'); left++; return; }
    var key = null;
    for (var i = 0; i < rules.length && !key; i++) {
      var re = rules[i][1];
      if (ps.some(function(s){ return re.test(s); })) key = rules[i][0];
    }
    if (key && P[key]) { setVal(el, P[key]); mark(el, '#2e7d32'); filled++; }
    else { mark(el, 'orange'); left++; }
  });
  return filled + '|' + left;
})();
"""
    }
}
