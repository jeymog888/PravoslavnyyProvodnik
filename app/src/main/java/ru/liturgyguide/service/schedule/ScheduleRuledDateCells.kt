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
    enum class CellKind { DATE, TIME }
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    interface CellCapture
    interface CellRequest {
        val band: Int
        val kind: CellKind
        val sourceWidth: Int
        val sourceHeight: Int
        val outer: Rect
        val crop: Rect
        fun capture(width: Int, height: Int, hocr: String): CellCapture
    }
    private class OwnedRequest(val owner: Any, override val band: Int, override val kind: CellKind,
        override val sourceWidth: Int, override val sourceHeight: Int, override val outer: Rect,
        override val crop: Rect) : CellRequest {
        var count = 0
        var closed = false
        override fun capture(width: Int, height: Int, hocr: String): CellCapture {
            check(!closed)
            count++
            return OwnedCapture(this, width, height, hocr)
        }
    }
    private class OwnedCapture(val request: OwnedRequest, val width: Int, val height: Int, val hocr: String) : CellCapture
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

    fun inspect(raster: ScheduleOcrRaster, hocr: String,
        readCell: ((CellRequest) -> CellCapture?)? = null): ScheduleDocumentPage? {
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
        if (readCell != null) return recoverCells(width, height, rails, boundaries, partials, ink, words, raw, hocr.length, readCell)
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

    private fun recoverCells(width: Int, height: Int, rails: List<Rule>, boundaries: List<Span>, partials: List<Span>,
        ink: BooleanArray, original: List<ScheduleWord>, raw: String, originalCharacters: Int,
        readCell: (CellRequest) -> CellCapture?): ScheduleDocumentPage {
        val evidence = mutableListOf(raw)
        fun refused(truncated: Boolean = false) = ScheduleDocumentPage(emptyList(), rawOcrEvidence = evidence.toList(),
            ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE, sourceTruncated = truncated)
        if (ScheduleCancellation.containsMatchIn(raw) || rails.any { it.last - it.first + 1 > 3 } ||
            boundaries.any { it.last - it.first + 1 > 3 }) return refused()
        val cells = boundaries.zipWithNext()
        val before = mutableListOf<ScheduleWord>(); val after = mutableListOf<ScheduleWord>()
        val bands = cells.map { List(3) { mutableListOf<ScheduleWord>() } }
        for (word in original) {
            if (word.bottom <= boundaries.first().first) before += word
            else if (word.top > boundaries.last().last) after += word
            else {
                val column = rails.zipWithNext().indexOfFirst { (a, b) -> word.left > a.last && word.right <= b.first }
                if (column < 0) return refused()
                val owners = cells.withIndex().filter { (_, edges) ->
                    word.bottom > edges.first.last + 1 && word.top < edges.second.first
                }
                if (owners.size != 1) return refused()
                val (index, edges) = owners.single()
                val contained = if (column == 1) word.top > edges.first.last && word.bottom <= edges.second.first
                    else word.top >= edges.first.first && word.bottom <= edges.second.last + 1
                if (!contained || column == 1 && partials.any { word.top <= it.last && word.bottom > it.first }) return refused()
                bands[index][column].add(word)
            }
        }
        val header = ScheduleOcrLayout.lines(before); val footer = ScheduleOcrLayout.lines(after)
        if (listOf(header, footer).any { lines ->
                val text = lines.joinToString(" ") { it.text }
                service.containsMatchIn(text) && roughClock.containsMatchIn(text)
            }) return refused()
        val headers = monthYear.findAll(header.joinToString(" ") { it.text }).toList()
        if (headers.size != 1) return refused()
        val year = headers.single().groupValues[2].toInt()
        if (bands.any { unsafeColumn(it[0]) || unsafeColumn(it[2]) }) return refused()
        val owner = Any()
        val requests = cells.flatMapIndexed { band, (upper, lower) ->
            listOf(CellKind.DATE to 0, CellKind.TIME to 2).map { (kind, column) ->
                val outer = Rect(rails[column].first, upper.first, rails[column + 1].last + 1, lower.last + 1)
                if (outer.left !in 0 until outer.right || outer.right > width ||
                    outer.top !in 0 until outer.bottom || outer.bottom > height || outer.width < 16 || outer.height < 16)
                    return refused()
                OwnedRequest(owner, band, kind, width, height, outer,
                    Rect(outer.left + 4, outer.top + 4, outer.right - 4, outer.bottom - 4))
            }
        }
        if (requests.size > 158 || requests.sumOf { it.crop.width.toLong() * it.crop.height } > width.toLong() * height ||
            requests.indices.any { a -> (a + 1 until requests.size).any { b -> overlaps(requests[a].crop, requests[b].crop) } })
            return refused()
        val captures = cells.map { List(2) { mutableListOf<ScheduleWord>() } }
        var characters = originalCharacters; var wordCount = original.size
        for (request in requests) {
            val result = try { readCell(request) } finally { request.closed = true }
            val capture = result as? OwnedCapture ?: return refused()
            if (request.owner !== owner || capture.request !== request || request.count != 1 ||
                capture.width != request.crop.width || capture.height != request.crop.height) return refused()
            if (capture.hocr.length > 4_000_000 - characters) return refused(truncated = true)
            characters += capture.hocr.length
            val nodes = Jsoup.parse(capture.hocr).select(".ocrx_word,.ocr_word").filter { it.text().isNotBlank() }
            val text = nodes.joinToString(" ") { it.text() }
            evidence += text
            if (ScheduleCancellation.containsMatchIn(text)) return refused()
            if (nodes.size > 20_000 - wordCount) return refused(truncated = true)
            wordCount += nodes.size
            val words = nodes.map { node ->
                val match = box.find(node.attr("title")) ?: return refused()
                val values = match.groupValues.drop(1).map { it.toIntOrNull() ?: return refused() }
                val (left, top, right, bottom) = values
                if (left < 0 || top < 0 || right > capture.width || bottom > capture.height || right <= left || bottom <= top)
                    return refused()
                ScheduleWord(node.text().trim(), left + request.crop.left, top + request.crop.top,
                    right + request.crop.left, bottom + request.crop.top,
                    confidence.find(node.attr("title"))?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            }
            if (unsafeColumn(words)) return refused()
            captures[request.band][if (request.kind == CellKind.DATE) 0 else 1].addAll(words)
        }
        val output = mutableListOf<ScheduleDocumentLine>()
        output += header; output += ScheduleDocumentLine("", endsTable = true)
        val tolerance = maxOf(2, rails.maxOf { it.last - it.first + 1 })
        for ((index, band) in bands.withIndex()) {
            if (unsafeColumn(band[0]) || unsafeColumn(band[2]) || band[1].isEmpty()) return refused()
            val dateWords = captures[index][0]
            val recoveredDate = readDate(dateWords, year) ?: return refused()
            val oldText = ScheduleOcrLayout.lines(band[0]).joinToString(" ") { it.text }
            if (band[0].isNotEmpty() && band[0].all { it.confidence >= 55 } && dateCell.matches(oldText)) {
                val oldDate = readDate(band[0], year) ?: return refused()
                if (oldDate.first != recoveredDate.first) return refused()
            }
            for (y in boundaries[index].last + tolerance + 1 until boundaries[index + 1].first - tolerance)
                for (x in rails[0].last + tolerance + 1 until rails[1].first - tolerance) {
                    if (ink[y * width + x] && dateWords.none { x in it.left - 1..it.right && y in it.top - 1..it.bottom })
                        return refused()
                }
            val times = unionClocks(band[2], captures[index][1]) ?: return refused()
            if (times.isEmpty() || times.any { it.confidence < 55 }) return refused()
            if (ScheduleOcrLayout.lines(band[1]).any { dated.containsMatchIn(it.text) }) return refused()
            val graphicWords = graphicRuleWords(ink, width, rails[1].last + tolerance + 2,
                rails[2].first - tolerance - 1, boundaries[index].last, boundaries[index + 1].first, band[1])
            output += ScheduleDocumentLine("", startsDayContainer = true)
            val dateHeading = ScheduleDocumentLine(recoveredDate.second, dateWords.minOf { it.confidence },
                temporalConfidence = dateWords.minOf { it.confidence })
            output += dateHeading
            fun appendProvenRows(lines: List<ScheduleDocumentLine>) {
                for (line in lines) {
                    if (ScheduleClocks.pattern.containsMatchIn(line.text)) {
                        // A service-column glyph cannot revoke the independently proved
                        // date-cell. Never change its confidence or borrow its title.
                        output += ScheduleDocumentLine("", endsTable = true)
                        output += dateHeading
                    }
                    output += line
                }
            }
            val dividers = partials.filter { it.first > boundaries[index].last && it.last < boundaries[index + 1].first }
            if (dividers.isEmpty()) appendProvenRows(serviceLines(band[1], times, graphicWords) ?: return refused())
            else {
                // A printed service/time divider is an ownership boundary, even when
                // OCR lost a whole clock. A date-band is not one shared service title.
                val sections = (listOf(boundaries[index]) + dividers + boundaries[index + 1]).zipWithNext()
                var assignedServices = 0; var assignedClocks = 0
                for ((section, edges) in sections.withIndex()) {
                    val ownServices = band[1].filter { it.top > edges.first.last && it.bottom <= edges.second.first }
                    val top = if (section == 0) edges.first.first else edges.first.last + 1
                    val bottom = if (section == sections.lastIndex) edges.second.last + 1 else edges.second.first
                    val ownClocks = times.filter { it.top >= top && it.bottom <= bottom }
                    if (ownServices.isEmpty() || ownClocks.size != 1) return refused()
                    assignedServices += ownServices.size; assignedClocks += ownClocks.size
                    if (section > 0) {
                        // Flush the previous title before any lower preface, then reuse
                        // only the same date-cell proof shared by these printed subcells.
                        output += ScheduleDocumentLine("", endsTable = true)
                        output += dateHeading
                    }
                    appendProvenRows(serviceLines(ownServices, ownClocks, graphicWords) ?: return refused())
                }
                if (assignedServices != band[1].size || assignedClocks != times.size) return refused()
            }
            output += ScheduleDocumentLine("", endsDayContainer = true)
        }
        output += ScheduleDocumentLine("", endsTable = true); output += footer
        return ScheduleDocumentPage(output, rawOcrEvidence = evidence.toList())
    }

    private fun readDate(words: List<ScheduleWord>, year: Int): Pair<LocalDate, String>? {
        if (words.size !in 3..8 || words.any { it.confidence < 55 }) return null
        val text = ScheduleOcrLayout.lines(words).joinToString(" ") { it.text }
        val match = dateCell.matchEntire(text) ?: return null
        val month = monthPrefixes.indexOfFirst { match.groupValues[2].lowercase(Locale.ROOT).startsWith(it) } + 1
        if (match.groupValues[3].isNotEmpty() && match.groupValues[3].toIntOrNull() != year) return null
        val date = runCatching { LocalDate.of(year,
            month, match.groupValues[1].toInt()) }.getOrNull() ?: return null
        return (date to text).takeIf { ScheduleWeekdays.agrees(match.groupValues[4], date) }
    }

    private fun unsafeColumn(words: List<ScheduleWord>): Boolean {
        val raw = words.joinToString(" ") { it.text }
        return ScheduleCancellation.containsMatchIn(raw) ||
            Regex("(?iu)(?<![а-яё])(?:храм|церк|собор|часовн|придел)[а-яё]*(?![а-яё])").containsMatchIn(raw)
    }

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    private fun rectangle(word: ScheduleWord) = Rect(word.left, word.top, word.right, word.bottom)

    private fun unionClocks(original: List<ScheduleWord>, supplement: List<ScheduleWord>): List<ScheduleWord>? {
        fun value(text: String): String? {
            val match = ScheduleClocks.pattern.matchEntire(text) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return "%02d:%02d".format(Locale.ROOT, hour, minute)
        }
        fun aligned(a: ScheduleWord, b: ScheduleWord): Boolean {
            val height = minOf(a.bottom - a.top, b.bottom - b.top)
            return minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) >= height * .8
        }
        val candidates = mutableListOf<ScheduleWord>()
        for (pass in listOf(original, supplement)) for (start in pass) {
            checkInterrupted()
            value(start.text)?.let { candidates += start.copy(text = it) }
            if (!Regex("[0-9]{1,2}[.:-]?").matches(start.text)) continue
            val chain = mutableListOf(start)
            repeat(2) {
                val previous = chain.last()
                val following = pass.filter { next -> next !== previous && next !in chain && next.left >= previous.right &&
                    next.left - previous.right <= minOf(previous.bottom - previous.top, next.bottom - next.top) * .5 &&
                    aligned(previous, next) }
                if (following.size > 1) return null
                val next = following.singleOrNull() ?: return@repeat
                if (pass.any { other -> other !== previous && other !== next && other !in chain && aligned(previous, other) &&
                        other.left < next.left && other.right > previous.left }) return null
                if (!Regex("[0-9.:\\-]+").matches(next.text)) return@repeat
                chain += next
                value(chain.joinToString("") { it.text })?.let { normalized ->
                    candidates += ScheduleWord(normalized, chain.minOf { it.left }, chain.minOf { it.top },
                        chain.maxOf { it.right }, chain.maxOf { it.bottom }, chain.minOf { it.confidence })
                }
            }
        }
        val groups = mutableListOf<MutableList<ScheduleWord>>()
        for (candidate in candidates) {
            checkInterrupted()
            val connected = groups.filter { group -> group.any { overlaps(rectangle(it), rectangle(candidate)) } }
            if (connected.size > 1) return null
            val group = connected.singleOrNull()
            if (group == null) groups += mutableListOf(candidate)
            else {
                if (group.any { it.text != candidate.text || !overlaps(rectangle(it), rectangle(candidate)) }) return null
                group += candidate
            }
        }
        val result = groups.map { group -> ScheduleWord(group.first().text, group.minOf { it.left }, group.minOf { it.top },
            group.maxOf { it.right }, group.maxOf { it.bottom }, group.minOf { it.confidence }) }
        if (result.indices.any { a -> (a + 1 until result.size).any { b -> aligned(result[a], result[b]) } }) return null
        return result
    }

    private fun rows(words: List<ScheduleWord>): List<List<ScheduleWord>> {
        data class Row(val members: MutableList<ScheduleWord>) {
            val middle get() = members.map { (it.top + it.bottom) / 2.0 }.average()
            val height get() = members.map { it.bottom - it.top }.sorted().let { it[it.size / 2].toDouble() }
        }
        val result = mutableListOf<Row>()
        words.sortedBy { it.top + it.bottom }.forEach { word ->
            checkInterrupted()
            val middle = (word.top + word.bottom) / 2.0
            val row = result.lastOrNull { kotlin.math.abs(it.middle - middle) <= maxOf(it.height, (word.bottom - word.top).toDouble()) * .48 }
            if (row == null) result += Row(mutableListOf(word)) else row.members += word
        }
        return result.sortedBy { it.middle }.map { it.members.sortedBy { word -> word.left } }
    }

    /** Only a thin, repeated full-column graphic can explain an OCR fragment here.
     * This proves no date, clock, service boundary or title. All words remain evidence. */
    private fun graphicRuleWords(ink: BooleanArray, width: Int, left: Int, right: Int,
        upper: Int, lower: Int, words: List<ScheduleWord>): Set<ScheduleWord> {
        if (right - left < 128) return emptySet()
        val candidates = words.filter { it.left >= left && it.right <= right &&
            it.bottom - it.top in 1..3 && it.top - 2 > upper && it.bottom + 1 < lower }
            .groupBy { it.top to it.bottom }
        if (candidates.size > 256) return emptySet()
        val result = mutableSetOf<ScheduleWord>()
        for ((edges, members) in candidates) {
            checkInterrupted()
            val (top, bottom) = edges
            // Two empty rows on both sides exclude letters crossing a narrow stripe.
            if (listOf(top - 2, top - 1, bottom, bottom + 1).any { y ->
                    (left until right).any { x -> ink[y * width + x] }
                }) continue
            val columns = (left until right).filter { x -> (top until bottom).any { y -> ink[y * width + x] } }
            val runs = spans(columns)
            if (runs.size < 32 || runs.first().first - left > 4 || right - 1 - runs.last().last > 4 ||
                runs.any { it.last - it.first + 1 > 3 }) continue
            val gaps = runs.zipWithNext().map { (a, b) -> b.first - a.last - 1 }
            val pitches = runs.zipWithNext().map { (a, b) -> b.first - a.first }
            if (gaps.any { it !in 1..4 } || pitches.max() - pitches.min() > 1) continue
            result += members.filter { word -> (word.left until word.right).any { x ->
                (word.top until word.bottom).any { y -> ink[y * width + x] }
            } }
        }
        return result
    }

    private fun serviceLines(serviceWords: List<ScheduleWord>, times: List<ScheduleWord>,
        graphicWords: Set<ScheduleWord>): List<ScheduleDocumentLine>? {
        data class Token(val row: Int, val word: ScheduleWord, val range: IntRange)
        val serviceRows = rows(serviceWords)
        val text = StringBuilder(); val tokens = mutableListOf<Token>()
        serviceRows.forEachIndexed { row, words -> words.forEach { word ->
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length; text.append(word.text)
            tokens += Token(row, word, start until text.length)
        } }
        val heights = serviceWords.map { it.bottom - it.top }.sorted()
        val height = heights[heights.size / 2]
        val calendarOnly = mutableSetOf<ScheduleWord>()
        ScheduleVenue.calendarRanges(text.toString()).forEach { range ->
            val matched = tokens.filter { it.range.first <= range.last && it.range.last >= range.first }
            val complete = matched.all { token ->
                val prefix = (range.first - token.range.first).coerceAtLeast(0)
                val suffix = (token.range.last - range.last).coerceAtLeast(0)
                (token.word.text.take(prefix) + token.word.text.takeLast(suffix)).none(Char::isLetterOrDigit)
            }
            if (matched.isNotEmpty() && complete && matched.all { it.word.confidence >= 45 } &&
                matched.last().row - matched.first().row <= 2 && matched.last().word.bottom - matched.first().word.top <= height * 4)
                calendarOnly += matched.map { it.word }
        }
        val all = serviceWords + times
        val grouped = rows(all)
        val projected = ScheduleOcrLayout.lines(all).mapIndexed { index, line ->
            val row = grouped[index]
            line.copy(venueEvidence = row.takeIf { members -> members.any { it in calendarOnly } }
                ?.filterNot { it in calendarOnly }?.joinToString(" ") { it.text })
        }
        val sourceLines = ScheduleOcrLayout.lines(serviceWords)
        if (sourceLines.any { ScheduleClocks.pattern.containsMatchIn(it.text) }) return null
        fun boundary(index: Int): Boolean {
            val raw = sourceLines[index].text
            val venue = serviceRows[index].filterNot { it in calendarOnly }.joinToString(" ") { it.text }
            return monthYear.containsMatchIn(raw) || dated.containsMatchIn(raw) ||
                Regex("(?iu)^\\s*\\d{1,2}\\s*(?:[.,/-]|[а-яё]{2,}|\\|)").containsMatchIn(raw) ||
                Regex("(?iu)\\d{4}-\\d{2}-\\d{2}").containsMatchIn(raw) ||
                Regex("(?iu)^(?:кажд(?:ый|ую|ое|ые|ая)|ежедневно|еженедельно)(?=\\s|:|$)").containsMatchIn(raw) ||
                Regex("(?iu)^(адрес|телефон|www\\.|https?://|с\\s+\\d+\\s+[а-я]+.*начало)").containsMatchIn(raw) ||
                Regex("(?iu)(расписан|пожертв|контакт|наверх)").containsMatchIn(raw) || ScheduleVenue.isLabel(venue)
        }
        if (sourceLines.indices.any(::boundary)) return null
        fun namesService(text: String) = namedService.containsMatchIn(text) ||
            Regex("(?iu)(?<![а-яё])часы(?![а-яё])").containsMatchIn(text)
        for (index in sourceLines.indices) {
            val line = sourceLines[index]
            if (line.confidence >= 45 && line.temporalConfidence >= 55) continue
            val sharesClockRow = grouped.any { row ->
                row.any { it in times } && row.any { it in serviceRows[index] }
            }
            // Never infer noise from a character or its confidence alone. Only a
            // complete untimed graphic-owned row can pass this one confidence gate.
            // Projected/raw text, title borrowing and clock ownership stay unchanged.
            if (line.temporalConfidence != 100 || sharesClockRow || serviceRows[index].any { it !in graphicWords }) return null
        }
        if (times.size != 1) {
            val timedRows = grouped.indices.filter { index -> grouped[index].any { it in times } }
            if (timedRows.size != times.size) return null
            val firstClock = timedRows.first()
            for (index in grouped.indices) {
                val row = grouped[index]
                val name = row.filterNot { it in times }.joinToString(" ") { it.text }
                if (index in timedRows) {
                    if (!namesService(name)) return null
                } else if (index > firstClock || namesService(name)) return null
            }
            return projected
        }
        val first = sourceLines.indexOfFirst { namesService(it.text) }
        if (first < 0) return null
        val borrowed = sourceLines.drop(first)
        if (borrowed.any { it.confidence < 45 || it.temporalConfidence < 55 }) return null
        val block = borrowed.joinToString(" ") { it.text }
        // The parser admits at most300 display-title characters and never appends500.
        if (block.length !in 2..300 || block.length + times.single().text.length + 1 >= 500) return null
        val borrowedWords = serviceRows.drop(first).flatten().toSet()
        val timed = grouped.indices.singleOrNull { times.single() in grouped[it] } ?: return null
        return projected.mapIndexed { index, line ->
            when {
                index == timed -> line.copy(serviceText = block + " " + times.single().text,
                    confidence = minOf(line.confidence, borrowed.minOf { it.confidence }),
                    temporalConfidence = minOf(line.temporalConfidence, borrowed.minOf { it.temporalConfidence }))
                grouped[index].any { it in borrowedWords } -> line.copy(serviceText = "")
                else -> line
            }
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Cell evidence processing cancelled")
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
