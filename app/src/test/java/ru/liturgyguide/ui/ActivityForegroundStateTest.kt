package ru.liturgyguide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityForegroundStateTest {
    @Test
    fun lateBindingPausesImmediatelyAndOldDisposalCannotRemoveTheReboundCallback() {
        val foreground = ActivityForegroundState()
        var pauses = 0
        val pause = { pauses++; Unit }
        val removeOld = foreground.bind(pause)
        assertEquals(1, pauses)
        val removeCurrent = foreground.bind(pause)
        assertEquals(2, pauses)
        removeOld()
        foreground.onTopResumedActivityChanged(true)
        assertEquals(2, pauses)
        foreground.onTopResumedActivityChanged(false)
        assertEquals(3, pauses)
        removeCurrent()
        foreground.onTopResumedActivityChanged(true)
        foreground.onTopResumedActivityChanged(false)
        assertEquals(3, pauses)
    }
}
