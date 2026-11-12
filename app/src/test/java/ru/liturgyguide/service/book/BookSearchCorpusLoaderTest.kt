package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences

/** Loading and route contracts. Small synthetic books below are UI fixtures, not provider evidence. */
class BookSearchCorpusLoaderTest {
    private val current = fixture(WorshipKind.VESPERS, "Вечерний свет")
    private val other = fixture(WorshipKind.MATINS, "Слава в вышних Богу", "Слава в вышних Богу и на земле мир")
    private val journey = BookJourneyContext("temple", "2026-11-12", "18:50", "Вечерня. Утреня",
        listOf(current.key, other.key))
    private val preferences = AcceptanceMemoryPreferences()
    private val store = BookReadingStore(preferences)

    @Test fun actualMatinsNovember13PhraseIsFoundFromVespersWithoutChangingEitherCursor() = runBlocking {
        val html = requireNotNull(javaClass.classLoader?.getResource("matins-rank/default-2026-11-13.html")).readText()
        val matins = ServiceBookParser().parse(Jsoup.parse(html), other.key.copy(churchType = "34"), 1)
        assertTrue(ServiceBookIntegrity.valid(matins))
        val evening = fixture(WorshipKind.VESPERS, "Вечерний свет", churchType = "34")
        val sourceJourney = journey.copy(parts = listOf(evening.key, matins.key))
        val accepted = position(matins).copy(journeyContext = sourceJourney)
        assertTrue(store.save(position(evening).copy(journeyContext = sourceJourney)))
        assertTrue(store.save(accepted))
        val before = preferences.values.toMap()
        val books = FakeBooks(matins)
        val corpus = BookSearchCorpusLoader.load(evening, sourceJourney, "temple", books, store)
        val hits = BookCorpusSearch.find(corpus.books, "Слава в вышних", BookReadingMode.ORIGINAL)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.bookKey == matins.key && it.validatedFor(matins) != null })
        assertEquals(before, preferences.values)
        assertEquals(0, books.loads)
    }

    @Test fun acceptedEditionWinsOverLatestWithoutAnyLatestLookup() = runBlocking {
        assertTrue(store.save(position(other)))
        val latest = other.copy(contentHash = "c".repeat(64))
        val books = FakeBooks(latest).apply { exact = other }
        val before = preferences.values.toMap()
        val corpus = BookSearchCorpusLoader.load(current, journey, "temple", books, store)
        assertEquals(listOf(current, other), corpus.books)
        assertNull(corpus.error)
        assertEquals(0, books.latestReads)
        assertEquals(0, books.loads)
        assertEquals(before, preferences.values)
    }

    @Test fun acceptedMissingEditionNeverFallsBackToCachedOrLoad() = runBlocking {
        assertTrue(store.save(position(other)))
        val books = FakeBooks(other.copy(contentHash = "c".repeat(64))).apply { exact = null }
        val before = preferences.values.toMap()
        val corpus = BookSearchCorpusLoader.load(current, journey, "temple", books, store)
        assertEquals(listOf(current), corpus.books)
        assertNotNull(corpus.error)
        assertEquals(WorshipKind.MATINS, corpus.otherPart)
        assertEquals(0, books.latestReads)
        assertEquals(0, books.loads)
        assertEquals(before, preferences.values)
    }

    @Test fun newOtherBookMustHaveAnExactDurableEditionAndSavedFalseIsNeverOpenable() = runBlocking {
        for (saved in listOf(false, true)) {
            val books = FakeBooks(null).apply {
                fetched = BookLoadResult.Ready(other, false, saved = saved)
                exact = if (saved) null else other
            }
            val corpus = BookSearchCorpusLoader.load(current, journey, "temple", books, store)
            assertEquals(listOf(current), corpus.books)
            assertNotNull(corpus.error)
            assertTrue(preferences.values.isEmpty())
            assertEquals(1, books.loads)
        }
        val books = FakeBooks(null).apply { fetched = BookLoadResult.Ready(other, false); exact = other }
        assertEquals(listOf(current, other), BookSearchCorpusLoader.load(current, journey, "temple", books, store).books)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun aStandaloneOpeningDoesNotLoadOtherBooksOrWriteAnything() = runBlocking {
        val books = FakeBooks(other)
        assertEquals(BookSearchCorpus(listOf(current)), BookSearchCorpusLoader.load(current, null, "temple", books, store))
        assertEquals(0, books.latestReads)
        assertEquals(0, books.loads)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun cancellingAnOpeningCancelsItsLoaderWithoutPublishingOrWriting() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var published = false
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? = null
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { BookSearchCorpusLoader.load(current, journey, "temple", books, store); published = true }
        entered.await()
        job.cancelAndJoin()
        assertFalse(published)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun pendingSearchSurvivesSerializationWithExactLanguageAndBothDatesUntilExplicitConsumption() {
        val hit = BookCorpusSearch.find(listOf(other), "на земле мир", BookReadingMode.RUSSIAN).single()
        val source = BookRoute(current.key, "temple", journey)
        assertNull(source.selectSearchHit(hit, savedCurrentPart = false))
        val selected = requireNotNull(source.selectSearchHit(hit, savedCurrentPart = true))
        val restored = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(selected)))
        assertEquals(selected, restored)
        assertEquals(hit, restored.pendingSearchTarget)
        assertEquals(BookReadingMode.RUSSIAN, restored.pendingSearchTarget!!.hit.mode)
        assertEquals("2026-11-12", restored.journeyContext?.civilDate)
        assertEquals("2026-11-13", restored.activeKey.date)
        assertEquals("temple", restored.parishId)
        assertEquals(current.key, restored.lastSuccessfulKey)
        assertNull(restored.clearPendingSearch().pendingSearchTarget)
        assertNull(restored.selectPart(current.key, true)?.pendingSearchTarget)
        assertNull(restored.withVariant(other.key.copy(mainEvent = "12"), true)?.pendingSearchTarget)
        assertNull(restored.returnToLastSuccessful()?.pendingSearchTarget)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun malformedSerializedTargetsCannotBecomeNavigationOrSilentlyLoseCoordinates() {
        val hit = BookCorpusSearch.find(listOf(other), "на земле мир", BookReadingMode.RUSSIAN).single()
        val route = requireNotNull(BookRoute(current.key, "temple", journey).selectSearchHit(hit, true))
        val source = JsonParser.parseString(BookRouteCodec.encode(route)).asJsonObject
        val mutations = listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.add("hit", com.google.gson.JsonNull.INSTANCE) },
            { it.addProperty("contentHash", "latest") },
            { it.getAsJsonObject("bookKey").addProperty("worship", "UNKNOWN") },
            { it.getAsJsonObject("bookKey").addProperty("churchType", "99") },
            { it.getAsJsonObject("hit").addProperty("mode", "UNKNOWN") },
            { it.getAsJsonObject("hit").add("mode", com.google.gson.JsonNull.INSTANCE) },
            { it.getAsJsonObject("hit").addProperty("mode", "MEANING") },
            { it.getAsJsonObject("hit").addProperty("blockId", "") },
            { it.getAsJsonObject("hit").addProperty("chapterTitle", "x".repeat(1001)) },
            { it.getAsJsonObject("hit").addProperty("matchStart", -1) },
            { it.getAsJsonObject("hit").addProperty("matchStart", "1") },
            { it.getAsJsonObject("hit").addProperty("matchStart", 1.5) },
            { it.getAsJsonObject("hit").addProperty("matchEndExclusive", 0) }
        )
        mutations.forEach { mutate ->
            val record = source.deepCopy()
            mutate(record.getAsJsonObject("pendingSearchTarget"))
            assertNull(record.toString(), BookRouteCodec.decode(record.toString()))
        }
        val old = source.deepCopy().apply { remove("pendingSearchTarget") }
        assertEquals(route.clearPendingSearch(), BookRouteCodec.decode(old.toString()))
        assertNull(route.copy(pendingSearchTarget = hit.copy(bookKey = current.key)).validated())
    }

    private fun position(book: ServiceBook) = BookReadingPosition(book.key, book.contentHash, "temple",
        book.blocks.first().id, book.chapters.first().id, mode = BookReadingMode.RUSSIAN,
        wordsPerMinute = 81, showAltar = true, journeyContext = journey)

    private class FakeBooks(private val latest: ServiceBook?) : ServiceBooks {
        var exact: ServiceBook? = latest
        var fetched: BookLoadResult = BookLoadResult.Failed(BookFailure.NETWORK, "Synthetic unavailable")
        var latestReads = 0
        var loads = 0
        override fun cached(key: ServiceBookKey): ServiceBook? { latestReads++; return latest?.takeIf { it.key == key } }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = exact?.takeIf { it.key == key && it.contentHash == contentHash }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            assertFalse(force)
            loads++
            return fetched
        }
    }

    private fun fixture(kind: WorshipKind, original: String, russian: String? = null, churchType: String = "30"): ServiceBook {
        val key = ServiceBookKey("2026-11-13", kind, churchType)
        return ServiceBook(key = key, title = "Synthetic UI fixture ${kind.title}", sourceUrl = key.sourceUrl(),
            sourceTitle = "Synthetic fixture", fetchedAtMillis = 1, selectedEventTitle = null,
            contentHash = if (kind == WorshipKind.MATINS) "b".repeat(64) else "a".repeat(64),
            chapters = listOf(BookChapter("same-chapter", "Synthetic prayers", "", listOf(
                BookBlock("same-block", BookBlockKind.PRAYER, null, original, russian)))))
    }
}
