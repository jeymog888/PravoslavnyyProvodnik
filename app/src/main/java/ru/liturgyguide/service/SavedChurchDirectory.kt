package ru.liturgyguide.service

import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.liturgyguide.model.Parish
import java.net.URI
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.*

enum class SavedChurchOrigin { USER, OPEN_STREET_MAP }

/** A personal entry is not a verified parish schedule. Coordinates are saved only by an explicit action. */
data class SavedChurchRecord(
    val id: String,
    val name: String,
    val city: String,
    val address: String,
    val timezone: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val origin: SavedChurchOrigin = SavedChurchOrigin.USER,
    val website: String = "",
    val sourceUrl: String = ""
) {
    fun asParish(): Parish = Parish(
        id = id, name = name, shortName = name, city = city, address = address,
        phone = "", website = website, timezone = timezone, sourceUrl = sourceUrl,
        scheduleSource = if (origin == SavedChurchOrigin.USER) "Храм добавлен вами" else "Данные OpenStreetMap",
        scheduleValidFrom = "", scheduleValidUntil = "", schedule = emptyList(),
        scheduleCoverage = "Расписание этого храма пока не добавлено",
        scheduleNote = "", latitude = latitude, longitude = longitude
    )

    companion object {
        fun create(name: String, city: String, address: String, timezone: String,
            latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float? = null): SavedChurchRecord =
            SavedChurchCodec.validated(SavedChurchRecord("custom:${UUID.randomUUID()}", name, city, address,
                timezone, latitude, longitude, accuracyMeters))
                ?: throw IllegalArgumentException("Проверьте название, город и часовой пояс")
    }
}

/** Nullable DTO fields are intentional: Gson can bypass Kotlin's non-null constructor guarantees. */
private data class SavedChurchDto(
    val id: String?, val name: String?, val city: String?, val address: String?, val timezone: String?,
    val latitude: Double?, val longitude: Double?, val locationAccuracyMeters: Float?,
    val origin: String?, val website: String?, val sourceUrl: String?
)

object SavedChurchCodec {
    const val MAX_NAME_LENGTH = 300
    const val MAX_CITY_LENGTH = 200
    const val MAX_ADDRESS_LENGTH = 512
    const val MAX_RECORDS = 100
    const val MAX_JSON_CHARS = 262_144
    private val gson = Gson()

    fun validated(record: SavedChurchRecord): SavedChurchRecord? = runCatching {
        val clean = record.copy(name = record.name.trim(), city = record.city.trim(), address = record.address.trim(),
            timezone = record.timezone.trim(), website = record.website.trim(), sourceUrl = record.sourceUrl.trim())
        require(clean.name.length in 1..MAX_NAME_LENGTH && clean.city.length <= MAX_CITY_LENGTH && clean.address.length <= MAX_ADDRESS_LENGTH)
        require(clean.origin != SavedChurchOrigin.USER || clean.city.isNotBlank())
        require(listOf(clean.name, clean.city, clean.address).none { value -> value.any(Char::isISOControl) })
        ZoneId.of(clean.timezone)
        require((clean.latitude == null) == (clean.longitude == null))
        clean.latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
        clean.longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
        clean.locationAccuracyMeters?.let { require(clean.latitude != null && it.isFinite() && it > 0f && it <= 10_000f) }
        require(clean.website.isEmpty() || isSafeWebsite(clean.website))
        when (clean.origin) {
            SavedChurchOrigin.USER -> {
                require(clean.id.startsWith("custom:"))
                require(UUID.fromString(clean.id.removePrefix("custom:")).toString() == clean.id.removePrefix("custom:"))
                require(clean.sourceUrl.isEmpty())
            }
            SavedChurchOrigin.OPEN_STREET_MAP -> {
                require(Regex("osm:(node|way|relation):[1-9][0-9]{0,18}").matches(clean.id))
                val parts = clean.id.split(':')
                require(clean.sourceUrl == "https://www.openstreetmap.org/${parts[1]}/${parts[2]}")
                require(clean.latitude != null)
            }
        }
        clean
    }.getOrNull()

    fun decode(json: String?): List<SavedChurchRecord> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS) return emptyList()
        val entries = runCatching { JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray }.getOrNull()
            ?: return emptyList()
        return entries.take(MAX_RECORDS).mapNotNull { element ->
            runCatching {
                val dto = gson.fromJson(element, SavedChurchDto::class.java) ?: return@runCatching null
                val record = SavedChurchRecord(
                    id = dto.id ?: return@runCatching null,
                    name = dto.name ?: return@runCatching null,
                    city = dto.city ?: return@runCatching null,
                    address = dto.address.orEmpty(),
                    timezone = dto.timezone ?: return@runCatching null,
                    latitude = dto.latitude, longitude = dto.longitude, locationAccuracyMeters = dto.locationAccuracyMeters,
                    origin = SavedChurchOrigin.valueOf(dto.origin ?: return@runCatching null),
                    website = dto.website.orEmpty(), sourceUrl = dto.sourceUrl.orEmpty()
                )
                validated(record)
            }.getOrNull()
        }.distinctBy { it.id }
    }

    fun encode(records: List<SavedChurchRecord>): String {
        val valid = records.mapNotNull(::validated).distinctBy { it.id }
        require(valid.size <= MAX_RECORDS) { "The personal directory is full" }
        val encoded = gson.toJson(valid)
        require(encoded.length <= MAX_JSON_CHARS) { "The personal directory is too large" }
        return encoded
    }

    fun isSafeWebsite(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && value.length <= 2048
    }.getOrDefault(false)
}

object ChurchDirectory {
    fun merge(bundled: List<Parish>, saved: List<SavedChurchRecord>): List<Parish> =
        (bundled + saved.mapNotNull(SavedChurchCodec::validated).map(SavedChurchRecord::asParish)).distinctBy { it.id }

    fun search(churches: List<Parish>, query: String, favorites: Set<String> = emptySet()): List<Parish> {
        val tokens = normalized(query).split(Regex("\\s+")).filter(String::isNotBlank)
        return churches.filter { church ->
            val haystack = normalized("${church.name} ${church.shortName} ${church.city} ${church.address}")
            tokens.all(haystack::contains)
        }.sortedWith(compareByDescending<Parish> { it.id in favorites }.thenBy { normalized(it.city) }.thenBy { normalized(it.name) })
    }

    /** Approximate location ranks a small list; it never identifies the parish as a fact. */
    fun nearby(churches: List<Parish>, latitude: Double, longitude: Double, accuracyMeters: Float): List<NearbyChurch> {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters <= 0f || accuracyMeters > 10_000f) return emptyList()
        val radius = maxOf(3_000.0, accuracyMeters.toDouble()).coerceAtMost(10_000.0)
        return churches.mapNotNull { church ->
            val lat = church.latitude ?: return@mapNotNull null
            val lon = church.longitude ?: return@mapNotNull null
            if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@mapNotNull null
            val distance = distanceMeters(latitude, longitude, lat, lon)
            if (distance <= radius) NearbyChurch(church, distance) else null
        }.sortedBy { it.distanceMeters }.take(5)
    }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е').trim()
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

data class NearbyChurch(val church: Parish, val distanceMeters: Double)
