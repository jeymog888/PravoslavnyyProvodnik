package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** Format-independent rows. A missing year is accepted only with qualified, reported provenance. */
object ScheduleDocumentParser {
    private val monthNames = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val monthPattern = "(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])"
    private val monthYear = Regex("(?iu)$monthPattern\\s+(20\\d{2})")
    private val namedDate = Regex("(?iu)(?<![\\d.-])(\\d{1,2})\\s+$monthPattern(?:\\s+(20\\d{2}))?")
    private val numericDate = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](20\\d{2})(?!\\d)")
    private val shortYearDate = Regex("(?<![\\d./-])(\\d{1,2})\\s*[./]\\s*(\\d{1,2})\\s*[./]\\s*(\\d{2})(?![\\d./])")
    private val scopedNumericDate = Regex("(?iu)^\\s*(\\d{1,2})\\s*[./]\\s*(\\d{1,2})(?![\\d./])(?=\\s*,?\\s*(?:\\||(?:${ScheduleWeekdays.dateWords})\\b))")
    private val isoDate = Regex("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)")
    private val dayPrefix = Regex("(?iu)^\\s*(\\d{1,2})\\s*[,| ]*\\s*(${ScheduleWeekdays.dateWords})\\.?\\b")
    private val simpleDay = Regex("^\\s*(\\d{1,2})\\s*\\|")
    private val namedRange = Regex("(?iu)(?<![\\d./-])(?:с\\s+)?(\\d{1,2})(?:\\s+$monthPattern)?\\s*(?:[-‐‑–—]|по)\\s*(\\d{1,2})\\s+$monthPattern(?:\\s+(20\\d{2}))?")
    private val numericRange = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})(?:[./](20\\d{2}))?\\s*[-‐‑–—]\\s*(\\d{1,2})[./](\\d{1,2})(?:[./](20\\d{2}))?(?!\\d)")
    private val clocks = ScheduleClocks.pattern
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист|^заупокойное\\s+богослужение(?![а-яё]))")
    private val cancellation = ScheduleCancellation
    private val generalServices = Regex("(?iu)(?:все\\s+(?:службы|богослужения)|богослужения|службы)")
    private val exactLiturgy = Regex("(?iu)^(?:(?:божественная|ранняя|поздняя)\\s+)*литургия\\b")

    fun parse(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): List<InternetService> = inspect(document, today, parish).events

    fun inspect(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): ScheduleParseResult {
        // A supplementary pass can reveal a withdrawal whose scope is not readable. Do
        // not revive primary-pass events, or invent which dates/buildings were withdrawn.
        if (document.pages.size > 12 || document.pages.any {
                it.sourceTruncated || it.lines.size > 2_000 || it.ocrFailure == ScheduleOcrFailure.CONFLICTING_EVIDENCE })
            return ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true)
        val events = mutableListOf<InternetService>()
        val cancelledDates = mutableSetOf<LocalDate>()
        val cancelledStarts = mutableSetOf<String>()
        var rejectedScope = false
        var cancelledAll = false
        var safetyConflict = false
        var unresolvedWithdrawal = false
        fun rejectUnresolvedWithdrawal() { safetyConflict = true; unresolvedWithdrawal = true }
        val inferredMonths = ScheduleYearInference.months(document, today)
        document.pages.take(12).forEachIndexed { pageIndex, page ->
            if (page.ocrFailure != null) return@forEachIndexed
            data class HeadingScope(val cancelled: Boolean, val venue: String?, val date: LocalDate?, val lineIndex: Int)
            val headings = sortedMapOf<Int, HeadingScope>()
            var sectionVenue: String? = null
            var sectionVenueLine = -1
            var scope: YearMonth? = null
            var currentDate: LocalDate? = null
            // Ranges and unreadable day boundaries cannot supply an inherited day.
            var requiresDay = false
            var rangeScope: ClosedRange<LocalDate>? = null
            var pendingClock: String? = null
            var pendingTitle = ""
            var pendingDate: LocalDate? = null
            var pendingVenue: String? = null
            var rejected = false
            data class DayContainerScope(val venue: String?, val venueLine: Int, val month: YearMonth?, val date: LocalDate?,
                val headings: Map<Int, HeadingScope>, val requiresDay: Boolean, val rangeScope: ClosedRange<LocalDate>?)
            val dayContainers = ArrayDeque<DayContainerScope>()
            fun flush() {
                val day = pendingDate
                val time = pendingClock
                if (rejected && day != null && time != null) {
                    rejectedScope = true
                    cancelledStarts += "${day}T$time"
                }
                if (!rejected && day != null && time != null) event(day, time, pendingTitle, today, parish, pendingVenue)?.let(events::add)
                pendingDate = null; pendingClock = null; pendingTitle = ""; pendingVenue = null; rejected = false
            }
            fun clearDay() {
                flush(); currentDate = null
                headings.keys.toList().forEach { level -> headings[level] = headings.getValue(level).copy(date = null) }
            }
            fun enterMonth(text: String): MatchResult? {
                val month = monthYear.find(text)
                if (month != null && (!clocks.containsMatchIn(text) || hasDateRange(text))) {
                    clearDay()
                    scope = runCatching { YearMonth.of(month.groupValues[2].toInt(), monthNumber(month.groupValues[1])) }.getOrNull()
                    // An explicit month is a valid monthly withdrawal scope, not an unknown range.
                    requiresDay = false; rangeScope = null
                }
                return month
            }
            fun enterRange(text: String) {
                clearDay(); requiresDay = true; rangeScope = rangeDates(text, scope)
                rangeScope?.let { scope = YearMonth.from(it.start) }
            }
            page.lines.take(2_000).forEachIndexed lineLoop@{ lineIndex, line ->
                // A named venue inside one ISO calendar card belongs to that card. Keep all
                // outer restrictions, then restore them before reading its sibling card.
                if (line.startsDayContainer) {
                    flush()
                    dayContainers.addLast(DayContainerScope(sectionVenue, sectionVenueLine, scope, currentDate, headings.toMap(), requiresDay, rangeScope))
                    currentDate = null; requiresDay = false; rangeScope = null
                }
                if (line.endsDayContainer) {
                    flush()
                    dayContainers.removeLastOrNull()?.let { outer ->
                        sectionVenue = outer.venue; sectionVenueLine = outer.venueLine; scope = outer.month; currentDate = outer.date
                        headings.clear(); headings.putAll(outer.headings)
                        requiresDay = outer.requiresDay; rangeScope = outer.rangeScope
                    }
                    return@lineLoop
                }
                if (line.endsTable) {
                    flush()
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                    return@lineLoop
                }
                val clean = line.text.replace('\u00a0', ' ').replace(Regex("[\\t ]+"), " ").trim()
                if (clean.isEmpty()) return@lineLoop
                if (line.struck || line.confidence < 45 || line.temporalConfidence < 55) {
                    if (hasDateRange(clean) || looksLikeDateHeading(clean)) {
                        clearDay(); requiresDay = true; rangeScope = null
                    } else { flush(); currentDate = null }
                    if (monthYear.containsMatchIn(clean)) scope = null
                    return@lineLoop
                }
                // Multiline table cells must apply temporal boundaries in physical source order.
                val singlePhysicalLine = '\n' !in clean
                val rangeHeading = singlePhysicalLine && hasDateRange(clean)
                val month = if (singlePhysicalLine) enterMonth(clean) else null
                inferredMonths[pageIndex]?.takeIf { lineIndex in it.headingIndices }?.let { inferred ->
                    flush(); scope = inferred.value; currentDate = null
                }
                if (rangeHeading) enterRange(clean)
                val venueEvidence = line.venueEvidence ?: clean
                val venueLabel = venueEvidence.takeIf(ScheduleVenue::isLabel)?.let { ScheduleVenue.inlineLabel(it) ?: it }
                if (line.headingLevel > 0) {
                    flush()
                    headings.keys.filter { it >= line.headingLevel }.toList().forEach(headings::remove)
                    val headingDate = dateIn(clean, scope)?.first
                    headings[line.headingLevel] = HeadingScope(cancellation.containsMatchIn(clean) && (!requiresDay || headingDate != null),
                        venueLabel, headingDate, lineIndex)
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                } else if (venueLabel != null) {
                    flush(); sectionVenue = venueLabel; sectionVenueLine = lineIndex
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                    if (!cancellation.containsMatchIn(clean)) return@lineLoop
                }
                val locatedRowDate = dateIn(clean, scope)
                val rowDate = locatedRowDate?.first ?: currentDate
                val venueText = dateIn(venueEvidence, scope)?.let { venueEvidence.removeRange(it.second) } ?: venueEvidence
                // A plain venue label and an active venue heading are both explicit
                // evidence. The later one governs; a generic heading cannot erase the
                // underlying parish signature when an older venue heading leaves scope.
                val headingVenue = headings.values.filter { it.venue != null }.maxByOrNull { it.lineIndex }
                val inheritedVenue = if (sectionVenue != null && sectionVenueLine > (headingVenue?.lineIndex ?: -1))
                    sectionVenue else headingVenue?.venue ?: sectionVenue
                val scopedVenue = line.venue ?: ScheduleVenue.inlineLabel(venueText, rowDate, datedPreface = locatedRowDate != null) ?:
                    inheritedVenue
                val scopedCancellation = headings.values.any { it.cancelled }
                if (!ScheduleVenue.matches(scopedVenue, parish)) { flush(); currentDate = null; rejectedScope = true; return@lineLoop }
                if (scopedCancellation) rejectedScope = true
                clean.split('\n').forEach { physical ->
                    var text = physical.trim()
                    // OCR may identify a service column, but dates and withdrawals
                    // continue to use the untouched full physical row in `text`.
                    var titleText = line.serviceText?.takeIf { '\n' !in clean }?.trim()
                    if (text.isBlank()) return@forEach
                    val rangeLine = hasDateRange(text)
                    val physicalMonth = if (singlePhysicalLine) month else enterMonth(text)
                    if (!singlePhysicalLine && rangeLine) enterRange(text)
                    if (Regex("(?iu)^(?:кажд(?:ый|ую|ое|ые|ая)|ежедневно|еженедельно)(?=\\s|:)").containsMatchIn(text)) {
                        // This can be a <br>-separated footer inside the same HTML paragraph.
                        // A weekly/daily rule never inherits the last printed date.
                        flush(); currentDate = null
                        headings.keys.toList().forEach { level -> headings[level] = headings.getValue(level).copy(date = null) }
                        return@forEach
                    }
                    if (Regex("(?iu)^(адрес|телефон|www\\.|https?://|с\\s+\\d+\\s+[а-я]+.*начало)").containsMatchIn(text)) { flush(); return@forEach }
                    val located = dateIn(text, scope)
                    if (located != null) {
                        flush()
                        currentDate = located.first
                        text = text.removeRange(located.second).trim(' ', '|', ',', '.', '-')
                        val stated = ScheduleWeekdays.inDateRemainder(text)?.groupValues?.get(1)
                        if (stated != null && !ScheduleWeekdays.agrees(stated, currentDate!!)) {
                            clearDay(); requiresDay = true; rangeScope = null
                            if (cancellation.containsMatchIn(text)) rejectUnresolvedWithdrawal()
                            return@forEach
                        }
                        requiresDay = false; rangeScope = null
                        text = ScheduleWeekdays.removeFromDateRemainder(text).trim(' ', '|', ',', '.')
                        titleText = titleText?.let { title ->
                            val titleDate = dateIn(title, scope)
                            ScheduleWeekdays.removeFromDateRemainder(titleDate?.let { title.removeRange(it.second) } ?: title)
                                .trim(' ', '|', ',', '.', '-')
                        }
                    } else if (!rangeLine && looksLikeDateHeading(text)) {
                        clearDay(); requiresDay = true; rangeScope = null
                        if (cancellation.containsMatchIn(text)) rejectUnresolvedWithdrawal()
                        return@forEach
                    }
                    val matches = clocks.findAll(text).toList()
                    val titleClocks = titleText?.let { clocks.findAll(it).toList() }
                    val useServiceColumn = titleClocks != null && titleClocks.map { it.groupValues.drop(1) } == matches.map { it.groupValues.drop(1) }
                    // A range has no single start time. Ambiguous service/time withdrawals
                    // must not disappear merely because the endpoint is no longer a day.
                    if (requiresDay && currentDate == null && cancellation.containsMatchIn(text) &&
                        (matches.isNotEmpty() || !generalServices.containsMatchIn(text))) rejectUnresolvedWithdrawal()
                    if (matches.isEmpty() && cancellation.containsMatchIn(text) && generalServices.containsMatchIn(text)) {
                        // A day/building section must not withdraw unrelated days or buildings.
                        val day = currentDate
                        val monthScope = scope
                        if (day != null) cancelledDates += day
                        else if (requiresDay) {
                            val range = rangeScope
                            if (range == null) rejectUnresolvedWithdrawal()
                            else (0..java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt())
                                .map { range.start.plusDays(it.toLong()) }.filter { it in today.minusDays(1)..today.plusDays(45) }
                                .forEach(cancelledDates::add)
                        } else if (monthScope != null) (1..monthScope.lengthOfMonth()).map(monthScope::atDay)
                            .filter { it in today.minusDays(1)..today.plusDays(45) }.forEach(cancelledDates::add)
                        else cancelledAll = true
                    }
                    if (matches.isNotEmpty()) {
                        flush()
                        if (rangeLine || currentDate == null) return@forEach
                        // Many tables put the title before its time; preserve that order as well.
                        matches.forEachIndexed { index, match ->
                            flush()
                            val hour = match.groupValues[1].toIntOrNull()
                            val minute = match.groupValues[2].toIntOrNull()
                            val time = runCatching { LocalTime.of(requireNotNull(hour), requireNotNull(minute)).toString() }.getOrNull()
                            val title = if (useServiceColumn) requireNotNull(titleText) else text
                            val titleMatches = if (useServiceColumn) requireNotNull(titleClocks) else matches
                            val titleMatch = titleMatches[index]
                            val tail = title.substring(titleMatch.range.last + 1, titleMatches.getOrNull(index + 1)?.range?.first ?: title.length)
                            val lead = if (index == 0) title.substring(0, titleMatch.range.first) else ""
                            pendingClock = time
                            pendingDate = currentDate
                            pendingVenue = scopedVenue
                            pendingTitle = (lead + " " + tail).trim(' ', '|', ',', '.', '-')
                            rejected = scopedCancellation || cancellation.containsMatchIn(text)
                            if (rejected) {
                                rejectedScope = true
                                if (time != null) cancelledStarts += "${currentDate}T$time"
                            }
                        }
                    } else if (!rangeLine && pendingClock != null && located == null && physicalMonth == null) {
                        if (pendingTitle.length + text.length < 500 && !Regex("(?iu)(расписан|пожертв|контакт|наверх)").containsMatchIn(text)) {
                            val continuation = titleText ?: text
                            if (continuation.isNotBlank()) pendingTitle += " " + continuation
                            rejected = rejected || cancellation.containsMatchIn(text)
                        } else flush()
                    }
                }
            }
            flush()
        }
        val accepted = events.filterNot { safetyConflict || cancelledAll || LocalDate.parse(it.date) in cancelledDates || "${it.date}T${it.time}" in cancelledStarts }
            .distinctBy { listOf(it.date, it.time, it.title) }
            .sortedWith(compareBy<InternetService> { it.date }.thenBy { it.time }).take(200)
        return ScheduleParseResult(when {
            safetyConflict -> ScheduleParseDisposition.REJECTED
            accepted.isNotEmpty() -> ScheduleParseDisposition.PARSED
            cancelledAll || cancelledDates.isNotEmpty() || rejectedScope -> ScheduleParseDisposition.REJECTED
            else -> ScheduleParseDisposition.UNSUPPORTED
        }, accepted, cancelledDates.map(LocalDate::toString).toSet(), cancelledStarts, cancelledAll,
            yearInferred = accepted.isNotEmpty() && inferredMonths.isNotEmpty(), safetyConflict = safetyConflict,
            unresolvedWithdrawal = unresolvedWithdrawal)
    }

    private fun monthNumber(word: String): Int = monthNames.indexOfFirst { word.lowercase(Locale.ROOT).startsWith(it) } + 1

    // Multiple ISO dates in one physical row cannot establish a single inherited day.
    private fun hasDateRange(text: String): Boolean = isoDate.findAll(text).take(2).count() == 2 || namedRange.containsMatchIn(text) || numericRange.findAll(text).any {
        it.groupValues[3].isNotEmpty() || it.groupValues[6].isNotEmpty()
    }

    private fun rangeDates(text: String, month: YearMonth?): ClosedRange<LocalDate>? = runCatching {
        val namedMatches = namedRange.findAll(text).toList()
        val numericMatches = numericRange.findAll(text).filter { it.groupValues[3].isNotEmpty() || it.groupValues[6].isNotEmpty() }.toList()
        require(namedMatches.size + numericMatches.size == 1)
        val named = namedMatches.singleOrNull()
        val start: LocalDate
        val end: LocalDate
        if (named != null) {
            val year = named.groupValues[5].toIntOrNull() ?: requireNotNull(month).year
            val endMonth = monthNumber(named.groupValues[4])
            val startMonth = named.groupValues[2].takeIf(String::isNotEmpty)?.let(::monthNumber) ?: endMonth
            start = LocalDate.of(year, startMonth, named.groupValues[1].toInt())
            end = LocalDate.of(year, endMonth, named.groupValues[3].toInt())
        } else {
            val numeric = numericMatches.single()
            val startYear = numeric.groupValues[3].toIntOrNull() ?: numeric.groupValues[6].toInt()
            val endYear = numeric.groupValues[6].toIntOrNull() ?: startYear
            start = LocalDate.of(startYear, numeric.groupValues[2].toInt(), numeric.groupValues[1].toInt())
            end = LocalDate.of(endYear, numeric.groupValues[5].toInt(), numeric.groupValues[4].toInt())
        }
        require(YearMonth.from(start) == YearMonth.from(end) && start <= end)
        start..end
    }.getOrNull()

    private fun dateIn(text: String, month: YearMonth?): Pair<LocalDate, IntRange>? {
        if (hasDateRange(text)) return null
        isoDate.find(text)?.let { match ->
            return runCatching { LocalDate.parse(match.groupValues[1]) to match.range }.getOrNull()
        }
        numericDate.find(text)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()) to match.range }.getOrNull()
        }
        shortYearDate.find(text)?.let { match ->
            val printedYear = month?.year ?: return null
            if (printedYear % 100 != match.groupValues[3].toInt()) return null
            return runCatching { LocalDate.of(printedYear, match.groupValues[2].toInt(), match.groupValues[1].toInt()) to match.range }.getOrNull()
        }
        scopedNumericDate.find(text)?.let { match ->
            // A printed year in the enclosing month is required; the device's year is never inferred.
            val year = month?.year ?: return null
            return runCatching { LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[1].toInt()) to match.range }.getOrNull()
        }
        namedDate.find(text)?.let { match ->
            val year = match.groupValues[3].toIntOrNull() ?: month?.year ?: return null
            return runCatching { LocalDate.of(year, monthNumber(match.groupValues[2]), match.groupValues[1].toInt()) to match.range }.getOrNull()
        }
        val prefix = dayPrefix.find(text) ?: simpleDay.find(text) ?: return null
        // Keep the weekday for validation rather than removing it with the day number.
        val range = prefix.groups[1]!!.range
        return runCatching { requireNotNull(month).atDay(prefix.groupValues[1].toInt()) to range }.getOrNull()
    }

    private fun looksLikeDateHeading(text: String): Boolean =
        !Regex("(?iu)^\\s*\\d+\\s*[-‐‑–—]\\s*лети[еяю]\\b").containsMatchIn(text) &&
            Regex("(?iu)^\\s*\\d{1,2}\\s*(?:[.,/-]|[а-яё]{2,}|\\|)").containsMatchIn(text) && !clocks.find(text).let { it?.range?.first == 0 }

    private fun event(date: LocalDate, clock: String, raw: String, today: LocalDate, parish: Parish?, scopedVenue: String?): InternetService? {
        if (date !in today.minusDays(1)..today.plusDays(45)) return null
        val title = raw.replace('|', ' ').replace(Regex("\\s+"), " ").trim(' ', '.', ',')
        if (title.length !in 2..300 || title.any(Char::isISOControl) || !service.containsMatchIn(title) || cancellation.containsMatchIn(title)) return null
        // Another named building is not automatically part of the selected parish.
        if (!ScheduleVenue.matches(scopedVenue, parish) || !ScheduleVenue.matches(ScheduleVenue.inlineLabel(title), parish)) return null
        val anotherRite = Regex("(?iu)(преждеосвящ|василия|иакова|якова)").containsMatchIn(title)
        return InternetService(date.toString(), clock, title, if (!anotherRite && exactLiturgy.containsMatchIn(title)) "liturgy" else "service_block")
    }
}
