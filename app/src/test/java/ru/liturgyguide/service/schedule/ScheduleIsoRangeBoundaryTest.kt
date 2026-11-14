package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleIsoRangeBoundaryTest {
    private fun inspect(html: String) = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, "https://parish.example/schedule/")),
        LocalDate.of(2026, 11, 15))

    @Test fun multipleIsoDatesCannotSupplyOneDayForTheFollowingUndatedService() {
        val result = inspect("""
            <h1>Ноябрь 2026</h1>
            <p>15, воскресенье</p><p>08:00 Литургия</p>
            <p>2026-11-16 - 2026-11-22</p><p>09:00 Молебен</p>
            <p>2026-11-17</p><p>10:00 Литургия</p>
        """.trimIndent())
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(listOf("2026-11-15" to "08:00", "2026-11-17" to "10:00"),
            result.events.map { it.date to it.time })
        assertFalse(result.safetyConflict)
    }

    @Test fun unresolvedIsoSpanCancellationRetainsTheFactWithoutInventingItsScope() {
        val result = inspect("""
            <h1>Ноябрь 2026</h1>
            <p>15, воскресенье</p><p>08:00 Литургия</p>
            <p>2026-11-16 - 2026-11-22: все службы отменены</p>
            <p>23, понедельник</p><p>09:00 Литургия</p>
        """.trimIndent())
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
        assertTrue(result.safetyConflict)
        assertTrue(result.unresolvedWithdrawal)
        assertTrue(result.events.isEmpty())
        assertTrue(result.withdrawnDates.isEmpty())
        assertTrue(result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll)
    }
}
