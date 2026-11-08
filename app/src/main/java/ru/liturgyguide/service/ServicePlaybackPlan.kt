package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.model.ReadingDay
import java.security.MessageDigest
import java.time.LocalDate

/** Editorial reading aid. Its duration is not a measurement of singing or worship. */
sealed class PlaybackSegment {
    abstract val stageId: String

    data class Text(
        override val stageId: String,
        val trackId: String,
        val lineId: String,
        val durationBaseMillis: Long
    ) : PlaybackSegment()

    data class Wait(override val stageId: String, val durationBaseMillis: Long) : PlaybackSegment()
    data class MissingReading(override val stageId: String, val serviceDate: LocalDate) : PlaybackSegment()
    data class End(override val stageId: String) : PlaybackSegment()
}

/**
 * One dated, language-independent route through text and editorial waiting.
 * A new entry starts at the beginning of its stage, including its full budget.
 * An estimate from the parish schedule must never masquerade as a detected line.
 */
class ServicePlaybackPlan private constructor(
    val serviceDate: LocalDate,
    val stages: List<LiturgyStage>,
    val tracksByStage: Map<String, List<PrayerTrack>>,
    val segments: List<PlaybackSegment>,
    val fingerprint: String
) {
    val contentVersion: Int get() = CONTENT_VERSION
    private val stageIndexes = stages.associateBy { it.id }
    private val firstByStage = segments.withIndex().groupBy { it.value.stageId }.mapValues { it.value.first().index }
    private val tracksById = tracksByStage.values.flatten().associateBy { it.id }
    private val lineIndexes = segments.withIndex().mapNotNull { (index, segment) ->
        (segment as? PlaybackSegment.Text)?.let { (it.trackId to it.lineId) to index }
    }.toMap()

    fun indexOfStage(stageId: String): Int? = firstByStage[stageId]
    fun indexOfLine(trackId: String, lineId: String): Int? = lineIndexes[trackId to lineId]
    fun indexOfTrack(trackId: String): Int? = segments.indexOfFirst {
        it is PlaybackSegment.Text && it.trackId == trackId
    }.takeIf { it >= 0 }

    fun stageAt(segmentIndex: Int): LiturgyStage = stageIndexes.getValue(segments[segmentIndex].stageId)
    fun track(trackId: String): PrayerTrack? = tracksById[trackId]
    fun line(text: PlaybackSegment.Text): PlayerLine = requireNotNull(track(text.trackId)?.lines?.find { it.id == text.lineId })

    /** Keep the last available text visible during waiting, a missing reading, and End. */
    fun retainedTextAt(segmentIndex: Int): PlaybackSegment.Text? {
        val stageId = segments[segmentIndex].stageId
        for (index in segmentIndex downTo 0) {
            val segment = segments[index]
            if (segment.stageId != stageId) return null
            if (segment is PlaybackSegment.Text) return segment
        }
        return null
    }

    fun nextStageIndex(segmentIndex: Int): Int? {
        val stageId = segments[segmentIndex].stageId
        return ((segmentIndex + 1)..segments.lastIndex).firstOrNull { segments[it].stageId != stageId }
    }

    internal fun durationQuarterMillis(segmentIndex: Int): Long? = when (val segment = segments[segmentIndex]) {
        is PlaybackSegment.Text -> segment.durationBaseMillis * 4
        is PlaybackSegment.Wait -> segment.durationBaseMillis * 4
        is PlaybackSegment.MissingReading, is PlaybackSegment.End -> null
    }

    companion object {
        /** Bump when segment/cadence semantics change; the hash also covers actual canonical content. */
        const val CONTENT_VERSION = 1
        private val whitespace = Regex("[\\s\\p{Z}]+")

        fun build(stages: List<LiturgyStage>, serviceDate: LocalDate, readingDay: ReadingDay?): ServicePlaybackPlan {
            require(serviceDate.year in 1..9999) { "The reading date must be an ordinary calendar date" }
            require(stages.isNotEmpty()) { "A playback plan needs at least one stage" }
            require(stages.all { it.id.isNotBlank() && it.durationMin > 0 }) { "Stages need IDs and positive budgets" }
            require(stages.map { it.id }.distinct().size == stages.size) { "Stage IDs must be unique" }
            require(stages.map { it.order }.distinct().size == stages.size) { "Stage orders must be unique" }
            val ordered = stages.sortedBy { it.order }.map { stage ->
                stage.copy(texts = stage.texts.map { it.copy(lines = it.lines.toList()) }, listenFor = stage.listenFor.toList())
            }
            // A wrong day's reading is unavailable, never a substitute for this service's date.
            val matchingDay = readingDay?.takeIf { it.date == serviceDate.toString() }
            val tracks = ordered.associate { stage -> stage.id to PrayerTrackFactory.build(stage, matchingDay) }
            val allTracks = tracks.values.flatten()
            require(allTracks.all { it.id.isNotBlank() && it.lines.isNotEmpty() }) { "Tracks need IDs and text" }
            require(allTracks.map { it.id }.distinct().size == allTracks.size) { "Track IDs must be unique" }
            val allLines = allTracks.flatMap { it.lines }
            require(allLines.all { it.id.isNotBlank() && canonical(it).isNotBlank() }) { "Lines need IDs and text" }
            require(allLines.map { it.id }.distinct().size == allLines.size) { "Line IDs must be unique" }

            val segments = mutableListOf<PlaybackSegment>()
            var totalQuarterMillis = 0L
            ordered.forEach { stage ->
                var textMillis = 0L
                tracks.getValue(stage.id).forEach { track ->
                    track.lines.forEach { line ->
                        val duration = lineDurationMillis(line)
                        textMillis = checkedAdd(textMillis, duration)
                        segments += PlaybackSegment.Text(stage.id, track.id, line.id, duration)
                    }
                }
                val missing = stage.id in setOf("epistle", "gospel") && tracks.getValue(stage.id).none { it.isScripture }
                val budget = stage.durationMin.toLong() * 60_000L
                val waitMillis = if (missing) 0 else (budget - textMillis).coerceAtLeast(0)
                if (missing) segments += PlaybackSegment.MissingReading(stage.id, serviceDate)
                else if (waitMillis > 0) segments += PlaybackSegment.Wait(stage.id, waitMillis)
                val duration = checkedAdd(textMillis, waitMillis)
                require(duration <= Long.MAX_VALUE / 4) { "A stage is too long" }
                totalQuarterMillis = checkedAdd(totalQuarterMillis, duration * 4)
            }
            segments += PlaybackSegment.End(ordered.last().id)
            val fingerprint = fingerprint(serviceDate, segments, allTracks)
            return ServicePlaybackPlan(serviceDate, ordered, tracks, segments.toList(), fingerprint)
        }

        private fun canonical(line: PlayerLine): String = line.original?.takeIf { it.isNotBlank() } ?: line.russian

        private fun lineDurationMillis(line: PlayerLine): Long {
            // Punctuation alone is not a word. Unicode spaces and combining Church Slavonic marks
            // must not make the count depend on font, line wrapping, or the selected translation.
            val count = canonical(line).split(whitespace).count { word -> word.any { it.isLetterOrDigit() } }
            return maxOf(4_000L, count.toLong() * 500L)
        }

        private fun checkedAdd(a: Long, b: Long): Long {
            require(b <= Long.MAX_VALUE - a) { "The playback plan is too long" }
            return a + b
        }

        private fun fingerprint(date: LocalDate, segments: List<PlaybackSegment>, tracks: List<PrayerTrack>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            add(CONTENT_VERSION.toString())
            add(date.toString())
            val lines = tracks.flatMap { it.lines }.associateBy { it.id }
            segments.forEach { segment ->
                add(segment.stageId)
                when (segment) {
                    is PlaybackSegment.Text -> {
                        add("text"); add(segment.trackId); add(segment.lineId)
                        add(segment.durationBaseMillis.toString()); add(canonical(lines.getValue(segment.lineId)))
                    }
                    is PlaybackSegment.Wait -> { add("wait"); add(segment.durationBaseMillis.toString()) }
                    is PlaybackSegment.MissingReading -> { add("missing"); add(segment.serviceDate.toString()) }
                    is PlaybackSegment.End -> add("end")
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
