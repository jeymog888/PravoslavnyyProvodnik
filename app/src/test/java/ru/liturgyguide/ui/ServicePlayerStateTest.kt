package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.StageText
import ru.liturgyguide.service.*
import java.time.LocalDate

class ServicePlayerStateTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val context = ReadingContext(null, date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private val text = StageText("Молитва", "Слова", "Смысл", "Пояснение",
        lines = listOf(PrayerLine("Первая строка", "Первая"), PrayerLine("Вторая строка", "Вторая")))
    private val stages = listOf(
        LiturgyStage("first", 1, "Первый", "Описание", 1, listOf(text, text.copy(type = "Второй текст"))),
        LiturgyStage("second", 2, "Второй", "Описание", 1, emptyList())
    )
    private fun reading(plan: ServicePlaybackPlan = ServicePlaybackPlan.build(stages, date, null)) =
        ActiveServicePlayback(plan, context.copy(serviceDate = plan.serviceDate.toString()), ServicePlaybackEngine(plan).start())

    private class Harness(initial: ActiveServicePlayback?) {
        var now = 1_000L
        var writable = true
        var writes = 0
        var saved: ServicePlaybackSnapshot? = null
        val bookmarks = mutableMapOf<String, String>()
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { value, reset, sampled ->
            writes++
            if (writable) {
                saved = value.engine.snapshot(value.state, sampled)
                if (reset) bookmarks.clear()
                value.text?.let { bookmarks[it.trackId] = it.lineId }
            }
            writable
        }, { bookmarks[it.id] })
        val current get() = requireNotNull(player.reading)
        fun seekLine(index: Int) = requireNotNull(current.track).let { player.seekLine(it.id, it.lines[index].id) }
        fun selectTrack(index: Int) = player.selectTrack(current.tracks[index].id)
    }

    @Test fun `one owner advances tracks waits and stages while its mirror is always a paused snapshot`() {
        val h = Harness(reading())
        assertTrue(h.player.play())
        h.now += 8_000
        h.player.tick(h.player.generation)
        assertEquals(1, h.current.tracks.indexOf(h.current.track))
        assertEquals(0, h.current.lineIndex)
        assertTrue(h.current.state.isPlaying)
        assertFalse(requireNotNull(h.current.engine.restore(requireNotNull(h.saved))).isPlaying)
        h.now += 8_000
        h.player.tick(h.player.generation)
        assertTrue(h.current.segment is PlaybackSegment.Wait)
        assertEquals(1, h.current.lineIndex)
        val waitFocus = h.player.focusRequest
        h.now += 44_000
        h.player.tick(h.player.generation)
        assertEquals("second", h.current.stage.id)
        assertNull(h.current.track)
        assertTrue(h.player.focusRequest > waitFocus)
    }

    @Test fun `a delayed tick from a replaced reading cannot touch its successor`() {
        val h = Harness(reading())
        h.player.play()
        val oldGeneration = h.player.generation
        val future = ServicePlaybackPlan.build(stages, date.plusDays(1), null)
        assertTrue(h.player.accept(reading(future), resetBookmarks = true))
        h.player.play()
        val accepted = h.current
        val writes = h.writes
        h.now += 900_000
        h.player.tick(oldGeneration)
        assertEquals(accepted, h.current)
        assertEquals(writes, h.writes)
        assertEquals(date.plusDays(1).toString(), h.current.context.serviceDate)
    }

    @Test fun `pause after a boundary materializes it once and background time never advances it`() {
        val h = Harness(reading())
        h.player.play()
        h.now += 4_001
        assertTrue(h.player.pause())
        assertEquals(1, h.current.lineIndex)
        assertEquals(4L, h.current.state.elapsedQuarterMillis)
        val paused = h.current
        h.now += 1_000_000
        h.player.tick(h.player.generation)
        assertEquals(paused, h.current)
        h.player.play()
        h.now++
        h.player.pause()
        assertEquals(8L, h.current.state.elapsedQuarterMillis)
    }

    @Test fun `failed mandatory pause remains paused and cannot resume from background`() {
        val h = Harness(reading())
        h.player.play()
        h.now += 1_000
        h.writable = false
        assertFalse(h.player.pause())
        assertFalse(h.current.state.isPlaying)
        assertEquals(4_000L, h.current.state.elapsedQuarterMillis)
        val paused = h.current
        h.now += 100_000
        h.player.tick(h.player.generation)
        assertEquals(paused, h.current)
        assertFalse(h.player.play())
        assertFalse(h.current.state.isPlaying)
    }

    @Test fun `failed manual correction leaves the old materialized position paused`() {
        val h = Harness(reading())
        h.player.play()
        h.now += 1_001
        h.writable = false
        assertFalse(h.player.seekStage("second"))
        assertEquals("first", h.current.stage.id)
        assertEquals(4_004L, h.current.state.elapsedQuarterMillis)
        assertFalse(h.current.state.isPlaying)
    }

    @Test fun `failed new acceptance never installs a different date or revives the previous timer`() {
        val h = Harness(reading())
        h.player.play()
        h.now += 500
        h.writable = false
        val future = ServicePlaybackPlan.build(stages, date.plusDays(1), null)
        assertFalse(h.player.accept(reading(future), resetBookmarks = true))
        assertEquals(date.toString(), h.current.context.serviceDate)
        assertEquals(2_000L, h.current.state.elapsedQuarterMillis)
        assertFalse(h.current.state.isPlaying)
        assertEquals(0, h.player.generation)
    }

    @Test fun `changing speed uses the old multiplier and failure keeps that speed on pause`() {
        val h = Harness(reading())
        h.player.play()
        h.now += 1_001
        assertTrue(h.player.changeSpeed(PlaybackSpeed.SLOW))
        assertEquals(4_004L, h.current.state.elapsedQuarterMillis)
        h.now++
        h.writable = false
        assertFalse(h.player.changeSpeed(PlaybackSpeed.FAST))
        assertEquals(4_007L, h.current.state.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.SLOW, h.current.state.speed)
        assertFalse(h.current.state.isPlaying)
    }

    @Test fun `manual track selection restores its own phrase without old elapsed time`() {
        val h = Harness(reading())
        assertTrue(h.seekLine(1))
        val firstTrack = requireNotNull(h.current.track)
        assertTrue(h.selectTrack(1))
        assertEquals(0, h.current.lineIndex)
        assertTrue(h.selectTrack(0))
        assertEquals(firstTrack, h.current.track)
        assertEquals(1, h.current.lineIndex)
        assertEquals(0L, h.current.state.elapsedQuarterMillis)
        assertFalse(h.current.state.isPlaying)
        h.bookmarks[firstTrack.id] = "removed-phrase"
        h.selectTrack(1)
        h.selectTrack(0)
        assertEquals(0, h.current.lineIndex)
    }

    @Test fun `failed speed change still focuses a newly reached missing-reading explanation`() {
        val plan = ServicePlaybackPlan.build(listOf(stages.first().copy(id = "epistle")), date, null)
        val h = Harness(reading(plan))
        h.player.play()
        val focus = h.player.focusRequest
        h.now += 1_000_000
        h.writable = false
        assertFalse(h.player.changeSpeed(PlaybackSpeed.FAST))
        assertTrue(h.current.segment is PlaybackSegment.MissingReading)
        assertFalse(h.current.state.isPlaying)
        assertTrue(h.player.focusRequest > focus)
    }

    @Test fun `missing text requires explicit continuation and End cannot restart from Play`() {
        val plan = ServicePlaybackPlan.build(listOf(stages.first().copy(id = "epistle"), stages.last()), date, null)
        val h = Harness(reading(plan))
        h.player.play()
        h.now += 1_000_000
        h.player.tick(h.player.generation)
        assertTrue(h.current.segment is PlaybackSegment.MissingReading)
        assertFalse(h.current.state.isPlaying)
        assertFalse(h.player.play())
        assertTrue(h.player.continueAfterMissing())
        assertEquals("second", h.current.stage.id)
        assertFalse(h.current.state.isPlaying)
        h.player.play()
        h.now += 60_000
        h.player.tick(h.player.generation)
        assertTrue(h.current.segment is PlaybackSegment.End)
        assertFalse(h.player.play())
        assertFalse(h.player.continueAfterMissing())
    }

    @Test fun `a failed boundary write stops playback and a forgotten owner never writes again`() {
        val h = Harness(reading())
        h.player.play()
        h.writable = false
        h.now += 4_000
        h.player.tick(h.player.generation)
        assertEquals(1, h.current.lineIndex)
        assertFalse(h.current.state.isPlaying)
        val oldGeneration = h.player.generation
        val writes = h.writes
        h.player.forgetAccepted()
        h.now += 9_000_000
        h.player.tick(oldGeneration)
        h.player.pause()
        assertNull(h.player.reading)
        assertEquals(writes, h.writes)
    }

    @Test fun `an incoming running state is never automatically resumed by restoration`() {
        val initial = reading()
        val running = initial.copy(state = initial.engine.play(initial.state, 100))
        val h = Harness(running)
        assertFalse(h.current.state.isPlaying)
        assertNull(h.current.state.anchorMonotonicMillis)
        assertEquals(0, h.writes)
    }

    @Test fun `a tap from a displayed frame seeks its exact phrase after automatic track change`() {
        val h = Harness(reading())
        val shownTrack = requireNotNull(h.current.track)
        val shownLine = shownTrack.lines[1]
        val generation = h.player.generation
        h.player.play()
        h.now += 8_000
        h.player.tick(generation)
        assertNotEquals(shownTrack.id, h.current.track?.id)
        assertTrue(h.player.seekLine(shownTrack.id, shownLine.id, generation))
        assertEquals(shownTrack.id, h.current.track?.id)
        assertEquals(shownLine.id, h.current.text?.lineId)
        assertFalse(h.current.state.isPlaying)
        h.player.play()
        h.now += 60_000
        h.player.tick(generation)
        assertEquals("second", h.current.stage.id)
        assertTrue(h.player.selectTrack(shownTrack.id, generation))
        assertEquals(shownLine.id, h.current.text?.lineId)
    }

    @Test fun `phrase and track taps from a replaced reading are rejected before pause or write`() {
        val h = Harness(reading())
        val oldTrack = requireNotNull(h.current.track)
        val oldGeneration = h.player.generation
        val future = ServicePlaybackPlan.build(stages, date.plusDays(1), null)
        h.player.accept(reading(future), resetBookmarks = true)
        h.player.play()
        val accepted = h.current
        val writes = h.writes
        assertFalse(h.player.seekLine(oldTrack.id, oldTrack.lines[1].id, oldGeneration))
        assertFalse(h.player.selectTrack(oldTrack.id, oldGeneration))
        assertFalse(h.player.pause(oldGeneration))
        assertFalse(h.player.play(oldGeneration))
        assertFalse(h.player.changeSpeed(PlaybackSpeed.FAST, oldGeneration))
        assertFalse(h.player.seekStage("second", oldGeneration))
        assertEquals(accepted, h.current)
        assertEquals(writes, h.writes)

        val missingPlan = ServicePlaybackPlan.build(listOf(stages.first().copy(id = "epistle", texts = emptyList())), date, null)
        h.player.accept(reading(missingPlan), resetBookmarks = true)
        val missing = h.current
        assertTrue(missing.segment is PlaybackSegment.MissingReading)
        assertFalse(h.player.continueAfterMissing(oldGeneration))
        assertEquals(missing, h.current)
    }
}
