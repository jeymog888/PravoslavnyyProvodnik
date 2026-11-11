package ru.liturgyguide.service

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class FeaturedService(val service: ParishService, val date: LocalDate, val mayBeInProgress: Boolean)

object TodayServiceSelection {
    fun pick(parish: Parish, selectedDate: LocalDate, now: Instant, lookAhead: Boolean): FeaturedService? {
        val zone = runCatching { ZoneId.of(parish.timezone) }.getOrNull() ?: return null
        val today = now.atZone(zone).toLocalDate()
        val todayServices = PublishedSchedule.forDate(parish, selectedDate)
        if (selectedDate != today) return todayServices.firstOrNull()?.let { FeaturedService(it, selectedDate, false) }
        fun instant(service: ParishService): Instant? = runCatching {
            LocalDate.parse(service.date).atTime(LocalTime.parse(service.time)).atZone(zone).toInstant()
        }.getOrNull()
        val recent = todayServices.lastOrNull { service -> instant(service)?.let { start ->
            val elapsed = Duration.between(start, now)
            !elapsed.isNegative && elapsed <= Duration.ofHours(2)
        } == true }
        if (recent != null) return FeaturedService(recent, selectedDate, true)
        val next = (0L..if (lookAhead) 45L else 0L).asSequence().flatMap { offset ->
            PublishedSchedule.forDate(parish, selectedDate.plusDays(offset)).asSequence()
        }.firstOrNull { service -> instant(service)?.isAfter(now) == true }
        return next?.let { FeaturedService(it, LocalDate.parse(it.date), false) }
    }
}
