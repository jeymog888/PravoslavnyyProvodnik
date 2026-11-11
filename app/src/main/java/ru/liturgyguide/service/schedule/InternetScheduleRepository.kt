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
    val documentFormat: String = "html", val linkedFrom: String = "",
    val yearInferred: Boolean = false
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
    val publicationRejected: Boolean = false,
    // A partial withdrawal can coexist with valid events. Keep its source authoritative
    // across failed refreshes instead of reviving cancelled events from another website.
    val sourceHasWithdrawals: Boolean = false
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
    val website = ScheduleUrl.publicSource(parish.website).orEmpty()
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
        if (sourceOverride != null && ScheduleUrl.publicSource(sourceOverride) == null) return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        val configured = ScheduleUrl.publicSource(parish.website).orEmpty()
        val custom = sourceOverride?.let(ScheduleUrl::publicSource) ?: previous.customSource
        val changedSource = sourceOverride != null && custom != previous.customSource
        // Cache validation already requires the configured source to be unchanged. Reuse a
        // confirmed resolved source instead of retrying the same obsolete configured URL.
        // A withdrawal has no publication, but its source must still be retried: returning to
        // the old configured page could resurrect events that the new source cancelled.
        val resolved = previous.website.takeIf { it.isNotBlank() && custom.isBlank() && previous.siteEvidenceUrl.isNotBlank() &&
            (previous.publication?.website == it || previous.publicationRejected) }
        var website = custom.takeIf(String::isNotBlank) ?: resolved ?: configured.takeIf(String::isNotBlank) ?: previous.website
        var source = website
        var evidence = previous.siteEvidenceUrl.takeUnless { changedSource }.orEmpty()
        var status = InternetScheduleStatus.UNAVAILABLE
        var publication: SchedulePublication? = null
        var rejectedPublication = false
        var sourceHasWithdrawals = previous.sourceHasWithdrawals && !changedSource
        fun phase(value: ScheduleImportPhase) { importProgress.value = ScheduleImportProgress(parish.id, value) }
        fun accept(attempt: SourceAttempt, siteEvidence: String) {
            website = attempt.website; source = attempt.sourceUrl; evidence = siteEvidence
            publication = attempt.publication; status = attempt.status
            rejectedPublication = attempt.rejected || attempt.hasWithdrawal
            if (attempt.hasWithdrawal || attempt.publication != null) sourceHasWithdrawals = attempt.hasWithdrawal
        }
        try {
            val completed = withTimeoutOrNull(180_000) {
                if (website.isBlank()) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val site = lookup(parish, emptySet())
                    website = site?.url.orEmpty()
                    evidence = site?.evidenceUrl.orEmpty()
                }
                source = website
                if (website.isBlank()) {
                    status = InternetScheduleStatus.NO_WEBSITE
                    return@withTimeoutOrNull true
                }
                val primaryEvidence = evidence
                val primary = importSource(parish, website, date, now) { checkedWebsite, checkedSource, withdrawal ->
                    rejectedPublication = true; website = checkedWebsite; source = checkedSource
                    if (withdrawal) sourceHasWithdrawals = true
                }
                accept(primary, primaryEvidence)
                // Neither a manual source nor an explicit rejection/withdrawal is a reason to
                // shop for a different answer. This also protects persisted prior rejections.
                if (primary.publication == null && custom.isBlank() && !previous.publicationRejected && !sourceHasWithdrawals &&
                    !primary.rejected && !primary.hasWithdrawal) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val alternative = lookup(parish, primary.attemptedUrls + configured)
                    if (alternative != null) {
                        val next = importSource(parish, alternative.url, date, now) { checkedWebsite, checkedSource, withdrawal ->
                            // A different building at a new candidate cannot invalidate an
                            // older accepted source. Actual withdrawals of this parish can.
                            if (withdrawal) {
                                rejectedPublication = true; website = checkedWebsite; source = checkedSource
                                evidence = alternative.evidenceUrl
                                sourceHasWithdrawals = true
                            }
                        }
                        if (next.publication != null || next.hasWithdrawal) accept(next, alternative.evidenceUrl)
                    }
                }
                true
            }
            if (completed != true) status = InternetScheduleStatus.UNAVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = InternetScheduleStatus.UNAVAILABLE }
        finally { phase(ScheduleImportPhase.FINISHED) }
        currentCoroutineContext().ensureActive()
        val rejection = publication == null && (rejectedPublication || (!changedSource && previous.publicationRejected))
        val retainedPublication = publication ?: previous.publication.takeUnless { changedSource || rejectedPublication }
        // A failed alternative must preserve every part of the accepted binding, not attach
        // a new homepage/evidence to cached events from a different source.
        if (publication == null && retainedPublication != null) {
            website = retainedPublication.website; source = retainedPublication.sourceUrl
            evidence = previous.siteEvidenceUrl
        }
        val result = InternetScheduleState(parish.id, status, website, retainedPublication?.sourceUrl ?: source, now.toString(),
            retainedPublication, configuredWebsite = configured,
            customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejection,
            sourceHasWithdrawals = sourceHasWithdrawals)
        runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(result)) }
        result
    }

    private data class SourceAttempt(val website: String, val sourceUrl: String,
        val status: InternetScheduleStatus, val publication: SchedulePublication?, val attemptedUrls: Set<String>,
        val rejected: Boolean, val hasWithdrawal: Boolean)

    private suspend fun lookup(parish: Parish, excludedUrls: Set<String>): ScheduleSite? = try {
        val excluded = ScheduleSourceExclusions(excludedUrls)
        siteResolver.resolve(parish, excludedUrls)?.let { site ->
            val url = ScheduleUrl.publicSource(site.url)
            val evidence = ScheduleUrl.publicSource(site.evidenceUrl)
            if (url == null || evidence == null || excluded.contains(url)) null
            else site.copy(url = url, evidenceUrl = evidence)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    /** Each attempt has the same small crawl budget; refresh owns the single shared deadline. */
    private suspend fun importSource(parish: Parish, root: String, date: LocalDate, now: Instant,
        onSafety: (website: String, sourceUrl: String, withdrawal: Boolean) -> Unit): SourceAttempt {
        var website = root
        var source = root
        var rejectedPublication = false
        var hasWithdrawal = false
        fun phase(value: ScheduleImportPhase) { importProgress.value = ScheduleImportProgress(parish.id, value) }
        val pending = ArrayDeque<ScheduleDocumentLink>()
        pending += ScheduleDocumentLink(website, "", false)
        val visited = mutableSetOf<String>()
        val attempted = mutableSetOf<String>()
        var htmlPages = 0
        var documents = 0
        var anyResponse = false
        var bestEvents = emptyList<InternetService>()
        var bestFormat = "html"
        var bestLinkedFrom = ""
        var bestDedicated = false
        var bestYearInferred = false
        var previousImage: ScheduleDocument? = null
        var previousImageParent = ""
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
            attempted += next.url
            val response = try { transport.fetch(next.url) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { previousImage = null; previousImageParent = ""; continue }
            attempted += response.url
            anyResponse = true
            val html = response.html()
            if (html != null) { previousImage = null; previousImageParent = "" }
            // External hosts are authorized only for a directly linked PDF/image. A
            // filename ending in .pdf cannot turn a foreign HTML page into this parish.
            if (html != null && !ScheduleDocumentLinks.sameSite(website, response.url)) continue
            if (visited.size == 1 && html != null) website = response.url
            val format: String
            val parsed: ScheduleParseResult
            var publicationSource = response.url
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
                val continuation = document?.takeIf { next.linkedFrom.isNotBlank() && next.linkedFrom == previousImageParent }
                    ?.let { current -> previousImage?.let { previous ->
                        ScheduleImageContinuation.join(previous, current, next.linkedFrom, parish)
                    } }
                val selectedDocument = continuation ?: document
                previousImage = selectedDocument?.takeIf { it.format == "image" }
                previousImageParent = next.linkedFrom.takeIf { previousImage != null }.orEmpty()
                if (continuation != null) publicationSource = continuation.sourceUrl
                parsed = selectedDocument?.let { ScheduleDocumentParser.inspect(it, date, parish) }
                    ?: ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED)
                format = selectedDocument?.format ?: "unknown"
            }
            if (parsed.safetyConflict) {
                // A newer OCR pass can contradict an already-readable page from this same
                // source. Its unknown scope cannot be turned into global withdrawal dates,
                // nor ignored while returning the earlier HTML as a successful publication.
                onSafety(website, publicationSource, false)
                rejectedPublication = true
                bestEvents = emptyList(); source = publicationSource
                break
            }
            val withdraws = parsed.withdrawsAll || parsed.withdrawnDates.isNotEmpty() || parsed.withdrawnStarts.isNotEmpty()
            hasWithdrawal = hasWithdrawal || withdraws
            if (parsed.disposition == ScheduleParseDisposition.REJECTED || withdraws) {
                // Report safety evidence immediately: a later timeout must not restore an
                // earlier cached service after this attempt already observed its withdrawal.
                onSafety(website, publicationSource, withdraws)
            }
            withdrawnDates += parsed.withdrawnDates
            withdrawnStarts += parsed.withdrawnStarts
            bestEvents = retained(bestEvents)
            if (parsed.disposition == ScheduleParseDisposition.REJECTED) {
                rejectedPublication = true
                if (bestEvents.isEmpty()) source = publicationSource
            }
            if (parsed.withdrawsAll) {
                // Only a truly unscoped withdrawal stops the lookup. A cancelled day must
                // not hide another day's schedule on a linked page.
                bestEvents = emptyList(); source = publicationSource
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
                parsed.yearInferred != bestYearInferred -> !parsed.yearInferred
                candidateNext != bestNext -> requireNotNull(candidateNext) < requireNotNull(bestNext)
                dedicated != bestDedicated -> dedicated
                else -> events.size > bestEvents.size
            }
            if (events.isNotEmpty() && prefer) {
                bestEvents = events; source = publicationSource; bestFormat = format; bestLinkedFrom = next.linkedFrom; bestDedicated = dedicated
                bestYearInferred = parsed.yearInferred
            }
        }
        val publication = bestEvents.takeIf { it.isNotEmpty() }?.let {
            SchedulePublication(parish.id, parish.timezone, website, source, now.toString(), it,
                documentFormat = bestFormat, linkedFrom = bestLinkedFrom, yearInferred = bestYearInferred)
        }
        val status = if (publication != null) InternetScheduleStatus.READY else
            if (anyResponse) InternetScheduleStatus.UNSUPPORTED else InternetScheduleStatus.UNAVAILABLE
        return SourceAttempt(website, source, status, publication, attempted, rejectedPublication, hasWithdrawal)
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
        val website = field("website").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val source = field("sourceUrl").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val configured = field("configuredWebsite").also { require(it == ScheduleUrl.publicSource(parish.website).orEmpty()) }
        val custom = field("customSource").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val checked = field("checkedAt").also { Instant.parse(it) }
        val evidence = field("siteEvidenceUrl").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val rejected = root.get("publicationRejected")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val sourceHasWithdrawals = root.get("sourceHasWithdrawals")?.takeUnless { it.isJsonNull }?.let { flag ->
            require(flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean)
            require(!flag.asBoolean || website.isNotBlank())
            flag.asBoolean
        } ?: false
        val publication = root.get("publication")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { value ->
            fun part(key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            require(part("parishId") == parish.id && part("timezone") == parish.timezone)
            val site = requireNotNull(ScheduleUrl.publicSource(part("website")))
            val url = requireNotNull(ScheduleUrl.publicSource(part("sourceUrl")))
            // A child page may use the same www redirect already accepted by the transport.
            val format = part("documentFormat").ifBlank { "html" }.also { require(it in setOf("html", "pdf", "image")) }
            val linkedFrom = part("linkedFrom").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
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
            val yearInferred = value.get("yearInferred")?.takeUnless { it.isJsonNull }?.let { flag ->
                require(flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean)
                flag.asBoolean
            } ?: false
            if (yearInferred) require(events.all { java.time.YearMonth.from(LocalDate.parse(it.date)) == java.time.YearMonth.from(publicationDate) })
            SchedulePublication(parish.id, parish.timezone, site, url, fetched, events, format, linkedFrom, yearInferred)
        }
        require(!rejected || publication == null)
        InternetScheduleState(parish.id, InternetScheduleStatus.valueOf(field("status")), website, source, checked, publication,
            configuredWebsite = configured, customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejected,
            sourceHasWithdrawals = sourceHasWithdrawals)
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
        scheduleSource = if (online.yearInferred) "Время с сайта прихода; год не указан" else "Страница расписания прихода",
        scheduleValidFrom = events.minOfOrNull { it.date }.orEmpty(),
        scheduleValidUntil = events.maxOfOrNull { it.date }.orEmpty(),
        schedule = events.map { ParishService(it.date, it.time, it.title, it.kind) },
        scheduleCoverage = if (online.yearInferred) "Год предположен по месяцу и дням недели" else "События с указанной датой на сайте прихода",
        scheduleNote = (if (online.yearInferred) "Год не указан - проверьте дату в расписании. " else "") +
            (if (online.sourceUrl.startsWith("http://")) "Источник использует HTTP. " else "") +
            "Загружено с сайта ${java.net.URI(online.sourceUrl).host}. Составные службы показаны с временем начала всего блока")
}
