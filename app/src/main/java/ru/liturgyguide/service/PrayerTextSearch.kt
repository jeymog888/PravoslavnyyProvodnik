package ru.liturgyguide.service

import ru.liturgyguide.model.PrayerTrack
import java.text.Normalizer
import java.util.Locale

/** Search folding, not speech confidence or a liturgical interpretation. */
object TextSearch {
    private val marks = Regex("\\p{M}")
    private val punctuation = Regex("[^а-яa-z0-9]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT).replace('ё', 'е').replace(punctuation, " ").trim()
}

data class PrayerTextMatch(val trackIndex: Int, val lineIndex: Int)

/**
 * Locates text after the user chooses a stage. This never changes stage, starts
 * playback or confirms an audio hypothesis. Equal matches retain content order.
 */
object PrayerTextSearch {
    fun find(tracks: List<PrayerTrack>, query: String?): PrayerTextMatch? {
        val words = TextSearch.normalize(query ?: return null).split(' ').filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) return null
        fun matches(value: String): Boolean = TextSearch.normalize(value).let { text -> words.all(text::contains) }

        // A recognizable title outranks a coincidental occurrence in another text.
        tracks.forEachIndexed { trackIndex, track ->
            if (track.lines.isNotEmpty() && matches(track.title)) return PrayerTextMatch(trackIndex, 0)
        }
        tracks.forEachIndexed { trackIndex, track ->
            track.lines.forEachIndexed { lineIndex, line ->
                if (matches(line.russian) || line.original?.let(::matches) == true) {
                    return PrayerTextMatch(trackIndex, lineIndex)
                }
            }
        }
        // A long confirmed anchor can span multiple editorial/display phrases.
        // Keep that track reachable without guessing which fragment was heard.
        tracks.forEachIndexed { trackIndex, track ->
            if (track.lines.isNotEmpty() && matches(buildString {
                    append(track.title)
                    track.lines.forEach { line ->
                        append(' '); append(line.russian)
                        line.original?.let { append(' '); append(it) }
                    }
                })) return PrayerTextMatch(trackIndex, 0)
        }
        return null
    }
}
