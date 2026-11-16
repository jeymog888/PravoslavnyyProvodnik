package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** Two complete unmodified public responses captured on 16 Nov 2026, not a generated schedule. */
class KalugaCurrentWeekCalendarTest {
    private val source = "http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/"
    private val child = "http://kaluga-voznesenie.ru/bogosluzheniya-v-nashem-hrame/"
    private val today = LocalDate.of(2026, 11, 16)
    private val now = Instant.parse("2026-11-16T02:15:06Z")
    // The publisher binding is tested separately. Here its actually selected website is configured.
    private val parish = Parish("osm:way:155168069", "Архиерейское подворье. Храм в честь Вознесения Господня",
        "Храм Вознесения Господня", "", "", "", source, "Europe/Moscow",
        "https://www.openstreetmap.org/way/155168069", "", "", "", emptyList(), "", "", 54.5305961, 36.3084198)
    private val council = "Отцов Поместного Собора Церкви Русской 1917–1918 годов"
    private val lydda = "Обновление храма вмч. Георгия в Лидде"
    private val expected = listOf(
        InternetService("2026-11-16", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-16", "17:00", "Вечерня с акафистом иконе Богородицы «Калужская»", "service_block"),
        InternetService("2026-11-17", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-17", "17:00", "Вечерня с акафистом блаженной Матроне Московской", "service_block"),
        InternetService("2026-11-18", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-18", "17:00", "Вечерня с акафистом святителю Николаю Чудотворцу", "service_block"),
        InternetService("2026-11-19", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-19", "17:00", "Вечерня с акафистом иконе Богородицы «Всецарица»", "service_block"),
        InternetService("2026-11-20", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-20", "17:00", "Вечерня с акафистом Архангелу Михаилу", "service_block"),
        InternetService("2026-11-21", "08:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-21", "17:00", "Всенощное бдение", "service_block"),
        InternetService("2026-11-22", "07:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-22", "09:00", "Божественная литургия", "liturgy"),
        InternetService("2026-11-22", "17:00", "Вечерня с акафистом Господу и Спасу нашему Иисусу Христу", "service_block")
    )
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/$name")).use { it.readBytes() }
    private fun html() = bytes("kaluga-current-week-2026-11-16.html").toString(Charsets.UTF_8)
    private fun document(value: String = html(), url: String = source) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(value, url))
    private fun inspect(value: String = html(), url: String = source) = ScheduleDocumentParser.inspect(document(value, url), today, parish)
    private fun changed(old: String, replacement: String): String {
        val raw = html()
        assertTrue("The mutation must address the actual recorded text", old in raw)
        return raw.replace(old, replacement)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `complete byte pinned current week retains all fifteen exact dates times titles and kinds`() {
        val raw = bytes("kaluga-current-week-2026-11-16.html")
        assertEquals(137701, raw.size)
        assertEquals("a2d7af9612f2b22e6fde2455b84939e8896f84f4781b511387b6a73b38cd7751", sha256(raw))
        val result = inspect()
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(expected, result.events)
        assertTrue(result.yearInferred)
        assertFalse(result.knownPartial)
        assertFalse(result.safetyConflict)
        assertFalse(result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty())
    }

    @Test fun `ordinary configured refresh consumes recorded pages and keeps inferred publication offline`() = runBlocking {
        val main = bytes("kaluga-current-week-2026-11-16.html")
        val about = bytes("kaluga-current-services-2026-11-16.html")
        assertEquals(137771, about.size)
        assertEquals("ea266bfe5c6bea7bdf22bf90b9effc6debfb8b2cdff488996de4cb4ca2e2792e", sha256(about))
        var stored: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = stored
            override fun write(parishId: String, value: String) { stored = value }
        }
        val calls = mutableListOf<String>()
        val online = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            ScheduleResponse(url, when (url) { source -> main; child -> about; else -> throw IOException("Unrecorded $url") }, "text/html; charset=UTF-8")
        }, cache, siteResolver = ScheduleSiteResolver { null })
        // No sourceOverride, manually seeded cache, alternative website or fixture HTML rewriting.
        val ready = online.refresh(parish, now, force = true)
        assertEquals(source, calls.first())
        assertEquals(InternetScheduleStatus.READY, ready.status)
        val publication = requireNotNull(ready.publication)
        assertEquals(source, publication.sourceUrl)
        assertEquals(expected, publication.events)
        assertTrue(publication.yearInferred)
        assertFalse(publication.knownPartial)
        var offlineCalls = 0
        val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls++; throw IOException("offline") }, cache,
            siteResolver = ScheduleSiteResolver { null })
        assertEquals(ready, offline.cached(parish))
        assertEquals(0, offlineCalls)
        val failed = offline.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(publication, failed.publication)
        assertEquals(failed, offline.cached(parish))
    }

    @Test fun `historical council spelling accepts only complete known names and preserves original UTF16 ranges`() {
        val original = "📖 $council. 2015"
        val ranges = ScheduleVenue.calendarRanges(original)
        assertEquals(listOf(council), ranges.map { original.substring(it) })
        assertTrue(ranges.none { original.lastIndexOf("2015") in it })
        for (separator in listOf("-", ".", "/", "–")) {
            val result = inspect(changed(council, council.replace("–", separator)))
            assertEquals(separator, expected, result.events)
            assertTrue(result.yearInferred)
        }
        for (incomplete in listOf(council.removeSuffix(" годов"), council.replace("1918", "1919"),
            council.replace("Поместного", "Другого"))) {
            val result = inspect(changed(council, incomplete))
            assertTrue(incomplete, result.events.isEmpty())
            assertFalse(incomplete, result.yearInferred)
        }
    }

    @Test fun `each publication year outside the exact phrase defeats inference even on the same line`() {
        for (year in listOf("2015", "2025", "2026")) {
            for (line in listOf("Расписание $year. $council", "$council. Расписание $year")) {
                val value = changed(council, line)
                assertTrue(line, ScheduleYearInference.months(document(value), today).isEmpty())
                assertFalse(line, inspect(value).yearInferred)
                assertTrue(line, inspect(value).events.isEmpty())
            }
        }
        val short = changed(council, "$council. Опубликовано 16.11.15")
        assertTrue(ScheduleYearInference.months(document(short), today).isEmpty())
        val original = document()
        val withOldPage = original.copy(pages = original.pages + ScheduleDocumentPage(listOf(ScheduleDocumentLine("Архив 2015"))))
        assertTrue(ScheduleYearInference.months(withOldPage, today).isEmpty())
        assertTrue(ScheduleDocumentParser.inspect(withOldPage, today, parish).events.isEmpty())
    }

    @Test fun `explicit governing stale year and archive URLs cannot be replaced by historical phrase exemption`() {
        val original = document()
        val stale = original.copy(pages = original.pages.map { it.copy(lines =
            listOf(ScheduleDocumentLine("Ноябрь 2015", headingLevel = 1)) + it.lines) })
        assertTrue(ScheduleYearInference.months(stale, today).isEmpty())
        assertTrue(ScheduleDocumentParser.inspect(stale, today, parish).events.isEmpty())
        for (url in listOf("https://example.org/2015/schedule/", "https://example.org/%32%30%31%35/schedule/",
            "https://example.org/schedule/?year=2015", "https://example.org/schedule-1917-1918/")) {
            assertTrue(url, ScheduleYearInference.months(document(url = url), today).isEmpty())
            assertTrue(url, inspect(url = url).events.isEmpty())
        }
    }

    @Test fun `weekday mismatch still defeats the complete current week`() {
        val result = inspect(changed("18 ноября, среда.", "18 ноября, четверг."))
        assertTrue(result.events.isEmpty())
        assertFalse(result.yearInferred)
        assertTrue(ScheduleYearInference.months(document(), today.plusWeeks(1)).isEmpty())
    }

    @Test fun `foreign and incomplete location outside either exact memory remains explicit venue evidence`() {
        for (memory in listOf(lydda, council)) {
            for (location in listOf("в Казанском храме в г. Москве", "в храме", "в храме в честь")) {
                val evidence = "$memory. $location"
                assertTrue(evidence, ScheduleVenue.isLabel(evidence))
                val label = ScheduleVenue.inlineLabel(evidence)
                assertNotNull(evidence, label)
                assertFalse(evidence, ScheduleVenue.matches(label, parish))
                val date = if (memory == lydda) "2026-11-16" else "2026-11-18"
                val result = inspect(changed(memory, evidence))
                assertTrue(evidence, result.events.none { it.date == date })
            }
        }
        assertFalse(ScheduleVenue.isLabel(lydda))
        assertFalse(ScheduleVenue.isLabel(council))
        assertNull(ScheduleVenue.inlineLabel(lydda))
        assertNull(ScheduleVenue.inlineLabel(council))
    }

    @Test fun `cancellation outside the historical memory removes only its explicit dated service or day`() {
        val cancelledService = inspect(changed("8-00. Божественная литургия", "8-00. Божественная литургия отменена"))
        // The recorded repeated 08:00 line occurs on six dates, all six explicit withdrawals survive.
        val cancelledKeys = (16..21).map { "2026-11-$it" to "08:00" }.toSet()
        assertEquals(expected.filterNot { it.date to it.time in cancelledKeys }, cancelledService.events)
        assertTrue(cancelledService.yearInferred)
        for (memory in listOf(lydda, council)) {
            val date = if (memory == lydda) "2026-11-16" else "2026-11-18"
            val result = inspect(changed(memory, "$memory. Все службы отменены"))
            assertEquals(setOf(date), result.withdrawnDates)
            assertEquals(expected.filterNot { it.date == date }, result.events)
            assertTrue(result.yearInferred)
        }
    }

    @Test fun `date range and cancellation outside the phrase never borrow a current single date`() {
        val value = changed(council, "$council. 18-19 ноября. 08:00 Литургия отменена")
        val result = inspect(value)
        assertTrue(result.safetyConflict)
        assertTrue(result.unresolvedWithdrawal)
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
        assertTrue(result.events.isEmpty())
        assertFalse(result.yearInferred)
    }
}
