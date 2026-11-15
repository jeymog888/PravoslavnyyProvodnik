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

class ArrivalPartialScheduleTest {
    private val date = LocalDate.of(2026, 11, 15)
    private val now = Instant.parse("2026-11-15T05:00:00Z")
    private val source = "https://parish.example/schedule/"
    private val church = Parish("church", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        source, "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private fun event(day: LocalDate = date) = ParishService(day.toString(), "09:00", "Литургия", "liturgy")
    private fun state(day: LocalDate = date, partial: Boolean = true, url: String = source) = InternetScheduleState(church.id,
        InternetScheduleStatus.READY, website = "https://parish.example/", sourceUrl = url, checkedAt = now.toString(),
        publication = SchedulePublication(church.id, church.timezone, "https://parish.example/", url, now.toString(),
            listOf(InternetService(day.toString(), "09:00", "Литургия", "liturgy")), knownPartial = partial))

    @Test fun partialPublishedOccurrenceUsesItsOwnFlagAndExactSourceInsteadOfPromisingTheNextService() {
        val selected = church.copy(schedule = listOf(event()), scheduleKnownPartial = true)
        val result = arrivalSchedule(selected, date, now, null)
        assertTrue(result.knownPartial)
        assertEquals(source, result.sourceUrl)
        assertEquals("Служба из опубликованного расписания", result.relation)
        assertFalse(result.saved)
    }

    @Test fun cachedDiagnosticDoesNotLeakIntoPublishedEventsFromAnotherSource() {
        val published = church.copy(schedule = listOf(event()), sourceUrl = "https://bundle.example/calendar/")
        val result = arrivalSchedule(published, date, now, state())
        assertFalse(result.knownPartial)
        assertNull(result.sourceUrl)
        assertEquals("Ближайшая служба по расписанию", result.relation)
        val partialPublished = published.copy(scheduleKnownPartial = true)
        val reverse = arrivalSchedule(partialPublished, date, now, state(partial = false))
        assertTrue(reverse.knownPartial)
        assertEquals(partialPublished.sourceUrl, reverse.sourceUrl)
    }

    @Test fun emptyDayOfferCarriesTheActualFutureProviderAndDoesNotPromiseAbsence() {
        val future = date.plusDays(6)
        val cached = state(future)
        val result = arrivalSchedule(church, date, now, cached)
        assertNull(result.featured)
        assertTrue(result.knownPartial)
        val offer = requireNotNull(result.next)
        assertTrue(offer.knownPartial)
        assertTrue(offer.saved)
        assertEquals(source, offer.sourceUrl)
        assertEquals(source, result.sourceUrl)
        assertEquals("Расписание прочитано не полностью", scheduleEmptyTitle(cached, true, church, result.knownPartial))
        assertEquals("Не все записи удалось прочитать. Проверьте выбранную дату на сайте храма",
            scheduleEmptyDescription(cached, true, church, result.knownPartial))
    }

    @Test fun savedAndOfflineFlagsStayVisibleWithoutStealingAnotherParishOrUrl() {
        val saved = state().copy(status = InternetScheduleStatus.UNAVAILABLE)
        val result = arrivalSchedule(church, date, now, saved)
        assertTrue(result.saved && result.knownPartial)
        assertEquals("Сохраненное расписание - проверьте изменения", result.relation)
        assertEquals(source, result.sourceUrl)
        for (wrong in listOf(saved.copy(parishId = "other"), saved.copy(sourceUrl = "https://wrong.example/schedule/"),
            saved.copy(publication = saved.publication!!.copy(parishId = "other")), saved.copy(publicationRejected = true),
            saved.copy(sourceNeedsVerification = true))) {
            val rejected = arrivalSchedule(church, date, now, wrong)
            assertFalse(rejected.knownPartial)
            assertNull(rejected.sourceUrl)
        }
    }

    @Test fun ordinaryOffersKeepTheirPreviousModelDefaultsAndSafetyCopyWins() {
        val future = date.plusDays(6)
        val ordinary = arrivalSchedule(church.copy(schedule = listOf(event(future))), date, now, null)
        assertEquals(ArrivalScheduleOffer(event(future), saved = false, yearInferred = false), ordinary.next)
        assertFalse(ordinary.knownPartial)
        assertNull(ordinary.sourceUrl)
        val unsafe = state().copy(publicationRejected = true)
        assertEquals("Расписание требует уточнения", scheduleEmptyTitle(unsafe, true, church, knownPartial = true))
        assertTrue(scheduleEmptyDescription(unsafe, true, church, knownPartial = true).startsWith("Источник не подтверждает"))
        assertEquals("Ищем расписание", scheduleEmptyTitle(state().copy(loading = true), true, church, knownPartial = true))
    }
}
