package ru.liturgyguide.service.discovery

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale

/** Small, immutable public map index. Loading never requests the phone's location or the network. */
class BundledPlaceGazetteer(private val open: () -> Reader, private val clock: Clock = Clock.systemUTC()) {
    private val lock = Mutex()
    private var loaded = false
    private var gazetteer: LocalPlaceGazetteer? = null

    internal suspend fun get(): LocalPlaceGazetteer? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                val context = currentCoroutineContext()
                gazetteer = try {
                    open().use { LocalPlaceGazetteer.decode(it, clock.instant()) { context.ensureActive() } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                // A cancelled reader can be retried; a corrupt packaged asset needs an app update.
                loaded = true
            }
            gazetteer
        }
    }

    companion object { const val ASSET_PATH = "discovery/places-ru.json" }
}

internal class LocalPlaceGazetteer private constructor(
    val fetchedAt: Long, private val cities: List<Entry>, private val stations: List<Entry>,
) {
    private data class Entry(
        val id: String, val name: String, val aliases: List<String>, val center: GeoPoint,
        val city: String, val region: String, val country: String, val network: String, val contextLabel: String, val mapColour: String,
    ) {
        val primary = PlaceWords.normalized(name)
        val names = (listOf(name) + aliases).map(PlaceWords::normalized).distinct()
        val contextWords = PlaceWords.words(listOf(region, country, PlaceWords.countryName(country), contextLabel).joinToString(" ")).toSet()
        fun asCity() = DiscoveryCity(id, name, region, country, center, contextLabel)
        fun asStation() = DiscoveryLandmark(id, name, center, city, region, country, network, contextLabel, mapColour)
    }

    /** A source city point is a proximity anchor, not an administrative boundary. */
    data class ChurchCityScope(val city: DiscoveryCity, val remainingQuery: String)

    private data class CityPhrase(val entry: Entry, val nameStart: Int, val endExclusive: Int,
        val removalStart: Int, val eligible: Boolean)

    fun churchCityScope(query: String, checkCancelled: () -> Unit = {}): ChurchCityScope? {
        val raw = CatalogWords.words(query)
        if (raw.none { it !in CatalogWords.generic }) return null
        val candidates = mutableListOf<CityPhrase>()
        for ((index, entry) in cities.withIndex()) {
            if (index % 128 == 0) checkCancelled()
            // Only the primary source name can bind a free-text church query.
            // Prefixes, aliases and a capped city-search result are not evidence.
            val name = CatalogWords.words(entry.name)
            if (name.isEmpty() || name.size > raw.size) continue
            for (start in 0..raw.size - name.size) {
                val end = start + name.size
                val marker = raw.getOrNull(start - 1)
                val phrase = raw.subList(start, end)
                // Forms can suppress an unsafe shorter-name fallback. They do not
                // authorize a city: the separate grammar below must still accept it.
                if (!phrase.indices.all { word -> phrase[word] in CatalogWords.cityForms(name[word]) }) continue
                val exact = phrase == name
                val locative = phrase.indices.all { word -> phrase[word] in cityLocatives(name[word], word < name.lastIndex) }
                val marked = when (marker) {
                    "в", "во", "городе" -> locative
                    "г", "город" -> exact
                    else -> false
                }
                val trailing = end == raw.size && exact && marker !in LOCATION_MARKERS && marker !in NON_LOCATION_PREFIXES
                candidates += CityPhrase(entry, start, end, if (marked) start - 1 else start, marked || trailing)
            }
        }
        // Match a whole multiword name before a contained shorter city name.
        // Exact namesakes remain separate entities and therefore remain ambiguous.
        val longest = candidates.filter { candidate -> candidates.none { other ->
            other.nameStart <= candidate.nameStart && other.endExclusive >= candidate.endExclusive &&
                other.endExclusive - other.nameStart > candidate.endExclusive - candidate.nameStart
        } }
        val selected = longest.filter { it.eligible }.singleOrNull() ?: return null
        return ChurchCityScope(selected.entry.asCity(), raw.filterIndexed { index, _ ->
            index !in selected.removalStart until selected.endExclusive
        }.joinToString(" "))
    }

    private fun cityLocatives(word: String, keepUnchangedPrefix: Boolean): Set<String> {
        val endings = when {
            word.endsWith("ая") || word.endsWith("яя") -> listOf("ой", "ей")
            listOf("ое", "ее", "ий", "ый", "ой").any(word::endsWith) -> listOf("ом", "ем")
            word.endsWith("а") || word.endsWith("я") -> listOf("е", "и")
            word.endsWith("ь") -> listOf("и", "е")
            word.last() in "бвгджзклмнпрстфхцчшщ" -> listOf("е")
            else -> emptyList()
        }
        val forms = CatalogWords.cityForms(word).filter { form -> endings.any(form::endsWith) }.toSet()
        // Uninflected places and fixed prefixes such as Санкт retain their source form.
        return forms + if (keepUnchangedPrefix || endings.isEmpty()) setOf(word) else emptySet()
    }

    fun searchCities(query: String, checkCancelled: () -> Unit = {}): DiscoveryResult.Success<DiscoveryCity> {
        val wanted = PlaceWords.words(query).filterNot { it in PlaceWords.cityMarkers }
        if (wanted.isEmpty()) return result(emptyList())
        val normalized = wanted.joinToString(" ")
        val matches = cities.mapIndexedNotNull { index, entry ->
            if (index % 128 == 0) checkCancelled()
            val exact = normalized in entry.names
            val named = entry.names.any { name ->
                val nameWords = PlaceWords.words(name)
                wanted.any { q -> nameWords.any { PlaceWords.prefix(it, q) } } &&
                    wanted.all { q -> nameWords.any { PlaceWords.prefix(it, q) } || entry.contextWords.any { PlaceWords.prefix(it, q) } }
            }
            if (!exact && !named) null else entry to if (exact) 2 else 1
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.name }
            .thenBy { it.first.region }.thenBy { it.first.contextLabel }.thenBy { it.first.id })
        // The public endpoint's limit of five is not appropriate for an already loaded index.
        // Every exact namesake survives; only a broad prefix is limited.
        val kept = matches.filter { it.second == 2 } + matches.filter { it.second != 2 }.take(MAX_PREFIX_RESULTS)
        return result(kept.map { it.first.asCity() }).copy(notice =
            if (kept.size < matches.size) "Уточните название города, чтобы сузить список" else null)
    }

    fun stationChoices(query: String, checkCancelled: () -> Unit = {}): List<DiscoveryLandmark> {
        val normalized = PlaceWords.normalized(query)
        if (normalized.isBlank()) return emptyList()
        val words = PlaceWords.words(query)
        val matches = stations.mapIndexedNotNull { index, entry ->
            if (index % 128 == 0) checkCancelled()
            val score = entry.names.maxOf { name -> when {
                name == normalized -> if (name == entry.primary) 4 else 3
                PlaceWords.containsPhrase(words, PlaceWords.words(name)) -> 2
                name.startsWith(normalized) -> 1
                else -> 0
            } }
            (entry to score).takeIf { score > 0 }
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.name }
            .thenBy { it.first.network }.thenBy { it.first.id })
        // No proximity-based interchange merging: raw OSM nodes keep their own identity.
        return (matches.filter { it.second >= 2 } + matches.filter { it.second == 1 }.take(MAX_PREFIX_RESULTS))
            .map { it.first.asStation() }
    }

    fun isExactPrimary(station: DiscoveryLandmark, query: String): Boolean =
        PlaceWords.normalized(station.name) == PlaceWords.normalized(query)

    fun <T> result(items: List<T>): DiscoveryResult.Success<T> = DiscoveryResult.Success(
        items, fetchedAt, DiscoveryCacheStatus.LOCAL_SNAPSHOT, DiscoveryProvider.LOCAL_CATALOG)

    companion object {
        private val LOCATION_MARKERS = setOf("в", "во", "г", "город", "городе")
        private val NON_LOCATION_PREFIXES = setOf("св", "свт", "святой", "святого", "святителя", "святых",
            "князь", "князя", "равноапостольного", "мученика", "преподобного", "имени", "имя", "честь",
            "икона", "иконы", "улица", "улицы", "ул", "переулок", "переулка", "пер", "проспект", "проспекта",
            "площадь", "площади", "набережная", "набережной", "шоссе", "из", "с", "со", "к", "от")
        const val MAX_CHARACTERS = 16 * 1024 * 1024
        const val MAX_RECORDS = 20_000
        const val MAX_PREFIX_RESULTS = 60
        private val ID = Regex("osm:node:([1-9][0-9]{0,18})")
        private val NAME_KEYS = listOf("name", "name:ru", "alt_name", "alt_name:ru", "official_name", "official_name:ru",
            "short_name", "short_name:ru", "loc_name", "loc_name:ru", "name:en", "int_name")
        private val INACTIVE = listOf("disused", "abandoned", "construction", "proposed")
        private val FALSE = setOf("", "no", "false", "0")

        fun decode(input: Reader, now: Instant, checkCancelled: () -> Unit = {}): LocalPlaceGazetteer {
            var version: Int? = null
            var metadata: JsonObject? = null
            val cities = mutableListOf<Entry>()
            val stations = mutableListOf<Entry>()
            val keys = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            JsonReader(GazetteerLimitedReader(input, MAX_CHARACTERS, checkCancelled)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                while (reader.hasNext()) {
                    checkCancelled()
                    val key = reader.nextName()
                    require(keys.add(key))
                    when (key) {
                        "schemaVersion" -> { require(reader.peek() == JsonToken.NUMBER); version = reader.nextInt() }
                        "metadata" -> metadata = strictValue(reader, TreeBudget()).asJsonObject
                        "cities", "stations" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                require(ids.size < MAX_RECORDS)
                                if (ids.size % 128 == 0) checkCancelled()
                                val entry = parseEntry(strictValue(reader, TreeBudget()).asJsonObject, key == "stations")
                                require(ids.add(entry.id))
                                if (key == "cities") cities += entry else stations += entry
                            }
                            reader.endArray()
                        }
                        else -> throw IllegalArgumentException("Unexpected place gazetteer field")
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            require(version == 1 && keys.containsAll(listOf("cities", "stations")) && ids.isNotEmpty())
            val info = requireNotNull(metadata)
            require(info.integer("cityCount") == cities.size.toLong() && info.integer("stationCount") == stations.size.toLong())
            require(info.text("generatorVersion", 100).isNotBlank())
            val source = requireNotNull(info.getAsJsonObject("source"))
            require(source.text("license", 32) == "ODbL-1.0")
            require(source.text("copyrightUrl", 2048) == DefaultPlaceDiscovery.COPYRIGHT_URL)
            require(source.text("licenseUrl", 2048) == "https://opendatacommons.org/licenses/odbl/1-0/")
            require(source.text("attribution", 200).contains("OpenStreetMap"))
            require(source.text("sha256", 64).matches(Regex("[a-f0-9]{64}")))
            require(source.integer("sizeBytes") > 0)
            val uri = URI(source.text("url", 2048))
            require(uri.scheme == "https" && uri.host == "download.geofabrik.de" && uri.userInfo == null && uri.port == -1 &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.matches(Regex("/russia(?:-[0-9]{6}|-latest)?\\.osm\\.pbf")))
            val timestamp = Instant.parse(source.text("dumpTimestamp", 64))
            require(timestamp >= Instant.EPOCH && timestamp <= now.plusSeconds(86_400))
            checkCancelled()
            return LocalPlaceGazetteer(timestamp.toEpochMilli(), cities, stations)
        }

        private fun parseEntry(value: JsonObject, station: Boolean): Entry {
            val id = value.text("id", 64)
            val node = requireNotNull(ID.matchEntire(id)).groupValues[1]
            require(node.toLongOrNull()?.let { it > 0 } == true)
            require(value.text("sourceUrl", 2048) == "https://www.openstreetmap.org/node/$node")
            val point = requireNotNull(value.getAsJsonObject("center"))
            val center = GeoPoint(point.number("latitude"), point.number("longitude")).also { require(it.isValid) }
            val source = requireNotNull(value.getAsJsonObject("sourceTags"))
            require(source.size() <= 100)
            val tags = source.entrySet().associate { (key, _) ->
                require(key.length in 1..100 && key.none(Char::isISOControl)); key to source.text(key, 2048)
            }
            fun tag(key: String, limit: Int = 300) = tags[key].orEmpty().trim().takeIf { it.codePointCount(0, it.length) <= limit }.orEmpty()
            require(INACTIVE.none { prefix ->
                (listOf(prefix) + listOf("place", "railway", "public_transport", "station").map { "$prefix:$it" })
                    .any { tags[it].orEmpty().trim().lowercase(Locale.ROOT) !in FALSE }
            })
            val cityType = tag("place") in setOf("city", "town")
            val stationType = tag("railway") == "station" && tag("station") == "subway"
            require(!(cityType && stationType))
            if (station) require(stationType) else require(cityType)
            val name = value.text("name", 300).also { require(it.isNotBlank() && it == (tag("name:ru").ifBlank { tag("name") })) }
            val sourceAliases = NAME_KEYS.flatMap { tag(it, 2048).split(';') }.map(String::trim)
                .filter { it.isNotBlank() && it.codePointCount(0, it.length) <= 300 && it != name }.distinct()
            val aliases = requireNotNull(value.getAsJsonArray("aliases")).also { require(it.size() <= 12 * 1024) }.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { alias -> require(alias.codePointCount(0, alias.length) in 1..300 && alias.none(Char::isISOControl)) }
            }
            require(aliases.sumOf { it.codePointCount(0, it.length) } <= 12 * 2048 && aliases == sourceAliases)
            fun checked(field: String, expected: String, limit: Int = 300) = value.text(field, limit).also { require(it == expected) }
            return Entry(id, name, aliases, center,
                checked("city", tag("addr:city")),
                checked("region", tag("addr:region:ru").ifBlank { tag("addr:region") }.ifBlank { tag("addr:state") }),
                checked("country", tag("addr:country")),
                checked("network", tag("network:ru").ifBlank { tag("network") }),
                checked("contextLabel", listOf(tag("addr:district"), tag("is_in")).filter(String::isNotBlank).distinct().joinToString(" · "), 600),
                tag("colour"))
        }

        // JsonParser.parseReader temporarily enables lenient mode. Read the small per-record
        // tree ourselves so malformed syntax and duplicate fields fail before becoming data.
        private class TreeBudget {
            private var values = 0
            private var characters = 0
            fun value() { require(++values <= 25_000) }
            fun text(value: String): String {
                characters += value.length
                require(characters <= 512 * 1024)
                return value
            }
        }

        private fun strictValue(reader: JsonReader, budget: TreeBudget, depth: Int = 0): JsonElement {
            require(depth <= 8)
            budget.value()
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        require(result.size() < 100)
                        val key = budget.text(reader.nextName())
                        require(key.length in 1..100 && !result.has(key))
                        result.add(key, strictValue(reader, budget, depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(result.size() < 12 * 1024)
                        result.add(strictValue(reader, budget, depth + 1))
                    }
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(budget.text(reader.nextString()).also { require(it.length <= 4096) })
                JsonToken.NUMBER -> JsonPrimitive(budget.text(reader.nextString()).also { require(it.length <= 64) }.toBigDecimal())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> throw IllegalArgumentException("Unexpected place gazetteer value")
            }
        }

        private fun JsonObject.text(key: String, limit: Int): String {
            val element = get(key)
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isString)
            return element.asString.also { require(it.codePointCount(0, it.length) <= limit && it.none(Char::isISOControl)) }
        }
        private fun JsonObject.number(key: String): Double {
            val element = get(key)
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isNumber)
            return element.asDouble.also { require(it.isFinite()) }
        }
        private fun JsonObject.integer(key: String): Long {
            val element = get(key)
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isNumber && element.asString.matches(Regex("[0-9]{1,19}")))
            return requireNotNull(element.asString.toLongOrNull())
        }
    }
}

private object PlaceWords {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val countryCodes = Locale.getISOCountries().toSet()
    val cityMarkers = setOf("г", "город", "города", "городе", "село", "поселок", "поселка", "пгт")
    fun words(text: String): List<String> = words.findAll(text.lowercase(Locale.ROOT).replace('ё', 'е')).map { it.value }.toList()
    fun normalized(text: String) = words(text).joinToString(" ")
    // A display alias translates an explicit source code; it never fills a missing country.
    fun countryName(country: String): String = country.uppercase(Locale.ROOT).takeIf { it in countryCodes }
        ?.let { Locale("", it).getDisplayCountry(Locale.forLanguageTag("ru")) }.orEmpty()
    fun prefix(value: String, query: String) = value == query || query.length >= 2 && value.startsWith(query)
    fun containsPhrase(haystack: List<String>, phrase: List<String>) = phrase.isNotEmpty() &&
        haystack.windowed(phrase.size).any { it == phrase }
}

private class GazetteerLimitedReader(input: Reader, private val limit: Int, private val checkCancelled: () -> Unit) : FilterReader(input) {
    private var consumed = 0
    override fun read(): Int = super.read().also { if (it != -1) add(1) }
    override fun read(buffer: CharArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) add(it) }
    private fun add(count: Int) {
        consumed += count
        if (consumed > limit) throw IOException("Place gazetteer exceeds size limit")
        checkCancelled()
    }
}
