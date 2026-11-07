package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

class CalendarEngineTest {
    @Test
    fun `all weekdays are correct including previously incorrect November fifth`() {
        val engine = CalendarEngine()
        val first = LocalDate.of(2026, 11, 1)
        val names = listOf("Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")

        names.forEachIndexed { offset, expected ->
            assertEquals(expected, engine.getDay(first.plusDays(offset.toLong()).toString()).dayOfWeek)
        }
    }

    @Test
    fun `today uses the parish clock across UTC midnight boundary`() {
        val clock = Clock.fixed(Instant.parse("2026-11-06T21:30:00Z"), ZoneId.of("Europe/Moscow"))
        val today = CalendarEngine(clock).getToday()

        assertEquals("2026-11-07", today.date)
        assertEquals("Суббота", today.dayOfWeek)
    }

    @Test
    fun `unverified schedules feast fasting and service claims remain absent`() {
        val engine = CalendarEngine()
        listOf("2026-11-01", "2026-11-04", "2026-11-28", "2027-01-07").forEach { date ->
            val day = engine.getDay(date)

            assertNull(day.scheduleToday)
            assertNull(day.feast)
            assertNull(day.fasting)
            assertNull(day.typicalService)
        }
    }

    @Test
    fun `invalid dates do not silently become another day`() {
        assertThrows(DateTimeParseException::class.java) { CalendarEngine().getDay("2026-02-30") }
    }
}
