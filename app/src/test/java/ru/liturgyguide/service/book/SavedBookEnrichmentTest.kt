package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SavedBookEnrichmentTest {
    private val directory = Files.createTempDirectory("saved-enrichment-").toFile()
    private val disk = FileBookCache(directory)
    private val original = BookFixtures.book()
    private val target = original.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length > 120 }
    private val full = BookTranslationIndex.TIMROT_FULL_SOURCE
    @After fun clean() { directory.deleteRecursively() }

    @Test fun cachedPairsImproveSavedBookOfflineWithoutChangingOriginalOrOldEdition() = runBlocking {
        assertTrue(disk.write(original))
        val oldJson = directory.listFiles()!!.single { it.extension == "json" }.readBytes()
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Точный опубликованный перевод"), version = 5).encode()))
        val repository = ServiceBookRepository(disk, ScheduleTransport { error("Offline enrichment must not fetch") })
        val result = requireNotNull(repository.enrichSaved(original, allowNetwork = false))
        assertTrue(result.saved)
        assertEquals("Точный опубликованный перевод", result.book.blocks.first { it.id == target.id }.russian)
        assertOnlyMissingRussianChanged(original, result.book)
        assertEquals(original, disk.readVersion(original.key, original.contentHash))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${original.contentHash}.json") }
        assertArrayEquals(oldJson, oldFile.readBytes())
        assertTrue(ServiceBookIntegrity.valid(Gson().fromJson(oldFile.readText(), ServiceBook::class.java)))
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("No network") })
        assertEquals(result.book, (offline.load(original.key) as BookLoadResult.Ready).book)
        assertNull(offline.enrichSaved(result.book, allowNetwork = false))
    }

    @Test fun existingTranslationsAndMeaningAndNonPrayerBlocksAreNeverRewritten() {
        val saved = replace(original, target.id) { it.copy(russian = "Старый перевод", translationSource = BookTranslationIndex.EDITORIAL_SOURCE,
            meaning = "Прежнее объяснение") }
        val nonPrayer = saved.blocks.first { it.kind != BookBlockKind.PRAYER }
        val index = pair(target.original, "Другой перевод").plus(pair(nonPrayer.original, "Не молитва"))
        assertNull(SavedBookEnrichment.apply(saved, index))
        assertNull(SavedBookEnrichment.apply(original, pair(target.original, "Редакция", BookTranslationIndex.EDITORIAL_SOURCE)))
        assertNull(SavedBookEnrichment.apply(original, pair(target.original, "Неизвестный", full.copy(url = "https://example.org/translation"))))
        assertNull(SavedBookEnrichment.apply(original, pair(target.original.substringBeforeLast(' '), "Только начало")))
    }

    @Test fun oldCorpusTriggersOnlyParallelPagesAndExactNewPairIsSaved() = runBlocking {
        assertTrue(disk.write(original))
        assertTrue(disk.writeTranslationCorpus(corpus(pair("Чужая молитва", "Другой перевод"), version = 5).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            response(url, target.original, "Новый точный перевод")
        })
        val result = requireNotNull(repository.enrichSaved(original))
        assertEquals((BookTranslationIndex.requiredSources(original.key.worship) + BookSharedRussianSources.forBook(original)).map { it.url }, requests)
        assertOnlyMissingRussianChanged(original, result.book)
        assertEquals("Новый точный перевод", result.book.blocks.first { it.id == target.id }.russian)
        assertTrue(BookTranslationCorpus.decode(disk.readTranslationCorpus()).completeFor(original.key.worship))
        val files = directory.listFiles()!!.map { it.name }.sorted()
        assertNull(repository.enrichSaved(result.book))
        assertEquals(6, requests.size)
        assertEquals(files, directory.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun offlineFailureDoesNotLoopOnReentryAndManualForceStillWorks() = runBlocking {
        assertTrue(disk.write(original))
        val clock = MutableClock()
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url -> requests += url; error("Offline") }, clock = clock)
        repeat(3) { assertNull(repository.enrichSaved(original)) }
        assertEquals(6, requests.size)
        assertEquals(original, disk.read(original.key))
        assertTrue(repository.load(original.key, force = true) is BookLoadResult.Failed)
        assertTrue(requests.last().contains("/worships/"))
        clock.now += 5 * 60_000
        assertNull(repository.enrichSaved(original))
        assertEquals(13, requests.size)
        assertNull(disk.readTranslationCorpus())
    }

    @Test fun partialSourceFailureKeepsFullTranslationAndPreviouslySavedPairs() = runBlocking {
        assertTrue(disk.write(original))
        // This pair is not in the book, so the background must continue beyond the cached pass.
        val preserved = "Особая полная молитва"
        assertTrue(disk.writeTranslationCorpus(corpus(pair(preserved, "Полный русский перевод", full), version = 5).encode()))
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            when (url) {
                BookTranslationIndex.TIMROT_SOURCE.url -> response(url, preserved, "Краткий перевод")
                full.url -> error("Full source unavailable")
                else -> response(url, target.original, "Перевод из доступного источника")
            }
        })
        val result = requireNotNull(repository.enrichSaved(original))
        val saved = BookTranslationCorpus.decode(disk.readTranslationCorpus())
        assertEquals("Полный русский перевод", saved.index.find(preserved)?.russian)
        assertEquals(full.url, saved.index.find(preserved)?.source?.url)
        assertFalse(saved.completeFor(original.key.worship))
        assertEquals("Перевод из доступного источника", result.book.blocks.first { it.id == target.id }.russian)
        assertEquals(original.enrichment, result.book.enrichment)
    }

    @Test fun currentLatestWithChangedOriginalNeverRollsBackToPinned() = runBlocking {
        assertTrue(disk.write(original))
        val newer = replace(original, target.id) { it.copy(original = it.original + " Иное окончание") }
        assertTrue(disk.write(newer))
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Перевод старого оригинала")).encode()))
        val repository = ServiceBookRepository(disk, ScheduleTransport { error("No network") })
        assertNull(repository.enrichSaved(original, allowNetwork = false))
        assertEquals(newer, disk.read(original.key))
        assertEquals(original, disk.readVersion(original.key, original.contentHash))
    }

    @Test fun competingLatestPublicationRebasesOnlyOntoNewerOriginal() = runBlocking {
        assertTrue(disk.write(original))
        val newer = replace(original, target.id) { it.copy(original = it.original + " Иное окончание") }
        val other = original.blocks.first { it.kind == BookBlockKind.PRAYER && it.id != target.id && it.original.length > 100 }
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Старая молитва").plus(pair(other.original, "Другая молитва"))).encode()))
        var raced = false
        val cache = object : BookCache by disk {
            override fun writeIfLatest(expectedHash: String?, book: ServiceBook): Boolean {
                if (!raced) { raced = true; assertTrue(FileBookCache(directory).write(newer)) }
                return disk.writeIfLatest(expectedHash, book)
            }
        }
        val result = requireNotNull(ServiceBookRepository(cache).enrichSaved(original, allowNetwork = false))
        assertEquals(newer.blocks.first { it.id == target.id }, result.book.blocks.first { it.id == target.id })
        assertEquals("Другая молитва", result.book.blocks.first { it.id == other.id }.russian)
        assertEquals(result.book, disk.read(original.key))
        assertOnlyMissingRussianChanged(newer, result.book)
    }

    @Test fun saveFailureAndMissingReadbackNeverProduceACandidate() = runBlocking {
        assertTrue(disk.write(original))
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Новый перевод")).encode()))
        val failed = object : BookCache by disk { override fun writeIfLatest(expectedHash: String?, book: ServiceBook) = false }
        assertNull(ServiceBookRepository(failed).enrichSaved(original, allowNetwork = false))
        assertEquals(original, disk.read(original.key))
        val phantom = object : BookCache by disk { override fun writeIfLatest(expectedHash: String?, book: ServiceBook) = true }
        assertNull(ServiceBookRepository(phantom).enrichSaved(original, allowNetwork = false))
        assertEquals(original, disk.read(original.key))
    }

    @Test fun readingAndHashingSavedChaptersNeverRunsOnTheCallingUiThread() = runBlocking {
        assertTrue(disk.write(original))
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Новый перевод")).encode()))
        val caller = Thread.currentThread()
        val offThreadChapters = object : AbstractList<BookChapter>() {
            override val size: Int get() {
                check(Thread.currentThread() !== caller) { "Traversing the saved book on the UI thread" }
                return original.chapters.size
            }
            override fun get(index: Int): BookChapter {
                check(Thread.currentThread() !== caller) { "Traversing the saved book on the UI thread" }
                return original.chapters[index]
            }
        }
        val tracked = original.copy(chapters = offThreadChapters)
        val cache = object : BookCache by disk {
            override fun read(key: ServiceBookKey): ServiceBook = tracked
            override fun readVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
                if (contentHash == original.contentHash) tracked else disk.readVersion(key, contentHash)
        }
        val result = requireNotNull(ServiceBookRepository(cache).enrichSaved(original, allowNetwork = false))
        assertEquals("Новый перевод", result.book.blocks.first { it.id == target.id }.russian)
    }

    @Test fun invalidLatestIsNotTreatedAsMissingAndCannotBeReplacedByPinned() = runBlocking {
        assertTrue(disk.write(original))
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Новый перевод")).encode()))
        var writes = 0
        val cache = object : BookCache by disk {
            override fun read(key: ServiceBookKey): ServiceBook = original.copy(title = "Поврежденная редакция без нового хеша")
            override fun writeIfLatest(expectedHash: String?, book: ServiceBook): Boolean { writes++; return false }
        }
        assertNull(ServiceBookRepository(cache).enrichSaved(original, allowNetwork = false))
        assertEquals(0, writes)
        assertEquals(original, disk.read(original.key))
    }

    @Test fun cancellationDuringNetworkDoesNotPublishOrDeleteAcceptedBook() = runBlocking {
        assertTrue(disk.write(original))
        val entered = CompletableDeferred<Unit>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { entered.complete(Unit); awaitCancellation() })
        val job = launch { repository.enrichSaved(original); fail("Cancelled call must not return") }
        entered.await()
        job.cancelAndJoin()
        assertEquals(original, disk.read(original.key))
        assertEquals(1, directory.listFiles()!!.count { it.extension == "json" })
    }

    @Test fun totalBackgroundDeadlineRetainsPairsFromEarlierSuccessfulSource() = runBlocking {
        val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html("vespers-2026-11-12")),
            ServiceBookKey("2026-11-12", WorshipKind.VESPERS), original.fetchedAtMillis)
        val prayer = book.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length > 120 }
        assertTrue(disk.write(book))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            if (requests.size == 1) response(url, prayer.original, "Перевод до недоступных источников")
            else awaitCancellation()
        })
        // Four sources cannot each consume the whole transport deadline. The outer budget
        // interrupts the last one while the first source's verified pair remains usable.
        val result = withTimeout(28_000) { requireNotNull(repository.enrichSaved(book)) }
        assertEquals(BookTranslationIndex.requiredSources(WorshipKind.VESPERS).map { it.url }, requests)
        assertEquals("Перевод до недоступных источников", result.book.blocks.first { it.id == prayer.id }.russian)
        val corpus = BookTranslationCorpus.decode(disk.readTranslationCorpus())
        assertEquals("Перевод до недоступных источников", corpus.index.find(prayer.original)?.russian)
        assertFalse(corpus.completeFor(WorshipKind.VESPERS))
        assertEquals(book, disk.readVersion(book.key, book.contentHash))
    }

    @Test fun unsavedAndDifferentDateVariantOrWorshipNeverUseAnotherSavedBook() = runBlocking {
        assertTrue(disk.write(original))
        assertTrue(disk.writeTranslationCorpus(corpus(pair(target.original, "Точный перевод")).encode()))
        val repository = ServiceBookRepository(disk, ScheduleTransport { error("No network") })
        for (key in listOf(original.key.copy(date = "2026-11-12"), original.key.copy(worship = WorshipKind.VESPERS),
            original.key.copy(mainEvent = "999"), original.key.copy(churchType = "10"))) {
            val unsaved = original.copy(key = key, sourceUrl = key.sourceUrl()).rehash()
            assertNull(repository.enrichSaved(unsaved))
        }
        assertEquals(original, disk.read(original.key))
    }

    private fun assertOnlyMissingRussianChanged(before: ServiceBook, after: ServiceBook) {
        assertTrue(ServiceBookIntegrity.valid(after))
        assertEquals(before.chapters.map { it.id }, after.chapters.map { it.id })
        assertEquals(before.blocks.size, after.blocks.size)
        before.blocks.zip(after.blocks).forEach { (old, new) ->
            if (old.russian != null || old.kind != BookBlockKind.PRAYER) assertEquals(old, new)
            else assertEquals(old, new.copy(russian = null, translationSource = old.translationSource))
        }
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
    }

    private fun pair(original: String, russian: String, source: BookSource = full): BookTranslationIndex =
        BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", original); addProperty("russian", russian)
            addProperty("source", source.title); addProperty("url", source.url)
        }) }.toString())

    private fun corpus(index: BookTranslationIndex, version: Int = BookTranslationCorpus.VERSION) =
        BookTranslationCorpus(index, BookTranslationIndex.requiredSources(original.key.worship).map { it.url }.toSet(), version)

    private fun response(url: String, original: String, russian: String) = ScheduleResponse(url,
        "<html><table><tr><td>$original</td><td>$russian</td></tr></table></html>".toByteArray(), "text/html;charset=utf-8")

    private fun replace(book: ServiceBook, id: String, transform: (BookBlock) -> BookBlock): ServiceBook =
        book.copy(chapters = book.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map { if (it.id == id) transform(it) else it }) }).rehash()

    private fun ServiceBook.rehash() = copy(contentHash = ServiceBookIntegrity.contentHash(this))
    private class MutableClock(var now: Long = 1_794_348_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
}
