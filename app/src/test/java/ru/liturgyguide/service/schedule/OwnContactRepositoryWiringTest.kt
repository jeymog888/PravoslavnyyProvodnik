package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Actual saved hOCR is a JVM input, distinct from the new real-JPG native gate. */
class OwnContactRepositoryWiringTest {
    private val f = ContactWiringFixtures
    private val documents get() = (1..3).map { ImageScheduleFixture.document(it, geometry = true) }

    private class Controls {
        var contactBytes: ByteArray = ContactWiringFixtures.bytes("contacts.html")
        var failAfterFirst = false
        var waitAfterFirst: CompletableDeferred<Unit>? = null
        var reached: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<String>()
    }
    private fun repository(cache: ScheduleCacheStore, control: Controls): InternetScheduleRepository {
        val docs = documents
        val transport = ScheduleTransport { url ->
            control.requests += url
            when {
                url.startsWith("https://api.openstreetmap.org/") -> f.osm(url)
                url == f.contact -> f.response(url, control.contactBytes)
                url == ImageScheduleFixture.parent -> f.response(url,
                    ("<html><body><h1>Расписание богослужений</h1>" + docs.joinToString("") { "<img src='${it.sourceUrl}'>" } + "</body></html>").toByteArray())
                url == docs.first().sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
                docs.any { it.sourceUrl == url } -> {
                    control.reached?.complete(Unit)
                    control.waitAfterFirst?.await()
                    if (control.failAfterFirst) throw IOException("Failure after a certified foreign image")
                    ScheduleResponse(url, byteArrayOf(2), "image/jpeg")
                }
                else -> error("Unexpected source in the closed replay: $url")
            }
        }
        return InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response -> docs.singleOrNull { it.sourceUrl == response.url } },
            siteResolver = f.resolver(transport), curatedPublishers = { f.owners })
    }
    private suspend fun accepted(cache: ContactWiringFixtures.Cache, control: Controls): InternetScheduleState {
        val repo = repository(cache, control)
        val offer = repo.refresh(f.selected(), f.now, force = true)
        val candidate = offer.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }
        assertTrue(candidate.canConfirm(f.selected())); assertNull(offer.siteBinding); assertNull(offer.publication)
        return repo.confirmCandidate(f.selected(), candidate.id, f.now).also { assertEquals(InternetScheduleStatus.READY, it.status) }
    }

    @Test fun freshConfirmedDiscoverySurvivesANewResolverSessionAndTheWholeHocrPathPublishesOnlyChildEvents() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls()
        val ready = accepted(cache, control)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
        assertEquals(ScheduleEntityIdentity.fingerprint(f.selected()), ready.siteBinding?.selectedIdentity)
        assertEquals(f.contact, ready.website)
        assertEquals(documents.last().sourceUrl, ready.publication?.sourceUrl)
        val events = requireNotNull(ready.publication).events
        assertEquals(listOf("2026-11-13T17:00", "2026-11-14T08:00", "2026-11-14T17:00", "2026-11-15T08:00"),
            events.filter { it.date in setOf("2026-11-13", "2026-11-14", "2026-11-15") }.map { "${it.date}T${it.time}" }.sorted())
        assertFalse(events.any { it.time in setOf("07:00", "09:30") })
        assertEquals(2, control.requests.count { it == f.contact }) // offer and genuinely fresh confirmation
        assertEquals(documents.map { it.sourceUrl }, control.requests.filter { url -> documents.any { it.sourceUrl == url } })
        assertTrue("Safety writes: " + cache.safety.joinToString { it.take(1400) }, cache.safety.isEmpty())
        assertEquals(ready, repository(cache, Controls()).cached(f.selected()))
        val raw2 = ImageScheduleFixture.document(2, geometry = false)
        assertEquals(ScheduleParseDisposition.REJECTED, ScheduleDocumentParser.inspect(raw2, ImageScheduleFixture.today, f.selected()).disposition)
    }

    @Test fun cancellationAndFailureAfterForeignOneKeepTheAcceptedPublicationAgeBindingAndHistoryOnColdRecreation() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls()
        val accepted = accepted(cache, control)
        val dismissed = f.mapCandidate()
        val old = accepted.copy(sourceHasWithdrawals = true, sourceCandidates = accepted.sourceCandidates + dismissed,
            dismissedCandidateIds = listOf(dismissed.id), candidateObservedIds = listOf("retained-before-refresh"))
        cache.write(f.selected().id, ScheduleCacheCodec.encode(old))
        assertTrue(old.sourceHasWithdrawals); assertTrue(old.dismissedCandidateIds.isNotEmpty()); assertTrue(old.candidateObservedIds.isNotEmpty())
        val bytes = cache.read(f.selected().id)
        control.reached = CompletableDeferred(); control.waitAfterFirst = CompletableDeferred()
        val task = launch { repository(cache, control).refresh(f.selected(), f.now.plusSeconds(3_600), force = true) }
        withTimeout(5_000) { requireNotNull(control.reached).await() }
        assertTrue("Safety writes: " + cache.safety.joinToString { it.take(1400) }, cache.safety.isEmpty()); assertEquals(bytes, cache.read(f.selected().id))
        task.cancelAndJoin(); assertTrue(task.isCancelled)
        assertEquals(old, repository(cache, Controls()).cached(f.selected()))
        val failed = Controls().apply { failAfterFirst = true }
        val next = repository(cache, failed).refresh(f.selected(), f.now.plusSeconds(7_200), force = true)
        assertEquals(old.publication, next.publication); assertEquals(old.siteBinding, next.siteBinding)
        assertEquals(old.sourceUrl, next.sourceUrl); assertEquals(old.siteEvidenceUrl, next.siteEvidenceUrl)
        assertEquals(old.sourceHasWithdrawals, next.sourceHasWithdrawals); assertFalse(next.publicationRejected)
        assertEquals(old.dismissedCandidateIds, next.dismissedCandidateIds)
        assertTrue(next.candidateObservedIds.isNotEmpty()); assertTrue(cache.safety.isEmpty())
    }

    @Test fun freshSameSourceConflictWithAChangedNameHashRevokesTheOldAuthorityDurably() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls(); val old = accepted(cache, control)
        val oldId = requireNotNull(old.siteBinding?.candidateConfirmation).candidate.id
        val doc = Jsoup.parse(control.contactBytes.toString(Charsets.UTF_8), f.contact)
        doc.select(".t195__text").single { it.text().contains("Приписные храмы") }.select("span")
            .single { it.text().startsWith("Храм в честь") }.text("Храм в честь иконы Божией Матери Казанская")
        control.contactBytes = doc.outerHtml().toByteArray(); control.requests.clear()
        val blocked = repository(cache, control).refresh(f.selected(), f.now.plusSeconds(60), force = true)
        assertNull(blocked.publication); assertNull(blocked.siteBinding); assertTrue(blocked.sourceNeedsVerification)
        val conflict = blocked.sourceCandidates.single { it.outcome == ScheduleIdentityOutcome.CONFLICT && it.sourceUrl == f.contact }
        assertNotEquals(oldId, conflict.id)
        assertTrue(cache.safety.isNotEmpty())
        assertTrue(control.requests.none { url -> documents.any { it.sourceUrl == url } })
        val cold = repository(cache, Controls()).cached(f.selected())
        assertNull(cold.publication); assertNull(cold.siteBinding); assertTrue(cold.sourceNeedsVerification)
    }

    @Test fun missingFreshOwnRowOrWitnessCannotUseAnotherMapCandidateOrCachedConfirmation() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls(); val old = accepted(cache, control)
        val approved = requireNotNull(old.siteBinding?.candidateConfirmation).candidate
        var imports = 0
        val noWitness = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: ru.liturgyguide.model.Parish): ScheduleSite? = null
            override suspend fun candidatesForConfirmation(parish: ru.liturgyguide.model.Parish, excludedUrls: Set<String>,
                expected: ScheduleSourceCandidate) = ScheduleCandidateDiscovery(listOf(approved, f.mapCandidate()))
        }
        val checked = InternetScheduleRepository(ScheduleTransport { imports++; error("Cached DTO is not a response capability") },
            cache, siteResolver = noWitness, curatedPublishers = { f.owners }).refresh(f.selected(), f.now.plusSeconds(60), force = true)
        assertEquals(old.publication, checked.publication); assertEquals(old.siteBinding, checked.siteBinding)
        assertTrue(checked.candidateSearchUnavailable); assertEquals(0, imports)
        control.contactBytes = "<html><body>Контакты без приписного храма</body></html>".toByteArray()
        control.requests.clear()
        val absent = repository(cache, control).refresh(f.selected(), f.now.plusSeconds(120), force = true)
        assertEquals(old.publication, absent.publication)
        assertTrue(control.requests.none { url -> documents.any { it.sourceUrl == url } })
    }

    @Test fun aFreshWitnessDoesNotBypassASelectedRootCancellation() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls(); accepted(cache, control)
        val doc = Jsoup.parse(control.contactBytes.toString(Charsets.UTF_8), f.contact)
        doc.body().prepend("<h1>${f.selected().name}</h1><p>Все богослужения отменены</p>")
        control.contactBytes = doc.outerHtml().toByteArray(); control.requests.clear()
        val cancelled = repository(cache, control).refresh(f.selected(), f.now.plusSeconds(60), force = true)
        assertNull(cancelled.publication); assertTrue(cancelled.publicationRejected); assertTrue(cancelled.sourceHasWithdrawals)
        assertTrue(cache.safety.isNotEmpty())
        assertTrue(control.requests.none { url -> documents.any { it.sourceUrl == url } })
    }
    @Test fun unclassifiedHiddenOrRawCancellationIsDurableBeforeAnotherFetchAndSurvivesImmediateJobCancellation() = runBlocking {
        for (extra in listOf("<div hidden>Все богослужения отменены</div>",
            "<script type='text/plain'>Все богослужения отменены</script>",
            "<p>Неизвестная служба в 09:30</p>")) {
            val cache = ContactWiringFixtures.Cache(); val control = Controls(); val accepted = accepted(cache, control)
            val old = accepted.copy(sourceHasWithdrawals = true, dismissedCandidateIds = listOf(f.mapCandidate().id))
            cache.write(f.selected().id, ScheduleCacheCodec.encode(old))
            val doc = requireNotNull(f.response().html()); doc.body().append(extra)
            control.contactBytes = doc.outerHtml().toByteArray(); control.requests.clear()
            var task: Job? = null
            val durable = object : ScheduleCacheStore {
                override fun read(parishId: String) = cache.read(parishId)
                override fun write(parishId: String, value: String) = cache.write(parishId, value)
                override fun writeSafety(parishId: String, value: String) {
                    cache.writeSafety(parishId, value)
                    task?.cancel() // Exactly after the durable observation, before any following source request.
                }
            }
            val running = launch(start = CoroutineStart.LAZY) {
                repository(durable, control).refresh(f.selected(), f.now.plusSeconds(60), force = true)
            }
            task = running
            running.start(); running.join(); assertTrue(running.isCancelled)
            assertTrue(cache.safety.isNotEmpty())
            assertEquals(listOf(f.contact), control.requests)
            val cold = repository(cache, Controls()).cached(f.selected())
            assertNull(cold.publication); assertTrue(cold.publicationRejected)
            assertEquals(old.siteBinding, cold.siteBinding)
            assertTrue(cold.sourceHasWithdrawals); assertEquals(old.dismissedCandidateIds, cold.dismissedCandidateIds)
        }
    }

    private suspend fun expectFreshRootSafetyBeforeNextFetch(body: ByteArray, reason: String? = null) = coroutineScope {
        val response = f.response(bytes = body)
        val fresh = OwnContactCandidateProvider(ScheduleTransport { response }, LocalPublisherSeedPool(listOf(f.seed())), f.clock)
            .discover(f.selected(), emptySet())
        val row = fresh.candidates.single { it.canConfirm(f.selected()) }
        assertTrue("The actual changed full response must issue the fresh capability", requireNotNull(fresh.freshContactEvidence?.get(row.id)).matchesFresh(f.selected(), row))
        if (reason != null) assertEquals(reason, ContactNavigationRootAudit.sourceBounds(response))
        val cache = ContactWiringFixtures.Cache(); val control = Controls(); val accepted = accepted(cache, control)
        val old = accepted.copy(sourceHasWithdrawals = true, dismissedCandidateIds = listOf(f.mapCandidate().id),
            candidateObservedIds = listOf("retained-correction-history"))
        cache.write(f.selected().id, ScheduleCacheCodec.encode(old))
        control.contactBytes = body; control.requests.clear()
        var task: Job? = null
        val durable = object : ScheduleCacheStore {
            override fun read(parishId: String) = cache.read(parishId)
            override fun write(parishId: String, value: String) = cache.write(parishId, value)
            override fun writeSafety(parishId: String, value: String) {
                cache.writeSafety(parishId, value)
                assertEquals(f.contact, requireNotNull(ScheduleCacheCodec.decode(value, f.selected())).sourceUrl)
                task?.cancel()
            }
        }
        val running = launch(start = CoroutineStart.LAZY) { repository(durable, control).refresh(f.selected(), f.now.plusSeconds(60), force = true) }
        task = running; running.start(); running.join()
        assertTrue(running.isCancelled); assertTrue(cache.safety.isNotEmpty())
        assertEquals(listOf(f.contact), control.requests)
        val cold = repository(cache, Controls()).cached(f.selected())
        assertNull(cold.publication); assertTrue(cold.publicationRejected); assertEquals(old.siteBinding, cold.siteBinding)
        assertTrue(cold.sourceHasWithdrawals); assertEquals(old.dismissedCandidateIds, cold.dismissedCandidateIds)
        assertEquals(old.candidateObservedIds, cold.candidateObservedIds)
    }

    @Test fun freshCssEscapesTemporalAttributesAndEveryImporterBoundWriteSafetyBeforeAnotherFetch() = runBlocking {
        val css = """<style>body::before{content:"\41e\442\43c\435\43d\430\20\432\441\435\445\20\431\43e\433\43e\441\43b\443\436\435\43d\438\439"}</style>"""
        for (mutation in listOf<(org.jsoup.nodes.Document) -> Unit>(
            { it.head().append(css) },
            { it.body().append("<svg><script href='https://parish.example/notice.js'></script></svg>") },
            { it.body().append("<svg><script xlink:href='https://parish.example/notice.js'></script></svg>") },
            { it.head().append("<script>document.body.append(String[\"fromCharCode\"](1054,1090,1084,1077,1085,1072));</script>") },
            { it.body().attr("onload", "document.body.append(String[\"fromCharCode\"](1054,1090,1084,1077,1085,1072))") },
            { it.head().append("<meta name='description' content='14 ноября 2026 08:00'>") },
            { it.select("[data-tooltip-hook]").first()!!.attr("data-tooltip-hook", "14 ноября 2026 08:00") },
            { it.body().attr("aria-description", "08:00") }
        )) {
            val body = requireNotNull(f.response().html()).also(mutation).outerHtml().toByteArray()
            expectFreshRootSafetyBeforeNextFetch(body)
        }
        val original = f.bytes("contacts.html").toString(Charsets.UTF_8)
        fun withTail(tail: String) = original.replace("</body>", "$tail</body>").toByteArray()
        // No ordinary HTML parser/extractor is called in test setup for the deep input.
        // A valid fresh provider witness is still required by expectFreshRootSafetyBeforeNextFetch.
        expectFreshRootSafetyBeforeNextFetch(withTail("<div>".repeat(8_192) + "Контакты" + "</div>".repeat(8_192)), "depth-cap")
        expectFreshRootSafetyBeforeNextFetch(withTail("<span></span>".repeat(ContactNavigationRootAudit.MAX_NODES + 1)), "node-cap")
        val overAuditBytes = withTail("<!--" + "x".repeat(ContactNavigationRootAudit.MAX_BYTES) + "-->")
        assertTrue(overAuditBytes.size <= OwnContactCandidateProvider.MAX_BYTES)
        expectFreshRootSafetyBeforeNextFetch(overAuditBytes, "source-completion-or-byte-cap")
    }

    @Test fun aSpentEnvelopeFromAnEarlierActionCannotBeReusedAsAFreshContactRecheck() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val old = accepted(cache, Controls())
        val found = f.fresh(); val row = found.candidates.single(); val proof = requireNotNull(found.freshContactEvidence?.get(row.id))
        val response = f.response(); val html = requireNotNull(response.html()); val document = HtmlScheduleDocumentExtractor.fromHtml(html)
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, proof.auditNavigationRoot(f.selected(), row, f.contact,
            response, ScheduleHtmlParser.inspect(html, ImageScheduleFixture.today, f.selected()), document,
            ScheduleDocumentParser.inspect(document, ImageScheduleFixture.today, f.selected())).disposition)
        var imports = 0
        val oldEnvelope = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: ru.liturgyguide.model.Parish): ScheduleSite? = null
            override suspend fun candidatesForConfirmation(parish: ru.liturgyguide.model.Parish, excludedUrls: Set<String>,
                expected: ScheduleSourceCandidate) = found
        }
        val checked = InternetScheduleRepository(ScheduleTransport { imports++; error("Spent envelope is not a fresh response") },
            cache, siteResolver = oldEnvelope).refresh(f.selected(), f.now.plusSeconds(60), force = true)
        assertEquals(0, imports); assertEquals(old.publication, checked.publication); assertEquals(old.siteBinding, checked.siteBinding)
        assertTrue(checked.candidateSearchUnavailable); assertTrue(cache.safety.isEmpty())
    }

    @Test fun manualLinkedAlternativeAndImageRootsKeepTheirPreviousPublicationGuards() = runBlocking {
        run {
            val cache = ContactWiringFixtures.Cache(); val control = Controls()
            var task: Job? = null
            val cancelAtManualSafety = object : ScheduleCacheStore {
                override fun read(parishId: String) = cache.read(parishId)
                override fun write(parishId: String, value: String) = cache.write(parishId, value)
                override fun writeSafety(parishId: String, value: String) {
                    cache.writeSafety(parishId, value)
                    assertEquals(f.contact, requireNotNull(ScheduleCacheCodec.decode(value, f.selected())).sourceUrl)
                    task?.cancel()
                }
            }
            val running = launch(start = CoroutineStart.LAZY) {
                repository(cancelAtManualSafety, control).refresh(f.selected(), f.now, force = true, sourceOverride = f.contact)
            }
            task = running; running.start(); running.join()
            assertTrue(running.isCancelled); assertTrue(cache.safety.isNotEmpty())
            // Only explicit cancellation prevents the already queued /schedule read. Generic REJECTED itself keeps the old continuation behavior.
            assertEquals(listOf(f.contact), control.requests)
            assertNull(repository(cache, Controls()).cached(f.selected()).publication)
            val continuingCache = ContactWiringFixtures.Cache(); val continuing = Controls()
            repository(continuingCache, continuing).refresh(f.selected(), f.now, force = true, sourceOverride = f.contact)
            assertEquals(f.contact, requireNotNull(ScheduleCacheCodec.decode(continuingCache.safety.first(), f.selected())).sourceUrl)
            assertTrue("Without explicit cancellation the previous importer continues to the published link",
                ImageScheduleFixture.parent in continuing.requests)
        }
        run {
            val cache = ContactWiringFixtures.Cache(); val control = Controls()
            val imageRoot = repository(cache, control).refresh(f.selected(), f.now, force = true, sourceOverride = documents.first().sourceUrl)
            assertNull(imageRoot.publication); assertTrue(cache.safety.isNotEmpty())
            assertEquals(listOf(documents.first().sourceUrl), control.requests)
        }
        run {
            val cache = ContactWiringFixtures.Cache(); val calls = mutableListOf<String>()
            val transport = ScheduleTransport { url -> calls += url; when {
                url.startsWith("https://api.openstreetmap.org/") -> f.osm(url)
                url == f.contact || url == ImageScheduleFixture.parent -> f.response(url)
                else -> error("Linked contact HTML must stop before another fetch: $url")
            } }
            val repo = InternetScheduleRepository(transport, cache, siteResolver = f.resolver(transport), curatedPublishers = { f.owners })
            val offered = repo.refresh(f.selected(), f.now, force = true)
            val row = offered.sourceCandidates.single { it.canConfirm(f.selected()) }
            val blocked = repo.confirmCandidate(f.selected(), row.id, f.now)
            assertNull(blocked.publication); assertTrue(cache.safety.isNotEmpty())
            assertEquals(ImageScheduleFixture.parent, blocked.sourceUrl)
            assertTrue(calls.none { url -> documents.any { it.sourceUrl == url } })
        }
        run {
            val primary = "https://primary.example/unavailable"
            val selected = f.selected().copy(website = primary)
            val cache = ContactWiringFixtures.Cache(); val calls = mutableListOf<String>()
            val transport = ScheduleTransport { url -> calls += url; if (url == primary) throw IOException("Primary unavailable")
                else if (url == f.contact) f.response(url) else error(url) }
            val alternative = object : ScheduleSiteResolver {
                override suspend fun resolve(parish: ru.liturgyguide.model.Parish) = ScheduleSite(f.contact, parish.sourceUrl, parish.name,
                    ScheduleEntityIdentity.bind(parish, f.contact, parish.sourceUrl, parish.name, ScheduleBindingMethod.EXACT_MAP_LINK))
            }
            val blocked = InternetScheduleRepository(transport, cache, siteResolver = alternative)
                .refresh(selected, f.now, force = true)
            assertNull(blocked.publication); assertNotEquals(InternetScheduleStatus.READY, blocked.status)
            assertTrue(calls.contains(f.contact)); assertTrue(calls.none { url -> documents.any { it.sourceUrl == url } })
        }
    }

}
