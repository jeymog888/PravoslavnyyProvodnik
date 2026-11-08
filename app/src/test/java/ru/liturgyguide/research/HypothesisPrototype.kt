package ru.liturgyguide.research

import ru.liturgyguide.service.PhraseMatcher

/** Test-source experiment only. Text hypotheses cannot establish acoustic synchronization. */
internal class HypothesisPrototype(private val matcher: PhraseMatcher) {
    data class Token(val generation: Long, val attemptId: Long)
    data class Window(val token: Token, val openedAtMs: Long, val acceptBeforeMs: Long)
    data class CandidateForReview(val token: Token, val stageId: String, val matchedPhrase: String)

    data class State(
        val generation: Long = 0,
        val highestAttemptId: Long = 0,
        val foreground: Boolean = true,
        val exhausted: Boolean = false,
        val observedAtMs: Long? = null,
        val active: Window? = null,
        val candidate: CandidateForReview? = null
    ) {
        init {
            require(generation >= 0 && highestAttemptId >= 0)
            require(observedAtMs == null || observedAtMs >= 0)
            require(active == null || candidate == null)
        }
    }

    sealed interface Event {
        data class Begin(val attemptId: Long, val acceptBeforeMs: Long) : Event
        data class Partial(val token: Token, val text: String) : Event
        data class Final(val token: Token, val text: String) : Event
        data class Timeout(val token: Token) : Event
        data class Cancel(val token: Token) : Event
        data object Background : Event
        data object Foreground : Event
        data object ContextChanged : Event
    }

    enum class Reason {
        STARTED, CANDIDATE_FOR_REVIEW, NO_DISTINCTIVE_MATCH, PARTIAL_ONLY,
        STALE_TOKEN, BUSY, BACKGROUND, CANCELLED, CONTEXT_CHANGED, FOREGROUND,
        INVALID_WINDOW, INVALID_CLOCK, EXPIRED, TIMEOUT_NOT_DUE, REUSED_ID,
        COUNTER_EXHAUSTED, INPUT_TOO_LONG
    }

    data class Result(val state: State, val reason: Reason, val emitted: CandidateForReview? = null) {
        // This is not an adjustable policy or a confidence threshold.
        val automaticSyncEligible: Boolean get() = false
    }

    fun reduce(state: State, event: Event, nowMs: Long): Result {
        fun result(reason: Reason, next: State = state) = Result(next, reason)
        when (event) {
            Event.Background -> return result(Reason.BACKGROUND, invalidate(observeControl(state, nowMs)).copy(foreground = false))
            Event.ContextChanged -> return result(Reason.CONTEXT_CHANGED, invalidate(observeControl(state, nowMs)))
            Event.Foreground -> {
                val observed = observeControl(state, nowMs).copy(foreground = true)
                return if (validClock(state, nowMs)) result(Reason.FOREGROUND, observed)
                else result(Reason.INVALID_CLOCK, invalidate(observed))
            }
            is Event.Cancel -> {
                if (event.token != state.active?.token && event.token != state.candidate?.token) {
                    return result(Reason.STALE_TOKEN)
                }
                return result(Reason.CANCELLED, invalidate(observeControl(state, nowMs)))
            }
            is Event.Begin -> {
                if (!validClock(state, nowMs)) return result(Reason.INVALID_CLOCK, invalidate(state))
                val observed = state.copy(observedAtMs = nowMs)
                if (state.exhausted || state.highestAttemptId == Long.MAX_VALUE) return result(Reason.COUNTER_EXHAUSTED, observed)
                if (!state.foreground) return result(Reason.BACKGROUND, observed)
                if (state.active != null) return result(Reason.BUSY, observed)
                if (event.attemptId <= state.highestAttemptId) return result(Reason.REUSED_ID, observed)
                if (event.acceptBeforeMs <= nowMs) return result(Reason.INVALID_WINDOW, observed)
                val window = Window(Token(state.generation, event.attemptId), nowMs, event.acceptBeforeMs)
                return result(Reason.STARTED, state.copy(
                    highestAttemptId = event.attemptId, observedAtMs = nowMs, active = window, candidate = null
                ))
            }
            else -> Unit
        }

        val token = when (event) {
            is Event.Partial -> event.token
            is Event.Final -> event.token
            is Event.Timeout -> event.token
            else -> error("Control event must have returned above")
        }
        val window = state.active
        // A stale callback must not poison the current clock or close a newer attempt.
        if (window == null || token != window.token) return result(Reason.STALE_TOKEN)
        if (!state.foreground) return result(Reason.BACKGROUND, invalidate(state))
        if (!validClock(state, nowMs) || nowMs < window.openedAtMs) {
            return result(Reason.INVALID_CLOCK, invalidate(state))
        }
        val observed = state.copy(observedAtMs = nowMs)
        if (nowMs >= window.acceptBeforeMs) return result(Reason.EXPIRED, invalidate(observed))
        return when (event) {
            is Event.Partial -> result(Reason.PARTIAL_ONLY, observed)
            is Event.Timeout -> result(Reason.TIMEOUT_NOT_DUE, observed)
            is Event.Final -> {
                // Consume even empty, ambiguous or oversized final before evaluating text.
                val completed = observed.copy(active = null, candidate = null)
                if (event.text.length > MAX_TRANSCRIPT_CHARS) return result(Reason.INPUT_TOO_LONG, completed)
                val match = matcher.match(event.text)
                    ?: return result(Reason.NO_DISTINCTIVE_MATCH, completed)
                val candidate = CandidateForReview(token, match.stageId, match.matchedPhrase)
                Result(completed.copy(candidate = candidate), Reason.CANDIDATE_FOR_REVIEW, candidate)
            }
            else -> error("Only hypothesis callbacks remain")
        }
    }

    private fun validClock(state: State, nowMs: Long): Boolean =
        nowMs >= 0 && (state.observedAtMs == null || nowMs >= state.observedAtMs)

    // Control actions still invalidate on a bad clock, but never lower the high-water mark.
    private fun observeControl(state: State, nowMs: Long): State =
        if (validClock(state, nowMs)) state.copy(observedAtMs = nowMs) else state

    private fun invalidate(state: State): State = if (state.generation == Long.MAX_VALUE) {
        state.copy(active = null, candidate = null, exhausted = true)
    } else state.copy(generation = state.generation + 1, active = null, candidate = null)

    companion object {
        // Resource bound for a synthetic input, not an acoustic duration or quality threshold.
        const val MAX_TRANSCRIPT_CHARS = 4096
    }
}
