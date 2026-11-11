package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

class CalendarSupplementTest {
    private val directory = Files.createTempDirectory("calendar-book-").toFile()
    private val date = "2026-11-11"
    @After fun clean() { directory.deleteRecursively() }

    private fun json() = """{
      "schemaVersion":1,"date":"$date","sourceUrl":"https://azbyka.ru/days/$date",
      "weekTitle":"Седмица 24-я по Пятидесятнице","voice":6,
      "commemorations":[{"title":"Память дня","description":"Опубликованное описание",
        "sourceUrl":"https://azbyka.ru/days/$date"}],
      "translations":[{"original":"Благослове́но Ца́рство Отца́, и Сы́на, и Свята́го Ду́ха, ны́не и при́сно и во ве́ки веко́в.",
        "russian":"Благословенно Царство Отца, и Сына, и Святого Духа, ныне и всегда и во веки веков.",
        "sourceTitle":"Азбука веры: календарь и API","sourceUrl":"https://azbyka.ru/days/$date","apiId":1}]
    }"""

    @Test fun exactPairsAndNullableVoice() {
        val supplement = requireNotNull(PublishedCalendarSupplements.decode(json(), date))
        assertEquals(6, supplement.day.voice)
        assertNotNull(supplement.translations.find("Благословено Царство Отца и Сына и Святаго Духа ныне и присно и во веки веков"))
        assertNull(supplement.translations.find("Благословено Царство Отца и Сына"))
        val paschalVoice = requireNotNull(PublishedCalendarSupplements.decode(json().replace("\"voice\":6", "\"voice\":null"), date))
        assertNull(paschalVoice.day.voice)
    }

    @Test fun actualGeneratedPackagesForFiveDatesMatchTheAndroidContract() {
        val counts = mapOf("2026-01-07" to 2, "2026-04-12" to 15, "2026-11-11" to 8,
            "2026-11-12" to 14, "2026-11-15" to 8)
        counts.forEach { (day, count) ->
            val raw = requireNotNull(javaClass.classLoader?.getResource("calendar/$day.json")).readText()
            val parsed = requireNotNull(PublishedCalendarSupplements.decode(raw, day))
            assertEquals(count, parsed.translations.size)
            assertEquals(day, parsed.day.date)
            assertTrue(parsed.day.commemorations.isNotEmpty())
            val pairs = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("translations")
            pairs.forEach { value ->
                val pair = value.asJsonObject
                val found = requireNotNull(parsed.translations.find(pair.get("original").asString))
                assertEquals(BookText.clean(pair.get("russian").asString), found.russian)
                assertEquals("https://azbyka.ru/days/$day", found.source.url)
            }
            if (day == "2026-01-07") assertEquals(setOf(2229, 2230), pairs.map { it.asJsonObject.get("apiId").asInt }.toSet())
            if (day == "2026-04-12") assertNull(parsed.day.voice)
        }
    }

    @Test fun rejectsOtherDateHtmlForeignSourceConflictingPairAndInvalidTypes() {
        assertNull(PublishedCalendarSupplements.decode(json(), "2026-11-12"))
        listOf(json().replace("azbyka.ru", "example.org"),
            json().replace("Память дня", "<script>Память дня</script>"),
            json().replace("\"voice\":6", "\"voice\":6.5"),
            json().replace("\"voice\":6", "\"voice\":9"),
            json().replace("\"apiId\":1", "\"apiId\":\"1\""),
            json().replace("\"commemorations\":[", "\"commemorations\":null,\"ignored\":[")
        ).forEach { assertNull(PublishedCalendarSupplements.decode(it, date)) }
        val root = JsonParser.parseString(json()).asJsonObject
        val conflict = root.getAsJsonArray("translations")[0].deepCopy().asJsonObject
        conflict.addProperty("russian", "Другой текст")
        root.getAsJsonArray("translations").add(conflict)
        assertNull(PublishedCalendarSupplements.decode(root.toString(), date))
    }

    @Test fun downloadRejectsChangedEndpointAndNeverAcceptsOversize() = runBlocking {
        val source = PublishedCalendarSupplements(ScheduleTransport { url ->
            ScheduleResponse(url.replace("calendar-cache", "untrusted"), json().toByteArray(), "text/plain")
        })
        assertNull(source.load(date))
        assertNull(PublishedCalendarSupplements.decode(" ".repeat(PublishedCalendarSupplements.MAX_BYTES + 1), date))
    }

    @Test fun cacheRestoresContextAndTranslationOfflineAndFailedRefreshPreservesIt() = runBlocking {
        val supplement = requireNotNull(PublishedCalendarSupplements.decode(json(), date))
        var online = true
        val repository = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
            ScheduleResponse(url, (if (url.contains("/worships/")) BookFixtures.html() else "<html></html>").toByteArray(), "text/html")
        }, downloadRussian = false, calendar = CalendarSupplements { if (online) supplement else error("Offline") })
        val first = (repository.load(ServiceBookKey(date)) as BookLoadResult.Ready).book
        assertEquals(supplement.day, first.calendarDay)
        assertTrue(first.blocks.any { it.russian?.startsWith("Благословенно Царство") == true })
        assertTrue(ServiceBookIntegrity.valid(first))
        online = false
        val updated = (repository.load(ServiceBookKey(date), true) as BookLoadResult.Ready).book
        assertEquals(first.calendarDay, updated.calendarDay)
        assertEquals(first.blocks.map { it.original }, updated.blocks.map { it.original })
        assertEquals(first.blocks.map { it.russian }, updated.blocks.map { it.russian })
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("No network") },
            calendar = CalendarSupplements { error("Must not fetch for a cached book") })
        val restored = offline.load(ServiceBookKey(date)) as BookLoadResult.Ready
        assertTrue(restored.fromCache)
        assertEquals(updated, restored.book)
    }

    @Test fun newOptionalFieldPreservesExistingBookHash() {
        val book = BookFixtures.book()
        val serialized = com.google.gson.Gson().toJson(book)
        assertFalse(serialized.contains("calendarDay"))
        val roundTrip = com.google.gson.Gson().fromJson(serialized, ServiceBook::class.java)
        assertEquals(book.contentHash, ServiceBookIntegrity.contentHash(roundTrip))
        assertTrue(ServiceBookIntegrity.valid(roundTrip))
    }

    @Test fun oneHundredCommemorationsSurviveButTheWholeOversizedPackageIsRejected() {
        val root = JsonParser.parseString(json()).asJsonObject
        val one = root.getAsJsonArray("commemorations")[0]
        val hundred = com.google.gson.JsonArray()
        repeat(100) { hundred.add(one.deepCopy()) }
        root.add("commemorations", hundred)
        assertEquals(100, requireNotNull(PublishedCalendarSupplements.decode(root.toString(), date)).day.commemorations.size)
        hundred.add(one.deepCopy())
        assertNull(PublishedCalendarSupplements.decode(root.toString(), date))
    }

    @Test fun cancellationDuringOptionalCalendarKeepsPreviousCompleteBook() = runBlocking {
        val cache = FileBookCache(directory)
        val previous = BookFixtures.book()
        assertTrue(cache.write(previous))
        val started = CompletableDeferred<Unit>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            ScheduleResponse(url, (if (url.contains("/worships/")) BookFixtures.html() else "<html></html>").toByteArray(), "text/html")
        }, downloadRussian = false, calendar = CalendarSupplements { started.complete(Unit); awaitCancellation() })
        val job = launch { repository.load(ServiceBookKey(date), force = true) }
        started.await()
        job.cancelAndJoin()
        assertEquals(previous, FileBookCache(directory).read(ServiceBookKey(date)))
    }
}
