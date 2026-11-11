package ru.liturgyguide.service.book

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

class ServiceBookRepositoryTest {
    private val directory = Files.createTempDirectory("service-books-").toFile()
    private val key = ServiceBookKey("2026-11-11")
    @After fun clean() { directory.deleteRecursively() }

    @Test fun downloadedBookSurvivesNewRepositoryWithNoNetwork() = runBlocking {
        val online = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            ScheduleResponse(url, (if (url.contains("/worships/")) BookFixtures.html() else "<html></html>").toByteArray(), "text/html;charset=utf-8")
        }, downloadRussian = false)
        val result = online.load(key) as BookLoadResult.Ready
        assertTrue(result.saved)
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("Network must not be needed") })
        val reopened = offline.load(key) as BookLoadResult.Ready
        assertTrue(reopened.fromCache)
        assertEquals(result.book, reopened.book)
    }

    @Test fun failedRefreshKeepsWholePreviousBook() = runBlocking {
        val cache = FileBookCache(directory)
        val original = BookFixtures.book()
        assertTrue(cache.write(original))
        val broken = ServiceBookRepository(cache, ScheduleTransport { url -> ScheduleResponse(url, "<html>Unavailable</html>".toByteArray(), "text/html") })
        val result = broken.load(key, force = true) as BookLoadResult.Failed
        assertEquals(BookFailure.INVALID_SOURCE, result.reason)
        assertEquals(original, cache.read(key))
        assertEquals(original, result.cachedBook)
    }

    @Test fun incompleteSupplementsRemainVisibleInCacheAndForceActuallyRetriesThem() = runBlocking {
        var available = false
        var russianRequests = 0
        var scriptureRequests = 0
        val transport = ScheduleTransport { url ->
            val html = when {
                url.contains("/worships/") -> BookFixtures.html()
                url.contains("/biblia/days/") -> { scriptureRequests++; if (available) dailyReadings() else "<html>Unavailable</html>" }
                url.startsWith("https://azbyka.ru/days/") -> if (available) CalendarFixtures.page(key.date) else "<html>Unavailable</html>"
                else -> { russianRequests++; if (available) parallelPrayer() else "<html>Unavailable</html>" }
            }
            ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
        }
        val repository = ServiceBookRepository(FileBookCache(directory), transport)
        val first = repository.load(key) as BookLoadResult.Ready
        assertTrue(first.saved)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, first.book.enrichment.russian)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, first.book.enrichment.scripture)
        assertNotNull(first.warning)
        val cached = repository.load(key) as BookLoadResult.Ready
        assertTrue(cached.fromCache)
        assertNotNull(cached.warning)
        assertEquals(3, russianRequests)
        assertEquals(1, scriptureRequests)

        available = true
        val refreshed = repository.load(key, force = true) as BookLoadResult.Ready
        assertEquals(BookSupplementStatus.AVAILABLE, refreshed.book.enrichment.russian)
        assertEquals(BookSupplementStatus.AVAILABLE, refreshed.book.enrichment.scripture)
        assertFalse(refreshed.book.enrichment.needsRetry)
        assertNull(refreshed.warning)
        assertTrue(refreshed.book.translatedPrayerCount > first.book.translatedPrayerCount)
        assertEquals(6, russianRequests)
        assertEquals(2, scriptureRequests)
        assertTrue(ServiceBookIntegrity.valid(refreshed.book))
    }

    @Test fun failedSupplementRefreshPreservesExactPreviouslyVerifiedTranslations() = runBlocking {
        var available = true
        var russianRequests = 0
        val repository = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            val html = when {
                url.contains("/worships/") -> BookFixtures.html()
                url.contains("/biblia/days/") -> if (available) dailyReadings() else error("Offline")
                url.startsWith("https://azbyka.ru/days/") -> if (available) CalendarFixtures.page(key.date) else error("Offline")
                else -> { russianRequests++; if (available) parallelPrayer() else error("Offline") }
            }
            ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
        })
        val original = (repository.load(key) as BookLoadResult.Ready).book
        available = false
        val refreshed = repository.load(key, force = true) as BookLoadResult.Ready
        assertEquals(6, russianRequests) // Force must not be swallowed by the corpus cache.
        assertEquals(original.blocks.map { it.russian }, refreshed.book.blocks.map { it.russian })
        assertEquals(original.blocks.map { it.translationSource }, refreshed.book.blocks.map { it.translationSource })
        assertTrue(refreshed.book.enrichment.needsRetry)
        assertNotNull(refreshed.warning)
        assertTrue(refreshed.saved)
        assertTrue(ServiceBookIntegrity.valid(refreshed.book))
    }

    @Test fun failedRefreshDoesNotCarryTranslationToChangedOriginal() = runBlocking {
        val cache = FileBookCache(directory)
        val original = BookFixtures.book()
        val target = original.blocks.first { it.kind == BookBlockKind.PRAYER && "благословено царство" in BookText.normalized(it.original) }
        val enriched = original.copy(chapters = original.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map {
            if (it.id == target.id) it.copy(russian = "Проверенный перевод") else it
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        assertTrue(cache.write(enriched))
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            val html = if (url.contains("/worships/")) BookFixtures.html().replace("Благослове́но", "Благослове́нно") else "<html></html>"
            ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
        }, downloadRussian = false)
        val refreshed = (repository.load(key, force = true) as BookLoadResult.Ready).book
        assertFalse(refreshed.blocks.any { it.russian == "Проверенный перевод" })
    }

    private fun parallelPrayer(): String {
        val original = BookFixtures.book().blocks.first { it.kind == BookBlockKind.PRAYER }.original
        return "<table><tr><td>${org.jsoup.nodes.Entities.escape(original)}</td><td>Проверенный русский перевод этой молитвы</td></tr></table>"
    }

    @Test fun publishedExactTranslationTakesPrecedenceOverEditorialParaphrase() = runBlocking {
        val original = BookFixtures.book().blocks.first { it.kind == BookBlockKind.PRAYER }.original
        val editorial = BookTranslationIndex.fromAssets(null, com.google.gson.Gson().toJson(listOf(
            mapOf("original" to original, "russian" to "Непроверенное редакционное изложение"))))
        val repository = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            ScheduleResponse(url, (if (url.contains("/worships/")) BookFixtures.html() else parallelPrayer())
                .toByteArray(), "text/html;charset=utf-8")
        }, translations = editorial)
        val result = (repository.load(key) as BookLoadResult.Ready).book.blocks.first { it.original == original }
        assertEquals("Проверенный русский перевод этой молитвы", result.russian)
        assertNotEquals(BookTranslationIndex.EDITORIAL_SOURCE, result.translationSource)
    }

    @Test fun aRefreshDoesNotRetainAnOldEditorialParaphraseAsPublishedRussian() = runBlocking {
        val cache = FileBookCache(directory)
        val plain = BookFixtures.book()
        val target = plain.blocks.first { it.kind == BookBlockKind.PRAYER }
        val old = plain.copy(chapters = plain.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map {
            if (it.id == target.id) it.copy(russian = "Непроверенное изложение", translationSource = BookTranslationIndex.EDITORIAL_SOURCE) else it
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        assertTrue(cache.write(old))
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            ScheduleResponse(url, BookFixtures.html().toByteArray(), "text/html;charset=utf-8")
        }, downloadRussian = false)
        val refreshed = (repository.load(key, force = true) as BookLoadResult.Ready).book
        assertNull(refreshed.blocks.first { it.id == target.id }.russian)
        assertEquals(target.original, refreshed.blocks.first { it.id == target.id }.original)
    }

    private fun dailyReadings(): String {
        val passages = mapOf(
            "1Thes.4:1-12" to (1..12).map { "1Thes.4:$it" },
            "Gal.5:22-6:2" to ((22..26).map { "Gal.5:$it" } + (1..2).map { "Gal.6:$it" }),
            "Lk.11:9-13" to (9..13).map { "Lk.11:$it" },
            "Mt.11:27-30" to (27..30).map { "Mt.11:$it" }
        )
        return passages.entries.joinToString("") { (reference, ids) ->
            "<p><a href='https://azbyka.ru/biblia/?$reference'>$reference</a></p>" +
                "<div class='tbl-content' data-book-slug='${reference.substringBefore('.')}' style='grid-template-rows:repeat(${ids.size + 1},auto)'>" +
                ids.joinToString("") { "<div class='verse' data-lang='r' data-verse='$it'>Русский текст стиха $it</div>" } + "</div>"
        }
    }

    @Test fun pinnedPreviousVersionRemainsReadableAfterRefresh() {
        val cache = FileBookCache(directory)
        val original = BookFixtures.book()
        val modified = original.copy(title = "Литургия · обновленная редакция", contentHash = "").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        assertTrue(cache.write(original)); assertTrue(cache.write(modified))
        assertEquals(modified, cache.read(key))
        assertEquals(original, cache.readVersion(key, original.contentHash))
    }

    @Test fun corruptedFileDoesNotBecomeAnEmptyBook() {
        val cache = FileBookCache(directory)
        assertTrue(cache.write(BookFixtures.book()))
        directory.listFiles()!!.first { it.extension == "json" }.writeText("{\"schemaVersion\":1}")
        assertNull(cache.read(key))
    }
}
