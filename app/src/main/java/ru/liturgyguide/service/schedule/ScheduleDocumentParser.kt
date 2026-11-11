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
    private val scopedNumericDate = Regex("(?iu)^\\s*(\\d{1,2})\\s*[./]\\s*(\\d{1,2})(?![\\d./])(?=\\s*,?\\s*(?:\\||(?:пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\b))")
    private val isoDate = Regex("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)")
    private val dayPrefix = Regex("(?iu)^\\s*(\\d{1,2})\\s*[| ]*\\s*(пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?\\b")
    private val simpleDay = Regex("^\\s*(\\d{1,2})\\s*\\|")
    private val clocks = ScheduleClocks.pattern
    private val weekday = Regex("(?iu)(?<![а-яё])(пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?(?![а-яё])")
    private val weekdays = listOf(setOf("пн", "понедельник"), setOf("вт", "вторник"), setOf("ср", "среда"), setOf("чт", "четверг"), setOf("пт", "пятница"), setOf("сб", "суббота"), setOf("вс", "воскресенье"))
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val cancellation = Regex("(?iu)(отмен|перенес|перенос|не\\s+(?:будет|соверша|служит|состоится|провод)|без\\s+(?:литургии|богослужения))")
    private val generalServices = Regex("(?iu)(?:все\\s+(?:службы|богослужения)|богослужения|службы)")
    private val exactLiturgy = Regex("(?iu)^(?:(?:божественная|ранняя|поздняя)\\s+)*литургия\\b")

    fun parse(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): List<InternetService> = inspect(document, today, parish).events

    fun inspect(document: ScheduleDocument, today: LocalDate, parish: Parish? = null): ScheduleParseResult {
        val events = mutableListOf<InternetService>()
        val cancelledDates = mutableSetOf<LocalDate>()
        val cancelledStarts = mutableSetOf<String>()
        var rejectedScope = false
        var cancelledAll = false
        val inferredMonths = ScheduleYearInference.months(document, today)
        document.pages.take(12).forEachIndexed { pageIndex, page ->
            data class HeadingScope(val cancelled: Boolean, val venue: String?, val date: LocalDate?)
            val headings = sortedMapOf<Int, HeadingScope>()
            var sectionVenue: String? = null
            var scope: YearMonth? = null
            var currentDate: LocalDate? = null
            var pendingClock: String? = null
            var pendingTitle = ""
            var pendingDate: LocalDate? = null
            var pendingVenue: String? = null
            var rejected = false
            data class DayContainerScope(val venue: String?, val month: YearMonth?, val date: LocalDate?,
                val headings: Map<Int, HeadingScope>)
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
            page.lines.take(2_000).forEachIndexed lineLoop@{ lineIndex, line ->
                // A named venue inside one ISO calendar card belongs to that card. Keep all
                // outer restrictions, then restore them before reading its sibling card.
                if (line.startsDayContainer) {
                    flush()
                    dayContainers.addLast(DayContainerScope(sectionVenue, scope, currentDate, headings.toMap()))
                    currentDate = null
                }
                if (line.endsDayContainer) {
                    flush()
                    dayContainers.removeLastOrNull()?.let { outer ->
                        sectionVenue = outer.venue; scope = outer.month; currentDate = outer.date
                        headings.clear(); headings.putAll(outer.headings)
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
                    flush(); currentDate = null
                    if (monthYear.containsMatchIn(clean)) scope = null
                    return@lineLoop
                }
                // A heading can be split visually across two rows, but footer years are never used.
                val month = monthYear.find(clean)
                if (month != null && !clocks.containsMatchIn(clean)) {
                    flush()
                    scope = runCatching { YearMonth.of(month.groupValues[2].toInt(), monthNumber(month.groupValues[1])) }.getOrNull()
                    currentDate = null
                }
                inferredMonths[pageIndex]?.takeIf { lineIndex in it.headingIndices }?.let { inferred ->
                    flush(); scope = inferred.value; currentDate = null
                }
                val venueLabel = clean.takeIf(ScheduleVenue::isLabel)?.let { ScheduleVenue.inlineLabel(it) ?: it }
                if (line.headingLevel > 0) {
                    flush()
                    headings.keys.filter { it >= line.headingLevel }.toList().forEach(headings::remove)
                    headings[line.headingLevel] = HeadingScope(cancellation.containsMatchIn(clean), venueLabel, dateIn(clean, scope)?.first)
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                } else if (venueLabel != null) {
                    flush(); sectionVenue = venueLabel
                    currentDate = headings.values.mapNotNull { it.date }.lastOrNull()
                    if (!cancellation.containsMatchIn(clean)) return@lineLoop
                }
                val locatedRowDate = dateIn(clean, scope)
                val rowDate = locatedRowDate?.first ?: currentDate
                val venueText = locatedRowDate?.let { clean.removeRange(it.second) } ?: clean
                val scopedVenue = line.venue ?: ScheduleVenue.inlineLabel(venueText, rowDate, datedPreface = locatedRowDate != null) ?:
                    headings.values.mapNotNull { it.venue }.lastOrNull() ?: sectionVenue
                val scopedCancellation = headings.values.any { it.cancelled }
                if (!ScheduleVenue.matches(scopedVenue, parish)) { flush(); currentDate = null; rejectedScope = true; return@lineLoop }
                if (scopedCancellation) rejectedScope = true
                clean.split('\n').forEach { physical ->
                    var text = physical.trim()
                    if (text.isBlank()) return@forEach
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
                        val stated = weekday.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                        if (stated != null && stated !in weekdays[currentDate!!.dayOfWeek.value - 1]) currentDate = null
                        text = text.replace(weekday, " ").trim(' ', '|', ',', '.')
                    } else if (looksLikeDateHeading(text)) {
                        flush(); currentDate = null
                    }
                    val matches = clocks.findAll(text).toList()
                    if (matches.isEmpty() && cancellation.containsMatchIn(text) && generalServices.containsMatchIn(text)) {
                        // A day/building section must not withdraw unrelated days or buildings.
                        val day = currentDate
                        val monthScope = scope
                        if (day != null) cancelledDates += day
                        else if (monthScope != null) (1..monthScope.lengthOfMonth()).map(monthScope::atDay)
                            .filter { it in today.minusDays(1)..today.plusDays(45) }.forEach(cancelledDates::add)
                        else cancelledAll = true
                    }
                    if (matches.isNotEmpty()) {
                        flush()
                        if (currentDate == null) return@forEach
                        // Many tables put the title before its time; preserve that order as well.
                        matches.forEachIndexed { index, match ->
                            flush()
                            val hour = match.groupValues[1].toIntOrNull()
                            val minute = match.groupValues[2].toIntOrNull()
                            val time = runCatching { LocalTime.of(requireNotNull(hour), requireNotNull(minute)).toString() }.getOrNull()
                            val tail = text.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: text.length)
                            val lead = if (index == 0) text.substring(0, match.range.first) else ""
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
                    } else if (pendingClock != null && located == null && month == null) {
                        if (pendingTitle.length + text.length < 500 && !Regex("(?iu)(расписан|пожертв|контакт|наверх)").containsMatchIn(text)) {
                            pendingTitle += " " + text
                            rejected = rejected || cancellation.containsMatchIn(text)
                        } else flush()
                    }
                }
            }
            flush()
        }
        val accepted = events.filterNot { cancelledAll || LocalDate.parse(it.date) in cancelledDates || "${it.date}T${it.time}" in cancelledStarts }
            .distinctBy { listOf(it.date, it.time, it.title) }
            .sortedWith(compareBy<InternetService> { it.date }.thenBy { it.time }).take(200)
        return ScheduleParseResult(when {
            accepted.isNotEmpty() -> ScheduleParseDisposition.PARSED
            cancelledAll || cancelledDates.isNotEmpty() || rejectedScope -> ScheduleParseDisposition.REJECTED
            else -> ScheduleParseDisposition.UNSUPPORTED
        }, accepted, cancelledDates.map(LocalDate::toString).toSet(), cancelledStarts, cancelledAll,
            yearInferred = accepted.isNotEmpty() && inferredMonths.isNotEmpty())
    }

    private fun monthNumber(word: String): Int = monthNames.indexOfFirst { word.lowercase(Locale.ROOT).startsWith(it) } + 1

    private fun dateIn(text: String, month: YearMonth?): Pair<LocalDate, IntRange>? {
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
            Regex("(?iu)^\\s*\\d{1,2}\\s*(?:[./-]|[а-яё]{2,}|\\|)").containsMatchIn(text) && !clocks.find(text).let { it?.range?.first == 0 }

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
