package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import java.time.Instant
import java.time.LocalDate

class ArrivalScheduleTest {
    private val date = LocalDate.of(2026, 11, 11)
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val church = Parish("trip", "Троицкий храм", "Троицкий храм", "Москва", "Сретенка, 27", "", "",
        "Europe/Moscow", "", "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private fun service(time: String, title: String = "Литургия", day: LocalDate = date) = ParishService(day.toString(), time, title, "service_block")

    @Test fun selectedTodayNeverBorrowsTomorrowsEvent() {
        val tomorrow = service("08:00", day = date.plusDays(1))
        val result = arrivalSchedule(church.copy(schedule = listOf(tomorrow)), date, now, null)
        assertTrue(result.entries.isEmpty())
        assertNull(result.featured)
        assertNull(result.relation)
    }

    @Test fun afterAllStartsTheLastEntryIsNotPresentedAsUpcomingOrTheFirstEntry() {
        val morning = service("08:00")
        val evening = service("17:00", "Вечерня")
        val result = arrivalSchedule(church.copy(schedule = listOf(morning, evening)), date, Instant.parse("2026-11-11T19:00:00Z"), null)
        assertEquals(evening, result.featured)
        assertEquals("Начало уже прошло", result.relation)
        assertEquals(listOf(morning, evening), result.entries)
    }

    @Test fun recentStartIsExplicitlyAnEstimateAndNextDayHasNoLiveClaim() {
        val liturgy = service("08:00")
        val scheduled = church.copy(schedule = listOf(liturgy))
        val live = arrivalSchedule(scheduled, date, Instant.parse("2026-11-11T05:30:00Z"), null)
        assertEquals("Возможно, идет · по времени начала", live.relation)
        val previousDay = arrivalSchedule(scheduled, date, Instant.parse("2026-11-10T05:30:00Z"), null)
        assertEquals("Ближайшая служба по расписанию", previousDay.relation)
    }

    @Test fun staleSavedPublicationIsReadableButCannotClaimLiveSynchronization() {
        val result = arrivalSchedule(church, date, now, cached())
        assertEquals("12:30", result.featured?.time)
        assertTrue(result.saved)
        assertEquals("Сохраненное расписание - проверьте изменения", result.relation)
    }

    @Test fun rejectedPublicationAndOtherChurchOrSourceCannotLeakIntoTheDay() {
        for (state in listOf(cached().copy(publicationRejected = true), cached().copy(parishId = "other"),
            cached().copy(sourceUrl = "https://different.example/schedule"),
            cached().copy(publication = cached().publication!!.copy(parishId = "other")),
            cached().copy(publication = cached().publication!!.copy(timezone = "Asia/Vladivostok")))) {
            assertTrue(arrivalSchedule(church, date, now, state).entries.isEmpty())
        }
    }

    @Test fun anInferredYearNeverClaimsAnOngoingService() {
        val scheduled = church.copy(schedule = listOf(service("12:30")), sourceUrl = "https://example.org/schedule")
        assertEquals("Время из расписания храма", arrivalSchedule(scheduled, date, now,
            cached().copy(publication = cached().publication!!.copy(yearInferred = true))).relation)
    }

    @Test fun emptyStatusDistinguishesFailureFromAbsenceOfActualServices() {
        assertEquals("Нет записей на эту дату", scheduleEmptyTitle(null, hasPublication = true))
        assertEquals("Расписание пока не найдено", scheduleEmptyTitle(null, hasPublication = false))
        assertTrue(scheduleEmptyDescription(null).contains("не означает, что служб не будет"))
        assertTrue(scheduleEmptyDescription(cached().copy(status = InternetScheduleStatus.UNAVAILABLE)).contains("Не удалось обновить"))
        assertTrue(scheduleEmptyDescription(cached().copy(loading = true)).contains("Проверяем"))
    }

    private fun cached() = InternetScheduleState(church.id, InternetScheduleStatus.UNAVAILABLE,
        sourceUrl = "https://example.org/schedule", publication = SchedulePublication(church.id, church.timezone,
            "https://example.org", "https://example.org/schedule", "2026-11-08T10:00:00Z",
            listOf(InternetService(date.toString(), "12:30", "Литургия", "service_block"))))
}
