package com.xarvis.ai.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The cloud brain hit its free daily/rate limit; XARVIS falls back to Gemma on the phone. */
class QuotaReached(message: String) : Exception(message)

/**
 * The optional cloud brain: Google's Gemini over its free API, chosen by Rex in ☰ → BRAIN with
 * his own key (stored encrypted, [BrainKeys]). Privacy: the caller sends a system prompt that
 * carries NO saved memories, and XARVIS only routes non-private messages here (see the agent's
 * router). On a quota or network problem it throws, and the agent answers with Gemma instead.
 */
class CloudLlm(context: Context) {

    private val appContext = context.applicationContext
    private val keys = BrainKeys(appContext)

    fun hasKey(): Boolean = keys.has(BrainKeys.GEMINI)

    fun saveKey(key: String) = keys.save(BrainKeys.GEMINI, key)

    fun clearKey() = keys.clear(BrainKeys.GEMINI)

    fun online(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** Asks Gemini. Returns its text; throws [QuotaReached] on a 429, or another exception otherwise. */
    suspend fun chat(systemPrompt: String, message: String): String = withContext(Dispatchers.IO) {
        val key = keys.get(BrainKeys.GEMINI) ?: throw IllegalStateException("no Gemini key")
        var lastError: Exception? = null
        for (model in MODELS) {
            try {
                return@withContext call(model, key, systemPrompt, message)
            } catch (e: QuotaReached) {
                throw e
            } catch (e: ModelNotFound) {
                lastError = e // that model id isn't available to this key; try the next
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Gemini didn't answer")
    }

    private class ModelNotFound(message: String) : Exception(message)

    private fun call(model: String, key: String, systemPrompt: String, message: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", message)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1536).put("temperature", 0.7))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw QuotaReached("Gemini's free limit is used up for now")
            if (code == 404 || code == 400) throw ModelNotFound("model $model unavailable ($code)")
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Gemini $code: ${err.take(300)}")
                throw IllegalStateException("Gemini error $code")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (text.isEmpty()) throw IllegalStateException("Gemini returned nothing")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "XarvisCloud"
        // Tried in order; the first the key can use wins. Ids change, so several are listed.
        private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
    }
}
