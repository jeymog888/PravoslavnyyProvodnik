package ru.liturgyguide.service.schedule

import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** A qualified current-month interpretation, never evidence that the source printed the year. */
internal object ScheduleYearInference {
    data class Month(val value: YearMonth, val headingIndices: Set<Int>)
    private val monthRoots = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val monthWord = "(?:январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])"
    private val monthHeading = Regex("(?iu)^(?:расписание(?:\\s+богослужени[йя])?(?:\\s+на)?\\s+)?($monthWord)(?:\\s+месяц)?[.!]?$")
    private val printedYear = Regex("(?<!\\d)(?:19|20|21)\\d{2}(?!\\d)")
    private val shortPrintedYear = Regex("(?<!\\d)\\d{1,2}\\s*[./]\\s*\\d{1,2}\\s*[./]\\s*\\d{2}(?!\\d)")
    private val namedDay = Regex("(?iu)^\\s*(\\d{1,2})\\s+($monthWord)(?=\\s|[,|.]|$)")
    private val numericDay = Regex("^\\s*(\\d{1,2})\\s*[./]\\s*(\\d{1,2})(?![\\d./])")
    private val simpleDay = Regex("^\\s*(\\d{1,2})(?=\\s|\\|)")
    private val weekday = Regex("(?iu)^[\\s,|.-]*(пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?(?![а-яё])")
    private val weekdayNames = listOf(setOf("пн", "понедельник"), setOf("вт", "вторник"), setOf("ср", "среда"),
        setOf("чт", "четверг"), setOf("пт", "пятница"), setOf("сб", "суббота"), setOf("вс", "воскресенье"))

    fun months(document: ScheduleDocument, today: LocalDate): Map<Int, Month> {
        val pages = document.pages.take(12)
        // Calendars repeat (9 Nov is Monday in both 2015 and 2026). Archive evidence
        // in the actual document URL must defeat a plausible current-year reading.
        val sourceLocation = runCatching {
            val uri = URI(document.sourceUrl)
            URLDecoder.decode(uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty(), "UTF-8")
        }.getOrNull() ?: return emptyMap()
        if (printedYear.findAll(sourceLocation).any { it.value.toInt() != today.year }) return emptyMap()
        // A printed stale year must never be replaced by the phone's year, including a
        // two-digit year. A different page of the same document is still publication evidence.
        if (pages.any { page -> page.lines.any { printedYear.containsMatchIn(it.text) || shortPrintedYear.containsMatchIn(it.text) } }) return emptyMap()
        return pages.mapIndexedNotNull { index, page -> month(page, today)?.let { index to it } }.toMap()
    }

    private fun month(page: ScheduleDocumentPage, today: LocalDate): Month? {
        val headings = page.lines.mapIndexedNotNull { index, line ->
            monthHeading.matchEntire(line.text.replace('\u00a0', ' ').trim())?.let { index to monthNumber(it.groupValues[1]) }
        }
        if (headings.isEmpty()) return currentWeek(page, today)
        if (headings.any { it.second != today.monthValue }) return null
        if (headings.any { !reliable(page.lines[it.first]) }) return null
        val distinctDays = mutableSetOf<Int>()
        for (line in page.lines.drop(headings.first().first + 1)) {
            val text = line.text.replace('\u00a0', ' ').trim()
            val named = namedDay.find(text)
            val numeric = if (named == null) numericDay.find(text) else null
            val simple = if (named == null && numeric == null) simpleDay.find(text) else null
            val match = named ?: numeric ?: simple ?: continue
            val day = match.groupValues[1].toInt()
            val statedWeekday = weekday.find(text.substring(match.range.last + 1))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            // A bare number can be a feast/anniversary description. It supplies no date evidence.
            if (simple != null && statedWeekday == null) continue
            val month = when {
                named != null -> monthNumber(named.groupValues[2])
                numeric != null -> numeric.groupValues[2].toInt()
                else -> today.monthValue
            }
            if (month != today.monthValue || !reliable(line)) return null
            val date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
            if (statedWeekday != null) {
                if (statedWeekday !in weekdayNames[date.dayOfWeek.value - 1]) return null
                distinctDays += day
            }
        }
        if (distinctDays.size < 3) return null
        return Month(YearMonth.from(today), headings.map { it.first }.toSet())
    }

    /** Without a month heading, only complete day/month/weekday anchors in this civil week qualify. */
    private fun currentWeek(page: ScheduleDocumentPage, today: LocalDate): Month? {
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weekEnd = weekStart.plusDays(6)
        val anchors = linkedMapOf<Int, LocalDate>()
        for ((index, line) in page.lines.withIndex()) {
            val text = line.text.replace('\u00a0', ' ').trim()
            val named = namedDay.find(text)
            val numeric = if (named == null) numericDay.find(text) else null
            val match = named ?: numeric ?: continue
            val month = if (named != null) monthNumber(named.groupValues[2]) else numeric!!.groupValues[2].toInt()
            val stated = weekday.find(text.substring(match.range.last + 1))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (stated == null) {
                // A dotted clock (8.30) is not a date. Other printed date headings without
                // a weekday cannot be silently assigned the inferred year of nearby rows.
                if (numeric != null && month !in 1..12) continue
                return null
            }
            if (month != today.monthValue || !reliable(line)) return null
            val date = runCatching { LocalDate.of(today.year, month, match.groupValues[1].toInt()) }.getOrNull() ?: return null
            if (date !in weekStart..weekEnd || stated !in weekdayNames[date.dayOfWeek.value - 1]) return null
            anchors[index] = date
        }
        if (anchors.values.distinct().size < 3) return null
        // The first complete date establishes the same qualified month scope as a month heading
        return Month(YearMonth.from(today), setOf(anchors.keys.first()))
    }

    private fun reliable(line: ScheduleDocumentLine) = !line.struck && line.confidence >= 75 && line.temporalConfidence >= 75
    private fun monthNumber(value: String) = monthRoots.indexOfFirst { value.lowercase(Locale.ROOT).startsWith(it) } + 1
}
