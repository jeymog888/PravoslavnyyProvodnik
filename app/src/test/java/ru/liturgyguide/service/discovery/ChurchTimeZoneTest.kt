package ru.liturgyguide.service.discovery

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class ChurchTimeZoneTest {
    @Test fun churchTimeDoesNotFollowTheTravellingPhone() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("Europe/Moscow", ChurchTimeZone.at(GeoPoint(55.76556, 37.62972)))
            assertEquals("Europe/Helsinki", ChurchTimeZone.at(GeoPoint(60.1699, 24.9384)))
            assertEquals("Asia/Yekaterinburg", ChurchTimeZone.at(GeoPoint(56.8389, 60.6057)))
            assertEquals("Asia/Vladivostok", ChurchTimeZone.at(GeoPoint(43.1155, 131.8855)))
            assertEquals("Europe/Kaliningrad", ChurchTimeZone.at(GeoPoint(54.7104, 20.4522)))
        } finally { TimeZone.setDefault(before) }
    }

    @Test fun parishCivilDayAndDaylightSavingComeFromItsOwnZone() {
        val helsinki = ZoneId.of(requireNotNull(ChurchTimeZone.at(GeoPoint(60.1699, 24.9384))))
        val instant = Instant.parse("2026-11-10T21:30:00Z")
        assertEquals("2026-11-10", instant.atZone(helsinki).toLocalDate().toString())
        assertEquals("2026-11-11", instant.atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString())
        assertEquals(7200, helsinki.rules.getOffset(instant).totalSeconds)
        assertEquals(10800, helsinki.rules.getOffset(Instant.parse("2026-07-10T21:30:00Z")).totalSeconds)
    }

    @Test fun invalidCoordinatesNeverBecomeThePhoneZone() {
        assertNull(ChurchTimeZone.at(GeoPoint(Double.NaN, 0.0)))
        assertNull(ChurchTimeZone.at(GeoPoint(91.0, 37.0)))
        assertNull(ChurchTimeZone.at(GeoPoint(55.0, -181.0)))
    }
}
