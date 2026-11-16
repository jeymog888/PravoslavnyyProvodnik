package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate

class ArrivalYearlessScheduleTest {
    private val now = Instant.parse("2026-11-16T06:00:00Z")
    private val source = "https://parish.example/raspisanie"
    private val church = Parish("church", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        source, "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private fun state(partial: Boolean = true) = InternetScheduleState(church.id, InternetScheduleStatus.READY,
        website = "https://parish.example/", sourceUrl = source, checkedAt = now.toString(),
        publication = SchedulePublication(church.id, church.timezone, "https://parish.example/", source, now.toString(),
            listOf(InternetService("2026-11-18", "08:00", "Утреня. Литургия. Молебен", "service_block")),
            yearInferred = true, knownPartial = partial))

    @Test fun emptySelectedDayKeepsBothDiagnosticsAndASeparateFutureOccurrence() {
        val selected = LocalDate.of(2026, 11, 16)
        val schedule = arrivalSchedule(church, selected, now, state())
        assertTrue(schedule.entries.isEmpty())
        assertNull(schedule.featured)
        assertTrue(schedule.yearInferred && schedule.knownPartial)
        val offer = requireNotNull(schedule.next)
        assertEquals("2026-11-18", offer.service.date)
        assertTrue(offer.yearInferred && offer.knownPartial)
        assertEquals(source, offer.sourceUrl)
        assertEquals(LocalDate.of(2026, 11, 16), selected)
    }

    @Test fun emptyDayWithoutAnotherOfferStillCarriesTheInferredYearAndPartialSource() {
        for (partial in listOf(true, false)) {
            val result = arrivalSchedule(church, LocalDate.of(2026, 11, 30), now, state(partial))
            assertNull(result.featured)
            assertNull(result.next)
            assertTrue(result.yearInferred)
            assertEquals(partial, result.knownPartial)
            if (partial) assertEquals(source, result.sourceUrl)
        }
    }

    @Test fun rejectedUnverifiedOrForeignPublicationCannotGiveAnEmptyDateNewAuthority() {
        val original = state()
        val variants = listOf(original.copy(parishId = "other"), original.copy(publicationRejected = true),
            original.copy(sourceNeedsVerification = true), original.copy(sourceUrl = "https://wrong.example/schedule/"),
            original.copy(publication = original.publication!!.copy(parishId = "other")))
        for (state in variants) {
            val result = arrivalSchedule(church, LocalDate.of(2026, 11, 30), now, state)
            assertFalse(result.yearInferred)
            assertFalse(result.knownPartial)
            assertNull(result.next)
        }
    }

    @Test fun anotherPublishedSourceDoesNotInheritTheCachedInferredYear() {
        val published = church.copy(sourceUrl = "https://bundle.example/schedule/",
            schedule = listOf(ParishService("2026-11-18", "09:00", "Литургия", "liturgy")))
        for (date in listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 30))) {
            val result = arrivalSchedule(published, date, now, state())
            assertFalse(result.yearInferred)
            assertFalse(result.knownPartial)
        }
    }
}
