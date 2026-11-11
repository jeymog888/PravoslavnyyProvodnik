package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Instant
import java.time.LocalDate

class TodayServiceSelectionTest {
    private val today = LocalDate.parse("2026-11-11")
    private fun event(date: String, time: String) = ParishService(date, time, "Литургия", "liturgy")
    private fun parish(events: List<ParishService>, zone: String = "Europe/Moscow") = Parish(
        "test", "Тестовый храм", "Тестовый храм", "Москва", "", "", "", zone, "", "",
        "2026-11-01", "2026-12-01", events, "", "")

    @Test fun afterMorningServiceShowsNextPublishedDateRatherThanOldStart() {
        val church = parish(listOf(event("2026-11-11", "08:00"), event("2026-11-12", "09:30")))
        val result = TodayServiceSelection.pick(church, today, Instant.parse("2026-11-11T13:00:00Z"), true)!!
        assertEquals("2026-11-12", result.date.toString())
        assertEquals("09:30", result.service.time)
        assertFalse(result.mayBeInProgress)
        assertNull(TodayServiceSelection.pick(church, today, Instant.parse("2026-11-11T13:00:00Z"), false))
    }

    @Test fun currentEstimateUsesParishTimezoneAcrossDateBoundary() {
        val church = parish(listOf(event("2026-11-11", "01:00"), event("2026-11-11", "08:00")), "Asia/Vladivostok")
        val result = TodayServiceSelection.pick(church, today, Instant.parse("2026-11-10T15:30:00Z"), true)!!
        assertEquals("01:00", result.service.time)
        assertTrue(result.mayBeInProgress)
    }

    @Test fun tomorrowIsNotMarkedAsInProgressAndEventsAreSorted() {
        val church = parish(listOf(event("2026-11-12", "18:00"), event("2026-11-12", "09:00")))
        val result = TodayServiceSelection.pick(church, today.plusDays(1), Instant.parse("2026-11-11T06:00:00Z"), false)!!
        assertEquals("09:00", result.service.time)
        assertFalse(result.mayBeInProgress)
    }

    @Test fun expiredScheduleAndUnknownTimezoneDoNotInventAService() {
        val church = parish(listOf(event("2026-11-11", "08:00")))
        assertNull(TodayServiceSelection.pick(church.copy(scheduleValidUntil = "2026-11-10"), today,
            Instant.parse("2026-11-11T05:00:00Z"), true))
        assertNull(TodayServiceSelection.pick(church.copy(timezone = "invalid"), today,
            Instant.parse("2026-11-11T05:00:00Z"), true))
    }
}
