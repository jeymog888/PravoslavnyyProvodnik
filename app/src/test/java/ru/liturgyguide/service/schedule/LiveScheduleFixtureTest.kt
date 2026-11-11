package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

/** Unmodified public HTML captured on 2026-11-11, including the old CMS navigation. */
class LiveScheduleFixtureTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val kazanskyUrl = "https://kazansky-spb.ru/texts/raspisanie"
    private val dneprUrl = "https://trisvyat-dp.church.ua/raspisanie-bogosluzhenij/"
    private fun parish(name: String, source: String) = Parish("test", name, name, "", "", "", source,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/schedule/$name")).readText()
    private fun parse(html: String, source: String, name: String): ScheduleParseResult = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, source)), today, parish(name, source))

    @Test fun `Kazansky named dates and weekday paragraphs preserve the complete table when year is explicit`() {
        val source = fixture("kazansky-november-2026-no-year.html")
        // This assertion deliberately does not claim that the real source prints a year.
        val inferred = parse(source, kazanskyUrl, "Казанский кафедральный собор")
        assertTrue(inferred.yearInferred)
        val explicit = source.replace("Расписание на ноябрь", "Расписание на ноябрь 2026")
        val result = parse(explicit, kazanskyUrl, "Казанский кафедральный собор")
        assertFalse(result.yearInferred)
        assertEquals(result.events, inferred.events)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        for (day in 10..30) {
            val date = "2026-11-${day.toString().padStart(2, '0')}"
            assertTrue("Daily liturgy on $date", result.events.any { it.date == date && it.time == "10:00" && it.title.contains("литургия", true) })
        }
        assertEquals(listOf("07:00", "10:00", "18:00"), result.events.filter { it.date == "2026-11-15" }.map { it.time })
        assertEquals(listOf("10:00", "18:00"), result.events.filter { it.date == "2026-11-13" }.map { it.time })
        assertEquals(listOf("10:00", "18:00"), result.events.filter { it.date == "2026-11-21" }.map { it.time })
        assertTrue(result.events.none { it.title.contains("Жизнь собора") || it.title.contains("Секретарь") })
        assertTrue(parse(source.replace("Расписание на ноябрь", "Расписание на ноябрь 2015"),
            kazanskyUrl, "Казанский кафедральный собор").events.isEmpty())
    }

    @Test fun `weekly footer does not inherit the last explicit date`() {
        val html = "<h1>Ноябрь 2026</h1><p>29 ноября 08:00 Литургия</p>" +
            "<h2>Каждую среду:</h2><p>09:00 Молебен</p><p>30 ноября 10:00 Литургия</p>"
        val result = parse(html, dneprUrl, "Трехсвятительский храм")
        assertEquals(listOf("2026-11-29T08:00", "2026-11-30T10:00"), result.events.map { "${it.date}T${it.time}" })
    }

    @Test fun `a dated feast preface without its own clock retains the following service`() {
        val html = "<h1>Ноябрь 2026</h1><p>21.11.26, суббота Собор Архистратига Михаила</p>" +
            "<p>07:30 Утреня. Божественная Литургия</p><p>14:00 Вечернее Богослужение</p>"
        assertEquals(listOf("07:30", "14:00"), parse(html, dneprUrl, "Трехсвятительский храм").events.map { it.time })
        assertTrue(parse(html.replace("21.11.26, суббота ", ""), dneprUrl, "Трехсвятительский храм").events.isEmpty())
    }

    @Test fun `recurring rows inside the same HTML paragraph never inherit November 29`() {
        val html = "<h1>Ноябрь 2026</h1><p>29 ноября 08:00 Литургия<br>ЕЖЕДНЕВНО:<br>09:00 Молебен<br>КАЖДУЮ СРЕДУ:<br>09:00 Молебен с акафистом</p>"
        assertEquals(listOf("08:00"), parse(html, dneprUrl, "Трехсвятительский храм").events.map { it.time })
        val real = parse(fixture("dnepr-november-2026.html"), dneprUrl, "Трехсвятительский храм")
        assertEquals(listOf("08:00"), real.events.filter { it.date == "2026-11-29" }.map { it.time })
    }

    @Test fun `Dnepr short calendar feast does not erase its liturgy or following evening service`() {
        val result = parse(fixture("dnepr-november-2026.html"), dneprUrl, "Трехсвятительский храм")
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertTrue(result.events.any { it.date == "2026-11-15" && it.time == "08:00" && it.title.contains("Литургия") })
        assertEquals(listOf("07:30", "14:00"), result.events.filter { it.date == "2026-11-21" }.map { it.time })
        assertTrue(result.events.first { it.date == "2026-11-21" }.title.contains("Божественная Литургия"))
    }

    @Test fun `calendar prefaces never exempt separate cathedral headings venue cells or explicit locations`() {
        val prefix = "<h1>Ноябрь 2026</h1>"
        val inputs = listOf(
            "<h2>Собор Архистратига Михаила</h2><p>21 ноября 09:00 Литургия</p>",
            "<table><tr><td>21 ноября</td><td>Собор Архистратига Михаила</td><td>09:00 Литургия</td></tr></table>",
            "<p>21 ноября 09:00 Литургия в соборе Архистратига Михаила</p>",
            "<p>21 ноября 09:00 Литургия | Собор Архистратига Михаила</p>",
            "<p>22 ноября | Собор Архистратига Михаила<br>09:00 Литургия</p>",
            "<p>21 ноября | Собор Архистратига Михаила<br>09:00 Литургия в Успенском соборе</p>"
        )
        inputs.forEach { assertTrue(it, parse(prefix + it, dneprUrl, "Трехсвятительский храм").events.isEmpty()) }
    }

    @Test fun `named date wrapping still validates weekday and cannot erase a foreign venue column`() {
        val html = """<h1>Ноябрь 2026</h1><table><tr><td><p>15 ноября</p><p>воскресенье</p></td>
            <td><p>Неделя 24-я по Пятидесятнице</p><p>09:00 Литургия</p><p>18:00 Вечерня</p></td></tr></table>"""
        assertEquals(listOf("09:00", "18:00"), parse(html, kazanskyUrl, "Казанский собор").events.map { it.time })
        assertTrue(parse(html.replace("воскресенье", "суббота"), kazanskyUrl, "Казанский собор").events.isEmpty())
        assertTrue(parse(html.replace("</tr>", "<td>Никольский храм</td></tr>"), kazanskyUrl, "Казанский собор").events.isEmpty())
    }

    @Test fun `anniversary descriptions preserve the date but invalid date headings still reset it`() {
        val prefix = "<h1>Ноябрь 2026</h1><p>20 ноября</p>"
        for (anniversary in listOf("80-летие Святейшего Патриарха", "25-летие освящения")) {
            assertEquals(listOf("2026-11-20" to "10:00"), parse(prefix + "<p>$anniversary</p><p>10:00 Литургия</p>",
                kazanskyUrl, "Казанский собор").events.map { it.date to it.time })
        }
        for (ambiguousDate in listOf("35 ноября", "14-15 ноября")) {
            assertTrue(parse(prefix + "<p>$ambiguousDate</p><p>10:00 Литургия</p>", kazanskyUrl, "Казанский собор").events.isEmpty())
        }
    }
}
