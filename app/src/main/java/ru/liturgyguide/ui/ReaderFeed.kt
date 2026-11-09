package ru.liturgyguide.ui

import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.PlaybackSegment

/** Presentation of one stage, with the plan's existing identities and ordering. */
internal sealed interface ReaderFeedItem {
    val key: String

    data class Header(val track: PrayerTrack) : ReaderFeedItem {
        override val key = "header:${track.id}"
    }

    data class Line(val trackId: String, val line: PlayerLine) : ReaderFeedItem {
        override val key = "line:${line.id}"
    }

    data class Continuation(val stageId: String) : ReaderFeedItem {
        override val key = "continuation:$stageId"
    }
}

internal class ReaderFeed(val stageId: String, tracks: List<PrayerTrack>) {
    val items: List<ReaderFeedItem> = buildList {
        tracks.forEach { track ->
            add(ReaderFeedItem.Header(track))
            track.lines.forEach { add(ReaderFeedItem.Line(track.id, it)) }
        }
        add(ReaderFeedItem.Continuation(stageId))
    }
    val lines = items.filterIsInstance<ReaderFeedItem.Line>()
    val continuationIndex = items.lastIndex
    val supportsOriginal = tracks.any { track -> track.lines.any { it.original != null } }
    private val lineIndexes = items.withIndex().mapNotNull { (index, item) ->
        (item as? ReaderFeedItem.Line)?.let { (it.trackId to it.line.id) to index }
    }.toMap()

    fun lineIndex(trackId: String, lineId: String): Int? = lineIndexes[trackId to lineId]

    /** Waiting and barriers never inherit a selected phrase from retainedTextAt. */
    fun targetIndex(segment: PlaybackSegment): Int = (segment as? PlaybackSegment.Text)
        ?.takeIf { it.stageId == stageId }
        ?.let { lineIndex(it.trackId, it.lineId) } ?: continuationIndex

    fun adjacentLine(segment: PlaybackSegment, direction: Int): ReaderFeedItem.Line? {
        val text = segment as? PlaybackSegment.Text ?: return null
        if (text.stageId != stageId || direction !in listOf(-1, 1)) return null
        val current = lines.indexOfFirst { it.trackId == text.trackId && it.line.id == text.lineId }
        return if (current < 0) null else lines.getOrNull(current + direction)
    }
}

internal fun readerLineText(line: PlayerLine, original: Boolean): String =
    if (original) line.original ?: line.russian else line.russian

internal fun readerTrackLanguage(track: PrayerTrack, original: Boolean): String {
    if (track.isScripture) return "${track.subtitle}. Церковнославянский текст пока не добавлен"
    val kind = if (track.isExcerpt) "Фрагмент молитвы" else "Полный текст"
    val language = if (original && track.lines.any { it.original != null }) "Церковнославянский текст"
        else "Русское изложение смысла молитвы"
    return "$kind · $language"
}

/** Any visible part of the phrase is enough; a long phrase need not fit in full. */
internal fun readerTextIntersectsViewport(textTop: Float, textBottom: Float, viewportTop: Float, viewportBottom: Float): Boolean =
    textBottom > textTop && viewportBottom > viewportTop && textBottom > viewportTop && textTop < viewportBottom
