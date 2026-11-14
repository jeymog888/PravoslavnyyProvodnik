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

/** Candidate-only discovery. It creates no automatic binding or foreign-image certificate. */
class OwnContactCandidateProvider(private val transport: ScheduleTransport, private val pool: PublisherSeedPool,
    private val clock: Clock = Clock.systemUTC(), private val timeoutMillis: Long = 25_000) : ScheduleCandidateProvider {
    init { require(timeoutMillis in 1..25_000) }

    override suspend fun discover(parish: Parish, excludedUrls: Set<String>) = discover(parish, excludedUrls, emptySet())

    suspend fun discover(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>): ScheduleCandidateDiscovery =
        attempt(parish, excludedUrls, excludedCandidateIds, { pool }, { pool }, null) { found -> found.candidates.any { it.canConfirm(parish) } }

    internal suspend fun discoverFromPools(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
        pools: OwnContactPoolFactories, preferredSourceUrl: String? = null,
        useful: (ScheduleCandidateDiscovery) -> Boolean): ScheduleCandidateDiscovery =
        attempt(parish, excludedUrls, excludedCandidateIds, { pools.direct(parish) }, { pools.websites(parish) }, preferredSourceUrl, useful)

    /** One context owns both lazy tiers, all six dispatches, three scopes and the complete 25s lifetime. */
    private suspend fun attempt(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
        directPool: suspend () -> PublisherSeedPool, websitePool: suspend () -> PublisherSeedPool,
        preferredSourceUrl: String?, useful: (ScheduleCandidateDiscovery) -> Boolean): ScheduleCandidateDiscovery {
        if (!OwnContactChildIdentity.ordinaryOsm(parish)) return ScheduleCandidateDiscovery()
        val selectedIdentity = ScheduleEntityIdentity.fingerprint(parish)
        val groups = linkedMapOf<String, Group>()
        val attempts = AtomicInteger()
        val unavailable = AtomicBoolean(false)
        val workers = Semaphore(2)
        val requested = ConcurrentHashMap.newKeySet<String>()
        val pages = ConcurrentHashMap<String, ScheduleResponse>()
        val excluded = ScheduleSourceExclusions(excludedUrls)
        val found = mutableListOf<ScheduleSourceCandidate>()
        val observed = mutableListOf<String>()
        val evidence = linkedMapOf<String, PublishedChildContactEvidence>()

        suspend fun addPool(factory: suspend () -> PublisherSeedPool) {
            val loaded = factory()
            currentCoroutineContext().ensureActive()
            val seeds = loaded.seeds(parish, 10_000.0, 60).take(60)
                .filter { it.valid() && distance(parish, it.provenance) <= 10_000 }
            val targets = seeds.groupBy { it.url }.mapNotNull { (url, sameUrl) ->
                val scopes = sameUrl.map { it.publisherScopeUrl }.distinct().sortedByDescending(String::length)
                val scope = scopes.firstOrNull() ?: return@mapNotNull null
                if (scopes.any { !PublisherContactScope.contains(it, scope) }) return@mapNotNull null
                Target(url, if (sameUrl.any { it.role == PublisherSeedRole.CONTACT_REFERENCE }) PublisherSeedRole.CONTACT_REFERENCE else
                    PublisherSeedRole.WEBSITE, scope, sameUrl.map { it.provenance }.distinct(), sameUrl.minOf { distance(parish, it.provenance) })
            }.sortedWith(compareBy({ if (it.url == preferredSourceUrl) 0 else 1 }, { if (it.role == PublisherSeedRole.CONTACT_REFERENCE) 0 else
                if (it.origins.any { origin -> origin.origin == PublisherSeedOrigin.CURATED }) 1 else 2 }, { it.distance }, { it.url }))
            for ((scope, additions) in targets.groupBy { it.scope }) {
                val old = groups[scope]
                if (old == null && groups.size >= 3) continue
                val joined = (old?.targets.orEmpty() + additions).groupBy { it.url }.map { (_, rows) ->
                    rows.first().copy(origins = rows.flatMap { it.origins }.distinct(),
                        role = if (rows.any { it.role == PublisherSeedRole.CONTACT_REFERENCE }) PublisherSeedRole.CONTACT_REFERENCE else PublisherSeedRole.WEBSITE)
                }
                groups[scope] = Group(scope, joined)
            }
        }

        fun snapshot(): ScheduleCandidateDiscovery {
            val rows = found.groupBy { it.id }.values.map { versions ->
                val latest = versions.last()
                latest.copy(ownContact = latest.ownContact?.copy(seedOrigins = versions.flatMap { it.ownContact?.seedOrigins.orEmpty() }.distinct()))
            }.take(20)
            return ScheduleCandidateDiscovery(rows, observed.distinct().take(200), incomplete = true, unavailable = unavailable.get(),
                freshContactEvidence = evidence.filter { (id, proof) -> rows.singleOrNull { it.id == id }?.let { proof.matchesFresh(parish, it) } == true })
        }

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
                    pages[response.url] = response
                    response
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable.set(true); null }
            }
        }

        fun candidates(response: ScheduleResponse, scope: String, origins: List<PublisherSeedProvenance>): PhaseResult {
            val parsed = PublishedChildContactParser.parse(response)
            if (parsed.ambiguous) unavailable.set(true)
            val fresh = linkedMapOf<String, PublishedChildContactEvidence>()
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
                candidate.takeIf { it.id !in excludedCandidateIds && it.validFor(parish) }?.also { row ->
                    PublishedChildContactEvidence.fromFreshResponse(parish, response, scope, row)?.let { fresh[row.id] = it }
                }
            }
            return PhaseResult(values, parsed.observedKeys.map { ScheduleSourceCandidate.sha("${response.url}\u0000$it") }, fresh)
        }

        suspend fun process(group: Group, direct: Boolean): PhaseResult {
            val target = group.targets.firstOrNull { (it.role == PublisherSeedRole.CONTACT_REFERENCE) == direct }
                ?: return PhaseResult()
            val origins = group.targets.flatMap { it.origins }.distinct()
            if (origins.size > 20) { unavailable.set(true); return PhaseResult() }
            val first = load(target.url, group.scope) ?: return PhaseResult()
            if (direct) return candidates(first, group.scope, origins)
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
            return load(contact, group.scope)?.let { candidates(it, group.scope, origins) } ?: PhaseResult()
        }

        suspend fun phase(direct: Boolean): Boolean = coroutineScope {
            val eligible = groups.values.filter { group -> group.targets.any { (it.role == PublisherSeedRole.CONTACT_REFERENCE) == direct } }
            val channel = Channel<PhaseResult>(eligible.size.coerceAtLeast(1))
            val jobs = eligible.map { group -> launch {
                val result = process(group, direct)
                channel.trySend(result)
            } }
            try {
                repeat(eligible.size) {
                    val result = channel.receive()
                    found += result.candidates
                    observed += result.observed + result.candidates.map { it.providerObjectId }
                    evidence.putAll(result.evidence)
                    if (useful(snapshot())) return@coroutineScope true
                }
                false
            } finally { jobs.forEach { it.cancel() }; channel.close() }
        }

        try {
            val finished = withTimeoutOrNull(timeoutMillis) {
                addPool(directPool)
                if (!phase(direct = true)) {
                    addPool(websitePool)
                    phase(direct = false)
                }
                true
            }
            if (finished != true) unavailable.set(true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable.set(true) }
        currentCoroutineContext().ensureActive()
        if (selectedIdentity != ScheduleEntityIdentity.fingerprint(parish)) return ScheduleCandidateDiscovery(unavailable = true)
        return snapshot()
    }

    private data class Target(val url: String, val role: PublisherSeedRole, val scope: String,
        val origins: List<PublisherSeedProvenance>, val distance: Double)
    private data class Group(val scope: String, val targets: List<Target>)
    private data class PhaseResult(val candidates: List<ScheduleSourceCandidate> = emptyList(), val observed: List<String> = emptyList(),
        val evidence: Map<String, PublishedChildContactEvidence> = emptyMap())
    private fun distance(parish: Parish, provenance: PublisherSeedProvenance) = OpenScheduleSiteResolver.distanceMeters(
        requireNotNull(parish.latitude), requireNotNull(parish.longitude), provenance.latitude, provenance.longitude)

    companion object {
        const val PROVIDER = "own_contact_block"
        internal const val MAX_BYTES = 1_000_000
    }
}
