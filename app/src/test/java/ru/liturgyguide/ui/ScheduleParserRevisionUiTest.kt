package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import ru.liturgyguide.service.schedule.withInternetSchedule
import java.time.Instant
import java.time.LocalDate

class ScheduleParserRevisionUiTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val source = "https://parish.example/schedule/"
    private val church = Parish("revision-ui", "Троицкий храм", "Троицкий храм", "Москва", "Улица, 1", "",
        "https://parish.example/", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val today = InternetService(date.toString(), "08:00", "Божественная Литургия", "liturgy")
    private val tomorrow = today.copy(date = date.plusDays(1).toString(), time = "10:00")
    private val later = today.copy(date = date.plusDays(2).toString(), time = "12:00")
    private fun publication(revision: Int, fetched: Instant = now) = SchedulePublication(church.id, church.timezone,
        church.website, source, fetched.toString(), listOf(today, tomorrow, later), parserRevision = revision)
    private fun state(publication: SchedulePublication) = InternetScheduleState(church.id, InternetScheduleStatus.READY,
        website = church.website, sourceUrl = source, checkedAt = now.toString(), publication = publication)

    @Test fun noncurrentRowsCannotPopulateTodayTomorrowOrNextBeforeDuringOrAfterAFailedRefresh() {
        for (revision in listOf(SchedulePublication.LEGACY_PARSER_REVISION, SchedulePublication.CURRENT_PARSER_REVISION + 1)) {
            for (fetched in listOf(now, now.minusSeconds(48 * 3_600L))) {
                val initial = state(publication(revision, fetched))
                for (attempt in listOf(initial, initial.copy(loading = true),
                    initial.copy(status = InternetScheduleStatus.UNAVAILABLE, checkedAt = now.plusSeconds(1).toString()))) {
                    val before = attempt.copy()
                    // Match Main: always project from the original parish, never an earlier overlay.
                    val projected = church.withInternetSchedule(attempt, now)
                    assertTrue(projected.schedule.isEmpty())
                    for (selected in listOf(date.minusDays(1), date, date.plusDays(1))) {
                        for (clock in listOf(now, null)) {
                            val result = arrivalSchedule(projected, selected, clock, attempt)
                            assertTrue(result.entries.isEmpty())
                            assertNull(result.featured)
                            assertNull(result.relation)
                            assertNull(result.next)
                            assertNull(result.publication)
                            assertFalse(result.saved)
                        }
                    }
                    assertEquals(before, attempt)
                    assertSame(initial.publication, attempt.publication)
                }
            }
        }
    }

    @Test fun currentRevisionRetainsBothFreshProjectionAndAgeStaleSavedFallback() {
        val fresh = state(publication(SchedulePublication.CURRENT_PARSER_REVISION))
        val projected = church.withInternetSchedule(fresh, now)
        val active = arrivalSchedule(projected, date, now, fresh)
        assertEquals(listOf("08:00"), active.entries.map { it.time })
        assertFalse(active.saved)
        val stale = fresh.copy(status = InternetScheduleStatus.UNAVAILABLE,
            publication = fresh.publication!!.copy(fetchedAt = now.minusSeconds(48 * 3_600L).toString()))
        val original = church.withInternetSchedule(stale, now)
        assertTrue(original.schedule.isEmpty())
        val saved = arrivalSchedule(original, date, now, stale)
        assertEquals(listOf("08:00"), saved.entries.map { it.time })
        assertTrue(saved.saved)
        assertEquals("Сохраненное расписание - проверьте изменения", saved.relation)
        val onlyFuture = stale.copy(publication = stale.publication!!.copy(events = listOf(later)))
        val next = arrivalSchedule(church, date, null, onlyFuture)
        assertEquals(later.date, next.next?.service?.date)
        assertEquals(later.time, next.next?.service?.time)
        assertTrue(requireNotNull(next.next).saved)
    }

    @Test fun independentlyValidBundledRowsAreNotRemovedByANoncurrentInternetCache() {
        val bundled = ParishService(date.toString(), "10:30", "Литургия", "liturgy")
        val base = church.copy(scheduleValidFrom = date.toString(), scheduleValidUntil = date.plusDays(2).toString(),
            schedule = listOf(bundled), sourceUrl = "https://bundle.example/schedule/")
        val old = state(publication(SchedulePublication.LEGACY_PARSER_REVISION))
        val result = arrivalSchedule(base.withInternetSchedule(old, now), date, now, old)
        assertEquals(listOf(bundled), result.entries)
        assertEquals(bundled, result.featured)
        assertFalse(result.saved)
        val next = arrivalSchedule(base, date.minusDays(1), null, old)
        assertEquals(bundled, next.next?.service)
        assertFalse(requireNotNull(next.next).saved)
    }

    @Test fun legacyReadyRequestsUpdateWithoutFalseSuccessAndPreservesLoadingFailureAndSafetyMessages() {
        val old = state(publication(SchedulePublication.LEGACY_PARSER_REVISION))
        assertEquals("Обновите расписание", scheduleEmptyTitle(old, true, church))
        assertEquals("Обновите расписание, чтобы снова показать даты и время", scheduleEmptyDescription(old, true, church))
        assertTrue(requireNotNull(scheduleCheckReceipt(old, church.timezone, church)).startsWith("Обновите расписание\n"))
        val loading = old.copy(loading = true)
        assertEquals("Ищем расписание", scheduleEmptyTitle(loading, true, church))
        assertTrue(scheduleEmptyDescription(loading, true, church).startsWith("Проверяем"))
        assertNull(scheduleCheckReceipt(loading, church.timezone, church))
        val failed = old.copy(status = InternetScheduleStatus.UNAVAILABLE)
        assertEquals("Обновите расписание", scheduleEmptyTitle(failed, true, church))
        assertTrue(scheduleEmptyDescription(failed, true, church).startsWith("Не удалось обновить"))
        assertTrue(requireNotNull(scheduleCheckReceipt(failed, church.timezone, church)).startsWith("Обновление не удалось\n"))
        for ((safe, title, receipt) in listOf(
            Triple(old.copy(publicationRejected = true), "Расписание требует уточнения", "Расписание не подтверждено источником"),
            Triple(old.copy(sourceHasWithdrawals = true), "В расписании есть изменения", "В источнике есть отмены или изменения"),
            Triple(old.copy(sourceNeedsVerification = true), "Нужно уточнить расписание", "Источник требует проверки"))) {
            assertEquals(title, scheduleEmptyTitle(safe, true, church))
            assertTrue(requireNotNull(scheduleCheckReceipt(safe, church.timezone, church)).startsWith(receipt + "\n"))
        }
        val current = state(publication(SchedulePublication.CURRENT_PARSER_REVISION))
        assertTrue(requireNotNull(scheduleCheckReceipt(current, church.timezone, church)).startsWith("Расписание получено\n"))
        assertEquals("Нет записей на эту дату", scheduleEmptyTitle(current, true, church))
    }
}
