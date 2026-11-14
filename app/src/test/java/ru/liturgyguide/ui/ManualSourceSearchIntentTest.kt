package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.schedule.*
import java.time.Instant

class ManualSourceSearchIntentTest {
    private val f = ContactWiringFixtures
    private val day = "2026-11-14"
    private fun owner() = ManualScheduleSearchOwner(ScheduleEntityIdentity.fingerprint(f.selected()), day)
    private fun map() = f.mapCandidate().copy(provider = OfficialMapCandidateProvider.PROVIDER)
    private fun state() = InternetScheduleState(f.selected().id, sourceCandidates = listOf(map()),
        candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(f.selected()))

    @Test fun oneExplicitClickIsConsumedOnceAndInvalidAttemptsCannotReplayLater() {
        val owner = owner(); val first = ManualScheduleSearchIntent(owner, 1, "MAIN")
        assertTrue(first.consume(owner, 1, day, "MAIN")); assertFalse(first.consume(owner, 1, day, "MAIN"))
        val stale = ManualScheduleSearchIntent(owner, 2, "CHURCH")
        assertFalse(stale.consume(owner, 2, day, "SETTINGS")); assertFalse(stale.consume(owner, 2, day, "CHURCH"))
    }

    @Test fun recreationIdentityDaySequenceAndDisposedOwnersNeverInheritTheClick() {
        val owner = owner()
        assertFalse(ManualScheduleSearchIntent(owner, 1, "MAIN").consume(owner(), 1, day, "MAIN"))
        assertFalse(ManualScheduleSearchIntent(owner, 1, "MAIN").consume(owner, 2, day, "MAIN"))
        assertFalse(ManualScheduleSearchIntent(owner, 1, "MAIN").consume(owner, 1, "2026-11-15", "MAIN"))
        owner.invalidate()
        assertFalse(ManualScheduleSearchIntent(owner, 1, "MAIN").consume(owner, 1, day, "MAIN"))
    }

    @Test fun returningToTheSameScreenCannotReviveACallbackFromThePreviousSurface() {
        val owner = owner(); val firstSurface = Any(); val reopenedSurface = Any()
        val old = ManualScheduleSearchIntent(owner, 1, "CHURCH", firstSurface)
        assertFalse(old.consume(owner, 1, day, "CHURCH", reopenedSurface))
        assertFalse(old.consume(owner, 1, day, "CHURCH", firstSurface))
        assertTrue(ManualScheduleSearchIntent(owner, 2, "CHURCH", reopenedSurface)
            .consume(owner, 2, day, "CHURCH", reopenedSurface))
    }

    @Test fun eligibleManualInformationNeverIncludesConfiguredCustomPublicationOrWithdrawalStates() {
        val parish = f.selected(); val state = state()
        assertTrue(permitsOtherSourceSearch(parish, state))
        assertTrue(permitsOtherSourceSearch(parish, state.copy(sourceCandidates = listOf(map().copy(outcome = ScheduleIdentityOutcome.CONFLICT)),
            sourceNeedsVerification = true, publicationRejected = true, sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT)))
        assertFalse(permitsOtherSourceSearch(parish.copy(website = "https://configured.example/"), state))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(configuredWebsite = "https://configured.example/")))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(customSource = "https://manual.example/")))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(sourceHasWithdrawals = true)))
        val publication = SchedulePublication(parish.id, parish.timezone, "https://source.example/", "https://source.example/",
            Instant.parse("2026-11-13T00:00:00Z").toString(), listOf(InternetService("2026-11-13", "08:00", "Литургия", "liturgy")))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(publication = publication)))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(sourceCandidates = emptyList())))
        assertFalse(permitsOtherSourceSearch(parish, state.copy(sourceCandidates = listOf(map().copy(verificationUnavailable = true)))))
        assertFalse(permitsOtherSourceSearch(parish.copy(sourceUrl = "https://unrelated.example/"), state))
    }

    @Test fun displayedScheduleProvenanceDoesNotHideAnOwnOfferWhenTheExactMapIdentityIsProvided() = kotlinx.coroutines.runBlocking<Unit> {
        val parish = f.selected(); val own = f.fresh().candidates.single()
        val failed = state().copy(website = "http://www.hram-elisaveta.ru/", sourceUrl = "http://www.hram-elisaveta.ru/",
            publicationRejected = true, sourceCandidates = listOf(map(), own))
        val displayed = parish.withInternetSchedule(failed, f.now)
        assertNotEquals(parish.sourceUrl, displayed.sourceUrl)
        assertFalse(own.validFor(displayed)); assertTrue(own.validFor(parish))
        val review = requireNotNull(scheduleReviewParish(displayed, parish))
        assertEquals(parish, review)
        assertTrue(scheduleCandidateGroups(review, failed).any { it.confirmationCandidate?.id == own.id })
        assertTrue(hasReviewableScheduleSources(review, failed))
        assertEquals(failed.sourceUrl, displayed.sourceUrl)
    }

    @Test fun anotherAddressPointNameOrIdCannotSupplyTheReviewIdentity() {
        val parish = f.selected(); val displayed = parish.copy(sourceUrl = "https://schedule.example/")
        for (other in listOf(parish.copy(id = "osm:way:42"), parish.copy(address = "Иная улица, 1"),
            parish.copy(latitude = requireNotNull(parish.latitude) + .001), parish.copy(name = "Церковь Казанской иконы"))) {
            assertSame(displayed, scheduleReviewParish(displayed, other))
        }
        assertSame(displayed, scheduleReviewParish(displayed, null)); assertNull(scheduleReviewParish(null, parish))
    }
}
