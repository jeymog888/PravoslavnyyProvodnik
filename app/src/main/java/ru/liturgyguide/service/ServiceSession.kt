package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Serializable session anchor. No wall-clock-only time or audio is persisted. */
data class ServiceSession(
    val anchorStageId: String,
    val anchorEpochMillis: Long,
    val autoAdvance: Boolean = false,
    val elapsedInStageMillis: Long = 0,
    val anchorIsApproximate: Boolean = false
)

data class SessionPosition(
    val stageId: String,
    val elapsedInStageMillis: Long,
    val isApproximate: Boolean,
    /** End of the estimated guide timeline, not confirmation that worship ended. */
    val atEndOfGuide: Boolean,
    val requiresResume: Boolean,
    val autoAdvanceActive: Boolean
)

/**
 * Advances only after an explicit opt-in. Durations are editorial estimates;
 * every position derived from time must remain labelled approximate in the UI.
 */
class ServiceSessionEngine(
    stages: List<LiturgyStage>,
    private val clock: Clock = Clock.systemUTC()
) {
    private val orderedStages = stages.sortedBy { it.order }.also { ordered ->
        require(ordered.isNotEmpty()) { "A session needs at least one stage" }
        require(ordered.all { it.id.isNotBlank() && it.durationMin > 0 }) { "Stages need IDs and positive durations" }
        require(ordered.map { it.id }.distinct().size == ordered.size) { "Stage IDs must be unique" }
        require(ordered.map { it.order }.distinct().size == ordered.size) { "Stage orders must be unique" }
    }
    private val indexes = orderedStages.mapIndexed { index, stage -> stage.id to index }.toMap()

    fun isValid(session: ServiceSession): Boolean =
        session.anchorStageId in indexes && session.elapsedInStageMillis >= 0

    fun start(
        stageId: String,
        autoAdvance: Boolean = false,
        now: Instant = clock.instant(),
        approximate: Boolean = false,
        elapsedInStageMillis: Long = 0
    ): ServiceSession {
        val stage = orderedStages[requireNotNull(indexes[stageId]) { "Unknown stage: $stageId" }]
        return ServiceSession(
            anchorStageId = stage.id,
            anchorEpochMillis = now.toEpochMilli(),
            autoAdvance = autoAdvance,
            elapsedInStageMillis = elapsedInStageMillis.coerceIn(0, durationMillis(stage)),
            anchorIsApproximate = approximate
        )
    }

    fun position(session: ServiceSession, now: Instant = clock.instant()): SessionPosition {
        val anchorIndex = indexes[session.anchorStageId]
        if (anchorIndex == null || session.elapsedInStageMillis < 0) {
            // Changed or corrupt saved content must never start a fresh timer.
            return SessionPosition(orderedStages.first().id, 0, true, false, true, false)
        }
        val sinceAnchor = Duration.between(Instant.ofEpochMilli(session.anchorEpochMillis), now)
        val stale = sinceAnchor >= STALE_AFTER
        val running = session.autoAdvance && !stale
        // toMillis is safe here: positive durations >= 6 hours have been excluded.
        val delta = if (running && !sinceAnchor.isNegative) sinceAnchor.toMillis() else 0L
        val originalOffset = session.elapsedInStageMillis.coerceAtMost(durationMillis(orderedStages[anchorIndex]))
        var elapsed = saturatedAdd(originalOffset, delta)
        var index = anchorIndex
        while (index < orderedStages.lastIndex && elapsed >= durationMillis(orderedStages[index])) {
            elapsed -= durationMillis(orderedStages[index])
            index++
        }
        val stageDuration = durationMillis(orderedStages[index])
        val atEnd = index == orderedStages.lastIndex && elapsed >= stageDuration
        return SessionPosition(
            stageId = orderedStages[index].id,
            elapsedInStageMillis = elapsed.coerceAtMost(stageDuration),
            isApproximate = session.anchorIsApproximate || session.autoAdvance || index != anchorIndex,
            atEndOfGuide = atEnd,
            requiresResume = stale,
            autoAdvanceActive = running && !atEnd
        )
    }

    /** Manual or user-confirmed audio correction always resets the anchor offset. */
    fun correct(session: ServiceSession, stageId: String, now: Instant = clock.instant()): ServiceSession =
        start(stageId, autoAdvance = session.autoAdvance && !position(session, now).requiresResume, now = now)

    /** Capture both the current stage and its elapsed offset before stopping. */
    fun pause(session: ServiceSession, now: Instant = clock.instant()): ServiceSession {
        val current = position(session, now)
        return start(current.stageId, now = now, approximate = current.isApproximate,
            elapsedInStageMillis = current.elapsedInStageMillis)
    }

    /** An explicit user action is required, especially after restoring an old session. */
    fun resume(session: ServiceSession, now: Instant = clock.instant()): ServiceSession {
        val current = position(session, now)
        return start(current.stageId, autoAdvance = true, now = now,
            approximate = current.isApproximate, elapsedInStageMillis = current.elapsedInStageMillis)
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(6)
        private fun durationMillis(stage: LiturgyStage): Long = stage.durationMin.toLong() * 60_000L
        private fun saturatedAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
    }
}

enum class ServiceRelation { Upcoming, InProgress }

data class ContextSuggestion(
    val service: ParishService,
    val relation: ServiceRelation,
    val startInstant: Instant,
    val approximateStageId: String? = null,
    val approximateElapsedInStageMillis: Long = 0,
    val source: ServiceStartSource = ServiceStartSource.PUBLISHED,
    val parishId: String = "",
    val timezone: String = "UTC",
    /** Exhausting an editorial guide is not evidence that the service has ended. */
    val estimatedGuideExhausted: Boolean = false
)

/** Both choices remain visible independently, even if the current estimate exhausted its guide. */
data class ScheduleOverview(
    val currentHypothesis: ContextSuggestion? = null,
    val nextKnownService: ContextSuggestion? = null
)

/** Schedule produces a hypothesis; it never starts a session or opens the microphone. */
class ContextSuggestionEngine(
    stages: List<LiturgyStage>,
    private val clock: Clock = Clock.systemUTC()
) {
    private val sessionEngine = ServiceSessionEngine(stages, clock)
    // Preparation precedes the published start of the liturgy. If the data has no
    // identifiable beginning, suggest the service without inventing a stage.
    private val beginningStageId = stages.firstOrNull { it.id == "beginning" }?.id

    fun overview(
        parish: Parish,
        now: Instant = clock.instant(),
        userStarts: List<UserServiceStart> = emptyList()
    ): ScheduleOverview {
        val zone = runCatching { ZoneId.of(parish.timezone) }.getOrNull() ?: return ScheduleOverview()
        // Only a valid personal start suppresses the published suggestion for this exact date.
        // The original parish.schedule is never mutated, including other liturgies that day.
        val personal = UserServiceStartCodec.unambiguous(userStarts)
            .mapNotNull { UserServiceStartCodec.validatedFor(it, parish) }
        val personalDates = personal.map { it.date }.toSet()
        val from = ScheduleTime.date(parish.scheduleValidFrom)
        val until = ScheduleTime.date(parish.scheduleValidUntil)
        val published = if (from == null || until == null || until.isBefore(from)) emptyList() else
            parish.schedule.mapNotNull { service ->
                if (service.kind != "liturgy" || service.date in personalDates || service.title.isBlank()) return@mapNotNull null
                val date = ScheduleTime.date(service.date) ?: return@mapNotNull null
                // Check each occurrence's date, not today's date: the next publication may begin tomorrow.
                if (date.isBefore(from) || date.isAfter(until)) return@mapNotNull null
                occurrence(service, zone, ServiceStartSource.PUBLISHED)
            }
        val candidates = published + personal.mapNotNull { record ->
            occurrence(ParishService(record.date, record.time, "Литургия", "liturgy"), zone, ServiceStartSource.USER)
        }
        // Tie-break deterministically when the source repeats a start with different titles.
        val ordered = candidates.sortedWith(compareBy<Occurrence> { it.start }.thenBy { it.service.title })
            .distinctBy { it.start }
        val current = ordered.lastOrNull { !it.start.isAfter(now) && Duration.between(it.start, now) <= MAX_STARTED_AGE }
        val next = ordered.firstOrNull { it.start.isAfter(now) }
        return ScheduleOverview(
            currentHypothesis = current?.let { candidate ->
                val estimate = beginningStageId?.let { beginning ->
                    sessionEngine.position(sessionEngine.start(beginning, autoAdvance = true,
                        now = candidate.start, approximate = true), now)
                }
                ContextSuggestion(candidate.service, ServiceRelation.InProgress, candidate.start,
                    estimate?.stageId, estimate?.elapsedInStageMillis ?: 0,
                    candidate.source, parish.id, zone.id, estimate?.atEndOfGuide == true)
            },
            nextKnownService = next?.let {
                ContextSuggestion(it.service, ServiceRelation.Upcoming, it.start,
                    source = it.source, parishId = parish.id, timezone = zone.id)
            }
        )
    }

    /** Compatibility only: new screens must show both overview choices and bind clicks to one of them. */
    @Deprecated("Use overview to keep current and next services visible independently")
    fun suggest(parish: Parish, now: Instant = clock.instant()): ContextSuggestion? {
        val overview = overview(parish, now)
        val today = runCatching { now.atZone(ZoneId.of(parish.timezone)).toLocalDate() }.getOrNull() ?: return null
        val current = overview.currentHypothesis?.takeIf { it.service.date == today.toString() }
        val next = overview.nextKnownService?.takeIf {
            it.service.date == today.toString() && Duration.between(now, it.startInstant) <= MAX_UPCOMING_DISTANCE
        }
        return if (current?.estimatedGuideExhausted == true && next != null) next else current ?: next
    }

    private data class Occurrence(val service: ParishService, val start: Instant, val source: ServiceStartSource)

    private fun occurrence(service: ParishService, zone: ZoneId, source: ServiceStartSource): Occurrence? {
        val date = ScheduleTime.date(service.date) ?: return null
        val time = when (source) {
            ServiceStartSource.PUBLISHED -> ScheduleTime.publishedTime(service.time)
            ServiceStartSource.USER -> ScheduleTime.manualTime(service.time)
        } ?: return null
        val start = ScheduleTime.instant(date, time, zone) ?: return null
        return Occurrence(service, start, source)
    }

    companion object {
        val MAX_STARTED_AGE: Duration = Duration.ofMinutes(150)
        val MAX_UPCOMING_DISTANCE: Duration = Duration.ofMinutes(120)
    }
}
