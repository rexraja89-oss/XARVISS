package com.xarvis.ai.brief

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A short weather line from wttr.in (free, no key). Empty string if it can't be read. */
object Weather {
    suspend fun summary(city: String): String = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext ""
        runCatching {
            val url = "https://wttr.in/" + URLEncoder.encode(city.trim(), "UTF-8") + "?format=%C,+%t+(feels+%f),+%p+precip&m"
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 12_000
                setRequestProperty("User-Agent", "curl/8")
            }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            c.disconnect()
            if (text.isBlank() || text.contains("Unknown location", true) || text.length > 160) "" else text
        }.getOrDefault("")
    }
}
