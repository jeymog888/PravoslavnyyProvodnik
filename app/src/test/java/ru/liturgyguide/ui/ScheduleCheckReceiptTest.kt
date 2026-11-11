package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.SchedulePublication

class ScheduleCheckReceiptTest {
    private val publication = SchedulePublication("church", "Europe/Moscow", "https://example.org/", "https://example.org/",
        "2026-11-10T10:00:00Z", emptyList())
    private val state = InternetScheduleState("church", InternetScheduleStatus.UNAVAILABLE,
        checkedAt = "2026-11-11T10:00:01Z", publication = publication)

    @Test fun failedRefreshNeverClaimsSuccessBecauseAnOlderPublicationWasKept() {
        val receipt = scheduleCheckReceipt(state, "Europe/Moscow")
        assertEquals("Обновление не удалось\nПопытка обновления: 11 ноября 2026, 13:00:01", receipt)
        assertFalse(requireNotNull(receipt).contains("Расписание получено"))
        assertEquals("Расписание получено\nПопытка обновления: 11 ноября 2026, 13:00:01",
            scheduleCheckReceipt(state.copy(status = InternetScheduleStatus.READY), "Europe/Moscow"))
        assertEquals("Расписание не получено\nПопытка обновления: 11 ноября 2026, 13:00:01",
            scheduleCheckReceipt(state.copy(status = InternetScheduleStatus.READY, publication = null), "Europe/Moscow"))
    }

    @Test fun incompleteOrInvalidAttemptsHaveNoCompletionReceipt() {
        assertNull(scheduleCheckReceipt(null, "Europe/Moscow"))
        assertNull(scheduleCheckReceipt(state.copy(loading = true), "Europe/Moscow"))
        assertNull(scheduleCheckReceipt(state.copy(status = InternetScheduleStatus.IDLE), "Europe/Moscow"))
        assertNull(scheduleCheckReceipt(state.copy(checkedAt = ""), "Europe/Moscow"))
        assertNull(scheduleCheckReceipt(state.copy(checkedAt = "not-a-time"), "Europe/Moscow"))
        assertNull(scheduleCheckReceipt(state, "invalid-zone"))
    }

    @Test fun receiptUsesTheParishTimezoneIncludingTheLocalCalendarDate() {
        assertEquals("Обновление не удалось\nПопытка обновления: 12 ноября 2026, 02:00:01",
            scheduleCheckReceipt(state.copy(checkedAt = "2026-11-11T16:00:01Z"), "Asia/Vladivostok"))
    }

    @Test fun rejectedPublicationIsNotDescribedAsATextExtractionFailure() {
        assertEquals("Расписание не подтверждено источником\nПопытка обновления: 11 ноября 2026, 13:00:01",
            scheduleCheckReceipt(state.copy(status = InternetScheduleStatus.UNSUPPORTED, publicationRejected = true), "Europe/Moscow"))
        assertEquals("Не удалось прочитать даты и время\nПопытка обновления: 11 ноября 2026, 13:00:01",
            scheduleCheckReceipt(state.copy(status = InternetScheduleStatus.UNSUPPORTED, publicationRejected = false), "Europe/Moscow"))
    }
}
