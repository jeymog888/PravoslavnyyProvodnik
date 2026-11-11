package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class LegalParishScheduleTest {
    private val parish = Parish("osm:relation:19595736", "Церковь Троицы Живоначальной в Листах", "Троицы в Листах",
        "Москва", "Сретенка, 27/29", "", "https://hram-troicy.ru/raspisanie/", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val legal = "Местная религиозная организация Православный приход Храма Живоначальной Троицы в Листах Московской епархии Русской Православной Церкви (Московский Патриархат)"
    private val date = LocalDate.of(2026, 11, 11)

    private fun parse(html: String, selected: Parish = parish) = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, parish.sourceUrl)), date, selected)

    @Test fun `real November publication survives legal menu and footer signatures`() {
        val html = requireNotNull(javaClass.classLoader?.getResourceAsStream("schedule/troicy/raspisanie-2026-11-11.html"))
            .bufferedReader().use { it.readText() }
        val result = parse(html)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        for (day in listOf("2026-11-11", "2026-11-12")) {
            val services = result.events.filter { it.date == day }
            assertEquals(day, listOf("08:00", "17:00"), services.map { it.time })
            assertTrue(services[0].title.contains("Часы") && services[0].title.contains("Литургия"))
            assertTrue(services[1].title.contains("Вечерня") && services[1].title.contains("Утреня"))
        }
        assertTrue("Legal name remains parish evidence", parse(html, parish.copy(name = "Никольский храм", shortName = "Никольский храм",
            address = "Никольская, 1")).events.isEmpty())
        assertTrue("Another Trinity church is not the same parish", parse(html, parish.copy(name = "Храм Троицы Живоначальной",
            shortName = "Троицкий храм", address = "Большая Сухаревская площадь, 3")).events.isEmpty())
    }

    @Test fun `legal words are removed only from a complete registered parish signature`() {
        assertEquals("Храма Живоначальной Троицы в Листах", ScheduleVenue.inlineLabel(legal))
        assertTrue(ScheduleVenue.matches(ScheduleVenue.inlineLabel(legal), parish))
        assertFalse(ScheduleVenue.matches(ScheduleVenue.inlineLabel("Нижний храм"), parish))
        assertFalse(ScheduleVenue.matches(ScheduleVenue.inlineLabel("Храм Казанской иконы Божией Матери"), parish))
    }

    @Test fun `real building labels and cancellations stay effective after legal header`() {
        val html = "<div>$legal</div><h1>Расписание на ноябрь 2026 года</h1>" +
            "<h2>Нижний храм</h2><p>11 ноября 2026 | 08:00 Литургия</p>" +
            "<h2>Храм Троицы Живоначальной в Листах</h2><p>11 ноября 2026 | 17:00 Вечерня отменена</p>"
        assertTrue(parse(html).events.isEmpty())
        val accepted = parse(html.replace("Нижний храм", "Храм Троицы Живоначальной в Листах"))
        assertEquals(listOf("08:00"), accepted.events.map { it.time })
        assertTrue(accepted.withdrawnStarts.contains("2026-11-11T17:00"))
    }
}
