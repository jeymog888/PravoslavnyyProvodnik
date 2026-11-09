package ru.liturgyguide.ui

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.PlaybackSegment
import ru.liturgyguide.service.ServicePlaybackPlan

/** A book of the entire service. IDs are independent of language and viewport position. */
internal sealed interface ServiceBookItem {
    val stageId: String
    val key: String

    data class Stage(val stage: LiturgyStage) : ServiceBookItem {
        override val stageId = stage.id
        override val key = "stage:$stageId"
    }
    data class Track(override val stageId: String, val track: PrayerTrack) : ServiceBookItem {
        override val key = "track:${track.id}"
    }
    data class Line(override val stageId: String, val trackId: String, val line: PlayerLine) : ServiceBookItem {
        override val key = "line:${line.id}"
    }
    data class Guidance(override val stageId: String) : ServiceBookItem {
        override val key = "guidance:$stageId"
    }
    data class Missing(override val stageId: String) : ServiceBookItem {
        override val key = "missing:$stageId"
    }
    data class Transition(override val stageId: String) : ServiceBookItem {
        override val key = "transition:$stageId"
    }
    data class End(override val stageId: String) : ServiceBookItem {
        override val key = "end:$stageId"
    }
}

internal class ContinuousServiceFeed(val plan: ServicePlaybackPlan) {
    val items: List<ServiceBookItem> = buildList {
        plan.stages.forEach { stage ->
            add(ServiceBookItem.Stage(stage))
            val tracks = plan.tracksByStage.getValue(stage.id)
            if (tracks.isEmpty()) add(ServiceBookItem.Guidance(stage.id))
            tracks.forEach { track ->
                if (bookShowsTrackHeading(track)) add(ServiceBookItem.Track(stage.id, track))
                track.lines.forEach { add(ServiceBookItem.Line(stage.id, track.id, it)) }
            }
            if (plan.segments.any { it is PlaybackSegment.MissingReading && it.stageId == stage.id }) {
                add(ServiceBookItem.Missing(stage.id))
            } else if (tracks.isNotEmpty() && plan.segments.any { it is PlaybackSegment.Wait && it.stageId == stage.id }) {
                add(ServiceBookItem.Transition(stage.id))
            }
        }
        add(ServiceBookItem.End(plan.stages.last().id))
    }
    private val indexes = items.withIndex().associate { it.value.key to it.index }
    val supportsOriginal = plan.tracksByStage.values.flatten().any { track -> track.lines.any { it.original != null } }

    fun indexOf(key: String): Int? = indexes[key]
    fun stageIndex(stageId: String): Int = requireNotNull(indexes["stage:$stageId"])
    fun stage(item: ServiceBookItem): LiturgyStage = plan.stages.first { it.id == item.stageId }
    fun track(item: ServiceBookItem): PrayerTrack? = when (item) {
        is ServiceBookItem.Line -> plan.track(item.trackId)
        is ServiceBookItem.Track -> item.track
        else -> null
    }

    fun targetIndex(segment: PlaybackSegment): Int = when (segment) {
        is PlaybackSegment.Text -> indexes["line:${segment.lineId}"] ?: stageIndex(segment.stageId)
        is PlaybackSegment.Wait -> indexes["transition:${segment.stageId}"]
            ?: indexes["guidance:${segment.stageId}"] ?: stageIndex(segment.stageId)
        is PlaybackSegment.MissingReading -> requireNotNull(indexes["missing:${segment.stageId}"])
        is PlaybackSegment.End -> items.lastIndex
    }

    /** Headers enter the first actual line; explicit missing-content blocks remain barriers. */
    fun segmentIndex(item: ServiceBookItem): Int = when (item) {
        is ServiceBookItem.Line -> requireNotNull(plan.indexOfLine(item.trackId, item.line.id))
        is ServiceBookItem.Track -> requireNotNull(plan.indexOfTrack(item.track.id))
        is ServiceBookItem.Missing -> plan.segments.indexOfFirst { it is PlaybackSegment.MissingReading && it.stageId == item.stageId }
        is ServiceBookItem.Transition -> plan.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == item.stageId }
        is ServiceBookItem.End -> plan.segments.lastIndex
        else -> requireNotNull(plan.indexOfStage(item.stageId))
    }

    /** The first intersecting row, including a paragraph taller than the viewport. */
    fun visibleItem(firstIndex: Int): ServiceBookItem = items[firstIndex.coerceIn(items.indices)]

    /** An inactive transition has no text; it can never restore an unrelated old position. */
    fun readableBefore(item: ServiceBookItem.Transition): ServiceBookItem = items.take(requireNotNull(indexOf(item.key)))
        .lastOrNull { it.stageId == item.stageId && it is ServiceBookItem.Line }
        ?: items[stageIndex(item.stageId)]
}

/** Short spoken exchanges read as a dialogue, without a heading above every answer. */
internal fun bookShowsTrackHeading(track: PrayerTrack): Boolean =
    track.isScripture || track.lines.size > 2 || !track.contextNote.isNullOrBlank()

/** Preserve progress inside a long paragraph when its translated height changes. */
internal fun translatedParagraphOffset(oldOffset: Int, oldHeight: Int, newHeight: Int): Int {
    if (oldHeight <= 0 || newHeight <= 0) return 0
    val fraction = oldOffset.toDouble().coerceAtLeast(0.0) / oldHeight
    return (fraction * newHeight).toInt().coerceIn(0, newHeight - 1)
}

/** Display progress only. Reach the last line before the engine leaves this paragraph. */
internal fun timedParagraphOffset(itemHeight: Int, viewportHeight: Int, elapsedQuarterMillis: Long,
    durationBaseMillis: Long, startOffset: Int = 0, startElapsedQuarterMillis: Long = 0): Int {
    val maximum = (itemHeight - viewportHeight).coerceAtLeast(0)
    if (maximum == 0 || durationBaseMillis <= 0) return 0
    val start = startOffset.coerceIn(0, maximum)
    val duration = durationBaseMillis.toDouble() * 4.0
    val baseline = startElapsedQuarterMillis.toDouble().coerceIn(0.0, duration)
    val remaining = duration - baseline
    if (remaining <= 0.0) return start
    val progress = ((elapsedQuarterMillis.toDouble() - baseline) / (remaining * .85)).coerceIn(0.0, 1.0)
    return start + ((maximum - start) * progress).toInt()
}
