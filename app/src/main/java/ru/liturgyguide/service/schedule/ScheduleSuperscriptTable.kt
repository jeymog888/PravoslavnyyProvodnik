package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** A narrow, evidence-based repair for dated tables whose minutes are printed as superscripts. */
internal object ScheduleSuperscriptTable {
    private data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val height get() = bottom - top
        val middle get() = (top + bottom) / 2
    }
    private data class Glyph(val text: String, val box: Box, val confidence: Int)
    private data class Word(val text: String, val box: Box, val confidence: Int, val glyphs: List<Glyph> = emptyList())
    private data class Anchor(val date: LocalDate, val dateWord: Word, val weekday: Word) {
        val start get() = weekday.box.top - weekday.box.height * .65
        val confidence get() = minOf(dateWord.confidence, weekday.confidence)
    }
    private data class Clock(val word: Word, val value: String, val confidence: Int)
    private val fullDate = Regex("(\\d{1,2})[./](\\d{1,2})[./](20\\d{2})")
    private val digits = Regex("[0-9]{3,4}")
    private val bbox = Regex("(?:^|[;\\s])bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
    private val charBox = Regex("x_bboxes\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
    private val wordConfidence = Regex("x_wconf\\s+(\\d+)")
    private val charConfidence = Regex("x_conf\\s+([0-9.]+)")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val cancellation = ScheduleCancellation
    private val recurrence = Regex("(?iu)(?<![а-яё])(?:ежедневно|еженедельно|кажд(?:ый|ую|ое|ые|ая))(?![а-яё])")
    private val building = Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])")
    private val weekdayNames = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    fun needsSupplement(hocr: String): Boolean {
        val words = read(hocr) ?: return false
        val anchors = anchors(words) ?: return false
        if (!service.containsMatchIn(words.joinToString(" ") { it.text })) return false
        val ordinary = words.count { ScheduleClocks.pattern.matches(it.text) }
        return ordinary < anchors.size
    }

    /** Nullable convenience for callers interested only in a successful repair. */
    fun recover(originalHocr: String, sparseHocr: String, enlargedHocr: String, scaleX: Double, scaleY: Double): ScheduleDocumentPage? =
        inspect(originalHocr, sparseHocr, enlargedHocr, scaleX, scaleY).takeIf { it.ocrFailure == null }

    /** Never fall back to primary-pass events after supplementary evidence was inspected. */
    fun inspect(originalHocr: String, sparseHocr: String, enlargedHocr: String, scaleX: Double, scaleY: Double): ScheduleDocumentPage {
        val passes = listOf(originalHocr, sparseHocr, enlargedHocr)
        val evidence = passes.filter(String::isNotBlank).map { hocr ->
            // Keep unlocated recognized words too: missing geometry does not erase a cancellation.
            Jsoup.parse(hocr.take(4_000_000)).select(".ocrx_word,.ocr_word").joinToString(" ") { word ->
                val characters = word.children().filter { it.hasClass("ocrx_cinfo") && it.attr("title").contains("x_bboxes") }
                if (characters.isEmpty()) word.text() else characters.joinToString("") { it.text() }
            }
        }
        return try {
            if (passes.any { it.length > 4_000_000 }) conflict()
            if (evidence.any { cancellation.containsMatchIn(it) }) conflict()
            if (evidence.any { recurrence.containsMatchIn(it) }) unsupported()
            repair(originalHocr, sparseHocr, enlargedHocr, scaleX, scaleY, evidence)
        } catch (refusal: Refusal) {
            ScheduleDocumentPage(emptyList(), rawOcrEvidence = evidence, ocrFailure = refusal.failure)
        }
    }

    private class Refusal(val failure: ScheduleOcrFailure) : RuntimeException()
    private fun unsupported(): Nothing = throw Refusal(ScheduleOcrFailure.UNSUPPORTED_LAYOUT)
    private fun conflict(): Nothing = throw Refusal(ScheduleOcrFailure.CONFLICTING_EVIDENCE)

    /** Every accepted time lies between two independently printed, consecutive day headings. */
    private fun repair(originalHocr: String, sparseHocr: String, enlargedHocr: String, scaleX: Double, scaleY: Double,
        evidence: List<String>): ScheduleDocumentPage {
        if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX !in 1.0..1.71 || scaleY !in 1.0..1.71) unsupported()
        val original = read(originalHocr) ?: unsupported()
        val sparse = read(sparseHocr) ?: unsupported()
        val enlarged = read(enlargedHocr, scaleX, scaleY) ?: unsupported()
        val supplement = sparse + enlarged
        val days = anchors(original) ?: unsupported()
        // A lost interior heading must not merge two civil dates into one band.
        if (days.zipWithNext().any { (a, b) -> a.date.plusDays(1) != b.date }) conflict()
        val medianHeight = median(days.map { it.weekday.box.height })
        val dateRight = days.maxOf { it.dateWord.box.right }
        // The last band has no following printed boundary. Do not extrapolate its
        // height: an entirely unreadable next day could otherwise inherit the last date.
        val bodyBottom = days.last().start
        val supplementalDates = supplement.mapNotNull { word -> printedDate(word)?.let { word to it } }
        for ((word, date) in supplementalDates) {
            val overlapping = days.filter { abs(it.dateWord.box.middle - word.box.middle) < medianHeight &&
                abs(it.dateWord.box.left - word.box.left) < medianHeight }
            if (overlapping.size != 1 || overlapping.single().date != date) conflict()
        }
        // A second left-hand weekday without a printed, matching date would hide a missing day band.
        for (word in supplement.filter { weekday(it.text) != null && it.box.right < dateRight + medianHeight * 2 }) {
            if (days.none { weekday(word.text) == it.date.dayOfWeek.value && abs(it.weekday.box.middle - word.box.middle) < medianHeight }) conflict()
        }
        val bodyOriginal = original.filter { it.box.middle >= days.first().start && it.box.middle < bodyBottom }
        val bodySupplement = supplement.filter { it.box.middle >= days.first().start && it.box.middle < bodyBottom }
        // Parallel buildings, relative chapels and names in notes require the normal conservative parser.
        if ((bodyOriginal + bodySupplement).any { building.containsMatchIn(it.text) }) conflict()
        val clockGroups = mutableListOf<MutableList<Clock>>()
        for (clock in bodySupplement.mapNotNull(::superscriptClock).filter { it.word.box.left > dateRight + medianHeight * .4 }) {
            val group = clockGroups.firstOrNull { candidates -> candidates.any { overlaps(it.word.box, clock.word.box) } }
            if (group == null) clockGroups += mutableListOf(clock) else group += clock
        }
        if (clockGroups.any { group -> group.map { it.value }.distinct().size != 1 }) conflict()
        // Each selected proof is a real word with its own confidence, never a synthetic vote.
        val clocks = clockGroups.map { group -> group.maxBy { it.confidence } }
        if (clocks.size < 6) unsupported()
        val clockLeft = median(clocks.map { it.word.box.left })
        val clockRight = clocks.maxOf { it.word.box.right }
        if (clocks.any { abs(it.word.box.left - clockLeft) > medianHeight }) conflict()
        fun dayFor(word: Word): Anchor? = days.lastOrNull { word.box.middle >= it.start }
            ?.takeIf { word.box.middle < bodyBottom }
        if (clocks.mapNotNull { dayFor(it.word)?.date }.distinct().size < 3) unsupported()
        for (clock in clocks) {
            val overlapping = (original + supplement.filter { ScheduleClocks.pattern.matches(it.text) }).filter { overlaps(it.box, clock.word.box) && it.confidence >= 55 &&
                (digits.matches(it.text) || ScheduleClocks.pattern.matches(it.text)) }
            if (overlapping.any { normalizedDigits(it.text) != clock.value.replace(":", "") }) conflict()
        }
        // An ordinary clock in any pass remains evidence, including a conflicting HH:mm.
        for (word in (bodyOriginal + bodySupplement).filter { ScheduleClocks.pattern.matches(it.text) && it.confidence >= 55 }) {
            if (clocks.none { overlaps(word.box, it.word.box) && normalizedDigits(word.text) == it.value.replace(":", "") }) conflict()
        }
        val headers = listOf(original, sparse, enlarged).map { pass ->
            rows(pass.filter { it.box.middle < days.first().start })
                .filter { row -> row.any { building.containsMatchIn(it.text) } }
        }
        if (headers.any { it.size > 1 }) conflict()
        val headerRows = headers.flatten()
        if (headerRows.isEmpty()) unsupported()
        // All recognized headings remain constraints, including a conflicting second dedication.
        val header = headerRows.flatten()
        val headerText = headerRows.joinToString(" | ") { row -> row.joinToString(" ") { it.text } }
        if (!ScheduleVenue.isLabel(headerText)) unsupported()
        val anchorWords = days.flatMap { listOf(it.dateWord, it.weekday) }.toSet()
        val repairedWords = bodyOriginal.filterNot { word -> word in anchorWords ||
            clocks.any { overlaps(word.box, it.word.box) && (digits.matches(word.text) || ScheduleClocks.pattern.matches(word.text)) } }.toMutableList()
        val clockWords = clocks.associate { clock ->
            val day = requireNotNull(dayFor(clock.word))
            clock.word.copy(text = clock.value, confidence = minOf(clock.confidence, day.confidence), glyphs = emptyList()) to day
        }
        repairedWords += clockWords.keys
        // An unreadable time cannot inherit the preceding clock. Its position only proves
        // a new row boundary, never a numeric value. Keep its service out of the prior title.
        val barriers = bodySupplement.filter { word ->
            abs(word.box.left - clockLeft) <= medianHeight && word.text.count(Char::isDigit) >= 2 &&
                clocks.none { overlaps(word.box, it.word.box) }
        }.map { it.copy(text = "", confidence = 0, glyphs = emptyList()) }.toSet()
        repairedWords += barriers
        val physicalRows = rows(repairedWords)
        val boundary = serviceBoundary(physicalRows, clockRight, medianHeight)
        val lines = mutableListOf(ScheduleDocumentLine(headerText, confidence = header.map { it.confidence }.average().toInt(), headingLevel = 1))
        for (row in physicalRows) {
            if (row.any { it in barriers }) {
                lines += ScheduleDocumentLine("", endsTable = true)
                continue
            }
            val timed = row.filter { it in clockWords }
            if (timed.size > 1) conflict()
            val raw = row.joinToString(" ") { it.text }
            val visible = row.filter { it.box.left >= days.minOf { day -> day.dateWord.box.left } - medianHeight * .2 }
            val confidence = visible.map { it.confidence }.average().takeIf(Double::isFinite)?.toInt() ?: 0
            val serviceWords = row.filter { it.box.left >= clockLeft - medianHeight * .3 && (boundary == null || it.box.right <= boundary) }
            val title = serviceWords.joinToString(" ") { it.text }
            val clock = timed.singleOrNull()
            if (clock == null) {
                lines += ScheduleDocumentLine(raw, confidence = confidence,
                    temporalConfidence = row.filter { it.text.any(Char::isDigit) }.minOfOrNull { it.confidence } ?: 100,
                    serviceText = title)
            } else {
                val day = clockWords.getValue(clock)
                // A printed date is moved before its first clock, never inferred from a neighbour.
                lines += ScheduleDocumentLine("${day.date} $raw", confidence = confidence,
                    temporalConfidence = clock.confidence, serviceText = "${day.date} $title")
            }
        }
        return ScheduleDocumentPage(lines, rawOcrEvidence = evidence)
    }

    private fun anchors(words: List<Word>): List<Anchor>? {
        val dates = words.mapNotNull { word -> printedDate(word)?.let { word to it } }
        if (dates.size !in 3..31 || dates.map { it.second }.distinct().size != dates.size) return null
        val height = median(dates.map { it.first.box.height })
        if (dates.maxOf { it.first.box.left } - dates.minOf { it.first.box.left } > height ||
            dates.maxOf { it.first.box.right } - dates.minOf { it.first.box.right } > height) return null
        val result = dates.map { (word, date) ->
            val preceding = words.filter { weekday(it.text) != null && it.confidence >= 55 &&
                abs(it.box.left - word.box.left) <= height && word.box.top - it.box.top in height*.5..height*4 }
            val label = preceding.singleOrNull() ?: return null
            if (weekday(label.text) != date.dayOfWeek.value) return null
            Anchor(date, word, label)
        }.sortedBy { it.start }
        if (result.zipWithNext().any { (a, b) -> !a.date.isBefore(b.date) || b.start - a.start < height * 4 }) return null
        val labels = words.filter { weekday(it.text) != null && it.box.left < dates.maxOf { d -> d.first.box.right } }
        if (labels.any { it !in result.map(Anchor::weekday) }) return null
        return result
    }

    private fun printedDate(word: Word): LocalDate? {
        if (word.confidence < 55) return null
        val match = fullDate.matchEntire(word.text) ?: return null
        return runCatching { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()) }.getOrNull()
    }

    private fun superscriptClock(word: Word): Clock? {
        if (!digits.matches(word.text) || word.confidence < 55 || word.glyphs.size != word.text.length) return null
        val glyphs = word.glyphs
        if (glyphs.any { it.confidence < 55 || it.text.length != 1 || it.text.single() !in '0'..'9' ||
                it.box.left < word.box.left-1 || it.box.right > word.box.right+1 ||
                it.box.top < word.box.top-1 || it.box.bottom > word.box.bottom+1 }) return null
        if (glyphs.joinToString("") { it.text } != word.text || glyphs.zipWithNext().any { (a, b) -> a.box.right > b.box.left }) return null
        val hours = glyphs.dropLast(2); val minutes = glyphs.takeLast(2)
        val height = median(hours.map { it.box.height })
        val baseline = median(hours.map { it.box.bottom })
        if (hours.any { abs(it.box.bottom - baseline) > height*.2 } ||
            minutes.any { it.box.height > height*.8 || it.box.bottom > baseline - height*.2 }) return null
        val hour = hours.joinToString("") { it.text }.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = minutes.joinToString("") { it.text }.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val value = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        return Clock(word, value, minOf(word.confidence, glyphs.minOf { it.confidence }))
    }

    private fun serviceBoundary(rows: List<List<Word>>, right: Double, height: Double): Double? {
        val spans = mutableListOf<Pair<Double, Double>>()
        rows.flatten().filter { it.box.left > right && it.confidence >= 45 }.sortedBy { it.box.left }.forEach { word ->
            val previous = spans.lastOrNull()
            if (previous != null && word.box.left <= previous.second) spans[spans.lastIndex] = previous.first to max(previous.second, word.box.right)
            else spans += word.box.left to word.box.right
        }
        for ((a, b) in spans.zipWithNext()) {
            if (a.second - right < height*4 || b.first - a.second < height*.35) continue
            val boundary = (a.second + b.first)/2
            val serviceRows = rows.count { row -> service.containsMatchIn(row.filter { it.box.left > right && it.box.right <= boundary }.joinToString(" ") { it.text }) }
            val separateRows = rows.count { row -> row.any { it.box.left > boundary } }
            if (serviceRows >= 6 && separateRows >= 3) return boundary
        }
        return null
    }

    private fun read(hocr: String, scaleX: Double = 1.0, scaleY: Double = 1.0): List<Word>? {
        if (hocr.length !in 1..4_000_000) return null
        val elements = Jsoup.parse(hocr).select(".ocrx_word,.ocr_word")
        if (elements.size !in 1..20_000) return null
        fun rectangle(match: MatchResult): Box? {
            val v = match.groupValues.drop(1).map { it.toDoubleOrNull()?.takeIf { coordinate -> coordinate.isFinite() && coordinate in 0.0..60_000.0 } ?: return null }
            return Box(v[0]/scaleX, v[1]/scaleY, v[2]/scaleX, v[3]/scaleY).takeIf { it.right > it.left && it.bottom > it.top }
        }
        val result = mutableListOf<Word>()
        for (word in elements) {
            val characters = word.children().filter { it.hasClass("ocrx_cinfo") && it.attr("title").contains("x_bboxes") }
            val text = (if (characters.isNotEmpty()) characters.joinToString("") { it.text() } else word.text()).trim()
            if (text.isEmpty()) continue
            // Unlocated recognized words may be a withdrawal or another building. Refuse the
            // repair rather than dropping them from its full-text safety evidence.
            val box = bbox.find(word.attr("title"))?.let(::rectangle) ?: return null
            val glyphs = characters.map { character ->
                val rect = charBox.find(character.attr("title"))?.let(::rectangle) ?: return null
                val confidence = charConfidence.find(character.attr("title"))?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { floor(it).toInt() } ?: 0
                Glyph(character.text(), rect, confidence)
            }
            result += Word(text, box, wordConfidence.find(word.attr("title"))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 0, glyphs)
        }
        return result
    }

    private fun rows(words: List<Word>): List<List<Word>> {
        val rows = mutableListOf<MutableList<Word>>()
        for (word in words.sortedBy { it.box.middle }) {
            val row = rows.lastOrNull { abs(it.map { w -> w.box.middle }.average() - word.box.middle) <=
                max(median(it.map { w -> w.box.height }), word.box.height)*.48 }
            if (row == null) rows += mutableListOf(word) else row += word
        }
        return rows.sortedBy { it.map { w -> w.box.middle }.average() }.map { it.sortedBy { w -> w.box.left } }
    }
    private fun weekday(text: String): Int? = weekdayNames.indexOf(text.lowercase(Locale.ROOT).trim('.').replace("воскресение", "воскресенье"))
        .takeIf { it >= 0 }?.plus(1)
    private fun normalizedDigits(text: String): String? {
        val plain = text.replace(Regex("[.:]"), "")
        return plain.takeIf { digits.matches(it) }?.padStart(4, '0')
    }
    private fun overlaps(a: Box, b: Box): Boolean = abs(a.middle-b.middle) <= max(a.height,b.height)*.6 &&
        minOf(a.right,b.right) > maxOf(a.left,b.left)
    private fun median(values: List<Double>): Double = values.sorted()[values.size/2]
}
