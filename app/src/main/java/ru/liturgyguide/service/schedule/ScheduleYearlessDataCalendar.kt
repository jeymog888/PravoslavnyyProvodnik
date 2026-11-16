package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** Extractor-local evidence. The source did not print a year; no ISO text is manufactured. */
@ConsistentCopyVisibility
data class ScheduleYearlessCalendarLine internal constructor(
    val blockKey: String,
    val month: YearMonth,
    val day: Int,
    val header: String,
    val feasts: List<String>,
    val raw: String,
    val index: Int,
    val kind: Kind,
    val ambiguousNegative: Boolean = false
) {
    enum class Kind { HEADER, FEAST, ROW, NEGATIVE, UNRESOLVED }
    internal fun date(): LocalDate? = runCatching { month.atDay(day) }.getOrNull()
    internal fun validFor(text: String): Boolean = text == raw && Regex("[a-f0-9]{64}").matches(blockKey) && index >= 0 &&
        if (kind == Kind.UNRESOLVED) text.length <= 130_000
        else !ScheduleYearlessDataCalendar.unsafe(text) && date() != null && ScheduleYearlessDataCalendar.headerDate(header, month) == date()
    internal fun safetyText(): String = (listOf(header) + feasts +
        if (kind in setOf(Kind.HEADER, Kind.FEAST)) emptyList() else listOf(raw)).joinToString(" | ")
    internal fun displayText(): String? = if (kind == Kind.ROW) ScheduleYearlessDataCalendar.displayRow(raw) else null
    internal fun clock(): String? {
        if (kind != Kind.ROW || date()?.let { ScheduleYearlessDataCalendar.namedDatesAgree(safetyText(), it) } != true) return null
        val times = ScheduleClocks.pattern.findAll(safetyText()).toList()
        if (times.size != 1 || !ScheduleClocks.pattern.containsMatchIn(raw)) return null
        val value = times.single()
        return runCatching { LocalTime.of(value.groupValues[1].toInt(), value.groupValues[2].toInt()).toString() }.getOrNull()
    }
}

/** One complete current-civil-month data grammar, never an executable renderer or an arbitrary script. */
internal object ScheduleYearlessDataCalendar {
    private const val FOREIGN = "nav,[role=navigation],header,footer,aside,blockquote,q,[hidden],[inert],[aria-hidden=true],.sidebar,.comments,.comment-list,#comments,.commentlist,#respond,.comment-respond"
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val header = Regex("(?iu)^!?\\s*(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\s*,\\s*([1-9]|[12][0-9]|3[01])\\s+(" + months.joinToString("|") + ")$")
    private val potentialHeader = Regex("(?iu)^!?\\s*(?:понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\s*,")
    private val timed = Regex("^(\\d{1,2}:\\d{2})\\s*[-‐‑–—]\\s*(.+)$")
    private val anyYear = Regex("(?<!\\d)(?:1[6-9]|20|21)\\d{2}(?!\\d)")
    private val shortYear = Regex("(?<!\\d)\\d{1,2}[./]\\d{1,2}[./]\\d{2}(?!\\d)")
    private val historicalCouncil = Regex("(?iu)(?<![а-яё])отцов\\s+поместного\\s+собора\\s+церкви\\s+русской\\s+1917-1918\\s+годов(?![а-яё])")
    private val monthWord = Regex("(?iu)(?<![а-яё])(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])(?![а-яё])")
    private val monthRoots = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val archive = Regex("(?iu)(?<![а-яёa-z])(?:архив|archive)(?![а-яёa-z])")
    private val hiddenStyle = Regex("(?iu)(?:display\\s*:\\s*none|visibility\\s*:\\s*(?:hidden|collapse)|content-visibility\\s*:\\s*hidden)(?:\\s*[;!}]|\\s*$)")
    private val negative = Regex("(?iu)^СЛУЖБ\\s+НЕТ$")
    private val negativeWords = Regex("(?iu)(?<![а-яё])служб\\s+нет(?![а-яё])")
    private data class Day(val header: String, val number: Int, val feasts: MutableList<String> = mutableListOf(),
        val entries: MutableList<String> = mutableListOf())

    internal fun unsafe(text: String): Boolean = text.codePoints().anyMatch {
        Character.isISOControl(it) && it != 9 && it != 10 && it != 13 ||
            Character.getType(it) == Character.FORMAT.toInt() || Character.getType(it) == Character.SURROGATE.toInt()
    }

    internal fun headerDate(text: String, month: YearMonth): LocalDate? {
        val match = header.matchEntire(text.trim()) ?: return null
        if (months.indexOf(match.groupValues[3].lowercase(Locale.ROOT)) + 1 != month.monthValue) return null
        val date = runCatching { month.atDay(match.groupValues[2].toInt()) }.getOrNull() ?: return null
        return date.takeIf { ScheduleWeekdays.agrees(match.groupValues[1], it) }
    }

    internal fun namedDatesAgree(text: String, expected: LocalDate): Boolean {
        val named = Regex("(?iu)(?<!\\d)([1-9]|[12][0-9]|3[01])\\s+(" + months.joinToString("|") + ")(?![а-яё])")
        return named.findAll(text).all { match ->
            match.groupValues[1].toInt() == expected.dayOfMonth &&
                months.indexOf(match.groupValues[2].lowercase(Locale.ROOT)) + 1 == expected.monthValue
        }
    }

    internal fun displayRow(raw: String): String? {
        val row = timed.matchEntire(raw) ?: return null
        val title = row.groupValues[2].substringBefore("||").trim()
        if (title.isBlank()) return null
        // Details retain their source authority; only the display title omits them.
        return row.groupValues[1] + " | " + title.replace(Regex("([.,;!?])(?=[А-ЯЁ])"), "$1 ")
    }

    private fun eligible(element: Element): Boolean = element.closest(FOREIGN) == null &&
        (listOf(element) + element.parents()).none { hiddenStyle.containsMatchIn(it.attr("style")) } &&
        (element.tagName() != "script" || element.parents().none(ScheduleHtmlNavigation::isProvenMenuContainer))

    /** Called on the extractor's clone, before scripts are removed. Keys are exact DOM objects. */
    fun extract(document: Document, today: LocalDate): Map<Element, List<ScheduleDocumentLine>> {
        val candidates = document.select("script[type=text/plain]").filter(::eligible).filter { script ->
            script.data().let { data -> data.length <= 100_000 &&
                data.lineSequence().take(1_000).any { potentialHeader.containsMatchIn(it.trim()) } }
        }
        if (candidates.isEmpty()) return emptyMap()
        val month = YearMonth.from(today)
        val result = linkedMapOf<Element, List<ScheduleDocumentLine>>()
        // Another data grammar beside this one cannot silently disappear behind an earlier fast path.
        val competing = candidates.size != 1 || document.select("script[type=text/plain]").filter(::eligible).any {
            it.data().trimStart().startsWith("===")
        }
        for (script in candidates.take(5)) {
            val data = script.data()
            val contextAllowed = contextAllows(document, script, month)
            // Explicit archive/foreign month evidence grants no guessed current-year negative dates.
            if (!contextAllowed || hasUnqualifiedYear(data) || data.lineSequence().map(String::trim).mapNotNull { header.matchEntire(it) }
                    .any { months.indexOf(it.groupValues[3].lowercase(Locale.ROOT)) + 1 != month.monthValue }) continue
            val outerNegative = negativeWords.containsMatchIn(visibleScope(document, script))
            val parsed = if (!competing && !outerNegative) days(data, month) else null
            if (parsed == null) {
                if (data.length <= 100_000 && (outerNegative || negativeWords.containsMatchIn(data) || ScheduleWeekdayTable.cancellation(data))) {
                    val raw = if (outerNegative) data + "\n" + visibleScope(document, script) else data
                    val proof = ScheduleYearlessCalendarLine(key(data), month, 1, "", emptyList(), raw, 0,
                        ScheduleYearlessCalendarLine.Kind.UNRESOLVED)
                    result[script] = listOf(ScheduleDocumentLine(raw, htmlYearlessCalendar = proof))
                }
                continue
            }
            val lines = mutableListOf<ScheduleDocumentLine>()
            val key = key(data)
            var index = 0
            for (day in parsed) {
                val date = month.atDay(day.number)
                // Qualification checked every day before emitting only the established import window.
                if (date !in today.minusDays(1)..today.plusDays(45)) continue
                lines += ScheduleDocumentLine("", startsDayContainer = true)
                fun append(raw: String, kind: ScheduleYearlessCalendarLine.Kind, ambiguous: Boolean = false) {
                    val evidence = ScheduleYearlessCalendarLine(key, month, day.number, day.header, day.feasts.toList(), raw, index++, kind, ambiguous)
                    lines += ScheduleDocumentLine(raw, serviceText = evidence.displayText(),
                        venueEvidence = if (kind in setOf(ScheduleYearlessCalendarLine.Kind.ROW, ScheduleYearlessCalendarLine.Kind.NEGATIVE)) evidence.safetyText() else null,
                        htmlScopeText = raw.takeIf { it.length <= 2_000 }, htmlYearlessCalendar = evidence)
                }
                append(day.header, ScheduleYearlessCalendarLine.Kind.HEADER)
                day.feasts.forEach { append(it, ScheduleYearlessCalendarLine.Kind.FEAST) }
                val hasNegative = day.entries.any { negativeWords.containsMatchIn(it) }
                for (entry in day.entries) {
                    if (negativeWords.containsMatchIn(entry)) append(entry, ScheduleYearlessCalendarLine.Kind.NEGATIVE,
                        ambiguous = !negative.matches(entry) || day.entries.size != 1 || day.feasts.any { ScheduleVenue.hasSharedScopeRestriction(it) })
                    else append(entry, ScheduleYearlessCalendarLine.Kind.ROW)
                }
                // A negative must never disappear simply because its text resembles a feast.
                if (!hasNegative && day.feasts.any { negativeWords.containsMatchIn(it) })
                    append(day.feasts.first { negativeWords.containsMatchIn(it) }, ScheduleYearlessCalendarLine.Kind.NEGATIVE, ambiguous = true)
                lines += ScheduleDocumentLine("", endsDayContainer = true)
            }
            result[script] = lines
        }
        return result
    }

    private fun days(data: String, month: YearMonth): List<Day>? {
        if (data.length !in 1..100_000 || unsafe(data) || '<' in data || '>' in data) return null
        val lines = data.lines().map { it.replace('\u00a0', ' ').trim() }.filter(String::isNotBlank)
        if (lines.size > 1_000 || lines.any { it.length > 2_000 }) return null
        val days = mutableListOf<Day>()
        var current: Day? = null
        for (line in lines) {
            if (potentialHeader.containsMatchIn(line)) {
                val date = headerDate(line, month) ?: return null
                if (date.dayOfMonth != days.size + 1) return null
                current = Day(line, date.dayOfMonth).also(days::add)
                continue
            }
            val day = current ?: return null
            if (timed.containsMatchIn(line) || negativeWords.containsMatchIn(line)) day.entries += line
            else {
                if (day.entries.isNotEmpty()) return null
                // The only historical years exception belongs to this complete calendar memory field.
                val masked = line.replace(historicalCouncil) { " ".repeat(it.value.length) }
                if (anyYear.containsMatchIn(masked) || shortYear.containsMatchIn(masked) || ScheduleClocks.pattern.containsMatchIn(line)) return null
                day.feasts += line
            }
            if ((timed.containsMatchIn(line) || negativeWords.containsMatchIn(line)) &&
                (anyYear.containsMatchIn(line) || shortYear.containsMatchIn(line))) return null
        }
        if (days.size != month.lengthOfMonth() || days.any { it.entries.isEmpty() }) return null
        return days
    }

    private fun hasUnqualifiedYear(data: String): Boolean {
        var entries = false
        for (raw in data.lineSequence()) {
            val line = raw.trim()
            if (potentialHeader.containsMatchIn(line)) entries = false
            val isEntry = timed.containsMatchIn(line) || negativeWords.containsMatchIn(line)
            val relevant = if (!entries && !isEntry && !potentialHeader.containsMatchIn(line))
                line.replace(historicalCouncil) { " ".repeat(it.value.length) } else line
            if (anyYear.containsMatchIn(relevant) || shortYear.containsMatchIn(relevant)) return true
            if (isEntry) entries = true
        }
        return false
    }

    private fun contextAllows(document: Document, script: Element, month: YearMonth): Boolean {
        val location = runCatching {
            val uri = URI(document.baseUri())
            URLDecoder.decode(uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty(), "UTF-8")
        }.getOrNull() ?: return false
        if (location.length > 4_000 || unsafe(location) || archive.containsMatchIn(location) ||
            anyYear.findAll(location).any { it.value.toInt() != month.year }) return false
        val owner = script.closest("article,.hentry,main,[role=main]") ?: document.body()
        val visible = visibleScope(document, script)
        val title = document.title()
        if (title.length > 2_000 || visible.length > 30_000 || unsafe(title) || unsafe(visible)) return false
        val activeHeadings = governingHeadings(document, script)
        if (activeHeadings.any { it.length > 2_000 || unsafe(it) }) return false
        val context = title + " " + visible + " " + activeHeadings.joinToString(" ")
        if (!Regex("(?iu)(расписан|богослужен)").containsMatchIn(title) &&
            owner.select("h1,h2,h3,h4").none { Regex("(?iu)(расписан|богослужен)").containsMatchIn(it.text()) }) return false
        if (anyYear.findAll(context).any { it.value.toInt() != month.year } || shortYear.containsMatchIn(context)) return false
        if (archive.containsMatchIn(context)) return false
        // Do not expand an entire month beneath an ordinary dated/time-qualified cancellation.
        // Its untouched HTML remains for the existing parser with its original narrower scope.
        if (ScheduleCancellation.containsMatchIn(visible)) return false
        val headings = (owner.select("h1,h2,h3,h4").filter(::eligible).filterNot(ScheduleHtmlNavigation::isPureMenuLabel)
            .map { it.wholeText() } + activeHeadings).joinToString(" ")
        if (ScheduleClocks.pattern.containsMatchIn(headings) || Regex("(?iu)(?<!\\d)\\d{1,2}\\s+(?:" + months.joinToString("|") + ")(?![а-яё])").containsMatchIn(headings)) return false
        return monthWord.findAll(location + " " + title + " " + headings + " " + visible).all { match ->
            monthRoots.indexOfFirst { match.value.startsWith(it, true) } + 1 == month.monthValue
        }
    }

    /** Mirrors the headings that the ordinary HTML walk leaves active at this exact script. */
    private fun governingHeadings(document: Document, script: Element): List<String> {
        val root = document.selectFirst("main,[role=main]") ?: document.select("#content").singleOrNull()
            ?.takeIf { it.select("h1").size == 1 } ?: document.body()
        val active = sortedMapOf<Int, String>()
        fun heading(element: Element) {
            if (!element.tagName().matches(Regex("h[1-6]")) || !eligible(element) || ScheduleHtmlNavigation.isPureMenuLabel(element)) return
            val level = element.tagName().drop(1).toInt()
            active.keys.filter { it >= level }.toList().forEach(active::remove)
            active[level] = element.wholeText()
        }
        if (root != document.body()) document.select("h1").filter { it !in root.getAllElements() && eligible(it) }.take(2).forEach(::heading)
        for (element in root.getAllElements()) {
            if (element === script) break
            heading(element)
        }
        return active.values.toList()
    }

    private fun visibleScope(document: Document, script: Element): String {
        val owner = script.closest("article,.hentry,main,[role=main]") ?: document.body()
        return owner.clone().also { copy ->
            copy.select("script,style,nav,[role=navigation],footer,header,aside,blockquote,q,[hidden],[aria-hidden=true],.sidebar").remove()
            ScheduleHtmlDiscussion.removeFrom(Document("").also { it.appendChild(copy) })
            // Only context qualification ignores a proven neutral navigation label. Source lines keep it.
            copy.getAllElements().filter(ScheduleHtmlNavigation::isPureMenuLabel).forEach { it.remove() }
            copy.getAllElements().flatMap { it.textNodes() }.filter { node ->
                val match = Regex("^©\\s*(?:19|20|21)\\d{2}(?:\\s*[-–—]\\s*(?:19|20|21)\\d{2})?\\s*(.*)$").matchEntire(node.wholeText.trim())
                val name = match?.groupValues?.get(1)
                name != null && name.none(Char::isDigit) && ScheduleHtmlNavigation.neutral(name.ifBlank { "©" })
            }.forEach { it.remove() }
        }.text()
    }

    private fun key(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
