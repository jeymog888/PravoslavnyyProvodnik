package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ParishRepository
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object ContactWiringFixtures {
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/contact-pool/$name")).use { it.readBytes() }
    val oracle get() = JsonParser.parseString(bytes("oracle.json").toString(Charsets.UTF_8)).asJsonObject
    val contact get() = oracle["contact"].asJsonObject["url"].asString
    val home get() = oracle["home"].asJsonObject["url"].asString
    val now: Instant = Instant.parse("2026-11-13T18:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    fun selected(): Parish {
        val row = oracle["selected"].asJsonObject; val point = row["center"].asJsonObject
        return Parish(row["id"].asString, row["name"].asString, "", "", "", "", "", "Europe/Moscow",
            row["sourceUrl"].asString, "", "", "", emptyList(), "", "", point["latitude"].asDouble, point["longitude"].asDouble)
    }
    fun provenance() = Gson().fromJson(oracle["metadataOrigin"], PublisherSeedProvenance::class.java)
    fun seed(url: String = contact, scope: String = home, role: PublisherSeedRole = PublisherSeedRole.CONTACT_REFERENCE) =
        PublisherSeed(url, role, scope, provenance())
    val owners get() = ParishRepository(requireNotNull(javaClass.getResource("/churches.json")).readText()).directory.churches
    fun pools(direct: List<PublisherSeed> = listOf(seed()), websites: List<PublisherSeed> = emptyList()) = object : OwnContactPoolFactories {
        override suspend fun direct(parish: Parish): PublisherSeedPool = LocalPublisherSeedPool(direct)
        override suspend fun websites(parish: Parish): PublisherSeedPool = LocalPublisherSeedPool(websites)
        override suspend fun curatedOwners() = owners
    }
    fun response(url: String = contact, bytes: ByteArray = bytes("contacts.html")) = ScheduleResponse(url, bytes, "text/html;charset=UTF-8")
    fun osm(url: String) = response(url, """{"elements":[{"type":"way","id":226509018,"tags":{"name":"Церковь иконы Божией Матери Всецарица"}}]}""".toByteArray())
    suspend fun fresh(url: String = contact, scope: String = home): ScheduleCandidateDiscovery =
        OwnContactCandidateProvider(ScheduleTransport { response(it) }, LocalPublisherSeedPool(listOf(seed(url, scope))), clock)
            .discover(selected(), emptySet())
    fun mapCandidate(source: String = "https://map.example/own/") = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(selected()), provider = "test_map", providerLabel = "Карта",
        providerObjectId = ScheduleSourceCandidate.sha(source), name = selected().name, address = "", sourceUrl = source,
        evidenceUrl = "https://map.example/card/", evidenceSha256 = "a".repeat(64), checkedAt = now.toString(),
        latitude = requireNotNull(selected().latitude), longitude = requireNotNull(selected().longitude),
        outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Опубликованный источник для проверки")
    class Cache : ScheduleCacheStore {
        val rows = mutableMapOf<String, String>()
        val safety = mutableListOf<String>()
        override fun read(parishId: String) = rows[parishId]
        override fun write(parishId: String, value: String) { rows[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safety += value; write(parishId, value) }
    }
    fun resolver(transport: ScheduleTransport, map: ScheduleCandidateProvider = ScheduleCandidateProvider { _, _ ->
        ScheduleCandidateDiscovery(incomplete = false) }, pools: OwnContactPoolFactories = pools()) =
        OpenScheduleSiteResolver(transport, candidateProvider = map, ownPools = pools, clock = clock)
}

class ContactWiringTest {
    private val f = ContactWiringFixtures

    @Test fun usefulMapOfferReturnsBeforeAnyOwnFactoryOrLargeCatalogAndRepositoryOwnsEligibility() = runBlocking {
        val map = f.mapCandidate()
        var maps = 0; var pools = 0
        val neverOwn = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { pools++; error("No Own work for useful map") }
            override suspend fun websites(parish: Parish): PublisherSeedPool { pools++; error("No catalog work for useful map") }
            override suspend fun curatedOwners(): List<Parish> { pools++; error("No metadata work for useful map") }
        }
        val requests = mutableListOf<String>()
        val transport = ScheduleTransport { requests += it; if (it.startsWith("https://api.openstreetmap.org/")) f.osm(it) else error(it) }
        val resolver = f.resolver(transport, ScheduleCandidateProvider { _, _ -> maps++; ScheduleCandidateDiscovery(listOf(map)) }, neverOwn)
        val repository = InternetScheduleRepository(transport, ContactWiringFixtures.Cache(), siteResolver = resolver)
        val review = repository.refresh(f.selected(), f.now, force = true)
        assertEquals(listOf(map.id), review.sourceCandidates.map { it.id })
        assertEquals(1, maps); assertEquals(0, pools); assertEquals(1, requests.size)
        assertNull(review.publication); assertNull(review.siteBinding)
    }

    @Test fun customOldFastAndCandidateHooksKeepTheirAuthoritativeBehavior() = runBlocking {
        val map = f.mapCandidate(); var oldFast = 0; var oldCandidates = 0
        val custom = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = error("Old custom hook owns this path")
            override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                oldFast++; return ScheduleSourceDiscovery(ScheduleIdentityOutcome.INSUFFICIENT,
                    candidates = ScheduleCandidateDiscovery(listOf(map)))
            }
            override suspend fun candidates(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
                oldCandidates++; return ScheduleCandidateDiscovery(listOf(map))
            }
        }
        val state = InternetScheduleRepository(ScheduleTransport { error(it) }, ContactWiringFixtures.Cache(), siteResolver = custom)
            .refresh(f.selected(), f.now, force = true)
        assertEquals(1, oldFast); assertEquals(map.id, state.sourceCandidates.single().id)
        assertEquals(map.id, custom.candidatesForConfirmation(f.selected(), emptySet(), map).candidates.single().id)
        assertEquals(1, oldCandidates)
    }

    @Test fun directAndWebsiteShareAllDispatchPublisherAndPageMemoBudgets() = runBlocking {
        val roots = (1..3).map { "https://publisher$it.example/" }
        val direct = roots.map { f.seed(it + "contacts", it) }
        val website = roots.map { f.seed(it, it, PublisherSeedRole.WEBSITE) } +
            f.seed("https://fourth.example/", "https://fourth.example/", PublisherSeedRole.WEBSITE)
        val calls = mutableListOf<String>(); var directLoads = 0; var websiteLoads = 0
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { directLoads++; return LocalPublisherSeedPool(direct) }
            override suspend fun websites(parish: Parish): PublisherSeedPool { websiteLoads++; return LocalPublisherSeedPool(website) }
            override suspend fun curatedOwners() = f.owners
        }
        val provider = OwnContactCandidateProvider(ScheduleTransport { url ->
            calls += url
            f.response(url, if (url.endsWith("/contacts")) "<html><body>Нет child relation</body></html>".toByteArray() else
                "<html><body><nav><a href='/contacts'>Контакты</a></nav></body></html>".toByteArray())
        }, LocalPublisherSeedPool(emptyList()), f.clock)
        val found = provider.discoverFromPools(f.selected(), emptySet(), emptySet(), pools) { false }
        assertEquals(1, directLoads); assertEquals(1, websiteLoads)
        assertEquals(6, calls.size); assertEquals(calls.size, calls.distinct().size)
        assertEquals(3, calls.map { java.net.URI(it).host }.distinct().size)
        assertTrue(calls.none { it.contains("fourth") }); assertTrue(found.candidates.isEmpty())
    }

    @Test fun canonicalEndpointRedirectKeepsActualResponseIdentityWhileCrossPathRedirectCannotIssueEvidence() = runBlocking {
        val scope = "https://shared.example/a/"; val requested = scope + "contacts/"; val canonical = scope + "contacts"
        val dispatched = mutableListOf<String>()
        val provider = OwnContactCandidateProvider(ScheduleTransport { url ->
            dispatched += url; f.response(canonical)
        }, LocalPublisherSeedPool(listOf(f.seed(requested, scope))), f.clock)
        val found = provider.discover(f.selected(), emptySet())
        assertEquals(listOf(requested), dispatched)
        val raw = found.candidates.single()
        assertEquals(canonical, raw.sourceUrl); assertEquals(canonical, raw.evidenceUrl)
        val proof = requireNotNull(found.freshContactEvidence?.get(raw.id))
        assertTrue(proof.matchesFresh(f.selected(), raw))
        assertEquals(canonical, proof.responseForApproved(f.selected(), raw, canonical)?.url)
        assertFalse(proof.matchesApproved(f.selected(), raw, requested))
        for (wrong in listOf("https://shared.example/ab/contacts", scope + "other-contact", "https://other.example/a/contacts")) {
            val rejected = OwnContactCandidateProvider(ScheduleTransport { f.response(wrong) },
                LocalPublisherSeedPool(listOf(f.seed(requested, scope))), f.clock).discover(f.selected(), emptySet())
            assertTrue(wrong, rejected.candidates.isEmpty()); assertTrue(wrong, rejected.unavailable)
            assertTrue(rejected.freshContactEvidence.isNullOrEmpty())
        }
    }

    @Test fun oneOwnDeadlineCoversBothLazyFactoriesAndParentCancellationRestartsTheNextAttempt() = runBlocking {
        var websiteEntered = false
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { delay(25); return LocalPublisherSeedPool(emptyList()) }
            override suspend fun websites(parish: Parish): PublisherSeedPool { websiteEntered = true; awaitCancellation() }
            override suspend fun curatedOwners() = f.owners
        }
        val provider = OwnContactCandidateProvider(ScheduleTransport { error("No dispatch before pool returns") },
            LocalPublisherSeedPool(emptyList()), f.clock, timeoutMillis = 75)
        val found = provider.discoverFromPools(f.selected(), emptySet(), emptySet(), pools) { false }
        assertTrue(websiteEntered); assertTrue(found.unavailable)
        val entered = CompletableDeferred<Unit>(); var reads = 0
        val fresh = OwnContactCandidateProvider(ScheduleTransport { reads++; entered.complete(Unit); awaitCancellation() },
            LocalPublisherSeedPool(listOf(f.seed())), f.clock)
        val job = launch { fresh.discover(f.selected(), emptySet()) }
        entered.await(); job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(1, reads)
    }

    @Test fun dismissedOrConflictedDirectAAndIneligibleMapCannotHideAnEligibleWebsiteBOnTheSameHost() = runBlocking {
        for (conflicted in listOf(false, true)) {
            val aUrl = "https://shared.example/a/contacts"; val aScope = "https://shared.example/a/"
            val bScope = "https://shared.example/b/"; val bUrl = bScope + "contacts"
            val a = f.fresh(aUrl, aScope).candidates.single()
            val map = f.mapCandidate()
            val calls = mutableListOf<String>()
            val transport = ScheduleTransport { url -> calls += url; when {
                url.startsWith("https://api.openstreetmap.org/") -> f.osm(url)
                url == aUrl || url == bUrl -> f.response(url)
                url == bScope -> f.response(url, "<html><body><nav><a href='contacts'>Контакты</a></nav></body></html>".toByteArray())
                else -> error("Heavy graph or unrelated host must not run: $url")
            } }
            val cache = ContactWiringFixtures.Cache()
            cache.write(f.selected().id, ScheduleCacheCodec.encode(InternetScheduleState(f.selected().id,
                status = InternetScheduleStatus.NO_WEBSITE, checkedAt = f.now.minusSeconds(301).toString(),
                candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(f.selected()),
                sourceCandidates = listOf(if (conflicted) a.copy(outcome = ScheduleIdentityOutcome.CONFLICT) else a, map),
                dismissedCandidateIds = listOf(map.id) + (if (conflicted) emptyList() else listOf(a.id)))))
            val seeded = requireNotNull(ScheduleCacheCodec.decode(requireNotNull(cache.read(f.selected().id)), f.selected()))
            assertEquals(setOf(a.id, map.id), seeded.sourceCandidates.map { it.id }.toSet())
            assertEquals(setOf(map.id) + (if (conflicted) emptySet() else setOf(a.id)), seeded.dismissedCandidateIds.toSet())
            val resolver = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map)) },
                f.pools(listOf(f.seed(aUrl, aScope)), listOf(f.seed(bScope, bScope, PublisherSeedRole.WEBSITE))))
            val state = InternetScheduleRepository(transport, cache, siteResolver = resolver).refresh(f.selected(), f.now, force = true)
            assertTrue("Actual requests: $calls", calls.containsAll(listOf(aUrl, bScope, bUrl)))
            assertTrue(state.sourceCandidates.any { it.sourceUrl == bUrl && it.canConfirm(f.selected()) })
            assertTrue(map.id in state.dismissedCandidateIds)
            if (conflicted) assertEquals(ScheduleIdentityOutcome.CONFLICT, state.sourceCandidates.single { it.id == a.id }.outcome)
            else assertTrue(a.id in state.dismissedCandidateIds)
            assertNull(state.siteBinding)
        }
    }

    @Test fun expectedOwnAIsFetchedDespiteUsefulMapAndAnEarlierOwnBAndGetsANewCapabilityOnEachReview() = runBlocking {
        val approved = f.fresh().candidates.single()
        val b = "https://other.example/contacts"; val bHome = "https://other.example/"
        val map = f.mapCandidate(); val aEntered = CompletableDeferred<Unit>(); val bFinished = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>(); var aGets = 0
        val transport = ScheduleTransport { url -> when (url) {
            f.contact -> { aGets++; aEntered.complete(Unit); releaseA.await(); f.response(url) }
            b -> { bFinished.complete(Unit); f.response(url) }
            else -> error(url)
        } }
        val session = f.resolver(transport, ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(listOf(map)) },
            f.pools(listOf(f.seed(b, bHome)))).forRefresh()
        val pending = async { session.candidatesForConfirmation(f.selected(), emptySet(), approved) }
        withTimeout(3_000) { aEntered.await(); bFinished.await() }
        assertTrue(pending.isActive)
        releaseA.complete(Unit)
        val first = pending.await()
        val raw = first.candidates.single { it.id == approved.id }
        val proof = requireNotNull(first.freshContactEvidence?.get(raw.id))
        assertTrue(proof.matchesFresh(f.selected(), raw)); assertTrue(first.candidates.any { it.id == map.id })
        val second = session.candidatesForConfirmation(f.selected(), emptySet(), approved)
        assertEquals(2, aGets)
        assertNotSame(proof, second.freshContactEvidence?.get(raw.id))
    }

    @Test fun parallelReviewRetainsReadyMapRowsAfterTheOwnNeighborTimesOut() = runBlocking {
        val mapStarted = CompletableDeferred<Unit>(); val ownStarted = CompletableDeferred<Unit>()
        val map = f.mapCandidate()
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool {
                ownStarted.complete(Unit); mapStarted.await()
                return withTimeout(25) { awaitCancellation() }
            }
            override suspend fun websites(parish: Parish): PublisherSeedPool = error("Timed out direct loader")
            override suspend fun curatedOwners() = f.owners
        }
        val maps = ScheduleCandidateProvider { _, _ -> mapStarted.complete(Unit); ownStarted.await(); delay(50); ScheduleCandidateDiscovery(listOf(map)) }
        val found = withTimeout(3_000) { f.resolver(ScheduleTransport { error(it) }, maps, pools).forRefresh().candidates(f.selected(), emptySet()) }
        assertEquals(map.id, found.candidates.single().id); assertTrue(found.unavailable)
        assertTrue(found.candidates.single().canConfirm(f.selected()))
    }

    @Test fun evidenceComesFromCompleteFreshResponsesAndCannotBeReconstructedFromJsonOrCachedDtos() = runBlocking {
        val found = f.fresh(); val row = found.candidates.single(); val proof = requireNotNull(found.freshContactEvidence?.get(row.id))
        assertTrue(proof.matchesFresh(f.selected(), row))
        val reconstructed = Gson().fromJson(Gson().toJson(proof), PublishedChildContactEvidence::class.java)
        assertFalse(reconstructed.matchesFresh(f.selected(), row))
        val envelope = Gson().toJson(found)
        assertFalse(envelope.contains("freshContactEvidence"))
        assertNull(PublishedChildContactEvidence.fromFreshResponse(f.selected(), f.response(bytes = f.bytes("contacts.html").dropLast(30).toByteArray()), f.home, row))
        assertNull(PublishedChildContactEvidence.fromFreshResponse(f.selected(), f.response(), f.home, row.copy(evidenceSha256 = "f".repeat(64))))
        assertFalse(proof.matchesApproved(f.selected().copy(latitude = f.selected().latitude!! + .00001), row, f.contact))
        assertFalse(proof.matchesApproved(f.selected(), row, "https://other.example/contacts"))
        assertNull(KnownOtherScheduleVenue.resolve(f.selected(), f.contact, ImageScheduleFixture.parent, f.owners))
        assertNotNull(KnownOtherScheduleVenue.fromFreshContact(f.selected(), f.contact, ImageScheduleFixture.parent, row, proof, f.owners))
    }

    @Test fun noUniqueOwnerWrongPathSameBuildingOrNoFreshWitnessCannotEnableForeignImageCertificate() = runBlocking {
        val found = f.fresh(); val row = found.candidates.single(); val proof = requireNotNull(found.freshContactEvidence?.get(row.id))
        val owner = f.owners.single { it.id == "elisavetinsky_opalikha" }
        fun known(owners: List<Parish> = f.owners, root: String = f.contact, linked: String = ImageScheduleFixture.parent,
            evidence: PublishedChildContactEvidence? = proof) = KnownOtherScheduleVenue.fromFreshContact(f.selected(), root, linked, row, evidence, owners)
        assertNull(known(evidence = null)); assertNull(known(owners = emptyList()))
        assertNull(known(owners = f.owners + owner.copy(id = "another-owner")))
        assertNull(known(owners = f.owners.map { if (it.id == owner.id) it.copy(latitude = f.selected().latitude, longitude = f.selected().longitude) else it }))
        assertNull(known(root = f.home + "different-contact")); assertNull(known(linked = "https://foreign.example/schedule"))
        assertNull(known(linked = f.home + "a/%2e%2e/schedule"))
        val image = ImageScheduleFixture.document(1)
        val selected = ScheduleDocumentParser.inspect(image, ImageScheduleFixture.today, f.selected())
        assertEquals(ScheduleRejectionReason.OTHER_ENTITY_ONLY, ScheduleForeignImage.certify(image, ImageScheduleFixture.today,
            f.selected(), f.contact, ImageScheduleFixture.parent, known(), selected).rejectionReason)
        assertNull(ScheduleForeignImage.certify(image, ImageScheduleFixture.today, f.selected(), f.contact,
            ImageScheduleFixture.parent, null, selected).rejectionReason)
    }
}
