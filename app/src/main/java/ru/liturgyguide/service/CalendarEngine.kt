package ru.liturgyguide.service

import ru.liturgyguide.model.ChurchDay
import java.time.LocalDate
import java.time.DayOfWeek

class CalendarEngine {

    private val fastingDays = mapOf(
        "2026-11-26" to "Заговенье на Рождественский пост",
        "2026-11-28" to "Начало Рождественского поста"
    )

    private val feastDays = mapOf(
        "2026-11-04" to "Казанская икона Божией Матери",
        "2026-11-08" to "Вмч. Димитрия Солунского",
        "2026-11-15" to "Памяти жертв ДТП",
        "2026-11-21" to "Собор Архистратига Михаила",
        "2026-11-22" to "Свт. Нектария Эгинского. Иконы Божией Матери \"Скоропослушница\"",
        "2026-11-29" to "Ап. и ев. Матфея"
    )

    private val schedule = mapOf(
        "2026-11-01" to "8:00 Литургия Молебен",
        "2026-11-03" to "17:00 Всенощная с литией",
        "2026-11-04" to "8:00 Литургия Молебен",
        "2026-11-06" to "17:00 Вечерня",
        "2026-11-07" to "8:00 Литургия Великая панихида",
        "2026-11-08" to "8:00 Литургия Молебен",
        "2026-11-15" to "8:00 Литургия Молебен Литья",
        "2026-11-22" to "8:00 Литургия Молебен",
        "2026-11-28" to "8:00 Литургия Панихида",
        "2026-11-29" to "8:00 Литургия Молебен"
    )

    fun getToday(): ChurchDay {
        val today = LocalDate.now()
        return getDay(today.toString())
    }

    fun getDay(date: String): ChurchDay {
        val localDate = LocalDate.parse(date)
        val dayOfWeek = localDate.dayOfWeek
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "Понедельник",
            DayOfWeek.TUESDAY to "Вторник",
            DayOfWeek.WEDNESDAY to "Среда",
            DayOfWeek.THURSDAY to "Четверг",
            DayOfWeek.FRIDAY to "Пятница",
            DayOfWeek.SATURDAY to "Суббота",
            DayOfWeek.SUNDAY to "Воскресенье"
        )

        return ChurchDay(
            date = date,
            dayOfWeek = dayNames[dayOfWeek] ?: "Неизвестно",
            fasting = fastingDays[date],
            feast = feastDays[date],
            typicalService = "Литургия Иоанна Златоуста",
            scheduleToday = schedule[date]
        )
    }
}