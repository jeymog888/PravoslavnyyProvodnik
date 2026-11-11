package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class InternetScheduleStatus { IDLE, READY, NO_WEBSITE, UNSUPPORTED, UNAVAILABLE }
data class SchedulePublication(
    val parishId: String, val timezone: String, val website: String, val sourceUrl: String,
    val fetchedAt: String, val events: List<InternetService>,
    val documentFormat: String = "html", val linkedFrom: String = ""
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
    val configuredWebsite: String = "", val customSource: String = "",
    val siteEvidenceUrl: String = "",
    // A cancellation or conflicting building invalidated old data; this does not assert all services were cancelled.
    val publicationRejected: Boolean = false
)

interface ScheduleCacheStore {
    fun read(parishId: String): String?
    fun write(parishId: String, value: String)
}

enum class ScheduleImportPhase { IDLE, FINDING_SITE, LOADING_PAGE, READING_DOCUMENT, FINISHED }
data class ScheduleImportProgress(val parishId: String, val phase: ScheduleImportPhase)

interface InternetSchedules {
    val progress: StateFlow<ScheduleImportProgress>? get() = null
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
    private val cache: ScheduleCacheStore,
    private val documentExtractor: ScheduleDocumentExtractor = HtmlScheduleDocumentExtractor,
    private val siteResolver: ScheduleSiteResolver = OpenScheduleSiteResolver(transport)
) : InternetSchedules {
    private val importProgress = MutableStateFlow(ScheduleImportProgress("", ScheduleImportPhase.IDLE))
    override val progress: StateFlow<ScheduleImportProgress> = importProgress

    override fun cached(parish: Parish): InternetScheduleState =
        ScheduleCacheCodec.decode(runCatching { cache.read(parish.id) }.getOrNull(), parish)
            ?: initialScheduleState(parish)

    override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState = withContext(Dispatchers.IO) {
        val previous = cached(parish)
        val age = runCatching { Duration.between(Instant.parse(previous.checkedAt), now) }.getOrNull()
        val retryAfter = if (previous.status == InternetScheduleStatus.READY) Duration.ofHours(6) else Duration.ofMinutes(5)
        if (!force && sourceOverride == null && age != null && !age.isNegative && age < retryAfter) return@withContext previous
        val date = runCatching { now.atZone(ZoneId.of(parish.timezone)).toLocalDate() }.getOrNull()
            ?: return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        if (sourceOverride != null && ScheduleUrl.normalized(sourceOverride) == null) return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        val configured = ScheduleUrl.normalized(parish.website).orEmpty()
        val custom = sourceOverride?.let(ScheduleUrl::normalized) ?: previous.customSource
        val changedSource = sourceOverride != null && custom != previous.customSource
        var website = custom.takeIf(String::isNotBlank) ?: configured.takeIf(String::isNotBlank) ?: previous.website
        var source = website
        var evidence = previous.siteEvidenceUrl
        var status = InternetScheduleStatus.UNAVAILABLE
        var publication: SchedulePublication? = null
        var rejectedPublication = false
        fun phase(value: ScheduleImportPhase) { importProgress.value = ScheduleImportProgress(parish.id, value) }
        try {
            val completed = withTimeoutOrNull(180_000) {
                if (website.isBlank()) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val site = siteResolver.resolve(parish)
                    website = site?.url.orEmpty()
                    evidence = site?.evidenceUrl.orEmpty()
                }
                source = website
                if (website.isBlank()) {
                    status = InternetScheduleStatus.NO_WEBSITE
                    return@withTimeoutOrNull true
                }
                val pending = ArrayDeque<ScheduleDocumentLink>()
                pending += ScheduleDocumentLink(website, "", false)
                val visited = mutableSetOf<String>()
                var htmlPages = 0
                var documents = 0
                var anyResponse = false
                var bestEvents = emptyList<InternetService>()
                var bestFormat = "html"
                var bestLinkedFrom = ""
                var bestDedicated = false
                val withdrawnDates = mutableSetOf<String>()
                val withdrawnStarts = mutableSetOf<String>()
                fun retained(values: List<InternetService>) = values.filterNot {
                    it.date in withdrawnDates || "${it.date}T${it.time}" in withdrawnStarts
                }
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val next = pending.removeFirst()
                    if (!visited.add(next.url)) continue
                    if (next.document && documents >= 3 || !next.document && htmlPages >= 3) continue
                    if (next.document) documents++ else htmlPages++
                    phase(if (next.document) ScheduleImportPhase.READING_DOCUMENT else ScheduleImportPhase.LOADING_PAGE)
                    val response = try { transport.fetch(next.url) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { continue }
                    anyResponse = true
                    val html = response.html()
                    if (visited.size == 1 && html != null) website = response.url
                    val format: String
                    val parsed: ScheduleParseResult
                    if (html != null) {
                        val existing = ScheduleHtmlParser.inspect(html, date, parish)
                        parsed = if (existing.disposition != ScheduleParseDisposition.UNSUPPORTED) existing else
                            ScheduleDocumentParser.inspect(HtmlScheduleDocumentExtractor.fromHtml(html), date, parish)
                        format = "html"
                        ScheduleDocumentLinks.find(html).filter { it.url !in visited }.forEach(pending::addLast)
                    } else {
                        phase(ScheduleImportPhase.READING_DOCUMENT)
                        val document = try { documentExtractor.extract(response) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        parsed = document?.let { ScheduleDocumentParser.inspect(it, date, parish) }
                            ?: ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED)
                        format = document?.format ?: "unknown"
                    }
                    withdrawnDates += parsed.withdrawnDates
                    withdrawnStarts += parsed.withdrawnStarts
                    bestEvents = retained(bestEvents)
                    if (parsed.disposition == ScheduleParseDisposition.REJECTED) {
                        rejectedPublication = true
                        if (bestEvents.isEmpty()) source = response.url
                    }
                    if (parsed.withdrawsAll) {
                        // Only a truly unscoped withdrawal stops the lookup. A cancelled day must
                        // not hide another day's schedule on a linked page.
                        bestEvents = emptyList(); source = response.url
                        break
                    }
                    val events = retained(parsed.events)
                    val dedicated = next.linkedFrom.isNotBlank() || next.document ||
                        Regex("(?iu)(schedule|raspisan|расписан)").containsMatchIn(response.url)
                    fun nearest(values: List<InternetService>) = values.filter { it.date >= date.toString() }
                        .minOfOrNull { "${it.date}T${it.time}" }
                    val candidateNext = nearest(events)
                    val bestNext = nearest(bestEvents)
                    val prefer = bestEvents.isEmpty() || when {
                        candidateNext != null && bestNext == null -> true
                        candidateNext == null && bestNext != null -> false
                        candidateNext != bestNext -> requireNotNull(candidateNext) < requireNotNull(bestNext)
                        dedicated != bestDedicated -> dedicated
                        else -> events.size > bestEvents.size
                    }
                    if (events.isNotEmpty() && prefer) {
                        bestEvents = events; source = response.url; bestFormat = format; bestLinkedFrom = next.linkedFrom; bestDedicated = dedicated
                    }
                }
                if (bestEvents.isEmpty()) status = if (anyResponse) InternetScheduleStatus.UNSUPPORTED else InternetScheduleStatus.UNAVAILABLE
                else {
                    publication = SchedulePublication(parish.id, parish.timezone, website, source, now.toString(), bestEvents,
                        documentFormat = bestFormat, linkedFrom = bestLinkedFrom)
                    status = InternetScheduleStatus.READY
                }
                true
            }
            if (completed != true) status = InternetScheduleStatus.UNAVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = InternetScheduleStatus.UNAVAILABLE }
        finally { phase(ScheduleImportPhase.FINISHED) }
        currentCoroutineContext().ensureActive()
        // Failed refresh preserves usable data or a rejection. An explicit new source starts a new binding.
        val rejection = publication == null && (rejectedPublication || (!changedSource && previous.publicationRejected))
        val result = InternetScheduleState(parish.id, status, website, source, now.toString(),
            publication ?: previous.publication.takeUnless { changedSource || rejectedPublication }, configuredWebsite = configured,
            customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejection)
        runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(result)) }
        result
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
        val evidence = field("siteEvidenceUrl").also { require(it.isEmpty() || ScheduleUrl.normalized(it) == it) }
        val rejected = root.get("publicationRejected")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val publication = root.get("publication")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { value ->
            fun part(key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            require(part("parishId") == parish.id && part("timezone") == parish.timezone)
            val site = requireNotNull(ScheduleUrl.normalized(part("website")))
            val url = requireNotNull(ScheduleUrl.normalized(part("sourceUrl")))
            // A child page may use the same www redirect already accepted by the transport.
            val format = part("documentFormat").ifBlank { "html" }.also { require(it in setOf("html", "pdf", "image")) }
            val linkedFrom = part("linkedFrom").also { require(it.isEmpty() || ScheduleUrl.normalized(it) == it) }
            require(ScheduleDocumentLinks.sameSite(site, url) || (format in setOf("pdf", "image") &&
                linkedFrom.isNotEmpty() && ScheduleDocumentLinks.sameSite(site, linkedFrom)))
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
            SchedulePublication(parish.id, parish.timezone, site, url, fetched, events, format, linkedFrom)
        }
        require(!rejected || publication == null)
        InternetScheduleState(parish.id, InternetScheduleStatus.valueOf(field("status")), website, source, checked, publication,
            configuredWebsite = configured, customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejected)
    }.getOrNull()
}

/** Fresh source data replaces the bundle, including its provenance. Rejected data must not resurrect it. */
fun Parish.withInternetSchedule(state: InternetScheduleState, now: Instant): Parish {
    if (state.parishId != id) return this
    if (state.publicationRejected) return copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "",
        website = state.website, sourceUrl = state.sourceUrl, scheduleSource = "Страница расписания прихода",
        scheduleCoverage = "", scheduleNote = "Прежнее расписание больше не подтверждено источником")
    val online = state.publication?.takeIf { it.parishId == id && it.timezone == timezone && it.isFresh(now) } ?: return this
    val today = now.atZone(ZoneId.of(timezone)).toLocalDate()
    val events = online.events.filter { LocalDate.parse(it.date) >= today }
    return copy(website = online.website, sourceUrl = online.sourceUrl,
        scheduleSource = "Страница расписания прихода", scheduleValidFrom = events.minOfOrNull { it.date }.orEmpty(),
        scheduleValidUntil = events.maxOfOrNull { it.date }.orEmpty(),
        schedule = events.map { ParishService(it.date, it.time, it.title, it.kind) },
        scheduleCoverage = "События с указанной датой на сайте прихода",
        scheduleNote = "Загружено с сайта ${java.net.URI(online.sourceUrl).host}. Составные службы показаны с временем начала всего блока")
}
