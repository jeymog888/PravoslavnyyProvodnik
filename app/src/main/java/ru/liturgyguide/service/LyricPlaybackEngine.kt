package ru.liturgyguide.service

import ru.liturgyguide.model.PrayerTrack
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Local reading aid, not a detected position in spoken or sung worship. */
data class LyricPlaybackState(
    val trackId: String,
    val anchorLineIndex: Int,
    val anchorEpochMillis: Long,
    val elapsedInLineMillis: Long = 0,
    val secondsPerLine: Int = 12,
    val isPlaying: Boolean = false
)

data class LyricPosition(
    val lineIndex: Int,
    val elapsedInLineMillis: Long,
    val isPlaying: Boolean,
    val atEnd: Boolean
)

/**
 * One explicit timer for one track. The owner pauses on ON_STOP and restores
 * preferences with start(play = false); elapsed background time is not listening.
 */
class LyricPlaybackEngine(
    private val track: PrayerTrack,
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(track.id.isNotBlank() && track.lines.isNotEmpty()) { "A player needs a nonempty track" }
        require(track.lines.all { it.id.isNotBlank() }) { "Player lines need IDs" }
        require(track.lines.map { it.id }.distinct().size == track.lines.size) { "Player line IDs must be unique" }
    }

    fun start(
        lineIndex: Int = 0,
        secondsPerLine: Int = DEFAULT_SECONDS_PER_LINE,
        now: Instant = clock.instant(),
        play: Boolean = false,
        elapsedInLineMillis: Long = 0
    ): LyricPlaybackState {
        val tempo = validTempo(secondsPerLine)
        return LyricPlaybackState(track.id, lineIndex.coerceIn(0, track.lines.lastIndex), now.toEpochMilli(),
            elapsedInLineMillis.coerceIn(0, tempo * 1000L), tempo, play)
    }

    fun position(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPosition {
        if (state.trackId != track.id) return LyricPosition(0, 0, false, false)
        val lineIndex = state.anchorLineIndex.coerceIn(0, track.lines.lastIndex)
        val duration = validTempo(state.secondsPerLine) * 1000L
        val offset = state.elapsedInLineMillis.coerceIn(0, duration)
        val valid = lineIndex == state.anchorLineIndex && state.secondsPerLine in TEMPOS &&
            offset == state.elapsedInLineMillis
        val remaining = (track.lines.size - lineIndex).toLong() * duration - offset
        val elapsed = Duration.between(Instant.ofEpochMilli(state.anchorEpochMillis), now)
        val delta = when {
            !state.isPlaying || !valid || elapsed.isNegative -> 0L
            elapsed >= Duration.ofMillis(remaining) -> remaining
            else -> elapsed.toMillis()
        }
        val total = offset + delta
        val atEnd = total >= (track.lines.size - lineIndex).toLong() * duration
        // Paused and invalid state is displayed at its captured line, even if
        // its offset is exactly the boundary; only playback advances it.
        val advance = if (state.isPlaying && valid) (total / duration).toInt() else 0
        val currentIndex = (lineIndex + advance).coerceAtMost(track.lines.lastIndex)
        return LyricPosition(
            lineIndex = currentIndex,
            elapsedInLineMillis = if (atEnd) duration else if (advance > 0) total % duration else total,
            isPlaying = state.isPlaying && valid && !atEnd,
            atEnd = atEnd
        )
    }

    /** Selection, including next/previous, always stops automatic scrolling. */
    fun seek(state: LyricPlaybackState, lineIndex: Int, now: Instant = clock.instant()): LyricPlaybackState =
        start(lineIndex, state.secondsPerLine, now)

    fun next(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPlaybackState =
        seek(state, position(state, now).lineIndex + 1, now)

    fun previous(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPlaybackState =
        seek(state, position(state, now).lineIndex - 1, now)

    fun reset(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPlaybackState = seek(state, 0, now)

    fun pause(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPlaybackState {
        val current = position(state, now)
        return start(current.lineIndex, state.secondsPerLine, now,
            elapsedInLineMillis = current.elapsedInLineMillis)
    }

    /** At the end, reset/seek is required before starting again. */
    fun resume(state: LyricPlaybackState, now: Instant = clock.instant()): LyricPlaybackState {
        val current = position(state, now)
        return start(current.lineIndex, state.secondsPerLine, now, play = !current.atEnd,
            elapsedInLineMillis = current.elapsedInLineMillis)
    }

    /** Keep the current line and its relative progress when changing tempo. */
    fun changeTempo(state: LyricPlaybackState, secondsPerLine: Int, now: Instant = clock.instant()): LyricPlaybackState {
        val current = position(state, now)
        val tempo = validTempo(secondsPerLine)
        val offset = current.elapsedInLineMillis * tempo / validTempo(state.secondsPerLine)
        return start(current.lineIndex, tempo, now, play = current.isPlaying, elapsedInLineMillis = offset)
    }

    companion object {
        val TEMPOS: List<Int> = listOf(8, 12, 16)
        const val DEFAULT_SECONDS_PER_LINE = 12
        private fun validTempo(seconds: Int): Int = seconds.takeIf { it in TEMPOS } ?: DEFAULT_SECONDS_PER_LINE
    }
}
