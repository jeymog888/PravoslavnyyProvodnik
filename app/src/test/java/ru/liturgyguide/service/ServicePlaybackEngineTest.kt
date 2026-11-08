package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.StageText
import java.time.LocalDate

class ServicePlaybackEngineTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val text = StageText("Молитва", "Короткий текст", "Короткий смысл", "Пояснение")
    private val stage = LiturgyStage("first", 1, "Первый", "Описание", 1, listOf(text))
    private val second = stage.copy(id = "second", order = 2)
    private fun engine(stages: List<LiturgyStage> = listOf(stage, second)) =
        ServicePlaybackEngine(ServicePlaybackPlan.build(stages, date, null))

    @Test fun `exact boundaries advance line wait and stage using one clock`() {
        val engine = engine()
        var state = engine.play(engine.start(), 10_000)
        state = engine.tick(state, 13_999)
        assertEquals(0, state.segmentIndex)
        assertEquals(15_996L, state.elapsedQuarterMillis)
        state = engine.tick(state, 14_000)
        assertTrue(engine.plan.segments[state.segmentIndex] is PlaybackSegment.Wait)
        assertEquals(0L, state.elapsedQuarterMillis)
        state = engine.tick(state, 70_000)
        assertEquals("second", engine.plan.stageAt(state.segmentIndex).id)
        assertEquals(0L, state.elapsedQuarterMillis)
        assertTrue(state.isPlaying)
        state = engine.tick(state, 130_000)
        assertTrue(engine.plan.segments[state.segmentIndex] is PlaybackSegment.End)
        assertFalse(state.isPlaying)
    }

    @Test fun `multiple texts transition without resetting elapsed time or creating a second timer`() {
        val lines = text.copy(lines = List(3) { PrayerLine("Строка $it", "Смысл $it") })
        val engine = engine(listOf(stage.copy(texts = listOf(lines, text))))
        val state = engine.tick(engine.play(engine.start(), 0), 12_500)
        val current = engine.plan.segments[state.segmentIndex] as PlaybackSegment.Text
        assertEquals(3, state.segmentIndex)
        assertEquals(2_000L, state.elapsedQuarterMillis)
        assertNotEquals((engine.plan.segments.first() as PlaybackSegment.Text).trackId, current.trackId)
    }

    @Test fun `many tiny ticks equal one large tick at fractional speed`() {
        val engine = engine()
        val start = engine.play(engine.start(speed = PlaybackSpeed.SLOW), 0)
        var tiny = start
        (1L..1_001L).forEach { tiny = engine.tick(tiny, it) }
        val large = engine.tick(start, 1_001)
        assertEquals(large, tiny)
        assertEquals(3_003L, tiny.elapsedQuarterMillis)
    }

    @Test fun `speed changes materialize the old multiplier and retain fractional base progress`() {
        val engine = engine()
        val start = engine.play(engine.start(speed = PlaybackSpeed.SLOW), 10)
        val faster = engine.changeSpeed(start, PlaybackSpeed.FAST, 1_011)
        assertEquals(3_003L, faster.elapsedQuarterMillis)
        assertTrue(faster.isPlaying)
        assertEquals(3_008L, engine.tick(faster, 1_012).elapsedQuarterMillis)
        val normal = engine.changeSpeed(faster, PlaybackSpeed.NORMAL, 1_012)
        assertEquals(3_008L, normal.elapsedQuarterMillis)
        assertEquals(3_012L, engine.tick(normal, 1_013).elapsedQuarterMillis)
    }

    @Test fun `pause captures progress and ignores all background elapsed time`() {
        val engine = engine()
        val paused = engine.pause(engine.play(engine.start(), 100), 1_100)
        assertFalse(paused.isPlaying)
        assertEquals(4_000L, paused.elapsedQuarterMillis)
        assertEquals(paused, engine.tick(paused, Long.MAX_VALUE))
        val resumed = engine.play(paused, 1_000_000)
        assertEquals(4_004L, engine.tick(resumed, 1_000_001).elapsedQuarterMillis)
    }

    @Test fun `snapshot captures foreground progress but never stores running or clock state`() {
        val engine = engine()
        val running = engine.play(engine.start(speed = PlaybackSpeed.FAST), 50)
        val snapshot = engine.snapshot(running, 1_051)
        assertEquals(5_005L, snapshot.elapsedQuarterMillis)
        val restored = requireNotNull(engine.restore(snapshot))
        assertEquals(PlaybackSpeed.FAST, restored.speed)
        assertFalse(restored.isPlaying)
        assertNull(restored.anchorMonotonicMillis)
        assertEquals(restored, engine.tick(restored, Long.MAX_VALUE))
        assertEquals(restored.elapsedQuarterMillis + 5, engine.tick(engine.play(restored, 0), 1).elapsedQuarterMillis)
    }

    @Test fun `restore rejects wrong plan version indexes and offsets instead of silently selecting text`() {
        val engine = engine()
        val snapshot = engine.snapshot(engine.start(), 0)
        assertNull(engine.restore(snapshot.copy(contentVersion = snapshot.contentVersion + 1)))
        assertNull(engine.restore(snapshot.copy(planFingerprint = "another")))
        assertNull(engine.restore(snapshot.copy(segmentIndex = -1)))
        assertNull(engine.restore(snapshot.copy(segmentIndex = engine.plan.segments.size)))
        assertNull(engine.restore(snapshot.copy(elapsedQuarterMillis = -1)))
        assertNull(engine.restore(snapshot.copy(elapsedQuarterMillis = 16_000)))
        assertNull(engine.restore(snapshot.copy(segmentIndex = engine.plan.segments.lastIndex, elapsedQuarterMillis = 1)))
        val otherDate = ServicePlaybackEngine(ServicePlaybackPlan.build(listOf(stage, second), date.plusDays(1), null))
        assertNull(otherDate.restore(snapshot))
    }

    @Test fun `large tick cannot jump a missing reading and leftover time is discarded`() {
        val engine = engine(listOf(stage.copy(id = "epistle"), second))
        val missing = engine.tick(engine.play(engine.start(), 0), 9_000_000)
        assertTrue(engine.plan.segments[missing.segmentIndex] is PlaybackSegment.MissingReading)
        assertFalse(missing.isPlaying)
        assertEquals(0L, missing.elapsedQuarterMillis)
        assertEquals(missing, engine.play(missing, Long.MAX_VALUE))
        assertEquals(missing, engine.tick(missing, Long.MAX_VALUE))
        val next = engine.continueAfterMissing(missing)
        assertEquals("second", engine.plan.stageAt(next.segmentIndex).id)
        assertFalse(next.isPlaying)
        assertEquals(0L, next.elapsedQuarterMillis)
        val resumed = engine.tick(engine.play(next, 9_000_001), 9_000_002)
        assertEquals(4L, resumed.elapsedQuarterMillis)
        assertEquals("second", engine.plan.stageAt(resumed.segmentIndex).id)
    }

    @Test fun `adjacent missing readings each require explicit acknowledgement`() {
        val engine = engine(listOf(stage.copy(id = "epistle", texts = emptyList()), second.copy(id = "gospel", texts = emptyList())))
        val first = engine.start()
        assertEquals(first, engine.play(first, 0))
        val next = engine.continueAfterMissing(first)
        assertTrue(engine.plan.segments[next.segmentIndex] is PlaybackSegment.MissingReading)
        assertFalse(next.isPlaying)
        assertEquals(next, engine.play(next, Long.MAX_VALUE))
        val end = engine.continueAfterMissing(next)
        assertTrue(engine.plan.segments[end.segmentIndex] is PlaybackSegment.End)
        assertEquals(end, engine.continueAfterMissing(end))
    }

    @Test fun `huge monotonic delta and large budgets do not wrap and still respect barriers`() {
        val huge = stage.copy(durationMin = Int.MAX_VALUE)
        val engine = engine(listOf(huge, second.copy(id = "epistle")))
        val stopped = engine.tick(engine.play(engine.start(speed = PlaybackSpeed.FAST), 0), Long.MAX_VALUE)
        assertTrue(engine.plan.segments[stopped.segmentIndex] is PlaybackSegment.MissingReading)
        assertFalse(stopped.isPlaying)
        val terminalEngine = engine(listOf(huge, second))
        val end = terminalEngine.tick(terminalEngine.play(terminalEngine.start(speed = PlaybackSpeed.FAST), 0), Long.MAX_VALUE)
        assertTrue(terminalEngine.plan.segments[end.segmentIndex] is PlaybackSegment.End)
        assertFalse(end.isPlaying)
    }

    @Test fun `backwards or negative monotonic time pauses without replaying previously counted time`() {
        val engine = engine()
        val playing = engine.tick(engine.play(engine.start(), 1_000), 2_000)
        val backwards = engine.tick(playing, 1_999)
        assertEquals(4_000L, backwards.elapsedQuarterMillis)
        assertFalse(backwards.isPlaying)
        assertEquals(backwards, engine.tick(backwards, 3_000))
        assertFalse(engine.tick(playing, -1).isPlaying)
        assertEquals(backwards, engine.play(backwards, -1))
    }

    @Test fun `repeated play cannot restart a clock anomaly or reset running progress`() {
        val engine = engine()
        val playing = engine.play(engine.start(), 1_000)
        val repeated = engine.play(playing, 2_000)
        assertEquals(4_000L, repeated.elapsedQuarterMillis)
        assertTrue(repeated.isPlaying)
        val backwardsPlay = engine.play(repeated, 1_999)
        assertFalse(backwardsPlay.isPlaying)
        assertEquals(4_000L, backwardsPlay.elapsedQuarterMillis)
    }

    @Test fun `manual seeks pause reset only their new offset and keep speed`() {
        val engine = engine()
        val running = engine.play(engine.start(speed = PlaybackSpeed.SLOW), 0)
        val stageSeek = engine.seekStage(running, "second")
        assertFalse(stageSeek.isPlaying)
        assertEquals(PlaybackSpeed.SLOW, stageSeek.speed)
        assertEquals(0L, stageSeek.elapsedQuarterMillis)
        val line = engine.plan.segments.first() as PlaybackSegment.Text
        val lineSeek = engine.seekLine(stageSeek, line.trackId, line.lineId)
        assertEquals(engine.start(speed = PlaybackSpeed.SLOW), lineSeek)
        assertThrows(IllegalArgumentException::class.java) { engine.seekStage(lineSeek, "unknown") }
        assertThrows(IllegalArgumentException::class.java) { engine.seekLine(lineSeek, line.trackId, "unknown") }
    }

    @Test fun `End is terminal until an explicit seek and speed change never starts paused content`() {
        val engine = engine()
        val end = engine.tick(engine.play(engine.start(), 0), 120_000)
        assertEquals(end, engine.play(end, 130_000))
        val fasterEnd = engine.changeSpeed(end, PlaybackSpeed.FAST, 140_000)
        assertFalse(fasterEnd.isPlaying)
        assertTrue(engine.plan.segments[fasterEnd.segmentIndex] is PlaybackSegment.End)
        val restart = engine.seekStage(fasterEnd, "first")
        assertFalse(restart.isPlaying)
        assertEquals(0, restart.segmentIndex)
        assertTrue(engine.play(restart, 150_000).isPlaying)
    }

    @Test fun `speed change crossing a boundary honors the old speed and cannot pass missing content`() {
        val engine = engine(listOf(stage.copy(id = "epistle"), second))
        val start = engine.play(engine.start(), 0)
        val changed = engine.changeSpeed(start, PlaybackSpeed.SLOW, 4_000)
        assertTrue(engine.plan.segments[changed.segmentIndex] is PlaybackSegment.MissingReading)
        assertFalse(changed.isPlaying)
        assertEquals(PlaybackSpeed.SLOW, changed.speed)
    }

    @Test fun `runtime positions from other plans or illegal barriers fail explicitly`() {
        val engine = engine()
        val state = engine.start()
        assertFalse(engine.isValid(state.copy(planFingerprint = "other")))
        assertThrows(IllegalArgumentException::class.java) { engine.tick(state.copy(planFingerprint = "other"), 0) }
        assertFalse(engine.isValid(state.copy(segmentIndex = engine.plan.segments.lastIndex, anchorMonotonicMillis = 0)))
        assertFalse(engine.isValid(state.copy(anchorMonotonicMillis = -1)))
    }
}
