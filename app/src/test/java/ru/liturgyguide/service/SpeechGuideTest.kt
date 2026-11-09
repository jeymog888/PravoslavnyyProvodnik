package ru.liturgyguide.service

import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test

class SpeechGuideTest {
    @Test fun `availability refresh neither checks permission nor creates a recognizer and old APIs cannot capture`() {
        for (api in listOf(26, 30, 31, 32, 33, 35)) {
            val h = Harness(api)
            assertEquals(if (api < 31) SpeechStatus.UNAVAILABLE else SpeechStatus.IDLE, h.guide.initialState.status)
            h.guide.refreshAvailability()
            assertEquals(0, h.backend.permissionChecks)
            assertTrue(h.backend.attempts.isEmpty())
            if (api < 31) {
                h.guide.start(skipSupportCheck = true)
                assertEquals(SpeechStatus.UNAVAILABLE, h.guide.state.status)
                assertEquals(0, h.backend.permissionChecks)
                assertTrue(h.backend.attempts.isEmpty())
            }
        }
        val h = Harness()
        h.backend.available = false
        h.guide.refreshAvailability()
        assertEquals(SpeechStatus.UNAVAILABLE, h.guide.state.status)
        h.backend.available = true
        h.guide.refreshAvailability()
        assertEquals(SpeechStatus.IDLE, h.guide.state.status)
        assertTrue(h.backend.attempts.isEmpty())
    }

    @Test fun `denied permission cannot create either a checking or an unchecked attempt`() {
        val h = Harness()
        h.backend.permission = false
        for (skip in listOf(false, true)) {
            h.guide.start(skip)
            assertEquals(SpeechStatus.ERROR, h.guide.state.status)
            assertTrue(h.guide.state.message.contains("микрофону"))
            assertFalse(h.guide.state.canTryWithoutSupport)
            assertTrue(h.backend.attempts.isEmpty())
        }
        assertEquals(2, h.backend.permissionChecks)
    }

    @Test fun `API31 and32 make only one bounded direct attempt without support API`() {
        for (api in listOf(31, 32)) {
            val h = Harness(api)
            h.guide.start()
            val a = h.last
            assertEquals(0, a.supportChecks)
            assertEquals(1, a.starts)
            h.guide.start()
            assertEquals(1, h.backend.attempts.size)
            h.clock.advance(14_999)
            assertEquals(SpeechStatus.STARTING, h.guide.state.status)
            a.events.ready()
            h.clock.advance(1)
            assertEquals(SpeechStatus.PROCESSING, h.guide.state.status)
            assertEquals(1, a.finishes)
            a.events.ready()
            h.guide.finishListening()
            a.events.end()
            h.clock.advance(4_999)
            assertEquals(SpeechStatus.PROCESSING, h.guide.state.status)
            h.clock.advance(1)
            assertEquals(SpeechStatus.ERROR, h.guide.state.status)
            assertEquals(1, a.releases)
            assertEquals(1, h.backend.attempts.size)
        }
    }

    @Test fun `only an installed exact Russian locale starts capture and final releases before persistent result`() {
        for (tag in listOf("ru", "ru-RU", "RU-ru", "ru_RU")) {
            val h = Harness()
            h.guide.start()
            val a = h.last
            val supportDeadline = h.clock.jobs.single()
            assertEquals(SpeechStatus.CHECKING, h.guide.state.status)
            assertTrue(h.guide.state.isActive)
            assertEquals(0, a.starts)
            a.support(SpeechSupport(listOf(tag), listOf("ru-RU"), listOf("ru-RU")))
            assertEquals(SpeechStatus.STARTING, h.guide.state.status)
            assertEquals(1, a.starts)
            supportDeadline.action() // An already-queued, canceled deadline cannot cancel capture.
            a.events.ready()
            a.events.partial("Господи")
            assertEquals(SpeechStatus.LISTENING, h.guide.state.status)
            assertFalse(h.states.any { it.status == SpeechStatus.RESULT })
            a.events.result("  Господи помилуй  ")
            val result = h.guide.state
            assertEquals(SpeechStatus.RESULT, result.status)
            assertEquals("Господи помилуй", result.transcript)
            assertEquals(1, a.releases)
            assertFalse(result.isActive)
            h.guide.refreshAvailability()
            h.guide.start()
            a.events.error(SpeechRecognizer.ERROR_AUDIO)
            a.events.result("поздний результат")
            h.clock.advance(100_000)
            assertEquals(result, h.guide.state)
            assertEquals(1, h.backend.attempts.size)
            h.guide.stop()
            assertEquals(SpeechStatus.IDLE, h.guide.state.status)
            assertEquals("", h.guide.state.transcript)
        }
    }

    @Test fun `pending missing and unsupported Russian models have distinct honest outcomes`() {
        val cases = listOf(
            SpeechSupport(emptyList(), listOf("ru-RU"), listOf("ru")) to "ожидает загрузки",
            SpeechSupport(emptyList(), listOf("ru-RU"), emptyList()) to "не установлена",
            SpeechSupport(listOf("en-US"), listOf("en-US"), emptyList()) to "не поддерживает"
        )
        for ((support, message) in cases) {
            val h = Harness(); h.guide.start(); h.last.support(support)
            assertEquals(SpeechStatus.UNAVAILABLE, h.guide.state.status)
            assertTrue(h.guide.state.message.contains(message))
            assertFalse(h.guide.state.canTryWithoutSupport)
            assertEquals(0, h.last.starts)
            assertEquals(1, h.last.releases)
        }
        // Locale.forLanguageTag alone truncates malformed suffixes; none is proof of installed ru-RU.
        for (tag in listOf("bru", "en-RU", "russian", "ru-??", "ru-RU-??", "ru-Cyrl-RU")) {
            val h = Harness(); h.guide.start(); h.last.support(SpeechSupport(listOf(tag), emptyList(), emptyList()))
            assertEquals(tag, SpeechStatus.UNAVAILABLE, h.guide.state.status)
            assertEquals(0, h.last.starts)
        }
    }

    @Test fun `unknown support permits only a subsequent explicit bounded attempt including after stop`() {
        val h = Harness(); h.guide.start()
        val old = h.last
        old.supportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        assertEquals(SpeechStatus.ERROR, h.guide.state.status)
        assertTrue(h.guide.state.canTryWithoutSupport)
        assertEquals(1, old.releases)
        assertEquals(0, old.starts)
        h.clock.advance(100_000)
        assertEquals(1, h.backend.attempts.size)
        h.guide.stop()
        assertFalse(h.guide.state.canTryWithoutSupport)
        h.guide.start(skipSupportCheck = true)
        val current = h.last
        assertEquals(0, current.supportChecks)
        assertEquals(1, current.starts)
        old.support(SpeechSupport(listOf("ru-RU"), emptyList(), emptyList()))
        assertEquals(0, old.starts)
        assertEquals(SpeechStatus.STARTING, h.guide.state.status)
        h.clock.advance(15_000)
        assertEquals(SpeechStatus.PROCESSING, h.guide.state.status)
        h.clock.advance(5_000)
        assertEquals(SpeechStatus.ERROR, h.guide.state.status)
        assertEquals(1, current.releases)
    }

    @Test fun `support deadline is five seconds and late success never starts a microphone`() {
        val h = Harness(); h.guide.start(); val a = h.last
        h.guide.finishListening()
        assertEquals(SpeechStatus.CHECKING, h.guide.state.status)
        h.clock.advance(4_999)
        assertEquals(SpeechStatus.CHECKING, h.guide.state.status)
        h.clock.advance(1)
        assertTrue(h.guide.state.canTryWithoutSupport)
        val stopped = h.guide.state
        a.support(SpeechSupport(listOf("ru-RU"), emptyList(), emptyList()))
        a.events.ready(); a.events.result("фраза")
        assertEquals(stopped, h.guide.state)
        assertEquals(0, a.starts)
        assertEquals(1, a.releases)
    }

    @Test fun `cancel in every active phase rejects old callbacks and canceled deadlines without touching replacement`() {
        for (phase in listOf(SpeechStatus.CHECKING, SpeechStatus.STARTING, SpeechStatus.LISTENING, SpeechStatus.PROCESSING)) {
            val h = Harness(); h.guide.start(); val old = h.last
            if (phase != SpeechStatus.CHECKING) old.support(SpeechSupport(listOf("ru"), emptyList(), emptyList()))
            if (phase == SpeechStatus.LISTENING || phase == SpeechStatus.PROCESSING) old.events.ready()
            if (phase == SpeechStatus.PROCESSING) h.guide.finishListening()
            assertEquals(phase, h.guide.state.status)
            val oldJobs = h.clock.jobs.toList()
            h.guide.stop()
            assertEquals(1, old.releases)
            h.guide.start()
            val current = h.last
            val before = h.guide.state
            old.support(SpeechSupport(listOf("ru-RU"), emptyList(), emptyList()))
            old.supportError(SpeechRecognizer.ERROR_AUDIO)
            old.events.ready(); old.events.end(); old.events.partial("старое"); old.events.result("старое")
            old.events.error(SpeechRecognizer.ERROR_AUDIO)
            oldJobs.forEach { it.action() }
            assertEquals(before, h.guide.state)
            assertEquals(0, current.starts)
            assertEquals(0, current.releases)
            current.support(SpeechSupport(listOf("ru"), emptyList(), emptyList()))
            current.events.result("новое")
            assertEquals("новое", h.guide.state.transcript)
            val results = h.states.size
            h.guide.destroy()
            h.guide.stop(); h.guide.start(); h.guide.refreshAvailability()
            current.events.result("после dispose")
            h.clock.advance(100_000)
            assertEquals(results, h.states.size)
        }
    }

    @Test fun `natural end has one fixed final deadline and partial text cannot replace an empty final`() {
        val h = Harness(31); h.guide.start(); val a = h.last
        a.events.ready(); a.events.partial("частичная фраза")
        h.clock.advance(1_000); a.events.end()
        assertEquals(SpeechStatus.PROCESSING, h.guide.state.status)
        assertEquals(0, a.finishes)
        h.clock.advance(2_000); a.events.end(); a.events.ready(); h.guide.finishListening()
        h.clock.advance(2_999)
        assertEquals(SpeechStatus.PROCESSING, h.guide.state.status)
        h.clock.advance(1)
        assertEquals(SpeechStatus.ERROR, h.guide.state.status)
        assertEquals("", h.guide.state.transcript)
        assertFalse(h.states.any { it.status == SpeechStatus.RESULT })
        h.guide.stop(); h.guide.start()
        h.last.events.partial("еще одна частичная фраза")
        h.last.events.result("   ")
        assertEquals(SpeechStatus.ERROR, h.guide.state.status)
        assertEquals("", h.guide.state.transcript)
        assertFalse(h.states.any { it.status == SpeechStatus.RESULT })
    }

    @Test fun `disposing an active check or capture releases once and never publishes a later result`() {
        for (api in listOf(31, 33)) {
            val h = Harness(api); h.guide.start(); val a = h.last
            val notifications = h.states.size
            h.guide.destroy()
            assertEquals(1, a.releases)
            if (api == 33) a.support(SpeechSupport(listOf("ru"), emptyList(), emptyList()))
            a.events.ready(); a.events.result("поздняя фраза"); a.events.end()
            h.clock.jobs.forEach { it.action() }
            h.guide.destroy(); h.guide.start(); h.guide.stop(); h.guide.refreshAvailability()
            assertEquals(1, a.releases)
            assertEquals(notifications, h.states.size)
            assertEquals(1, h.backend.attempts.size)
        }
    }

    @Test fun `permission and device errors release owned attempts with specific reasons`() {
        for ((code, word) in listOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to "микрофону",
            SpeechRecognizer.ERROR_AUDIO to "микрофон", SpeechRecognizer.ERROR_RECOGNIZER_BUSY to "занято",
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to "модель", SpeechRecognizer.ERROR_NO_MATCH to "эхо")) {
            val h = Harness(31); h.guide.start(); h.last.events.error(code)
            assertEquals(SpeechStatus.ERROR, h.guide.state.status)
            assertTrue(h.guide.state.message.contains(word))
            assertFalse(h.guide.state.canTryWithoutSupport)
            assertEquals(1, h.last.releases)
        }
        val h = Harness(); h.guide.start(); h.last.supportError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertFalse(h.guide.state.canTryWithoutSupport)
        assertTrue(h.guide.state.message.contains("микрофону"))
        assertEquals(0, h.last.starts)
        assertEquals(1, h.last.releases)
    }

    @Test fun `synchronous final during stop and permission failure during start leave no later timeout`() {
        val h = Harness(31)
        h.backend.onCreate = { a -> a.onFinish = { a.events.result("синхронный итог") } }
        h.guide.start(); h.guide.finishListening()
        assertEquals(SpeechStatus.RESULT, h.guide.state.status)
        assertEquals(1, h.last.releases)
        h.clock.advance(100_000)
        assertEquals(SpeechStatus.RESULT, h.guide.state.status)
        h.guide.stop()
        h.backend.onCreate = { a -> a.onStart = { throw SecurityException("revoked") } }
        h.guide.start()
        assertEquals(SpeechStatus.ERROR, h.guide.state.status)
        assertTrue(h.guide.state.message.contains("микрофону"))
        assertEquals(1, h.last.releases)
        h.clock.advance(100_000)
        assertFalse(h.guide.state.isActive)
    }

    private class Harness(api: Int = 33) {
        val backend = FakeBackend(api)
        val clock = FakeClock()
        val states = mutableListOf<SpeechGuideState>()
        val guide = SpeechGuide(backend, clock, states::add)
        val last get() = backend.attempts.last()
    }
    private class FakeBackend(override val apiLevel: Int) : SpeechBackend {
        var available = true
        var permission = true
        var permissionChecks = 0
        val attempts = mutableListOf<FakeAttempt>()
        var onCreate: (FakeAttempt) -> Unit = {}
        override fun isAvailable() = available
        override fun hasPermission(): Boolean { permissionChecks++; return permission }
        override fun create(events: SpeechEvents) = FakeAttempt(events).also { attempts += it; onCreate(it) }
    }
    private class FakeAttempt(val events: SpeechEvents) : SpeechAttempt {
        var supportChecks = 0
        var starts = 0
        var finishes = 0
        var releases = 0
        lateinit var support: (SpeechSupport) -> Unit
        lateinit var supportError: (Int) -> Unit
        var onStart: () -> Unit = {}
        var onFinish: () -> Unit = {}
        override fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit) {
            supportChecks++; support = result; supportError = error
        }
        override fun start() { starts++; onStart() }
        override fun finish() { finishes++; onFinish() }
        override fun release() { releases++ }
    }
    private class FakeClock : SpeechScheduler {
        data class Job(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        val jobs = mutableListOf<Job>()
        override fun schedule(delayMillis: Long, action: () -> Unit): SpeechCancellation {
            val job = Job(now + delayMillis, action).also(jobs::add)
            return SpeechCancellation { job.canceled = true }
        }
        fun advance(delta: Long) {
            val end = now + delta
            while (true) {
                val job = jobs.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
                job.canceled = true
                now = job.at
                job.action()
            }
            now = end
        }
    }
}
