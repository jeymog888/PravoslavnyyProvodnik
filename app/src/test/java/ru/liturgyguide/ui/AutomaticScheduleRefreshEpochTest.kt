package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.schedule.InternetScheduleState

class AutomaticScheduleRefreshEpochTest {
    @Test fun revocationBeforePresentationInvalidatesTheCapturedPendingRight() {
        val controller = AutomaticScheduleSourceController(); val owner = Any()
        val old = controller.beginEpoch(owner, 0, "2026-11-14")
        assertTrue(controller.owns(old)); assertTrue(controller.matches(owner, 0, "2026-11-14"))
        controller.revoke()
        assertFalse(old.active); assertFalse(controller.owns(old))
        assertFalse(controller.matches(owner, 0, "2026-11-14"))
        val late = AutomaticScheduleSourcePresentation(owner, Any(), 0, "MAIN", "2026-11-14",
            InternetScheduleState("osm:way:42"), old)
        assertTrue(runCatching { controller.install(late) }.isFailure)
    }

    @Test fun aNewEpochCannotMakeALateOldDeliveryCurrentAgain() {
        val controller = AutomaticScheduleSourceController(); val owner = Any()
        val old = controller.beginEpoch(owner, 0, "2026-11-14")
        controller.revoke()
        val fresh = controller.beginEpoch(owner, 1, "2026-11-14")
        assertFalse(controller.owns(old)); assertTrue(controller.owns(fresh)); assertNotSame(old, fresh)
        assertFalse(controller.matches(owner, 0, "2026-11-14"))
        assertTrue(controller.matches(owner, 1, "2026-11-14"))
    }

    @Test fun aViewingTransferDoesNotNeedToCreateAnotherEpoch() {
        val controller = AutomaticScheduleSourceController(); val owner = Any()
        val epoch = controller.beginEpoch(owner, 0, "2026-11-14")
        assertTrue(controller.matches(owner, 0, "2026-11-14"))
        assertTrue(controller.owns(epoch))
        assertFalse(controller.matches(Any(), 0, "2026-11-14"))
        assertFalse(controller.matches(owner, 0, "2026-11-15"))
        assertFalse(controller.matches(owner, 1, "2026-11-14"))
        assertTrue(epoch.active)
    }

    @Test fun presentationRevocationAlsoInvalidatesItsOriginalPendingEpoch() {
        val controller = AutomaticScheduleSourceController(); val owner = Any()
        val epoch = controller.beginEpoch(owner, 0, "2026-11-14")
        val view = AutomaticScheduleSourcePresentation(owner, Any(), 0, "MAIN", "2026-11-14",
            InternetScheduleState("osm:way:42"), epoch)
        controller.install(view); view.revoke()
        assertFalse(epoch.active); assertFalse(controller.owns(epoch)); assertTrue(view.revoked)
    }
}
