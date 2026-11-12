package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

class BookTranslationCorpusTest {
    private val directory = Files.createTempDirectory("book-corpus-").toFile()
    private val cache = FileBookCache(directory)
    private val liturgy = ServiceBookKey("2026-11-11")
    private val nextLiturgy = ServiceBookKey("2026-11-15")
    private val sources = BookTranslationIndex.PARALLEL_SOURCES
    private val allUrls = sources.map { it.url }.toSet()
    private val oldPrayer = "Господи, помилуй."
    @After fun clean() { directory.deleteRecursively() }

    private fun page(original: String, russian: String) =
        "<table><tr><td>${Entities.escape(original)}</td><td>${Entities.escape(russian)}</td></tr></table>"
    private fun oldIndex() = BookTranslationIndex.fromParallelPage(
        Jsoup.parse(page(oldPrayer, "Сохраненный русский текст"), sources[0].url))
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
    private fun basePage(url: String): String? = when {
        "worship=liturgy" in url -> BookFixtures.html(if ("2026-11-15" in url) "liturgy-2026-11-15" else "liturgy-2026-11-11")
        "worship=vespers" in url -> BookFixtures.html("vespers-2026-11-12")
        "/biblia/days/" in url -> "<html></html>"
        url.startsWith("https://azbyka.ru/days/") -> CalendarFixtures.page(url.substringAfterLast('/'))
        else -> null
    }

    @Test fun oldUnversionedCorpusSurvivesMigrationAndCannotSuppressNewSources() = runBlocking {
        assertTrue(cache.writeTranslationCorpus(oldIndex().encode()))
        val old = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertFalse(old.current)
        assertFalse(old.completeFor(WorshipKind.LITURGY))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url != sources[1].url) error("This source is temporarily unavailable")
            // Store the actual response URL, not a label belonging to a different source.
            response("${url}?edition=verified", page("Царю Небесный, Утешителю, Душе истины.", "Новый тестовый русский текст"))
        })
        val result = repository.load(liturgy) as BookLoadResult.Ready
        assertEquals(allUrls, requests.toSet())
        assertEquals(3, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(FileBookCache(directory).readTranslationCorpus())
        assertTrue(saved.current)
        assertFalse(saved.completeFor(WorshipKind.LITURGY))
        assertEquals(setOf(sources[1].url), saved.completedSources)
        assertEquals("Сохраненный русский текст", saved.index.find(oldPrayer)?.russian)
        assertEquals("${sources[1].url}?edition=verified", saved.index.find("Царю Небесный, Утешителю, Душе истины.")?.source?.url)
    }

    @Test fun partialCorpusRetriesOnlyFailedSourcesOnTheNextNetworkBook() = runBlocking {
        var failing = true
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (failing && url == sources[2].url) error("Thanksgiving source unavailable")
            response(url, page("Молитва источника ${sources.indexOfFirst { it.url == url }}", "Тестовый русский текст"))
        })
        val first = repository.load(liturgy) as BookLoadResult.Ready
        assertEquals(BookSupplementStatus.RETRY_NEEDED, first.book.enrichment.russian)
        assertEquals(allUrls - sources[2].url, BookTranslationCorpus.decode(cache.readTranslationCorpus()).completedSources)
        requests.clear()
        failing = false
        val second = repository.load(nextLiturgy) as BookLoadResult.Ready
        assertEquals(listOf(sources[2].url), requests)
        assertEquals(BookSupplementStatus.AVAILABLE, second.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertTrue(saved.completeFor(WorshipKind.LITURGY))
        assertEquals(3, saved.index.size)
    }

    @Test fun versionSixWithAllSourceFlagsStillRechecksEverySourceAndKeepsOldPairs() = obsoleteVersionRetries(6)

    @Test fun versionSevenWithAllSourceFlagsStillRechecksEverySourceAndKeepsOldPairs() = obsoleteVersionRetries(7)

    private fun obsoleteVersionRetries(version: Int) = runBlocking {
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(oldIndex(), allUrls, version = version).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            response(url, "<html>Сервис временно недоступен</html>")
        })
        val result = repository.load(liturgy) as BookLoadResult.Ready
        assertEquals(allUrls, requests.toSet())
        assertEquals(3, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertTrue(saved.current)
        assertTrue(saved.completedSources.isEmpty())
        assertEquals("Сохраненный русский текст", saved.index.find(oldPrayer)?.russian)
    }

    @Test fun versionSevenPartialRefreshAddsTheVerifiedBoundaryAndRetainsExistingPairs() = runBlocking {
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(oldIndex(), allUrls, version = 7).encode()))
        val requests = mutableListOf<String>()
        val original = "Поминающе убо спасительную сию заповедь."
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url != BookTranslationIndex.TIMROT_FULL_SOURCE.url) error("Source temporarily unavailable")
            response(url, """<table><tr><td>Лик: Аминь.<p>Священник молится: $original</p></td>
                <td>Хор: Аминь.<p>Священник: Тестовый полный русский текст.</p></td></tr></table>""")
        })
        val result = repository.load(liturgy) as BookLoadResult.Ready
        assertEquals(allUrls, requests.toSet())
        assertEquals(3, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(FileBookCache(directory).readTranslationCorpus())
        assertTrue(saved.current)
        assertFalse(saved.completeFor(WorshipKind.LITURGY))
        assertEquals(setOf(BookTranslationIndex.TIMROT_FULL_SOURCE.url), saved.completedSources)
        assertEquals("Сохраненный русский текст", saved.index.find(oldPrayer)?.russian)
        assertEquals("Тестовый полный русский текст.", saved.index.find(original)?.russian)
        assertEquals(BookTranslationIndex.TIMROT_FULL_SOURCE, saved.index.find(original)?.source)
    }

    @Test fun completeCacheAvoidsRequestsButForceRechecksAllSourcesAndReportsPartialFailure() = runBlocking {
        val old = BookTranslationCorpus(oldIndex(), allUrls)
        assertTrue(cache.writeTranslationCorpus(old.encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url == sources[1].url) error("Unavailable")
            response(url, page("Новая молитва ${sources.indexOfFirst { it.url == url }}", "Новый тестовый перевод"))
        })
        val initial = repository.load(liturgy) as BookLoadResult.Ready
        assertEquals(BookSupplementStatus.AVAILABLE, initial.book.enrichment.russian)
        assertTrue(requests.isEmpty())
        val refreshed = repository.load(liturgy, force = true) as BookLoadResult.Ready
        assertEquals(allUrls, requests.toSet())
        assertEquals(3, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, refreshed.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertEquals(allUrls - sources[1].url, saved.completedSources)
        assertFalse(saved.completeFor(WorshipKind.LITURGY))
        assertEquals(old.index.find(oldPrayer), saved.index.find(oldPrayer))
    }

    @Test fun invalidOrEmptyCorpusCannotOverwriteLastUsefulFile() {
        val good = BookTranslationCorpus(oldIndex(), allUrls).encode()
        assertTrue(cache.writeTranslationCorpus(good))
        assertFalse(cache.writeTranslationCorpus("{broken"))
        assertFalse(cache.writeTranslationCorpus(BookTranslationCorpus().encode()))
        assertEquals(good, FileBookCache(directory).readTranslationCorpus())
    }

    @Test fun cancelledRussianRefreshLeavesPreviousCorpusAndBookIntact() = runBlocking {
        val corpus = BookTranslationCorpus(oldIndex(), allUrls).encode()
        val book = BookFixtures.book()
        assertTrue(cache.writeTranslationCorpus(corpus))
        assertTrue(cache.write(book))
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            throw CancellationException("Reader left")
        })
        try {
            repository.load(liturgy, force = true)
            fail("Cancellation must not become a completed refresh")
        } catch (_: CancellationException) { }
        assertEquals(corpus, cache.readTranslationCorpus())
        assertEquals(book, cache.read(liturgy))
    }
}
