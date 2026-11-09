package ru.liturgyguide.ui

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.service.*
import java.time.LocalDate

class ReaderFeedTest {
    private fun plan(date: String = "2026-11-08"): ServicePlaybackPlan {
        val stages = StageMatcher(Published05Fixtures.asset("liturgy_stages.json")).getAllStages()
        val day = Gson().fromJson(Published05Fixtures.asset("daily_readings.json"), DailyReadings::class.java)
            .days.singleOrNull { it.date == date }
        return ServicePlaybackPlan.build(stages, LocalDate.parse(date), day)
    }

    @Test fun `every packaged stage has its ordered headers lines and exactly one continuation`() {
        for (date in listOf("2026-11-08", "2026-11-09")) {
            val plan = plan(date)
            val segments = plan.segments.toList()
            val fingerprint = plan.fingerprint
            plan.tracksByStage.forEach { (stage, tracks) ->
                val feed = ReaderFeed(stage, tracks)
                var index = 0
                tracks.forEach { track ->
                    assertEquals(ReaderFeedItem.Header(track), feed.items[index++])
                    track.lines.forEach { line -> assertEquals(ReaderFeedItem.Line(track.id, line), feed.items[index++]) }
                }
                assertEquals(index, feed.continuationIndex)
                assertEquals(ReaderFeedItem.Continuation(stage), feed.items[index])
                assertEquals(index + 1, feed.items.size)
                assertEquals(1, feed.items.count { it is ReaderFeedItem.Continuation })
                assertEquals(tracks.flatMap { it.lines }, feed.lines.map { it.line })
                assertEquals(feed.items.size, feed.items.map { it.key }.distinct().size)
            }
            assertEquals(segments, plan.segments)
            assertEquals(fingerprint, plan.fingerprint)
        }
    }

    @Test fun `empty preparation is only a continuation while expanded anaphora retains all seven headers`() {
        val plan = plan()
        val before = ReaderFeed("preparation", plan.tracksByStage.getValue("preparation"))
        assertEquals(listOf(ReaderFeedItem.Continuation("preparation")), before.items)
        assertEquals(0, before.continuationIndex)
        assertTrue(before.lines.isEmpty())
        assertFalse(before.supportsOriginal)
        val anaphora = ReaderFeed("anaphora", plan.tracksByStage.getValue("anaphora"))
        assertEquals(7, anaphora.items.count { it is ReaderFeedItem.Header })
        assertEquals(22, anaphora.lines.size)
    }

    @Test fun `actual text addresses map to their lines and non text segments never target a retained phrase`() {
        for (date in listOf("2026-11-08", "2026-11-09")) {
            val plan = plan(date)
            for (segment in plan.segments) {
                val feed = ReaderFeed(segment.stageId, plan.tracksByStage.getValue(segment.stageId))
                val target = feed.items[feed.targetIndex(segment)]
                if (segment is PlaybackSegment.Text) {
                    assertEquals(ReaderFeedItem.Line(segment.trackId, plan.line(segment)), target)
                    assertEquals(feed.lineIndex(segment.trackId, segment.lineId), feed.targetIndex(segment))
                    assertNull(feed.lineIndex("wrong-track", segment.lineId))
                    assertEquals(feed.continuationIndex, feed.targetIndex(segment.copy(stageId = "other-stage")))
                    assertEquals(feed.continuationIndex, feed.targetIndex(segment.copy(lineId = "unknown-line")))
                } else {
                    assertEquals(feed.continuationIndex, feed.targetIndex(segment))
                    assertTrue(target is ReaderFeedItem.Continuation)
                    assertNull(feed.adjacentLine(segment, 1))
                    assertNull(feed.adjacentLine(segment, -1))
                }
            }
        }
    }

    @Test fun `phrase navigation crosses tracks but never a stage boundary or missing reading`() {
        val plan = plan("2026-11-09")
        var trackBoundaries = 0
        plan.tracksByStage.forEach { (stage, tracks) ->
            val feed = ReaderFeed(stage, tracks)
            val texts = plan.segments.filterIsInstance<PlaybackSegment.Text>().filter { it.stageId == stage }
            texts.forEachIndexed { index, text ->
                val previous = feed.adjacentLine(text, -1)
                val next = feed.adjacentLine(text, 1)
                assertEquals(texts.getOrNull(index - 1)?.lineId, previous?.line?.id)
                assertEquals(texts.getOrNull(index + 1)?.lineId, next?.line?.id)
                assertNull(feed.adjacentLine(text, 0))
                assertNull(feed.adjacentLine(text, 2))
                assertNull(feed.adjacentLine(text.copy(stageId = "other-stage"), 1))
                if (next != null && next.trackId != text.trackId) trackBoundaries++
            }
        }
        assertTrue(trackBoundaries > 0)
    }

    @Test fun `mixed original keeps prayer originals while scripture stays honestly Russian`() {
        val plan = plan()
        val tracks = plan.tracksByStage.getValue("gospel")
        val prayer = tracks.first { !it.isScripture }
        val scripture = tracks.first { it.isScripture }
        assertTrue(ReaderFeed("gospel", tracks).supportsOriginal)
        assertEquals("Фрагмент молитвы · Церковнославянский текст", readerTrackLanguage(prayer.copy(isExcerpt = true), true))
        assertEquals("Полный текст · Церковнославянский текст", readerTrackLanguage(prayer.copy(isExcerpt = false), true))
        assertEquals("Фрагмент молитвы · Русское изложение смысла молитвы", readerTrackLanguage(prayer.copy(isExcerpt = true), false))
        assertEquals("Полный текст · Русское изложение смысла молитвы", readerTrackLanguage(prayer.copy(isExcerpt = false), false))
        assertEquals("${scripture.subtitle}. Церковнославянский текст пока не добавлен", readerTrackLanguage(scripture, true))
        assertEquals(readerTrackLanguage(scripture, true), readerTrackLanguage(scripture, false))
        prayer.lines.forEach { assertEquals(it.original, readerLineText(it, true)); assertEquals(it.russian, readerLineText(it, false)) }
        scripture.lines.forEach { assertNull(it.original); assertEquals(it.russian, readerLineText(it, true)) }
    }

    @Test fun `a scripture only feed cannot advertise an unavailable original`() {
        val scripture = plan().tracksByStage.getValue("gospel").first { it.isScripture }
        val feed = ReaderFeed("gospel", listOf(scripture))
        assertFalse(feed.supportsOriginal)
        assertTrue(readerTrackLanguage(scripture, true).contains("Церковнославянский текст пока не добавлен"))
        assertTrue(feed.lines.all { readerLineText(it.line, true) == it.line.russian })
    }

    @Test fun `identical words retain distinct identities and metadata does not create extra reading rows`() {
        val tracks = plan().tracksByStage.getValue("great_litany")
        val feed = ReaderFeed("great_litany", tracks)
        assertTrue(feed.lines.groupBy { it.line.original }.values.any { it.size > 1 })
        val rendered = ReaderFeed("great_litany", tracks.map { track -> track.copy(title = "Новое название",
            contextNote = "Длинное пояснение ".repeat(100), lines = track.lines.map { it.copy(russian = "Другой перевод", speaker = "Другой голос") }) })
        assertEquals(feed.items.map { it.key }, rendered.items.map { it.key })
        assertEquals(feed.lines.size, rendered.lines.size)
        assertEquals(feed.continuationIndex, rendered.continuationIndex)
    }

    @Test fun `visible means actual text intersects viewport including part of a long phrase`() {
        for ((top, bottom) in listOf(10f to 40f, -50f to 1f, 99f to 200f, -50f to 200f)) {
            assertTrue("$top..$bottom", readerTextIntersectsViewport(top, bottom, 0f, 100f))
        }
        for ((top, bottom) in listOf(-50f to 0f, 100f to 200f, 20f to 20f, 40f to 20f)) {
            assertFalse("$top..$bottom", readerTextIntersectsViewport(top, bottom, 0f, 100f))
        }
        assertFalse(readerTextIntersectsViewport(0f, 100f, 20f, 20f))
        assertFalse(readerTextIntersectsViewport(Float.NaN, 100f, 0f, 100f))
    }
}
