package ru.liturgyguide.service.book

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

class PatronalRepositoryTest {
    private val directory = Files.createTempDirectory("patronal-repository-").toFile()
    private val cache = FileBookCache(directory)
    private val calls = CopyOnWriteArrayList<String>()
    private val sourceCalls get() = calls.filter { it.startsWith("https://azbyka.ru/worships/?") }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun realDedicationsHaveSeparateDiskBooksAndReopenWithoutProofProviderOrNetwork() = runBlocking {
        val downloaded = PatronalFixtures.labels.map { label ->
            val key = PatronalFixtures.key(label)
            val ready = repository(label).load(key) as BookLoadResult.Ready
            assertTrue(ready.saved); assertFalse(ready.fromCache)
            assertTrue(ServiceBookIntegrity.valid(ready.book))
            assertEquals(PatronalFixtures.proof(label), ready.book.patronalSelection)
            assertEquals(ready.book, cache.read(key))
            ready.book
        }
        assertEquals(6, downloaded.map { it.key.canonical() }.distinct().size)
        assertNull(cache.read(PatronalFixtures.key().copy(patronalId = null)))
        calls.clear()
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            calls += url; error("An exact cached patronal book must not need network")
        })
        for (book in downloaded) {
            val restored = offline.load(book.key) as BookLoadResult.Ready
            assertTrue(restored.fromCache)
            assertEquals(book, restored.book)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun anUnknownOrMismatchedProofCannotStartTheWorshipRequest() = runBlocking {
        val key = PatronalFixtures.key()
        val proof = PatronalFixtures.proof()
        for (provider in listOf(PatronalProofs { null }, PatronalProofs { proof.copy(id = "129540") },
            PatronalProofs { proof.copy(churchType = "30") }, PatronalProofs { error("Synthetic proof storage read failure") })) {
            calls.clear()
            val result = ServiceBookRepository(cache, ScheduleTransport { url -> calls += url; error("Must stop before HTTP") },
                downloadRussian = false, patronalProofs = provider).load(key) as BookLoadResult.Failed
            assertEquals(BookFailure.INVALID_SOURCE, result.reason)
            assertTrue(result.message.contains("посвящение"))
            assertNull(result.cachedBook); assertNull(cache.read(key)); assertTrue(calls.isEmpty())
        }
    }

    @Test fun aFailedHttp200RefreshKeepsTheAcceptedExactBookAndReadingCursor() = runBlocking {
        val key = PatronalFixtures.key()
        val first = (repository().load(key) as BookLoadResult.Ready).book
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        val prayer = PatronalFixtures.templePrayer(first, "тропарь храма")
        val chapter = first.chapters.single { prayer in it.blocks }
        val position = BookReadingPosition(key, first.contentHash, "patronal-test", prayer.id, chapter.id,
            fraction = .41f, mode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        val before = preferences.all.toMap()
        calls.clear()
        // No external proof provider after restart: the exact cached snapshot is enough.
        val failed = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            calls += url; response(url, PatronalFixtures.text("invalid-patronal.html"))
        }, downloadRussian = false).load(key, force = true) as BookLoadResult.Failed
        assertEquals(BookFailure.INVALID_SOURCE, failed.reason)
        assertTrue(failed.message.contains("посвящение"))
        assertEquals(listOf(key.sourceUrl()), calls)
        assertEquals(first, failed.cachedBook)
        assertEquals(first, cache.read(key)); assertEquals(first, cache.readVersion(key, first.contentHash))
        assertEquals(position, store.positionFor(key, "patronal-test"))
        assertEquals(before, preferences.all)
    }

    @Test fun aRedirectCannotPublishAnotherResponseUnderTheRequestedDedicationUrl() = runBlocking {
        val key = PatronalFixtures.key()
        val proof = PatronalFixtures.proof()
        val result = ServiceBookRepository(cache, ScheduleTransport { url ->
            calls += url
            response(key.copy(patronalId = null).sourceUrl(), PatronalFixtures.text("nicholas-may-patronal.html"))
        }, downloadRussian = false, patronalProofs = PatronalProofs { proof }).load(key) as BookLoadResult.Failed
        assertEquals(BookFailure.INVALID_SOURCE, result.reason)
        assertTrue(result.message.contains("посвящение"))
        assertEquals(listOf(key.sourceUrl()), calls)
        assertNull(result.cachedBook); assertNull(cache.read(key))
    }

    @Test fun theRealRankProtocolPassesItsProofThroughBothParsingPassesAndOfflineReentry() = runBlocking {
        val proof = PatronalFixtures.proof()
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34", topLevel = "3", patronalId = proof.id)
        val request = MatinsRankSource.resolve(PatronalFixtures.document("matins-default-2026-11-13"), key, proof)
        val ready = ServiceBookRepository(cache, ScheduleTransport { url ->
            calls += url
            val html = when (url) {
                key.copy(topLevel = null).sourceUrl() -> PatronalFixtures.text("matins-default-2026-11-13.html")
                request.url -> PatronalFixtures.text("matins-rank3-2026-11-13.html")
                else -> "<html></html>" // Scripture is deliberately unavailable in this protocol test.
            }
            response(url, html)
        }, downloadRussian = false, patronalProofs = PatronalProofs { proof }).load(key) as BookLoadResult.Ready
        assertTrue(ready.saved)
        assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), request.url), sourceCalls)
        assertEquals(proof, ready.book.patronalSelection)
        assertEquals(BookRankSelection("0", "", "3"), ready.book.rankSelection)
        assertTrue(MatinsRankSource.hasPolyeleosOrder(ready.book))
        assertTrue(ServiceBookIntegrity.valid(ready.book))
        calls.clear()
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url -> calls += url; error("Offline") })
        assertEquals(ready.book, (offline.load(key) as BookLoadResult.Ready).book)
        assertTrue(calls.isEmpty())
    }

    @Test fun aVigilAssemblesThreeCachedPartsWithOneSnapshotWithoutAProviderOrNetwork() = runBlocking {
        val proof = PatronalFixtures.proof()
        val key = ServiceBookKey("2026-11-12", WorshipKind.VIGIL, "34", patronalId = proof.id)
        val parts = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1).map { kind ->
            val partKey = key.copy(worship = kind)
            ServiceBookParser().parse(syntheticPartPage(partKey, proof), partKey, 12345, patronalSelection = proof)
                .also { assertTrue(ServiceBookIntegrity.valid(it)); assertTrue(cache.write(it)) }
        }
        val ready = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url -> calls += url; error("Offline") },
            downloadRussian = false).load(key) as BookLoadResult.Ready
        assertTrue(ready.saved)
        assertEquals(proof, ready.book.patronalSelection)
        assertEquals(parts.flatMap { it.blocks }, ready.book.blocks)
        assertTrue(ServiceBookIntegrity.valid(ready.book))
        assertEquals(ready.book, cache.readVersion(key, ready.book.contentHash))
        assertTrue(calls.isEmpty())
    }

    @Test fun aVigilDoesNotMixCachedAndFreshPartsWithDifferentProofSnapshots() = runBlocking {
        val priorProof = PatronalFixtures.proof()
        val nextProof = priorProof.copy(title = priorProof.title + " - synthetic revised evidence")
        val key = ServiceBookKey("2026-11-12", WorshipKind.VIGIL, "34", patronalId = priorProof.id)
        val oldParts = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1).map { kind ->
            val partKey = key.copy(worship = kind)
            ServiceBookParser().parse(syntheticPartPage(partKey, priorProof), partKey, 12345, patronalSelection = priorProof)
                .also { assertTrue(cache.write(it)) }
        }
        val ready = ServiceBookRepository(cache, ScheduleTransport { url ->
            calls += url
            val kind = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)
                .singleOrNull { url == key.copy(worship = it).sourceUrl() }
            response(url, if (kind == null) "<html></html>" else syntheticPartPage(key.copy(worship = kind), nextProof).outerHtml())
        }, downloadRussian = false, patronalProofs = PatronalProofs { nextProof }).load(key) as BookLoadResult.Ready
        assertEquals(3, sourceCalls.size)
        assertTrue(ready.saved)
        assertEquals(nextProof, ready.book.patronalSelection)
        for (old in oldParts) {
            assertEquals(old, cache.readVersion(old.key, old.contentHash))
            assertEquals(nextProof, cache.read(old.key)?.patronalSelection)
        }
        assertTrue(ServiceBookIntegrity.valid(ready.book))
    }

    @Test fun dedicationPairsTranslateOnlyExactOriginalsAndNeverReplaceTheServiceDayContext() = runBlocking {
        val proof = PatronalFixtures.proof()
        val plain = PatronalFixtures.parse()
        val target = PatronalFixtures.templePrayer(plain, "тропарь храма")
        val russian = "Синтетическая точная русская пара храмового песнопения для проверки источника"
        val serviceDay = BookCalendarDay(plain.key.date, "Проверочный день службы", 6, emptyList(),
            "https://azbyka.ru/days/${plain.key.date}")
        val cases = listOf(
            "exact" to calendarPage(proof.calendarDate, target.original, russian),
            "wrong-date" to calendarPage("2026-05-23", target.original, russian),
            "wrong-original" to calendarPage(proof.calendarDate, "Иная синтетическая молитва для отрицательной проверки", russian),
            "meaning-is-not-translation" to calendarPage(proof.calendarDate, target.original, russian, label = "Толкование")
        )
        for ((name, patronalPage) in cases) {
            val requestedCalendarDays = CopyOnWriteArrayList<String>()
            val ready = translatedRepository(File(directory, name), patronalPage,
                CalendarSupplements { date -> requestedCalendarDays += date; CalendarSupplement(serviceDay, BookTranslationIndex.empty()) })
                .load(plain.key) as BookLoadResult.Ready
            assertTrue(ready.saved)
            assertEquals(plain.blocks.map { it.original }, ready.book.blocks.map { it.original })
            val translated = ready.book.blocks.single { it.id == target.id }
            if (name == "exact") {
                assertEquals(russian, translated.russian)
                assertEquals("https://azbyka.ru/days/${proof.calendarDate}", translated.translationSource?.url)
            } else assertNull(name, translated.russian)
            assertEquals(serviceDay, ready.book.calendarDay)
            assertEquals(listOf(plain.key.date), requestedCalendarDays)
            assertEquals(proof, ready.book.patronalSelection)
            assertTrue(ServiceBookIntegrity.valid(ready.book))
        }
    }

    @Test fun theServiceDayPublishedPairKeepsPriorityOverADifferentDedicationDatePair() = runBlocking {
        val proof = PatronalFixtures.proof()
        val plain = PatronalFixtures.parse()
        val target = PatronalFixtures.templePrayer(plain, "тропарь храма")
        val currentRussian = "Синтетический приоритетный русский текст опубликованной даты службы"
        val patronalRussian = "Другой синтетический русский текст даты посвящения"
        val feedPairs = requireNotNull(BookTranslationIndex.fromCalendarPage(Jsoup.parse(
            calendarPage(plain.key.date, target.original, currentRussian)), plain.key.date))
        val day = BookCalendarDay(plain.key.date, "День службы", null, emptyList(), "https://azbyka.ru/days/${plain.key.date}")
        val ready = translatedRepository(directory, calendarPage(proof.calendarDate, target.original, patronalRussian),
            CalendarSupplements { CalendarSupplement(day, feedPairs) }).load(plain.key) as BookLoadResult.Ready
        val translated = ready.book.blocks.single { it.id == target.id }
        assertEquals(currentRussian, translated.russian)
        assertEquals(day.sourceUrl, translated.translationSource?.url)
        assertEquals(day, ready.book.calendarDay)
        assertTrue(ready.saved)
    }

    private fun repository(label: String = "nicholas-may") = ServiceBookRepository(cache, ScheduleTransport { url ->
        calls += url
        response(url, if (url == PatronalFixtures.key(label).sourceUrl()) PatronalFixtures.text("$label-patronal.html") else "<html></html>")
    }, downloadRussian = false, patronalProofs = PatronalProofs { PatronalFixtures.proof(label) })

    private fun translatedRepository(destination: File, patronalPage: String, calendar: CalendarSupplements): ServiceBookRepository {
        val proof = PatronalFixtures.proof()
        return ServiceBookRepository(FileBookCache(destination), ScheduleTransport { url ->
            calls += url
            response(url, when (url) {
                PatronalFixtures.key().sourceUrl() -> PatronalFixtures.text("nicholas-may-patronal.html")
                "https://azbyka.ru/days/${proof.calendarDate}" -> patronalPage
                else -> "<html></html>"
            })
        }, calendar = calendar, patronalProofs = PatronalProofs { proof })
    }

    /** Deliberately synthetic parameter mutation of a real full order, only for composition tests. */
    private fun syntheticPartPage(key: ServiceBookKey, proof: PatronalEventProof): org.jsoup.nodes.Document {
        val name = when (key.worship) { WorshipKind.VESPERS -> "vespers"; WorshipKind.MATINS -> "matins"; else -> "hour1" }
        return Jsoup.parse(BookFixtures.html("$name-${key.date}")).also {
            it.select("#bg_worships_church").remove()
            it.body().append("<select id=bg_worships_patronal><option selected value=${proof.id}></option></select>")
        }
    }

    private fun calendarPage(date: String, original: String, russian: String, label: String = "Перевод"): String {
        val d = LocalDate.parse(date)
        val month = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
            "октября", "ноября", "декабря")[d.monthValue - 1]
        return """<html><head><link rel="canonical" href="https://azbyka.ru/days/$date"></head><body>
            <h1>${d.dayOfMonth} $month ${d.year}</h1><div class="taks_content"><div>
            <p>${Entities.escape(original)}</p><div class="taks-explanation">$label</div>
            <div class="taks-explanation-info">${Entities.escape(russian)}</div></div></div></body></html>"""
    }

    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
}
