package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import java.time.Instant
import java.time.LocalDate

class ChurchReadingEntryTest {
    private val date = LocalDate.of(2026, 12, 31)
    private val now = Instant.parse("2026-12-31T06:00:00Z")
    private val church = Parish("quiet", "Троицкий храм", "Троицкий храм", "Москва", "Сретенка, 27", "", "",
        "Europe/Moscow", "", "", "2026-12-31", "2027-01-01", emptyList(), "", "")
    private fun service(title: String = "Часы. Литургия", day: LocalDate = date, time: String = "09:00") =
        ParishService(day.toString(), time, title, "service_block")
    private fun decision(featured: ParishService? = service(), parish: Parish? = church,
        personal: Boolean = false, read: Boolean = true, chooser: Boolean = true, open: Boolean = true,
        full: Boolean = false, selected: LocalDate = date, retainedChooser: Boolean? = if (full) true else null) =
        churchReadingEntry(parish, selected, featured, read && !personal,
            churchReadingChooser(featured, personal, read, chooser, open, retainedChooser), chooser, open, full)
    private fun cached(event: ParishService = service()) = InternetScheduleState(church.id, InternetScheduleStatus.READY,
        sourceUrl = "https://parish.example/schedule", publication = SchedulePublication(church.id, church.timezone,
            "https://parish.example", "https://parish.example/schedule", now.toString(),
            listOf(InternetService(event.date, event.time, event.title, event.kind))))
    private fun arrivalDecision(state: InternetScheduleState, parish: Parish = church, selected: LocalDate = date) =
        decision(arrivalSchedule(parish, selected, now, state).featured, parish = parish, selected = selected)

    @Test fun existingRoutesUseOneCompactSecondaryEntryAndPreserveThePublishedComposition() {
        for (title in listOf("Литургия", "Часы. Литургия", "Часы. Литургия. Панихида", "Вечерня. Утреня",
            "Всенощное бдение", "Литургия. Панихида", "Вечерня с акафистом иконе Богородицы Всецарица")) {
            val event = service(title)
            assertNotNull(title, ServiceBookSelection.resolve(event, church))
            assertEquals(title, ChurchReadingEntry.COMPACT, decision(event))
        }
        val route = requireNotNull(ServiceBookSelection.resolve(service(), church))
        assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY),
            requireNotNull(route.journeyContext).parts.map { it.worship })
        assertEquals(service().title, route.journeyContext!!.title)
        assertTrue(route.journeyContext!!.parts.all { it.date == date.toString() })
    }

    @Test fun unspecifiedEveningUsesTheChoiceProbeBeforeResolveAndRetainsCivilOwnership() {
        val event = service("Вечернее богослужение", time = "17:00")
        assertNull(ServiceBookSelection.resolve(event, church))
        val probe = requireNotNull(ServiceBookSelection.eveningTexts(event, church, WorshipKind.VESPERS))
        assertEquals(date.plusDays(1).toString(), probe.activeKey.date)
        assertEquals(date.toString(), probe.journeyContext!!.civilDate)
        assertEquals(ChurchReadingEntry.COMPACT, decision(event))
        // The UI chooser receives this civil date; forCivilDate owns the one rollover.
        assertEquals(probe.activeKey, ServiceBookSelection.forCivilDate(date, WorshipKind.VESPERS, church))
    }

    @Test fun unsupportedMalformedAndOtherCivilDaysKeepTheFullIndependentEntry() {
        for (event in listOf(service("Молебен"), service("Царские часы"), service(""),
            service().copy(date = "bad-date"), service().copy(time = "24:00"), service(day = date.plusDays(1))))
            assertEquals(event.toString(), ChurchReadingEntry.FULL, decision(event))
        assertEquals(ChurchReadingEntry.FULL, decision(null))
        val nextOnly = arrivalSchedule(church, date, now, cached(service(day = date.plusDays(1))))
        assertNull(nextOnly.featured)
        assertNotNull(nextOnly.next)
        assertEquals(ChurchReadingEntry.FULL, decision(nextOnly.featured))
        assertEquals(ChurchReadingEntry.NONE, decision(parish = null))
    }

    @Test fun callbackNegativesAndPersonalEntriesKeepTheirExistingReadingPaths() {
        assertEquals(ChurchReadingEntry.FULL, decision(chooser = false))
        assertEquals(ChurchReadingEntry.FULL, decision(open = false))
        assertEquals(ChurchReadingEntry.NONE, decision(personal = true))
        assertEquals(ChurchReadingEntry.NONE, decision(personal = true, chooser = false))
        assertEquals(ChurchReadingEntry.NONE, decision(read = false))
        assertEquals(ChurchReadingEntry.FULL, decision(null, read = false))
        assertEquals(ChurchReadingEntry.FULL, decision(open = false, read = false))
        assertEquals(ChurchReadingEntry.FULL, decision(service("Молебен"), read = false))
        assertEquals(ChurchReadingEntry.FULL, decision(null, personal = true))
        assertEquals(ChurchReadingEntry.NONE, decision(null, personal = true, chooser = false))
        assertEquals(ChurchReadingEntry.NONE, decision(null, read = false, chooser = false))
        assertEquals(ChurchReadingEntry.NONE, decision(read = false, chooser = false, open = false))
    }

    @Test fun validCachedPublicationRemainsCompactDuringLoadingAndRefreshFailure() {
        val ready = cached()
        for (state in listOf(ready, ready.copy(loading = true), ready.copy(status = InternetScheduleStatus.UNAVAILABLE),
            ready.copy(status = InternetScheduleStatus.UNSUPPORTED), ready.copy(status = InternetScheduleStatus.IDLE))) {
            val arrival = arrivalSchedule(church, date, now, state)
            assertTrue(arrival.saved)
            assertNotNull(arrival.featured)
            assertEquals("Cache is usable independently of refresh enum: ${state.status}", ChurchReadingEntry.COMPACT,
                arrivalDecision(state))
        }
        for (state in listOf(InternetScheduleState(church.id, loading = true),
            InternetScheduleState(church.id, InternetScheduleStatus.UNAVAILABLE)))
            assertEquals(ChurchReadingEntry.FULL, arrivalDecision(state))
    }

    @Test fun currentArrivalGuardsRejectForeignUnverifiedStaleParserAndChangedSources() {
        val ready = cached()
        val publication = requireNotNull(ready.publication)
        for (state in listOf(ready.copy(parishId = "other"), ready.copy(sourceNeedsVerification = true),
            ready.copy(publicationRejected = true), ready.copy(sourceUrl = "https://other.example/schedule"),
            ready.copy(publication = publication.copy(parishId = "other")),
            ready.copy(publication = publication.copy(timezone = "Asia/Vladivostok")),
            ready.copy(publication = publication.copy(parserRevision = 0)))) {
            assertNull(arrivalSchedule(church, date, now, state).featured)
            assertEquals(ChurchReadingEntry.FULL, arrivalDecision(state))
        }
        assertEquals(ChurchReadingEntry.FULL, arrivalDecision(ready, selected = date.plusDays(1)))
        assertEquals(ChurchReadingEntry.FULL, arrivalDecision(ready, parish = church.copy(id = "other")))
    }

    @Test fun aRetainedFullFormCannotOscillateOrDiscardItsOnlyChooser() {
        assertEquals(ChurchReadingEntry.FULL, decision(full = true))
        assertEquals(ChurchReadingEntry.FULL, decision(service("Молебен"), full = true))
        assertEquals(ChurchReadingEntry.FULL, decision(read = false, full = true))
        assertEquals(ChurchReadingEntry.FULL, decision(personal = true, full = true))
        assertEquals(ChurchReadingEntry.NONE, decision(read = false, chooser = false, full = true))
        // The source is evaluated again; retaining FULL never gives an old publication authority.
        assertEquals(ChurchReadingEntry.FULL, decision(null, full = true))
        assertEquals(ChurchReadingEntry.COMPACT, decision(full = false))
    }


    @Test fun retainedControls00CannotCreateAnEmptyCardOrNewChooserWhenPersonalReadingAppears() {
        val unsupported = service("Молебен")
        assertEquals(ChurchReadingEntry.FULL, decision(unsupported))
        assertFalse(churchReadingChooser(unsupported, false, true, true, true, retainedChooser = false))
        // Same owner, same callbacks, saved controls00: only the independent personal action remains.
        assertEquals(ChurchReadingEntry.NONE, decision(unsupported, personal = true, full = true, retainedChooser = false))
        assertFalse(churchReadingChooser(unsupported, true, true, true, true, retainedChooser = false))
        // Removing personal reading exposes the current standalone read with the retained full form.
        assertEquals(ChurchReadingEntry.FULL, decision(unsupported, full = true, retainedChooser = false))
        // A real retained chooser stays separate, and current callback loss keeps a sole usable chooser.
        assertEquals(ChurchReadingEntry.FULL, decision(unsupported, personal = true, full = true, retainedChooser = true))
        assertEquals(ChurchReadingEntry.FULL, decision(unsupported, read = false, full = true, retainedChooser = false))
        assertTrue(churchReadingChooser(unsupported, false, false, true, true, retainedChooser = false))
        assertEquals(ChurchReadingEntry.NONE, decision(unsupported, read = false, chooser = false, full = true, retainedChooser = false))
    }

    @Test fun hoursCopyDefinesTheHoursAndKeepsTheTwoExistingScopeLimits() {
        assertEquals("Часы: псалмы и молитвы перед литургией. Порядок в храме может отличаться",
            ServiceBookSelection.hoursLiturgyScope("Часы. Литургия"))
        assertEquals("Часы: псалмы и молитвы перед литургией. Остальные службы из этой записи в набор не входят",
            ServiceBookSelection.hoursLiturgyScope("Часы. Литургия. Панихида"))
        assertEquals(ServiceBookSelection.hoursLiturgyScope("Часы. Литургия"),
            ServiceBookSelection.readingScope("Часы. Литургия"))
        assertNull(ServiceBookSelection.readingScope("Молебен"))
    }
}
