package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exact saved primary bytes, actual production Map/Own providers, no live request and no fabricated schedule. */
class ActualMapOtherSourceReplayTest {
    private val f = ContactWiringFixtures
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/manual-other-source-search/$name")).use { it.readBytes() }
    private val manifest by lazy { JsonParser.parseString(bytes("manifest.json").toString(Charsets.UTF_8)).asJsonObject }
    private val maps by lazy { manifest["map"].asJsonArray.map { it.asJsonObject } }
    private val legacy by lazy { manifest["legacy"].asJsonArray.map { it.asJsonObject } }
    private fun redacted(url: String) = url.replace(Regex("([?&]token=)[^&]*"), "$1REDACTED")
    private inner class Replay {
        val requests = CopyOnWriteArrayList<String>()
        val direct = AtomicInteger(); val website = AtomicInteger()
        val cache = ContactWiringFixtures.Cache()
        val contextualSearches = AtomicInteger(); val scheduleRequests = AtomicInteger()
        var scheduleAvailable = false
        var contactBytes = f.bytes("contacts.html")
        private val linked = LinkedNavigationFixtures
        private val documents by lazy { linked.documents() }
        private val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { direct.incrementAndGet(); return f.pools().direct(parish) }
            override suspend fun websites(parish: Parish): PublisherSeedPool { website.incrementAndGet(); error("A direct contact is sufficient") }
            override suspend fun curatedOwners() = f.owners
        }
        private fun response(row: JsonObject, requested: String): ScheduleResponse {
            val body = bytes(row["asset"].asString)
            assertEquals(row["sha256"].asString, ScheduleSourceCandidate.sha(body)); assertEquals(row["bytes"].asInt, body.size)
            val url = row["responseUrl"].asString.let { if (it.contains("token=REDACTED")) requested else it }
            return ScheduleResponse(url, body, row["contentType"].asString)
        }
        private val transport = ScheduleTransport { url ->
            requests += redacted(url)
            val map = maps.singleOrNull { redacted(it["requestedUrl"].asString) == redacted(url) }
            val old = legacy.firstOrNull { it["requestedUrl"].asString == url || it["responseUrl"].asString == url }
            when {
                map != null -> response(map, url)
                old != null -> response(old, url)
                url == f.contact -> f.response(url, contactBytes)
                url == linked.target -> {
                    scheduleRequests.incrementAndGet()
                    if (!scheduleAvailable) throw IOException("Transient failure of the accepted Own schedule leaf")
                    linked.response()
                }
                documents.any { it.sourceUrl == url } -> {
                    val index = documents.indexOfFirst { it.sourceUrl == url } + 1
                    ScheduleResponse(url, linked.bytes("image-$index.jpg"), "image/jpeg")
                }
                else -> throw IOException("No saved response for this request; the replay has no live network")
            }
        }
        private val productionResolver = OpenScheduleSiteResolver(transport, ownPools = pools, clock = f.clock)
        private val recordedResolver = object : ScheduleSiteResolver by productionResolver {
            override fun forRefresh(): ScheduleSiteResolver {
                val session = productionResolver.forRefresh()
                return object : ScheduleSiteResolver by session {
                    override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
                        useful: (ScheduleCandidateDiscovery) -> Boolean,
                        onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit): ScheduleCandidateDiscovery {
                        contextualSearches.incrementAndGet()
                        return session.candidatesForSourceSearch(parish, excludedUrls, excludedCandidateIds, useful, onProviderCompleted)
                    }
                }
            }
        }
        val repository = InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response ->
            val index = documents.indexOfFirst { it.sourceUrl == response.url }
            check(index >= 0)
            assertArrayEquals(linked.bytes("image-${index + 1}.jpg"), response.bytes)
            documents[index]
        }, siteResolver = recordedResolver, curatedPublishers = { f.owners })
    }

    @Test fun actualSevenResponsesKeepTheThreeFastOffersThenOneManualActionAddsFreshIndependentOwn() = runBlocking<Unit> {
        val replay = Replay(); val before = replay.repository.refresh(f.selected(), f.now, force = true)
        assertEquals(7, replay.requests.size); assertEquals(3, before.sourceCandidates.size)
        assertEquals(setOf(OfficialMapCandidateProvider.PROVIDER, ElitsyMapCandidateProvider.PROVIDER), before.sourceCandidates.map { it.provider }.toSet())
        assertTrue(before.sourceCandidates.all { it.canConfirm(f.selected()) && it.ownContact == null })
        assertEquals(0, replay.direct.get()); assertEquals(0, replay.website.get())
        val result = replay.repository.searchOtherSources(f.selected(), f.now.plusSeconds(1))
        assertEquals(before.withoutSearchMetadata(), result.withoutSearchMetadata())
        assertEquals(1, replay.direct.get()); assertEquals(0, replay.website.get())
        assertEquals(1, replay.requests.count { it == f.contact })
        assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) })
        assertNull(result.siteBinding); assertNull(result.publication)
        assertFalse(legacy.any { row -> replay.requests.contains(row["requestedUrl"].asString) })
    }

    @Test fun bothActualFailedLegacyRootsCanFindOwnWithoutReplacingTheirBindingOrHistory() = runBlocking<Unit> {
        for (row in legacy) {
            val replay = Replay(); val initial = replay.repository.refresh(f.selected(), f.now, force = true)
            val selected = initial.sourceCandidates.first { it.sourceUrl == row["requestedUrl"].asString }
            val failed = replay.repository.confirmCandidate(f.selected(), selected.id, f.now.plusSeconds(1))
            assertTrue(replay.requests.contains(row["requestedUrl"].asString)); assertNull(failed.publication)
            assertEquals(0, replay.direct.get()); assertTrue(permitsOtherSourceSearch(f.selected(), failed))
            val result = replay.repository.searchOtherSources(f.selected(), f.now.plusSeconds(2))
            assertEquals(failed.withoutSearchMetadata(), result.withoutSearchMetadata())
            assertEquals(failed.checkedAt, result.checkedAt); assertEquals(failed.siteBinding, result.siteBinding)
            assertEquals(failed.publicationRejected, result.publicationRejected)
            assertEquals(failed.sourceNeedsVerification, result.sourceNeedsVerification)
            assertEquals(failed.sourceHasWithdrawals, result.sourceHasWithdrawals)
            assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) })
            assertEquals(1, replay.direct.get()); assertEquals(0, replay.website.get()); assertNull(result.publication)
        }
    }
    @Test fun existingRefreshRetriesAnAcceptedOwnAfterLeafFailureAndUsesItsOrdinaryDurableRecheck() = runBlocking<Unit> {
        for (recovery in listOf(true, false)) {
            val replay = Replay()
            replay.repository.refresh(f.selected(), f.now, force = true)
            val offers = replay.repository.searchOtherSources(f.selected(), f.now.plusSeconds(1))
            val own = offers.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) }
            val failed = replay.repository.confirmCandidate(f.selected(), own.id, f.now.plusSeconds(2))
            assertNull(failed.publication)
            assertEquals(OwnContactCandidateProvider.PROVIDER, failed.siteBinding?.candidateConfirmation?.candidate?.provider)
            assertTrue(requireNotNull(failed.siteBinding).validFor(f.selected(), failed.website, failed.siteEvidenceUrl))
            assertTrue(failed.sourceCandidates.any { it.provider in setOf(OfficialMapCandidateProvider.PROVIDER,
                ElitsyMapCandidateProvider.PROVIDER) && it.validFor(f.selected()) })
            assertFalse("Retained Map facts cannot intercept an accepted Own retry", permitsOtherSourceSearch(f.selected(), failed))
            assertEquals(1, replay.contextualSearches.get()); assertEquals(1, replay.scheduleRequests.get())
            if (recovery) replay.scheduleAvailable = true else {
                // Controlled current-source mutation, not a claim that the real page changed.
                val document = Jsoup.parse(replay.contactBytes.toString(Charsets.UTF_8), f.contact)
                document.select(".t195__text").single { it.text().contains("Приписные храмы") }.select("span")
                    .single { it.text().startsWith("Храм в честь") }.text("Храм в честь иконы Божией Матери Казанская")
                replay.contactBytes = document.outerHtml().toByteArray()
            }
            val retried = replay.repository.searchOtherSources(f.selected(), f.now.plusSeconds(3))
            assertEquals("The existing action must use ordinary refresh, not another metadata whole", 1, replay.contextualSearches.get())
            assertTrue(replay.requests.count { it == f.contact } >= 3)
            if (recovery) {
                assertEquals(2, replay.scheduleRequests.get()); assertEquals(InternetScheduleStatus.READY, retried.status)
                assertEquals(LinkedNavigationFixtures.certifiedParses().last().events, requireNotNull(retried.publication).events)
                assertEquals(12, requireNotNull(retried.publication).events.size)
                assertEquals(own.id, retried.siteBinding?.candidateConfirmation?.candidate?.id)
                assertTrue(replay.cache.safety.isEmpty())
            } else {
                assertEquals("A fresh Own conflict stops before retrying the leaf", 1, replay.scheduleRequests.get())
                assertNull(retried.siteBinding); assertNull(retried.publication); assertTrue(retried.sourceNeedsVerification)
                assertTrue(retried.publicationRejected); assertTrue(replay.cache.safety.isNotEmpty())
                val cold = requireNotNull(ScheduleCacheCodec.decode(replay.cache.read(f.selected().id), f.selected()))
                assertNull(cold.siteBinding); assertNull(cold.publication); assertTrue(cold.sourceNeedsVerification)
                assertTrue(cold.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER &&
                    it.sourceUrl == f.contact && it.outcome == ScheduleIdentityOutcome.CONFLICT })
            }
        }
    }

}
