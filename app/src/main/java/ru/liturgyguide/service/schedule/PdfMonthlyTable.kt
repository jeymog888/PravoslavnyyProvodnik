package ru.liturgyguide.service.schedule

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs

/** Bounds of a visible, opaque, dark painted PDF rectangle, in the glyph coordinate system. */
internal data class PdfTableRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Narrow ruled table: day/weekday, calendar memory, service, clock. Never guesses a row boundary. */
internal object PdfMonthlyTable {
    data class Continuation(val month: YearMonth, val columns: List<Float>, val lastDay: LocalDate,
        val venue: String, val monthHeading: String)
    data class Result(val page: ScheduleDocumentPage, val continuation: Continuation)
    private data class Rule(val at: Float, val start: Float, val end: Float)
    private val monthNames = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val monthYear = Regex("(?iu)(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])\\s+(20\\d{2})")
    private val dayWeek = Regex("(?iu)^(\\d{1,2})\\s+(${ScheduleWeekdays.dateWords})\\.?$")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private const val GAP = 3.5f

    fun read(input: List<PdfScheduleGlyph>, rectangles: List<PdfTableRect>, previous: Continuation? = null): Result? {
        if (input.size !in 1..40_000 || rectangles.size !in 1..4_000) return null
        val glyphs = input.filter { it.text.isNotBlank() }
        if (glyphs.isEmpty() || glyphs.any { g -> '\uFFFD' in g.text ||
                listOf(g.left, g.baseline, g.width, g.fontSize).any { !it.isFinite() } ||
                g.width <= 0 || g.fontSize !in 1f..200f }) return null
        if (rectangles.any { r -> listOf(r.left, r.top, r.right, r.bottom).any { !it.isFinite() } ||
                r.right <= r.left || r.bottom <= r.top }) return null
        val horizontal = rectangles.filter { it.bottom - it.top <= 3f && it.right - it.left >= 6f }
            .map { Rule((it.top + it.bottom) / 2, it.left, it.right) }
        val vertical = rectangles.filter { it.right - it.left <= 3f && it.bottom - it.top >= 6f }
            .map { Rule((it.left + it.right) / 2, it.top, it.bottom) }
        val xs = coordinates(vertical)
        val ys = coordinates(horizontal)
        if (xs.size != 5 || xs.zipWithNext().any { (a, b) -> b - a < 15f }) return null
        val days = ys.filter { y -> covers(horizontal, y, xs.first(), xs.last()) }
        if (days.size !in 4..32 || days.zipWithNext().any { (a, b) -> b - a < 15f }) return null
        if (xs.any { !covers(vertical, it, days.first(), days.last()) }) return null
        // A partial date/calendar separator or a line striking text is not an ordinary cell boundary.
        if (ys.any { it > days.first() + GAP && it < days.last() - GAP &&
                covers(horizontal, it, xs[0], xs[1]) && days.none { d -> abs(d - it) < 1.2f } }) return null
        if (horizontal.any { rule -> glyphs.any { g ->
                rule.at in (g.baseline - g.fontSize * .75f)..(g.baseline - g.fontSize * .2f) &&
                    g.left + g.width / 2 in rule.start..rule.end
            } }) return null

        fun center(g: PdfScheduleGlyph) = g.baseline - g.fontSize * .4f
        fun text(values: List<PdfScheduleGlyph>) = PdfScheduleTextLayout.lines(values).joinToString(" ") { it.text }.trim()
        val heading = text(glyphs.filter { center(it) < days.first() })
        val months = monthYear.findAll(heading).toList()
        val headerLines = PdfScheduleTextLayout.lines(glyphs.filter { center(it) < days.first() }).map { it.text }
        val month: YearMonth
        val venue: String
        val monthHeading: String
        val carried = heading.isBlank()
        if (carried) {
            val proof = previous ?: return null
            if (xs.zip(proof.columns).any { (a, b) -> abs(a - b) > 1.2f }) return null
            month = proof.month; venue = proof.venue; monthHeading = proof.monthHeading
        } else {
            val match = months.singleOrNull() ?: return null
            val number = monthNames.indexOfFirst { match.groupValues[1].lowercase().startsWith(it) } + 1
            month = runCatching { YearMonth.of(match.groupValues[2].toInt(), number) }.getOrNull() ?: return null
            venue = headerLines.filter(ScheduleVenue::isLabel).singleOrNull() ?: return null
            monthHeading = headerLines.singleOrNull { monthYear.containsMatchIn(it) } ?: return null
            // Do not discard an extra header notice, including cancellation or a second building.
            if (headerLines.any { it != venue && !monthYear.containsMatchIn(it) }) return null
        }
        if (glyphs.any { center(it) > days.last() ||
                (center(it) >= days.first() && (it.left < xs.first() - 1f || it.left + it.width > xs.last() + 1f)) }) return null
        val lines = mutableListOf(ScheduleDocumentLine(monthHeading),
            ScheduleDocumentLine(venue, headingLevel = 1))
        var last: LocalDate? = null
        var datedServices = 0
        val consumed = mutableSetOf<Int>()
        fun cell(left: Float, right: Float, top: Float, bottom: Float): String? {
            val selected = glyphs.withIndex().filter { (_, g) ->
                center(g) > top && center(g) < bottom && g.left + g.width / 2 > left && g.left + g.width / 2 < right
            }
            if (selected.any { (_, g) -> g.left < left - 1f || g.left + g.width > right + 1f }) return null
            if (selected.any { it.index in consumed }) return null
            consumed += selected.map { it.index }
            return text(selected.map { it.value })
        }
        for ((top, bottom) in days.zipWithNext()) {
            val label = cell(xs[0], xs[1], top, bottom) ?: return null
            val match = dayWeek.matchEntire(label) ?: return null
            val date = runCatching { month.atDay(match.groupValues[1].toInt()) }.getOrNull() ?: return null
            if (!ScheduleWeekdays.agrees(match.groupValues[2], date) ||
                (last != null && date != last!!.plusDays(1)) ||
                (last == null && carried && date != previous!!.lastDay.plusDays(1))) return null
            last = date
            val memory = cell(xs[1], xs[2], top, bottom) ?: return null
            if (memory.isBlank()) return null
            val serviceRows = listOf(top) + ys.filter { it > top + GAP && it < bottom - GAP &&
                covers(horizontal, it, xs[2], xs[4]) } + bottom
            if (serviceRows.size !in 2..9) return null
            var servicesOnDay = 0
            for ((start, end) in serviceRows.zipWithNext()) {
                if (end - start < 8f) return null
                val title = cell(xs[2], xs[3], start, end) ?: return null
                val clock = cell(xs[3], xs[4], start, end) ?: return null
                val time = ScheduleClocks.pattern.matchEntire(clock) ?: return null
                if (runCatching { LocalTime.of(time.groupValues[1].toInt(), time.groupValues[2].toInt()) }.isFailure || title.isBlank()) return null
                if (service.containsMatchIn(title)) servicesOnDay++
                // The full memory column remains safety evidence; only the display title omits it.
                lines += ScheduleDocumentLine("$date | $memory | $title | $clock",
                    serviceText = "$date | $title | $clock",
                    // An explicitly isolated opening-hours label is not the name of another building.
                    venueEvidence = if (title.trimEnd('.').equals("храм открыт", ignoreCase = true)) "$date | $memory" else null)
            }
            if (servicesOnDay == 0) return null
            datedServices++
        }
        if (datedServices < 3 || glyphs.withIndex().any { (index, g) -> center(g) >= days.first() && index !in consumed }) return null
        return Result(ScheduleDocumentPage(lines), Continuation(month, xs, requireNotNull(last), venue, monthHeading))
    }

    private fun coordinates(rules: List<Rule>): List<Float> {
        val groups = mutableListOf<MutableList<Float>>()
        rules.map { it.at }.sorted().forEach { value ->
            val last = groups.lastOrNull()
            if (last == null || value - last.first() > 1.2f) groups += mutableListOf(value) else last += value
        }
        return groups.map { it.average().toFloat() }
    }

    private fun covers(rules: List<Rule>, at: Float, start: Float, end: Float): Boolean {
        var reached = start
        for (rule in rules.filter { abs(it.at - at) <= 1.2f && it.end >= start && it.start <= end }.sortedBy { it.start }) {
            if (rule.start > reached + GAP) return false
            reached = maxOf(reached, rule.end)
        }
        return reached >= end - GAP
    }
}
