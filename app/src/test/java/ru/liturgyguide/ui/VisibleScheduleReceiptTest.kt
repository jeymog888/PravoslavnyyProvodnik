package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.*
import java.time.Instant

class VisibleScheduleReceiptTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val service = ParishService("2026-11-16", "09:00", "Часы. Литургия", "service_block")
    private val url = "https://parish.example/schedule/"
    private val church = Parish("church", "Никольский храм", "Никольский храм", "Москва", "", "", "https://parish.example/",
        "Europe/Moscow", url, "", "", "", emptyList(), "", "")
    private val publication = SchedulePublication(church.id, church.timezone, church.website, url, now.toString(),
        listOf(InternetService(service.date, service.time, service.title, service.kind)))
    private val state = InternetScheduleState(church.id, InternetScheduleStatus.READY, website = church.website,
        sourceUrl = url, publication = publication, checkedAt = now.toString())
    private fun visible(input: InternetScheduleState = state, member: ParishService? = service, time: Instant? = now,
        owner: Parish? = church) = visibleScheduleCheckReceipt(input, church.timezone, owner, member, time)

    @Test fun actualFreshVisibleMemberGetsOnePlainLocalReceiptAndRepeatedRetryTimeStaysDistinct() {
        assertEquals("Проверено сегодня в 13:00:00", visible())
        assertEquals("Проверено сегодня в 13:00:01", visible(state.copy(checkedAt = now.plusSeconds(1).toString())))
        assertTrue(requireNotNull(scheduleCheckReceipt(state, church.timezone)).startsWith("Расписание получено\n"))
    }

    @Test fun emptyWrongEditionAndUnprovenOwnerKeepDetailedFeedback() {
        val changed = listOf(state.copy(publication = publication.copy(events = emptyList())),
            state.copy(publication = publication.copy(parishId = "other")), state.copy(parishId = "other"),
            state.copy(sourceUrl = url + "other"), state.copy(publication = publication.copy(parserRevision = 0)),
            state.copy(publication = publication.copy(fetchedAt = now.minusSeconds(86401).toString())))
        changed.forEach { assertEquals(scheduleCheckReceipt(it, church.timezone, church), visible(it)) }
        assertEquals(scheduleCheckReceipt(state, church.timezone, church), visible(member = null))
        assertEquals(scheduleCheckReceipt(state, church.timezone, church), visible(member = service.copy(kind = "liturgy")))
        assertEquals(scheduleCheckReceipt(state, church.timezone, church), visible(time = null))
        val another = church.copy(sourceUrl = "https://other.example/schedule/")
        assertEquals(scheduleCheckReceipt(state, church.timezone, another), visible(owner = another))
    }

    @Test fun errorSafetyVerificationAndLoadingAreNeverShortenedByRetainedPublication() {
        val states = listOf(state.copy(status = InternetScheduleStatus.UNAVAILABLE), state.copy(publicationRejected = true),
            state.copy(sourceNeedsVerification = true), state.copy(sourceHasWithdrawals = true), state.copy(loading = true))
        states.forEach { assertEquals(scheduleCheckReceipt(it, church.timezone, church), visible(it)) }
    }

    @Test fun compactAdmissionCannotUseAnotherSourcesFreshPublicationOrAnAlteredKind() {
        assertTrue(isFreshPublishedService(church, state, service, now))
        val urlB = "https://new.example/schedule/"
        val freshB = state.copy(sourceUrl = urlB, publication = publication.copy(sourceUrl = urlB))
        assertFalse(isFreshPublishedService(church, freshB, service, now))
        assertFalse(isFreshPublishedService(church, state, service.copy(kind = "liturgy"), now))
        assertFalse(isFreshPublishedService(church, state.copy(publicationRejected = true), service, now))
        assertFalse(isFreshPublishedService(church, state, service, now.plusSeconds(86401)))
    }

    @Test fun localCheckDateSurvivesYearBoundaryAndDoesNotUsePublicationFetchTime() {
        val newYear = Instant.parse("2026-12-31T22:00:00Z")
        val member = service.copy(date = "2027-01-01")
        val input = state.copy(checkedAt = "2026-12-31T19:00:00Z", publication = publication.copy(fetchedAt = newYear.toString(),
            events = listOf(InternetService(member.date, member.time, member.title, member.kind))))
        assertEquals("Проверено: 31 декабря 2026, 22:00:00", visible(input, member = member, time = newYear))
        val sameYear = state.copy(checkedAt = "2026-11-15T19:00:00Z")
        assertEquals("Проверено: 15 ноября, 22:00:00", visible(sameYear))
    }
}
