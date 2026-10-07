package com.xarvis.ai.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A cloud brain that speaks the OpenAI "chat/completions" format — which Groq, Cerebras, OpenRouter,
 * GitHub Models and OpenAI all do. One class, parameterised by [base] URL, [models] to try (best
 * first) and the key [id]. Keys stay in the Keystore ([BrainKeys]); only sent to [base]'s provider.
 */
class OpenAiCompat(
    context: Context,
    override val id: String,
    override val label: String,
    private val base: String,
    private val models: List<String>,
    override val keyHint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Brain {

    private val keys = BrainKeys(context.applicationContext)

    override fun hasKey(): Boolean = keys.has(id)
    override fun saveKey(key: String) = keys.save(id, key)
    override fun clearKey() = keys.clear(id)

    override suspend fun chat(systemPrompt: String, message: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val key = keys.get(id) ?: throw IllegalStateException("no $label key")
        var lastError: Exception? = null
        for (model in models) {
            try {
                return@withContext call(model, key, systemPrompt, message, maxTokens)
            } catch (e: QuotaReached) {
                throw e
            } catch (e: ModelNotFound) {
                lastError = e // that model isn't available; try the next for this provider
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("$label didn't answer")
    }

    private class ModelNotFound(message: String) : Exception(message)

    private fun call(model: String, key: String, systemPrompt: String, message: String, maxTokens: Int): String {
        val url = URL("$base/chat/completions")
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", message)),
            )
            .put("max_tokens", maxTokens)
            .put("temperature", 0.7)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw QuotaReached("$label's free limit is used up for now")
            if (code == 404 || code == 400) throw ModelNotFound("model $model unavailable on $label ($code)")
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "$label $code: ${err.take(300)}")
                val why = Regex(""""message"\s*:\s*"([^"]{0,120})""").find(err)?.groupValues?.get(1)
                throw IllegalStateException("$label $code" + (why?.let { ": $it" } ?: ""))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isEmpty()) throw IllegalStateException("$label returned nothing")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "XarvisCloud"
    }
}
