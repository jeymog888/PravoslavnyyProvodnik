package ru.liturgyguide.ui

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

class BookSearchAutofocusTest {
    private val opening = Any()
    private val window = Any()
    private val lifecycle = Any()
    private val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS)
    private fun attempt() = BookSearchFocusAttempt(opening, key, "edition-a", window, lifecycle)

    @Test fun onlyANewManualOpeningGetsOneAttemptAndRestorationIsSpentEvenBeforeItStarted() {
        val fresh = BookSearchFocusGrant.opening(true)
        assertTrue(fresh.take()); assertFalse(fresh.take())
        val notYetStarted = BookSearchFocusGrant.opening(true)
        assertFalse(BookSearchFocusGrant.restored().take())
        assertTrue("Restoration must not mutate another live opening", notYetStarted.take())
        assertFalse(BookSearchFocusGrant.opening(false).take())
        assertTrue("Only a different explicit opening gets another right", BookSearchFocusGrant.opening(true).take())
    }

    @Test fun everyProcessOwnerDimensionMustStillMatchIncludingTheActualWindowIdentity() {
        val a = attempt()
        assertTrue(a.matches(opening, key.copy(), "edition-a", window, lifecycle))
        assertFalse(a.matches(Any(), key, "edition-a", window, lifecycle))
        assertFalse(a.matches(opening, key.copy(date = "2026-11-14"), "edition-a", window, lifecycle))
        assertFalse(a.matches(opening, key.copy(worship = WorshipKind.VESPERS), "edition-a", window, lifecycle))
        assertFalse(a.matches(opening, key, "edition-b", window, lifecycle))
        assertFalse(a.matches(opening, key, "edition-a", Any(), lifecycle))
        assertFalse(a.matches(opening, key, "edition-a", window, Any()))
        assertFalse("A recreated window does not inherit another process token", attempt().matches(opening, key, "edition-a", Any(), lifecycle))
    }

    @Test fun revokingBeforeTheCoroutineBindsCancelsItAndNeverPermitsFocus() {
        val a = attempt(); a.revoke()
        val work = Job(); a.bind(work)
        assertTrue(work.isCancelled); assertFalse(a.valid); assertFalse(a.claim())
        assertFalse(a.matches(opening, key, "edition-a", window, lifecycle))
        a.revoke(); assertFalse(a.claim())
    }

    @Test fun manualEditMicrophoneAndLifecycleRevocationCancelAnAwaitingJobSynchronously() {
        for (reason in listOf("edit", "microphone", "pause", "stop", "dispose", "edition replacement")) {
            val a = attempt(); val work = Job(); a.bind(work)
            assertFalse(reason, work.isCancelled)
            a.revoke()
            assertTrue(reason, work.isCancelled); assertFalse(reason, a.claim()); assertFalse(reason, a.valid)
            assertFalse(reason, a.matches(opening, key, "edition-a", window, lifecycle))
        }
    }

    @Test fun theFocusActionCanBeClaimedOnceAndAReentrantRevocationAlsoStopsTheIme() {
        val a = attempt()
        assertTrue(a.claim()); assertFalse(a.claim())
        assertTrue("The same completed attempt may still guard its immediately following IME action", a.valid)
        a.revoke()
        assertFalse("A synchronous action caused by requestFocus may revoke before keyboard.show", a.valid)
        assertFalse(a.claim())
    }

    @Test fun finishingAnOldJobCannotDetachOrCancelADifferentAttempt() {
        val old = attempt(); val oldJob = Job(); old.bind(oldJob); old.unbind(oldJob)
        val new = attempt(); val newJob = Job(); new.bind(newJob)
        old.revoke()
        assertFalse(oldJob.isCancelled); assertFalse(newJob.isCancelled); assertTrue(new.valid)
        new.unbind(Job()); new.revoke()
        assertTrue("Only exact bound job identity may detach pending work", newJob.isCancelled)
    }
}
