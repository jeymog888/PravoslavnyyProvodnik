package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Instant
import java.time.ZoneId

class ServiceEntryResolverTest {
    private val start = Instant.parse("2026-11-08T06:00:00Z")
    private val stages = listOf(stage("preparation", 0, 5), stage("beginning", 1, 10), stage("creed", 2, 10), stage("dismissal", 3, 5))
    private val engine = ContextSuggestionEngine(stages)
    private val resolver = ServiceEntryResolver(engine)
    private val parish = Parish("church", "Храм", "Храм", "Город", "", "", "", "Europe/Moscow",
        "", "", "2026-11-01", "2026-11-30", listOf(service("09:00"), service("10:00")), "", "")

    @Test fun `a future choice retains its own date and becomes current across the start boundary`() {
        val selected = requireNotNull(engine.overview(parish, start.minusSeconds(1)).nextKnownService)
        val before = available(resolver.resolve(selected, parish, start.minusMillis(1)))
        assertEquals(ServiceRelation.Upcoming, before.suggestion.relation)
        assertNull(before.suggestion.approximateStageId)
        assertEquals("2026-11-08", before.context.serviceDate)
        val atStart = available(resolver.resolve(selected, parish, start))
        assertEquals(ServiceRelation.InProgress, atStart.suggestion.relation)
        assertEquals("beginning", atStart.suggestion.approximateStageId)
        assertEquals(0L, atStart.suggestion.approximateElapsedInStageMillis)
        assertEquals(before.context, atStart.context)
    }

    @Test fun `current stage is recomputed at click time instead of taking the old snapshot`() {
        val selected = requireNotNull(engine.overview(parish, start.plusSeconds(30)).currentHypothesis)
        assertEquals("beginning", selected.approximateStageId)
        val actual = available(resolver.resolve(selected, parish, start.plusSeconds(11 * 60 + 7)))
        assertEquals("creed", actual.suggestion.approximateStageId)
        assertEquals(67_000L, actual.suggestion.approximateElapsedInStageMillis)
        assertEquals(start.toEpochMilli(), actual.context.startEpochMillis)
    }

    @Test fun `an expired selected service is never silently replaced by another current or next service`() {
        val selected = requireNotNull(engine.overview(parish, start).currentHypothesis)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish, start.plusSeconds(61 * 60)))
        val one = parish.copy(schedule = listOf(service("09:00")))
        assertTrue(resolver.resolve(selected, one, start.plusSeconds(150 * 60)) is ServiceEntryResult.Available)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, one, start.plusSeconds(150 * 60).plusMillis(1)))
    }

    @Test fun `switching parish or its timezone rejects a stale card even with similar labels`() {
        val selected = requireNotNull(engine.overview(parish, start).currentHypothesis)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish.copy(id = "other"), start))
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish.copy(timezone = "Europe/Samara"), start))
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected.copy(parishId = ""), parish, start))
    }

    @Test fun `accepted zone aliases compare against the canonical zone emitted by overview`() {
        for ((inputZone, startText) in listOf("+00:00" to "2026-11-08T09:00:00Z", "UTC+3" to "2026-11-08T06:00:00Z")) {
            val church = parish.copy(timezone = inputZone)
            val actualStart = Instant.parse(startText)
            val selected = requireNotNull(engine.overview(church, actualStart).currentHypothesis)
            assertEquals(ZoneId.of(inputZone).id, selected.timezone)
            assertNotEquals(inputZone, selected.timezone)
            val actual = available(resolver.resolve(selected, church, actualStart))
            assertEquals(selected.timezone, actual.context.timezone)
            assertEquals(actualStart.toEpochMilli(), actual.context.startEpochMillis)
            assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, church.copy(timezone = "Mars/Nowhere"), actualStart))
        }
    }

    @Test fun `personal time changes and source changes invalidate the selected card`() {
        val oldPersonal = UserServiceStart(parish.id, "2026-11-08", "09:00")
        val selected = requireNotNull(engine.overview(parish, start, listOf(oldPersonal)).currentHypothesis)
        assertEquals(ServiceStartSource.USER, selected.source)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish, start, listOf(oldPersonal.copy(time = "09:10"))))
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish, start))
        val fromPublished = requireNotNull(engine.overview(parish, start).currentHypothesis)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(fromPublished, parish, start, listOf(oldPersonal)))
        assertEquals(ReadingContextSource.USER, available(resolver.resolve(selected, parish, start, listOf(oldPersonal))).context.source)
    }

    @Test fun `title editing does not create a different occurrence while removal and date corruption do`() {
        val selected = requireNotNull(engine.overview(parish, start).currentHypothesis)
        val renamed = parish.copy(schedule = parish.schedule.map { it.copy(title = "Божественная литургия") })
        assertEquals("Божественная литургия", available(resolver.resolve(selected, renamed, start)).suggestion.service.title)
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected, parish.copy(schedule = emptyList()), start))
        assertEquals(ServiceEntryResult.Expired, resolver.resolve(selected.copy(service = selected.service.copy(date = "2026-11-09")), parish, start))
    }

    @Test fun `clock moving backwards changes relation but keeps the same selected occurrence`() {
        val selected = requireNotNull(engine.overview(parish, start.plusSeconds(11 * 60)).currentHypothesis)
        val actual = available(resolver.resolve(selected, parish, start.minusSeconds(60)))
        assertEquals(ServiceRelation.Upcoming, actual.suggestion.relation)
        assertNull(actual.suggestion.approximateStageId)
        assertEquals(0L, actual.suggestion.approximateElapsedInStageMillis)
    }

    @Test fun `a late evening occurrence preserves yesterday as its service date after parish midnight`() {
        val parish = parish.copy(schedule = listOf(service("23:55", "2026-11-08"), service("08:00", "2026-11-09")))
        val selected = requireNotNull(engine.overview(parish, Instant.parse("2026-11-08T20:50:00Z")).nextKnownService)
        val actual = available(resolver.resolve(selected, parish, Instant.parse("2026-11-08T21:05:00Z")))
        assertEquals(ServiceRelation.InProgress, actual.suggestion.relation)
        assertEquals("2026-11-08", actual.context.serviceDate)
        assertEquals("Europe/Moscow", actual.context.timezone)
        assertEquals("creed", actual.suggestion.approximateStageId)
    }

    @Test fun `tomorrows selected service keeps tomorrows context while still upcoming`() {
        val tomorrow = parish.copy(schedule = listOf(service("08:00", "2026-11-09")))
        val selected = requireNotNull(engine.overview(tomorrow, start).nextKnownService)
        val actual = available(resolver.resolve(selected, tomorrow, start.plusSeconds(60)))
        assertEquals("2026-11-09", actual.context.serviceDate)
        assertEquals(ReadingContextSource.PUBLISHED, actual.context.source)
        assertEquals(ServiceRelation.Upcoming, actual.suggestion.relation)
    }

    private fun available(result: ServiceEntryResult): ServiceEntryResult.Available {
        assertTrue(result.toString(), result is ServiceEntryResult.Available)
        return result as ServiceEntryResult.Available
    }
    private fun stage(id: String, order: Int, minutes: Int) = LiturgyStage(id, order, id, id, minutes, emptyList())
    private fun service(time: String, date: String = "2026-11-08") = ParishService(date, time, "Литургия", "liturgy")
}
