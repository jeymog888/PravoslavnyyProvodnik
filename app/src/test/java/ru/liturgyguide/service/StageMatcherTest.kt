package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileWriter

class StageMatcherTest {

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
              "texts": [
                {"type": "молитва", "original": "Текст 1", "translation": "Перевод 1", "explanation": "Пояснение 1"}
              ]
            },
            {
              "id": "test2",
              "order": 2,
              "name": "Второй этап",
              "description": "Второй тестовый этап",
              "duration_min": 10,
              "texts": [
                {"type": "возглас", "original": "Текст 2", "translation": "Перевод 2", "explanation": "Пояснение 2"}
              ]
            },
            {
              "id": "test3",
              "order": 3,
              "name": "Третий этап",
              "description": "Третий тестовый этап",
              "duration_min": 10,
              "texts": [
                {"type": "ектения", "original": "Текст 3", "translation": "Перевод 3", "explanation": "Пояснение 3"}
              ]
            }
          ]
        }
    """.trimIndent()

    private fun createTestMatcher(): StageMatcher = StageMatcher(testJson)

    @Test
    fun `getAllStages returns all stages`() {
        val matcher = createTestMatcher()
        val stages = matcher.getAllStages()

        assertEquals(3, stages.size)
        assertEquals("Первый этап", stages[0].name)
        assertEquals("Второй этап", stages[1].name)
        assertEquals("Третий этап", stages[2].name)
    }

    @Test
    fun `getStage finds stage by name`() {
        val matcher = createTestMatcher()
        val stage = matcher.getStage("Второй этап")

        assertNotNull(stage)
        assertEquals("Второй этап", stage?.name)
        assertEquals(2, stage?.order)
    }

    @Test
    fun `getStage returns null for unknown name`() {
        val matcher = createTestMatcher()
        val stage = matcher.getStage("Несуществующий")

        assertNull(stage)
    }

    @Test
    fun `getStageByOrder finds stage by order`() {
        val matcher = createTestMatcher()
        val stage = matcher.getStageByOrder(3)

        assertNotNull(stage)
        assertEquals("Третий этап", stage?.name)
    }

    @Test
    fun `getNextStage returns next stage`() {
        val matcher = createTestMatcher()
        val next = matcher.getNextStage("Первый этап")

        assertNotNull(next)
        assertEquals("Второй этап", next?.name)
    }

    @Test
    fun `getNextStage returns null for last stage`() {
        val matcher = createTestMatcher()
        val next = matcher.getNextStage("Третий этап")

        assertNull(next)
    }

    @Test
    fun `getTotalDuration returns correct total`() {
        val matcher = createTestMatcher()
        val total = matcher.getTotalDuration()

        assertEquals(30, total)
    }

    @Test
    fun `getServiceName returns service name`() {
        val matcher = createTestMatcher()
        val name = matcher.getServiceName()

        assertEquals("Тестовая Литургия", name)
    }
}