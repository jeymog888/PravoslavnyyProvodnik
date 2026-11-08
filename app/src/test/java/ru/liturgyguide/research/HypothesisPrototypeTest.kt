package ru.liturgyguide.research

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.research.HypothesisPrototype.*
import ru.liturgyguide.service.PhraseMatcher
import ru.liturgyguide.service.StageAnchor
import ru.liturgyguide.service.StageMatcher

class HypothesisPrototypeTest {
    private val guide = StageMatcher(requireNotNull(javaClass.classLoader?.getResourceAsStream("liturgy_stages.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() })
    private val prototype = HypothesisPrototype(PhraseMatcher(guide.getAllStages().map { StageAnchor(it.id, it.listenFor) }))
    private val cherubic = "Иже Херувимы тайно образующе"
    private val creed = "Вѣ́рую во еди́наго Бо́га"

    private fun begin(state: State = State(), id: Long = 1, now: Long = 100, deadline: Long = 200): State =
        prototype.reduce(state, Event.Begin(id, deadline), now).also {
            assertEquals(Reason.STARTED, it.reason)
            assertFalse(it.automaticSyncEligible)
        }.state

    private fun finish(text: String, state: State = begin(), now: Long = 150): Result =
        prototype.reduce(state, Event.Final(requireNotNull(state.active).token, text), now)

    @Test fun `real guide anchors yield only a human review candidate`() {
        mapOf(cherubic to "cherubic_hymn", creed to "creed",
            "Благословенно царство отца" to "beginning", "Святая святым" to "clergy_communion").forEach { (text, stage) ->
            val result = finish(text)
            assertEquals(stage, result.emitted?.stageId)
            assertEquals(Token(0, 1), result.emitted?.token)
            assertFalse(result.emitted?.matchedPhrase.isNullOrBlank())
            assertNull(result.state.active)
            assertEquals(result.emitted, result.state.candidate)
            assertFalse(result.automaticSyncEligible)
        }
    }

    @Test fun `common repeated fragment and reordered phrases do not select a stage`() {
        listOf("Господи помилуй", "Аминь", "Мир всем", "О свышнем мире и спасении душ наших",
            "Господи помилуй ".repeat(40), "херувимы", "верую во", "неблагословенно царство отца",
            "Бога единаго во верую", "", "Сегодня тихо").forEach { text ->
            val result = finish(text)
            assertEquals(text, Reason.NO_DISTINCTIVE_MATCH, result.reason)
            assertNull(result.emitted)
            assertFalse(result.automaticSyncEligible)
        }
    }

    @Test fun `two different stages in one final remain ambiguous`() {
        assertEquals(Reason.NO_DISTINCTIVE_MATCH, finish("$cherubic. $creed").reason)
    }

    @Test fun `normalized shared anchors are ambiguous but independent unique anchors can help a person`() {
        val synthetic = HypothesisPrototype(PhraseMatcher(listOf(
            StageAnchor("a", listOf("Святый Боже", "Начальное особое песнопение", "Еще одно начальное песнопение")),
            StageAnchor("b", listOf("Святой Боже", "Последующее особое песнопение"))
        )))
        fun match(text: String): Result {
            val state = synthetic.reduce(State(), Event.Begin(1, 200), 100).state
            return synthetic.reduce(state, Event.Final(requireNotNull(state.active).token, text), 150)
        }
        assertNull(match("Святый Боже").emitted)
        assertNull(match("Святой Боже").emitted)
        assertEquals("a", match("Начальное особое песнопение. Еще одно начальное песнопение").emitted?.stageId)
        assertEquals("b", match("Святой Боже. Последующее особое песнопение").emitted?.stageId)
        assertNull(match("Начальное особое песнопение. Последующее особое песнопение").emitted)
    }

    @Test fun `quoted prayer is a candidate and a counterexample to automatic synchronization`() {
        // Ground truth of this fixture is a conversation about a prior service, not the hymn now.
        val result = finish("Вчера пели $cherubic")
        assertEquals("cherubic_hymn", result.emitted?.stageId)
        assertFalse(result.automaticSyncEligible)
    }

    @Test fun `partials are never matched or joined to final`() {
        var state = begin()
        val token = requireNotNull(state.active).token
        listOf("Иже Херувимы", "тайно образующе", cherubic).forEachIndexed { index, text ->
            val partial = prototype.reduce(state, Event.Partial(token, text), 110L + index)
            assertEquals(Reason.PARTIAL_ONLY, partial.reason)
            assertNull(partial.emitted)
            assertNull(partial.state.candidate)
            assertEquals(200L, partial.state.active?.acceptBeforeMs)
            state = partial.state
        }
        assertNull(prototype.reduce(state, Event.Final(token, "Ничего не разобрано"), 150).emitted)
    }

    @Test fun `only final stage survives a conflicting partial`() {
        val initial = begin()
        val token = requireNotNull(initial.active).token
        val partial = prototype.reduce(initial, Event.Partial(token, cherubic), 110)
        assertEquals("creed", prototype.reduce(partial.state, Event.Final(token, creed), 150).emitted?.stageId)
    }

    @Test fun `first final consumes token including empty ambiguous and oversized text`() {
        listOf("", "$cherubic. $creed", "x".repeat(HypothesisPrototype.MAX_TRANSCRIPT_CHARS + 1), cherubic).forEach { first ->
            val state = begin()
            val token = requireNotNull(state.active).token
            val completed = finish(first, state)
            val duplicate = prototype.reduce(completed.state, Event.Final(token, creed), 160)
            assertEquals(Reason.STALE_TOKEN, duplicate.reason)
            assertNull(duplicate.emitted)
            assertEquals(completed.state, duplicate.state)
        }
    }

    @Test fun `one active attempt rejects another start without moving deadline or consuming its id`() {
        val state = begin()
        val busy = prototype.reduce(state, Event.Begin(2, 900), 150)
        assertEquals(Reason.BUSY, busy.reason)
        assertEquals(state.copy(observedAtMs = 150), busy.state)
        val finished = finish(cherubic, busy.state).state
        assertEquals(2L, begin(finished, 2, 160, 250).active?.token?.attemptId)
    }

    @Test fun `accepted attempt ids cannot be repeated after completion or invalidation`() {
        val completed = finish(cherubic, begin(id = 4)).state
        listOf(-1L, 0L, 1L, 4L).forEach { id ->
            assertEquals(Reason.REUSED_ID, prototype.reduce(completed, Event.Begin(id, 250), 160).reason)
        }
        val cancelled = prototype.reduce(completed, Event.Cancel(requireNotNull(completed.candidate).token), 160).state
        assertEquals(Reason.REUSED_ID, prototype.reduce(cancelled, Event.Begin(4, 250), 170).reason)
    }

    @Test fun `stale final partial timeout and cancel cannot close or poison a newer attempt`() {
        val first = begin()
        val old = requireNotNull(first.active).token
        val second = begin(finish(cherubic, first).state, 2, 160, 260)
        listOf(Event.Final(old, creed), Event.Partial(old, creed), Event.Timeout(old), Event.Cancel(old)).forEach { event ->
            // Even absurd future or backward times on stale callbacks must not touch the live window.
            listOf(-1L, Long.MAX_VALUE).forEach { now ->
                val result = prototype.reduce(second, event, now)
                assertEquals(Reason.STALE_TOKEN, result.reason)
                assertEquals(second, result.state)
            }
        }
        assertEquals("creed", finish(creed, second, 200).emitted?.stageId)
    }

    @Test fun `final at opened time is allowed but deadline is exclusive`() {
        assertNotNull(finish(cherubic, now = 100).emitted)
        listOf(200L, 201L, Long.MAX_VALUE).forEach { now ->
            val state = begin()
            val token = requireNotNull(state.active).token
            val expired = finish(cherubic, state, now)
            assertEquals(Reason.EXPIRED, expired.reason)
            assertNull(expired.state.active)
            assertNull(prototype.reduce(expired.state, Event.Final(token, cherubic), 150).emitted)
        }
    }

    @Test fun `backward clock consumes attempt and cannot be repaired by a later callback`() {
        listOf(-1L, 99L, 149L).forEach { backwards ->
            val initial = begin()
            val token = requireNotNull(initial.active).token
            val partial = prototype.reduce(initial, Event.Partial(token, cherubic), 150)
            val invalid = prototype.reduce(partial.state, Event.Final(token, cherubic), backwards)
            assertEquals(Reason.INVALID_CLOCK, invalid.reason)
            assertNull(invalid.state.active)
            assertEquals(Reason.STALE_TOKEN, prototype.reduce(invalid.state, Event.Final(token, cherubic), 160).reason)
        }
    }

    @Test fun `partial at deadline expires instead of extending window`() {
        val state = begin()
        val token = requireNotNull(state.active).token
        val expired = prototype.reduce(state, Event.Partial(token, cherubic), 200)
        assertEquals(Reason.EXPIRED, expired.reason)
        assertNull(prototype.reduce(expired.state, Event.Final(token, cherubic), 201).emitted)
    }

    @Test fun `timeout before deadline does not close and timeout at deadline closes once`() {
        val state = begin()
        val token = requireNotNull(state.active).token
        val early = prototype.reduce(state, Event.Timeout(token), 150)
        assertEquals(Reason.TIMEOUT_NOT_DUE, early.reason)
        assertEquals(state.active, early.state.active)
        val due = prototype.reduce(early.state, Event.Timeout(token), 200)
        assertEquals(Reason.EXPIRED, due.reason)
        assertNull(prototype.reduce(due.state, Event.Final(token, cherubic), 201).emitted)
    }

    @Test fun `cancel clears active and pending candidate and increments context generation`() {
        listOf(begin(), finish(cherubic).state).forEach { state ->
            val token = state.active?.token ?: requireNotNull(state.candidate).token
            val cancelled = prototype.reduce(state, Event.Cancel(token), 160)
            assertNull(cancelled.state.active)
            assertNull(cancelled.state.candidate)
            assertEquals(1L, cancelled.state.generation)
            assertNull(prototype.reduce(cancelled.state, Event.Final(token, cherubic), 170).emitted)
        }
    }

    @Test fun `background blocks new attempts and foreground never resumes old capture or candidate`() {
        listOf(begin(), finish(cherubic).state).forEach { state ->
            val token = state.active?.token ?: requireNotNull(state.candidate).token
            val background = prototype.reduce(state, Event.Background, 160).state
            assertFalse(background.foreground)
            assertNull(background.active)
            assertNull(background.candidate)
            assertEquals(Reason.BACKGROUND, prototype.reduce(background, Event.Begin(2, 250), 170).reason)
            assertNull(prototype.reduce(background, Event.Final(token, cherubic), 170).emitted)
            val foreground = prototype.reduce(background, Event.Foreground, 180).state
            assertTrue(foreground.foreground)
            assertNull(foreground.active)
            assertNull(foreground.candidate)
            assertNull(prototype.reduce(foreground, Event.Final(token, cherubic), 190).emitted)
            assertEquals(Token(1, 2), begin(foreground, 2, 190, 250).active?.token)
        }
    }

    @Test fun `context change invalidates even if the next service has identical date and parish`() {
        val state = finish(cherubic).state
        val old = requireNotNull(state.candidate).token
        val changed = prototype.reduce(state, Event.ContextChanged, 160).state
        assertEquals(1L, changed.generation)
        assertNull(changed.candidate)
        val next = begin(changed, 2, 170, 250)
        assertEquals(Reason.STALE_TOKEN, prototype.reduce(next, Event.Final(old, creed), 180).reason)
        assertEquals(next, prototype.reduce(next, Event.Cancel(old), 180).state)
    }

    @Test fun `repeated distinctive phrase in a new attempt does not accumulate confidence`() {
        val first = finish(cherubic)
        val second = finish(cherubic, begin(first.state, 2, 160, 260), 200)
        assertEquals(first.emitted?.stageId, second.emitted?.stageId)
        assertNotEquals(first.emitted?.token, second.emitted?.token)
        assertFalse(first.automaticSyncEligible)
        assertFalse(second.automaticSyncEligible)
    }

    @Test fun `invalid beginning windows create no token and require no duration arithmetic`() {
        listOf(-1L to 200L, 100L to 100L, 100L to 99L, Long.MAX_VALUE to Long.MAX_VALUE).forEach { (now, deadline) ->
            val result = prototype.reduce(State(), Event.Begin(1, deadline), now)
            assertNull(result.state.active)
            assertNull(result.emitted)
        }
        val nearLimit = begin(now = Long.MAX_VALUE - 1, deadline = Long.MAX_VALUE)
        assertNotNull(finish(cherubic, nearLimit, Long.MAX_VALUE - 1).emitted)
    }

    @Test fun `new attempts reject backward time after a completed attempt`() {
        val state = finish(cherubic).state
        val result = prototype.reduce(state, Event.Begin(2, 250), 149)
        assertEquals(Reason.INVALID_CLOCK, result.reason)
        assertNull(result.state.candidate)
        assertNull(result.state.active)
        assertEquals(150L, result.state.observedAtMs)
    }

    @Test fun `control events preserve monotonic high water mark across generations`() {
        val active = begin()
        val token = requireNotNull(active.active).token
        listOf(Event.Background, Event.ContextChanged, Event.Cancel(token)).forEach { event ->
            val invalidated = prototype.reduce(active, event, 300).state
            assertEquals(300L, invalidated.observedAtMs)
            val foreground = prototype.reduce(invalidated, Event.Foreground, 300).state
            val backwards = prototype.reduce(foreground, Event.Begin(2, 400), 200)
            assertEquals(Reason.INVALID_CLOCK, backwards.reason)
            assertNull(backwards.state.active)
            assertEquals(300L, backwards.state.observedAtMs)
        }
        val badForeground = prototype.reduce(active, Event.Foreground, 99)
        assertEquals(Reason.INVALID_CLOCK, badForeground.reason)
        assertNull(badForeground.state.active)
        assertEquals(100L, badForeground.state.observedAtMs)
    }

    @Test fun `attempt counter stops at max without wrapping`() {
        val active = begin(id = Long.MAX_VALUE)
        val complete = finish(cherubic, active).state
        listOf(Long.MIN_VALUE, 1L, Long.MAX_VALUE).forEach { id ->
            val result = prototype.reduce(complete, Event.Begin(id, 250), 160)
            assertEquals(Reason.COUNTER_EXHAUSTED, result.reason)
            assertNull(result.state.active)
        }
    }

    @Test fun `generation exhaustion permanently prevents reuse after invalidation`() {
        val active = begin(State(generation = Long.MAX_VALUE))
        val token = requireNotNull(active.active).token
        val cancelled = prototype.reduce(active, Event.Cancel(token), 150).state
        assertTrue(cancelled.exhausted)
        assertEquals(Long.MAX_VALUE, cancelled.generation)
        val cycled = prototype.reduce(prototype.reduce(cancelled, Event.Background, 160).state, Event.Foreground, 170).state
        assertEquals(Reason.COUNTER_EXHAUSTED, prototype.reduce(cycled, Event.Begin(2, 250), 180).reason)
        assertEquals(Reason.STALE_TOKEN, prototype.reduce(cycled, Event.Final(token, cherubic), 180).reason)
    }
}
