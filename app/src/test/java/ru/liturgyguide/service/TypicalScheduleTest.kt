package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.LocalDate

class TypicalScheduleTest {
    private val parish = Parish("trip", "Храм", "Храм", "Город", "", "", "", "Asia/Vladivostok",
        "", "", "2026-11-01", "2026-11-30", emptyList(), "", "")

    @Test fun `weekly orientation does not invent weekday services or exact times`() {
        assertTrue(TypicalSchedule.forDate(LocalDate.of(2026, 11, 10)).isEmpty())
        assertEquals("Вечером", TypicalSchedule.forDate(LocalDate.of(2026, 11, 14)).single().period)
        assertEquals("Литургия", TypicalSchedule.forDate(LocalDate.of(2026, 11, 15)).single().title)
        assertTrue(parish.schedule.isEmpty())
    }

    @Test fun `a chosen reading date crosses the year without becoming a scheduled start`() {
        val selected = LocalDate.of(2027, 1, 1)
        val context = requireNotNull(ReadingContextFactory.manualOnDate(parish, selected))
        assertEquals(selected.toString(), context.serviceDate)
        assertEquals(parish.id, context.parishId)
        assertEquals("Asia/Vladivostok", context.timezone)
        assertEquals(ReadingContextSource.MANUAL, context.source)
        assertNull(context.startEpochMillis)
        assertEquals(context, ReadingContextCodec.decode(ReadingContextCodec.encode(context)))
    }

    @Test fun `invalid timezone cannot create a manual date context`() {
        assertNull(ReadingContextFactory.manualOnDate(parish.copy(timezone = "Mars/Nowhere"), LocalDate.of(2026, 11, 11)))
    }

    @Test fun `church page cannot expose occurrences outside publication validity`() {
        val old = ParishService("2026-12-01", "09:00", "Литургия", "liturgy")
        assertTrue(PublishedSchedule.forDate(parish.copy(schedule = listOf(old)), LocalDate.of(2026, 12, 1)).isEmpty())
        assertTrue(PublishedSchedule.forDate(parish.copy(scheduleValidFrom = "", schedule = listOf(old)), LocalDate.of(2026, 12, 1)).isEmpty())
    }

    @Test fun `published day validates and sorts clock times without changing the data`() {
        val date = LocalDate.of(2026, 11, 15)
        val entries = listOf(ParishService(date.toString(), "10:00", "Поздняя литургия", "liturgy"),
            ParishService(date.toString(), "07:00", "Ранняя литургия", "liturgy"),
            ParishService(date.toString(), "bad", "Ошибочная строка", "liturgy"))
        val church = parish.copy(schedule = entries)
        assertEquals(listOf("07:00", "10:00"), PublishedSchedule.forDate(church, date).map { it.time })
        assertEquals(entries, church.schedule)
    }
}
