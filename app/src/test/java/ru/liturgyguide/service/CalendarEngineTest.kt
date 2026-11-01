package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarEngineTest {

    private val engine = CalendarEngine()

    @Test
    fun `getDay returns correct day name for known date`() {
        val result = engine.getDay("2026-11-01")

        assertEquals("Воскресенье", result.dayOfWeek)
        assertEquals("8:00 Литургия Молебен", result.scheduleToday)
    }

    @Test
    fun `getDay returns correct day name for monday`() {
        val result = engine.getDay("2026-11-02")

        assertEquals("Понедельник", result.dayOfWeek)
        assertNull(result.scheduleToday)
    }

    @Test
    fun `getDay returns feast day info`() {
        val result = engine.getDay("2026-11-04")

        assertEquals("Казанская икона Божией Матери", result.feast)
    }

    @Test
    fun `getDay returns fasting day info`() {
        val result = engine.getDay("2026-11-28")

        assertEquals("Начало Рождественского поста", result.fasting)
    }

    @Test
    fun `getDay returns typical service`() {
        val result = engine.getDay("2026-11-01")

        assertEquals("Литургия Иоанна Златоуста", result.typicalService)
    }

    @Test
    fun `getDay handles date without schedule`() {
        val result = engine.getDay("2026-11-05")

        assertEquals("Среда", result.dayOfWeek)
        assertNull(result.scheduleToday)
        assertNull(result.feast)
        assertNull(result.fasting)
    }
}