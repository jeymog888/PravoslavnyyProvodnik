package ru.liturgyguide.service.discovery

import java.util.Locale

/** A public named station, never the phone's location or a selected parish. */
data class DiscoveryLandmark(val id: String, val name: String, val center: GeoPoint,
    val city: String, val region: String, val country: String,
    val network: String = "", val contextLabel: String = "", val mapColour: String = "") {
    val label: String get() = "Рядом со станцией $name" +
        city.ifBlank { network }.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
}

internal data class LandmarkQuery(val stationQuery: String, val churchQuery: String, val requiresChoice: Boolean = false)

/** Only explicit station markers opt in to this bounded extra search. */
internal object LandmarkSearch {
    private val marker = Regex("(?iu)(?<![а-яёa-z])(?:метро|станци[яию]|м\\.)(?=\\s)")
    private val churchWord = Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|монастыр[а-яё]*|часовн[а-яё]*|троиц[а-яё]*|никол[а-яё]*|казанск[а-яё]*|покров[а-яё]*|успен[а-яё]*|благовещен[а-яё]*|спас[а-яё]*)(?![а-яё])")
    private val generic = setOf("храм", "церковь", "церкви", "собор", "часовня", "монастырь", "рядом", "около", "возле", "найти", "у", "с", "со", "в", "на")
    // Parsing may recognize broad roots, but automatic navigation must not: "Никольск"
    // and "Покров" are also city names. Ambiguous adjectives still require a station choice.
    private val dedicationWords = setOf("троицы", "николая", "чудотворца", "успения", "богородицы",
        "пресвятой", "пресвятого", "благовещения", "покрова", "спасителя", "спаса", "воскресения",
        "христова", "рождества", "креста", "воздвижения", "введения", "предтечи", "святого", "святой")

    fun parse(query: String): LandmarkQuery? {
        val mark = marker.find(query) ?: return null
        if (marker.find(query, mark.range.last + 1) != null) return null
        val prefix = query.substring(0, mark.range.first).trim(' ', ',', '-')
        val suffix = query.substring(mark.range.last + 1).trim(' ', ',', '-')
        if (suffix.length < 2) return null
        // Quotes preserve station names containing a church word, e.g. "Троицкая".
        val quoted = Regex("^[\"«]([^\"»]{2,80})[\"»](.*)$").matchEntire(suffix)
        val boundary = if (quoted == null) churchWord.findAll(suffix).firstOrNull { it.range.first > 0 }?.range?.first else null
        val station = (quoted?.groupValues?.get(1) ?: boundary?.let { suffix.substring(0, it) } ?: suffix).trim(' ', ',', '-')
        val church = listOf(prefix, quoted?.groupValues?.get(2) ?: boundary?.let { suffix.substring(it) }.orEmpty())
            .map(String::trim).filter(String::isNotBlank).joinToString(" ")
        return station.takeIf { it.length in 2..DefaultPlaceDiscovery.MAX_QUERY_LENGTH }
            // An unconstrained prefix can contain a city ("Казань метро Центральная").
            // Do not silently treat it only as the church's name.
            ?.let { LandmarkQuery(it, church, requiresChoice = words(prefix).any { word -> word !in generic && !churchWord.matches(word) }) }
    }

    fun rank(items: List<DiscoveredChurch>, query: String, center: GeoPoint): List<DiscoveredChurch> {
        val wanted = words(query).filterNot { it in generic }.map { it.take(5) }.distinct()
        fun score(church: DiscoveredChurch): Int {
            val name = words(church.name).map { it.take(5) }.toSet()
            return wanted.count { it in name }
        }
        return items.sortedWith(compareByDescending<DiscoveredChurch>(::score)
            .thenBy { center.distanceMeters(it.center) }.thenBy { it.id })
    }

    /** Unknown extra words may name a place; only an explicit station choice may disregard them. */
    fun onlyChurchWords(query: String): Boolean = words(query).all { it in generic || it in dedicationWords }

    fun nearbyName(query: String): String = words(query).filterNot { it in generic }.joinToString(" ").ifBlank { "храм" }

    /** Close coordinates and matching names do not prove that different OSM objects are one station. */
    fun stationChoices(items: List<DiscoveryLandmark>, query: String): List<DiscoveryLandmark> {
        val queryWords = words(query).toSet()
        val exactNames = items.filter { words(it.name).all(queryWords::contains) }
        // Fuzzy or different names remain explicit choices even if Photon returned only one.
        return exactNames.ifEmpty { items }.distinctBy { it.id }
    }

    fun exactName(landmark: DiscoveryLandmark, query: String): Boolean {
        val requested = words(query).toSet() - setOf("в", "г", "город", "городе")
        val name = words(landmark.name).toSet()
        val place = words(listOf(landmark.city, landmark.region, landmark.country).joinToString(" ")).toSet()
        // A matching station name does not override an explicitly requested city.
        // Unrecognized declensions also require a choice instead of a geographic guess.
        return name.isNotEmpty() && requested.containsAll(name) && (requested - name).all(place::contains)
    }

    private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map { it.value }.toList()
}
