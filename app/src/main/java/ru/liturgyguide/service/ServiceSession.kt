package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    val approximateElapsedInStageMillis: Long = 0
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

    fun suggest(parish: Parish, now: Instant = clock.instant()): ContextSuggestion? {
        val zone = runCatching { ZoneId.of(parish.timezone) }.getOrNull() ?: return null
        val today = now.atZone(zone).toLocalDate()
        val from = runCatching { LocalDate.parse(parish.scheduleValidFrom) }.getOrNull() ?: return null
        val until = runCatching { LocalDate.parse(parish.scheduleValidUntil) }.getOrNull() ?: return null
        if (today.isBefore(from) || today.isAfter(until)) return null

        val candidates = parish.schedule.mapNotNull { service ->
            if (service.kind != "liturgy") return@mapNotNull null
            val start = runCatching {
                val date = LocalDate.parse(service.date)
                if (date != today) return@mapNotNull null
                val local = date.atTime(LocalTime.parse(service.time))
                // Avoid guessing which occurrence was intended during a daylight
                // saving clock change, or moving a nonexistent time forward.
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.size != 1) return@mapNotNull null
                local.atOffset(offsets.single()).toInstant()
            }.getOrNull() ?: return@mapNotNull null
            service to start
        }

        val inProgress = candidates.filter { (_, start) ->
            !start.isAfter(now) && Duration.between(start, now) <= MAX_STARTED_AGE
        }.maxByOrNull { it.second }
        val upcoming = candidates.filter { (_, start) ->
            start.isAfter(now) && Duration.between(now, start) <= MAX_UPCOMING_DISTANCE
        }.minByOrNull { it.second }?.let { (service, start) ->
            ContextSuggestion(service, ServiceRelation.Upcoming, start)
        }
        if (inProgress != null) {
            val (service, start) = inProgress
            val estimate = beginningStageId?.let { beginning ->
                sessionEngine.position(sessionEngine.start(beginning, autoAdvance = true,
                    now = start, approximate = true), now)
            }
            // A prior service may run longer than the guide. When its estimated
            // timeline is exhausted, prefer a nearby upcoming liturgy rather
            // than placing someone arriving for it at the previous dismissal.
            // This is a ranking rule, not evidence that the earlier service ended.
            if (estimate?.atEndOfGuide == true && upcoming != null) return upcoming
            return ContextSuggestion(service, ServiceRelation.InProgress, start,
                estimate?.stageId, estimate?.elapsedInStageMillis ?: 0)
        }

        return upcoming
    }

    companion object {
        val MAX_STARTED_AGE: Duration = Duration.ofMinutes(150)
        val MAX_UPCOMING_DISTANCE: Duration = Duration.ofMinutes(120)
    }
}
