package com.xarvis.ai.jobs

/** One of Rex's target countries: his rank (1 = most wanted), its currency, and names/places that point to it. */
data class Country(val name: String, val rank: Int, val currency: String, val places: List<String> = emptyList()) {
    /** What the hunter searches for. */
    val searchName: String get() = name
}

/**
 * Rex's 33 countries in his order (Nov 2026). "Siberia" (his #31) is Russia's remote and rotational sites, so
 * it's a place inside Russia; "Europe" (#21) covers the EU, the UK and Norway.
 */
object Countries {
    val ranked: List<Country> = listOf(
        Country("Nigeria", 1, "NGN", listOf("Lagos", "Abuja", "Port Harcourt", "Lekki", "Warri", "Bonny Island", "Onne", "Escravos", "Ibeju")),
        Country("Kazakhstan", 2, "KZT", listOf("Atyrau", "Aktau", "Astana", "Almaty", "Tengiz", "Karachaganak", "Kashagan", "Aksai", "Mangystau")),
        Country("South Africa", 3, "ZAR", listOf("Johannesburg", "Durban", "Cape Town", "Secunda", "Richards Bay", "Mossel Bay", "Gauteng", "Sasolburg")),
        Country("Mozambique", 4, "MZN", listOf("Maputo", "Pemba", "Palma", "Cabo Delgado", "Afungi", "Rovuma")),
        Country("Ghana", 5, "GHS", listOf("Accra", "Takoradi", "Tema")),
        Country("Kyrgyzstan", 6, "KGS", listOf("Bishkek", "Kumtor", "Kyrgyz")),
        Country("Uzbekistan", 7, "UZS", listOf("Tashkent", "Bukhara", "Karshi", "Navoi", "Kandym", "Shurtan", "Uzbek")),
        Country("Russia", 8, "RUB", listOf("Russian Federation", "Moscow", "Sakhalin", "Yamal", "Murmansk", "Siberia", "Novy Urengoy", "Ust-Luga", "Arctic LNG", "Tyumen", "Tomsk")),
        Country("Ukraine", 9, "UAH", listOf("Kyiv", "Kiev", "Odesa", "Odessa")),
        Country("Iraq", 10, "IQD", listOf("Basra", "Basrah", "Baghdad", "Erbil", "Kirkuk", "Kurdistan", "Rumaila", "West Qurna")),
        Country("Mauritania", 11, "MRU", listOf("Nouakchott", "Nouadhibou", "Tortue")),
        Country("Papua New Guinea", 12, "PGK", listOf("Port Moresby", "Papua New Guinea")),
        Country("Singapore", 13, "SGD", listOf("Jurong", "Tuas")),
        Country("Malaysia", 14, "MYR", listOf("Kuala Lumpur", "Johor", "Pengerang", "Sarawak", "Bintulu", "Labuan", "Kemaman", "Sabah", "Kerteh")),
        Country("Thailand", 15, "THB", listOf("Bangkok", "Map Ta Phut", "Rayong", "Laem Chabang", "Sattahip")),
        Country("Vietnam", 16, "VND", listOf("Viet Nam", "Ho Chi Minh", "Hanoi", "Vung Tau", "Hai Phong", "Dung Quat")),
        Country("Philippines", 17, "PHP", listOf("Manila", "Batangas", "Subic", "Cebu", "Mindanao")),
        Country("Mongolia", 18, "MNT", listOf("Ulaanbaatar", "Ulan Bator", "Oyu Tolgoi")),
        Country("UAE", 19, "AED", listOf("United Arab Emirates", "Abu Dhabi", "Dubai", "Sharjah", "Ruwais", "Das Island", "Fujairah", "Jebel Ali", "Habshan", "Barakah", "Ras Al Khaimah", "Ajman", "Mussafah")),
        Country("Zambia", 20, "ZMW", listOf("Lusaka", "Copperbelt", "Kitwe", "Ndola", "Solwezi")),
        Country(
            "Europe", 21, "EUR",
            listOf(
                "European Union", "Germany", "Netherlands", "Holland", "Belgium", "France", "Italy", "Spain", "Portugal", "Poland",
                "Norway", "United Kingdom", "UK", "England", "Scotland", "Aberdeen", "Denmark", "Sweden", "Finland", "Ireland",
                "Romania", "Greece", "Croatia", "Czech Republic", "Austria", "Hungary", "Bulgaria", "Rotterdam", "Antwerp",
                "Stavanger", "Bergen", "Hamburg", "Gdansk", "Esbjerg",
            ),
        ),
        Country("Oman", 22, "OMR", listOf("Muscat", "Duqm", "Sohar", "Salalah")),
        Country("Cambodia", 23, "KHR", listOf("Phnom Penh", "Sihanoukville")),
        Country("Laos", 24, "LAK", listOf("Lao PDR", "Vientiane")),
        Country("Indonesia", 25, "IDR", listOf("Jakarta", "Batam", "Balikpapan", "Surabaya", "Tangguh", "Bontang", "Cilegon")),
        Country("Brunei", 26, "BND", listOf("Bandar Seri Begawan", "Kuala Belait", "Brunei Darussalam")),
        Country("Australia", 27, "AUD", listOf("Perth", "Darwin", "Gladstone", "Karratha", "Brisbane", "Western Australia", "Queensland")),
        Country("Namibia", 28, "NAD", listOf("Windhoek", "Walvis Bay", "Luderitz")),
        Country("Saudi Arabia", 29, "SAR", listOf("KSA", "Riyadh", "Jeddah", "Jubail", "Dammam", "Yanbu", "Ras Tanura", "Al Khobar", "Jazan", "NEOM", "Saudi")),
        Country("Qatar", 30, "QAR", listOf("Doha", "Ras Laffan", "Mesaieed", "Dukhan")),
        Country("Greenland", 32, "DKK", listOf("Nuuk")),
        Country("Canada", 33, "CAD", listOf("Alberta", "Fort McMurray", "Calgary", "Edmonton", "Kitimat", "Newfoundland", "British Columbia")),
    )

    private val patterns: List<Pair<Country, Regex>> = ranked.map { c ->
        val names = (listOf(c.name) + c.places).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        // Whole words only; "UK" and "KSA" must be in capitals (so "uk" inside text doesn't count).
        c to Regex("""(?<![A-Za-z])(?:$names)(?![A-Za-z])""", if (c.name == "Europe" || c.name == "Saudi Arabia") emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    fun byName(name: String): Country? {
        val n = name.trim()
        return ranked.firstOrNull { it.name.equals(n, true) || it.places.any { p -> p.equals(n, true) } }
            ?: if (n.equals("siberia", true)) ranked.first { it.name == "Russia" } else null
    }

    /**
     * Which of Rex's countries a job is in: its location first, then its title, then the start of the ad. Among
     * several matches in one field, the earliest mention wins. Null: none of his countries.
     */
    fun detect(location: String, title: String = "", text: String = ""): Country? {
        for (field in listOf(location, title, text.take(1_500))) {
            if (field.isBlank()) continue
            val hit = patterns.mapNotNull { (c, re) -> re.find(field)?.let { c to it.range.first } }.minByOrNull { it.second }
            if (hit != null) return hit.first
        }
        return null
    }

    /** The first [n] countries in Rex's order (a quick hunt), or all of them. */
    fun top(n: Int): List<Country> = ranked.take(n)
}
