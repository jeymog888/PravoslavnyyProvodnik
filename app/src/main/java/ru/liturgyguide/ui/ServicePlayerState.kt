package ru.liturgyguide.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.*

/** One accepted reading. A replacement is installed only after its complete write succeeds. */
data class ActiveServicePlayback(
    val plan: ServicePlaybackPlan,
    val context: ReadingContext,
    val state: ServicePlaybackState,
    val isApproximate: Boolean = false
) {
    val engine = ServicePlaybackEngine(plan)
    init {
        require(context.serviceDate == plan.serviceDate.toString())
        require(ReadingContextCodec.validated(context) != null && engine.isValid(state))
    }
    val segment get() = plan.segments[state.segmentIndex]
    val stage get() = plan.stageAt(state.segmentIndex)
    val text get() = plan.retainedTextAt(state.segmentIndex)
    val track get() = text?.let { plan.track(it.trackId) }
    val tracks get() = plan.tracksByStage.getValue(stage.id)
    val lineIndex get() = text?.let { text -> track?.lines?.indexOfFirst { it.id == text.lineId } } ?: 0
}

/** The writer must atomically store context, paused snapshot, stage marker and current bookmark. */
fun interface ServicePlaybackWriter {
    fun save(reading: ActiveServicePlayback, resetBookmarks: Boolean, sampledMonotonicMillis: Long): Boolean
}

/**
 * One owner for the lifetime of AppNavigation. No internal coroutine, lifecycle observer or clock
 * survives its caller. The screen supplies ticks only while visible and RESUMED; leaving pauses.
 */
@Stable
class ServicePlayerState(
    initial: ActiveServicePlayback?,
    private val monotonicMillis: () -> Long,
    private val writer: ServicePlaybackWriter,
    private val savedPhrase: (PrayerTrack) -> String? = { null }
) {
    var reading by mutableStateOf(initial?.let { it.copy(state = it.state.copy(anchorMonotonicMillis = null)) })
        private set
    var generation by mutableIntStateOf(0)
        private set
    var focusRequest by mutableIntStateOf(0)
        private set

    /** Freeze the old owner before the new acceptance; a failed replacement leaves it paused. */
    fun accept(candidate: ActiveServicePlayback, resetBookmarks: Boolean): Boolean {
        pause()
        val now = monotonicMillis()
        val paused = candidate.copy(state = candidate.engine.pause(candidate.state, now))
        if (!write(paused, resetBookmarks, now)) return false
        reading = paused
        generation++
        focusRequest++
        return true
    }

    /** Call only after clearing storage successfully; deliberately cannot write an old reading. */
    fun forgetAccepted() {
        reading = null
        generation++
        focusRequest++
    }

    fun tick(expectedGeneration: Int) {
        if (expectedGeneration != generation) return
        val previous = reading ?: return
        if (!previous.state.isPlaying) return
        val now = monotonicMillis()
        var next = previous.copy(state = previous.engine.tick(previous.state, now))
        val boundary = next.state.segmentIndex != previous.state.segmentIndex
        if (boundary || !next.state.isPlaying) {
            if (!write(next, false, now)) next = next.copy(state = next.state.copy(anchorMonotonicMillis = null))
            focusRequest++
        }
        reading = next
    }

    /** Mandatory pause is never rolled back into playing, including on storage failure. */
    fun pause(): Boolean {
        val previous = reading ?: return true
        if (!previous.state.isPlaying) return true
        val now = monotonicMillis()
        val paused = previous.copy(state = previous.engine.pause(previous.state, now))
        reading = paused
        if (paused.state.segmentIndex != previous.state.segmentIndex) focusRequest++
        return write(paused, false, now)
    }

    fun play(): Boolean {
        val previous = reading ?: return false
        if (previous.state.isPlaying) return true
        val now = monotonicMillis()
        val state = previous.engine.play(previous.state, now)
        if (!state.isPlaying) return false // MissingReading and End require explicit actions.
        val next = previous.copy(state = state, isApproximate = true)
        if (!write(next, false, now)) return false
        reading = next
        focusRequest++
        return true
    }

    fun changeSpeed(speed: PlaybackSpeed): Boolean {
        val previous = reading ?: return false
        val now = monotonicMillis()
        val materialized = previous.copy(state = previous.engine.tick(previous.state, now))
        val next = materialized.copy(state = materialized.state.copy(speed = speed))
        if (next.state.segmentIndex != previous.state.segmentIndex) focusRequest++
        if (!write(next, false, now)) {
            reading = materialized.copy(state = materialized.state.copy(anchorMonotonicMillis = null))
            return false
        }
        reading = next
        return true
    }

    fun seekStage(stageId: String): Boolean = seek { current ->
        current.copy(state = current.engine.seekStage(current.state, stageId), isApproximate = false)
    }

    fun seekLine(index: Int): Boolean {
        val current = reading ?: return false
        val track = current.track ?: return false
        val line = track.lines.getOrNull(index) ?: return false
        return seek { paused -> paused.copy(state = paused.engine.seekLine(paused.state, track.id, line.id), isApproximate = false) }
    }

    fun selectTrack(index: Int): Boolean {
        val current = reading ?: return false
        val track = current.tracks.getOrNull(index) ?: return false
        if (track.id == current.track?.id) return pause()
        return seek { paused ->
            val savedId = runCatching { savedPhrase(track) }.getOrNull()
            val line = track.lines.firstOrNull { it.id == savedId } ?: track.lines.first()
            paused.copy(state = paused.engine.seekLine(paused.state, track.id, line.id), isApproximate = false)
        }
    }

    fun continueAfterMissing(): Boolean {
        if (reading?.segment !is PlaybackSegment.MissingReading) return false
        return seek { current -> current.copy(state = current.engine.continueAfterMissing(current.state), isApproximate = false) }
    }

    private fun seek(transform: (ActiveServicePlayback) -> ActiveServicePlayback): Boolean {
        pause()
        val previous = reading ?: return false
        val next = runCatching { transform(previous) }.getOrNull() ?: return false
        val now = monotonicMillis()
        if (!write(next, false, now)) return false
        reading = next
        focusRequest++
        return true
    }

    private fun write(value: ActiveServicePlayback, reset: Boolean, now: Long): Boolean =
        runCatching { writer.save(value, reset, now) }.getOrDefault(false)
}
