package ru.liturgyguide.service

import ru.liturgyguide.model.ChurchDay
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class CalendarEngine(
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow"))
) {
    fun getToday(): ChurchDay = getDay(LocalDate.now(clock).toString())

    fun getDay(date: String): ChurchDay {
        val localDate = LocalDate.parse(date)
        val dayName = when (localDate.dayOfWeek) {
            DayOfWeek.MONDAY -> "Понедельник"
            DayOfWeek.TUESDAY -> "Вторник"
            DayOfWeek.WEDNESDAY -> "Среда"
            DayOfWeek.THURSDAY -> "Четверг"
            DayOfWeek.FRIDAY -> "Пятница"
            DayOfWeek.SATURDAY -> "Суббота"
            DayOfWeek.SUNDAY -> "Воскресенье"
        }

        // Parish schedules and the liturgical calendar need an up-to-date, verified source.
        // Unknown information stays absent instead of reusing an old demo schedule.
        return ChurchDay(date = localDate.toString(), dayOfWeek = dayName)
    }
}
