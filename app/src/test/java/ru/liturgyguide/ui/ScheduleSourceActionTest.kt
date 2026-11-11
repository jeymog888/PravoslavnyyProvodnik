package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus

class ScheduleSourceActionTest {
    private val parish = Parish("osm:node:123", "Храм Троицы", "Храм Троицы", "Москва", "", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "https://parish.example/")
        .copy(sourceUrl = "https://www.openstreetmap.org/node/123", website = "https://parish.example/")

    @Test fun failedScheduleLinksToTheDiscoveredSourceInsteadOfTheMapEvidence() {
        val state = InternetScheduleState(parish.id, InternetScheduleStatus.UNSUPPORTED,
            website = "https://current.example/", sourceUrl = "https://current.example/services/")
        assertEquals("https://current.example/services/", scheduleSourceFor(parish, state))
        assertEquals("https://current.example/", scheduleSourceFor(parish, state.copy(sourceUrl = "")))
        assertEquals("https://parish.example/", scheduleSourceFor(parish, null))
    }

    @Test fun anotherParishAndPlaceEvidenceCannotBecomeTheScheduleButton() {
        val state = InternetScheduleState("another-parish", InternetScheduleStatus.READY, sourceUrl = "https://other.example/")
        assertEquals("https://parish.example/", scheduleSourceFor(parish, state))
        assertNull(scheduleSourceFor(parish.copy(website = ""), state))
        assertNull(scheduleSourceFor(parish.copy(website = "javascript:alert(1)"), null))
    }

    @Test fun errorWithoutAUsableSiteDoesNotSendTheReaderToAnAbsentLink() {
        for (status in listOf(InternetScheduleStatus.UNAVAILABLE, InternetScheduleStatus.UNSUPPORTED)) {
            val state = InternetScheduleState(parish.id, status)
            assertFalse(scheduleEmptyDescription(state, false).contains("сайт"))
            assertFalse(scheduleEmptyDescription(state, false).contains("источник"))
            assertTrue(scheduleEmptyDescription(state, false).contains("текст", true))
        }
    }
}
