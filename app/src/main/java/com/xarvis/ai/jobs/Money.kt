package com.xarvis.ai.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Salaries in job ads ("USD 3,500 – 4,500 per month", "₦1.2m monthly", "AED 9000", "$250/day") turned into
 * rupees per month, so Rex's minimum (₹1,50,000 a month) can be checked whatever the currency.
 */
object Money {
    /** Rex's minimum, rupees per month. */
    const val MIN_INR_MONTH = 150_000L

    data class Salary(val low: Double, val high: Double, val currency: String, val perMonthFactor: Double) {
        /** The top of the range, per month, in rupees (null: an unknown currency). */
        fun inrPerMonth(rates: Map<String, Double>): Long? {
            val perInr = rupeesPer(currency, rates) ?: return null
            return (high * perMonthFactor * perInr).toLong()
        }
    }

    // Symbols and words for currencies, longest first so "A$" wins over "$".
    private val SYMBOLS = listOf(
        "US$" to "USD", "USD" to "USD", "A$" to "AUD", "AU$" to "AUD", "AUD" to "AUD", "C$" to "CAD", "CA$" to "CAD", "CAD" to "CAD",
        "S$" to "SGD", "SGD" to "SGD", "B$" to "BND", "BND" to "BND", "N$" to "NAD", "NAD" to "NAD", "$" to "USD",
        "€" to "EUR", "EUR" to "EUR", "£" to "GBP", "GBP" to "GBP", "₦" to "NGN", "NGN" to "NGN", "naira" to "NGN",
        "₸" to "KZT", "KZT" to "KZT", "tenge" to "KZT", "₽" to "RUB", "RUB" to "RUB", "rubles" to "RUB", "roubles" to "RUB",
        "₹" to "INR", "INR" to "INR", "Rs." to "INR", "Rs" to "INR", "AED" to "AED", "dirhams" to "AED", "QAR" to "QAR", "QR" to "QAR",
        "SAR" to "SAR", "SR" to "SAR", "riyals" to "SAR", "OMR" to "OMR", "RO" to "OMR", "KWD" to "KWD", "BHD" to "BHD",
        "GHS" to "GHS", "GH₵" to "GHS", "cedis" to "GHS", "ZAR" to "ZAR", "rand" to "ZAR", "MZN" to "MZN", "UZS" to "UZS",
        "KGS" to "KGS", "UAH" to "UAH", "₴" to "UAH", "IQD" to "IQD", "MRU" to "MRU", "PGK" to "PGK", "kina" to "PGK",
        "MYR" to "MYR", "RM" to "MYR", "THB" to "THB", "฿" to "THB", "baht" to "THB", "VND" to "VND", "₫" to "VND",
        "PHP" to "PHP", "₱" to "PHP", "MNT" to "MNT", "₮" to "MNT", "ZMW" to "ZMW", "kwacha" to "ZMW", "KHR" to "KHR",
        "LAK" to "LAK", "₭" to "LAK", "IDR" to "IDR", "Rp" to "IDR", "rupiah" to "IDR", "DKK" to "DKK", "NOK" to "NOK",
        "SEK" to "SEK", "PLN" to "PLN", "CHF" to "CHF",
    ).sortedByDescending { it.first.length }

    private const val NUM = """\d{1,3}(?:[,.\s]\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"""
    // A whole word only: the "m" of "4500 monthly" isn't "million".
    private const val MULT = """(?:(k|m|mn|million|lakh|lakhs|thousand)\b)?"""
    /** An amount, or a range "3,500 - 4,500" / "USD 3k to USD 4k". */
    private val AMOUNT = Regex(
        """($NUM)\s*$MULT(?:\s*(?:-|–|—|to)\s*(?:[A-Za-z$€£₦₸₽₹]{1,4}\s*)?($NUM)\s*$MULT)?(?![\d%])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reads the first salary in [text]. The currency must be written (a bare "4500" could be dollars or naira):
     * without one it's null, and the job counts as "salary not stated" rather than being thrown away.
     */
    fun parse(text: String): Salary? {
        if (text.isBlank()) return null
        val t = text.replace('\u00A0', ' ')
        val (currency, at) = findCurrency(t) ?: return null
        // "USD 3,500 – 4,500" (amount after) or "3,500 USD" (just before).
        val region = t.substring(maxOf(0, at - 16), minOf(t.length, at + 40))
        val m = AMOUNT.findAll(region).firstOrNull { (amount(it.groupValues[1], it.groupValues[2]) ?: 0.0) >= 10 } ?: return null
        val a = amount(m.groupValues[1], m.groupValues[2]) ?: return null
        val b = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { amount(it, m.groupValues[4].ifEmpty { m.groupValues[2] }) }
        val low = minOf(a, b ?: a)
        val high = maxOf(a, b ?: a)
        if (high / maxOf(low, 1.0) > 20) return null
        return Salary(low, high, currency, factor(t.substring(maxOf(0, at - 40), minOf(t.length, at + 120)), high, currency))
    }

    private fun findCurrency(t: String): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for ((sym, code) in SYMBOLS) {
            val re = if (sym.any { it.isLetter() }) Regex("""(?<![A-Za-z])${Regex.escape(sym)}(?![A-Za-z])""", if (sym.length > 3) setOf(RegexOption.IGNORE_CASE) else emptySet())
            else Regex(Regex.escape(sym))
            val m = re.find(t) ?: continue
            // Only when a number is close by (so "USD" in "paid in USD" with no amount doesn't count).
            val near = t.substring(maxOf(0, m.range.first - 20), minOf(t.length, m.range.last + 25))
            if (!Regex("""\d{2,}""").containsMatchIn(near)) continue
            if (best == null || m.range.first < best.second) best = code to m.range.first
        }
        return best
    }

    private fun amount(num: String, mult: String): Double? {
        val clean = num.trim().let {
            // "3,500" / "3.500" / "3 500" are thousands; "3.5" is a decimal.
            if (Regex("""^\d{1,3}(?:[,.\s]\d{3})+$""").matches(it)) it.replace(Regex("[,.\\s]"), "") else it.replace(",", "")
        }
        val v = clean.toDoubleOrNull() ?: return null
        return v * when (mult.lowercase()) {
            "k", "thousand" -> 1_000.0
            "m", "mn", "million" -> 1_000_000.0
            "lakh", "lakhs" -> 100_000.0
            else -> 1.0
        }
    }

    /** Per month: daily ×26, hourly ×208, weekly ×4.33, yearly ÷12; else monthly (big round yearly sums too). */
    private fun factor(near: String, high: Double, currency: String): Double {
        val n = near.lowercase()
        return when {
            Regex("""per\s*day|/\s*day|a day|daily|day rate|per diem""").containsMatchIn(n) -> 26.0
            Regex("""per\s*hour|/\s*h(ou)?r|hourly|an hour""").containsMatchIn(n) -> 208.0
            Regex("""per\s*week|/\s*week|weekly""").containsMatchIn(n) -> 4.33
            Regex("""per\s*(year|annum)|/\s*(year|yr|annum)|annual|yearly|\bp\.\s?a\.|\bpa\b""").containsMatchIn(n) -> 1.0 / 12
            // No period: in strong currencies a six-figure number is a year's pay.
            currency in setOf("USD", "EUR", "GBP", "AUD", "CAD", "SGD", "CHF") && high >= 60_000 -> 1.0 / 12
            else -> 1.0
        }
    }

    /** Rupees per 1 unit of [currency]. [rates] are "units per 1 INR" (open.er-api.com/v6/latest/INR). */
    fun rupeesPer(currency: String, rates: Map<String, Double>): Double? {
        if (currency == "INR") return 1.0
        rates[currency]?.takeIf { it > 0 }?.let { return 1.0 / it }
        return FALLBACK_INR[currency]
    }

    /** Rough rupee values (Nov 2026) for when the live rates can't be fetched; amounts then show "≈". */
    val FALLBACK_INR: Map<String, Double> = mapOf(
        "USD" to 88.0, "EUR" to 101.0, "GBP" to 116.0, "AED" to 24.0, "QAR" to 24.2, "SAR" to 23.5, "OMR" to 229.0,
        "KWD" to 287.0, "BHD" to 234.0, "NGN" to 0.058, "KZT" to 0.17, "RUB" to 1.05, "UZS" to 0.0072, "KGS" to 1.0,
        "UAH" to 2.1, "IQD" to 0.067, "MRU" to 2.2, "PGK" to 22.0, "SGD" to 67.0, "MYR" to 20.5, "THB" to 2.6,
        "VND" to 0.0034, "PHP" to 1.5, "MNT" to 0.025, "ZMW" to 3.3, "KHR" to 0.022, "LAK" to 0.004, "IDR" to 0.0054,
        "BND" to 67.0, "AUD" to 57.0, "NAD" to 4.9, "ZAR" to 4.9, "GHS" to 5.8, "MZN" to 1.38, "DKK" to 13.5,
        "NOK" to 8.2, "SEK" to 8.4, "PLN" to 23.5, "CHF" to 108.0, "CAD" to 63.0,
    )

    @Volatile private var cached: Map<String, Double> = emptyMap()
    @Volatile private var cachedAt = 0L

    /** Live rates (units per 1 INR) from open.er-api.com (free, no key), at most once every 12 hours. */
    suspend fun liveRates(): Map<String, Double> = withContext(Dispatchers.IO) {
        if (cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < 12 * 3_600_000L) return@withContext cached
        runCatching {
            val c = URL("https://open.er-api.com/v6/latest/INR").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val r = json.getJSONObject("rates")
            r.keys().asSequence().associateWith { r.getDouble(it) }.also { cached = it; cachedAt = System.currentTimeMillis() }
        }.getOrDefault(cached)
    }

    /** "₹2,64,000/month" in the Indian grouping Rex reads. */
    fun inr(amount: Long): String {
        val s = amount.toString()
        if (s.length <= 3) return "₹$s"
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "₹$rest,$last3"
    }
}
