package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.model.ReadingDay
import java.security.MessageDigest

/** Builds content only. Track selection never starts a timer or changes a service stage. */
object PrayerTrackFactory {
    fun build(stage: LiturgyStage, readingDay: ReadingDay?): List<PrayerTrack> {
        val tracks = mutableListOf<PrayerTrack>()
        val identities = mutableMapOf<String, Int>()
        fun trackId(kind: String, vararg identity: String): String {
            val base = "${stage.id}:$kind:${digest(*identity)}"
            return uniqueId(base, identities)
        }

        // At reading stages these are the introductory calls. Keep them before
        // Scripture so the next-text action never goes back to the introduction.
        stage.texts.forEach { text ->
            val pairs = if (text.lines.isEmpty()) listOf(text.original to text.translation)
                else text.lines.map { it.original to it.translation }
            // Validated assets cannot reach this case. Keep manually constructed
            // or legacy empty content out of the player instead of a blank screen.
            if (pairs.any { (original, russian) -> original.isBlank() || russian.isBlank() }) return@forEach
            val id = trackId("prayer", text.type, pairs.joinToString("\n") { it.first.trim() })
            val lineIdentities = mutableMapOf<String, Int>()
            tracks += PrayerTrack(
                id = id,
                title = text.title?.trim()?.takeIf { it.isNotEmpty() } ?: text.type,
                subtitle = "Смысл по-русски",
                lines = pairs.mapIndexed { index, (original, russian) ->
                    PlayerLine(uniqueId("$id:${digest(original.trim())}", lineIdentities), russian.trim(), original.trim(),
                        speaker = text.lines.getOrNull(index)?.speaker?.trim()?.takeIf { it.isNotEmpty() })
                },
                sourceUrl = httpsUrl(text.sourceUrl) ?: httpsUrl(stage.sourceUrl),
                isExcerpt = text.isExcerpt,
                explanation = text.explanation,
                contextNote = text.contextNote?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        readingDay?.readings.orEmpty().filter { reading ->
            (stage.id == "epistle" && reading.kind == "epistle") ||
                (stage.id == "gospel" && reading.kind == "gospel")
        }.forEach { reading ->
            // Keep the source's verse boundaries, then divide long verses only
            // for display. The canonical asset and every source word stay intact.
            val verses = reading.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (verses.isEmpty()) return@forEach
            val id = trackId("reading", readingDay!!.date, reading.kind, reading.reference, verses.joinToString("\n"))
            val lineIdentities = mutableMapOf<String, Int>()
            tracks += PrayerTrack(
                id = id,
                title = reading.reference,
                subtitle = listOfNotNull(reading.occasion?.takeIf { it.isNotBlank() },
                    reading.translation?.takeIf { it.isNotBlank() } ?: "Синодальный перевод").joinToString(" · "),
                lines = verses.flatMap(::displayLines).map { verse ->
                    PlayerLine(uniqueId("$id:${digest(verse)}", lineIdentities), verse, null)
                },
                sourceUrl = httpsUrl(reading.sourceUrl) ?: httpsUrl(readingDay.sourceUrl),
                isExcerpt = false,
                explanation = reading.explanation,
                contextNote = readingDay.note.takeIf { it.isNotBlank() },
                isScripture = true
            )
        }
        return tracks
    }

    private fun httpsUrl(value: String?): String? = value?.trim()?.takeIf { it.startsWith("https://") }

    private const val MAX_DISPLAY_LINE_LENGTH = 120
    private val whitespace = Regex("\\s+")
    private val abbreviations = setOf("св.", "мф.", "мк.", "лк.", "ин.", "т.д.", "т.п.", "т.е.", "т.к.", "т.ч.", "г.", "ст.", "см.", "др.", "пр.")
    private val singleInitial = Regex("[А-Яа-яA-Za-z]\\.")

    private fun displayLines(verse: String): List<String> {
        val words = verse.trim().split(whitespace).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            var limit = start
            var length = 0
            var strongBoundary = -1
            var commaBoundary = -1
            while (limit < words.size) {
                val addedLength = words[limit].length + if (limit > start) 1 else 0
                if (length + addedLength > MAX_DISPLAY_LINE_LENGTH && limit > start) break
                length += addedLength
                val word = words[limit].trimEnd('"', '\'', '»', '”', ')', ']')
                if (length >= MAX_DISPLAY_LINE_LENGTH / 2 && mayBreakAfter(words[limit])) {
                    if (word.lastOrNull() in setOf('.', '!', '?', ';', ':')) strongBoundary = limit + 1
                    if (word.endsWith(',')) commaBoundary = limit + 1
                }
                limit++
            }
            var end = when {
                limit == words.size -> limit
                strongBoundary > start -> strongBoundary
                commaBoundary > start -> commaBoundary
                else -> limit
            }
            // Keep references and abbreviations attached to the following word.
            while (end > start + 1 && end < words.size && !mayBreakAfter(words[end - 1])) end--
            result += words.subList(start, end).joinToString(" ")
            start = end
        }
        return result
    }

    private fun mayBreakAfter(word: String): Boolean {
        val bare = word.trimEnd('"', '\'', '»', '”', ')', ']')
        return bare.lowercase(java.util.Locale.ROOT) !in abbreviations &&
            !singleInitial.matches(bare) && bare !in setOf("-", "–", "—")
    }

    private fun uniqueId(base: String, counts: MutableMap<String, Int>): String {
        val occurrence = counts.getOrDefault(base, 0) + 1
        counts[base] = occurrence
        return if (occurrence == 1) base else "$base:$occurrence"
    }

    private fun digest(vararg parts: String): String {
        // Length prefixes distinguish ["ab", "c"] from ["a", "bc"]. Identity
        // depends on content, never list position or the currently shown language.
        val encoded = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
