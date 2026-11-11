package ru.liturgyguide.service.discovery

import com.skedgo.converter.TimezoneMapper
import java.time.ZoneId

/** The selected church's coordinates, never the travelling reader's phone zone. */
object ChurchTimeZone {
    fun at(point: GeoPoint): String? {
        if (!point.isValid) return null
        return runCatching {
            val id = TimezoneMapper.latLngToTimezoneString(point.latitude, point.longitude)
            if (id == "unknown") null else ZoneId.of(id).id
        }.getOrNull()
    }
}
