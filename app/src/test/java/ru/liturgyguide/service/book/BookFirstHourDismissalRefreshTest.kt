package ru.liturgyguide.service.book

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files
import java.util.Collections

/** The accepted edition remains mounted until the existing explicit version acceptance succeeds. */
class BookFirstHourDismissalRefreshTest {
    private val directory = Files.createTempDirectory("hour1-dismissal-refresh-").toFile()
    private val date = "2026-11-22"
    private val own = BookTranslationIndex.fromFeoriaCatalog()
    private val gson = Gson()
    @After fun clean() { directory.deleteRecursively() }

    private fun html(kind: WorshipKind): String = requireNotNull(javaClass.classLoader?.getResource(when (kind) {
        WorshipKind.HOUR1 -> "book/first-hour-dismissal/$date-chas1.html"
        WorshipKind.VESPERS -> "book/published-russian/vespers-$date.html"
        WorshipKind.MATINS -> "book/published-russian/matins-$date.html"
        else -> error("Not a component")
    })).readText()

    private fun oldHour(): ServiceBook {
        // Keep unrelated approved translations present, so this fixture isolates
        // the structural change of the one historical dismissal paragraph.
        val fresh = ServiceBookParser().parse(Jsoup.parse(html(WorshipKind.HOUR1)), ServiceBookKey(date, WorshipKind.HOUR1), 1L, own)
        val chapter = fresh.chapters.last()
        val body = chapter.blocks.first()
        val legacy = body.copy(id = "${chapter.id}:${BookText.sha256("RUBRIC|null|false|${body.original}|").take(16)}:1",
            kind = BookBlockKind.RUBRIC, russian = body.original, translationSource = null, meaning = body.original)
        val old = fresh.copy(chapters = fresh.chapters.dropLast(1) + chapter.copy(
            meaning = requireNotNull(BookMeaningCatalog.meaningFor("Отпуст")), blocks = listOf(legacy, chapter.blocks.last())), contentHash = "")
        return old.copy(contentHash = ServiceBookIntegrity.contentHash(old))
    }

    private fun oldBook(kind: WorshipKind): ServiceBook {
        val hour = oldHour()
        if (kind == WorshipKind.HOUR1) return hour
        val parts = listOf(WorshipKind.VESPERS, WorshipKind.MATINS).map { component ->
            ServiceBookParser().parse(Jsoup.parse(html(component)), ServiceBookKey(date, component), 1L, own)
        } + hour
        val key = ServiceBookKey(date, WorshipKind.VIGIL)
        val old = ServiceBook(key = key, title = key.worship.title, sourceUrl = key.sourceUrl(),
            sourceTitle = "Азбука веры: вечерня, утреня и первый час", fetchedAtMillis = 1L,
            selectedEventTitle = parts.first().selectedEventTitle,
            chapters = parts.flatMap { part -> part.chapters.mapIndexed { index, chapter ->
                if (index == 0) chapter.copy(title = "${part.title} · ${chapter.title}") else chapter
            } }, contentHash = "", sources = parts.flatMap { it.sources }.distinct())
        return old.copy(contentHash = ServiceBookIntegrity.contentHash(old))
    }

    @Test fun ordinaryForcedSourceRefreshPublishesCanonicalHourAndVigilWithoutAcceptingOrClaimingATargetedTranslation() = runBlocking {
        for (kind in listOf(WorshipKind.HOUR1, WorshipKind.VIGIL)) {
            val cache = FileBookCache(directory.resolve(kind.name))
            val old = oldBook(kind)
            assertTrue(ServiceBookIntegrity.valid(old))
            assertTrue(cache.write(old))
            val oldJson = gson.toJson(old)
            val oldIds = old.blocks.map { it.id }
            val original = old.chapters.last().blocks.first()
            val prefs = AcceptanceMemoryPreferences()
            val store = BookReadingStore(prefs)
            val parish = "first-hour-source-refresh"
            val cursor = BookReadingPosition(old.key, old.contentHash, parish, original.id,
                old.chapters.last().id, fraction = 1f, followingGapFraction = .23f,
                mode = BookReadingMode.RUSSIAN, wordsPerMinute = 180, showAltar = true)
            assertTrue(store.save(cursor))
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val urls = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)
                .associateBy { old.key.copy(worship = it).sourceUrl() }
            val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
                requests += url
                ScheduleResponse(url, (urls[url]?.let(::html) ?: "<html></html>").toByteArray(), "text/html;charset=utf-8")
            }, translations = own, downloadRussian = false)
            val candidate = repository.load(old.key, force = true) as BookLoadResult.Ready
            assertTrue(candidate.saved)
            assertFalse(candidate.fromCache)
            assertNotEquals(old.contentHash, candidate.book.contentHash)
            assertEquals(if (kind == WorshipKind.HOUR1) listOf(old.key.sourceUrl()) else urls.keys.toList(),
                requests.filter { it in urls })
            val canonical = candidate.book.chapters.last().blocks.first()
            assertEquals(BookBlockKind.PRAYER, canonical.kind)
            assertNull(canonical.speaker)
            assertNotEquals(original.id, canonical.id)
            assertEquals(original.original, canonical.original)
            assertEquals(own.find(original.original)?.russian, canonical.russian)
            assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, canonical.translationSource)
            assertEquals(candidate.book, FileBookCache(directory.resolve(kind.name)).readVersion(old.key, candidate.book.contentHash))
            assertEquals(old, cache.readVersion(old.key, old.contentHash))
            assertEquals(oldJson, gson.toJson(old))
            assertEquals(oldIds, old.blocks.map { it.id })
            assertEquals(cursor, store.positionFor(old.key, parish))
            assertEquals(old, repository.cachedVersion(old.key, cursor.contentHash))
            assertNull(BookRussianUpdateDescriptor.create(old, candidate, true, Any(), "hour1-dismissal-refresh"))
            // Fresh repository reopen sees latest, while the stored accepted pointer still chooses the old edition.
            val offline = ServiceBookRepository(FileBookCache(directory.resolve(kind.name)), ScheduleTransport { error("Offline") })
            assertEquals(candidate.book, (offline.load(old.key) as BookLoadResult.Ready).book)
            assertEquals(old, offline.cachedVersion(old.key, requireNotNull(store.positionFor(old.key, parish)).contentHash))
            assertEquals(cursor, BookReadingStore(prefs).positionFor(old.key, parish))

            for ((mode, last) in listOf(BookReadingMode.ORIGINAL to BookReadingMode.ORIGINAL,
                BookReadingMode.RUSSIAN to BookReadingMode.RUSSIAN,
                BookReadingMode.MEANING to BookReadingMode.ORIGINAL,
                BookReadingMode.MEANING to BookReadingMode.RUSSIAN)) {
                val p = cursor.copy(mode = mode, lastTextMode = last)
                val acceptancePrefs = AcceptanceMemoryPreferences()
                val acceptance = BookReadingStore(acceptancePrefs)
                assertTrue(acceptance.save(p))
                assertTrue(acceptance.acceptVersion(old, candidate.book, parish, p))
                val accepted = requireNotNull(BookReadingStore(acceptancePrefs).positionFor(old.key, parish))
                assertEquals(candidate.book.contentHash, accepted.contentHash)
                assertEquals(canonical.id, accepted.blockId)
                assertEquals(old.chapters.last().id, accepted.chapterId)
                assertEquals(mode, accepted.mode)
                assertEquals(last, accepted.lastTextMode)
                assertEquals(p.wordsPerMinute, accepted.wordsPerMinute)
                assertEquals(p.showAltar, accepted.showAltar)
                assertEquals(p.parishId, accepted.parishId)
                assertEquals(p.journeyContext, accepted.journeyContext)
                assertEquals(if (last == BookReadingMode.ORIGINAL) p.fraction else 0f, accepted.fraction)
                assertEquals(if (last == BookReadingMode.ORIGINAL) p.followingGapFraction else 0f, accepted.followingGapFraction)
                assertEquals(old, cache.readVersion(old.key, old.contentHash))
                assertEquals(candidate.book, cache.readVersion(old.key, accepted.contentHash))
                assertFalse("A stale accepted owner cannot overwrite the new edition",
                    acceptance.acceptVersion(old, candidate.book, parish, p))
            }
        }
    }
}
