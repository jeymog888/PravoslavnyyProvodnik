package ru.liturgyguide.service.discovery

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.liturgyguide.service.schedule.ScheduleUrl
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale

/** The bundled map snapshot is separate from live parish schedules and from query history. */
class BundledChurchCatalog(private val open: () -> Reader, private val clock: Clock = Clock.systemUTC()) {
    private val lock = Mutex()
    private var loaded = false
    private var catalog: LocalChurchCatalog? = null

    internal suspend fun get(): LocalChurchCatalog? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                val context = currentCoroutineContext()
                catalog = try {
                    open().use { LocalChurchCatalog.decode(it, clock.instant()) { context.ensureActive() } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                // A missing/corrupt packaged asset cannot become valid until the application is updated.
                // Cancellation, in contrast, must allow the next search to load it again.
                loaded = true
            }
            catalog
        }
    }

    companion object { const val ASSET_PATH = "discovery/churches-ru.json" }
}

/** Successful local searches never contact a public map server. Empty results make no coverage claim. */
class CatalogPlaceDiscovery(private val local: BundledChurchCatalog, private val network: PlaceDiscovery) : PlaceDiscovery {
    override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = network.searchCities(query)

    override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (!validQuery(cleaned)) return DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        // Resolving a metro station is a different operation. Never drop its name and return
        // a same-named church in another city as an offline substitute.
        if (LandmarkSearch.parse(cleaned) != null) return network.searchPlaces(cleaned)
        val found = withContext(Dispatchers.IO) {
            val catalog = local.get() ?: return@withContext null
            val context = currentCoroutineContext()
            catalog.search(cleaned) { context.ensureActive() }.takeIf { it.isNotEmpty() }
                ?.let { catalog.result(it) }
        }
        return found ?: network.searchPlaces(cleaned)
    }

    override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
        if (!center.isValid || radiusMeters !in DefaultPlaceDiscovery.MIN_RADIUS_METERS..DefaultPlaceDiscovery.MAX_RADIUS_METERS)
            return DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        return nearby(center, radiusMeters) ?: network.findChurches(center, radiusMeters)
    }

    override suspend fun findChurchesNearLandmark(landmark: DiscoveryLandmark, churchQuery: String): DiscoveryResult<DiscoveredChurch> {
        if (!landmark.center.isValid) return DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        val result = nearby(landmark.center, DefaultPlaceDiscovery.LANDMARK_RADIUS_METERS)
            ?: return network.findChurchesNearLandmark(landmark, churchQuery)
        return result.copy(items = LandmarkSearch.rank(result.items, churchQuery, landmark.center),
            landmark = landmark, landmarkChurchQuery = churchQuery)
    }

    private suspend fun nearby(center: GeoPoint, radiusMeters: Int): DiscoveryResult.Success<DiscoveredChurch>? = withContext(Dispatchers.IO) {
        val catalog = local.get() ?: return@withContext null
        val context = currentCoroutineContext()
        catalog.nearby(center, radiusMeters) { context.ensureActive() }.takeIf { it.isNotEmpty() }
            ?.let { catalog.result(it, center.searchAreaCenter(), radiusMeters) }
    }

    private fun validQuery(value: String) = value.length in 2..DefaultPlaceDiscovery.MAX_QUERY_LENGTH && value.none(Char::isISOControl)
}

internal class LocalChurchCatalog private constructor(val fetchedAt: Long, entries: List<Entry>) {
    internal data class Entry(val church: DiscoveredChurch, val aliases: List<String>) {
        val names = (listOf(church.name) + aliases).map(CatalogWords::normalized)
        val nameWords = names.flatMap(CatalogWords::words).toSet()
        val locationWords = CatalogWords.words(listOf(church.city, church.address, church.region, church.country).joinToString(" ")).toSet()
        val cityWords = CatalogWords.words(church.city).filterNot { it in CatalogWords.cityMarkers }
        val allWords = nameWords + locationWords
        val allStems = allWords.map(CatalogWords::stem).toSet()
        val nameStems = nameWords.map(CatalogWords::stem).toSet()
    }
    private val entries = entries.toList()
    private val namedCities = entries.map { it.cityWords }.filter { it.isNotEmpty() }.distinct()
        .associateWith { city -> city.map(CatalogWords::cityForms) }

    fun nearby(center: GeoPoint, radius: Int, checkCancelled: () -> Unit): List<DiscoveredChurch> = entries.mapIndexedNotNull { index, entry ->
        if (index % 256 == 0) checkCancelled()
        val distance = center.distanceMeters(entry.church.center)
        (entry.church to distance).takeIf { distance <= radius }
    }.sortedWith(compareBy<Pair<DiscoveredChurch, Double>> { it.second }.thenBy { it.first.id })
        // The remote response cap does not apply to an already bounded local snapshot.
        // Keep the whole radius so the screen's name filter can find a church after row 60.
        .map { it.first }

    fun search(query: String, checkCancelled: () -> Unit): List<DiscoveredChurch> {
        val raw = CatalogWords.words(query)
        val wanted = raw.filterNot { it in CatalogWords.generic }.distinct().map { CatalogWords.Token(it) }
        if (wanted.isEmpty()) return emptyList()
        // City cases are narrower than name matching: Сретенский is not Сретенское,
        // and "Никольского храма" does not name the village Никольское.
        val cityConstraints = namedCities.filter { (city, forms) -> raw.windowed(city.size).withIndex().any { (start, words) ->
            words.indices.all { index -> words[index] in forms[index] } &&
                (words == city || city.size > 1 || !CatalogWords.adjectiveCity(city.single()) ||
                    raw.getOrNull(start - 1) in setOf("в", "во", "г", "город", "городе"))
        } }.keys
        // If an explicitly named city is present, a same-named dedication elsewhere is not
        // evidence of that location. Missing addr:city stays missing, rather than being guessed.
        val constrainedCities = cityConstraints.filter { city -> cityConstraints.none { other ->
            other.size > city.size && other.windowed(city.size).any { it == city }
        } }
        val normalized = CatalogWords.normalized(query)
        return entries.mapIndexedNotNull { index, entry ->
            if (index % 256 == 0) checkCancelled()
            if (constrainedCities.any { it != entry.cityWords }) return@mapIndexedNotNull null
            if (!wanted.all { CatalogWords.matches(it, entry.allWords, entry.allStems) }) return@mapIndexedNotNull null
            val score = (if (normalized in entry.names) 1_000 else 0) +
                wanted.count { CatalogWords.matches(it, entry.nameWords, entry.nameStems) } * 10 +
                wanted.count { it.value in entry.nameWords }
            entry.church to score
        }.sortedWith(compareByDescending<Pair<DiscoveredChurch, Int>> { it.second }
            .thenBy { it.first.name }.thenBy { it.first.id })
            .take(DefaultPlaceDiscovery.MAX_CHURCHES).map { it.first }
    }

    fun result(items: List<DiscoveredChurch>, center: GeoPoint? = null, radius: Int? = null) =
        DiscoveryResult.Success(items, fetchedAt, DiscoveryCacheStatus.LOCAL_SNAPSHOT, DiscoveryProvider.LOCAL_CATALOG,
            center, radius, notice = "Данные карты могут быть неполными. Сверьте название и адрес храма")

    companion object {
        internal const val MAX_CHARACTERS = 64 * 1024 * 1024
        internal const val MAX_RECORDS = 120_000
        // The importer reads eight name/alias tags of at most 2048 characters each.
        // Even one-character semicolon-separated aliases cannot exceed this count.
        // Keep every valid alias searchable; do not silently truncate the index to 40.
        internal const val MAX_ALIAS_CHARACTERS = 8 * 2048
        internal const val MAX_ALIASES = 8 * 1024
        private val identity = Regex("osm:(node|way|relation):([1-9][0-9]{0,18})")

        fun decode(input: Reader, now: Instant, checkCancelled: () -> Unit = {}): LocalChurchCatalog {
            var version: Int? = null
            var metadata: JsonObject? = null
            val entries = mutableListOf<Entry>()
            val keys = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            JsonReader(LimitedReader(input, MAX_CHARACTERS)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                while (reader.hasNext()) {
                    checkCancelled()
                    val key = reader.nextName()
                    require(keys.add(key))
                    when (key) {
                        "schemaVersion" -> { require(reader.peek() == JsonToken.NUMBER); version = reader.nextInt() }
                        "metadata" -> metadata = JsonParser.parseReader(reader).asJsonObject
                        "churches" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                require(entries.size < MAX_RECORDS)
                                if (entries.size % 256 == 0) checkCancelled()
                                val entry = parseEntry(JsonParser.parseReader(reader).asJsonObject)
                                require(ids.add(entry.church.id))
                                entries += entry
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            require(version == 1 && "churches" in keys && entries.isNotEmpty())
            val info = requireNotNull(metadata)
            require(info.integer("recordCount") == entries.size.toLong())
            val source = requireNotNull(info.getAsJsonObject("source"))
            require(source.string("license", 32) == "ODbL-1.0")
            require(source.string("copyrightUrl", 2048) == DefaultPlaceDiscovery.COPYRIGHT_URL)
            require(source.string("licenseUrl", 2048) == "https://opendatacommons.org/licenses/odbl/1-0/")
            require(source.string("attribution", 200).contains("OpenStreetMap"))
            require(source.string("sha256", 64).matches(Regex("[a-f0-9]{64}")))
            val origin = URI(source.string("url", 2048))
            require(origin.scheme == "https" && origin.host == "download.geofabrik.de" && origin.userInfo == null && origin.port == -1 &&
                origin.rawQuery == null && origin.rawFragment == null)
            require(origin.path.matches(Regex("/russia(?:-[0-9]{6}|-latest)?\\.osm\\.pbf")))
            require(source.integer("sizeBytes") > 0)
            val timestamp = Instant.parse(source.string("dumpTimestamp", 64))
            require(timestamp >= Instant.EPOCH && timestamp <= now.plusSeconds(86_400))
            return LocalChurchCatalog(timestamp.toEpochMilli(), entries)
        }

        private fun parseEntry(value: JsonObject): Entry {
            val id = value.string("id", 64)
            val match = requireNotNull(identity.matchEntire(id))
            require(match.groupValues[2].toLongOrNull()?.let { it > 0 } == true)
            val source = "https://www.openstreetmap.org/${match.groupValues[1]}/${match.groupValues[2]}"
            require(value.string("sourceUrl", 2048) == source)
            val point = requireNotNull(value.getAsJsonObject("center"))
            val center = GeoPoint(point.number("latitude"), point.number("longitude"))
            require(center.isValid)
            val name = value.string("name", 300).also { require(it.isNotBlank()) }
            val aliases = requireNotNull(value.getAsJsonArray("aliases")).also { require(it.size() <= MAX_ALIASES) }.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { alias -> require(alias.codePointCount(0, alias.length) in 1..300 && alias.none(Char::isISOControl)) }
            }.also { aliases ->
                // Python's importer limits Unicode code points, not UTF-16 code units.
                require(aliases.sumOf { it.codePointCount(0, it.length) } <= MAX_ALIAS_CHARACTERS)
            }.distinct()
            val kind = DiscoveredPlaceKind.valueOf(value.string("kind", 32))
            val chapel = value.get("isChapel").also { require(it?.isJsonPrimitive == true && it.asJsonPrimitive.isBoolean) }.asBoolean
            val tags = requireNotNull(value.getAsJsonObject("sourceTags"))
            require(tags.string("amenity", 32) in setOf("place_of_worship", "monastery"))
            require(tags.string("religion", 32) == "christian" && tags.string("tower:type", 32, optional = true) != "bell_tower")
            val denomination = tags.string("denomination", 512, optional = true)
            val orthodox = denomination.split(';').map(String::trim).any { it in DiscoveryJson.DENOMINATIONS }
            require(orthodox || denomination.isEmpty() && kind == DiscoveredPlaceKind.WORSHIP_PLACE && !chapel)
            if (kind != DiscoveredPlaceKind.WORSHIP_PLACE) require(orthodox)
            if (kind == DiscoveredPlaceKind.MONASTERY) require(tags.string("amenity", 32) == "monastery")
            if (kind == DiscoveredPlaceKind.CHAPEL) require(chapel)
            val rawWebsite = value.get("website")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
            }
            val website = rawWebsite?.let(ScheduleUrl::publicSource)
            return Entry(DiscoveredChurch(id, name, center, value.string("address", 512), value.string("city", 200),
                website, chapel, source, kind, value.string("region", 200), value.string("country", 200)), aliases)
        }

        private fun JsonObject.string(key: String, limit: Int, optional: Boolean = false): String {
            val element = get(key)
            if (optional && (element == null || element.isJsonNull)) return ""
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isString)
            return element.asString.also { require(it.length <= limit && it.none(Char::isISOControl)) }
        }
        private fun JsonObject.number(key: String): Double {
            val element = get(key)
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isNumber)
            return element.asDouble.also { require(it.isFinite()) }
        }
        private fun JsonObject.integer(key: String): Long {
            val element = get(key)
            require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isNumber)
            require(element.asString.matches(Regex("[0-9]{1,19}")))
            return requireNotNull(element.asString.toLongOrNull())
        }
    }
}

private object CatalogWords {
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val cyrillicWord = Regex("[а-я]+")
    data class Token(val value: String) { val stem = CatalogWords.stem(value) }
    val cityMarkers = setOf("г", "город", "города", "городе")
    val generic = setOf("храм", "храма", "церковь", "церкви", "собор", "собора", "монастырь", "монастыря", "часовня", "часовни",
        "найти", "рядом", "около", "возле", "в", "во", "на", "у", "и", "с", "со") + cityMarkers
    fun normalized(value: String): String = words(value).joinToString(" ")
    fun words(value: String): List<String> = wordPattern.findAll(value.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map { it.value }.toList()
    fun matches(query: Token, words: Set<String>, stems: Set<String>): Boolean = query.value in words ||
        query.value.length >= 4 && (query.stem in stems || words.any { it.startsWith(query.value) })
    fun adjectiveCity(value: String) = listOf("ое", "ее", "ий", "ый", "ой", "ая", "яя").any(value::endsWith)
    /** Conservative whole-word cases, never a shared root across different adjective genders. */
    fun cityForms(value: String): Set<String> {
        if (!cyrillicWord.matches(value) || value.length < 3) return setOf(value)
        val ending = when {
            value.endsWith("ое") -> "ое" to listOf("ого", "ому", "ым", "ом")
            value.endsWith("ее") -> "ее" to listOf("его", "ему", "им", "ем")
            value.endsWith("ий") -> "ий" to listOf("его", "ему", "им", "ем", "ого", "ому", "ом")
            value.endsWith("ый") || value.endsWith("ой") -> value.takeLast(2) to listOf("ого", "ому", "ым", "ом")
            value.endsWith("ая") -> "ая" to listOf("ой", "ую", "ою")
            value.endsWith("яя") -> "яя" to listOf("ей", "юю", "ею")
            value.endsWith("а") -> "а" to listOf("ы", "и", "е", "у", "ой", "ою")
            value.endsWith("я") -> "я" to listOf("и", "е", "ю", "ей", "ею")
            value.endsWith("ь") -> "ь" to listOf("и", "ью", "я", "ю", "е", "ем")
            value.last() in "бвгджзклмнпрстфхцчшщ" -> "" to listOf("а", "у", "е", "ом")
            else -> return setOf(value)
        }
        return setOf(value) + ending.second.map { value.dropLast(ending.first.length) + it }
    }
    private val suffixes = listOf("ого", "его", "ому", "ему", "ыми", "ими", "ая", "яя", "ое", "ее", "ые", "ие", "ий", "ый", "ой", "ов", "ев",
        "ам", "ям", "ах", "ях", "ом", "ем", "ую", "юю", "а", "я", "ы", "и", "у", "ю", "е", "о", "ь")
    fun stem(value: String): String {
        val suffix = suffixes.firstOrNull { value.endsWith(it) && value.length - it.length >= 4 }
        return suffix?.let { value.dropLast(it.length) } ?: value
    }
}

private class LimitedReader(input: Reader, private val limit: Int) : FilterReader(input) {
    private var consumed = 0
    override fun read(): Int = super.read().also { if (it != -1) add(1) }
    override fun read(buffer: CharArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) add(it) }
    private fun add(count: Int) { consumed += count; if (consumed > limit) throw IOException("Church catalog exceeds size limit") }
}
