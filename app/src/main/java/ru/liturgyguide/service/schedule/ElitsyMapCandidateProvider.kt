package ru.liturgyguide.service.schedule

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import kotlin.math.cos

/** Public map points identify reviewable sources, never a unique church or a service time. */
class ElitsyMapCandidateProvider(
    private val transport: ScheduleTransport,
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = 25_000,
) : ScheduleCandidateProvider {
    init { require(timeoutMillis in 1..30_000) }

    override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
        val latitude = parish.latitude; val longitude = parish.longitude
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0 ||
            OpenScheduleSiteResolver.nameTokens(parish.name).isEmpty()) return ScheduleCandidateDiscovery()
        val dLat = 300.0 / 111_000.0
        val dLon = dLat / cos(Math.toRadians(latitude)).coerceAtLeast(.1)
        if (longitude - dLon < -180 || longitude + dLon > 180) return ScheduleCandidateDiscovery()
        val url = "$API?lb=${latitude - dLat}&lb=${longitude - dLon}&rt=${latitude + dLat}&rt=${longitude + dLon}&z=15"
        val candidates = mutableListOf<ScheduleSourceCandidate>()
        val observed = mutableListOf<String>()
        var unavailable = false
        try {
            val completed = withTimeoutOrNull(timeoutMillis) {
                val response = get(url, MAX_COLLECTION_BYTES)
                require(response.contentType.substringBefore(';').trim().lowercase() == "application/json")
                val root = strictJson(response.bytes.toString(Charsets.UTF_8)).asJsonObject
                val points = requireNotNull(root.get("points")).also { require(it.isJsonArray) }.asJsonArray
                require(points.size() <= 200)
                val parsed = linkedMapOf<String, Point>()
                val ambiguous = mutableSetOf<String>()
                for ((index, row) in points.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val id = runCatching { row.asJsonObject.get("pk").asString }
                        .getOrNull()?.takeIf { it.matches(ID) }
                    observed += id ?: "unreadable:$index"
                    try {
                        val point = point(row)
                        if (point.id in ambiguous) continue
                        val old = parsed[point.id]
                        if (old != null && old != point) {
                            parsed.remove(point.id); ambiguous += point.id; unavailable = true
                        } else parsed[point.id] = point
                    } catch (_: Exception) { unavailable = true }
                }
                val possible = parsed.values.map { it to OpenScheduleSiteResolver.distanceMeters(
                    latitude, longitude, it.latitude, it.longitude) }
                    .filter { (point, distance) -> distance <= 650 && OpenScheduleSiteResolver.namesMatch(parish.name, point.name) }
                    .sortedWith(compareBy({ it.second }, { it.first.id })).take(2)
                val excluded = ScheduleSourceExclusions(excludedUrls)
                for ((point, _) in possible) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val detailUrl = "$HOME/parish/${point.id}/"
                        val detail = get(detailUrl, MAX_DETAIL_BYTES)
                        // A truncated HTML card must not borrow a site's link from an incomplete profile.
                        require(Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(detail.bytes.toString(Charsets.ISO_8859_1)))
                        val document = requireNotNull(detail.html())
                        val profile = document.select(".profile .pf-left").single()
                        val name = profile.select("h1").single().text().trim()
                        require(name.length in 1..500 && ScheduleEntityIdentity.normalize(name) == ScheduleEntityIdentity.normalize(point.name))
                        val address = profile.select(".place a[href]").filter {
                            ScheduleUrl.publicSource(it.absUrl("href")) == "$HOME/map/${point.id}/"
                        }.single().text().trim().also { require(it.length in 1..500 && it.none(Char::isISOControl)) }
                        val sources = profile.select("a[href]").filter { clean(it.text()) in SITE_LABELS }
                            .mapNotNull { source(it.absUrl("href")) }.distinct().also { require(it.size <= 3) }
                        for (site in sources) {
                            if (excluded.contains(site)) continue
                            val scopeConflict = ScheduleEntityIdentity.hasExplicitConflict(parish.name, name)
                            val cityConflict = parish.city.isNotBlank() && ChildChurchRelation.city(address)?.let {
                                it != ScheduleEntityIdentity.normalize(parish.city)
                            } == true
                            val wanted = PalomnikStreetAddress.parse(parish.address)
                            val published = PalomnikStreetAddress.parse(address)
                            val addressConflict = wanted != null && published != null && MoscowChurchIdentity.definitelyOtherAddress(wanted, published)
                            val conflict = scopeConflict || cityConflict || addressConflict
                            val candidate = ScheduleSourceCandidate(
                                selectedIdentity = ScheduleEntityIdentity.fingerprint(parish), provider = PROVIDER,
                                providerLabel = "Елицы - карта храмов", providerObjectId = point.id,
                                name = name, address = address, sourceUrl = site, evidenceUrl = detailUrl,
                                evidenceSha256 = ScheduleSourceCandidate.sha(detail.bytes), checkedAt = clock.instant().toString(),
                                latitude = point.latitude, longitude = point.longitude,
                                outcome = if (conflict) ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT,
                                reason = when {
                                    scopeConflict -> "Карточка относится к другому виду места богослужения"
                                    cityConflict -> "Населенный пункт источника не совпадает с выбранным храмом"
                                    addressConflict -> "Адрес источника не совпадает с выбранным храмом"
                                    else -> "Проверьте, что на этом сайте опубликовано расписание выбранного храма"
                                }, geometryEvidence = ScheduleGeometryEvidence(response.url, ScheduleSourceCandidate.sha(response.bytes)))
                            if (candidate.validFor(parish)) candidates += candidate else unavailable = true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { unavailable = true }
                }
                true
            }
            if (completed != true) unavailable = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        return ScheduleCandidateDiscovery(candidates.distinctBy { it.id }, observed.distinct().take(200), incomplete = true, unavailable = unavailable)
    }

    private suspend fun get(url: String, maximum: Int): ScheduleResponse {
        val response = transport.fetchBounded(url, maximum)
        require(response.url == url && response.bytes.size in 1..maximum)
        return response
    }

    private data class Point(val id: String, val name: String, val latitude: Double, val longitude: Double)
    private fun point(value: JsonElement): Point {
        require(value.isJsonObject)
        val row = value.asJsonObject
        val pk = requireNotNull(row.get("pk")).also { require(it.isJsonPrimitive) }.asJsonPrimitive
        require(pk.isNumber || pk.isString)
        val id = pk.asString.also { require(it.matches(ID)) }
        val text = requireNotNull(row.get("text")).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
            .asString.trim().also { require(it.length in 1..500 && it.none(Char::isISOControl)) }
        val coordinates = requireNotNull(row.get("coords")).also { require(it.isJsonArray) }.asJsonArray
        require(coordinates.size() == 2 && coordinates.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber })
        val lat = coordinates[0].asDouble; val lon = coordinates[1].asDouble
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        return Point(id, text, lat, lon)
    }

    companion object {
        const val PROVIDER = "elitsy_map"
        internal const val HOME = "https://elitsy.ru"
        internal const val API = "$HOME/map/ajaxpoints/"
        internal const val MAX_COLLECTION_BYTES = 196_608
        internal const val MAX_DETAIL_BYTES = 131_072
        private val ID = Regex("[1-9][0-9]{0,11}")
        private val SITE_LABELS = setOf("официальный сайт храма", "страница храма на prihod.ru")
        private fun clean(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().lowercase()

        private fun source(value: String): String? = runCatching {
            val uri = URI(value)
            if (uri.scheme == "https" && uri.host == "elitsy.ru" && uri.port == -1 && uri.rawPath == "/redirect") {
                require(uri.rawFragment == null && ScheduleUrl.publicSource(value) == value)
                val parts = uri.rawQuery.orEmpty().split('&').map { part ->
                    URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                }
                require(parts.size == 1 && parts.single().first == "url")
                ScheduleUrl.publicSource(parts.single().second)
            } else {
                // An unrecognized wrapper is not an own-site URL.
                require(uri.host != "elitsy.ru" && uri.host != "www.elitsy.ru")
                ScheduleUrl.publicSource(value)
            }
        }.getOrNull()

        private fun strictJson(text: String): JsonElement {
            val reader = JsonReader(StringReader(text)).also { it.isLenient = false }
            var nodes = 0
            fun value(depth: Int): JsonElement {
                require(depth <= 8 && ++nodes <= 3_000)
                return when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                        reader.beginArray(); while (reader.hasNext()) array.add(value(depth + 1)); reader.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) { val key = reader.nextName(); require(!obj.has(key)); obj.add(key, value(depth + 1)) }
                        reader.endObject()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Invalid public map JSON")
                }
            }
            return value(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
        }
    }
}
