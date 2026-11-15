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

    private data class SharedVenueEvidence(val raw: String, val title: String, val venue: String)

    fun parse(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): List<InternetService> = inspect(document, today, parish).events

    fun inspect(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): ScheduleParseResult {
        // A supplementary pass can reveal a withdrawal whose scope is not readable. Do
        // not revive primary-pass events, or invent which dates/buildings were withdrawn.
        if (document.pages.size > 12 || document.pages.any {
                it.sourceTruncated || it.lines.size > 2_000 || it.ocrFailure == ScheduleOcrFailure.CONFLICTING_EVIDENCE })
            return ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true)
        val events = mutableListOf<InternetService>()
        data class CalendarAttempt(val date: LocalDate?, val month: YearMonth, val raw: String, val struck: Boolean)
        val calendarAttempts = mutableMapOf<Pair<Int, String>, CalendarAttempt>()
        val representedCalendarRows = mutableMapOf<Pair<Int, String>, InternetService>()
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
            // A provenance marker alone is insufficient: walk may not emit its table.
            val emittedCalendarScopes = page.lines.mapNotNull { line ->
                line.htmlCalendarNotice?.takeIf { it.text == line.text }?.scope?.takeIf { it.text == line.text }
            }.toSet()
            data class HeadingScope(val cancelled: Boolean, val venue: String?, val date: LocalDate?, val lineIndex: Int)
            val headings = sortedMapOf<Int, HeadingScope>()
            var sectionVenue: String? = null
            var sectionVenueLine = -1
            var scope: YearMonth? = null
            var currentDate: LocalDate? = null
            data class BoundHtmlDay(val container: Int, val date: LocalDate)
            var boundDay: BoundHtmlDay? = null
            val safeSharedPage = document.format == "html" && listOfNotNull(parish?.name, parish?.shortName, parish?.city, parish?.address)
                .none(ScheduleVenue::hasUnsafeSharedInput) && page.lines.none {
                ScheduleVenue.hasUnsafeSharedInput(it.text) || it.htmlEventRow?.let(ScheduleVenue::hasUnsafeSharedInput) == true ||
                    it.htmlDayHeading?.let(ScheduleVenue::hasUnsafeSharedInput) == true
            }
            // Ranges and unreadable day boundaries cannot supply an inherited day.
            var requiresDay = false
            var rangeScope: ClosedRange<LocalDate>? = null
            var pendingClock: String? = null
            var pendingTitle = ""
            var pendingDate: LocalDate? = null
            var pendingVenue: String? = null
            var pendingSharedVenue: SharedVenueEvidence? = null
            var pendingCalendarRow: Pair<Int, String>? = null
            var activeCalendarRow: Pair<Int, String>? = null
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
                if (!rejected && day != null && time != null) event(day, time, pendingTitle, today, parish, pendingVenue, pendingSharedVenue)?.let { accepted ->
                    events += accepted
                    pendingCalendarRow?.let { representedCalendarRows[it] = accepted }
                }
                pendingDate = null; pendingClock = null; pendingTitle = ""; pendingVenue = null; pendingSharedVenue = null; pendingCalendarRow = null; rejected = false
            }
            fun clearDay() {
                flush(); currentDate = null; boundDay = null
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
                line.htmlCalendarNotice?.let { notice ->
                    if (notice.text != line.text) {
                        if (ScheduleWeekdayTable.cancellation(line.text)) { clearDay(); rejectUnresolvedWithdrawal() }
                        return@lineLoop
                    }
                    if (!ScheduleWeekdayTable.cancellation(notice.text)) return@lineLoop
                    val headingVenue = headings.values.filter { it.venue != null }.maxByOrNull { it.lineIndex }
                    val inherited = if (sectionVenue != null && sectionVenueLine > (headingVenue?.lineIndex ?: -1))
                        sectionVenue else headingVenue?.venue ?: sectionVenue
                    val governingLines = headings.values.map { it.lineIndex } +
                        listOfNotNull(sectionVenueLine.takeIf { sectionVenue != null && it > (headingVenue?.lineIndex ?: -1) })
                    val restrictedAuthority = governingLines.any { index ->
                        val source = page.lines.getOrNull(index)
                        if (notice.scope != null && source?.htmlCalendarScope == notice.scope) false
                        else source?.let { it.htmlCalendarScope?.text ?: it.htmlScopeText }
                            ?.let(ScheduleVenue::hasSharedScopeRestriction) ?: true
                    }
                    if (restrictedAuthority) {
                        clearDay(); requiresDay = true; rangeScope = null; rejectUnresolvedWithdrawal()
                        return@lineLoop
                    }
                    if (!ScheduleVenue.matches(inherited, parish) || ScheduleWeekdayTable.clearlyForeignNotice(notice.text, parish)) return@lineLoop
                    clearDay(); requiresDay = true; rangeScope = null
                    val starts = notice.starts(parish)
                    if (starts == null) rejectUnresolvedWithdrawal()
                    else {
                        // Caption/owner scope withdraws its proven listed starts, never the whole month or a sibling table.
                        starts.filter { runCatching { LocalDate.parse(it.substringBefore('T')) in today.minusDays(1)..today.plusDays(45) }.getOrDefault(false) }
                            .forEach(cancelledStarts::add)
                    }
                    return@lineLoop
                }
                val calendar = line.htmlCalendarRow
                // Every calendar physical row owns its date; neither siblings nor a footer borrow it.
                if (calendar != null || activeCalendarRow != null) { clearDay(); requiresDay = true; rangeScope = null }
                activeCalendarRow = calendar?.let { pageIndex to "${it.tableKey}:${it.permitted}:${it.rowId}" }
                if (line.htmlDayContainer != boundDay?.container) boundDay = null
                // A named venue inside one ISO calendar card belongs to that card. Keep all
                // outer restrictions, then restore them before reading its sibling card.
                if (line.startsDayContainer) {
                    flush()
                    dayContainers.addLast(DayContainerScope(sectionVenue, sectionVenueLine, scope, currentDate, headings.toMap(), requiresDay, rangeScope))
                    currentDate = null; boundDay = null; requiresDay = false; rangeScope = null
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
                    flush(); boundDay = null
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                    return@lineLoop
                }
                val raw = line.text.replace('\u00a0', ' ').replace(Regex("[\\t ]+"), " ").trim()
                val calendarDate = calendar?.takeIf { it.validFor(raw) }?.date()
                if (calendar != null) {
                    if (!calendar.validFor(raw)) {
                        if (cancellation.containsMatchIn(raw)) rejectUnresolvedWithdrawal()
                        return@lineLoop
                    }
                    if (calendar.cells[3].any(Char::isDigit))
                        calendarAttempts[requireNotNull(activeCalendarRow)] = CalendarAttempt(calendarDate, calendar.month(), raw, line.struck)
                    if (!calendar.permitted || !calendar.hasDateColumns() || calendarDate == null || calendar.clock() == null) {
                        // No unambiguous temporal row: retain a read cancellation conservatively.
                        if (cancellation.containsMatchIn(raw)) {
                            if (calendar.permitted && calendarDate != null && generalServices.containsMatchIn(raw)) cancelledDates += calendarDate
                            else rejectUnresolvedWithdrawal()
                        }
                        return@lineLoop
                    }
                }
                val clean = calendarDate?.let { "$it | $raw" } ?: raw
                if (clean.isEmpty()) return@lineLoop
                if (line.struck || line.confidence < 45 || line.temporalConfidence < 55) {
                    if (hasDateRange(clean) || looksLikeDateHeading(clean)) {
                        clearDay(); requiresDay = true; rangeScope = null
                    } else { flush(); currentDate = null; boundDay = null }
                    if (monthYear.containsMatchIn(clean)) scope = null
                    return@lineLoop
                }
                // Multiline table cells must apply temporal boundaries in physical source order.
                val singlePhysicalLine = '\n' !in clean
                val rangeHeading = singlePhysicalLine && hasDateRange(clean)
                val month = if (singlePhysicalLine) enterMonth(clean) else null
                inferredMonths[pageIndex]?.takeIf { lineIndex in it.headingIndices }?.let { inferred ->
                    flush(); scope = inferred.value; currentDate = null; boundDay = null
                }
                if (rangeHeading) enterRange(clean)
                val representedScope = line.htmlCalendarScope
                val scopeOnly = representedScope != null || line.htmlCalendarScopeAmbiguous
                val venueEvidence = representedScope?.text ?: line.venueEvidence ?: clean
                val venueLabel = venueEvidence.takeIf(ScheduleVenue::isLabel)?.let { ScheduleVenue.inlineLabel(it) ?: it }
                if (line.headingLevel > 0) {
                    flush(); boundDay = null
                    headings.keys.filter { it >= line.headingLevel }.toList().forEach(headings::remove)
                    val headingDate = dateIn(if (scopeOnly) venueEvidence else clean, scope)?.first
                    headings[line.headingLevel] = HeadingScope(!scopeOnly && cancellation.containsMatchIn(clean) && (!requiresDay || headingDate != null),
                        venueLabel, headingDate, lineIndex)
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                } else if (venueLabel != null) {
                    boundDay = null
                    flush(); sectionVenue = venueLabel; sectionVenueLine = lineIndex
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                    if (!scopeOnly && !cancellation.containsMatchIn(clean)) return@lineLoop
                }
                if (scopeOnly) {
                    // Heading/venue/month authority above remains in its source position.
                    // The complete emitted notice alone owns withdrawal scope. Orphan or
                    // mixed origins cannot silently discard a cancellation.
                    if (line.htmlCalendarScopeAmbiguous || representedScope !in emittedCalendarScopes)
                        rejectUnresolvedWithdrawal()
                    flush(); boundDay = null
                    return@lineLoop
                }
                val locatedRowDate = dateIn(clean, scope)
                if (locatedRowDate != null && line.htmlDayHeading == null) boundDay = null
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
                fun rawScopeRestricted(index: Int): Boolean {
                    val raw = page.lines.getOrNull(index)?.let { it.htmlCalendarScope?.text ?: it.htmlScopeText }
                    return raw == null || ScheduleVenue.hasSharedScopeRestriction(raw)
                }
                if (calendar != null && ScheduleWeekdayTable.cancellation(raw) && !cancellation.containsMatchIn(raw)) {
                    val clock = calendar.clock()
                    if (!scopedCancellation && calendar.permitted && calendarDate != null && clock != null &&
                        ScheduleWeekdays.agrees(calendar.cells[0].removeSuffix("."), calendarDate) && ScheduleVenue.matches(inheritedVenue, parish))
                        cancelledStarts += "${calendarDate}T$clock"
                    else rejectUnresolvedWithdrawal()
                    clearDay(); requiresDay = true; rangeScope = null
                    return@lineLoop
                }
                val sharedScopeRestricted = headings.values.any { rawScopeRestricted(it.lineIndex) } ||
                    (sectionVenue != null && sectionVenueLine > (headingVenue?.lineIndex ?: -1) &&
                        rawScopeRestricted(sectionVenueLine))
                fun calendarScopeRestricted(index: Int): Boolean = page.lines.getOrNull(index)?.let { it.htmlCalendarScope?.text ?: it.htmlScopeText }
                    ?.let(ScheduleWeekdayTable::restricted) ?: true
                val restrictedCalendarScope = calendar != null && (headings.values.any { calendarScopeRestricted(it.lineIndex) } ||
                    (sectionVenue != null && sectionVenueLine > (headingVenue?.lineIndex ?: -1) && calendarScopeRestricted(sectionVenueLine)))
                if (calendar != null && ((!scopedCancellation && restrictedCalendarScope) ||
                        (ScheduleWeekdayTable.restricted(raw) && !cancellation.containsMatchIn(raw)))) {
                    if (cancellation.containsMatchIn(raw)) rejectUnresolvedWithdrawal()
                    clearDay(); requiresDay = true; rangeScope = null
                    return@lineLoop
                }
                val wholeEventRow = line.htmlEventRow?.takeIf { raw ->
                    !ScheduleVenue.hasUnsafeSharedInput(raw) && raw.replace('\u00a0', ' ').replace(Regex("[ ]+"), " ").trim(' ') == clean
                }
                val provenDay = boundDay?.takeIf { it.container == line.htmlDayContainer && it.date == currentDate }
                val sharedVenue = if (safeSharedPage && !sharedScopeRestricted && wholeEventRow != null && provenDay != null &&
                    singlePhysicalLine && locatedRowDate == null && rowDate == provenDay.date && !rangeHeading &&
                    line.venue == null && line.venueEvidence == null && line.serviceText == null &&
                    line.confidence == 100 && line.temporalConfidence == 100 &&
                    ScheduleVenue.matches(inheritedVenue, parish) && !scopedCancellation)
                    ScheduleVenue.sharedEventVenue(wholeEventRow, parish) else null
                if (!ScheduleVenue.matches(scopedVenue, parish) && sharedVenue == null) {
                    flush()
                    // Preserve only a date actually parsed from this same DOM heading/list.
                    // A new date, withdrawal, foreign heading or table boundary never borrows it.
                    val sameParentDay = safeSharedPage && !sharedScopeRestricted && wholeEventRow != null && provenDay != null &&
                        !requiresDay && locatedRowDate == null && singlePhysicalLine && !rangeHeading &&
                        line.headingLevel == 0 && line.venue == null && ScheduleVenue.matches(inheritedVenue, parish) &&
                        !scopedCancellation && !cancellation.containsMatchIn(clean) &&
                        ScheduleVenue.isConcreteForeignEvent(wholeEventRow, parish)
                    if (!sameParentDay) { currentDate = null; boundDay = null }
                    rejectedScope = true
                    return@lineLoop
                }
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
                        flush(); currentDate = null; boundDay = null
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
                        boundDay = line.htmlDayContainer?.takeIf { safeSharedPage && line.htmlDayHeading != null && line.headingLevel > 0 }
                            ?.let { BoundHtmlDay(it, currentDate!!) }
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
                            pendingCalendarRow = activeCalendarRow
                            pendingSharedVenue = sharedVenue?.takeIf { matches.size == 1 && currentDate == rowDate }
                                ?.let { SharedVenueEvidence(requireNotNull(wholeEventRow), pendingTitle, it) }
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
        val knownPartial = calendarAttempts.any { (id, row) ->
            val relevant = row.date?.let { it in today..today.plusDays(45) } ?:
                (row.month.atEndOfMonth() >= today && row.month.atDay(1) <= today.plusDays(45))
            val times = ScheduleClocks.pattern.findAll(row.raw).mapNotNull { clock ->
                runCatching { LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt()).toString() }.getOrNull()
            }.toList()
            val withdrawn = cancelledAll || row.date?.let { date ->
                date in cancelledDates || times.singleOrNull()?.let { time -> "${date}T$time" in cancelledStarts } == true
            } == true
            relevant && !row.struck && !withdrawn &&
                representedCalendarRows[id]?.let { it in accepted } != true
        }
        return ScheduleParseResult(when {
            safetyConflict -> ScheduleParseDisposition.REJECTED
            accepted.isNotEmpty() -> ScheduleParseDisposition.PARSED
            cancelledAll || cancelledDates.isNotEmpty() || rejectedScope -> ScheduleParseDisposition.REJECTED
            else -> ScheduleParseDisposition.UNSUPPORTED
        }, accepted, cancelledDates.map(LocalDate::toString).toSet(), cancelledStarts, cancelledAll,
            yearInferred = accepted.isNotEmpty() && inferredMonths.isNotEmpty(), safetyConflict = safetyConflict,
            unresolvedWithdrawal = unresolvedWithdrawal, knownPartial = knownPartial)
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

    private fun event(date: LocalDate, clock: String, raw: String, today: LocalDate, parish: Parish?, scopedVenue: String?, sharedVenue: SharedVenueEvidence?): InternetService? {
        if (date !in today.minusDays(1)..today.plusDays(45)) return null
        val title = raw.replace('|', ' ').replace(Regex("\\s+"), " ").trim(' ', '.', ',')
        if (title.length !in 2..300 || title.any(Char::isISOControl) || !service.containsMatchIn(title) || cancellation.containsMatchIn(title)) return null
        // Another named building is not automatically part of the selected parish.
        val shared = sharedVenue != null && sharedVenue.title == raw &&
            ScheduleVenue.sharedEventVenue(sharedVenue.raw, parish) == sharedVenue.venue
        if (!shared && (!ScheduleVenue.matches(scopedVenue, parish) || !ScheduleVenue.matches(ScheduleVenue.inlineLabel(title), parish))) return null
        val anotherRite = Regex("(?iu)(преждеосвящ|василия|иакова|якова)").containsMatchIn(title)
        return InternetService(date.toString(), clock, title, if (!anotherRite && exactLiturgy.containsMatchIn(title)) "liturgy" else "service_block")
    }
}
