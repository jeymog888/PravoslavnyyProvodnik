package ru.liturgyguide.service.book

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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CalendarFixtures {
    fun realStructure(): String = requireNotNull(javaClass.getResourceAsStream("/book/calendar-hymns-2026-11-11.html"))
        .bufferedReader().use { it.readText() }

    fun page(date: String, original: String = "Молитва календарного дня", russian: String = "Тестовый русский перевод дня"): String {
        val heading = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
        return """<html><head><link rel="canonical" href="https://azbyka.ru/days/$date"></head><body><h1>$heading</h1>
            <div class="inner taks_content"><h3>Тропарь</h3><div><p>${Entities.escape(original)}</p>
            <p class="taks-explanation"><strong><span>Перевод:</span></strong></p>
            <div class="taks-explanation-info"><p>${Entities.escape(russian)}</p></div></div></div></body></html>"""
    }
}

class BookCalendarTranslationTest {
    private val date = "2026-11-11"
    private val key = ServiceBookKey(date)
    private val directory = Files.createTempDirectory("calendar-book-").toFile()
    private val cache = FileBookCache(directory)
    @After fun clean() { directory.deleteRecursively() }
    private fun parse(html: String, requested: String = date) = BookTranslationIndex.fromCalendarPage(Jsoup.parse(html), requested)
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
    private fun common() = "<table><tr><td>Общая постоянная молитва</td><td>Тестовый постоянный перевод</td></tr></table>"

    @Test fun realCalendarStructureAddsSixWholeDatedHymnsWithCalendarProvenance() {
        val index = requireNotNull(parse(CalendarFixtures.realStructure()))
        assertEquals(8, index.size)
        val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html()), key, 12345, index)
        val recovered = book.blocks.filter { it.kind == BookBlockKind.PRAYER && it.russian?.startsWith("Тестовый русский перевод календарной") == true }
        assertEquals(6, recovered.size)
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("агница твоя") })
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("девства водами") })
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("в тебе отче") })
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("во плоти яко ангел") })
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("спаси господи люди") })
        assertTrue(recovered.any { BookText.normalized(it.original).startsWith("вознесыйся на крест") })
        recovered.forEach {
            assertEquals("https://azbyka.ru/days/$date", it.translationSource?.url)
            assertEquals("Азбука веры · календарь на $date", it.translationSource?.title)
            assertNull(index.find("не ${it.original}"))
            assertNull(index.find(it.original.substringBefore('/')))
        }
        assertTrue(ServiceBookIntegrity.valid(book))
    }

    @Test fun canonicalAndVisibleDateMustBothMatchBeforeAnyPrayerIsAccepted() {
        val valid = CalendarFixtures.realStructure()
        assertNull(parse(valid, "2026-11-12"))
        assertNull(parse(valid.replace("11 ноября", "12 ноября")))
        assertNull(parse(valid.replace("https://azbyka.ru/days/2026-11-11", "https://azbyka.ru/days/2025-11-11")))
        assertNull(parse(valid.replace("https://azbyka.ru/days/2026-11-11", "https://example.org/days/2026-11-11")))
        assertNull(parse(valid.replace("rel=\"canonical\"", "rel=\"alternate\"")))
        assertNull(parse(valid, "2026-02-30"))
        // A sidebar link to the requested day is not the identity of this calendar page.
        assertNull(parse(valid.replace("rel=\"canonical\"", "rel=\"alternate\"") + "<a href='https://azbyka.ru/days/$date'>$date</a>"))
    }

    @Test fun emptyExplanationsAndAmbiguousPairsAreNotTranslations() {
        val html = CalendarFixtures.page(date)
        assertNull(parse(html.replace("Перевод:", "Пояснение:")))
        assertNull(parse(html.replace("Тестовый русский перевод дня", "")))
        assertNull(parse(html.replace("<div class=\"taks-explanation-info\">", "<div class=\"taks-explanation-info\">Еще один перевод</div><div class=\"taks-explanation-info\">")))
        assertNull(parse("<link rel='canonical' href='https://azbyka.ru/days/$date'><h1>11 ноября 2026</h1>"))
    }

    @Test fun downloadedDatedTranslationsSurviveOfflineAndFailedRefreshWithoutEnteringStaticCorpus() = runBlocking {
        var available = true
        var dayRequests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            val html = when {
                "worship=liturgy" in url -> BookFixtures.html()
                "/biblia/days/" in url -> "<html></html>"
                url == "https://azbyka.ru/days/$date" -> {
                    dayRequests++
                    if (!available) error("Calendar unavailable")
                    CalendarFixtures.realStructure()
                }
                else -> common()
            }
            response(url, html)
        })
        val first = repository.load(key) as BookLoadResult.Ready
        val hymns = first.book.blocks.filter { it.translationSource?.url == "https://azbyka.ru/days/$date" }
        assertEquals(6, hymns.size)
        assertEquals(BookSupplementStatus.AVAILABLE, first.book.enrichment.russian)
        assertEquals(1, dayRequests)
        val corpus = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        hymns.forEach { assertNull("Dated hymns must not accumulate in the static corpus", corpus.index.find(it.original)) }
        val offline = ServiceBookRepository(cache, ScheduleTransport { error("No network allowed") })
        assertEquals(first.book, (offline.load(key) as BookLoadResult.Ready).book)
        available = false
        val refreshed = repository.load(key, force = true) as BookLoadResult.Ready
        assertEquals(2, dayRequests)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, refreshed.book.enrichment.russian)
        assertTrue(refreshed.warning.orEmpty().contains("русский перевод молитв"))
        for (hymn in hymns) {
            val retained = refreshed.book.blocks.single { it.id == hymn.id }
            assertEquals(hymn.original, retained.original)
            assertEquals(hymn.russian, retained.russian)
            assertEquals(hymn.translationSource, retained.translationSource)
        }
        assertTrue(refreshed.saved)
    }

    @Test fun wrongDayDoesNotBlockTheOriginalBookOrAssignItsHymns() = runBlocking {
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            response(url, when {
                "worship=liturgy" in url -> BookFixtures.html()
                "/biblia/days/" in url -> "<html></html>"
                url == "https://azbyka.ru/days/$date" -> CalendarFixtures.realStructure()
                    .replace("2026-11-11", "2026-11-12").replace("11 ноября", "12 ноября")
                else -> common()
            })
        })
        val result = repository.load(key) as BookLoadResult.Ready
        assertTrue(result.saved)
        assertTrue(ServiceBookIntegrity.valid(result.book))
        assertFalse(result.book.blocks.any { it.translationSource?.url?.contains("/days/") == true })
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        assertNotNull(result.warning)
    }

    @Test fun wholeVigilDownloadsItsDatedCalendarOnlyOnceEvenOnFailure() = runBlocking {
        var dayRequests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            response(url, when {
                "worship=vespers" in url -> BookFixtures.html("vespers-2026-11-12")
                "worship=matins" in url -> BookFixtures.html("matins-2026-11-12")
                "worship=chas1" in url -> BookFixtures.html("hour1-2026-11-12")
                "/biblia/days/" in url -> "<html></html>"
                url == "https://azbyka.ru/days/2026-11-12" -> { dayRequests++; error("Calendar unavailable") }
                else -> common()
            })
        })
        val result = repository.load(ServiceBookKey("2026-11-12", WorshipKind.VIGIL)) as BookLoadResult.Ready
        assertEquals(1, dayRequests)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        assertTrue(ServiceBookIntegrity.valid(result.book))
    }
}
