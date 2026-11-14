package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Whole saved HTML/JPEG plus pinned actual native22 documents. Overrides are explicit synthetic content controls. */
class LinkedAttachmentRepositoryTest {
    private val x = LinkedNavigationFixtures
    private val f = x.f
    private class Controls {
        var html = LinkedNavigationFixtures.bytes("schedule.html")
        var urls = LinkedNavigationFixtures.documents().map { it.sourceUrl }
        var responseUrl: String? = null
        val documents = linkedMapOf<Int, ScheduleDocument>()
        var failRemaining = false
        var pauseAt = 0
        val reached = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
    }
    private fun repo(cache: ScheduleCacheStore, control: Controls): InternetScheduleRepository {
        val original = x.documents()
        val transport = ScheduleTransport { url ->
            control.requests += url
            when {
                url.startsWith("https://api.openstreetmap.org/") -> f.osm(url)
                url == f.contact -> f.response()
                url == x.target -> x.response(control.html).copy(url = control.responseUrl ?: url)
                url in control.urls -> {
                    val index = control.urls.indexOf(url) + 1
                    if (index == control.pauseAt) { control.reached.complete(Unit); awaitCancellation() }
                    if (control.failRemaining && index >= 2) throw IOException("All remaining attachments failed after foreign1")
                    ScheduleResponse(url, if (index in control.documents) "EXPLICIT_SYNTHETIC_CONTENT_$index".toByteArray()
                        else x.bytes("image-$index.jpg"), "image/jpeg")
                }
                else -> error("Closed source replay: $url")
            }
        }
        return InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response ->
            val index = control.urls.indexOf(response.url) + 1
            check(index in 1..3)
            val override = control.documents[index]
            if (override == null) assertArrayEquals(x.bytes("image-$index.jpg"), response.bytes)
            else assertEquals("EXPLICIT_SYNTHETIC_CONTENT_$index", response.bytes.toString(Charsets.UTF_8))
            (override ?: original[index - 1]).copy(sourceUrl = response.url)
        }, siteResolver = f.resolver(transport), curatedPublishers = { f.owners })
    }
    private suspend fun accepted(cache: ContactWiringFixtures.Cache, control: Controls): InternetScheduleState {
        val repository = repo(cache, control)
        val offer = repository.refresh(f.selected(), f.now, force = true)
        assertNull(offer.siteBinding); assertNull(offer.publication)
        val row = offer.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }
        return repository.confirmCandidate(f.selected(), row.id, f.now).also { assertEquals(InternetScheduleStatus.READY, it.status) }
    }
    private fun controls(state: InternetScheduleState) {
        val events = requireNotNull(state.publication).events
        assertEquals(12, events.size)
        assertEquals(x.certifiedParses().last().events, events)
        assertEquals(listOf("2026-11-13T17:00", "2026-11-14T08:00", "2026-11-14T17:00", "2026-11-15T08:00"), events
            .filter { it.date in setOf("2026-11-13", "2026-11-14", "2026-11-15") }.map { "${it.date}T${it.time}" }.sorted())
        assertFalse(events.any { it.time in setOf("07:00", "09:30") })
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, state.siteBinding?.method)
        assertEquals(ScheduleEntityIdentity.fingerprint(f.selected()), state.siteBinding?.selectedIdentity)
    }

    @Test fun actualFullIndexAndOriginalNativeDocumentsReachTwelveChildEventsWithNoNavigationSafetyWrites() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls(); val ready = accepted(cache, control)
        controls(ready); assertTrue(cache.safety.isEmpty())
        assertEquals(x.documents().last().sourceUrl, ready.publication?.sourceUrl)
        assertEquals(f.contact, ready.publication?.website); assertEquals(x.target, ready.publication?.linkedFrom)
        assertEquals(2, control.requests.count { it == f.contact })
        assertEquals(listOf(x.target), control.requests.filter { it == x.target })
        assertEquals(control.urls, control.requests.filter { it in control.urls })
        assertEquals(ready, repo(cache, Controls()).cached(f.selected()))
    }

    @Test fun currentPublishedTuplesRequestNewUrlsInOrderAndStillUseOrdinaryImageContentRules() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val control = Controls().apply { html = x.monthly(); urls = x.nextUrls }
        val ready = accepted(cache, control); controls(ready)
        assertEquals(x.nextUrls, control.requests.filter { it in x.nextUrls })
        assertEquals(x.nextUrls.last(), ready.publication?.sourceUrl); assertEquals(x.target, ready.publication?.linkedFrom)
        assertTrue(cache.safety.isEmpty())
        assertTrue(control.requests.none { it in x.documents().map(ScheduleDocument::sourceUrl) })
    }

    @Test fun validNewIndexUrlsCannotLicenseForeignUnreadableExpiredOrCancelledThirdImageContent() = runBlocking {
        val selected = ScheduleDocumentLine(f.selected().name)
        val cases = listOf(
            x.documents().first(),
            ScheduleDocument("unused", "image", listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Нечитаемый документ"))))),
            x.documents().last().copy(pages = x.documents().last().pages.map { page -> page.copy(lines = page.lines.map { it.copy(text = it.text.replace("2026", "2020")) }) }),
            ScheduleDocument("unused", "image", listOf(ScheduleDocumentPage(listOf(selected, ScheduleDocumentLine("Все богослужения отменены")))))
        )
        for (document in cases) {
            val cache = ContactWiringFixtures.Cache(); val control = Controls().apply {
                html = x.monthly(); urls = x.nextUrls; documents[3] = document
            }
            val repository = repo(cache, control); val offer = repository.refresh(f.selected(), f.now, force = true)
            val row = offer.sourceCandidates.single { it.canConfirm(f.selected()) }
            val result = repository.confirmCandidate(f.selected(), row.id, f.now)
            assertNull(result.publication); assertNotEquals(InternetScheduleStatus.READY, result.status)
            assertEquals(x.nextUrls, control.requests.filter { it in x.nextUrls })
        }
    }

    @Test fun cancellationAndFailureAfterActualForeignOnePreserveExactOldCacheWithBothWithdrawalHistories() = runBlocking {
        for (hasWithdrawals in listOf(false, true)) {
            val cache = ContactWiringFixtures.Cache(); val control = Controls(); val accepted = accepted(cache, control)
            val old = accepted.copy(sourceHasWithdrawals = hasWithdrawals, dismissedCandidateIds = listOf(f.mapCandidate().id),
                candidateObservedIds = listOf("retained-linked-navigation-history"))
            cache.write(f.selected().id, ScheduleCacheCodec.encode(old)); val bytes = cache.read(f.selected().id)
            assertTrue(cache.safety.isEmpty()); assertTrue(old.dismissedCandidateIds.isNotEmpty())
            control.pauseAt = 2
            val job = launch { repo(cache, control).refresh(f.selected(), f.now.plusSeconds(3_600), force = true) }
            withTimeout(5_000) { control.reached.await() }
            assertTrue(cache.safety.isEmpty()); assertEquals(bytes, cache.read(f.selected().id))
            job.cancelAndJoin(); assertTrue(job.isCancelled)
            assertEquals(bytes, cache.read(f.selected().id)); assertEquals(old, repo(cache, Controls()).cached(f.selected()))
            val failed = Controls().apply { failRemaining = true }
            val next = repo(cache, failed).refresh(f.selected(), f.now.plusSeconds(7_200), force = true)
            assertTrue(cache.safety.isEmpty()); assertEquals(old.publication, next.publication); assertEquals(old.siteBinding, next.siteBinding)
            assertEquals(old.sourceUrl, next.sourceUrl); assertEquals(old.siteEvidenceUrl, next.siteEvidenceUrl)
            assertEquals(old.sourceHasWithdrawals, next.sourceHasWithdrawals); assertEquals(old.dismissedCandidateIds, next.dismissedCandidateIds)
            assertEquals(old.publication?.fetchedAt, next.publication?.fetchedAt)
        }
    }

    @Test fun wholeLeafFailureIsDurableBeforeAnyAttachmentFetchAndColdCancellationCannotReviveOldPublication() = runBlocking {
        val htmlCases = listOf(
            x.mutate { it.select("meta[property=og:image]").single().attr("content", "https://other.example/wrong.jpg") },
            x.mutate { it.select("footer").single().append("<div hidden>Все богослужения отменены</div>") },
            x.mutate { it.head().append("<script>const tick=2;</script>") }
        ) + listOf(
            x.bytes("schedule.html").toString(Charsets.UTF_8).replace("</body>", "<div>".repeat(8_192) + "x" + "</div>".repeat(8_192) + "</body>").toByteArray(),
            x.bytes("schedule.html").toString(Charsets.UTF_8).replace("</body>", "<!--" + "x".repeat(262_144) + "--></body>").toByteArray()
        )
        for (body in htmlCases) {
            val cache = ContactWiringFixtures.Cache(); val old = accepted(cache, Controls())
            val control = Controls().apply { html = body }; var task: Job? = null
            val durable = object : ScheduleCacheStore {
                override fun read(parishId: String) = cache.read(parishId)
                override fun write(parishId: String, value: String) = cache.write(parishId, value)
                override fun writeSafety(parishId: String, value: String) {
                    cache.writeSafety(parishId, value)
                    assertEquals(x.target, requireNotNull(ScheduleCacheCodec.decode(value, f.selected())).sourceUrl)
                    task?.cancel()
                }
            }
            val job = launch(start = CoroutineStart.LAZY) { repo(durable, control).refresh(f.selected(), f.now.plusSeconds(60), force = true) }
            task = job; job.start(); job.join(); assertTrue(job.isCancelled)
            assertTrue(cache.safety.isNotEmpty()); assertTrue(control.requests.none { it in control.urls })
            val cold = repo(cache, Controls()).cached(f.selected())
            assertNull(cold.publication); assertTrue(cold.publicationRejected); assertEquals(old.siteBinding, cold.siteBinding)
        }
    }

    @Test fun realTimedWithdrawalAfterTheIndexSurvivesCancellationOfTheNextImageAndKeepsOldAge() = runBlocking {
        val cache = ContactWiringFixtures.Cache(); val old = accepted(cache, Controls())
        val cancellation = ScheduleDocument("unused", "image", listOf(ScheduleDocumentPage(listOf(
            ScheduleDocumentLine(f.selected().name), ScheduleDocumentLine("14 ноября 2026 года"), ScheduleDocumentLine("08:00 Литургия отменена")
        ))))
        val parsed = ScheduleDocumentParser.inspect(cancellation, x.today, f.selected())
        assertTrue("Timed cancellation must keep its exact start", "2026-11-14T08:00" in parsed.withdrawnStarts)
        assertFalse(parsed.withdrawsAll)
        val control = Controls().apply { documents[2] = cancellation; pauseAt = 3 }
        val job = launch { repo(cache, control).refresh(f.selected(), f.now.plusSeconds(60), force = true) }
        withTimeout(5_000) { control.reached.await() }
        assertTrue(cache.safety.isNotEmpty())
        val observed = repo(cache, Controls()).cached(f.selected())
        assertEquals(11, observed.publication?.events?.size)
        assertFalse(requireNotNull(observed.publication).events.any { it.date == "2026-11-14" && it.time == "08:00" })
        assertEquals(old.publication?.fetchedAt, observed.publication?.fetchedAt)
        job.cancelAndJoin(); assertTrue(job.isCancelled)
        assertEquals(observed, repo(cache, Controls()).cached(f.selected()))
        assertTrue(observed.sourceHasWithdrawals)
    }
}
