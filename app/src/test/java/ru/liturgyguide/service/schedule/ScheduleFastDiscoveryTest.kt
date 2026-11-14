package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real production session/repository ordering, with controlled HTTP and no wall-clock performance oracle. */
class ScheduleFastDiscoveryTest {
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val source = "https://parish.example/"
    private val selected = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва", "Осенняя улица, 32 с1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    private val osmUrl = "https://api.openstreetmap.org/api/0.6/way/42.json"
    private val html = """<html><body><h1>Храм святителя Николая</h1><article data-day="2026-11-13">
        <li><time>09:00</time><span>Литургия</span></li></article></body></html>"""
    private fun response(url: String, value: String) = ScheduleResponse(url, value.toByteArray(), "text/html; charset=UTF-8")
    private fun osm(tags: String = "") = """{"elements":[{"type":"way","id":42,"tags":{$tags}}]}"""
    private fun entity(url: String = source) = """{"entities":{"Q42":{"claims":{"P856":[
        {"rank":"normal","mainsnak":{"datavalue":{"value":"$url"}}}]}}}}"""
    private fun candidate() = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(selected),
        provider = "public_index", providerLabel = "Публичный справочник", providerObjectId = "42", name = selected.name,
        address = selected.address, sourceUrl = source, evidenceUrl = "https://index.example/church/42",
        evidenceSha256 = "a".repeat(64), checkedAt = now.minusSeconds(10).toString(), latitude = selected.latitude!!,
        longitude = selected.longitude!!, outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте источник")
    private inner class Cache : ScheduleCacheStore {
        val values = ConcurrentHashMap<String, String>()
        val writes = AtomicInteger()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value; writes.incrementAndGet() }
        fun seed(state: InternetScheduleState) {
            val raw = ScheduleCacheCodec.encode(state)
            assertEquals("Persisted fixture must survive the real cache codec", state, ScheduleCacheCodec.decode(raw, selected))
            values[state.parishId] = raw
        }
    }
    private fun pending(vararg rows: ScheduleSourceCandidate) = InternetScheduleState(selected.id,
        checkedAt = now.minusSeconds(301).toString(),
        sourceCandidates = rows.toList(), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected))

    @Test fun freshActionableCandidateReturnsBeforeEnteringBlockedHeavyResolveAndBeforeAnySourceImport() = runBlocking {
        val calls = CopyOnWriteArrayList<String>(); val heavy = CompletableDeferred<Unit>(); val batches = AtomicInteger()
        val c = candidate(); val cache = Cache()
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            if (url == osmUrl) response(url, osm("\"wikidata\":\"Q42\""))
            else { heavy.complete(Unit); awaitCancellation() }
        }, candidateProvider = ScheduleCandidateProvider { _, _ ->
            batches.incrementAndGet(); ScheduleCandidateDiscovery(listOf(c), listOf("42"), incomplete = true)
        })
        val repo = InternetScheduleRepository(ScheduleTransport { error("A suggestion must not import its source") }, cache, siteResolver = resolver)
        val result = withTimeout(5_000) { repo.refresh(selected, now) }
        assertEquals(listOf(osmUrl), calls.toList()); assertEquals(1, batches.get()); assertFalse(heavy.isCompleted)
        assertEquals(InternetScheduleStatus.NO_WEBSITE, result.status); assertFalse(result.loading)
        assertEquals(c, result.sourceCandidates.single()); assertEquals(listOf("42"), result.candidateObservedIds)
        assertNull(result.publication); assertNull(result.siteBinding); assertEquals("", result.website)
        assertEquals(result, repo.cached(selected)); assertEquals(ScheduleImportPhase.FINISHED, repo.progress.value.phase)
    }

    @Test fun realCompositeThirtySecondTimeoutRetainsReadyNeighborAndMemoWithoutEnteringHeavyGraph() = runBlocking {
        val c = candidate().copy(provider = "source_a")
        val calls = CopyOnWriteArrayList<String>(); val aCalls = AtomicInteger(); val bCalls = AtomicInteger()
        val batchCalls = AtomicInteger(); val bCancelled = AtomicInteger()
        val a = ScheduleCandidateProvider { _, _ ->
            aCalls.incrementAndGet(); ScheduleCandidateDiscovery(listOf(c), listOf("42"), incomplete = false)
        }
        val b = ScheduleCandidateProvider { _, _ ->
            if (bCalls.incrementAndGet() == 1) {
                // Exercise the actual default 30s Composite deadline. Do not shorten production
                // deadlines to test this nested-timeout regression or fabricate the merged result.
                try { awaitCancellation() } finally { bCancelled.incrementAndGet() }
            } else ScheduleCandidateDiscovery(unavailable = true)
        }
        val composite = CompositeScheduleCandidateProvider(listOf("source_a" to a, "source_b" to b))
        val provider = ScheduleCandidateProvider { parish, exclusions ->
            batchCalls.incrementAndGet(); composite.discover(parish, exclusions)
        }
        val http = ScheduleTransport { url ->
            calls += url
            if (url == osmUrl) response(url, osm()) else error("A ready neighbor must precede heavy lookup and source import")
        }
        val resolver = OpenScheduleSiteResolver(http, candidateProvider = provider)
        val session = resolver.forRefresh()
        val repository = InternetScheduleRepository(http, Cache(), siteResolver = session)
        val result = withTimeout(45_000) { repository.refresh(selected, now) }
        assertEquals(listOf(osmUrl), calls.toList())
        assertEquals(listOf(c), result.sourceCandidates); assertTrue(result.sourceCandidates.single().canConfirm(selected))
        assertTrue(result.candidateSearchUnavailable); assertTrue(result.candidateSearchIncomplete)
        assertEquals(listOf("source_a:42"), result.candidateObservedIds)
        assertFalse(result.loading); assertNull(result.publication); assertNull(result.siteBinding); assertEquals("", result.website)
        assertEquals(1, aCalls.get()); assertEquals(1, bCalls.get()); assertEquals(1, batchCalls.get()); assertEquals(1, bCancelled.get())
        assertEquals(ScheduleImportPhase.FINISHED, repository.progress.value.phase)

        val repeated = requireNotNull(session.initialFastDiscovery(selected, emptySet())).candidates
        assertEquals(listOf(c), repeated.candidates); assertTrue(repeated.unavailable)
        assertEquals(listOf("source_a:42"), repeated.observedIds)
        assertEquals(1, aCalls.get()); assertEquals(1, bCalls.get()); assertEquals(1, batchCalls.get())
        assertEquals(listOf(osmUrl), calls.toList())

        // A fresh session must really call both providers again. Only this second B invocation
        // returns unavailable immediately; the regression above used its real default deadline.
        val fresh = requireNotNull(resolver.forRefresh().initialFastDiscovery(selected, emptySet())).candidates
        assertEquals(listOf(c), fresh.candidates); assertTrue(fresh.unavailable)
        assertEquals(2, aCalls.get()); assertEquals(2, bCalls.get()); assertEquals(2, batchCalls.get())
        assertEquals(1, bCancelled.get()); assertEquals(listOf(osmUrl, osmUrl), calls.toList())
    }

    @Test fun exactOsmWebsiteRetainsAutomaticProofAndNeverStartsWeakProviders() = runBlocking {
        for (tag in listOf("website", "contact:website")) {
            val calls = CopyOnWriteArrayList<String>(); val cache = Cache()
            val http = ScheduleTransport { url -> calls += url; response(url, when (url) {
                osmUrl -> osm("\"$tag\":\"$source\"")
                source -> html
                else -> error("Heavy lookup must not run")
            }) }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ -> error("No weak batch") })
            val ready = InternetScheduleRepository(http, cache, siteResolver = resolver).refresh(selected, now)
            assertEquals(InternetScheduleStatus.READY, ready.status)
            assertEquals(ScheduleBindingMethod.EXACT_MAP_LINK, ready.siteBinding?.method)
            assertEquals(listOf(osmUrl, source), calls.toList()); assertEquals("09:00", ready.publication?.events?.single()?.time)
        }
    }

    @Test fun dismissedOnlyBatchFallsThroughToStrongProofWithoutRepeatingOsmOrProviderAndKeepsPartialMetadata() = runBlocking {
        val c = candidate(); val cache = Cache().apply { seed(pending(c).copy(dismissedCandidateIds = listOf(c.id))) }
        val calls = CopyOnWriteArrayList<String>(); val batches = AtomicInteger()
        val http = ScheduleTransport { url -> calls += url; response(url, when {
            url == osmUrl -> osm("\"wikidata\":\"Q42\"")
            "wikidata.org" in url -> entity()
            url == source -> html
            else -> error("Unexpected heavy lookup")
        }) }
        val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ ->
            batches.incrementAndGet(); ScheduleCandidateDiscovery(listOf(c), listOf("42"), incomplete = false, unavailable = true)
        })
        val ready = InternetScheduleRepository(http, cache, siteResolver = resolver).refresh(selected, now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status); assertEquals(ScheduleBindingMethod.EXACT_MAP_LINK, ready.siteBinding?.method)
        assertEquals(1, calls.count { it == osmUrl }); assertEquals(1, batches.get())
        assertEquals(1, calls.count { "wikidata.org" in it }); assertEquals(1, calls.count { it == source })
        assertEquals(listOf(c.id), ready.dismissedCandidateIds); assertTrue(ready.candidateSearchUnavailable)
        assertFalse(ready.candidateSearchIncomplete); assertEquals(listOf("42"), ready.candidateObservedIds)
    }

    @Test fun noUsableRowsReachHeavyFallbackAndTheSameFailedCandidateBatchIsNotRepeated() = runBlocking {
        for (found in listOf(ScheduleCandidateDiscovery(incomplete = false), ScheduleCandidateDiscovery(unavailable = true),
            ScheduleCandidateDiscovery(listOf(candidate().copy(outcome = ScheduleIdentityOutcome.CONFLICT))),
            ScheduleCandidateDiscovery(listOf(candidate().copy(verificationUnavailable = true))))) {
            val calls = CopyOnWriteArrayList<String>(); val batches = AtomicInteger()
            val http = ScheduleTransport { url -> calls += url; response(url, if (url == osmUrl) osm() else "{}") }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ -> batches.incrementAndGet(); found })
            val result = InternetScheduleRepository(http, Cache(), siteResolver = resolver).refresh(selected, now)
            assertTrue("Heavy graph must still be entered for unusable rows", calls.any { it != osmUrl })
            assertEquals(1, calls.count { it == osmUrl }); assertEquals(1, batches.get())
            assertEquals(found.unavailable, result.candidateSearchUnavailable); assertEquals(found.incomplete, result.candidateSearchIncomplete)
            assertNull(result.publication)
        }
    }

    @Test fun boundedExactOsmTimeoutAllowsCandidateOrHeavyFallbackWithoutRetryingTheSameOsmGet() = runBlocking {
        for (actionable in listOf(true, false)) {
            val cancelled = AtomicBoolean(); val calls = CopyOnWriteArrayList<String>(); val c = candidate(); val batches = AtomicInteger()
            val http = ScheduleTransport { url -> calls += url
                if (url == osmUrl) try { awaitCancellation() } finally { cancelled.set(true) }
                else response(url, "{}")
            }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ ->
                batches.incrementAndGet(); ScheduleCandidateDiscovery(if (actionable) listOf(c) else emptyList())
            })
            val result = withTimeout(8_000) { InternetScheduleRepository(http, Cache(), siteResolver = resolver).refresh(selected, now) }
            assertTrue(cancelled.get()); assertEquals(1, calls.count { it == osmUrl }); assertEquals(1, batches.get())
            if (actionable) {
                assertEquals(listOf(osmUrl), calls.toList()); assertEquals(c, result.sourceCandidates.single())
            } else {
                assertTrue(calls.any { it != osmUrl }); assertTrue(result.sourceCandidates.isEmpty())
            }
        }
    }

    @Test fun malformedSelectedMapElementsDoNotSuppressTheExistingPublicCandidatePath() = runBlocking {
        for (body in listOf("{}", "{\"elements\":{}}", "{\"elements\":[{\"type\":\"node\",\"id\":42,\"tags\":{\"website\":\"https://wrong.example/\"}}]}",
            "{\"elements\":[{\"type\":\"way\",\"id\":43,\"tags\":{\"website\":\"https://wrong.example/\"}}]}")) {
            val calls = CopyOnWriteArrayList<String>(); val c = candidate()
            val http = ScheduleTransport { url -> calls += url; response(url, body) }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(c)) })
            val result = InternetScheduleRepository(http, Cache(), siteResolver = resolver).refresh(selected, now)
            assertEquals(c, result.sourceCandidates.single()); assertNull(result.siteBinding); assertEquals(listOf(osmUrl), calls.toList())
        }
    }

    @Test fun osmAndCandidateMemoAreAttemptFingerprintAndExactExclusionScoped() = runBlocking {
        val batches = AtomicInteger(); val calls = CopyOnWriteArrayList<String>()
        val http = ScheduleTransport { url -> calls += url; response(url, osm()) }
        val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { parish, _ ->
            batches.incrementAndGet(); ScheduleCandidateDiscovery(listOf(candidate().copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(parish))))
        })
        val session = resolver.forRefresh()
        session.initialFastDiscovery(selected, emptySet()); session.initialFastDiscovery(selected, emptySet())
        assertEquals(1, batches.get()); assertEquals(1, calls.size)
        session.candidates(selected, setOf(source)); assertEquals(2, batches.get())
        session.initialFastDiscovery(selected.copy(latitude = selected.latitude!! + .00001), emptySet())
        assertEquals(3, batches.get()); assertEquals(2, calls.size)
        resolver.forRefresh().initialFastDiscovery(selected, emptySet())
        assertEquals(4, batches.get()); assertEquals(3, calls.size)
    }

    @Test fun exactOsmPrimaryFailureRetainsThePermittedAlternativeExclusionsAndSharedMemo() = runBlocking {
        val alternative = "https://other-own.example/"; val calls = CopyOnWriteArrayList<String>()
        val http = ScheduleTransport { url -> calls += url; response(url, when {
            url == osmUrl -> osm("\"website\":\"$source\",\"wikidata\":\"Q42\"")
            url == source -> "<html><body>Временно недоступно</body></html>"
            "wikidata.org" in url -> entity(alternative)
            url == alternative -> html
            else -> "{}"
        }) }
        val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ -> error("Both strong paths precede candidates") })
        val result = InternetScheduleRepository(http, Cache(), siteResolver = resolver).refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, result.status); assertEquals(alternative, result.website)
        assertEquals(1, calls.count { it == osmUrl }); assertEquals(1, calls.count { it == source }); assertEquals(1, calls.count { it == alternative })
    }

    @Test fun rawAndMergedEligibilityRejectStaleDuplicateMalformedDisplacedAndUnusableRows() {
        val c = candidate(); val state = pending(c)
        assertTrue(hasActionableFastCandidate(selected, state, ScheduleCandidateDiscovery(listOf(c), unavailable = true), listOf(c), emptySet()))
        val unavailable = c.copy(verificationUnavailable = true)
        val wrong = c.copy(selectedIdentity = "b".repeat(64))
        val other = (1..20).map { c.copy(providerObjectId = "other-$it", outcome = ScheduleIdentityOutcome.CONFLICT, sourceUrl = "https://other-$it.example/") }
        for ((raw, merged) in listOf(emptyList<ScheduleSourceCandidate>() to listOf(c), listOf(c, c) to listOf(c), listOf(wrong) to listOf(c),
            listOf(unavailable) to listOf(c), listOf(c) to listOf(unavailable), listOf(c) to emptyList(), listOf(c) to (listOf(c, c)),
            listOf(c) to mergeCandidateEvidence(selected, other, listOf(c)))) {
            assertFalse(hasActionableFastCandidate(selected, state, ScheduleCandidateDiscovery(raw), merged, emptySet()))
        }
        assertFalse(hasActionableFastCandidate(selected, state.copy(dismissedCandidateIds = listOf(c.id)), ScheduleCandidateDiscovery(listOf(c)), listOf(c), emptySet()))
        assertFalse(hasActionableFastCandidate(selected, state.copy(sourceHasWithdrawals = true), ScheduleCandidateDiscovery(listOf(c)), listOf(c), emptySet()))
        assertFalse(hasActionableFastCandidate(selected, state, ScheduleCandidateDiscovery(listOf(c)), listOf(c), setOf(source)))
    }

    @Test fun crossProviderConflictUsesActualMergedPathScopeRatherThanWholeHost() {
        val c = candidate().copy(sourceUrl = "https://shared.example/church-b/")
        val conflict = c.copy(provider = "another_index", providerObjectId = "other", sourceUrl = "https://shared.example/church-a/",
            outcome = ScheduleIdentityOutcome.CONFLICT)
        val fresh = ScheduleCandidateDiscovery(listOf(c, conflict))
        assertTrue(hasActionableFastCandidate(selected, pending(), fresh, listOf(c, conflict), emptySet()))
        val covering = conflict.copy(sourceUrl = "https://shared.example/church-b/")
        assertFalse(hasActionableFastCandidate(selected, pending(), fresh.copy(candidates = listOf(c, covering)), listOf(c, covering), emptySet()))
        val oldConflict = c.copy(outcome = ScheduleIdentityOutcome.CONFLICT)
        val merged = mergeCandidateEvidence(selected, listOf(oldConflict), listOf(c))
        assertFalse(hasActionableFastCandidate(selected, pending(), ScheduleCandidateDiscovery(listOf(c)), merged, emptySet()))
    }

    @Test fun unavailableFreshContactCannotBorrowAnEarlierSuccessfulContactFromMerge() {
        val contact = candidate().copy(currentContactAddress = selected.address, currentContactUrl = source + "contacts/",
            currentContactSha256 = "c".repeat(64), linkedEmail = "own@example.org")
        val failed = contact.copy(currentContactAddress = "", currentContactUrl = "", currentContactSha256 = "", linkedEmail = "", verificationUnavailable = true)
        val merged = mergeCandidateEvidence(selected, listOf(contact), listOf(failed))
        assertTrue(merged.single().canConfirm(selected))
        assertFalse(hasActionableFastCandidate(selected, pending(contact), ScheduleCandidateDiscovery(listOf(failed)), merged, emptySet()))
    }

    @Test fun coldGateRejectsEveryAcceptedConfiguredRecoveryAndNonMapState() {
        assertTrue(permitsInitialFastDiscovery(selected, pending()))
        for (state in listOf(pending().copy(website = source), pending().copy(sourceUrl = source), pending().copy(configuredWebsite = source),
            pending().copy(customSource = source), pending().copy(siteEvidenceUrl = source), pending().copy(publicationRejected = true),
            pending().copy(sourceHasWithdrawals = true), pending().copy(sourceNeedsVerification = true), pending().copy(loading = true),
            pending().copy(sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT), pending().copy(siteBinding = ScheduleEntityIdentity.bind(selected,
                source, osmUrl, selected.name, ScheduleBindingMethod.EXACT_MAP_LINK)), pending().copy(publication = SchedulePublication(selected.id,
                selected.timezone, source, source, now.toString(), emptyList())))) assertFalse(permitsInitialFastDiscovery(selected, state))
        for (p in listOf(selected.copy(id = "custom:1"), selected.copy(id = "osm:way:0"), selected.copy(website = source),
            selected.copy(latitude = null), selected.copy(latitude = Double.NaN), selected.copy(latitude = 91.0), selected.copy(longitude = -181.0)))
            assertFalse(permitsInitialFastDiscovery(p, pending()))
    }

    @Test fun existingCustomDiscoverOverrideRemainsTheOnlyLookupAuthority() = runBlocking {
        val calls = AtomicInteger(); val cache = Cache()
        val resolver = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = error("Custom discover owns this route")
            override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                calls.incrementAndGet()
                return ScheduleSourceDiscovery(ScheduleIdentityOutcome.PROVED, ScheduleSite(source, osmUrl, selected.name,
                    ScheduleEntityIdentity.bind(selected, source, osmUrl, selected.name, ScheduleBindingMethod.EXACT_MAP_LINK)))
            }
        }
        val ready = InternetScheduleRepository(ScheduleTransport { response(it, html) }, cache, siteResolver = resolver).refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, ready.status); assertEquals(1, calls.get())
    }

    @Test fun lateFastResultCannotOverwriteDismissalCancellationOrAnotherAcceptedAuthority() = runBlocking {
        for (kind in listOf("dismissal", "withdrawal", "accepted")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val c = candidate()
            // A fast pre-commit race starts cold; a serialized completed attempt now skips this branch.
            val cache = Cache()
            val resolver = object : ScheduleSiteResolver {
                override suspend fun resolve(parish: Parish): ScheduleSite? = error("No late heavy fallback")
                override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                    entered.complete(Unit); release.await(); return ScheduleSourceDiscovery(ScheduleIdentityOutcome.INSUFFICIENT,
                        candidates = ScheduleCandidateDiscovery(listOf(c)))
                }
            }
            val repo = InternetScheduleRepository(ScheduleTransport { error("No import") }, cache, siteResolver = resolver)
            val result = async { repo.refresh(selected, now, force = true) }; withTimeout(5_000) { entered.await() }
            when (kind) {
                "dismissal" -> cache.seed(pending(c).copy(dismissedCandidateIds = listOf(c.id)))
                "withdrawal" -> cache.seed(pending(c).copy(website = source, sourceUrl = source,
                    publicationRejected = true, sourceHasWithdrawals = true))
                else -> cache.seed(pending(c).copy(website = source, sourceUrl = source, siteEvidenceUrl = osmUrl,
                    siteBinding = ScheduleEntityIdentity.bind(selected, source, osmUrl, selected.name, ScheduleBindingMethod.EXACT_MAP_LINK)))
            }
            val authoritative = repo.cached(selected); val raw = cache.values.getValue(selected.id); val writes = cache.writes.get()
            release.complete(Unit)
            assertEquals(authoritative, withTimeout(5_000) { result.await() }); assertEquals(raw, cache.values[selected.id])
            assertEquals(writes, cache.writes.get()); assertEquals(ScheduleImportPhase.FINISHED, repo.progress.value.phase)
        }
    }

    @Test fun lateStrongExactOsmResultCannotImportOverANewerWithdrawalOrCustomAuthority() = runBlocking {
        for (kind in listOf("withdrawal", "custom")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val cache = Cache()
            val calls = CopyOnWriteArrayList<String>()
            val http = ScheduleTransport { url ->
                calls += url
                if (url == osmUrl) { entered.complete(Unit); release.await(); response(url, osm("\"website\":\"$source\"")) }
                else error("The outdated exact site must not be imported")
            }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ -> error("Strong result needs no provider") })
            val repo = InternetScheduleRepository(http, cache, siteResolver = resolver)
            val work = async { repo.refresh(selected, now) }; withTimeout(5_000) { entered.await() }
            cache.seed(if (kind == "withdrawal") pending().copy(website = source, sourceUrl = source,
                publicationRejected = true, sourceHasWithdrawals = true)
                else pending().copy(customSource = "https://new-own.example/"))
            val authoritative = repo.cached(selected); val raw = cache.values.getValue(selected.id)
            release.complete(Unit)
            assertEquals(authoritative, withTimeout(5_000) { work.await() }); assertEquals(raw, cache.values[selected.id])
            assertEquals(listOf(osmUrl), calls.toList()); assertEquals(0, cache.writes.get())
            assertEquals(ScheduleImportPhase.FINISHED, repo.progress.value.phase)
        }
    }

    @Test fun parentCancellationAtEachStagePropagatesWithoutLateCacheWrites() = runBlocking {
        for (stage in listOf("osm", "candidates", "heavy")) {
            val entered = CompletableDeferred<Unit>(); val cache = Cache(); val c = candidate()
            val http = ScheduleTransport { url ->
                if (url == osmUrl && stage != "osm") response(url, osm("\"wikidata\":\"Q42\""))
                else { entered.complete(Unit); awaitCancellation() }
            }
            val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ ->
                if (stage == "candidates") { entered.complete(Unit); awaitCancellation() }
                else ScheduleCandidateDiscovery(if (stage == "heavy") emptyList() else listOf(c))
            })
            val repo = InternetScheduleRepository(http, cache, siteResolver = resolver)
            val work = async { repo.refresh(selected, now) }; withTimeout(5_000) { entered.await() }
            work.cancelAndJoin(); assertTrue(work.isCancelled); assertTrue(cache.values.isEmpty()); assertEquals(0, cache.writes.get())
            assertEquals(ScheduleImportPhase.FINISHED, repo.progress.value.phase)
        }
    }

    @Test fun explicitConfirmationAndAcceptedRefreshEachRequireANewCandidateRoundWithoutRepeatingTheColdHook() = runBlocking {
        val c = candidate(); val cache = Cache(); val batches = AtomicInteger(); val initial = AtomicInteger(); var available = true
        val resolver = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = error("The fresh proposal should avoid the heavy graph")
            override suspend fun candidates(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
                batches.incrementAndGet(); return if (available) ScheduleCandidateDiscovery(listOf(c)) else ScheduleCandidateDiscovery(unavailable = true)
            }
            override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                initial.incrementAndGet(); return ScheduleSourceDiscovery(ScheduleIdentityOutcome.INSUFFICIENT, candidates = candidates(parish, excludedUrls))
            }
        }
        val httpCalls = CopyOnWriteArrayList<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { httpCalls += it; response(it, html) }, cache, siteResolver = resolver)
        assertNull(repo.refresh(selected, now).publication)
        val ready = repo.confirmCandidate(selected, c.id, now.plusSeconds(1))
        assertEquals(2, batches.get()); assertEquals(1, initial.get()); assertEquals(InternetScheduleStatus.READY, ready.status)
        available = false
        val offline = repo.refresh(selected, now.plusSeconds(2), force = true)
        assertEquals(3, batches.get()); assertEquals(1, initial.get()); assertEquals(ready.publication, offline.publication)
        assertEquals(listOf(source), httpCalls.toList()); assertEquals(InternetScheduleStatus.UNAVAILABLE, offline.status)
    }
}
