package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class ScheduleYearInferenceTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val now = Instant.parse("2026-11-11T07:00:00Z")
    private val source = "https://example.org/schedule/"
    private val parish = Parish("test", "Никольский храм", "Никольский храм", "Москва", "", "", source,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val rows = """<table>
        <tr><td>11 ноября среда</td><td>09:00 Литургия</td></tr>
        <tr><td>12 ноября четверг</td><td>09:00 Литургия</td></tr>
        <tr><td>13 ноября пятница</td><td>09:00 Литургия</td></tr></table>"""
    private val html = "<h1>Расписание на ноябрь</h1>$rows"
    private fun inspect(value: String = html, date: LocalDate = today) = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(value, source)), date, parish)

    @Test fun `three distinct consistent weekday dates qualify but do not claim an explicit year`() {
        val result = inspect()
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertTrue(result.yearInferred)
        assertEquals(listOf("2026-11-11", "2026-11-12", "2026-11-13"), result.events.map { it.date })
        assertEquals(listOf("09:00", "09:00", "09:00"), result.events.map { it.time })
        val explicit = inspect(html.replace("Расписание на ноябрь", "Расписание на ноябрь 2026"))
        assertFalse(explicit.yearInferred)
        assertEquals(result.events, explicit.events)
    }

    @Test fun `insufficient distinct dates and any weekday conflict do not infer`() {
        val unsupported = listOf(
            html.replace("13 ноября пятница", "12 ноября четверг"),
            html.replace("13 ноября пятница", "13 ноября четверг"),
            html.replace("13 ноября пятница", "32 ноября пятница"),
            html.replace("13 ноября пятница", "13 декабря воскресенье"),
            html.replace("13 ноября пятница", "13 ноября")
        )
        unsupported.forEach { value ->
            assertTrue(value, inspect(value).events.isEmpty())
            assertFalse(value, inspect(value).yearInferred)
        }
        assertTrue(inspect(date = LocalDate.of(2026, 12, 1)).events.isEmpty())
    }

    @Test fun `stale printed years cannot be replaced even when the weekdays happen to match`() {
        for (year in listOf("2015", "2025", "2020")) {
            val result = inspect(html.replace("Расписание на ноябрь", "Расписание на ноябрь $year"))
            assertTrue(result.events.isEmpty())
            assertFalse(result.yearInferred)
        }
        assertTrue(inspect("<p>Архив 2015</p>$html").events.isEmpty())
        assertTrue(inspect(html.replace("13 ноября пятница", "13.11.15 пятница")).events.isEmpty())
    }

    @Test fun `inference preserves cancellation and foreign venue rejection`() {
        val cancelled = inspect(html.replace("12 ноября четверг</td><td>09:00 Литургия", "12 ноября четверг</td><td>09:00 Литургия отменена"))
        assertEquals(listOf("2026-11-11", "2026-11-13"), cancelled.events.map { it.date })
        assertTrue(cancelled.yearInferred)
        val foreign = inspect(html.replace("Литургия", "Литургия в Казанском храме"))
        assertEquals(ScheduleParseDisposition.REJECTED, foreign.disposition)
        assertTrue(foreign.events.isEmpty())
        assertFalse(foreign.yearInferred)
    }

    @Test fun `unreliable OCR cannot supply the extra weekday needed to infer a year`() {
        val page = ScheduleDocumentPage(listOf(ScheduleDocumentLine("Расписание на ноябрь"),
            ScheduleDocumentLine("11 ноября среда | 09:00 Литургия"),
            ScheduleDocumentLine("12 ноября четверг | 09:00 Литургия"),
            ScheduleDocumentLine("13 ноября пятница | 09:00 Литургия", temporalConfidence = 65)))
        val result = ScheduleDocumentParser.inspect(ScheduleDocument(source, "pdf", listOf(page)), today, parish)
        assertTrue(result.events.isEmpty())
        assertFalse(result.yearInferred)
    }

    @Test fun `inferred provenance survives repository cache and offline reopening`() = runBlocking {
        var stored: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = stored
            override fun write(parishId: String, value: String) { stored = value }
        }
        val online = InternetScheduleRepository(ScheduleTransport { url -> ScheduleResponse(url, html.toByteArray(), "text/html") }, cache)
        val state = online.refresh(parish, now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertTrue(requireNotNull(state.publication).yearInferred)
        val projected = parish.withInternetSchedule(state, now)
        assertTrue(projected.scheduleNote.contains("Год не указан"))
        assertFalse(projected.scheduleCoverage.contains("указанной датой"))
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache)
        assertEquals(state.publication, offline.cached(parish).publication)
        assertEquals(state.publication, offline.refresh(parish, now.plusSeconds(60), force = true).publication)
        assertTrue(requireNotNull(offline.cached(parish).publication).yearInferred)
        assertNull(ScheduleCacheCodec.decode(requireNotNull(stored).replace("\"yearInferred\":true", "\"yearInferred\":\"true\""), parish))
    }
}
