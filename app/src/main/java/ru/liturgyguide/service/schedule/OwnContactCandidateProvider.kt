package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ru.liturgyguide.model.Parish
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Candidate-only discovery. It creates neither an automatic site binding nor a foreign-image witness. */
class OwnContactCandidateProvider(private val transport: ScheduleTransport, private val pool: PublisherSeedPool,
    private val clock: Clock = Clock.systemUTC(), private val timeoutMillis: Long = 25_000) : ScheduleCandidateProvider {
    init { require(timeoutMillis in 1..25_000) }

    override suspend fun discover(parish: Parish, excludedUrls: Set<String>) = discover(parish, excludedUrls, emptySet())

    /** The future fast stage may pass dismissed/conflicted IDs without excluding their whole publisher. */
    suspend fun discover(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>): ScheduleCandidateDiscovery {
        if (!OwnContactChildIdentity.ordinaryOsm(parish)) return ScheduleCandidateDiscovery()
        val startedNanos = System.nanoTime()
        val selectedIdentity = ScheduleEntityIdentity.fingerprint(parish)
        val seeds = try {
            pool.seeds(parish, 10_000.0, 60).take(60).filter { it.valid() && distance(parish, it.provenance) <= 10_000 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ScheduleCandidateDiscovery(unavailable = true) }
        val targets = seeds.groupBy { it.url }.mapNotNull { (url, sameUrl) ->
            // Preserve every origin for exact-URL duplicates. Conflicting tenant scopes are not widened.
            val scopes = sameUrl.map { it.publisherScopeUrl }.distinct().sortedByDescending(String::length)
            val scope = scopes.firstOrNull() ?: return@mapNotNull null
            if (scopes.any { !PublisherContactScope.contains(it, scope) }) return@mapNotNull null
            Target(url, if (sameUrl.any { it.role == PublisherSeedRole.CONTACT_REFERENCE }) PublisherSeedRole.CONTACT_REFERENCE else
                PublisherSeedRole.WEBSITE, scope, sameUrl.map { it.provenance }.distinct(), sameUrl.minOf { distance(parish, it.provenance) })
        }.sortedWith(compareBy({ if (it.role == PublisherSeedRole.CONTACT_REFERENCE) 0 else
            if (it.origins.any { origin -> origin.origin == PublisherSeedOrigin.CURATED }) 1 else 2 }, { it.distance }, { it.url }))
        val groups = targets.groupBy { it.scope }.entries.take(3).map { Group(it.key, it.value) }
        val attempts = AtomicInteger()
        val unavailable = AtomicBoolean(false)
        val workers = Semaphore(2)
        val requested = ConcurrentHashMap.newKeySet<String>()
        val pages = ConcurrentHashMap<String, ScheduleResponse>()
        val excluded = ScheduleSourceExclusions(excludedUrls)
        val found = mutableListOf<ScheduleSourceCandidate>()
        val observed = mutableListOf<String>()

        suspend fun load(url: String, scope: String): ScheduleResponse? {
            if (excluded.contains(url) || !PublisherContactScope.contains(scope, url)) return null
            pages[url]?.let { return it }
            if (!requested.add(url)) return null
            return workers.withPermit {
                var reserved = false
                while (!reserved) {
                    val old = attempts.get()
                    if (old >= 6) return@withPermit null
                    reserved = attempts.compareAndSet(old, old + 1)
                }
                try {
                    val response = transport.fetchBounded(url, MAX_BYTES)
                    currentCoroutineContext().ensureActive()
                    require(response.bytes.size in 1..MAX_BYTES && ScheduleUrl.publicSource(response.url) == response.url &&
                        PublisherContactScope.contains(scope, response.url) && PublisherContactScope.sameEndpoint(url, response.url) &&
                        !excluded.contains(response.url))
                    require(Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1)))
                    val document = requireNotNull(response.html())
                    require(document.body().text().isNotBlank() && !Regex("(?iu)домен прода[её]тся|domain (?:is )?parked|сайт заблокирован")
                        .containsMatchIn(document.body().text()))
                    pages[url] = response
                    response
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable.set(true); null }
            }
        }

        fun candidates(response: ScheduleResponse, origins: List<PublisherSeedProvenance>): PhaseResult {
            val parsed = PublishedChildContactParser.parse(response)
            if (parsed.ambiguous) unavailable.set(true)
            val values = parsed.children.filter { OwnContactChildIdentity.pointAgrees(parish, it.latitude, it.longitude) }.mapNotNull { child ->
                val conflict = OwnContactChildIdentity.conflict(parish, child.name, child.locality, child.phone)
                val candidate = ScheduleSourceCandidate(selectedIdentity = selectedIdentity, provider = PROVIDER,
                    providerLabel = "Контакты приходского сайта", providerObjectId = OwnContactChildIdentity.objectId(response.url, child.blockKey, child.name),
                    name = child.name, address = child.locality, sourceUrl = response.url, evidenceUrl = response.url,
                    evidenceSha256 = ScheduleSourceCandidate.sha(response.bytes), checkedAt = clock.instant().toString(),
                    latitude = child.latitude, longitude = child.longitude,
                    outcome = if (conflict) ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT,
                    reason = if (conflict) "Опубликованные данные храма не совпадают с выбранным объектом" else
                        "На странице контактов назван приписной храм рядом с выбранной точкой. Подтвердите источник",
                    geometryEvidence = ScheduleGeometryEvidence(response.url, ScheduleSourceCandidate.sha(response.bytes)),
                    ownContact = OwnContactCandidateMetadata(child.blockKey, child.phone, origins))
                candidate.takeIf { it.id !in excludedCandidateIds && it.validFor(parish) }
            }
            return PhaseResult(values, parsed.observedKeys.map { ScheduleSourceCandidate.sha("${response.url}\u0000$it") })
        }

        suspend fun process(group: Group, direct: Boolean): PhaseResult {
            val target = group.targets.firstOrNull { (it.role == PublisherSeedRole.CONTACT_REFERENCE) == direct }
                ?: return PhaseResult()
            val origins = group.targets.flatMap { it.origins }.distinct()
            if (origins.size > 20) { unavailable.set(true); return PhaseResult() }
            val first = load(target.url, group.scope) ?: return PhaseResult()
            if (direct) return candidates(first, origins)
            val document = first.html() ?: return PhaseResult()
            val links = document.select("nav a[href],header a[href],[role=navigation] a[href]").filter {
                it.closest("aside,footer,#comments,.comments,blockquote") == null &&
                    ScheduleEntityIdentity.normalize(it.text()) in setOf("контакты", "как добраться")
            }.mapNotNull { link ->
                val raw = link.attr("href").trim()
                if (raw.isEmpty() || raw in setOf("null", "#") || raw.startsWith("javascript:", true)) null else
                    ScheduleUrl.publicSource(link.absUrl("href"))?.takeIf { PublisherContactScope.contains(group.scope, it) }
            }.distinct()
            if (links.size > 1) { unavailable.set(true); return PhaseResult() }
            val contact = links.singleOrNull()?.takeIf { it != first.url } ?: return PhaseResult()
            return load(contact, group.scope)?.let { candidates(it, origins) } ?: PhaseResult()
        }

        suspend fun phase(direct: Boolean): Boolean = coroutineScope {
            val eligible = groups.filter { group -> group.targets.any { (it.role == PublisherSeedRole.CONTACT_REFERENCE) == direct } }
            val channel = Channel<PhaseResult>(eligible.size.coerceAtLeast(1))
            val jobs = eligible.map { group -> launch {
                val result = process(group, direct)
                channel.send(result)
            } }
            try {
                repeat(eligible.size) {
                    val result = channel.receive()
                    found += result.candidates
                    observed += result.observed + result.candidates.map { it.providerObjectId }
                    if (result.candidates.any { it.canConfirm(parish) }) return@coroutineScope true
                }
                false
            } finally { jobs.forEach { it.cancel() }; channel.close() }
        }

        try {
            val remaining = timeoutMillis - (System.nanoTime() - startedNanos) / 1_000_000
            val finished = withTimeoutOrNull(remaining.coerceAtLeast(0)) {
                if (!phase(direct = true)) phase(direct = false)
                true
            }
            if (finished != true) unavailable.set(true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable.set(true) }
        currentCoroutineContext().ensureActive()
        if (selectedIdentity != ScheduleEntityIdentity.fingerprint(parish)) return ScheduleCandidateDiscovery(unavailable = true)
        return ScheduleCandidateDiscovery(found.distinctBy { it.id }.take(20), observed.distinct().take(200),
            incomplete = true, unavailable = unavailable.get())
    }

    private data class Target(val url: String, val role: PublisherSeedRole, val scope: String,
        val origins: List<PublisherSeedProvenance>, val distance: Double)
    private data class Group(val scope: String, val targets: List<Target>)
    private data class PhaseResult(val candidates: List<ScheduleSourceCandidate> = emptyList(), val observed: List<String> = emptyList())
    private fun distance(parish: Parish, provenance: PublisherSeedProvenance) = OpenScheduleSiteResolver.distanceMeters(
        requireNotNull(parish.latitude), requireNotNull(parish.longitude), provenance.latitude, provenance.longitude)

    companion object {
        const val PROVIDER = "own_contact_block"
        internal const val MAX_BYTES = 1_000_000
    }
}
