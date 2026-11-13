package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ParishRepository
import java.io.IOException
import java.time.Instant

/** Frozen public documents plus synthetic transport/cache control; no network is claimed. */
class ForeignImageScheduleRepositoryTest {
    private val curated = ParishRepository(requireNotNull(javaClass.getResource("/churches.json")).readText()).directory.churches
    private val parent = ImageScheduleFixture.parent
    private val child = curated.single { it.id == "vsetsaritsa_nakhabino" }.copy(website = parent)
    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val oldEvents = listOf(InternetService("2026-11-13", "17:00", "Вечерня", "service_block"),
        InternetService("2026-11-14", "08:00", "Литургия", "liturgy"))
    private val oldPublication = SchedulePublication(child.id, child.timezone, parent,
        ImageScheduleFixture.document(3, false).sourceUrl, now.minusSeconds(3_600).toString(), oldEvents,
        documentFormat = "image", linkedFrom = parent)
    // Explicit accepted-state protocol fixture, not a new network identity proof.
    private val binding = ScheduleEntityIdentity.bind(child, parent, child.sourceUrl, child.name, ScheduleBindingMethod.ARTICLE_SCOPE)

    private class Cache : ScheduleCacheStore {
        val entries = mutableMapOf<String, String>()
        val safety = mutableListOf<String>()
        override fun read(parishId: String) = entries[parishId]
        override fun write(parishId: String, value: String) { entries[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safety += value; write(parishId, value) }
    }

    private fun initial(cache: Cache, publication: SchedulePublication? = oldPublication,
        rejected: Boolean = false, withdrawals: Boolean = false): InternetScheduleState {
        val state = InternetScheduleState(child.id, InternetScheduleStatus.READY, parent,
            publication?.sourceUrl ?: parent, now.minusSeconds(3_600).toString(), publication,
            configuredWebsite = parent, siteEvidenceUrl = child.sourceUrl, siteBinding = binding,
            publicationRejected = rejected, sourceHasWithdrawals = withdrawals)
        cache.write(child.id, ScheduleCacheCodec.encode(state))
        assertEquals(state, requireNotNull(ScheduleCacheCodec.decode(cache.read(child.id), child)))
        return state
    }

    private fun page(documents: List<ScheduleDocument>) = "<h1>Расписание богослужений</h1>" +
        documents.joinToString("") { "<img src='${it.sourceUrl}'>" }
    private fun repo(cache: Cache, documents: List<ScheduleDocument>, known: List<Parish> = curated,
        transport: (suspend (String) -> ScheduleResponse)? = null) = InternetScheduleRepository(
        ScheduleTransport { url -> transport?.invoke(url) ?: if (url == parent)
            ScheduleResponse(url, page(documents).toByteArray(), "text/html")
        else ScheduleResponse(url, byteArrayOf(1, 2), "image/jpeg") }, cache,
        ScheduleDocumentExtractor { response -> documents.singleOrNull { it.sourceUrl == response.url } },
        siteResolver = ScheduleSiteResolver { null }, curatedPublishers = { known })

    @Test fun knownOtherFirstImageCannotDurablyEraseTheChildBeforeTheNextSuspendingFetch() = runBlocking {
        for (geometry in listOf(false, true)) {
            val documents = (1..3).map { ImageScheduleFixture.document(it, geometry) }
            val cache = Cache(); val old = initial(cache)
            val bytes = cache.read(child.id)
            val reached = CompletableDeferred<Unit>()
            val never = CompletableDeferred<ScheduleResponse>()
            val repository = repo(cache, documents, transport = { url -> when (url) {
                parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
                documents[0].sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
                else -> { reached.complete(Unit); never.await() }
            } })
            val job = launch { repository.refresh(child, now, force = true) }
            withTimeout(10_000) { reached.await() }
            assertTrue("Foreign1 must not call durable safety: geometry=$geometry", cache.safety.isEmpty())
            assertEquals(bytes, cache.read(child.id))
            assertEquals(old, repo(cache, documents).cached(child))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(bytes, cache.read(child.id))
            assertEquals(old, repo(cache, documents).cached(child))
        }
    }

    @Test fun foreignOnlyAttachmentAndLaterTransportFailureRetainOldPublicationAgeAndAuthority() = runBlocking {
        for (geometry in listOf(false, true)) {
            val documents = (1..3).map { ImageScheduleFixture.document(it, geometry) }
            val cache = Cache(); val old = initial(cache)
            val repository = repo(cache, documents, transport = { url -> when (url) {
                parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
                documents[0].sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
                else -> throw IOException("Explicit test failure after foreign1")
            } })
            val finished = repository.refresh(child, now, force = true)
            assertEquals(old.publication, finished.publication)
            assertEquals(old.siteBinding, finished.siteBinding)
            assertEquals(old.siteEvidenceUrl, finished.siteEvidenceUrl)
            assertEquals(old.sourceUrl, finished.sourceUrl)
            assertFalse(finished.publicationRejected); assertFalse(finished.sourceHasWithdrawals)
            assertTrue(cache.safety.isEmpty())
            assertEquals(finished, repo(cache, documents).cached(child))
        }
    }

    @Test fun completeLinkedImageReplayKeepsChildStartsAndTheParentsExistingContinuation() = runBlocking {
        for (geometry in listOf(false, true)) {
            val documents = (1..3).map { ImageScheduleFixture.document(it, geometry) }
            val cache = Cache()
            val ready = repo(cache, documents).refresh(child, now, force = true)
            assertEquals(InternetScheduleStatus.READY, ready.status)
            val publication = requireNotNull(ready.publication)
            assertEquals(documents[2].sourceUrl, publication.sourceUrl)
            assertEquals(listOf("2026-11-13T17:00", "2026-11-14T08:00", "2026-11-14T17:00", "2026-11-15T08:00"),
                publication.events.filter { it.date in setOf("2026-11-13", "2026-11-14", "2026-11-15") }
                    .map { "${it.date}T${it.time}" }.sorted())
            assertFalse(publication.events.any { it.time in setOf("07:00", "09:30") })
            assertEquals(ready, repo(cache, documents).cached(child))
            val offline = repo(cache, documents, transport = { throw IOException("Test offline") })
                .refresh(child, now.plusSeconds(60), force = true)
            assertEquals(publication, offline.publication)
            val joined = requireNotNull(ScheduleImageContinuation.join(documents[0], documents[1], parent,
                ImageScheduleFixture.parish))
            assertTrue(ScheduleDocumentParser.inspect(joined, ImageScheduleFixture.today, ImageScheduleFixture.parish)
                .events.any { it.date == "2026-11-29" && it.time == "09:30" })
        }
    }

    @Test fun anUnprovedOtherAndTheSameImageAsPrimaryKeepThePreviousQuarantinePolicy() = runBlocking {
        val first = ImageScheduleFixture.document(1, false)
        val cache = Cache(); initial(cache)
        val withoutProof = repo(cache, listOf(first), known = emptyList()).refresh(child, now, force = true)
        assertNull(withoutProof.publication); assertTrue(withoutProof.publicationRejected)
        assertFalse(withoutProof.sourceHasWithdrawals); assertTrue(cache.safety.isNotEmpty())
        val primaryCache = Cache(); initial(primaryCache)
        val primary = first.copy(sourceUrl = parent)
        val direct = repo(primaryCache, listOf(primary), transport = {
            ScheduleResponse(parent, byteArrayOf(1), "image/jpeg")
        }).refresh(child, now, force = true)
        assertNull(direct.publication); assertTrue(direct.publicationRejected)
        assertFalse(direct.sourceHasWithdrawals); assertTrue(primaryCache.safety.isNotEmpty())
    }

    @Test fun foreignCancellationAndUnknownQualityStillReachDurableSafetyBeforeSuspension() = runBlocking {
        val first = ImageScheduleFixture.document(1, false)
        val originals = first.pages.single().lines
        val bad = listOf(first.copy(pages = listOf(first.pages.single().copy(
            lines = originals + ScheduleDocumentLine("Все службы отменены")))),
            first.copy(pages = listOf(first.pages.single().copy(sourceTruncated = true))))
        for (unsafe in bad) {
            val cache = Cache(); initial(cache)
            val second = ImageScheduleFixture.document(2, false)
            val documents = listOf(unsafe, second)
            val reached = CompletableDeferred<Unit>(); val never = CompletableDeferred<ScheduleResponse>()
            val repository = repo(cache, documents, transport = { url -> when (url) {
                parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
                unsafe.sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
                else -> { reached.complete(Unit); never.await() }
            } })
            val job = launch { repository.refresh(child, now, force = true) }
            // A safetyConflict stops the attempt before a second GET; an ordinary
            // foreign cancellation continues. Observe its synchronous safety receipt.
            if (unsafe.pages.single().sourceTruncated) {
                withTimeout(10_000) { job.join() }
            } else {
                withTimeout(10_000) { reached.await() }; job.cancelAndJoin()
            }
            assertTrue(cache.safety.isNotEmpty())
            val cold = repo(cache, documents).cached(child)
            assertNull(cold.publication); assertTrue(cold.publicationRejected)
        }
    }

    @Test fun selectedCancellationStillRemovesOnlyItsOldStartBeforeTheNextSuspension() = runBlocking {
        val original = ImageScheduleFixture.document(3, false)
        var changed = 0
        val cancelled = ImageScheduleFixture.changed(original) { line ->
            if (Regex("^13\\s+пятница").containsMatchIn(line.text)) {
                changed++; line.copy(text = line.text + " отменена")
            } else line
        }
        assertEquals(1, changed)
        val documents = listOf(cancelled, ImageScheduleFixture.document(2, false))
        val cache = Cache(); initial(cache)
        val reached = CompletableDeferred<Unit>(); val never = CompletableDeferred<ScheduleResponse>()
        val repository = repo(cache, documents, transport = { url -> when (url) {
            parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
            cancelled.sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
            else -> { reached.complete(Unit); never.await() }
        } })
        val job = launch { repository.refresh(child, now, force = true) }
        withTimeout(10_000) { reached.await() }
        assertTrue(cache.safety.isNotEmpty())
        val cold = repo(cache, documents).cached(child)
        assertEquals(oldPublication.copy(events = oldEvents.takeLast(1)), cold.publication)
        assertTrue(cold.sourceHasWithdrawals)
        assertFalse(cold.publicationRejected)
        assertEquals(binding, cold.siteBinding)
        job.cancelAndJoin()
        assertEquals(cold, repo(cache, documents).cached(child))
    }

    @Test fun aPlainOcrSectionAfterTheKnownForeignScheduleKeepsQuarantineBeforeTheNextFetch() = runBlocking {
        val first = ImageScheduleFixture.document(1, false)
        val mixed = first.copy(pages = listOf(first.pages.single().copy(lines = first.pages.single().lines +
            listOf(ScheduleDocumentLine("Расписание Всецарицы"), ScheduleDocumentLine("13 пятница 17:00 Вечерня")))))
        val documents = listOf(mixed, ImageScheduleFixture.document(2, false))
        val cache = Cache(); initial(cache)
        val reached = CompletableDeferred<Unit>(); val never = CompletableDeferred<ScheduleResponse>()
        val repository = repo(cache, documents, transport = { url -> when (url) {
            parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
            mixed.sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
            else -> { reached.complete(Unit); never.await() }
        } })
        val job = launch { repository.refresh(child, now, force = true) }
        withTimeout(10_000) { reached.await() }
        assertTrue(cache.safety.isNotEmpty())
        val cold = repo(cache, documents).cached(child)
        assertNull(cold.publication); assertTrue(cold.publicationRejected)
        assertFalse(cold.sourceHasWithdrawals)
        job.cancelAndJoin()
        assertEquals(cold, repo(cache, documents).cached(child))
    }

    @Test fun partialWithdrawalSurvivorAndCompleteTombstoneAreNotClearedByTheForeignAttachment() = runBlocking {
        val documents = (1..3).map { ImageScheduleFixture.document(it, false) }
        for (complete in listOf(false, true)) {
            val cache = Cache()
            val survivor = oldPublication.copy(events = oldEvents.takeLast(1))
            val old = initial(cache, if (complete) null else survivor, rejected = complete, withdrawals = true)
            val bytes = cache.read(child.id)
            val reached = CompletableDeferred<Unit>(); val never = CompletableDeferred<ScheduleResponse>()
            val repository = repo(cache, documents, transport = { url -> when (url) {
                parent -> ScheduleResponse(url, page(documents).toByteArray(), "text/html")
                documents[0].sourceUrl -> ScheduleResponse(url, byteArrayOf(1), "image/jpeg")
                else -> { reached.complete(Unit); never.await() }
            } })
            val job = launch { repository.refresh(child, now, force = true) }
            withTimeout(10_000) { reached.await() }; assertEquals(bytes, cache.read(child.id)); job.cancelAndJoin()
            assertEquals(old, repo(cache, documents).cached(child))
            assertTrue(cache.safety.isEmpty())
            assertEquals(bytes, cache.read(child.id))
        }
    }
}
