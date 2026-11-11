package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleHyphenClockTest {
    private fun parse(body: String) = ScheduleDocumentParser.parse(HtmlScheduleDocumentExtractor.fromHtml(
        Jsoup.parse("<h1>Ноябрь 2026</h1><h2>15 ноября</h2>$body", "https://example.org/schedule/")), LocalDate.of(2026,11,11))

    @Test fun `dated hyphen clocks with punctuation and parallel table cells retain hours minutes and titles`() {
        assertEquals(listOf("08:00", "09:15", "17:30"), parse("<p>8-00. Литургия</p><p>9-15 Молебен</p><p>17-30. Вечерня</p>").map { it.time })
        val table = parse("<table><tr><td>08-00<br>09-15</td><td>Часы<br>Литургия</td></tr></table>")
        assertEquals(listOf("08:00" to "Часы", "09:15" to "Литургия"), table.map { it.time to it.title })
        assertEquals(listOf("08:00"), parse("<p>08:00. Литургия</p>").map { it.time })
    }

    @Test fun `date ranges full numeric dates anniversaries and invalid clocks are not hyphen times`() {
        for (text in listOf("14-15 ноября Литургия", "14-15 ноября 2026 Литургия", "14-15.11.2026 Литургия",
            "100-летие совершения литургии", "10-00-летие литургии", "10-15 годовщина литургии", "24-00 Литургия", "8-65 Литургия")) {
            assertTrue(text, parse("<p>$text</p>").isEmpty())
        }
    }

    @Test fun `hyphen clock alone cannot provide missing service date and cancellation remains authoritative`() {
        val undated = ScheduleDocumentParser.parse(HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse("<p>8-00. Литургия</p>", "https://example.org/")), LocalDate.of(2026,11,11))
        assertTrue(undated.isEmpty())
        assertTrue(parse("<p>8-00. Литургия отменена</p>").isEmpty())
    }
}
