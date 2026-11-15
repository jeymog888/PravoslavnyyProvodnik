package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate

class ScheduleAgendaTest {
    private val now = Instant.parse("2026-12-31T22:00:00Z")
    private val source = "https://church.example/schedule"
    private val church = Parish("church", "Никольский храм", "Никольский храм", "Москва", "", "", "https://church.example/",
        "Europe/Moscow", source, "", "2026-12-01", "2027-01-31", emptyList(), "", "")
    private val first = InternetService("2027-01-01", "09:00", "Часы. Литургия. Панихида", "service_block")
    private val second = InternetService("2027-01-03", "18:00", "Всенощное бдение. Исповедь", "service_block")
    private val publication = SchedulePublication(church.id, church.timezone, church.website, source, now.toString(), listOf(first, second))
    private val state = InternetScheduleState(church.id, InternetScheduleStatus.READY, sourceUrl = source, publication = publication)
    private fun InternetService.service() = ParishService(date, time, title, kind)

    @Test fun oneEditionDoesNotFillMissingDatesFromParishProjection() {
        val old = church.copy(schedule = listOf(first.copy(date = "2027-01-02").service()))
        val result = requireNotNull(scheduleAgenda(old, state, now))
        assertEquals(LocalDate.of(2027, 1, 1), result.today)
        assertEquals(listOf("2027-01-01", "2027-01-03"), result.days.map { it.date.toString() })
        assertEquals(first.service(), result.days.first().entries.single())
        assertFalse(result.saved)
        assertEquals(source, result.sourceUrl)
    }

    @Test fun sourceOwnerParserAndAuthorityMustAllBeAdmissible() {
        val bad = listOf(state.copy(parishId = "other"), state.copy(sourceUrl = source + "/other"),
            state.copy(publicationRejected = true), state.copy(sourceNeedsVerification = true),
            state.copy(publication = publication.copy(parishId = "other")),
            state.copy(publication = publication.copy(timezone = "UTC")),
            state.copy(publication = publication.copy(parserRevision = 0)),
            state.copy(sourceUrl = "javascript:bad", publication = publication.copy(sourceUrl = "javascript:bad")),
            state.copy(publication = publication.copy(fetchedAt = "unknown")))
        bad.forEach { assertNull(it.toString(), scheduleAgenda(church, it, now)) }
        assertNull(scheduleAgenda(church, null, now))
        assertNull(scheduleAgenda(church, state, null))
        assertNull(scheduleAgenda(church.copy(timezone = "Bad/Zone"), state, now))
    }

    @Test fun failedRefreshRetainsOwnFlagsAndNeverRequiresReady() {
        val saved = state.copy(status = InternetScheduleStatus.UNAVAILABLE,
            publication = publication.copy(yearInferred = true, knownPartial = true, fetchedAt = now.minusSeconds(172800).toString()),
            sourceHasWithdrawals = true)
        val result = requireNotNull(scheduleAgenda(church, saved, now))
        assertTrue(result.saved && result.publication.yearInferred && result.publication.knownPartial)
        assertEquals(saved.publication, result.publication)
        assertEquals(source, result.sourceUrl)
    }

    @Test fun filtersMalformedPastAndExactDuplicatesButKeepsAllKindsAndTodayStarts() {
        val events = listOf(first, first, first.copy(date = "2026-12-31"), first.copy(date = "2027-02-30"),
            first.copy(time = "25:00"), first.copy(title = " "), first.copy(title = "Литургия\nв другом храме"),
            first.copy(kind = ""), first.copy(time = "08:00", title = "Молебен", kind = "service_block"), second)
        val result = requireNotNull(scheduleAgenda(church, state.copy(publication = publication.copy(events = events)), now))
        assertEquals(listOf("08:00", "09:00"), result.days.first().entries.map { it.time })
        assertEquals(3, result.days.sumOf { it.entries.size })
        val laterToday = now.plusSeconds(12 * 3600)
        assertEquals(2, requireNotNull(scheduleAgenda(church, state, laterToday)).days.size)
        assertNull(scheduleAgenda(church, state.copy(publication = publication.copy(events = emptyList())), now))
    }

    @Test fun staleTapRejectsChangedKindMetadataEditionOrOwnerEvenAtSameDateTime() {
        val displayed = requireNotNull(scheduleAgenda(church, state, now))
        assertTrue(displayed.permits(scheduleAgenda(church, state, now), first.service()))
        val replacements = listOf(publication.copy(fetchedAt = now.plusSeconds(1).toString()),
            publication.copy(events = listOf(first.copy(kind = "liturgy"), second)),
            publication.copy(knownPartial = true), publication.copy(yearInferred = true))
        replacements.forEach { assertFalse(displayed.permits(scheduleAgenda(church, state.copy(publication = it), now), first.service())) }
        assertFalse(displayed.permits(scheduleAgenda(church.copy(address = "Новый адрес"), state, now), first.service()))
        assertFalse(displayed.permits(scheduleAgenda(church, state.copy(status = InternetScheduleStatus.UNAVAILABLE), now), first.service()))
        assertFalse(displayed.sameEdition(scheduleAgenda(church, state, now.plusSeconds(86400))))
        assertFalse(displayed.permits(null, first.service()))
        assertFalse(displayed.permits(displayed, first.copy(title = "Литургия").service()))
    }

    @Test fun retainedEditionIsDetachedFromMutableInputAndKeysAreSaveableDistinctStrings() {
        val events = publication.events.toMutableList()
        val displayed = requireNotNull(scheduleAgenda(church, state.copy(publication = publication.copy(events = events)), now))
        events.clear()
        assertEquals(2, displayed.publication.events.size)
        assertNotEquals(agendaServiceTag(first.service()), agendaServiceTag(first.copy(kind = "x", title = "service_block_" + first.title).service()))
    }
}
