package ru.liturgyguide.service

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.DayOfWeek
import java.time.LocalDate

/** An orientation aid, deliberately not a ParishService or a source for the live estimate. */
data class TypicalService(val day: DayOfWeek, val period: String, val title: String)

object TypicalSchedule {
    val week = listOf(
        TypicalService(DayOfWeek.SATURDAY, "Вечером", "Вечернее богослужение"),
        TypicalService(DayOfWeek.SUNDAY, "Утром", "Литургия")
    )

    fun forDate(date: LocalDate): List<TypicalService> = week.filter { it.day == date.dayOfWeek }
}

/** Use the same validity boundaries in both the church page and the repository. */
object PublishedSchedule {
    fun forDate(church: Parish, date: LocalDate): List<ParishService> {
        val from = ScheduleTime.date(church.scheduleValidFrom) ?: return emptyList()
        val until = ScheduleTime.date(church.scheduleValidUntil) ?: return emptyList()
        if (until.isBefore(from) || date.isBefore(from) || date.isAfter(until)) return emptyList()
        return church.schedule.filter {
            it.date == date.toString() && it.title.isNotBlank() && ScheduleTime.publishedTime(it.time) != null
        }.sortedBy { ScheduleTime.publishedTime(it.time) }
    }
}
