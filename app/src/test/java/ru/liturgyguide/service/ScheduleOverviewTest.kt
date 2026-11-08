package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ScheduleOverviewTest {
    private val now = Instant.parse("2026-11-08T06:15:00Z") // 09:15 in the parish
    private val stages = listOf(stage("preparation", 0, 5), stage("beginning", 1, 10), stage("dismissal", 2, 5))
    private val engine = ContextSuggestionEngine(stages, Clock.fixed(now, ZoneId.of("Pacific/Honolulu")))

    @Test fun `exhausted current guide and next known service are independent choices`() {
        val overview = engine.overview(parish(listOf(service("07:00"), service("09:30"))))
        assertEquals("07:00", overview.currentHypothesis?.service?.time)
        assertEquals(ServiceRelation.InProgress, overview.currentHypothesis?.relation)
        assertTrue(overview.currentHypothesis?.estimatedGuideExhausted == true)
        assertEquals("09:30", overview.nextKnownService?.service?.time)
        assertEquals(ServiceRelation.Upcoming, overview.nextKnownService?.relation)
        assertNull(overview.nextKnownService?.approximateStageId)
    }

    @Test fun `next service spans days and months without a two hour cutoff`() {
        val overview = engine.overview(parish(listOf(service("08:00", "2026-12-01"), service("23:00", "2026-11-30"))),
            Instant.parse("2026-11-30T08:00:00Z"))
        assertNull(overview.currentHypothesis)
        assertEquals("2026-11-30", overview.nextKnownService?.service?.date)
        val after = engine.overview(parish(listOf(service("08:00", "2026-12-01"))), Instant.parse("2026-11-30T21:10:00Z"))
        assertEquals("2026-12-01", after.nextKnownService?.service?.date)
        assertEquals(Instant.parse("2026-12-01T05:00:00Z"), after.nextKnownService?.startInstant)
    }

    @Test fun `a future published period can be shown before it begins`() {
        val nextMonth = parish(listOf(service("08:00", "2026-12-01")))
            .copy(scheduleValidFrom = "2026-12-01", scheduleValidUntil = "2026-12-31")
        assertEquals("2026-12-01", engine.overview(nextMonth).nextKnownService?.service?.date)
    }

    @Test fun `each published event must lie within its own valid period`() {
        val parish = parish(listOf(service("08:00", "2026-11-07"), service("10:00"), service("08:00", "2026-12-01")))
            .copy(scheduleValidFrom = "2026-11-08", scheduleValidUntil = "2026-11-30")
        assertEquals("10:00", engine.overview(parish).nextKnownService?.service?.time)
        assertNull(engine.overview(parish, Instant.parse("2026-12-01T04:00:00Z")).nextKnownService)
        assertEquals(ScheduleOverview(), engine.overview(parish.copy(scheduleValidFrom = "2026-12-01", scheduleValidUntil = "2026-11-01")))
    }

    @Test fun `old month and weekday are never recycled as a future service`() {
        val old = parish(listOf(service("08:00", "2026-10-08"))).copy(scheduleValidFrom = "2026-10-01", scheduleValidUntil = "2026-10-31")
        assertEquals(ScheduleOverview(), engine.overview(old))
        val personal = UserServiceStart("test", "2026-11-01", "09:30")
        assertEquals(ScheduleOverview(), engine.overview(parish(emptyList()), userStarts = listOf(personal)))
    }

    @Test fun `current may cross parish midnight and retains its service date`() {
        val overview = engine.overview(parish(listOf(service("23:55", "2026-11-07"), service("08:00"))),
            Instant.parse("2026-11-07T21:10:00Z"))
        assertEquals("2026-11-07", overview.currentHypothesis?.service?.date)
        assertEquals("2026-11-08", overview.nextKnownService?.service?.date)
        assertEquals("Europe/Moscow", overview.currentHypothesis?.timezone)
        assertEquals("test", overview.currentHypothesis?.parishId)
        assertEquals(ServiceStartSource.PUBLISHED, overview.currentHypothesis?.source)
    }

    @Test fun `parish timezone determines the instant independently of fixed clock zone`() {
        val tokyo = parish(listOf(service("15:00"), service("16:00"))).copy(timezone = "Asia/Tokyo")
        val overview = engine.overview(tokyo)
        assertEquals("15:00", overview.currentHypothesis?.service?.time)
        assertEquals(Instant.parse("2026-11-08T06:00:00Z"), overview.currentHypothesis?.startInstant)
        assertEquals("Asia/Tokyo", overview.nextKnownService?.timezone)
    }

    @Test fun `exact start enters current and 150 minute boundary is inclusive`() {
        val parish = parish(listOf(service("09:15"), service("08:00", "2026-11-09")))
        val before = engine.overview(parish, now.minusNanos(1))
        assertNull(before.currentHypothesis)
        assertEquals("09:15", before.nextKnownService?.service?.time)
        assertEquals("09:15", engine.overview(parish, now).currentHypothesis?.service?.time)
        assertNotNull(engine.overview(parish, now.plusSeconds(150 * 60)).currentHypothesis)
        assertNull(engine.overview(parish, now.plusSeconds(150 * 60).plusNanos(1)).currentHypothesis)
        assertEquals("2026-11-09", engine.overview(parish, now.plusSeconds(151 * 60)).nextKnownService?.service?.date)
    }

    @Test fun `multiple published starts select latest plausible current and nearest future`() {
        val overview = engine.overview(parish(listOf(service("11:00"), service("08:00"), service("10:00"), service("09:00"))))
        assertEquals("09:00", overview.currentHypothesis?.service?.time)
        assertEquals("10:00", overview.nextKnownService?.service?.time)
    }

    @Suppress("DEPRECATION")
    @Test fun `published seconds remain compatible with repository and legacy suggestions`() {
        val church = parish(listOf(service("09:30:00"), service("09:30:15")))
        val instant = Instant.parse("2026-11-08T06:30:05Z")
        val overview = engine.overview(church, instant)
        assertEquals("09:30:00", overview.currentHypothesis?.service?.time)
        assertEquals(Instant.parse("2026-11-08T06:30:00Z"), overview.currentHypothesis?.startInstant)
        assertEquals("09:30:15", overview.nextKnownService?.service?.time)
        assertEquals(Instant.parse("2026-11-08T06:30:15Z"), overview.nextKnownService?.startInstant)
        assertEquals("09:30:00", engine.suggest(church, instant)?.service?.time)
        assertEquals("09:30:15", engine.suggest(parish(listOf(service("09:30:15"))), instant)?.service?.time)
        assertEquals("09:30:15", engine.overview(church, instant.plusSeconds(10)).currentHypothesis?.service?.time)
    }

    @Test fun `duplicated published instant keeps the same identity across the start boundary`() {
        val first = service("09:15").copy(title = "А Литургия")
        val second = first.copy(title = "Б Литургия")
        for (services in listOf(listOf(first, second), listOf(second, first))) {
            val parish = parish(services)
            assertEquals(first, engine.overview(parish, now.minusNanos(1)).nextKnownService?.service)
            assertEquals(first, engine.overview(parish, now).currentHypothesis?.service)
        }
    }

    @Test fun `spring DST gap and autumn DST overlap never get guessed`() {
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val berlin = parish(listOf(service("02:30", date), service("04:00", date)))
                .copy(timezone = "Europe/Berlin", scheduleValidFrom = date, scheduleValidUntil = date)
            val before = Instant.parse("${date}T00:00:00Z")
            assertNull(engine.overview(berlin, before).currentHypothesis)
            assertEquals("04:00", engine.overview(berlin, before).nextKnownService?.service?.time)
            val invalid = UserServiceStart("test", date, "02:30")
            assertNull(UserServiceStartCodec.validatedFor(invalid, berlin))
            assertEquals("04:00", engine.overview(berlin, before, listOf(invalid)).nextKnownService?.service?.time)
        }
    }

    @Test fun `custom parish can use one explicit personal start without published validity dates`() {
        val custom = parish(emptyList()).copy(scheduleValidFrom = "", scheduleValidUntil = "")
        val overview = engine.overview(custom, userStarts = listOf(UserServiceStart("test", "2026-11-08", "10:00")))
        assertEquals("10:00", overview.nextKnownService?.service?.time)
        assertEquals(ServiceStartSource.USER, overview.nextKnownService?.source)
        assertEquals("test", overview.nextKnownService?.parishId)
        assertEquals("Europe/Moscow", overview.nextKnownService?.timezone)
        assertTrue(custom.schedule.isEmpty())
    }

    @Test fun `personal time overrides only the suggestion for its parish and date`() {
        val published = listOf(service("09:00"), service("11:00"), service("08:00", "2026-11-09"))
        val parish = parish(published)
        val personal = listOf(UserServiceStart("test", "2026-11-08", "09:10"), UserServiceStart("elsewhere", "2026-11-09", "06:00"))
        val overview = engine.overview(parish, userStarts = personal)
        assertEquals("09:10", overview.currentHypothesis?.service?.time)
        assertEquals(ServiceStartSource.USER, overview.currentHypothesis?.source)
        assertEquals("2026-11-09", overview.nextKnownService?.service?.date)
        assertEquals("08:00", overview.nextKnownService?.service?.time)
        assertEquals(ServiceStartSource.PUBLISHED, overview.nextKnownService?.source)
        assertEquals(published, parish.schedule)
        val cleared = engine.overview(parish)
        assertEquals("09:00", cleared.currentHypothesis?.service?.time)
        assertEquals("11:00", cleared.nextKnownService?.service?.time)
    }

    @Test fun `invalid personal records do not hide published services`() {
        val parish = parish(listOf(service("10:00")))
        for (record in listOf(UserServiceStart("test", "2026-11-08", "25:00"),
            UserServiceStart("test", "2026-11-8", "11:00"), UserServiceStart("other", "2026-11-08", "11:00"))) {
            assertEquals(ServiceStartSource.PUBLISHED, engine.overview(parish, userStarts = listOf(record)).nextKnownService?.source)
        }
    }

    @Test fun `conflicting duplicates never choose an arbitrary personal override`() {
        val parish = parish(listOf(service("10:00")))
        val first = UserServiceStart("test", "2026-11-08", "11:00")
        val conflict = first.copy(time = "12:00")
        for (records in listOf(listOf(first, conflict), listOf(conflict, first))) {
            val overview = engine.overview(parish, userStarts = records)
            assertEquals("10:00", overview.nextKnownService?.service?.time)
            assertEquals(ServiceStartSource.PUBLISHED, overview.nextKnownService?.source)
        }
        assertEquals("11:00", engine.overview(parish, userStarts = listOf(first, first)).nextKnownService?.service?.time)
    }

    @Test fun `only liturgy has a supported player and invalid neighboring data is skipped`() {
        val parish = parish(listOf(service("09:30").copy(kind = "vespers"), service("bad"),
            service("10:00", "2026-02-30"), service("10:30").copy(title = " "), service("11:00")))
        assertEquals("11:00", engine.overview(parish).nextKnownService?.service?.time)
        assertEquals(ScheduleOverview(), engine.overview(parish.copy(timezone = "Mars/Nowhere")))
    }

    @Test fun `missing known beginning offers service without inventing stage`() {
        val engine = ContextSuggestionEngine(listOf(stage("other", 1, 5)), Clock.fixed(now, ZoneId.of("UTC")))
        val current = engine.overview(parish(listOf(service("09:00")))).currentHypothesis
        assertNotNull(current)
        assertNull(current?.approximateStageId)
        assertFalse(current?.estimatedGuideExhausted == true)
    }

    private fun stage(id: String, order: Int, minutes: Int) = LiturgyStage(id = id, order = order,
        name = id, description = id, durationMin = minutes, texts = emptyList())
    private fun service(time: String, date: String = "2026-11-08") = ParishService(date, time, "Литургия", "liturgy")
    private fun parish(schedule: List<ParishService>) = Parish(id = "test", name = "Храм", shortName = "Храм", city = "Город",
        address = "Улица", phone = "", website = "", timezone = "Europe/Moscow", sourceUrl = "", scheduleSource = "Приход",
        scheduleValidFrom = "2026-01-01", scheduleValidUntil = "2026-12-31", schedule = schedule, scheduleCoverage = "", scheduleNote = "")
}
