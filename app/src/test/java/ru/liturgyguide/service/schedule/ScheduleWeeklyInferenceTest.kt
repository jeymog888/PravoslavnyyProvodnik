package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleWeeklyInferenceTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val source = "https://example.org/raspisanie/"
    private val rows = """<table>
        <tr><td>11 ноября среда</td><td>08:30 Литургия</td></tr>
        <tr><td>12 ноября четверг</td><td>08:30 Литургия</td></tr>
        <tr><td>13 ноября пятница</td><td>08:30 Литургия</td></tr></table>"""
    private fun doc(html: String, url: String = source) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, url))
    private fun inspect(html: String, url: String = source, date: LocalDate = today) = ScheduleDocumentParser.inspect(doc(html, url), date)
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("schedule/weekly/$name.html")!!.bufferedReader().use { it.readText() }

    @Test fun `three full weekday anchors in current week work without invented month heading`() {
        val result = inspect(rows)
        assertTrue(result.yearInferred)
        assertEquals(listOf("2026-11-11", "2026-11-12", "2026-11-13"), result.events.map { it.date })
        assertFalse(inspect(rows.replace("11 ноября среда", "11 ноября 2026 среда")).yearInferred)
        assertTrue(inspect(rows, date = LocalDate.of(2026, 11, 18)).events.isEmpty())
        // The existing month-heading mode still supports the whole month, not only this week
        assertEquals(3, inspect("<h1>Расписание на ноябрь</h1>" + rows.replace("11 ноября среда", "18 ноября среда")
            .replace("12 ноября четверг", "19 ноября четверг").replace("13 ноября пятница", "20 ноября пятница")).events.size)
    }

    @Test fun `weekly mode requires distinct complete reliable dates and rejects contradictions`() {
        for (html in listOf(
            rows.replace("13 ноября пятница", "12 ноября четверг"),
            rows.replace("13 ноября пятница", "13 ноября четверг"),
            rows.replace("13 ноября пятница", "13 ноября"),
            rows.replace("13 ноября пятница", "13 декабря воскресенье"),
            rows.replace("13 ноября пятница", "32 ноября пятница"),
            rows.replace("11 ноября среда", "11 среда").replace("12 ноября четверг", "12 четверг").replace("13 ноября пятница", "13 пятница"),
            "<h1>Расписание на октябрь</h1>$rows"
        )) assertTrue(html, inspect(html).events.isEmpty())
        val plain = ScheduleDocument(source, "pdf", listOf(ScheduleDocumentPage(listOf(
            ScheduleDocumentLine("11 ноября среда | 08:30 Литургия"),
            ScheduleDocumentLine("12 ноября четверг | 08:30 Литургия"),
            ScheduleDocumentLine("13 ноября пятница | 08:30 Литургия", temporalConfidence = 74)))))
        assertTrue(ScheduleDocumentParser.inspect(plain, today).events.isEmpty())
    }

    @Test fun `archive source year defeats repeating weekday calendar including encoded path and query`() {
        // November 2015 and November 2026 have identical dates/weekdays in this week
        for (url in listOf("https://example.org/2015/11/raspisanie/", "https://example.org/raspisanie-2015.pdf",
            "https://example.org/%32%30%31%35/schedule/", "https://example.org/schedule/?year=2015")) {
            assertTrue(url, inspect(rows, url).events.isEmpty())
            assertTrue(url, inspect("<h1>Расписание на ноябрь</h1>$rows", url).events.isEmpty())
        }
        assertEquals(3, inspect(rows, "https://example.org/2026/11/schedule/").events.size)
        assertEquals(3, inspect(rows + "<footer>Архив 2015</footer>").events.size)
        assertTrue(inspect("<p>Архив 2015</p>$rows").events.isEmpty())
    }

    @Test fun `real Novosibirsk week imports table but not the undated confession and opening hours below it`() {
        val html = fixture("novosibirsk")
        val result = inspect(html, "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/")
        assertTrue(result.yearInferred)
        // 9 November lies before the parser's yesterday-and-later window, 10-15 November remain
        assertEquals(12, result.events.size)
        assertEquals(setOf("08:30", "17:00"), result.events.map { it.time }.toSet())
        assertFalse(result.events.any { it.title.startsWith("Исповедь") || it.time in setOf("07:30", "19:30") })
        assertEquals(listOf("08:30", "17:00"), result.events.filter { it.date == "2026-11-15" }.map { it.time })
    }

    @Test fun `real Kaluga prose provides seven weekday anchors without standalone heading`() {
        val html = fixture("kaluga")
        val document = doc(html, "http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/")
        val inferred = ScheduleYearInference.months(document, today)
        assertEquals(1, inferred.size)
        assertEquals("2026-11", inferred.getValue(0).value.toString())
        assertEquals(setOf(0), inferred.getValue(0).headingIndices)
        val events = ScheduleDocumentParser.inspect(document, today).events
        assertEquals(13, events.size)
        assertEquals(listOf("07:00", "09:00", "17:00"), events.filter { it.date == "2026-11-15" }.map { it.time })
    }

    @Test fun `table boundary resets row date but preserves explicit enclosing heading and next table`() {
        val html = """<h1>Ноябрь 2026</h1>
          <table><tr><td>11 ноября среда</td><td>08:30 Литургия</td></tr></table>
          <p>Исповедь в 8:30 (перед началом Божественной Литургии) и 17:00</p>
          <table><tr><td>12 ноября четверг</td><td>08:30 Литургия</td></tr></table>"""
        assertEquals(listOf("2026-11-11", "2026-11-12"), inspect(html).events.map { it.date })
        val underDayHeading = """<h2>11 ноября 2026</h2>
          <table><tr><td>08:30 Литургия</td></tr></table><p>17:00 Вечерня</p>"""
        assertEquals(listOf("08:30", "17:00"), inspect(underDayHeading).events.map { it.time })
        val lines = doc(html).pages.single().lines
        assertEquals(2, lines.count { it.endsTable })
    }

    @Test fun `weekly inference retains cancellation and does not rewrite original explicit stale year`() {
        val cancelled = inspect(rows.replace("12 ноября четверг</td><td>08:30 Литургия", "12 ноября четверг</td><td>08:30 Литургия отменена"))
        assertEquals(listOf("2026-11-11", "2026-11-13"), cancelled.events.map { it.date })
        assertTrue(inspect(rows.replace("11 ноября среда", "11 ноября 2015 среда")).events.isEmpty())
    }
}
