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
    @Test fun pendingSourceIdentityIsDifferentFromAbsentServicesOrAnActiveNetworkRequest() {
        val state = ru.liturgyguide.service.schedule.InternetScheduleState("pending",
            status = ru.liturgyguide.service.schedule.InternetScheduleStatus.UNAVAILABLE,
            checkedAt = "2026-11-12T06:00:00Z", sourceNeedsVerification = true)
        assertEquals("Нужно уточнить расписание", scheduleEmptyTitle(state, false))
        assertTrue(scheduleEmptyDescription(state, true).contains("относится к этому храму"))
        assertTrue(scheduleEmptyDescription(state, true).contains("на сайте"))
        assertFalse(scheduleEmptyDescription(state, false).contains("на сайте"))
        assertEquals("Проверяем источник расписания", scheduleEmptyTitle(state.copy(loading = true), false))
        assertNull(scheduleCheckReceipt(state.copy(loading = true), "Europe/Moscow"))
        assertTrue(scheduleCheckReceipt(state, "Europe/Moscow")!!.startsWith("Источник требует проверки"))
    }
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
        assertSame(tomorrow, result.next?.service)
        assertFalse(requireNotNull(result.next).saved)
        assertFalse(requireNotNull(result.next).yearInferred)
    }

    @Test fun nextOfferUsesTheEarliestValidFutureStartInTheParishZoneAndNeverReplacesAPopulatedDay() {
        val selected = LocalDate.of(2026, 12, 31)
        val nextDay = selected.plusDays(1)
        val first = service("03:00", "Вечернее богослужение", nextDay)
        val earlier = service("01:30", day = nextDay)
        val scheduled = church.copy(timezone = "Asia/Vladivostok", scheduleValidFrom = selected.toString(),
            scheduleValidUntil = "2027-01-02", schedule = listOf(
                service("08:00", day = nextDay.plusDays(1)), service("00:30", day = selected.minusDays(1)),
                service("00:30", day = nextDay).copy(date = "2027-13-01"), service("25:00", day = nextDay),
                service("02:45", "", nextDay), first, earlier))
        // In Vladivostok it is already January 1, 02:30. The 01:30 start has passed.
        val current = Instant.parse("2026-12-31T16:30:00Z")
        val result = arrivalSchedule(scheduled, selected, current, null)
        assertTrue(result.entries.isEmpty())
        assertNull(result.featured)
        assertSame(first, result.next?.service)
        assertNull(arrivalSchedule(scheduled.copy(scheduleValidUntil = selected.toString()), selected, current, null).next)
        assertNull(arrivalSchedule(scheduled.copy(timezone = "not/a-zone"), selected, current, null).next)
        val onSelectedDay = service("12:00", day = selected)
        val populated = arrivalSchedule(scheduled.copy(schedule = scheduled.schedule + onSelectedDay), selected, current, null)
        assertEquals(listOf(onSelectedDay), populated.entries)
        assertEquals(onSelectedDay, populated.featured)
        assertNull(populated.next)
        val beforeClockChange = LocalDate.of(2026, 3, 28)
        val nonexistentLocalStart = church.copy(timezone = "Europe/Berlin", scheduleValidFrom = "2026-03-28",
            scheduleValidUntil = "2026-03-29", schedule = listOf(service("02:30", day = beforeClockChange.plusDays(1))))
        assertNull(arrivalSchedule(nonexistentLocalStart, beforeClockChange, null, null).next)
    }

    @Test fun futureSavedAndInferredWarningsBelongToTheOfferRatherThanTheEmptySelectedDay() {
        val future = service("18:00", "Вечернее богослужение. Акафист Пресвятой Богородице", date.plusDays(2))
        val state = cached().copy(publication = cached().publication!!.copy(yearInferred = true,
            events = listOf(InternetService(future.date, future.time, future.title, future.kind))))
        val result = arrivalSchedule(church, date, now, state)
        assertTrue(result.entries.isEmpty())
        assertNull(result.featured)
        assertFalse(result.saved)
        assertEquals(ArrivalScheduleOffer(future, saved = true, yearInferred = true), result.next)
        val laterBundled = service("08:00", day = date.plusDays(3))
        assertEquals(future, arrivalSchedule(church.copy(schedule = listOf(laterBundled)), date, now, state).next?.service)
    }

    @Test fun futureOfferRejectsForeignUnverifiedWithdrawnAndMalformedPublications() {
        val event = InternetService(date.plusDays(1).toString(), "18:00", "Вечерня", "service_block")
        val state = cached().copy(publication = cached().publication!!.copy(events = listOf(event)))
        for (rejected in listOf(state.copy(publicationRejected = true), state.copy(sourceNeedsVerification = true),
            state.copy(parishId = "other"), state.copy(sourceUrl = "https://different.example/schedule"),
            state.copy(publication = state.publication!!.copy(parishId = "other")),
            state.copy(publication = state.publication!!.copy(timezone = "Asia/Vladivostok")),
            state.copy(publication = state.publication!!.copy(events = listOf(event.copy(date = "tomorrow")))),
            state.copy(publication = state.publication!!.copy(events = listOf(event.copy(time = "25:00")))),
            state.copy(publication = state.publication!!.copy(events = listOf(event.copy(title = "")))))) {
            assertNull(rejected.toString(), arrivalSchedule(church, date, now, rejected).next)
        }
        val bundled = church.copy(schedule = listOf(service("18:00", day = date.plusDays(1))))
        assertNull(arrivalSchedule(bundled, date, now, state.copy(publicationRejected = true)).next)
        assertNull(arrivalSchedule(bundled, date, now, state.copy(sourceNeedsVerification = true)).next)
        assertNull(arrivalSchedule(church, date, Instant.parse("2026-11-12T16:00:00Z"), state).next)
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
        for (state in listOf(cached().copy(publicationRejected = true), cached().copy(sourceNeedsVerification = true), cached().copy(parishId = "other"),
            cached().copy(sourceUrl = "https://different.example/schedule"),
            cached().copy(publication = cached().publication!!.copy(parishId = "other")),
            cached().copy(publication = cached().publication!!.copy(timezone = "Asia/Vladivostok")))) {
            assertTrue(arrivalSchedule(church, date, now, state).entries.isEmpty())
        }
    }

    @Test fun pendingSourceDoesNotBorrowBundledRowsFromTheSameUnconfirmedBinding() {
        val pending = cached().copy(sourceNeedsVerification = true)
        assertTrue(arrivalSchedule(church.copy(schedule = listOf(service("08:00"))), date, now, pending).entries.isEmpty())
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
