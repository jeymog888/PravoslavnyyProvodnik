package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LyricPlaybackEngineTest {
    private val now = Instant.parse("2026-11-08T06:00:00Z")
    private val track = PrayerTrack("prayer", "Молитва", "Смысл по-русски",
        (0..2).map { PlayerLine("line-$it", "Строка $it", "Оригинал $it") }, null, false)
    private val engine = LyricPlaybackEngine(track, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `manual mode is the default and never advances on elapsed time`() {
        val state = engine.start(lineIndex = 1)
        val current = engine.position(state, now.plusSeconds(3600))
        assertEquals(1, current.lineIndex)
        assertFalse(current.isPlaying)
        assertEquals(0L, current.elapsedInLineMillis)
    }

    @Test fun `explicit playback advances at the chosen line boundary`() {
        val state = engine.start(secondsPerLine = 8, play = true)
        assertEquals(0, engine.position(state, now.plusMillis(7999)).lineIndex)
        val second = engine.position(state, now.plusSeconds(8))
        assertEquals(1, second.lineIndex)
        assertEquals(0L, second.elapsedInLineMillis)
        assertTrue(second.isPlaying)
    }

    @Test fun `pause and resume preserve a partial line instead of restarting it`() {
        val paused = engine.pause(engine.start(play = true), now.plusSeconds(17))
        assertEquals(1, paused.anchorLineIndex)
        assertEquals(5000L, paused.elapsedInLineMillis)
        assertFalse(paused.isPlaying)
        assertEquals(1, engine.position(paused, now.plusSeconds(500)).lineIndex)
        val resumed = engine.resume(paused, now.plusSeconds(600))
        assertEquals(1, engine.position(resumed, now.plusSeconds(606)).lineIndex)
        assertEquals(2, engine.position(resumed, now.plusSeconds(607)).lineIndex)
    }

    @Test fun `playback stops on the last line and does not wrap or restart`() {
        val state = engine.start(play = true)
        val end = engine.position(state, now.plusSeconds(36))
        assertEquals(2, end.lineIndex)
        assertEquals(12000L, end.elapsedInLineMillis)
        assertTrue(end.atEnd)
        assertFalse(end.isPlaying)
        val resumed = engine.resume(state, now.plusSeconds(100))
        assertFalse(resumed.isPlaying)
        assertTrue(engine.position(resumed).atEnd)
        val restart = engine.resume(engine.reset(resumed))
        assertEquals(0, engine.position(restart).lineIndex)
        assertTrue(restart.isPlaying)
    }

    @Test fun `single line tracks wait their duration before stopping`() {
        val single = LyricPlaybackEngine(track.copy(lines = track.lines.take(1)))
        val state = single.start(now = now, secondsPerLine = 16, play = true)
        assertTrue(single.position(state, now.plusSeconds(15)).isPlaying)
        assertTrue(single.position(state, now.plusSeconds(16)).atEnd)
    }

    @Test fun `manual seeking pauses and clears the old offset`() {
        val selected = engine.seek(engine.start(play = true), 2, now.plusSeconds(3))
        assertEquals(2, selected.anchorLineIndex)
        assertFalse(selected.isPlaying)
        assertEquals(0L, selected.elapsedInLineMillis)
    }

    @Test fun `next previous and seek are bounded at track ends`() {
        val first = engine.start()
        assertEquals(0, engine.previous(first).anchorLineIndex)
        assertEquals(0, engine.seek(first, Int.MIN_VALUE).anchorLineIndex)
        val last = engine.seek(first, Int.MAX_VALUE)
        assertEquals(2, engine.next(last).anchorLineIndex)
        assertEquals(2, last.anchorLineIndex)
        assertFalse(engine.next(engine.start(play = true)).isPlaying)
    }

    @Test fun `next and previous are relative to the visible timed line`() {
        val active = engine.start(play = true)
        assertEquals(2, engine.next(active, now.plusSeconds(13)).anchorLineIndex)
        assertEquals(0, engine.previous(active, now.plusSeconds(13)).anchorLineIndex)
    }

    @Test fun `tempo change preserves relative offset and playing state`() {
        val changed = engine.changeTempo(engine.start(secondsPerLine = 8, play = true), 16, now.plusSeconds(4))
        assertEquals(0, changed.anchorLineIndex)
        assertEquals(8000L, changed.elapsedInLineMillis)
        assertTrue(changed.isPlaying)
        assertEquals(0, engine.position(changed, now.plusSeconds(11)).lineIndex)
        assertEquals(1, engine.position(changed, now.plusSeconds(12)).lineIndex)
        val paused = engine.changeTempo(engine.pause(changed, now.plusSeconds(6)), 8, now.plusSeconds(6))
        assertFalse(paused.isPlaying)
        assertEquals(5000L, paused.elapsedInLineMillis)
    }

    @Test fun `clock reversal cannot move to an earlier line or make a negative offset`() {
        val state = engine.start(lineIndex = 1, elapsedInLineMillis = 3000, play = true)
        val current = engine.position(state, now.minusSeconds(600))
        assertEquals(1, current.lineIndex)
        assertEquals(3000L, current.elapsedInLineMillis)
    }

    @Test fun `extreme elapsed time stops safely without overflow`() {
        val current = engine.position(engine.start(play = true), Instant.MAX)
        assertEquals(2, current.lineIndex)
        assertTrue(current.atEnd)
        assertFalse(current.isPlaying)
    }

    @Test fun `invalid saved state cannot start an automatic timer`() {
        val base = engine.start(play = true)
        listOf(base.copy(anchorLineIndex = -8), base.copy(anchorLineIndex = Int.MAX_VALUE),
            base.copy(secondsPerLine = 0), base.copy(elapsedInLineMillis = -1),
            base.copy(elapsedInLineMillis = Long.MAX_VALUE), base.copy(trackId = "old-track")).forEach { corrupt ->
            val position = engine.position(corrupt, now.plusSeconds(100))
            assertFalse(position.isPlaying)
            assertTrue(position.lineIndex in track.lines.indices)
            assertTrue(position.elapsedInLineMillis in 0..12000L)
        }
    }

    @Test fun `restoring a saved line and offset is paused until explicitly resumed`() {
        val paused = engine.pause(engine.start(play = true), now.plusSeconds(19))
        val restored = engine.start(paused.anchorLineIndex, paused.secondsPerLine,
            now.plusSeconds(3600), elapsedInLineMillis = paused.elapsedInLineMillis)
        assertEquals(1, engine.position(restored, now.plusSeconds(7200)).lineIndex)
        assertEquals(7000L, restored.elapsedInLineMillis)
        assertFalse(restored.isPlaying)
    }

    @Test fun `restoring with saved tempo then adopting global tempo preserves phrase fraction`() {
        val saved = engine.pause(engine.start(secondsPerLine = 16, play = true), now.plusSeconds(8))
        val restored = engine.start(saved.anchorLineIndex, saved.secondsPerLine,
            now.plusSeconds(600), elapsedInLineMillis = saved.elapsedInLineMillis)
        val globalTempo = engine.changeTempo(restored, 8, now.plusSeconds(600))
        assertEquals(0, globalTempo.anchorLineIndex)
        assertEquals(4000L, globalTempo.elapsedInLineMillis)
        assertFalse(globalTempo.isPlaying)
        val resumed = engine.resume(globalTempo, now.plusSeconds(600))
        assertEquals(0, engine.position(resumed, now.plusSeconds(603)).lineIndex)
        assertEquals(1, engine.position(resumed, now.plusSeconds(604)).lineIndex)
    }

    @Test fun `a finished track stays finished after restoring into a slower global tempo`() {
        val saved = engine.pause(engine.start(secondsPerLine = 8, play = true), now.plusSeconds(24))
        val restored = engine.start(saved.anchorLineIndex, saved.secondsPerLine,
            now.plusSeconds(600), elapsedInLineMillis = saved.elapsedInLineMillis)
        val globalTempo = engine.changeTempo(restored, 16, now.plusSeconds(600))
        val current = engine.position(globalTempo, now.plusSeconds(600))
        assertTrue(current.atEnd)
        assertFalse(current.isPlaying)
        assertEquals(16000L, current.elapsedInLineMillis)
    }

    @Test fun `invalid requested tempo and offsets are normalized safely`() {
        val state = engine.start(-100, -3, elapsedInLineMillis = -200)
        assertEquals(12, state.secondsPerLine)
        assertEquals(0, state.anchorLineIndex)
        assertEquals(0L, state.elapsedInLineMillis)
        assertEquals(12000L, engine.start(elapsedInLineMillis = Long.MAX_VALUE).elapsedInLineMillis)
    }

    @Test fun `empty and ambiguous tracks fail before player creation`() {
        assertThrows(IllegalArgumentException::class.java) { LyricPlaybackEngine(track.copy(lines = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            LyricPlaybackEngine(track.copy(lines = listOf(track.lines.first(), track.lines.first())))
        }
    }
}
