package ru.liturgyguide.service.book

import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class MatinsRankRepositoryTest {
    private val directory = Files.createTempDirectory("rank-repository-").toFile()
    private val disk = FileBookCache(directory)
    private val writes = CopyOnWriteArrayList<ServiceBook>()
    private val cache = object : BookCache by disk {
        override fun write(book: ServiceBook): Boolean { writes += book; return disk.write(book) }
    }
    private val calls = CopyOnWriteArrayList<String>()
    private val sourceCalls get() = calls.filter { it.startsWith("https://azbyka.ru/worships/?") }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun bothRealPairsUseTwoSourceRequestsOneFinalWriteAndNoRequestsAfterRestart() = runBlocking {
        for (date in MatinsRankFixtures.dates) {
            calls.clear(); writes.clear()
            val key = MatinsRankFixtures.key(date)
            val daily = ServiceBookParser().parse(MatinsRankFixtures.document(date), key.copy(topLevel = null), 12345)
            assertTrue(disk.write(daily))
            val result = repository(pairTransport(date)).load(key) as BookLoadResult.Ready
            assertTrue(result.saved); assertFalse(result.fromCache)
            assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), MatinsRankFixtures.url(key)), sourceCalls)
            assertEquals(listOf(result.book), writes)
            assertEquals(key, result.book.key)
            assertEquals(BookRankSelection("0", "", "3"), result.book.rankSelection)
            assertEquals(MatinsRankFixtures.url(key), result.book.sourceUrl)
            assertTrue(ServiceBookIntegrity.valid(result.book))
            assertEquals("Preflight is never published as a daily book", daily, disk.read(daily.key))
            assertEquals(daily, disk.readVersion(daily.key, daily.contentHash))
            assertEquals(listOf("https://azbyka.ru/biblia/days/$date", directUrl(date)), calls.filterNot { it in sourceCalls })
            calls.clear(); writes.clear()
            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
                calls += url; error("Offline must use its own persisted rank cache")
            }, downloadRussian = false)
            val restored = offline.load(key) as BookLoadResult.Ready
            assertTrue(restored.fromCache)
            assertEquals(result.book, restored.book)
            assertTrue(calls.isEmpty()); assertTrue(writes.isEmpty())
        }
    }

    @Test fun syntheticNonzeroIdIsUsedByTheActualRepositoryProtocol() = runBlocking {
        val key = MatinsRankFixtures.key()
        val result = repository(pairTransport(key.date, "273")).load(key) as BookLoadResult.Ready
        assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), MatinsRankFixtures.url(key, "273")), sourceCalls)
        assertEquals("273", result.book.rankSelection?.mainEvent)
        assertEquals(key, result.book.key)
        assertEquals(MatinsRankFixtures.url(key, "273"), result.book.sourceUrl)
        assertEquals(1, writes.size)
    }

    @Test fun invalidPreflightStopsAfterOneRequestAndHigherFeastsHaveAnHonestError() = runBlocking {
        val key = MatinsRankFixtures.key()
        for (level in listOf("1", "2", "", "unknown")) {
            calls.clear(); writes.clear()
            val before = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(before, "bg_worships_mainEvent").attr("data-level", level)
            val result = repository(ScheduleTransport { url ->
                calls += url; response(url, before.outerHtml())
            }).load(key) as BookLoadResult.Failed
            assertEquals(BookFailure.INVALID_SOURCE, result.reason)
            if (level in listOf("1", "2")) assertTrue(result.message.contains("другой праздничный порядок"))
            assertEquals(listOf(key.copy(topLevel = null).sourceUrl()), calls)
            assertNull(result.cachedBook); assertNull(disk.read(key)); assertTrue(writes.isEmpty())
        }
        calls.clear()
        val result = repository(pairTransport()).load(key.copy(topLevel = "4")) as BookLoadResult.Failed
        assertEquals(BookFailure.INVALID_KEY, result.reason)
        assertTrue(calls.isEmpty())
    }

    @Test fun aFailedSecondRequestCannotPublishPreflightOrBorrowTheDailyCache() = runBlocking {
        val key = MatinsRankFixtures.key()
        val daily = ServiceBookParser().parse(MatinsRankFixtures.document(), key.copy(topLevel = null), 12345)
        assertTrue(disk.write(daily))
        for (networkFailure in listOf(true, false)) {
            calls.clear(); writes.clear()
            val result = repository(ScheduleTransport { url ->
                calls += url
                if (url == daily.key.sourceUrl()) response(url, MatinsRankFixtures.text("default-${key.date}.html"))
                else if (networkFailure) throw IOException("Test second source failure")
                else response(url, MatinsRankFixtures.text("default-${key.date}.html"))
            }).load(key) as BookLoadResult.Failed
            assertEquals(if (networkFailure) BookFailure.NETWORK else BookFailure.INVALID_SOURCE, result.reason)
            assertEquals(2, sourceCalls.size)
            assertEquals(2, calls.size)
            assertNull(result.cachedBook); assertNull(disk.read(key)); assertTrue(writes.isEmpty())
            assertEquals(daily, disk.read(daily.key))
        }
    }

    @Test fun cancellationBetweenResponsesPreventsTheSecondRequestAndEveryWrite() = runBlocking {
        val key = MatinsRankFixtures.key()
        val received = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = ScheduleTransport { url ->
            calls += url
            // Model a blocking transport that returns its completed response after cancellation.
            withContext(NonCancellable) {
                received.complete(Unit)
                releaseResponse.await()
                response(url, MatinsRankFixtures.text("default-${key.date}.html"))
            }
        }
        val job = launch { repository(transport).load(key) }
        try {
            withTimeout(5_000) { received.await() }
            job.cancel()
            releaseResponse.complete(Unit)
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(listOf(key.copy(topLevel = null).sourceUrl()), calls)
            assertTrue(writes.isEmpty()); assertNull(disk.read(key)); assertNull(disk.read(key.copy(topLevel = null)))
        } finally { releaseResponse.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun refreshKeepsIntentKeyAndTheAcceptedOldVersionUntilExplicitAcceptance() = runBlocking {
        val key = MatinsRankFixtures.key()
        val first = (repository(pairTransport()).load(key) as BookLoadResult.Ready).book
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val position = BookReadingPosition(key, first.contentHash, "rank-test", first.blocks.first().id,
            first.chapters.first().id, fraction = .41f, mode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        calls.clear(); writes.clear()
        val next = (repository(pairTransport(key.date, "273")).load(key, force = true) as BookLoadResult.Ready).book
        assertEquals(key, next.key); assertNotEquals(first.contentHash, next.contentHash)
        assertEquals("273", next.rankSelection?.mainEvent)
        assertEquals(2, sourceCalls.size); assertEquals(listOf(next), writes)
        assertEquals(position, store.positionFor(key, "rank-test"))
        assertEquals(first, disk.readVersion(key, first.contentHash)); assertEquals(next, disk.read(key))
        assertTrue(store.acceptVersion(first, next, "rank-test"))
        assertEquals(next.contentHash, store.positionFor(key, "rank-test")?.contentHash)
        calls.clear(); writes.clear()
        val failure = repository(ScheduleTransport { url -> calls += url; throw IOException("Refresh offline") })
            .load(key, force = true) as BookLoadResult.Failed
        assertEquals(next, failure.cachedBook)
        assertEquals(next, disk.read(key)); assertEquals(first, disk.readVersion(key, first.contentHash))
        assertTrue(writes.isEmpty())
    }

    @Test fun enrichedParseRetainsTheSameResolutionAndVerifiedOriginalText() = runBlocking {
        val plain = MatinsRankFixtures.parse()
        val target = plain.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length in 40..250 && it.russian == null }
        val source = BookSource("Synthetic exact-pair QA fixture", "https://example.org/rank-translation-fixture")
        val table = Jsoup.parse("<table><tr><td></td><td></td></tr></table>")
        table.select("td")[0].text(target.original)
        table.select("td")[1].text("Проверочная русская пара для теста метаданных источника")
        val pairs = BookTranslationIndex.fromParallelPage(table, source)
        assertTrue("Test precondition: exact pair parsed", pairs.size > 0)
        val ready = ServiceBookRepository(cache, pairTransport(), translations = pairs, downloadRussian = false)
            .load(plain.key) as BookLoadResult.Ready
        assertEquals(plain.key, ready.book.key)
        assertEquals(plain.rankSelection, ready.book.rankSelection)
        assertEquals(plain.sourceUrl, ready.book.sourceUrl)
        assertEquals(plain.blocks.map { it.original }, ready.book.blocks.map { it.original })
        val translated = ready.book.blocks.single { it.id == target.id }
        assertNotNull(translated.russian); assertEquals(source, translated.translationSource)
        assertEquals(2, sourceCalls.size); assertEquals(listOf(ready.book), writes)
        assertTrue(ServiceBookIntegrity.valid(ready.book))
    }

    private fun repository(transport: ScheduleTransport) = ServiceBookRepository(cache, transport, downloadRussian = false)
    private fun pairTransport(date: String = MatinsRankFixtures.dates.first(), main: String = "0") = ScheduleTransport { url ->
        calls += url
        val key = MatinsRankFixtures.key(date)
        when (url) {
            key.copy(topLevel = null).sourceUrl() -> {
                val before = MatinsRankFixtures.document(date)
                MatinsRankFixtures.selected(before, "bg_worships_mainEvent").attr("value", main)
                response(url, before.outerHtml())
            }
            MatinsRankFixtures.url(key, main) -> {
                val after = MatinsRankFixtures.document(date, true)
                MatinsRankFixtures.selected(after, "bg_worships_mainEvent").attr("value", main)
                response(url, after.outerHtml())
            }
            "https://azbyka.ru/biblia/days/$date", directUrl(date) -> response(url, "<html><body></body></html>")
            else -> error("Unexpected protocol request: $url")
        }
    }
    private fun directUrl(date: String) = "https://azbyka.ru/biblia/?" +
        (if (date == "2026-11-13") "Lk.10:1-21" else "Lk.21:12-19") + "&c~r"
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
}
