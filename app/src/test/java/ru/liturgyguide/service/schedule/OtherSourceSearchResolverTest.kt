package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OtherSourceSearchResolverTest {
    private val f = ContactWiringFixtures
    private val selected get() = f.selected()
    private fun map() = f.mapCandidate("http://www.hram-elisaveta.ru/").copy(provider = OfficialMapCandidateProvider.PROVIDER)
    private fun state(extra: List<ScheduleSourceCandidate> = emptyList(), dismissed: List<String> = emptyList()) =
        InternetScheduleState(selected.id, status = InternetScheduleStatus.NO_WEBSITE, sourceCandidates = listOf(map()) + extra,
            checkedAt = f.now.toString(), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected), dismissedCandidateIds = dismissed)
    private fun useful(base: InternetScheduleState): (ScheduleCandidateDiscovery) -> Boolean = { fresh ->
        hasActionableOwnSearchCandidate(selected, base, fresh,
            mergeCandidateEvidence(selected, base.sourceCandidates, fresh.candidates), emptySet())
    }

    @Test fun readyMapAndDismissedDirectAWaitForALaterEligibleDirectBInsideOneOwnAttempt() = runBlocking<Unit> {
        val a = "https://first.example/contacts"; val b = "https://second.example/contacts"
        val aRow = f.fresh(a, "https://first.example/").candidates.single()
        val before = state(listOf(aRow), listOf(aRow.id)); val calls = CopyOnWriteArrayList<String>()
        var mapCompletions = 0; var ownCompletions = 0; var directLoads = 0; var websiteLoads = 0
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool {
                directLoads++; return LocalPublisherSeedPool(listOf(f.seed(a, "https://first.example/"), f.seed(b, "https://second.example/")))
            }
            override suspend fun websites(parish: Parish): PublisherSeedPool { websiteLoads++; error("Eligible direct B already found") }
            override suspend fun curatedOwners() = f.owners
        }
        val transport = ScheduleTransport { url -> calls += url; if (url == b) delay(40); f.response(url) }
        val session = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map())) }, pools).forRefresh()
        val result = session.candidatesForSourceSearch(selected, emptySet(), setOf(aRow.id), useful(before)) { batch ->
            if (batch.candidates.any { it.provider == OwnContactCandidateProvider.PROVIDER }) ownCompletions++ else mapCompletions++
        }
        assertEquals(1, mapCompletions); assertEquals(1, ownCompletions)
        assertEquals(1, directLoads); assertEquals(0, websiteLoads)
        assertEquals(setOf(a, b), calls.toSet())
        assertTrue(result.candidates.any { it.sourceUrl == b && it.canConfirm(selected) })
        assertTrue(result.candidates.none { it.id == aRow.id })
        assertTrue(result.candidates.any { it.id == map().id })
    }

    @Test fun dismissedDirectAAllowsWebsiteBAndContextualSearchDoesNotReuseAnotherExclusionMemo() = runBlocking<Unit> {
        val aHome = "https://shared.example/a/"; val a = aHome + "contacts"
        val bHome = "https://shared.example/b/"; val b = bHome + "contacts"
        val aRow = f.fresh(a, aHome).candidates.single(); val bRow = f.fresh(b, bHome).candidates.single()
        val calls = CopyOnWriteArrayList<String>(); var directLoads = 0; var websiteLoads = 0; var maps = 0
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool {
                directLoads++; return LocalPublisherSeedPool(listOf(f.seed(a, aHome)))
            }
            override suspend fun websites(parish: Parish): PublisherSeedPool {
                websiteLoads++; return LocalPublisherSeedPool(listOf(f.seed(bHome, bHome, PublisherSeedRole.WEBSITE)))
            }
            override suspend fun curatedOwners() = f.owners
        }
        val transport = ScheduleTransport { url -> calls += url; if (url == bHome)
            f.response(url, "<html><body><nav><a href='contacts'>Контакты</a></nav></body></html>".toByteArray()) else f.response(url) }
        val session = f.resolver(transport, ScheduleCandidateProvider { _, _ -> maps++; ScheduleCandidateDiscovery(listOf(map())) }, pools).forRefresh()
        val first = session.candidatesForSourceSearch(selected, emptySet(), setOf(aRow.id),
            useful(state(listOf(aRow), listOf(aRow.id)))) { }
        assertTrue(first.candidates.any { it.id == bRow.id }); assertTrue(calls.containsAll(listOf(a, bHome, b)))
        val second = session.candidatesForSourceSearch(selected, emptySet(), setOf(bRow.id),
            useful(state(listOf(bRow), listOf(bRow.id)))) { }
        assertTrue(second.candidates.any { it.id == aRow.id }); assertTrue(second.candidates.none { it.id == bRow.id })
        assertEquals(2, directLoads); assertEquals(1, websiteLoads); assertEquals(1, maps)
        assertEquals(2, calls.count { it == a })
    }

    @Test fun contextualExclusionsNeverLeakIntoASeparateExpectedOwnAReview() = runBlocking<Unit> {
        val a = f.fresh().candidates.single(); val bHome = "https://other.example/"; val bUrl = bHome + "contacts"
        var aReads = 0
        val aEntered = CompletableDeferred<Unit>()
        val transport = ScheduleTransport { url ->
            if (url == f.contact) { aReads++; aEntered.complete(Unit); delay(40) }
            else if (url == bUrl) aEntered.await()
            f.response(url)
        }
        val session = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map())) },
            f.pools(listOf(f.seed(), f.seed(bUrl, bHome)))).forRefresh()
        val first = session.candidatesForSourceSearch(selected, emptySet(), setOf(a.id),
            useful(state(listOf(a), listOf(a.id)))) { }
        assertTrue(first.candidates.none { it.id == a.id }); assertTrue(first.candidates.any { it.sourceUrl == bUrl })
        val reviewed = session.candidatesForConfirmation(selected, emptySet(), a)
        val exact = reviewed.candidates.single { it.id == a.id }
        val proof = requireNotNull(reviewed.freshContactEvidence?.get(a.id))
        assertTrue(exact.canConfirm(selected)); assertTrue(proof.matchesFresh(selected, exact)); assertEquals(2, aReads)
    }

    @Test fun aMissingExpectedOwnACannotBeReplacedByBOrTheReadyMap() = runBlocking<Unit> {
        val a = f.fresh().candidates.single(); val bHome = "https://other.example/"; val b = bHome + "contacts"
        val cache = ContactWiringFixtures.Cache()
        val before = state(listOf(a))
        assertTrue(a.canConfirm(selected))
        cache.write(selected.id, ScheduleCacheCodec.encode(before))
        assertEquals(before, ScheduleCacheCodec.decode(cache.read(selected.id), selected))
        var imported = false
        val requests = CopyOnWriteArrayList<String>()
        val transport = ScheduleTransport { url -> requests += url; when (url) {
            f.contact -> f.response(url, "<html><body>Контакты без выбранного храма</body></html>".toByteArray())
            b -> f.response(url)
            else -> { imported = true; error("No import when expected A is absent: $url") }
        } }
        val resolver = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map())) },
            f.pools(listOf(f.seed(b, bHome))))
        val result = InternetScheduleRepository(transport, cache, siteResolver = resolver).confirmCandidate(selected, a.id, f.now)
        assertNull(result.publication); assertNull(result.siteBinding); assertFalse(imported)
        assertTrue(result.candidateSearchUnavailable)
        assertTrue(requests.containsAll(listOf(f.contact, b)))
        assertTrue(result.sourceCandidates.any { it.sourceUrl == b && it.canConfirm(selected) })
    }

    @Test fun eitherFailedProviderRetainsItsCompletedNeighborWithExactlyOneMapAndOwnPool() = runBlocking<Unit> {
        for (mapFails in listOf(false, true)) {
            var mapCalls = 0; var directCalls = 0
            val pools = object : OwnContactPoolFactories {
                override suspend fun direct(parish: Parish): PublisherSeedPool {
                    directCalls++
                    if (!mapFails) throw IOException("Own metadata temporarily unavailable")
                    return LocalPublisherSeedPool(listOf(f.seed()))
                }
                override suspend fun websites(parish: Parish): PublisherSeedPool = error("No second Own attempt")
                override suspend fun curatedOwners() = f.owners
            }
            val maps = ScheduleCandidateProvider { _, _ -> mapCalls++
                if (mapFails) throw IOException("Map temporarily unavailable")
                ScheduleCandidateDiscovery(listOf(map()))
            }
            val result = f.resolver(ScheduleTransport { f.response(it) }, maps, pools).forRefresh()
                .candidatesForSourceSearch(selected, emptySet(), emptySet(), useful(state())) { }
            assertEquals(1, mapCalls); assertEquals(1, directCalls); assertTrue(result.unavailable)
            assertTrue(result.candidates.any { if (mapFails) it.provider == OwnContactCandidateProvider.PROVIDER else it.id == map().id })
        }
    }

    @Test fun wholeSourceSearchKeepsTheSharedSixDispatchThreePublisherTwoWorkerBudget() = runBlocking<Unit> {
        val roots = (1..4).map { "https://publisher$it.example/" }
        val calls = CopyOnWriteArrayList<String>(); val workers = AtomicInteger(); val peak = AtomicInteger()
        var directLoads = 0; var websiteLoads = 0
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool {
                directLoads++; return LocalPublisherSeedPool(roots.map { f.seed(it + "contacts", it) })
            }
            override suspend fun websites(parish: Parish): PublisherSeedPool {
                websiteLoads++; return LocalPublisherSeedPool(roots.map { f.seed(it, it, PublisherSeedRole.WEBSITE) })
            }
            override suspend fun curatedOwners() = f.owners
        }
        val transport = ScheduleTransport { url ->
            calls += url; val active = workers.incrementAndGet(); peak.updateAndGet { maxOf(it, active) }
            try {
                delay(15)
                f.response(url, if (url.endsWith("contacts")) "<html><body>Без нужного храма</body></html>".toByteArray()
                    else "<html><body><nav><a href='contacts'>Контакты</a></nav></body></html>".toByteArray())
            } finally { workers.decrementAndGet() }
        }
        val result = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map())) }, pools).forRefresh()
            .candidatesForSourceSearch(selected, emptySet(), emptySet(), useful(state())) { }
        assertEquals(1, directLoads); assertEquals(1, websiteLoads)
        assertEquals(6, calls.size); assertEquals(3, calls.map { java.net.URI(it).host }.distinct().size)
        assertFalse(calls.any { it.startsWith(roots.last()) }); assertTrue(peak.get() <= 2); assertEquals(0, workers.get())
        assertTrue(result.candidates.any { it.id == map().id })
    }
}
