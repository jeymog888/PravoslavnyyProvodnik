package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ServiceSessionTest {
    private val start = Instant.parse("2026-11-08T06:30:00Z")
    private val stages = listOf(
        stage("preparation", 0, 5), stage("beginning", 1, 10),
        stage("middle", 2, 20), stage("dismissal", 3, 5)
    )
    private val engine = ServiceSessionEngine(stages, Clock.fixed(start, ZoneOffset.UTC))

    @Test
    fun `manual session never advances without explicit opt in`() {
        val session = engine.start("beginning")
        val position = engine.position(session, start.plusSeconds(3_600))
        assertFalse(session.autoAdvance)
        assertEquals("beginning", position.stageId)
        assertEquals(0L, position.elapsedInStageMillis)
        assertFalse(position.isApproximate)
    }

    @Test
    fun `automatic progression crosses exact stage boundaries and is approximate`() {
        val session = engine.start("beginning", autoAdvance = true)
        assertEquals("beginning", engine.position(session, start.plusSeconds(599)).stageId)
        val next = engine.position(session, start.plusSeconds(600))
        assertEquals("middle", next.stageId)
        assertEquals(0L, next.elapsedInStageMillis)
        assertTrue(next.isApproximate)
        assertTrue(next.autoAdvanceActive)
    }

    @Test
    fun `pause and resume preserve elapsed part of the current stage`() {
        val session = engine.start("beginning", autoAdvance = true)
        val paused = engine.pause(session, start.plusSeconds(13 * 60 + 30))
        assertEquals("middle", paused.anchorStageId)
        assertEquals(210_000L, paused.elapsedInStageMillis)
        assertFalse(paused.autoAdvance)
        val muchLater = start.plusSeconds(50 * 60)
        assertEquals(210_000L, engine.position(paused, muchLater).elapsedInStageMillis)

        val resumed = engine.resume(paused, muchLater)
        assertEquals("middle", engine.position(resumed, muchLater.plusSeconds(989)).stageId)
        assertEquals("dismissal", engine.position(resumed, muchLater.plusSeconds(990)).stageId)
        assertTrue(engine.position(paused, muchLater).isApproximate)
    }

    @Test
    fun `manual correction can go backwards and resets the time anchor`() {
        val running = engine.start("beginning", autoAdvance = true)
        val now = start.plusSeconds(15 * 60)
        val corrected = engine.correct(running, "beginning", now)
        assertEquals(now.toEpochMilli(), corrected.anchorEpochMillis)
        assertEquals(0L, corrected.elapsedInStageMillis)
        assertFalse(corrected.anchorIsApproximate)
        assertTrue(corrected.autoAdvance)
        assertEquals("beginning", engine.position(corrected, now.plusSeconds(599)).stageId)
    }

    @Test
    fun `correction does not enable auto mode in a paused session`() {
        val paused = engine.start("middle", approximate = true, elapsedInStageMillis = 100_000)
        val corrected = engine.correct(paused, "beginning")
        assertFalse(corrected.autoAdvance)
        assertFalse(corrected.anchorIsApproximate)
        assertEquals(0L, corrected.elapsedInStageMillis)
    }

    @Test
    fun `restored active session continues across midnight using an instant`() {
        val midnightStart = Instant.parse("2026-11-08T23:55:00Z")
        val session = engine.start("beginning", autoAdvance = true, now = midnightStart)
        val restored = Gson().fromJson(Gson().toJson(session), ServiceSession::class.java)
        val position = engine.position(restored, Instant.parse("2026-11-09T00:07:00Z"))
        assertEquals("middle", position.stageId)
        assertEquals(120_000L, position.elapsedInStageMillis)
        assertFalse(position.requiresResume)
    }

    @Test
    fun `backwards clock change never gives negative elapsed time`() {
        val session = engine.start("middle", autoAdvance = true, elapsedInStageMillis = 80_000)
        val position = engine.position(session, start.minusSeconds(300))
        assertEquals("middle", position.stageId)
        assertEquals(80_000L, position.elapsedInStageMillis)
    }

    @Test
    fun `old running session stays at its saved anchor until explicitly resumed`() {
        val session = engine.start("middle", autoAdvance = true, elapsedInStageMillis = 150_000)
        val nextDay = start.plus(Duration.ofDays(1))
        val position = engine.position(session, nextDay)
        assertEquals("middle", position.stageId)
        assertEquals(150_000L, position.elapsedInStageMillis)
        assertTrue(position.requiresResume)
        assertFalse(position.autoAdvanceActive)

        val resumed = engine.resume(session, nextDay)
        assertFalse(engine.position(resumed, nextDay).requiresResume)
        assertTrue(engine.position(resumed, nextDay).autoAdvanceActive)
        assertEquals(150_000L, engine.position(resumed, nextDay).elapsedInStageMillis)
    }

    @Test
    fun `six hour boundary is stale and correction does not silently restart it`() {
        val session = engine.start("beginning", autoAdvance = true)
        val now = start.plus(ServiceSessionEngine.STALE_AFTER)
        assertTrue(engine.position(session, now).requiresResume)
        assertFalse(engine.correct(session, "middle", now).autoAdvance)
    }

    @Test
    fun `very distant clock and invalid saved stage do not crash or auto start`() {
        val session = engine.start("beginning", autoAdvance = true)
        val distant = engine.position(session, Instant.MAX)
        assertTrue(distant.requiresResume)
        assertEquals("beginning", distant.stageId)

        val deleted = session.copy(anchorStageId = "removed")
        assertFalse(engine.isValid(deleted))
        assertTrue(engine.position(deleted).requiresResume)
        assertFalse(engine.position(deleted).autoAdvanceActive)
    }

    @Test
    fun `estimated completion stays on last stage without claiming service has ended`() {
        val session = engine.start("beginning", autoAdvance = true)
        val position = engine.position(session, start.plusSeconds(40 * 60))
        assertEquals("dismissal", position.stageId)
        assertEquals(300_000L, position.elapsedInStageMillis)
        assertTrue(position.atEndOfGuide)
        assertTrue(position.isApproximate)
        assertFalse(position.autoAdvanceActive)
    }

    @Test
    fun `schedule stage offset survives opt in without restarting that stage`() {
        val session = engine.start("middle", approximate = true, elapsedInStageMillis = 19 * 60_000)
        val resumed = engine.resume(session)
        assertEquals("dismissal", engine.position(resumed, start.plusSeconds(60)).stageId)
    }

    @Test
    fun `context uses latest plausible started liturgy and excludes preparation`() {
        val parish = parish(listOf(service("07:00"), service("09:30"), service("10:00")))
        val context = ContextSuggestionEngine(stages).suggest(parish, start.plusSeconds(12 * 60))
        assertEquals("09:30", context?.service?.time)
        assertEquals(ServiceRelation.InProgress, context?.relation)
        assertEquals(start, context?.startInstant)
        assertEquals("middle", context?.approximateStageId)
        assertEquals(120_000L, context?.approximateElapsedInStageMillis)
    }

    @Test
    fun `upcoming suggestion has no invented current stage`() {
        val context = ContextSuggestionEngine(stages).suggest(parish(listOf(service("09:30"))), start.minusSeconds(30 * 60))
        assertEquals(ServiceRelation.Upcoming, context?.relation)
        assertEquals("09:30", context?.service?.time)
        assertNull(context?.approximateStageId)
    }

    @Test
    fun `arrival before next liturgy is not placed at the previous estimated dismissal`() {
        val parish = parish(listOf(service("07:00"), service("09:30")))
        val arrival = Instant.parse("2026-11-08T06:15:00Z") // 09:15 in Opalikha
        val context = ContextSuggestionEngine(stages).suggest(parish, arrival)

        assertEquals("09:30", context?.service?.time)
        assertEquals(ServiceRelation.Upcoming, context?.relation)
        assertNull(context?.approximateStageId)
    }

    @Test
    fun `an unexhausted estimated service still takes priority over an upcoming one`() {
        val parish = parish(listOf(service("09:00"), service("09:30")))
        val arrival = Instant.parse("2026-11-08T06:15:00Z")
        val context = ContextSuggestionEngine(stages).suggest(parish, arrival)

        assertEquals("09:00", context?.service?.time)
        assertEquals(ServiceRelation.InProgress, context?.relation)
        assertEquals("middle", context?.approximateStageId)
    }

    @Test
    fun `estimated guide end alone does not assert that a service has finished`() {
        val parish = parish(listOf(service("07:00")))
        val arrival = Instant.parse("2026-11-08T06:15:00Z")
        val context = ContextSuggestionEngine(stages).suggest(parish, arrival)

        assertEquals("07:00", context?.service?.time)
        assertEquals(ServiceRelation.InProgress, context?.relation)
        assertEquals("dismissal", context?.approximateStageId)
    }

    @Test
    fun `services outside plausible windows do not become current`() {
        val context = ContextSuggestionEngine(stages)
        assertNull(context.suggest(parish(listOf(service("09:30"))), start.plusSeconds(151 * 60)))
        assertNull(context.suggest(parish(listOf(service("09:30"))), start.minusSeconds(121 * 60)))
        assertNotNull(context.suggest(parish(listOf(service("09:30"))), start.plusSeconds(150 * 60)))
        assertNotNull(context.suggest(parish(listOf(service("09:30"))), start.minusSeconds(120 * 60)))
    }

    @Test
    fun `context uses parish local date rather than clock zone or another day`() {
        val context = ContextSuggestionEngine(stages, Clock.fixed(Instant.parse("2026-11-07T21:20:00Z"), ZoneOffset.UTC))
        val parish = parish(listOf(service("00:10"), service("00:30", date = "2026-11-07")))
        val suggestion = context.suggest(parish)
        assertEquals("2026-11-08", suggestion?.service?.date)
        assertEquals("00:10", suggestion?.service?.time)
        assertEquals(ServiceRelation.InProgress, suggestion?.relation)
    }

    @Test
    fun `wrong date expired schedule and nonliturgical services are never inferred`() {
        val context = ContextSuggestionEngine(stages)
        assertNull(context.suggest(parish(listOf(service("09:30", date = "2026-11-07"))), start))
        assertNull(context.suggest(parish(listOf(service("09:30", kind = "vespers"))), start))
        assertNull(context.suggest(parish(listOf(service("09:30"))).copy(scheduleValidUntil = "2026-11-07"), start))
    }

    @Test
    fun `malformed times are skipped without hiding a usable service`() {
        val context = ContextSuggestionEngine(stages)
        val suggestion = context.suggest(parish(listOf(service("oops"), service("09:30"))), start)
        assertEquals("09:30", suggestion?.service?.time)
        assertNull(context.suggest(parish(listOf(service("09:30"))).copy(timezone = "not-a-zone"), start))
    }

    @Test
    fun `context without known beginning suggests only the service`() {
        val context = ContextSuggestionEngine(listOf(stage("unmapped", 1, 10)))
        val suggestion = context.suggest(parish(listOf(service("09:30"))), start)
        assertEquals(ServiceRelation.InProgress, suggestion?.relation)
        assertNull(suggestion?.approximateStageId)
    }

    private fun stage(id: String, order: Int, duration: Int) = LiturgyStage(
        id = id, order = order, name = id, description = id, durationMin = duration, texts = emptyList()
    )

    private fun service(time: String, date: String = "2026-11-08", kind: String = "liturgy") =
        ParishService(date, time, "Литургия", kind)

    private fun parish(schedule: List<ParishService>) = Parish(
        id = "test", name = "Храм", shortName = "Храм", city = "Город", address = "Улица",
        phone = "", website = "https://example.org", timezone = "Europe/Moscow",
        sourceUrl = "https://example.org", scheduleSource = "Тестовое расписание",
        scheduleValidFrom = "2026-11-01", scheduleValidUntil = "2026-11-30", schedule = schedule,
        scheduleCoverage = "Тест", scheduleNote = "Тест"
    )
}
