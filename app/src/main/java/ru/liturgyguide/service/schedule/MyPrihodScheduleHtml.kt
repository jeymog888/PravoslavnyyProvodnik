package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** The platform prints a selected month and parallel service/clergy columns, not a generic prose table. */
internal object MyPrihodScheduleHtml {
    data class Prepared(val tables: Map<Element, List<ScheduleDocumentLine>>, val conflict: Boolean = false)
    private val tenant = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.myprihod\\.ru")
    private val months = listOf("январь|января", "февраль|февраля", "март|марта", "апрель|апреля", "май|мая", "июнь|июня",
        "июль|июля", "август|августа", "сентябрь|сентября", "октябрь|октября", "ноябрь|ноября", "декабрь|декабря")
    private val monthYear = Regex("(?iu)^([а-яё]+)\\s+(20\\d{2})$")
    private val printedDay = Regex("(?iu)^(\\d{1,2})\\s+([а-яё]+)(?:\\s+(20\\d{2}))?\\s*,?\\s+(${ScheduleWeekdays.dateWords})\\.?$")
    private val namedService = Regex("(?iu)(богослужен|служб[ауыое]|литург|вечерн|утрен|всенощ|молеб|панихид|акафист|исповед|часы)")

    fun appliesTo(document: Document): Boolean = runCatching {
        val source = URI(document.baseUri())
        source.scheme == "https" && tenant.matches(source.host.orEmpty().lowercase(Locale.ROOT)) &&
            document.select("link[href]").any { link ->
                val url = link.absUrl("href")
                ScheduleUrl.sameOrigin(document.baseUri(), url) && URI(url).path == "/multisite/church/css/schedule.css"
            } && document.select("div.content-block.schedule > table").isNotEmpty()
    }.getOrDefault(false)

    /** Mutates only validated calendar widgets in the caller's private clone. Outer venue/notices stay intact. */
    fun prepare(document: Document): Prepared? {
        if (!appliesTo(document)) return null
        val result = linkedMapOf<Element, List<ScheduleDocumentLine>>()
        val selections = mutableListOf<Pair<Element, Element>>()
        val tables = document.select("div.content-block.schedule > table")
        if (tables.size !in 1..4) return Prepared(emptyMap(), conflict = true)
        for (table in tables) {
            val container = table.parent() ?: return Prepared(emptyMap(), true)
            val header = container.previousElementSibling()?.takeIf { it.hasClass("content-title") }
                ?: return Prepared(emptyMap(), true)
            if (header.select("h1.active").singleOrNull()?.text()?.trim() != "Расписание") return Prepared(emptyMap(), true)
            val select = header.select("select.select-title-style").singleOrNull() ?: return Prepared(emptyMap(), true)
            val option = select.select("option[selected]").singleOrNull() ?: return Prepared(emptyMap(), true)
            val month = selectedMonth(document.baseUri(), option) ?: return Prepared(emptyMap(), true)
            val lines = tableLines(table, month) ?: return Prepared(emptyMap(), true)
            result[table] = lines
            selections += select to option
        }
        // Other archive months are choices, not headings for the displayed table's dates.
        for ((select, selected) in selections) select.select("option").filter { it !== selected }.forEach(Element::remove)
        return Prepared(result)
    }

    private fun selectedMonth(source: String, option: Element): YearMonth? = runCatching {
        val printed = requireNotNull(monthYear.matchEntire(clean(option.text())))
        val number = monthNumber(printed.groupValues[1])
        require(number in 1..12)
        val month = YearMonth.of(printed.groupValues[2].toInt(), number)
        val selectedUrl = option.absUrl("value")
        require(ScheduleUrl.sameOrigin(source, selectedUrl) && URI(selectedUrl).path == URI(source).path)
        require(currentMonth(selectedUrl) == month.toString())
        val requested = currentMonth(source)
        require(requested == null || requested == month.toString())
        month
    }.getOrNull()

    private fun currentMonth(url: String): String? {
        val values = URI(url).rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.filter { it.first == "current_date" }
        require(values.size <= 1)
        return values.singleOrNull()?.second?.also { require(it.matches(Regex("20\\d{2}-(?:0[1-9]|1[0-2])"))) }
    }

    private fun tableLines(table: Element, month: YearMonth): List<ScheduleDocumentLine>? = runCatching {
        val rows = table.select("tr").filter { it.closest("table") === table }
        require(rows.size in 1..31)
        val lines = mutableListOf<ScheduleDocumentLine>()
        var previousDay = 0
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            require(cells.size == 4 && cells.all { it.attr("rowspan").let { value -> value.isBlank() || value == "1" } &&
                it.attr("colspan").let { value -> value.isBlank() || value == "1" } })
            val printed = requireNotNull(printedDay.matchEntire(clean(cells[0].text())))
            val day = printed.groupValues[1].toInt()
            val printedMonth = monthNumber(printed.groupValues[2])
            require(printedMonth == month.monthValue && (printed.groupValues[3].isEmpty() || printed.groupValues[3].toInt() == month.year))
            val date = month.atDay(day)
            require(day > previousDay && ScheduleWeekdays.agrees(printed.groupValues[4], date))
            previousDay = day
            val services = listedEntries(cells[1])
            val clergyEntries = listedEntries(cells[2])
            val notesEntries = listedEntries(cells[3])
            require(services.size <= 20)
            val clergyScoped = clergyEntries.size == services.size
            val notesScoped = notesEntries.size == services.size
            val allClergy = clean(cells[2].text())
            val allNotes = clean(cells[3].text())
            // A notice without a unique service row cannot be silently attached to every time.
            require(notesScoped || !ScheduleCancellation.containsMatchIn(allNotes))
            require(clergyScoped || !(ScheduleCancellation.containsMatchIn(allClergy) && namedService.containsMatchIn(allClergy)))
            lines += ScheduleDocumentLine(date.toString(), startsDayContainer = true)
            for ((index, entry) in services.withIndex()) {
                val clockNode = entry.select("span.time").singleOrNull() ?: error("Ambiguous service clock")
                val clock = clean(clockNode.text())
                val time = normalizedClock(requireNotNull(ScheduleClocks.pattern.matchEntire(clock)))
                val title = clean(entry.clone().apply { select("span.time").remove() }.text())
                require(title.isNotBlank() && !ScheduleClocks.pattern.containsMatchIn(title))
                val clergy = if (clergyScoped) clean(clergyEntries[index].text()) else allClergy
                val notes = if (notesScoped) clean(notesEntries[index].text()) else allNotes
                // A row's notes belong to that service even when they only say "Отменено".
                // A replacement celebrant still needs an explicit service withdrawal.
                val withdrawal = listOfNotNull(
                    clergy.takeIf { ScheduleCancellation.containsMatchIn(it) && namedService.containsMatchIn(it) },
                    notes.takeIf { notesScoped && ScheduleCancellation.containsMatchIn(it) }
                ).joinToString(" ")
                val noticeClocks = ScheduleClocks.pattern.findAll(withdrawal).map(::normalizedClock).toList()
                require(noticeClocks.all { it == time })
                val withdrawalWithoutClock = ScheduleClocks.pattern.replace(withdrawal, " ")
                val struck = entry.select("s,del,strike").isNotEmpty() || cells[0].select("s,del,strike").isNotEmpty() ||
                    row.parents().any { it.tagName() in setOf("s", "del", "strike") }
                val strikeNotice = if (struck) "богослужение отменено" else ""
                val serviceText = "$date $time $title"
                lines += ScheduleDocumentLine("$serviceText $withdrawalWithoutClock $strikeNotice".trim(),
                    serviceText = serviceText, venueEvidence = "$date $time $title $clergy $notes")
            }
            lines += ScheduleDocumentLine("", endsDayContainer = true)
        }
        lines += ScheduleDocumentLine("", endsTable = true)
        lines
    }.getOrNull()

    private fun normalizedClock(match: MatchResult) = LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString()

    private fun listedEntries(cell: Element): List<Element> {
        val entries = cell.select("li").filter { it.closest("td") === cell }
        require(entries.all { entry -> entry.select("li").none { it !== entry } })
        // Extra text outside the proven list might be a cancellation or another building.
        // The known widget is unsafe if we cannot associate it; do not drop it as decoration.
        require(cell.clone().apply { select("li").remove() }.text().isBlank())
        return entries
    }

    private fun clean(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun monthNumber(value: String) = months.indexOfFirst { value.lowercase(Locale.ROOT) in it.split('|') } + 1
}
