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
 *
 * Model names change and old ones (e.g. gemini-1.5-flash) get retired and 404. So instead of a
 * fixed list, XARVIS asks the key which models it actually has ([discoverModels]) and uses the
 * best chat model among them; the list only ever contains models this key can call.
 */
class CloudLlm(context: Context) {

    private val appContext = context.applicationContext
    private val keys = BrainKeys(appContext)

    /** Models this key can call (newest chat model first). Discovered once, then reused. */
    @Volatile private var cachedModels: List<String>? = null
    /** The model that last answered, tried first next time. */
    @Volatile private var working: String? = null

    fun hasKey(): Boolean = keys.has(BrainKeys.GEMINI)

    fun saveKey(key: String) {
        keys.save(BrainKeys.GEMINI, key)
        cachedModels = null // a new key may have different models
        working = null
    }

    fun clearKey() {
        keys.clear(BrainKeys.GEMINI)
        cachedModels = null
        working = null
    }

    fun online(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
    }.getOrDefault(false)

    /** Asks Gemini. Returns its text; throws [QuotaReached] on a 429, or another exception otherwise. */
    suspend fun chat(systemPrompt: String, message: String): String = withContext(Dispatchers.IO) {
        val key = keys.get(BrainKeys.GEMINI) ?: throw IllegalStateException("no Gemini key")
        runModels(modelsToTry(key), key, systemPrompt, message, remember = true)
    }

    /**
     * The AI council (Rex asked): one model drafts, then a second, independent model reviews it for
     * mistakes/gaps and writes the best final answer. Uses two different models from the key when it
     * has them (a real second opinion), else the same model reviews its own draft. Two cloud calls,
     * so it spends the free quota faster — that's why it's a switch. If the review can't run (quota,
     * error) the draft is returned, so the council never leaves Rex worse off than solo.
     */
    suspend fun council(systemPrompt: String, message: String, onStage: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val key = keys.get(BrainKeys.GEMINI) ?: throw IllegalStateException("no Gemini key")
            onStage("Drafting an answer…")
            val draft = runModels(modelsToTry(key), key, systemPrompt, message, remember = true)
            onStage("A second AI is reviewing the answer…")
            try {
                // Prefer a different model than the one that drafted, for a genuine second opinion.
                val order = alternateOrder(modelsToTry(key), working)
                runModels(order, key, systemPrompt, reviewMessage(message, draft), remember = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Council review failed; keeping the draft", e)
                draft // the draft already cost a call and is a good answer on its own
            }
        }

    /** Tries each model in order; returns the first answer. [remember] records the model that worked. */
    private fun runModels(models: List<String>, key: String, systemPrompt: String, message: String, remember: Boolean): String {
        var lastError: Exception? = null
        for (model in models) {
            try {
                val answer = call(model, key, systemPrompt, message)
                if (remember) working = model
                return answer
            } catch (e: QuotaReached) {
                throw e
            } catch (e: ModelNotFound) {
                lastError = e // that model id isn't available to this key; try the next
                if (working == model) working = null
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Gemini didn't answer")
    }

    private class ModelNotFound(message: String) : Exception(message)

    /** The models to try, best first: the last working one, then everything the key lists. */
    private fun modelsToTry(key: String): List<String> {
        val discovered = cachedModels ?: discoverModels(key).also { if (it.isNotEmpty()) cachedModels = it }
        val base = discovered.ifEmpty { FALLBACK_MODELS }
        val w = working
        return if (w != null && base.contains(w)) listOf(w) + base.filter { it != w } else base
    }

    /** Asks the key which models it has; keeps the chat models, best first. Empty if it can't ask. */
    private fun discoverModels(key: String): List<String> = runCatching {
        val url = URL("$BASE/models?key=$key&pageSize=1000")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "ListModels ${conn.responseCode}: ${err.take(200)}")
                return@runCatching emptyList<String>()
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = json.optJSONArray("models") ?: return@runCatching emptyList<String>()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")
                val supportsChat = methods != null &&
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (!supportsChat) continue
                val name = m.optString("name").removePrefix("models/")
                if (name.isNotEmpty()) ids.add(name)
            }
            ids.sortedByDescending { score(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    /** Ranks models for chat: prefer newer "flash" (fast, big free quota), avoid preview/dated ones. */
    private fun score(id: String): Int {
        var s = when {
            id.contains("flash-lite") -> 80
            id.contains("flash") -> 100
            id.contains("pro") -> 50
            else -> 10
        }
        // "2.5" -> +25, "2.0" -> +20, a future "3.0" -> +30: newer wins.
        Regex("""(\d+)\.(\d+)""").find(id)?.let { s += it.groupValues[1].toInt() * 10 + it.groupValues[2].toInt() }
        if (id.contains("preview") || id.contains("exp")) s -= 25
        if (id.contains("thinking")) s -= 10
        if (Regex("""-\d{3,}$""").containsMatchIn(id)) s -= 5 // a dated snapshot; prefer the clean alias
        return s
    }

    /** True if [model]'s "x.y" version is at least [major].[minor] (e.g. gemini-2.5-flash ≥ 2.5). */
    private fun versionAtLeast(model: String, major: Int, minor: Int): Boolean =
        Regex("""(\d+)\.(\d+)""").find(model)?.let {
            val hi = it.groupValues[1].toInt()
            hi > major || (hi == major && it.groupValues[2].toInt() >= minor)
        } == true

    private fun call(model: String, key: String, systemPrompt: String, message: String): String {
        val url = URL("$BASE/models/$model:generateContent?key=$key")
        val gen = JSONObject().put("maxOutputTokens", 1536).put("temperature", 0.7)
        // 2.5+ flash "thinks" by default and can spend the whole budget thinking, leaving no text.
        // Turn thinking off for a direct answer (only flash models accept a 0 budget).
        if (model.contains("flash") && (model.contains("latest") || versionAtLeast(model, 2, 5))) {
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", message)))))
            .put("generationConfig", gen)
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
                val why = Regex(""""message"\s*:\s*"([^"]{0,120})""").find(err)?.groupValues?.get(1)
                throw IllegalStateException("Gemini $code" + (why?.let { ": $it" } ?: ""))
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
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        // Only used if the key can't be asked for its model list (the usual path is discovery).
        private val FALLBACK_MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-latest")

        /** Models to try for the council review: a different one than [drafted] first, then the rest. */
        internal fun alternateOrder(models: List<String>, drafted: String?): List<String> {
            if (drafted == null) return models
            return (models.filter { it != drafted } + models).distinct()
        }

        /** The prompt that turns a second model into an independent reviewer of the [draft]. */
        internal fun reviewMessage(question: String, draft: String): String = buildString {
            append("A user asked:\n\"").append(question).append("\"\n\n")
            append("Another assistant drafted this answer:\n\"").append(draft).append("\"\n\n")
            append("You are a second, independent expert giving a careful review. Check the draft for ")
            append("mistakes, missing points, and anything unclear, then write the best possible FINAL ")
            append("answer for the user. Keep what is good; fix what is wrong. ")
            append("Reply with ONLY the final answer — do not mention the draft, the review, or yourself.")
        }
    }
}
