package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.*
import java.time.LocalDate

class ContinuousServiceFeedTest {
    private fun plan(): ServicePlaybackPlan = ServicePlaybackPlan.build(
        StageMatcher(Published05Fixtures.asset("liturgy_stages.json")).getAllStages(), LocalDate.of(2026, 11, 10), null)

    @Test fun `the book contains every stage and every available canonical line in order`() {
        val plan = plan()
        val feed = ContinuousServiceFeed(plan)
        assertEquals(plan.stages, feed.items.filterIsInstance<ServiceBookItem.Stage>().map { it.stage })
        assertEquals(plan.segments.filterIsInstance<PlaybackSegment.Text>().map { it.lineId },
            feed.items.filterIsInstance<ServiceBookItem.Line>().map { it.line.id })
        assertEquals(feed.items.size, feed.items.map { it.key }.distinct().size)
        assertTrue(feed.items.last() is ServiceBookItem.End)
        val firstLine = feed.items.first { it is ServiceBookItem.Line }
        val lastLine = feed.items.last { it is ServiceBookItem.Line }
        assertNotEquals(firstLine.stageId, lastLine.stageId)
    }

    @Test fun `every engine position maps to a real row including missing readings and preparation`() {
        val plan = plan()
        val feed = ContinuousServiceFeed(plan)
        plan.segments.forEachIndexed { index, segment ->
            val item = feed.items[feed.targetIndex(segment)]
            assertEquals(segment.stageId, item.stageId)
            when (segment) {
                is PlaybackSegment.Text -> {
                    assertTrue(item is ServiceBookItem.Line)
                    assertEquals(index, feed.segmentIndex(item))
                    assertEquals(segment.lineId, (item as ServiceBookItem.Line).line.id)
                }
                is PlaybackSegment.MissingReading -> assertTrue(item is ServiceBookItem.Missing)
                is PlaybackSegment.End -> assertTrue(item is ServiceBookItem.End)
                is PlaybackSegment.Wait -> assertTrue(item is ServiceBookItem.Transition || item is ServiceBookItem.Guidance)
            }
        }
    }

    @Test fun `a missing daily reading remains in the book and does not hide the following stages`() {
        val plan = plan()
        val feed = ContinuousServiceFeed(plan)
        val missing = plan.segments.filterIsInstance<PlaybackSegment.MissingReading>()
        assertEquals(listOf("epistle", "gospel"), missing.map { it.stageId })
        missing.forEach { segment ->
            val index = feed.targetIndex(segment)
            assertTrue(feed.items[index] is ServiceBookItem.Missing)
            assertTrue(feed.items.drop(index + 1).any { it is ServiceBookItem.Stage && it.stageId != segment.stageId })
            val engine = ServicePlaybackEngine(plan)
            val state = engine.start(segment.stageId)
            val stopped = engine.tick(engine.play(state, 0), Long.MAX_VALUE)
            assertEquals(segment, plan.segments[stopped.segmentIndex])
            assertFalse(stopped.isPlaying)
        }
    }

    @Test fun `switching language does not change keys stage or the canonical phrase`() {
        val feed = ContinuousServiceFeed(plan())
        feed.items.filterIsInstance<ServiceBookItem.Line>().forEach { item ->
            val before = feed.indexOf(item.key)
            assertEquals(item.line.russian, readerLineText(item.line, false))
            assertEquals(item.line.original ?: item.line.russian, readerLineText(item.line, true))
            assertEquals(before, feed.indexOf(item.key))
            assertEquals(item.stageId, feed.visibleItem(requireNotNull(before)).stageId)
        }
    }

    @Test fun `an inactive transition resolves inside its own stage rather than to an old bookmark`() {
        val feed = ContinuousServiceFeed(plan())
        val transitions = feed.items.filterIsInstance<ServiceBookItem.Transition>()
        assertTrue(transitions.isNotEmpty())
        transitions.forEach { item ->
            val before = feed.readableBefore(item)
            assertEquals(item.stageId, before.stageId)
            assertTrue(before is ServiceBookItem.Line)
            assertTrue(requireNotNull(feed.indexOf(before.key)) < requireNotNull(feed.indexOf(item.key)))
        }
    }

    @Test fun `language switching keeps relative progress within a long paragraph and clamps invalid geometry`() {
        assertEquals(240, translatedParagraphOffset(120, 400, 800))
        assertEquals(60, translatedParagraphOffset(120, 400, 200))
        assertEquals(0, translatedParagraphOffset(-10, 400, 800))
        assertEquals(799, translatedParagraphOffset(999, 400, 800))
        assertEquals(0, translatedParagraphOffset(100, 0, 800))
        assertEquals(0, translatedParagraphOffset(100, 400, 0))
    }

    @Test fun `a tall paragraph reaches its last line before timed playback moves to the next phrase`() {
        assertEquals(0, timedParagraphOffset(1_000, 400, 0, 10_000))
        assertEquals(300, timedParagraphOffset(1_000, 400, 17_000, 10_000))
        assertEquals(600, timedParagraphOffset(1_000, 400, 34_000, 10_000))
        assertEquals(600, timedParagraphOffset(1_000, 400, 39_999, 10_000))
        assertEquals(200, timedParagraphOffset(1_000, 400, 0, 10_000, startOffset = 200))
        assertEquals(0, timedParagraphOffset(200, 400, 39_999, 10_000))
    }

    @Test fun `short exchanges have no empty heading rows while scripture and essential notes keep their headings`() {
        val feed = ContinuousServiceFeed(plan())
        val hidden = feed.plan.tracksByStage.values.flatten().filterNot(::bookShowsTrackHeading)
        assertTrue("A spoken dialogue should not repeat headings for every response", hidden.isNotEmpty())
        hidden.forEach { track ->
            assertNull(feed.indexOf("track:${track.id}"))
            track.lines.forEach { assertNotNull(feed.indexOf("line:${it.id}")) }
        }
        feed.plan.tracksByStage.values.flatten().filter(::bookShowsTrackHeading).forEach { track ->
            assertNotNull(feed.indexOf("track:${track.id}"))
        }
        val sample = feed.plan.tracksByStage.values.flatten().first()
        assertTrue(bookShowsTrackHeading(sample.copy(isScripture = true, lines = sample.lines.take(1), contextNote = null)))
        assertTrue(bookShowsTrackHeading(sample.copy(lines = sample.lines.take(1), contextNote = "Местное поминовение")))
    }

    @Test fun `resuming a partially elapsed paragraph never applies its elapsed progress twice`() {
        val before = timedParagraphOffset(1_000, 400, 17_000, 10_000)
        assertEquals(300, before)
        assertEquals(300, timedParagraphOffset(1_000, 400, 17_000, 10_000,
            startOffset = before, startElapsedQuarterMillis = 17_000))
        assertEquals(450, timedParagraphOffset(1_000, 400, 26_775, 10_000,
            startOffset = before, startElapsedQuarterMillis = 17_000))
        assertEquals(600, timedParagraphOffset(1_000, 400, 36_550, 10_000,
            startOffset = before, startElapsedQuarterMillis = 17_000))
    }
}
