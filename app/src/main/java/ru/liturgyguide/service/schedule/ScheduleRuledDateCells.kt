package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.Locale

/** A row reader avoids copying a complete ARGB bitmap into a second IntArray. */
internal interface ScheduleOcrRaster {
    val width: Int
    val height: Int
    fun readArgbRow(y: Int, row: IntArray)
}

/** A deliberately narrow guard for one black, axis-aligned date / service / time grid.
 * null means no supported grid was observed, never that flat OCR is generally safe.
 * An observed but unproved grid blocks the source, without inventing a withdrawal. */
internal object ScheduleRuledDateCells {
    private data class Rule(val first: Int, val last: Int, val start: Int, val end: Int)
    private data class Span(val first: Int, val last: Int)
    private val box = Regex("(?:^|[;\\s])bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
    private val confidence = Regex("(?:^|[;\\s])x_wconf\\s+(\\d+)")
    private const val months = "январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе]"
    private val monthYear = Regex("(?iu)(?<![а-яё])($months)\\s+(20\\d{2})(?!\\d)")
    private val dated = Regex("(?iu)(?<!\\d)\\d{1,2}\\s+(?:$months)(?![а-яё])")
    private val dateCell = Regex("(?iu)^(\\d{1,2})\\s+($months)(?:\\s+(20\\d{2}))?\\s+(${ScheduleWeekdays.dateWords})\\.?$")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val namedService = Regex("(?iu)(литурги|всенощ|(?<![а-яё])(?:вечерн|утрен)[яиюе](?![а-яё])|исповед(?!ни[кч])|молеб|панихид|лития|акафист)")
    private val roughClock = Regex("(?<!\\d)(?!20\\d{2}(?!\\d))(?:[01]?\\d|2[0-3])[:.]?\\s*[0-5]\\d(?!\\d)")
    private val monthPrefixes = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    fun inspect(raster: ScheduleOcrRaster, hocr: String): ScheduleDocumentPage? {
        if (hocr.length > 4_000_000) return ScheduleDocumentPage(emptyList(),
            ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE, sourceTruncated = true)
        val nodes = Jsoup.parse(hocr).select(".ocrx_word,.ocr_word").filter { it.text().isNotBlank() }
        val raw = nodes.joinToString(" ") { it.text() }
        // Readable dates are not admission evidence: losing them must still audit the pixels.
        // A repeated "Часы работы" cannot stand in for a named liturgical service.
        if (!monthYear.containsMatchIn(raw) || !namedService.containsMatchIn(raw) ||
            !roughClock.containsMatchIn(raw)) return null
        fun refused() = ScheduleDocumentPage(emptyList(), rawOcrEvidence = listOf(raw),
            ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE)
        val width = raster.width; val height = raster.height
        if (width !in 80..30_000 || height !in 80..30_000 ||
            width.toLong() * height > ScheduleDocumentExtractor.MAX_IMAGE_PIXELS || nodes.size > 20_000) return refused()
        val dark = BooleanArray(width * height)
        val ink = BooleanArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            raster.readArgbRow(y, row)
            for (x in 0 until width) {
                val color = row[x]
                dark[y * width + x] = color ushr 24 >= 200 &&
                    (color ushr 16 and 255) <= 110 && (color ushr 8 and 255) <= 110 && (color and 255) <= 110
                ink[y * width + x] = color ushr 24 >= 200 &&
                    minOf(color ushr 16 and 255, color ushr 8 and 255, color and 255) <= 160
            }
        }
        fun solid(y: Int, left: Int, right: Int) = (left..right).all { dark[y * width + it] }
        val vertical = (0 until width).mapNotNull { x ->
            var start = 0; var bestStart = 0; var bestEnd = -1
            for (y in 0..height) {
                if (y < height && dark[y * width + x]) continue
                if (y - start > bestEnd - bestStart + 1) { bestStart = start; bestEnd = y - 1 }
                start = y + 1
            }
            if (bestEnd - bestStart + 1 >= maxOf(80, height / 2)) Rule(x, x, bestStart, bestEnd) else null
        }
        val rails = mutableListOf<Rule>()
        vertical.forEach { line ->
            val previous = rails.lastOrNull()
            if (previous != null && previous.last + 1 == line.first) rails[rails.lastIndex] = previous.copy(
                last = line.last, start = minOf(previous.start, line.start), end = maxOf(previous.end, line.end))
            else rails += line
        }
        fun recognizedWords(): List<ScheduleWord>? = nodes.map { node ->
            val match = box.find(node.attr("title")) ?: return null
            val values = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            val (l, t, r, b) = values
            if (l < 0 || t < 0 || r > width || b > height || r <= l || b <= t) return null
            ScheduleWord(node.text().trim(), l, t, r, b,
                confidence.find(node.attr("title"))?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }
        fun existingColumnGrid(): Boolean {
            if (rails.size !in 5..6) return false
            val tolerance = maxOf(2, rails.maxOf { it.last - it.first + 1 })
            val top = rails.minOf { it.start }; val bottom = rails.maxOf { it.end }
            if (rails.any { it.last - it.first + 1 > maxOf(3, width / 180) } ||
                rails.maxOf { it.start } - top > tolerance || bottom - rails.minOf { it.end } > tolerance ||
                rails.zipWithNext().any { (a, b) -> b.first - a.last < maxOf(16, width / 12) } ||
                rails.any { rail -> (rail.first..rail.last).none { x -> (top..bottom).all { y -> dark[y * width + x] } } }) return false
            val left = rails.first().first; val right = rails.last().last
            if (right - left < width / 2) return false
            val boundaries = spans((top..bottom).filter { solid(it, left, right) })
            if (boundaries.size !in 4..80 || boundaries.first().first - top > tolerance ||
                bottom - boundaries.last().last > tolerance ||
                boundaries.zipWithNext().any { (a, b) -> b.first - a.last < 12 }) return false
            val words = recognizedWords() ?: return false
            val cells = boundaries.zipWithNext().map { mutableListOf<ScheduleWord>() }
            val columns = rails.zipWithNext()
            for (word in words) {
                if (word.bottom <= top || word.top > bottom) continue
                val band = boundaries.zipWithNext().indexOfFirst { (a, b) -> word.top > a.last && word.bottom <= b.first }
                if (band < 0 || columns.count { (a, b) -> word.left > a.last && word.right <= b.first } != 1) return false
                cells[band] += word
            }
            val legacyDate = Regex("(?iu)^\\d{1,2}\\s+(?:${ScheduleWeekdays.dateWords})\\.?$")
            for (cell in cells) {
                val dates = cell.filter { it.right <= rails[1].first }
                val clocks = cell.filter { ScheduleClocks.pattern.matches(it.text) }
                if (dates.any { it.confidence < 55 } ||
                    !legacyDate.matches(ScheduleOcrLayout.lines(dates).joinToString(" ") { it.text }) ||
                    clocks.isEmpty() || clocks.any { it.left <= rails[1].last || it.right > rails[2].first } ||
                    !service.containsMatchIn(cell.filter { it.left > rails[2].last && it.right <= rails[3].first }
                        .joinToString(" ") { it.text })) return false
            }
            // This exact closed layout already has a strict reader. Its untouched full
            // hOCR keeps the old safety path; no three-column permission is inherited.
            return ScheduleOcrLayout.hasProvenServiceColumn(cells.flatten())
        }
        if (existingColumnGrid()) return null
        if (rails.size != 4) {
            // A damaged/second grid must not silently switch back to flat inheritance.
            var longRows = 0
            for (y in 0 until height) {
                var run = 0
                for (x in 0 until width) {
                    run = if (dark[y * width + x]) run + 1 else 0
                    if (run >= width / 2) { longRows++; break }
                }
            }
            return if (rails.size >= 2 || longRows >= 4) refused() else null
        }
        val tolerance = maxOf(2, rails.maxOf { it.last - it.first + 1 })
        if (rails.any { it.last - it.first + 1 > maxOf(3, width / 180) } ||
            rails.maxOf { it.start } - rails.minOf { it.start } > tolerance ||
            rails.maxOf { it.end } - rails.minOf { it.end } > tolerance ||
            rails.zipWithNext().any { (a, b) -> b.first - a.last < maxOf(16, width / 12) }) return refused()
        val left = rails.first().first; val right = rails.last().last
        val top = rails.minOf { it.start }; val bottom = rails.maxOf { it.end }
        val inkLeft = rails[0].last + tolerance + 1
        val inkRight = rails[1].first - tolerance
        if (right - left < width / 2 || rails.any { rail ->
                (rail.first..rail.last).none { x -> (top..bottom).all { y -> dark[y * width + x] } }
            }) return refused()
        val boundaries = spans((top..bottom).filter { solid(it, left, right) })
        if (boundaries.size !in 4..80 || boundaries.first().first - top > tolerance ||
            bottom - boundaries.last().last > tolerance ||
            boundaries.zipWithNext().any { (a, b) -> b.first - a.last < 12 }) return refused()
        val cells = boundaries.zipWithNext()
        val partials = spans((top..bottom).filter { y ->
            solid(y, rails[1].first, right) && boundaries.none { y in it.first..it.last }
        })
        for (partial in partials) {
            val band = cells.singleOrNull { (a, b) ->
                partial.first > a.last && partial.last < b.first
            } ?: return refused()
            // A supported merged date-cell has all date ink above its right-only dividers.
            // Ink on/below a divider could be a damaged full border and a second date.
            for (y in partial.first until band.second.first)
                if ((inkLeft until inkRight).any { ink[y * width + it] }) return refused()
        }
        val words = recognizedWords() ?: return refused()
        val before = mutableListOf<ScheduleWord>(); val after = mutableListOf<ScheduleWord>()
        val bands = cells.map { mutableListOf<ScheduleWord>() }
        for (word in words) {
            if (word.bottom <= top) before += word
            else if (word.top > bottom) after += word
            else {
                val band = cells.indexOfFirst { (a, b) -> word.top > a.last && word.bottom <= b.first }
                if (band < 0 || word.left <= rails.first().last || word.right > rails.last().first ||
                    rails.drop(1).dropLast(1).any { word.left <= it.last && word.right > it.first } ||
                    partials.any { word.top <= it.last && word.bottom > it.first }) return refused()
                bands[band] += word
            }
        }
        val headerLines = ScheduleOcrLayout.lines(before)
        val footerLines = ScheduleOcrLayout.lines(after)
        // An outside-grid dated service needs its own separately proved structure.
        if (listOf(headerLines, footerLines).any { lines ->
                val text = lines.joinToString(" ") { it.text }
                service.containsMatchIn(text) && roughClock.containsMatchIn(text)
            }) return refused()
        val headers = monthYear.findAll(headerLines.joinToString(" ") { it.text }).toList()
        if (headers.size != 1) return refused()
        val year = headers.single().groupValues[2].toInt()
        val output = mutableListOf<ScheduleDocumentLine>()
        // rawOcrEvidence is audit-only in the parser. An observed cancellation must
        // therefore block this structural path directly, even at low OCR confidence.
        if (ScheduleCancellation.containsMatchIn(raw)) return refused()
        output += headerLines
        output += ScheduleDocumentLine("", endsTable = true)
        for ((index, band) in bands.withIndex()) {
            val dateWords = band.filter { it.right <= rails[1].first }
            val body = band.filter { it.left > rails[1].last }
            if (dateWords.size !in 3..8 || body.isEmpty() || dateWords.any { it.confidence < 55 }) return refused()
            val dateText = ScheduleOcrLayout.lines(dateWords).joinToString(" ") { it.text }
            val printed = dateCell.matchEntire(dateText) ?: return refused()
            // Unrecognized date-cell ink cannot hide a second day after a lost border.
            // Coverage is checked against every original word box, with one raster pixel
            // of antialias tolerance; no word or connected ink component is synthesized.
            val upper = boundaries[index].last; val lower = boundaries[index + 1].first
            for (y in upper + tolerance + 1 until lower - tolerance) for (x in inkLeft until inkRight) {
                if (ink[y * width + x] && dateWords.none {
                        x in it.left - 1..it.right && y in it.top - 1..it.bottom
                    }) return refused()
            }
            val month = monthPrefixes.indexOfFirst { printed.groupValues[2].lowercase(Locale.ROOT).startsWith(it) } + 1
            val date = runCatching { LocalDate.of(printed.groupValues[3].toIntOrNull() ?: year,
                month, printed.groupValues[1].toInt()) }.getOrNull() ?: return refused()
            if (!ScheduleWeekdays.agrees(printed.groupValues[4], date)) return refused()
            val bodyLines = ScheduleOcrLayout.lines(body)
            if (bodyLines.any { dated.containsMatchIn(it.text) }) return refused()
            output += ScheduleDocumentLine("", startsDayContainer = true)
            output += ScheduleDocumentLine(dateText, dateWords.minOf { it.confidence },
                temporalConfidence = dateWords.minOf { it.confidence })
            output += bodyLines
            output += ScheduleDocumentLine("", endsDayContainer = true)
        }
        output += ScheduleDocumentLine("", endsTable = true)
        output += footerLines
        return ScheduleDocumentPage(output, rawOcrEvidence = listOf(raw))
    }

    private fun spans(values: List<Int>): List<Span> {
        val result = mutableListOf<Span>()
        values.forEach { value ->
            val previous = result.lastOrNull()
            if (previous != null && previous.last + 1 == value) result[result.lastIndex] = previous.copy(last = value)
            else result += Span(value, value)
        }
        return result
    }
}
