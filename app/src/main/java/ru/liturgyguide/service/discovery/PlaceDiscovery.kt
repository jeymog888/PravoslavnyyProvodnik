package ru.liturgyguide.service.discovery

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale
import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean get() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** The query discloses a small area, not the precise phone position. */
    fun searchAreaCenter(): GeoPoint = GeoPoint(round(latitude * 100) / 100, round(longitude * 100) / 100)

    fun distanceMeters(other: GeoPoint): Double {
        if (!isValid || !other.isValid) return Double.POSITIVE_INFINITY
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
    }
}

data class DiscoveryCity(
    val id: String,
    val name: String,
    val region: String,
    val country: String,
    val center: GeoPoint
)

data class DiscoveredChurch(
    val id: String,
    val name: String,
    val center: GeoPoint,
    val address: String,
    val city: String,
    val website: String?,
    val isChapel: Boolean,
    val sourceUrl: String,
    val kind: DiscoveredPlaceKind = if (isChapel) DiscoveredPlaceKind.CHAPEL else DiscoveredPlaceKind.WORSHIP_PLACE,
    val region: String = "",
    val country: String = ""
)

/** A map category is not a denomination or a published service schedule. */
enum class DiscoveredPlaceKind { CHURCH, CHAPEL, MONASTERY, WORSHIP_PLACE }

enum class DiscoveryCacheStatus { FRESH_NETWORK, FRESH_CACHE, STALE_CACHE }
enum class DiscoveryFailure { INVALID_QUERY, UNAVAILABLE, SERVER_UNAVAILABLE, TIMEOUT, TOO_LARGE, INVALID_RESPONSE, RATE_LIMITED, BUSY }
enum class DiscoveryProvider { PHOTON, VK_MAPS }

sealed interface DiscoveryResult<out T> {
    data class Success<T>(
        val items: List<T>,
        val fetchedAtEpochMillis: Long,
        val cacheStatus: DiscoveryCacheStatus,
        val provider: DiscoveryProvider,
        val queryCenter: GeoPoint? = null,
        val radiusMeters: Int? = null,
        /** Non-null only when a failed network request fell back to old cached data. */
        val fallbackReason: DiscoveryFailure? = null
    ) : DiscoveryResult<T>

    data class Failure(val reason: DiscoveryFailure) : DiscoveryResult<Nothing>
}

interface PlaceDiscovery {
    suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch>
    suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity>
    suspend fun findChurches(center: GeoPoint, radiusMeters: Int = 3_000): DiscoveryResult<DiscoveredChurch>
}

/** Fixed slots are used by the client; the store must not turn query keys into location history. */
interface DiscoveryCacheStore {
    fun read(slot: String): String?
    fun write(slot: String, value: String)
}

object NoDiscoveryCache : DiscoveryCacheStore {
    override fun read(slot: String): String? = null
    override fun write(slot: String, value: String) = Unit
}

data class DiscoveryEndpoints(
    val cities: String = "https://photon.komoot.io/api/",
    val churches: String = "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
) {
    init {
        listOf(cities, churches).forEach { endpoint ->
            val uri = URI(endpoint)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null)
        }
    }
}

/** Explicit searches only. Nearby search has at most one fallback, with a separate provider and parser. */
class DefaultPlaceDiscovery(
    private val transport: DiscoveryTransport = UrlConnectionDiscoveryTransport(),
    private val cache: DiscoveryCacheStore = NoDiscoveryCache,
    private val endpoints: DiscoveryEndpoints = DiscoveryEndpoints(),
    private val clock: Clock = Clock.systemUTC()
) : PlaceDiscovery {
    override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> = withContext(Dispatchers.IO) {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length !in 2..MAX_QUERY_LENGTH || cleaned.any { it.isISOControl() }) {
            return@withContext DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        }
        val url = endpoints.cities + "?q=" + encode(cleaned) + "&limit=$MAX_PLACES&lang=default" +
            "&include=osm.amenity.place_of_worship,osm.amenity.monastery"
        search("places", url, DiscoveryRequest(url), DiscoveryProvider.PHOTON, parser = DiscoveryJson::places)
    }

    override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = withContext(Dispatchers.IO) {
        val cleaned = query.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length !in 2..MAX_QUERY_LENGTH || cleaned.any { it.isISOControl() }) {
            return@withContext DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        }
        // The public instance supports default/de/en/fr, not ru. Default keeps local OSM names.
        val url = endpoints.cities + "?q=" + encode(cleaned) + "&layer=city&limit=$MAX_CITIES&lang=default"
        search("cities", url, DiscoveryRequest(url), DiscoveryProvider.PHOTON, parser = DiscoveryJson::cities)
    }

    override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = withContext(Dispatchers.IO) {
        if (!center.isValid || radiusMeters !in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            return@withContext DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY)
        }
        val area = center.searchAreaCenter()
        // Fixed padding covers every point in the rounded cell without disclosing the exact offset.
        val queryRadius = radiusMeters + ROUNDING_PADDING_METERS
        val query = churchQuery(area, queryRadius)
        val key = endpoints.churches + query
        val nearbyUrl = nearbyPlacesUrl(endpoints.cities, area, queryRadius)
        val now = clock.millis()
        val parseOrthodox: (String) -> List<DiscoveredChurch> = { DiscoveryJson.churches(it, area, queryRadius) }
        // The one fixed slot stores only one area. The full endpoint+query key binds data to its parser/provider.
        val orthodoxCache = readCache("churches", sha256(key), now, parseOrthodox)
        val photonCache = readCache("churches", sha256(nearbyUrl), now, DiscoveryJson::places)
        fun local(result: DiscoveryResult.Success<DiscoveredChurch>) = result.copy(
            items = result.items.filter { center.distanceMeters(it.center) <= radiusMeters }
                .sortedBy { center.distanceMeters(it.center) })
        for ((cached, provider) in listOf(orthodoxCache to DiscoveryProvider.VK_MAPS, photonCache to DiscoveryProvider.PHOTON)) {
            if (cached != null && now - cached.fetchedAt <= CACHE_FRESH_MILLIS) {
                return@withContext local(DiscoveryResult.Success(cached.items, cached.fetchedAt,
                    DiscoveryCacheStatus.FRESH_CACHE, provider, area, radiusMeters))
            }
        }
        val request = DiscoveryRequest(endpoints.churches, postBody = "data=" + encode(query), timeoutMillis = NEARBY_PRIMARY_TIMEOUT_MILLIS)
        val primary = search("churches", key, request, DiscoveryProvider.VK_MAPS, area, radiusMeters, parser = parseOrthodox)
        val failure = when (primary) {
            is DiscoveryResult.Failure -> primary.reason
            is DiscoveryResult.Success -> primary.fallbackReason
        }
        // An empty valid answer is still authoritative. Never bypass a rate limit or start another request after cancellation/busy.
        if (failure !in NEARBY_FALLBACK_FAILURES) return@withContext when (primary) {
            is DiscoveryResult.Success -> local(primary)
            is DiscoveryResult.Failure -> primary
        }
        currentCoroutineContext().ensureActive()
        val usablePrimary = primary is DiscoveryResult.Success && local(primary).items.isNotEmpty()
        val fallback = search("churches", nearbyUrl,
            DiscoveryRequest(nearbyUrl, timeoutMillis = NEARBY_FALLBACK_TIMEOUT_MILLIS),
            DiscoveryProvider.PHOTON, area, radiusMeters,
            cacheIf = { items -> !usablePrimary || items.any { center.distanceMeters(it.center) <= radiusMeters } },
            parser = DiscoveryJson::places)
        // Photon is name/category search, not proof of denomination. Preserve its WORSHIP_PLACE kind and OSM identity.
        // A partial name search finding nothing must not erase useful saved Orthodox places.
        val result = if (primary is DiscoveryResult.Success && (fallback is DiscoveryResult.Failure ||
                (usablePrimary && fallback is DiscoveryResult.Success && local(fallback).items.isEmpty()))) primary else fallback
        when (result) {
            is DiscoveryResult.Success -> local(result)
            is DiscoveryResult.Failure -> result
        }
    }

    private suspend fun <T> search(
        slot: String,
        identity: String,
        request: DiscoveryRequest,
        provider: DiscoveryProvider,
        area: GeoPoint? = null,
        radius: Int? = null,
        cacheIf: (List<T>) -> Boolean = { true },
        parser: (String) -> List<T>
    ): DiscoveryResult<T> {
        val key = sha256(identity)
        val now = clock.millis()
        val cached = readCache(slot, key, now, parser)
        if (cached != null && now - cached.fetchedAt <= CACHE_FRESH_MILLIS) {
            return DiscoveryResult.Success(cached.items, cached.fetchedAt, DiscoveryCacheStatus.FRESH_CACHE, provider, area, radius)
        }
        return try {
            val body = transport.fetch(request)
            currentCoroutineContext().ensureActive()
            if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE_BYTES) throw DiscoveryException(DiscoveryFailure.TOO_LARGE)
            val items = parser(body)
            currentCoroutineContext().ensureActive()
            val fetchedAt = clock.millis()
            if (cacheIf(items)) writeCache(slot, key, fetchedAt, body)
            DiscoveryResult.Success(items, fetchedAt, DiscoveryCacheStatus.FRESH_NETWORK, provider, area, radius)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DiscoveryException) {
            if (cached != null) DiscoveryResult.Success(cached.items, cached.fetchedAt,
                DiscoveryCacheStatus.STALE_CACHE, provider, area, radius, failure.reason)
            else DiscoveryResult.Failure(failure.reason)
        } catch (_: Exception) {
            if (cached != null) DiscoveryResult.Success(cached.items, cached.fetchedAt,
                DiscoveryCacheStatus.STALE_CACHE, provider, area, radius, DiscoveryFailure.UNAVAILABLE)
            else DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
    }

    private data class Cached<T>(val fetchedAt: Long, val items: List<T>)
    private fun <T> readCache(slot: String, key: String, now: Long, parser: (String) -> List<T>): Cached<T>? = runCatching {
        val raw = cache.read(slot) ?: return null
        // A JSON string may escape the response, so its envelope can be larger than the response itself.
        if (raw.length > MAX_RESPONSE_BYTES * 2) return null
        val value = JsonParser.parseString(raw).asJsonObject
        if (value["version"]?.asInt != 1 || value["key"]?.asString != key) return null
        val fetchedAt = value["fetchedAt"]?.asLong ?: return null
        if (fetchedAt < 0 || now < fetchedAt || now - fetchedAt > CACHE_MAX_AGE_MILLIS) return null
        val body = value["body"]?.asString ?: return null
        if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE_BYTES) return null
        Cached(fetchedAt, parser(body))
    }.getOrNull()

    private fun writeCache(slot: String, key: String, fetchedAt: Long, body: String) {
        val json = JsonObject().apply {
            addProperty("version", 1)
            addProperty("key", key)
            addProperty("fetchedAt", fetchedAt)
            addProperty("body", body)
        }
        // Full storage or a corrupt store must not turn a successful search into an error.
        runCatching { cache.write(slot, json.toString()) }
    }

    companion object {
        const val MAX_QUERY_LENGTH = 120
        const val MAX_CITIES = 5
        const val MAX_PLACES = 10
        const val MAX_CHURCHES = 60
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MIN_RADIUS_METERS = 500
        const val MAX_RADIUS_METERS = 5_000
        internal const val ROUNDING_PADDING_METERS = 800
        internal const val NEARBY_PRIMARY_TIMEOUT_MILLIS = 8_000L
        internal const val NEARBY_FALLBACK_TIMEOUT_MILLIS = 10_000L
        private val NEARBY_FALLBACK_FAILURES = setOf(DiscoveryFailure.UNAVAILABLE, DiscoveryFailure.SERVER_UNAVAILABLE,
            DiscoveryFailure.TIMEOUT, DiscoveryFailure.TOO_LARGE, DiscoveryFailure.INVALID_RESPONSE)
        const val CACHE_FRESH_MILLIS = 24 * 60 * 60 * 1_000L
        const val CACHE_MAX_AGE_MILLIS = 7 * CACHE_FRESH_MILLIS
        const val ATTRIBUTION = "Данные OpenStreetMap, могут быть неполными"
        const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun churchQuery(center: GeoPoint, radiusMeters: Int): String {
            require(center.isValid && radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS + ROUNDING_PADDING_METERS)
            val lat = String.format(Locale.ROOT, "%.2f", center.latitude)
            val lon = String.format(Locale.ROOT, "%.2f", center.longitude)
            val denominations = DiscoveryJson.DENOMINATIONS.joinToString("|")
            return """[out:json][timeout:6][maxsize:8388608];
                |nwr(around:$radiusMeters,$lat,$lon)["amenity"~"^(place_of_worship|monastery)${'$'}"]["religion"="christian"]
                |["denomination"~"(^|;)($denominations)(;|${'$'})"];
                |out center tags $MAX_CHURCHES;""".trimMargin()
        }

        internal fun nearbyPlacesUrl(endpoint: String, area: GeoPoint, queryRadius: Int): String {
            require(area.isValid && queryRadius in MIN_RADIUS_METERS..MAX_RADIUS_METERS + ROUNDING_PADDING_METERS)
            val angularRadius = queryRadius / 6_371_000.0
            val latitudeDelta = Math.toDegrees(angularRadius)
            val minLat = max(-90.0, area.latitude - latitudeDelta)
            val maxLat = min(90.0, area.latitude + latitudeDelta)
            val longitudeDelta = if (minLat <= -90.0 || maxLat >= 90.0) 180.0 else
                Math.toDegrees(asin((sin(angularRadius) / cos(Math.toRadians(area.latitude))).coerceIn(-1.0, 1.0)))
            // Photon bbox cannot wrap at the date line. The bounded response is still filtered locally by radius.
            val wraps = area.longitude - longitudeDelta < -180.0 || area.longitude + longitudeDelta > 180.0
            val minLon = if (wraps) -180.0 else area.longitude - longitudeDelta
            val maxLon = if (wraps) 180.0 else area.longitude + longitudeDelta
            val bbox = listOf(minLon, minLat, maxLon, maxLat).joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }
            return endpoint + "?q=" + encode("храм") + "&lat=" + String.format(Locale.ROOT, "%.2f", area.latitude) +
                "&lon=" + String.format(Locale.ROOT, "%.2f", area.longitude) + "&bbox=" + encode(bbox) +
                "&limit=$MAX_PLACES&lang=default&include=osm.amenity.place_of_worship,osm.amenity.monastery"
        }
    }
}

/** Responses are untrusted data. Unknown fields (including service_times) never become a schedule. */
internal object DiscoveryJson {
    val DENOMINATIONS = listOf("orthodox", "russian_orthodox", "greek_orthodox", "serbian_orthodox", "georgian_orthodox")
    fun places(body: String): List<DiscoveredChurch> = parse {
        val root = JsonParser.parseString(body).asJsonObject
        require(root.string("type") == "FeatureCollection")
        val features = root.getAsJsonArray("features") ?: error("Missing features")
        features.take(DefaultPlaceDiscovery.MAX_PLACES).mapNotNull { element -> runCatching {
            val feature = element.asJsonObject
            if (feature.string("type") != "Feature") return@runCatching null
            val props = feature.getAsJsonObject("properties") ?: return@runCatching null
            if (props.string("osm_key") != "amenity") return@runCatching null
            val kind = when (props.string("osm_value")) {
                "monastery" -> DiscoveredPlaceKind.MONASTERY
                "place_of_worship" -> DiscoveredPlaceKind.WORSHIP_PLACE
                else -> return@runCatching null
            }
            val geometry = feature.getAsJsonObject("geometry") ?: return@runCatching null
            if (geometry.string("type") != "Point") return@runCatching null
            val coordinates = geometry.getAsJsonArray("coordinates") ?: return@runCatching null
            if (coordinates.size() != 2 || coordinates.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) return@runCatching null
            val center = GeoPoint(coordinates[1].asDouble, coordinates[0].asDouble)
            if (!center.isValid) return@runCatching null
            val name = props.string("name", 300).takeIf { it.isNotBlank() } ?: return@runCatching null
            val type = when (props.string("osm_type")) { "N" -> "node"; "W" -> "way"; "R" -> "relation"; else -> return@runCatching null }
            val id = props.positiveId() ?: return@runCatching null
            val address = listOf(props.string("street", 200), props.string("housenumber", 30)).filter(String::isNotBlank).joinToString(", ")
            DiscoveredChurch("osm:$type:$id", name, center, address, props.string("city", 200),
                website = null, isChapel = false, sourceUrl = "https://www.openstreetmap.org/$type/$id", kind = kind,
                region = listOf(props.string("state", 200), props.string("county", 200)).filter(String::isNotBlank).distinct().joinToString(", "),
                country = props.string("country", 200))
        }.getOrNull() }.distinctBy { it.id }
    }

    fun cities(body: String): List<DiscoveryCity> = parse {
        val root = JsonParser.parseString(body).asJsonObject
        require(root.string("type") == "FeatureCollection")
        val features = root.getAsJsonArray("features") ?: error("Missing features")
        features.take(DefaultPlaceDiscovery.MAX_CITIES).mapNotNull { element -> runCatching {
            val feature = element.asJsonObject
            val props = feature.getAsJsonObject("properties") ?: return@runCatching null
            val geometry = feature.getAsJsonObject("geometry") ?: return@runCatching null
            if (geometry.string("type") != "Point" || props.string("type") != "city") return@runCatching null
            val coordinates = geometry.getAsJsonArray("coordinates") ?: return@runCatching null
            if (coordinates.size() != 2) return@runCatching null
            val center = GeoPoint(coordinates[1].asDouble, coordinates[0].asDouble)
            if (!center.isValid) return@runCatching null
            val name = props.string("name", 200).takeIf { it.isNotBlank() } ?: return@runCatching null
            val type = when (props.string("osm_type")) { "N" -> "node"; "W" -> "way"; "R" -> "relation"; else -> return@runCatching null }
            val id = props.positiveId() ?: return@runCatching null
            DiscoveryCity("osm:$type:$id", name, listOf(props.string("state", 200), props.string("county", 200))
                .filter { it.isNotBlank() }.distinct().joinToString(", "), props.string("country", 200), center)
        }.getOrNull() }.distinctBy { it.id }
    }

    fun churches(body: String, center: GeoPoint, radius: Int): List<DiscoveredChurch> = parse {
        val root = JsonParser.parseString(body).asJsonObject
        // Overpass may return a truncated 'successful' JSON response with a timeout remark.
        val remark = root["remark"]
        require(remark == null || remark.isJsonNull ||
            (remark.isJsonPrimitive && remark.asJsonPrimitive.isString && remark.asString.isBlank()))
        val elements = root.getAsJsonArray("elements") ?: error("Missing elements")
        elements.take(DefaultPlaceDiscovery.MAX_CHURCHES).mapNotNull { element -> runCatching {
            val value = element.asJsonObject
            val type = value.string("type").takeIf { it in setOf("node", "way", "relation") } ?: return@runCatching null
            val id = value.positiveId("id") ?: return@runCatching null
            val tags = value.getAsJsonObject("tags") ?: return@runCatching null
            if (tags.string("amenity") !in setOf("place_of_worship", "monastery") || tags.string("religion") != "christian" ||
                tags.string("denomination").split(';').map(String::trim).none { it in DENOMINATIONS } ||
                tags.string("tower:type") == "bell_tower") return@runCatching null
            val name = tags.string("name:ru", 300).ifBlank { tags.string("name", 300) }.takeIf { it.isNotBlank() } ?: return@runCatching null
            val point = if (type == "node") value else value.getAsJsonObject("center") ?: return@runCatching null
            val location = GeoPoint(point["lat"].asDouble, point["lon"].asDouble)
            if (!location.isValid || center.distanceMeters(location) > radius + 50.0) return@runCatching null
            val city = tags.string("addr:city", 200)
            val street = listOf(tags.string("addr:street", 200), tags.string("addr:housenumber", 30)).filter { it.isNotBlank() }.joinToString(", ")
            val address = listOf(city, street).filter { it.isNotBlank() }.joinToString(", ")
            val website = listOf(tags.string("website", 2048), tags.string("contact:website", 2048)).firstNotNullOfOrNull(::safeWebsite)
            val chapel = tags.string("building") == "chapel" || tags.string("place_of_worship") == "chapel" || name.startsWith("часовня", ignoreCase = true)
            DiscoveredChurch("osm:$type:$id", name, location, address, city, website, chapel,
                "https://www.openstreetmap.org/$type/$id", kind = when {
                    tags.string("amenity") == "monastery" -> DiscoveredPlaceKind.MONASTERY
                    chapel -> DiscoveredPlaceKind.CHAPEL
                    else -> DiscoveredPlaceKind.CHURCH
                })
        }.getOrNull() }.distinctBy { it.id }.sortedBy { center.distanceMeters(it.center) }
    }

    private fun safeWebsite(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && !value.any { it.isISOControl() }) value else null
    }.getOrNull()

    private fun JsonObject.string(key: String, maxLength: Int = 512): String = runCatching {
        val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty().trim()
        if (value.length > maxLength || value.any { it.isISOControl() && it != '\n' }) "" else value
    }.getOrDefault("")

    private fun JsonObject.positiveId(key: String = "osm_id"): Long? = runCatching {
        val raw = get(key)?.asString ?: return null
        if (!raw.matches(Regex("[1-9][0-9]{0,18}"))) return null
        raw.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private inline fun <T> parse(block: () -> T): T = try { block() }
    catch (failure: DiscoveryException) { throw failure }
    catch (_: Exception) { throw DiscoveryException(DiscoveryFailure.INVALID_RESPONSE) }
}
