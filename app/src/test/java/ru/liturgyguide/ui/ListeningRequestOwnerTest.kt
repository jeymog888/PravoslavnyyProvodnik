package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test

class ListeningRequestOwnerTest {
    @Test fun `permission already granted starts once and a new explicit request forwards unchecked`() {
        val h = Harness()
        assertNull(h.owner.request(hasPermission = true))
        assertEquals(listOf(false), h.starts)
        repeat(2) { h.owner.foregroundChanged() }
        assertEquals(listOf(false), h.starts)
        val boundary = h.events.size
        assertNull(h.owner.request(hasPermission = true, unchecked = true))
        assertEquals(listOf(false, true), h.starts)
        assertEquals("The previous capture is canceled before its replacement starts",
            listOf("cancel", "start:true"), h.events.drop(boundary))
        assertEquals(0, h.denials)
    }

    @Test fun `a request outside the accepted foreground route neither starts nor requests permission`() {
        val h = Harness()
        h.current = false
        assertNull(h.owner.request(hasPermission = false, unchecked = true))
        h.current = true
        h.foreground = false
        assertNull(h.owner.request(hasPermission = true))
        assertNull(h.owner.request(hasPermission = false))
        assertTrue(h.events.isEmpty())
        assertEquals(0, h.denials)
    }

    @Test fun `its own permission overlay may pause then grant and resume the same request exactly once`() {
        val h = Harness()
        val token = requireNotNull(h.owner.request(hasPermission = false, unchecked = true))
        val cancels = h.cancels
        h.foreground = false
        h.owner.foregroundChanged()
        assertEquals("A pending permission dialog is not an active capture", cancels, h.cancels)
        h.owner.permissionResult(token, granted = true)
        assertTrue(h.starts.isEmpty())
        h.foreground = true
        h.owner.foregroundChanged()
        assertEquals(listOf(true), h.starts)
        h.owner.permissionResult(token, granted = true)
        h.owner.permissionResult(token, granted = false)
        h.owner.foregroundChanged()
        assertEquals(listOf(true), h.starts)
        assertEquals(0, h.denials)
    }

    @Test fun `stop route loss and disposal invalidate late grants including the ON_STOP stop path`() {
        val invalidations: List<Pair<String, (Harness) -> Unit>> = listOf(
            "Stop or lifecycle ON_STOP" to { h: Harness -> h.owner.stop() },
            "Route replacement" to { h: Harness -> h.current = false; h.owner.foregroundChanged() },
            "Disposal" to { h: Harness -> h.owner.dispose() }
        )
        invalidations.forEach { (reason, invalidate) ->
            val h = Harness()
            val token = requireNotNull(h.owner.request(hasPermission = false))
            h.foreground = false
            h.owner.foregroundChanged()
            h.owner.permissionResult(token, granted = true)
            assertTrue(h.starts.isEmpty())
            invalidate(h)
            val cancels = h.cancels
            h.owner.permissionResult(token, granted = true)
            h.owner.permissionResult(token, granted = false)
            h.current = true
            h.foreground = true
            h.owner.foregroundChanged()
            assertTrue("$reason must discard even a previously granted pending request", h.starts.isEmpty())
            assertEquals("Late permission results cannot cancel anything else: $reason", cancels, h.cancels)
            assertEquals(reason, 0, h.denials)
            if (reason == "Disposal") {
                h.owner.dispose()
                assertNull(h.owner.request(hasPermission = true, unchecked = true))
                assertEquals(cancels, h.cancels)
                assertTrue(h.starts.isEmpty())
            }
        }
    }

    @Test fun `old grants and denials cannot start or cancel a replacement permission request`() {
        val h = Harness()
        val old = requireNotNull(h.owner.request(hasPermission = false, unchecked = true))
        val current = requireNotNull(h.owner.request(hasPermission = false, unchecked = false))
        assertNotEquals(old, current)
        val cancels = h.cancels
        h.owner.permissionResult(old, granted = true)
        h.owner.permissionResult(old, granted = false)
        h.owner.foregroundChanged()
        assertTrue(h.starts.isEmpty())
        assertEquals(cancels, h.cancels)
        assertEquals(0, h.denials)
        h.owner.permissionResult(current, granted = true)
        assertEquals(listOf(false), h.starts)
        h.owner.permissionResult(old, granted = true)
        h.owner.permissionResult(old, granted = false)
        assertEquals(listOf(false), h.starts)
        assertEquals(cancels, h.cancels)
        assertEquals(0, h.denials)
    }

    @Test fun `an active capture is canceled on foreground loss and gain never restarts it`() {
        val h = Harness()
        h.owner.request(hasPermission = true)
        val cancels = h.cancels
        h.foreground = false
        h.owner.foregroundChanged()
        assertEquals(cancels + 1, h.cancels)
        h.owner.foregroundChanged()
        assertEquals(cancels + 1, h.cancels)
        h.foreground = true
        h.owner.foregroundChanged()
        assertEquals(listOf(false), h.starts)
        h.owner.request(hasPermission = true, unchecked = true)
        assertEquals(listOf(false, true), h.starts)
    }

    @Test fun `denial is reported once and a later grant cannot revive the denied request`() {
        val h = Harness()
        val token = requireNotNull(h.owner.request(hasPermission = false))
        h.owner.permissionResult(token, granted = false)
        assertEquals(1, h.denials)
        h.owner.permissionResult(token, granted = false)
        h.owner.permissionResult(token, granted = true)
        h.owner.foregroundChanged()
        assertEquals(1, h.denials)
        assertTrue(h.starts.isEmpty())
        h.owner.request(hasPermission = true)
        assertEquals(listOf(false), h.starts)
    }

    @Test fun `interrupt cleanup belongs to its binding even when the same owner binds again`() {
        val interrupt = ListeningInterrupt()
        val owner = Any()
        var oldCalls = 0
        var currentCalls = 0
        var currentStops = 0
        val oldUnbind = interrupt.bind(owner, callback = { oldCalls++ })
        val currentUnbind = interrupt.bind(owner, callback = { currentCalls++ }, onStop = { currentStops++ })
        oldUnbind()
        interrupt.foregroundChanged()
        assertEquals(0, oldCalls)
        assertEquals(1, currentCalls)
        interrupt.stop()
        assertEquals("Leaving the route must call cancellation, not just a foreground notification", 1, currentStops)
        assertEquals(1, currentCalls)
        val replacement = Any()
        var replacementCalls = 0
        val replacementUnbind = interrupt.bind(replacement, callback = { replacementCalls++ })
        currentUnbind()
        interrupt.foregroundChanged()
        assertEquals(1, currentCalls)
        assertEquals(1, replacementCalls)
        interrupt.stop()
        assertEquals("Two-argument bindings retain their explicit default stop callback", 2, replacementCalls)
        assertEquals(1, currentStops)
        replacementUnbind()
        interrupt.foregroundChanged()
        interrupt.stop()
        assertEquals(2, replacementCalls)
    }

    private class Harness {
        var current = true
        var foreground = true
        var cancels = 0
        var denials = 0
        val starts = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        val owner = ListeningRequestOwner(
            isCurrent = { current }, isForeground = { foreground },
            start = { starts += it; events += "start:$it" },
            cancel = { cancels++; events += "cancel" }, denied = { denials++ }
        )
    }
}
