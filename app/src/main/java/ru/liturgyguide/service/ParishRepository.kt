package ru.liturgyguide.service

import com.google.gson.Gson
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishDirectory
import ru.liturgyguide.model.ParishService
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ParishRepository(json: String) {
    val directory: ParishDirectory = Gson().fromJson(json, ParishDirectory::class.java).also { data ->
        requireNotNull(data)
        require(data.churches.isNotEmpty())
        require(data.churches.map { it.id }.distinct().size == data.churches.size)
        require(data.churches.any { it.id == data.defaultChurchId })
        data.churches.forEach { church ->
            require(church.name.isNotBlank() && church.address.isNotBlank())
            require(church.sourceUrl.startsWith("https://") && church.website.startsWith("https://"))
            ZoneId.of(church.timezone)
            val from = LocalDate.parse(church.scheduleValidFrom)
            val until = LocalDate.parse(church.scheduleValidUntil)
            require(!until.isBefore(from))
            church.schedule.forEach { service ->
                val date = LocalDate.parse(service.date)
                require(!date.isBefore(from) && !date.isAfter(until))
                LocalTime.parse(service.time)
                require(service.title.isNotBlank())
            }
        }
    }
    fun selected(id: String?): Parish = directory.churches.firstOrNull { it.id == id }
        ?: directory.churches.first { it.id == directory.defaultChurchId }
    fun scheduleFor(church: Parish, date: LocalDate): List<ParishService> {
        if (church.schedule.isEmpty()) return emptyList()
        val from = runCatching { LocalDate.parse(church.scheduleValidFrom) }.getOrNull() ?: return emptyList()
        val until = runCatching { LocalDate.parse(church.scheduleValidUntil) }.getOrNull() ?: return emptyList()
        return if (date.isBefore(from) || date.isAfter(until)) emptyList()
            else church.schedule.filter { it.date == date.toString() }.sortedBy { it.time }
    }
}
