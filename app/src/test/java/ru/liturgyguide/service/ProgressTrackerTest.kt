package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.time.LocalTime

class ProgressTrackerTest {

    private val testJson = """
        {
          "service": "Тестовая Литургия",
          "total_duration_min": 30,
          "stages": [
            {
              "id": "test1",
              "order": 1,
              "name": "Первый этап",
              "description": "Первый тестовый этап",
              "duration_min": 10,
              "texts": []
            },
            {
              "id": "test2",
              "order": 2,
              "name": "Второй этап",
              "description": "Второй тестовый этап",
              "duration_min": 10,
              "texts": []
            },
            {
              "id": "test3",
              "order": 3,
              "name": "Третий этап",
              "description": "Третий тестовый этап",
              "duration_min": 10,
              "texts": []
            }
          ]
        }
    """.trimIndent()

    private fun createTracker(): ProgressTracker {
        val matcher = StageMatcher(testJson)
        return ProgressTracker(matcher)
    }

    @Test
    fun `getProgress returns correct stage number`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(5)
        val progress = tracker.getProgress(startTime, "Первый этап")

        assertEquals(1, progress.stageNumber)
        assertEquals(3, progress.totalStages)
        assertEquals("Первый этап", progress.currentStage?.name)
    }

    @Test
    fun `getProgress returns second stage`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(15)
        val progress = tracker.getProgress(startTime, "Второй этап")

        assertEquals(2, progress.stageNumber)
        assertEquals("Второй этап", progress.currentStage?.name)
    }

    @Test
    fun `getProgress returns next stage`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(5)
        val progress = tracker.getProgress(startTime, "Первый этап")

        assertNotNull(progress.nextStage)
        assertEquals("Второй этап", progress.nextStage?.name)
    }

    @Test
    fun `getProgress last stage has no next`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(25)
        val progress = tracker.getProgress(startTime, "Третий этап")

        assertNull(progress.nextStage)
    }

    @Test
    fun `getProgress auto-detects stage by time`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(25)
        val progress = tracker.getProgress(startTime, null)

        assertNotNull(progress.currentStage)
        assertEquals("Третий этап", progress.currentStage?.name)
    }

    @Test
    fun `getProgress returns zero stage for unknown name`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(5)
        val progress = tracker.getProgress(startTime, "Неизвестный этап")

        assertEquals(0, progress.stageNumber)
        assertNull(progress.currentStage)
    }

    @Test
    fun `getProgress elapsed time is non-negative`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(5)
        val progress = tracker.getProgress(startTime, "Первый этап")

        assertTrue(progress.elapsedMinutes >= 0)
        assertTrue(progress.remainingMinutes >= 0)
    }

    @Test
    fun `getProgress percent is between 0 and 100`() {
        val tracker = createTracker()
        val startTime = LocalTime.now().minusMinutes(5)
        val progress = tracker.getProgress(startTime, "Первый этап")

        assertTrue(progress.progressPercent >= 0)
        assertTrue(progress.progressPercent <= 100)
    }
}