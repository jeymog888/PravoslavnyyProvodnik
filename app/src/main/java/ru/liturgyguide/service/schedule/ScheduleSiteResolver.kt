package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import ru.liturgyguide.model.Parish
import java.net.URLEncoder
import java.util.Locale
import java.time.Clock
import kotlin.math.*

data class ScheduleSite(val url: String, val evidenceUrl: String, val matchedName: String,
    val binding: ScheduleSiteBinding? = null, val observedSchedule: ScheduleResponse? = null)
fun interface ScheduleSiteResolver {
    suspend fun resolve(parish: Parish): ScheduleSite?
    suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
        resolve(parish)?.takeUnless { ScheduleSourceExclusions(excludedUrls).contains(it.url) }
    suspend fun candidates(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery =
        ScheduleCandidateDiscovery(unavailable = true)
    /** Explicit metadata search; custom resolvers keep their previous candidate contract. */
    suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
        useful: (ScheduleCandidateDiscovery) -> Boolean,
        onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit): ScheduleCandidateDiscovery =
        withTimeoutOrNull(35_000) { candidates(parish, excludedUrls).also(onProviderCompleted) }
            ?: ScheduleCandidateDiscovery(unavailable = true)
    /** Existing custom resolvers remain authoritative; expected-aware production review is optional. */
    suspend fun candidatesForConfirmation(parish: Parish, excludedUrls: Set<String>, expected: ScheduleSourceCandidate): ScheduleCandidateDiscovery =
        candidates(parish, excludedUrls)
    suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
        val site = resolve(parish, excludedUrls)
        if (site != null && site.binding?.validFor(parish, site.url, site.evidenceUrl) == true)
            return ScheduleSourceDiscovery(ScheduleIdentityOutcome.PROVED, site)
        val found = candidates(parish, excludedUrls)
        val outcome = if (found.candidates.isNotEmpty() && found.candidates.all { it.outcome == ScheduleIdentityOutcome.CONFLICT })
            ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT
        return ScheduleSourceDiscovery(outcome, candidates = found)
    }
    /** Optional cold-source prelude. Existing custom discover contracts remain authoritative. */
    suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery? = null
    /** Eligibility is owned by the repository. Custom old hooks still receive exactly their old call. */
    suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>,
        useful: (ScheduleCandidateDiscovery) -> Boolean, excludedCandidateIds: Set<String> = emptySet()): ScheduleSourceDiscovery? =
        initialFastDiscovery(parish, excludedUrls)
    /** One scope for primary/alternative lookup; test/custom resolvers keep their existing behavior. */
    fun forRefresh(): ScheduleSiteResolver = this
}

/** Exact OSM identity first, then an identified encyclopedia entity, never the first search hit. */
class OpenScheduleSiteResolver(private val transport: ScheduleTransport,
    directoryCache: OfficialDirectoryCacheStore = MemoryOfficialDirectoryCacheStore(),
    moscowSearch: MoscowDirectorySearchTransport? = null,
    private val candidateProvider: ScheduleCandidateProvider = CompositeScheduleCandidateProvider(listOf(
        OfficialMapCandidateProvider.PROVIDER to OfficialMapCandidateProvider(transport),
        ElitsyMapCandidateProvider.PROVIDER to ElitsyMapCandidateProvider(transport))),
    private val ownPools: OwnContactPoolFactories? = null, private val clock: Clock = Clock.systemUTC()) : ScheduleSiteResolver {
    private val directories = OfficialDirectoryResolver(transport, directoryCache)
    private val moscow = moscowSearch?.let { MoscowOfficialDirectoryResolver(transport, it) }
    override suspend fun resolve(parish: Parish): ScheduleSite? = resolve(parish, emptySet())

    override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
        resolveInSession(parish, excludedUrls, mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf())

    override suspend fun candidates(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery =
        forRefresh().candidates(parish, excludedUrls)

    override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
        useful: (ScheduleCandidateDiscovery) -> Boolean, onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit) =
        forRefresh().candidatesForSourceSearch(parish, excludedUrls, excludedCandidateIds, useful, onProviderCompleted)

    override suspend fun candidatesForConfirmation(parish: Parish, excludedUrls: Set<String>, expected: ScheduleSourceCandidate) =
        forRefresh().candidatesForConfirmation(parish, excludedUrls, expected)

    override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery? =
        forRefresh().initialFastDiscovery(parish, excludedUrls)

    override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>,
        useful: (ScheduleCandidateDiscovery) -> Boolean, excludedCandidateIds: Set<String>) =
        forRefresh().initialFastDiscovery(parish, excludedUrls, useful, excludedCandidateIds)

    private data class CandidateRequest(val identity: String, val excluded: Set<String>)

    override fun forRefresh(): ScheduleSiteResolver {
        val directoryMemo = mutableMapOf<String, OfficialDirectorySnapshot?>()
        val contactMemo = mutableMapOf<String, ScheduleSite?>()
        val moscowMemo = mutableMapOf<String, ScheduleSite?>()
        val osmMemo = mutableMapOf<String, JsonObject?>()
        val mapMemo = mutableMapOf<CandidateRequest, ScheduleCandidateDiscovery>()
        val wholeMemo = mutableMapOf<CandidateRequest, ScheduleCandidateDiscovery>()
        val ownProvider = OwnContactCandidateProvider(transport, LocalPublisherSeedPool(emptyList()), clock)
        return object : ScheduleSiteResolver {
            private fun request(parish: Parish, excludedUrls: Set<String>) =
                CandidateRequest(ScheduleEntityIdentity.fingerprint(parish), excludedUrls.toSet())

            private suspend fun maps(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
                val key = request(parish, excludedUrls)
                mapMemo[key]?.let { return it }
                val found = try {
                    // Production Composite owns its 30s limit; retain its completed neighbor with 35s headroom.
                    withTimeoutOrNull(35_000) { candidateProvider.discover(parish, excludedUrls) }
                        ?: ScheduleCandidateDiscovery(unavailable = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ScheduleCandidateDiscovery(unavailable = true) }
                mapMemo[key] = found
                return found
            }

            private suspend fun own(parish: Parish, excludedUrls: Set<String>, excludedIds: Set<String>,
                expected: ScheduleSourceCandidate? = null, useful: (ScheduleCandidateDiscovery) -> Boolean): ScheduleCandidateDiscovery {
                val pools = ownPools ?: return ScheduleCandidateDiscovery()
                val actual = if (expected?.provider == OwnContactCandidateProvider.PROVIDER)
                    expectedHintPools(parish, expected, pools) else pools
                return ownProvider.discoverFromPools(parish, excludedUrls, excludedIds, actual, expected?.sourceUrl, useful)
            }

            private suspend fun whole(parish: Parish, excludedUrls: Set<String>, expected: ScheduleSourceCandidate? = null): ScheduleCandidateDiscovery {
                if (ownPools == null) return maps(parish, excludedUrls)
                val completed = ConcurrentHashMap<Int, ScheduleCandidateDiscovery>()
                // Map30 and Own25 run in parallel. A local timeout cannot erase a completed neighbor.
                withTimeoutOrNull(35_000) { coroutineScope {
                    launch { completed[0] = maps(parish, excludedUrls) }
                    launch { completed[1] = own(parish, excludedUrls, emptySet(), expected) { found ->
                        if (expected == null) found.candidates.any { it.canConfirm(parish) }
                        else found.candidates.any { row -> row.id == expected.id && row.canConfirm(parish) ||
                            row.provider == expected.provider && row.outcome == ScheduleIdentityOutcome.CONFLICT &&
                                !row.verificationUnavailable && sameCandidateSource(row.sourceUrl, expected.sourceUrl) }
                    } }
                } }
                return mergeFreshScheduleDiscoveries(parish, completed[0] ?: ScheduleCandidateDiscovery(unavailable = true),
                    completed[1] ?: ScheduleCandidateDiscovery(unavailable = true))
            }

            override suspend fun candidates(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
                val key = request(parish, excludedUrls)
                wholeMemo[key]?.let { return it }
                return whole(parish, excludedUrls).also { wholeMemo[key] = it }
            }

            override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
                useful: (ScheduleCandidateDiscovery) -> Boolean,
                onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit): ScheduleCandidateDiscovery {
                // No wholeMemo: dismissal, chosen URL and the predicate belong to this action.
                if (ownPools == null) return maps(parish, excludedUrls).also(onProviderCompleted)
                val completed = ConcurrentHashMap<Int, ScheduleCandidateDiscovery>()
                withTimeoutOrNull(35_000) { coroutineScope {
                    launch {
                        val found = maps(parish, excludedUrls)
                        completed[0] = found
                        // Either provider's fresh negative reaches durable storage before
                        // waiting for its neighbor. The callback has no suspension point.
                        onProviderCompleted(found)
                    }
                    launch {
                        val found = own(parish, excludedUrls, excludedCandidateIds) { found ->
                            useful(mergeFreshScheduleDiscoveries(parish, found,
                                completed[0] ?: ScheduleCandidateDiscovery()))
                        }
                        completed[1] = found
                        // A completed Own negative can target the exact accepted
                        // Map endpoint too. Persist it before awaiting Map.
                        onProviderCompleted(found)
                    }
                } }
                // Own comes first in this explicit alternative search. It cannot be
                // crowded out merely because the old Map proposal is ready again.
                return mergeFreshScheduleDiscoveries(parish,
                    completed[1] ?: ScheduleCandidateDiscovery(unavailable = true),
                    completed[0] ?: ScheduleCandidateDiscovery(unavailable = true))
            }

            override suspend fun candidatesForConfirmation(parish: Parish, excludedUrls: Set<String>, expected: ScheduleSourceCandidate): ScheduleCandidateDiscovery =
                if (expected.provider == OwnContactCandidateProvider.PROVIDER) whole(parish, excludedUrls, expected)
                else maps(parish, excludedUrls)

            override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery? =
                initialFastDiscovery(parish, excludedUrls, { found -> found.candidates.any { it.canConfirm(parish) } }, emptySet())

            override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>,
                useful: (ScheduleCandidateDiscovery) -> Boolean, excludedCandidateIds: Set<String>): ScheduleSourceDiscovery? {
                val url = osmObjectUrl(parish) ?: return null
                val excluded = ScheduleSourceExclusions(excludedUrls)
                val exact = withTimeoutOrNull(3_000) { exactOsmSite(parish, osmTags(parish, osmMemo), url, excluded) }
                if (!osmMemo.containsKey(osmMemoKey(parish, url))) osmMemo[osmMemoKey(parish, url)] = null
                if (exact?.binding?.validFor(parish, exact.url, exact.evidenceUrl) == true)
                    return ScheduleSourceDiscovery(ScheduleIdentityOutcome.PROVED, exact)
                val map = maps(parish, excludedUrls)
                if (useful(map) || ownPools == null) return candidateDiscovery(map).copy(
                    fastMapReceipt = if (ownPools != null) FastMapContinuationReceipt.issue(parish) else null)
                // This starts its own single 25s Own context only after Maps are found ineligible.
                val contact = own(parish, excludedUrls, excludedCandidateIds) { found ->
                    useful(mergeFreshScheduleDiscoveries(parish, map, found))
                }
                val found = mergeFreshScheduleDiscoveries(parish, map, contact)
                wholeMemo[request(parish, excludedUrls)] = found
                return candidateDiscovery(found)
            }

            override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                val site = resolve(parish, excludedUrls)
                val alreadyRead = wholeMemo[request(parish, excludedUrls)] ?: mapMemo[request(parish, excludedUrls)]
                if (site?.binding?.validFor(parish, site.url, site.evidenceUrl) == true)
                    return ScheduleSourceDiscovery(ScheduleIdentityOutcome.PROVED, site, alreadyRead ?: ScheduleCandidateDiscovery())
                return candidateDiscovery(candidates(parish, excludedUrls))
            }
            override suspend fun resolve(parish: Parish): ScheduleSite? = resolve(parish, emptySet())
            override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
                resolveInSession(parish, excludedUrls, directoryMemo, contactMemo, moscowMemo, osmMemo)
        }
    }

    /** An accepted URL is only a public fetch hint; current bytes still have to create the witness. */
    private fun expectedHintPools(parish: Parish, expected: ScheduleSourceCandidate, pools: OwnContactPoolFactories) = object : OwnContactPoolFactories {
        override suspend fun direct(parish: Parish): PublisherSeedPool {
            val ordinary = pools.direct(parish)
            val owners = pools.curatedOwners().filter { it.latitude != null && it.longitude != null &&
                CuratedPublisherScope.owns(it.website, expected.sourceUrl) }
            val scope = owners.singleOrNull()?.website?.let(ScheduleUrl::publicSource) ?: expected.sourceUrl
            val hints = expected.ownContact?.seedOrigins.orEmpty().filter { it.valid() }.map {
                PublisherSeed(expected.sourceUrl, PublisherSeedRole.CONTACT_REFERENCE, scope, it)
            }.filter { it.valid() }
            return PublisherSeedPool { selected, radius, limit ->
                (hints + ordinary.seeds(selected, radius, limit)).distinct().take(limit)
            }
        }
        override suspend fun websites(parish: Parish) = pools.websites(parish)
        override suspend fun curatedOwners() = pools.curatedOwners()
    }

    private suspend fun resolveInSession(parish: Parish, excludedUrls: Set<String>,
        directoryMemo: MutableMap<String, OfficialDirectorySnapshot?>,
        contactMemo: MutableMap<String, ScheduleSite?>,
        moscowMemo: MutableMap<String, ScheduleSite?>,
        osmMemo: MutableMap<String, JsonObject?>): ScheduleSite? {
        val excluded = ScheduleSourceExclusions(excludedUrls)
        ScheduleUrl.publicSource(parish.website)?.takeUnless(excluded::contains)?.let { return ScheduleSite(it, parish.sourceUrl, parish.name) }
        val tags = osmTags(parish, osmMemo)
        osmObjectUrl(parish)?.let { url -> exactOsmSite(parish, tags, url, excluded)?.let { return it } }
        // These links belong to the exact selected map object and need no broad search inference.
        val linkedTitle = tags?.string("wikipedia")?.takeIf { it.startsWith("ru:") }?.substringAfter(':')
        val linkedTitleCompatible = linkedTitle == null || !ScheduleEntityIdentity.hasExplicitConflict(parish.name, linkedTitle)
        tags?.string("wikidata")?.takeIf { linkedTitleCompatible && Regex("Q[1-9][0-9]{0,12}").matches(it) }?.let { id ->
            fromWikidata(parish, id, parish.name, excluded, ScheduleBindingMethod.EXACT_MAP_LINK)?.let { return it }
        }
        linkedTitle?.takeIf { linkedTitleCompatible }?.let { title ->
            val api = "https://ru.wikipedia.org/w/api.php?action=query&format=json&prop=pageprops&titles=${encode(title)}"
            pages(json(api)).firstNotNullOfOrNull { it.getAsJsonObject("pageprops")?.string("wikibase_item") }
                ?.let { id -> fromWikidata(parish, id, title, excluded, ScheduleBindingMethod.EXACT_MAP_LINK)?.let { return it } }
        }
        moscow?.resolve(parish, excluded, moscowMemo)?.let { return it }
        PalomnikScheduleSiteResolver(transport, childChurchVerifier = MonasteryChurchSiteVerifier(transport, memo = contactMemo))
            .resolve(parish, excludedUrls)?.let { return it }
        directories.resolve(parish, excluded, directoryMemo, contactMemo)?.let { return it }
        PublisherGeometryResolver(transport, memo = contactMemo).resolve(parish, excluded)?.let { return it }
        val latitude = parish.latitude ?: return null
        val longitude = parish.longitude ?: return null
        val distinctive = nameTokens(parish.name)
        if (distinctive.isEmpty() || !latitude.isFinite() || !longitude.isFinite()) return null
        val query = listOf(parish.name, parish.city).filter(String::isNotBlank).joinToString(" ")
        val url = "https://ru.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=0&gsrlimit=5&prop=coordinates%7Cpageprops&gsrsearch=${encode(query)}"
        val candidates = pages(json(url)).mapNotNull { page ->
            val title = page.string("title") ?: return@mapNotNull null
            if (!namesMatch(parish.name, title)) return@mapNotNull null
            if (!ScheduleEntityIdentity.compatible(parish.name, title)) return@mapNotNull null
            val coordinate = page.getAsJsonArray("coordinates")?.firstOrNull()?.asJsonObject ?: return@mapNotNull null
            val lat = coordinate.get("lat")?.asDouble ?: return@mapNotNull null
            val lon = coordinate.get("lon")?.asDouble ?: return@mapNotNull null
            val distance = distanceMeters(latitude, longitude, lat, lon)
            if (distance > 650.0) return@mapNotNull null
            val id = page.getAsJsonObject("pageprops")?.string("wikibase_item") ?: return@mapNotNull null
            Triple(id, title, distance)
        }.sortedBy { it.third }
        // Near-identical nearby names are ambiguous; do not select one by search ranking.
        if (candidates.size > 1 && candidates[1].third - candidates[0].third < 150) return null
        // Select the building before excluding endpoints. A failed website cannot turn a
        // farther namesake into the selected church merely because its website responds.
        val candidate = candidates.firstOrNull() ?: return null
        return fromWikidata(parish, candidate.first, candidate.second, excluded, ScheduleBindingMethod.ARTICLE_SCOPE)
    }

    private suspend fun fromWikidata(parish: Parish, id: String, title: String, excluded: ScheduleSourceExclusions,
        method: ScheduleBindingMethod): ScheduleSite? {
        if (!Regex("Q[1-9][0-9]{0,12}").matches(id)) return null
        val url = "https://www.wikidata.org/wiki/Special:EntityData/$id.json"
        val claims = json(url)?.getAsJsonObject("entities")?.getAsJsonObject(id)?.getAsJsonObject("claims")
            ?.getAsJsonArray("P856") ?: return null
        val active = claims.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            .filter { it.string("rank") != "deprecated" && it.getAsJsonObject("qualifiers")?.has("P582") != true }
            .sortedByDescending { it.string("rank") == "preferred" }
        val sites = active.mapNotNull { claim ->
            claim.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")?.string("value")?.let(ScheduleUrl::publicSource)
        }.distinct()
        val chosen = sites.firstOrNull { !excluded.contains(it) } ?: return null
        val evidence = "https://www.wikidata.org/wiki/$id"
        return ScheduleSite(chosen, evidence, title, ScheduleEntityIdentity.bind(parish, chosen, evidence, title, method))
    }

    private fun candidateDiscovery(found: ScheduleCandidateDiscovery): ScheduleSourceDiscovery {
        val outcome = if (found.candidates.isNotEmpty() && found.candidates.all { it.outcome == ScheduleIdentityOutcome.CONFLICT })
            ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT
        return ScheduleSourceDiscovery(outcome, candidates = found)
    }

    private fun osmObjectUrl(parish: Parish): String? =
        Regex("^osm:(node|way|relation):([1-9][0-9]{0,18})$").matchEntire(parish.id)?.let {
            "https://api.openstreetmap.org/api/0.6/${it.groupValues[1]}/${it.groupValues[2]}.json"
        }

    private fun osmMemoKey(parish: Parish, url: String): String =
        ScheduleEntityIdentity.fingerprint(parish) + "\u0000" + url

    private suspend fun osmTags(parish: Parish, memo: MutableMap<String, JsonObject?>): JsonObject? {
        val url = osmObjectUrl(parish) ?: return null
        val key = osmMemoKey(parish, url)
        if (memo.containsKey(key)) return memo[key]
        val identity = parish.id.split(':')
        val data = json(url)
        val tags = runCatching { data?.getAsJsonArray("elements")?.firstOrNull { item ->
            val value = item.takeIf { it.isJsonObject }?.asJsonObject
            value?.get("type")?.asString == identity[1] && value.get("id")?.asString == identity[2]
        }?.asJsonObject?.getAsJsonObject("tags") }.getOrNull()
        memo[key] = tags
        return tags
    }

    private fun exactOsmSite(parish: Parish, tags: JsonObject?, url: String, excluded: ScheduleSourceExclusions): ScheduleSite? {
        val site = listOf("website", "contact:website").firstNotNullOfOrNull { key ->
            tags?.string(key)?.let(ScheduleUrl::publicSource)?.takeUnless(excluded::contains)
        } ?: return null
        val name = tags?.string("name") ?: parish.name
        return ScheduleSite(site, url, name,
            ScheduleEntityIdentity.bind(parish, site, url, name, ScheduleBindingMethod.EXACT_MAP_LINK))
    }

    private suspend fun json(url: String): JsonObject? = try {
        val response = transport.fetch(url)
        JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun pages(data: JsonObject?): List<JsonObject> = data?.getAsJsonObject("query")?.getAsJsonObject("pages")
        ?.entrySet()?.mapNotNull { it.value.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
        private val common = setOf("храм", "храме", "церк", "собор", "монас", "свято", "святы", "свято", "свят", "препо", "имени", "право", "русск", "мужск", "женск", "москов", "москв")
        internal fun nameTokens(text: String): Set<String> = Regex("[а-яёa-z]{3,}").findAll(text.lowercase(Locale.ROOT))
            .map { it.value.replace('ё', 'е').take(5) }.filter { token -> token != "при" && common.none { token.startsWith(it) } }.toSet()
        internal fun namesMatch(selected: String, candidate: String): Boolean {
            val wanted = nameTokens(selected)
            val found = nameTokens(candidate)
            if (wanted.isEmpty() || found.isEmpty()) return false
            val common = wanted.intersect(found)
            return common.isNotEmpty() && common.size.toDouble() / min(wanted.size, found.size) >= .65
        }
        internal fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
            val lat = Math.toRadians(c - a); val lon = Math.toRadians(d - b)
            val value = sin(lat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(lon / 2).pow(2)
            return 6_371_000 * 2 * atan2(sqrt(value.coerceIn(0.0, 1.0)), sqrt((1 - value).coerceIn(0.0, 1.0)))
        }
    }
}
