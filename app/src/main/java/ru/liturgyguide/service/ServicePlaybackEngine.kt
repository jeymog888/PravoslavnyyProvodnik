package ru.liturgyguide.service

/** Exact multipliers of one monotonic millisecond in quarter-millisecond base units. */
enum class PlaybackSpeed(val quartersPerMillis: Int) { SLOW(3), NORMAL(4), FAST(5) }

/** Runtime-only state. Its monotonic anchor must never be persisted across processes. */
data class ServicePlaybackState(
    val planFingerprint: String,
    val segmentIndex: Int,
    val elapsedQuarterMillis: Long,
    val speed: PlaybackSpeed,
    val anchorMonotonicMillis: Long? = null
) {
    val isPlaying: Boolean get() = anchorMonotonicMillis != null
}

/** Serializable captured position, with no clock or autoplay flag. Restore always pauses. */
data class ServicePlaybackSnapshot(
    val contentVersion: Int,
    val planFingerprint: String,
    val segmentIndex: Int,
    val elapsedQuarterMillis: Long,
    val speed: PlaybackSpeed
)

/**
 * Pure reducer for a single visible-screen clock. The UI owner supplies elapsedRealtime()
 * and pauses on navigation/background/meaning; neither parish time nor wall clock enters here.
 */
class ServicePlaybackEngine(val plan: ServicePlaybackPlan) {
    fun start(stageId: String = plan.stages.first().id, speed: PlaybackSpeed = PlaybackSpeed.NORMAL): ServicePlaybackState =
        ServicePlaybackState(plan.fingerprint, requireNotNull(plan.indexOfStage(stageId)) { "Unknown stage: $stageId" }, 0, speed)

    /** Invalid or changed content is explicit; callers choose a safe entry/migration instead. */
    fun restore(snapshot: ServicePlaybackSnapshot): ServicePlaybackState? {
        if (snapshot.contentVersion != plan.contentVersion) return null
        val state = ServicePlaybackState(snapshot.planFingerprint, snapshot.segmentIndex, snapshot.elapsedQuarterMillis, snapshot.speed)
        return state.takeIf(::isValid)
    }

    /** Capture elapsed foreground time before saving; the returned snapshot cannot resume itself. */
    fun snapshot(state: ServicePlaybackState, nowMonotonicMillis: Long): ServicePlaybackSnapshot {
        val current = pause(state, nowMonotonicMillis)
        return ServicePlaybackSnapshot(plan.contentVersion, current.planFingerprint, current.segmentIndex,
            current.elapsedQuarterMillis, current.speed)
    }

    fun isValid(state: ServicePlaybackState): Boolean {
        if (state.planFingerprint != plan.fingerprint || state.segmentIndex !in plan.segments.indices ||
            state.elapsedQuarterMillis < 0 || (state.anchorMonotonicMillis != null && state.anchorMonotonicMillis < 0)) return false
        val duration = plan.durationQuarterMillis(state.segmentIndex)
        return if (duration == null) state.elapsedQuarterMillis == 0L && !state.isPlaying
        else state.elapsedQuarterMillis < duration
    }

    fun tick(state: ServicePlaybackState, nowMonotonicMillis: Long): ServicePlaybackState {
        require(isValid(state)) { "The runtime position does not belong to this playback plan" }
        val anchor = state.anchorMonotonicMillis ?: return state
        // A reboot or defective clock cannot create elapsed listening time or a future jump.
        if (nowMonotonicMillis < anchor) return state.copy(anchorMonotonicMillis = null)
        val elapsed = nowMonotonicMillis - anchor // Both operands are nonnegative, so subtraction is safe.
        val multiplier = state.speed.quartersPerMillis.toLong()
        var remaining = if (elapsed > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else elapsed * multiplier
        var index = state.segmentIndex
        var offset = state.elapsedQuarterMillis
        while (true) {
            val duration = plan.durationQuarterMillis(index)
                ?: return state.copy(segmentIndex = index, elapsedQuarterMillis = 0, anchorMonotonicMillis = null)
            val toBoundary = duration - offset
            if (remaining < toBoundary) return state.copy(segmentIndex = index, elapsedQuarterMillis = offset + remaining,
                anchorMonotonicMillis = nowMonotonicMillis)
            remaining -= toBoundary
            index++ // Every plan ends with a non-timed End, which stops this loop.
            offset = 0
        }
    }

    fun play(state: ServicePlaybackState, nowMonotonicMillis: Long): ServicePlaybackState {
        val current = tick(state, nowMonotonicMillis)
        // A repeated play is idempotent, including when tick just detected a backwards clock.
        return if (state.isPlaying || nowMonotonicMillis < 0 || plan.durationQuarterMillis(current.segmentIndex) == null) current
        else current.copy(anchorMonotonicMillis = nowMonotonicMillis)
    }

    fun pause(state: ServicePlaybackState, nowMonotonicMillis: Long): ServicePlaybackState =
        tick(state, nowMonotonicMillis).copy(anchorMonotonicMillis = null)

    /** Materialize at the old speed first, then change speed without rounding away base progress. */
    fun changeSpeed(state: ServicePlaybackState, speed: PlaybackSpeed, nowMonotonicMillis: Long): ServicePlaybackState =
        tick(state, nowMonotonicMillis).copy(speed = speed)

    /** Every manual correction pauses and starts the selected available text at its beginning. */
    fun seekStage(state: ServicePlaybackState, stageId: String): ServicePlaybackState {
        require(isValid(state))
        return start(stageId, state.speed)
    }

    fun seekLine(state: ServicePlaybackState, trackId: String, lineId: String): ServicePlaybackState {
        require(isValid(state))
        val index = requireNotNull(plan.indexOfLine(trackId, lineId)) { "Unknown reading line" }
        return state.copy(segmentIndex = index, elapsedQuarterMillis = 0, anchorMonotonicMillis = null)
    }

    /** Explicit acknowledgement only. Ordinary play never passes missing content. */
    fun continueAfterMissing(state: ServicePlaybackState): ServicePlaybackState {
        require(isValid(state))
        if (plan.segments[state.segmentIndex] !is PlaybackSegment.MissingReading) return state
        return state.copy(segmentIndex = state.segmentIndex + 1, elapsedQuarterMillis = 0, anchorMonotonicMillis = null)
    }
}
