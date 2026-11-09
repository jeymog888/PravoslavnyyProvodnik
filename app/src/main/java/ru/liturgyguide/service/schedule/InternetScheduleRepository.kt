package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.PublishedSchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class InternetScheduleStatus { IDLE, READY, NO_WEBSITE, UNSUPPORTED, UNAVAILABLE }
data class SchedulePublication(
    val parishId: String, val timezone: String, val website: String, val sourceUrl: String,
    val fetchedAt: String, val events: List<InternetService>
) {
    fun forDate(date: LocalDate): List<InternetService> = events.filter { it.date == date.toString() }
    fun isFresh(now: Instant): Boolean = runCatching {
        val age = Duration.between(Instant.parse(fetchedAt), now)
        !age.isNegative && age <= Duration.ofHours(24)
    }.getOrDefault(false)
}

data class InternetScheduleState(
    val parishId: String, val status: InternetScheduleStatus = InternetScheduleStatus.IDLE,
    val website: String = "", val sourceUrl: String = "", val checkedAt: String = "",
    val publication: SchedulePublication? = null, val loading: Boolean = false,
    val configuredWebsite: String = "", val customSource: String = ""
)

interface ScheduleCacheStore {
    fun read(parishId: String): String?
    fun write(parishId: String, value: String)
}

interface InternetSchedules {
    fun cached(parish: Parish): InternetScheduleState
    suspend fun refresh(parish: Parish, now: Instant, force: Boolean = false, sourceOverride: String? = null): InternetScheduleState
}

/** Injectable in UI tests; no production test-environment detection and no implicit network mock. */
object OfflineInternetSchedules : InternetSchedules {
    override fun cached(parish: Parish) = initialScheduleState(parish)
    override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?) = cached(parish)
}

private fun initialScheduleState(parish: Parish): InternetScheduleState {
    val website = ScheduleUrl.normalized(parish.website).orEmpty()
    return InternetScheduleState(parish.id, website = website, configuredWebsite = website)
}

class InternetScheduleRepository(
    private val transport: ScheduleTransport,
    private val cache: ScheduleCacheStore
) : InternetSchedules {
    override fun cached(parish: Parish): InternetScheduleState =
        ScheduleCacheCodec.decode(runCatching { cache.read(parish.id) }.getOrNull(), parish)
            ?: initialScheduleState(parish)

    override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState = withContext(Dispatchers.IO) {
        val previous = cached(parish)
        val age = runCatching { Duration.between(Instant.parse(previous.checkedAt), now) }.getOrNull()
        if (!force && sourceOverride == null && age != null && !age.isNegative && age < Duration.ofHours(6)) return@withContext previous
        val date = runCatching { now.atZone(ZoneId.of(parish.timezone)).toLocalDate() }.getOrNull()
            ?: return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        if (sourceOverride != null && ScheduleUrl.normalized(sourceOverride) == null) return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        val configured = ScheduleUrl.normalized(parish.website).orEmpty()
        val custom = sourceOverride?.let(ScheduleUrl::normalized) ?: previous.customSource
        val changedSource = sourceOverride != null && custom != previous.customSource
        var website = custom.takeIf(String::isNotBlank) ?: configured.takeIf(String::isNotBlank) ?: previous.website
        var source = website
        var status = InternetScheduleStatus.UNAVAILABLE
        var publication: SchedulePublication? = null
        try {
            withTimeoutOrNull(20_000) {
                if (website.isBlank()) website = websiteFromOsm(parish.id).orEmpty()
                source = website
                if (website.isBlank()) {
                    status = InternetScheduleStatus.NO_WEBSITE
                    return@withTimeoutOrNull
                }
                val firstResponse = transport.fetch(website)
                val first = firstResponse.html()
                source = firstResponse.url
                if (first == null) {
                    status = InternetScheduleStatus.UNSUPPORTED
                    return@withTimeoutOrNull
                }
                website = firstResponse.url
                val firstEvents = ScheduleHtmlParser.parse(first, date)
                var bestEvents = firstEvents
                // At most the source page and two same-origin schedule links, including one nested index.
                val pending = ArrayDeque(ScheduleHtmlParser.scheduleLinks(first).filter { it != firstResponse.url })
                val visited = mutableSetOf(firstResponse.url)
                var pages = 0
                while (pending.isNotEmpty() && pages < 2 && bestEvents.none { it.date >= date.toString() }) {
                    val next = pending.removeFirst()
                    if (!visited.add(next)) continue
                    pages++
                    val page = try { transport.fetch(next).html() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (page != null) {
                        val events = ScheduleHtmlParser.parse(page, date)
                        // Keep one publication and its exact source, rather than invent a combined provenance.
                        if (events.count { it.date >= date.toString() } > bestEvents.count { it.date >= date.toString() }) {
                            bestEvents = events
                            source = page.baseUri()
                        }
                        ScheduleHtmlParser.scheduleLinks(page).filter { it !in visited }.forEach(pending::addLast)
                    }
                }
                if (bestEvents.isEmpty()) status = InternetScheduleStatus.UNSUPPORTED
                else {
                    publication = SchedulePublication(parish.id, parish.timezone, website, source, now.toString(), bestEvents)
                    status = InternetScheduleStatus.READY
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = InternetScheduleStatus.UNAVAILABLE }
        currentCoroutineContext().ensureActive()
        // A failed refresh never deletes the last dated publication or a bundled timetable.
        val result = InternetScheduleState(parish.id, status, website, source, now.toString(),
            publication ?: previous.publication.takeUnless { changedSource }, configuredWebsite = configured, customSource = custom)
        runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(result)) }
        result
    }

    private suspend fun websiteFromOsm(id: String): String? {
        val match = Regex("^osm:(node|way|relation):([1-9][0-9]{0,18})$").matchEntire(id) ?: return null
        val response = transport.fetch("https://api.openstreetmap.org/api/0.6/${match.groupValues[1]}/${match.groupValues[2]}.json")
        val json = JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
        val element = json.getAsJsonArray("elements")?.firstOrNull { item ->
            val value = item.asJsonObject
            value.get("type")?.asString == match.groupValues[1] && value.get("id")?.asString == match.groupValues[2]
        }?.asJsonObject ?: return null
        val tags = element.getAsJsonObject("tags") ?: return null
        return listOf("website", "contact:website").firstNotNullOfOrNull { key ->
            tags.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let(ScheduleUrl::normalized)
        }
    }
}

/** Validated cache, including binding and age. Gson constructor bypass must not admit malformed data. */
internal object ScheduleCacheCodec {
    private val gson = Gson()
    fun encode(state: InternetScheduleState): String = gson.toJson(state)
    fun decode(json: String?, parish: Parish): InternetScheduleState? = runCatching {
        require(json != null && json.length <= 200_000)
        val root = JsonParser.parseString(json).asJsonObject
        fun field(key: String) = root.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        require(field("parishId") == parish.id)
        val website = field("website").also { require(it.isEmpty() || ScheduleUrl.normalized(it) == it) }
        val source = field("sourceUrl").also { require(it.isEmpty() || ScheduleUrl.normalized(it) == it) }
        val configured = field("configuredWebsite").also { require(it == ScheduleUrl.normalized(parish.website).orEmpty()) }
        val custom = field("customSource").also { require(it.isEmpty() || ScheduleUrl.normalized(it) == it) }
        val checked = field("checkedAt").also { Instant.parse(it) }
        val publication = root.get("publication")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { value ->
            fun part(key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            require(part("parishId") == parish.id && part("timezone") == parish.timezone)
            val site = requireNotNull(ScheduleUrl.normalized(part("website")))
            val url = requireNotNull(ScheduleUrl.normalized(part("sourceUrl")))
            require(ScheduleUrl.sameOrigin(site, url))
            val fetched = part("fetchedAt").also { Instant.parse(it) }
            val array = value.getAsJsonArray("events")
            require(array.size() in 1..200)
            val events = array.map { element ->
                val event = element.asJsonObject
                val date = event.get("date").asString.also { LocalDate.parse(it) }
                val time = event.get("time").asString.also { require(LocalTime.parse(it).second == 0 && it.length == 5) }
                val title = event.get("title").asString.also { require(it.length in 2..300 && it.none(Char::isISOControl)) }
                val kind = event.get("kind").asString.also { require(it in setOf("liturgy", "service_block")) }
                InternetService(date, time, title, kind)
            }
            val publicationDate = Instant.parse(fetched).atZone(ZoneId.of(parish.timezone)).toLocalDate()
            require(events.all { LocalDate.parse(it.date) in publicationDate.minusDays(1)..publicationDate.plusDays(45) })
            SchedulePublication(parish.id, parish.timezone, site, url, fetched, events)
        }
        InternetScheduleState(parish.id, InternetScheduleStatus.valueOf(field("status")), website, source, checked, publication,
            configuredWebsite = configured, customSource = custom)
    }.getOrNull()
}

/** Fresh online data fills dates not covered by the curated bundle; it cannot rewrite that bundle. */
fun Parish.withInternetSchedule(state: InternetScheduleState, now: Instant): Parish {
    val online = state.publication?.takeIf { it.parishId == id && it.timezone == timezone && it.isFresh(now) } ?: return this
    val today = now.atZone(ZoneId.of(timezone)).toLocalDate()
    // One Parish has one provenance. Keep the curated publication if it still covers today or tomorrow.
    if (PublishedSchedule.forDate(this, today).isNotEmpty() || PublishedSchedule.forDate(this, today.plusDays(1)).isNotEmpty()) return this
    val events = online.events.filter { LocalDate.parse(it.date) >= today }
    if (events.isEmpty()) return this
    return copy(website = online.website, sourceUrl = online.sourceUrl,
        scheduleSource = "Страница расписания прихода", scheduleValidFrom = events.minOf { it.date },
        scheduleValidUntil = events.maxOf { it.date },
        schedule = events.map { ParishService(it.date, it.time, it.title, it.kind) },
        scheduleCoverage = "События с указанной датой на сайте прихода",
        scheduleNote = "Загружено с сайта ${java.net.URI(online.sourceUrl).host}. Составные службы показаны с временем начала всего блока")
}
