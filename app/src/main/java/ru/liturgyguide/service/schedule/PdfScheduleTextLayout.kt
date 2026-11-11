package ru.liturgyguide.service.schedule

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real decoded PDF glyphs, not OCR guesses. Coordinates are direction-adjusted PDF points. */
internal data class PdfScheduleGlyph(val text: String, val left: Float, val baseline: Float,
    val width: Float, val fontSize: Float)

internal object PdfScheduleTextLayout {
    private data class Word(val text: String, val left: Float, val right: Float, val baseline: Float, val fontSize: Float)
    private class Row(val baseline: Float, val fontSize: Float, val glyphs: MutableList<PdfScheduleGlyph>)
    private val hour = Regex("(?:[01]?\\d|2[0-3])")
    private val minute = Regex("[0-5]\\d")
    private val clock = Regex("(?<![\\d./:])(?:[01]?\\d|2[0-3])[:.][0-5]\\d(?![\\d./])")
    private val fullDate = Regex("(?<!\\d)([0-3]?\\d)[./]([01]?\\d)[./](20\\d{2}|\\d{2})(?!\\d)")
    private val service = Regex("(?iu)литурги|утрен|вечерн|богослужен|всенощ|молеб|панихид|исповед|акафист|водосвят|\\bчасы\\b")
    private val suspiciousClock = Regex("(?<![\\d./])\\d{3,4}(?![\\d./])|\\d[°®%]")

    fun lines(input: List<PdfScheduleGlyph>): List<ScheduleDocumentLine> {
        val glyphs = input.filter { it.text.isNotEmpty() && listOf(it.left, it.baseline, it.width, it.fontSize).all(Float::isFinite) &&
            it.width >= 0 && it.fontSize in 1f..200f }
            .distinctBy { listOf(it.text, (it.left * 20).roundToInt(), (it.baseline * 20).roundToInt()) }
        val rows = mutableListOf<Row>()
        glyphs.sortedBy { it.baseline }.forEach { glyph ->
            val row = rows.lastOrNull { abs(it.baseline - glyph.baseline) <= minOf(it.fontSize, glyph.fontSize) * .12f }
            if (row == null) rows += Row(glyph.baseline, glyph.fontSize, mutableListOf(glyph)) else row.glyphs += glyph
        }
        val words = rows.flatMap { row ->
            val output = mutableListOf<Word>()
            var current: Word? = null
            fun flush() { current?.let { output += it }; current = null }
            row.glyphs.sortedBy { it.left }.forEach { glyph ->
                if (glyph.text.isBlank()) { flush(); return@forEach }
                val previous = current
                if (previous == null || glyph.left - previous.right > minOf(previous.fontSize, glyph.fontSize) * .22f ||
                    abs(previous.fontSize - glyph.fontSize) > previous.fontSize * .12f) {
                    flush()
                    current = Word(glyph.text, glyph.left, glyph.left + glyph.width, glyph.baseline, glyph.fontSize)
                } else current = previous.copy(text = previous.text + glyph.text, right = maxOf(previous.right, glyph.left + glyph.width))
            }
            flush()
            output
        }
        val removed = mutableSetOf<Word>()
        val replacements = mutableMapOf<Word, Word>()
        val hours = words.filter { hour.matches(it.text) }
        val minutes = words.filter { minute.matches(it.text) }
        hours.forEach { h ->
            // Two decoded digits, visibly smaller and raised, immediately follow the hour.
            // A plain "800", a degree sign, or a footnote without a service title is never converted.
            val candidates = minutes.filter { m -> m != h && m !in removed &&
                m.fontSize in (h.fontSize * .4f)..(h.fontSize * .82f) &&
                h.baseline - m.baseline in (h.fontSize * .16f)..(h.fontSize * .60f) &&
                m.left - h.right in (-h.fontSize * .12f)..(h.fontSize * .25f) }
            val m = candidates.singleOrNull() ?: return@forEach
            val baseRow = words.filter { abs(it.baseline - h.baseline) <= h.fontSize * .12f }
            val preceding = baseRow.filter { it.right <= h.left }.maxByOrNull { it.right }
            if (preceding != null && h.left - preceding.right < h.fontSize * .65f) return@forEach
            val following = baseRow.filter { it.left >= m.right - 1f && it.left - m.right < h.fontSize * 30 }
                .sortedBy { it.left }.take(12).joinToString(" ") { it.text }
            if (!service.containsMatchIn(following)) return@forEach
            removed += m
            replacements[h] = h.copy(text = "${h.text.padStart(2, '0')}:${m.text}", right = m.right)
        }
        return ScheduleOcrLayout.lines(words.filterNot { it in removed }.map { replacements[it] ?: it }.map {
            ScheduleWord(it.text, (it.left * 10).roundToInt(), ((it.baseline - it.fontSize) * 10).roundToInt(),
                (it.right * 10).roundToInt(), (it.baseline * 10).roundToInt())
        })
    }

    /** A broken/hidden OCR layer cannot replace the rendered-page fallback on one plausible clock. */
    fun isReliable(lines: List<ScheduleDocumentLine>): Boolean {
        if (lines.isEmpty() || lines.sumOf { it.text.length } > 160_000 || lines.any { '\uFFFD' in it.text }) return false
        var date: LocalDate? = null
        val datedStarts = mutableSetOf<LocalDate>()
        for (line in lines) {
            val matches = fullDate.findAll(line.text).toList()
            if (matches.isNotEmpty()) {
                if (matches.size != 1) { date = null; continue }
                val values = matches.single().groupValues
                date = runCatching { LocalDate.of(values[3].toInt().let { if (values[3].length == 2) 2000 + it else it },
                    values[2].toInt(), values[1].toInt()) }.getOrNull()
                if (date == null) return false
            }
            if (line.text.contains(Regex("(?iu)кажд(?:ый|ая|ое|ую|ые)"))) date = null
            if (service.containsMatchIn(line.text) && date != null) {
                val withoutDates = fullDate.replace(line.text, "")
                if (suspiciousClock.containsMatchIn(clock.replace(withoutDates, ""))) return false
                if (clock.containsMatchIn(withoutDates)) datedStarts += date
            }
        }
        return datedStarts.size >= 3
    }
}
