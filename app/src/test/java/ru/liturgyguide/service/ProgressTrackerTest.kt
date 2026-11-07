package ru.liturgyguide.service

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProgressTrackerTest {
    private val tracker = ProgressTracker(StageMatcher(testGuideJson()))

    @Test
    fun `no selection or a stale saved id does not claim a current stage`() {
        listOf(null, "deleted-stage", "Второй этап").forEach { id ->
            val progress = tracker.getProgress(id)

            assertNull(progress.currentStage)
            assertEquals(0, progress.stageNumber)
            assertEquals(0, progress.progressPercent)
            assertEquals(3, progress.totalStages)
            assertNull(progress.previousStage)
            assertEquals("first", progress.nextStage?.id)
        }
    }

    @Test
    fun `manual jump and return use selection without a running clock`() {
        val last = tracker.getProgress("third")
        val middle = tracker.getProgress("second")
        val first = tracker.getProgress("first")

        assertEquals(3, last.stageNumber)
        assertEquals(100, last.progressPercent)
        assertNull(last.nextStage)
        assertEquals("second", last.previousStage?.id)
        assertEquals(2, middle.stageNumber)
        assertEquals(66, middle.progressPercent)
        assertEquals("first", middle.previousStage?.id)
        assertEquals("third", middle.nextStage?.id)
        assertEquals(1, first.stageNumber)
        assertEquals(33, first.progressPercent)
        assertNull(first.previousStage)
        assertEquals("second", first.nextStage?.id)
    }

    @Test
    fun `a single stage has safe boundaries and a bounded position`() {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        val stages = source.getAsJsonArray("stages")
        stages.remove(2)
        stages.remove(1)
        val singleTracker = ProgressTracker(StageMatcher(source.toString()))
        val progress = singleTracker.getProgress("first")

        assertEquals(1, progress.stageNumber)
        assertEquals(1, progress.totalStages)
        assertEquals(100, progress.progressPercent)
        assertNull(progress.previousStage)
        assertNull(progress.nextStage)
    }

    @Test
    fun `illustrative duration never determines selected stage or position`() {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        source.getAsJsonArray("stages")[0].asJsonObject.addProperty("duration_min", 1000)
        val alteredTracker = ProgressTracker(StageMatcher(source.toString()))

        assertEquals(tracker.getProgress("second").stageNumber,
            alteredTracker.getProgress("second").stageNumber)
        assertEquals(tracker.getProgress("second").progressPercent,
            alteredTracker.getProgress("second").progressPercent)
    }
}
