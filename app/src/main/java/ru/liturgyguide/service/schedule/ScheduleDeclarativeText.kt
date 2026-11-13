package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.util.Locale

/** Data, not executable code: fully dated day headers followed by pipe-separated service rows. */
internal object ScheduleDeclarativeText {
    data class Prepared(val document: Document, val serviceTitles: Map<String, String>, val conflict: Boolean = false)

    private val header = Regex("^===\\s*(.+?)\\s*\\|\\s*(20\\d{2}-\\d{2}-\\d{2})\\s*\\|\\s*(.*)$")
    private val printedDate = Regex("(?iu)(?<!\\d)(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+[^\\d]*(20\\d{2})(?!\\d)")
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val weekday = Regex("(?iu)\\b(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\b")
    private val row = Regex("^\\d{1,2}:\\d{2}\\s*\\|")

    fun prepare(source: Document, today: LocalDate): Prepared? {
        val scripts = source.select("script[type=text/plain]").filterNot(ScheduleHtmlDiscussion::contains).filter {
            it.data().trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("===")
        }
        if (scripts.isEmpty()) return null
        if (scripts.size > 4) return Prepared(source, emptyMap(), conflict = true)
        val document = source.clone()
        val hints = mutableMapOf<String, String>()
        // All surrounding HTML stays at its original position: an outer cancellation or
        // building heading must govern the expanded rows just as it governs visible HTML.
        val copies = document.select("script[type=text/plain]").filterNot(ScheduleHtmlDiscussion::contains).filter {
            it.data().trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("===")
        }
        for (script in copies) {
            val text = script.data().removePrefix("\uFEFF")
            if (text.length > 600_000) return Prepared(source, emptyMap(), conflict = true)
            val lines = text.lines()
            if (lines.size > 10_000) return Prepared(source, emptyMap(), conflict = true)
            val expanded = Element("section")
            var day: Element? = null
            var haveHeader = false
            var blocks = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.length > 2_000) return Prepared(source, emptyMap(), conflict = true)
                if (line.startsWith("===")) {
                    val fields = header.matchEntire(line) ?: return Prepared(source, emptyMap(), conflict = true)
                    val date = runCatching { LocalDate.parse(fields.groupValues[2]) }.getOrNull()
                        ?: return Prepared(source, emptyMap(), conflict = true)
                    // Two printed representations are corroboration, never permission to
                    // choose whichever date happens to fit the requested day.
                    if (!agrees(fields.groupValues[1], date) || ++blocks > 512)
                        return Prepared(source, emptyMap(), conflict = true)
                    haveHeader = true
                    day = if (date in today.minusDays(1)..today.plusDays(45)) {
                        expanded.appendElement("div").attr("data-day", date.toString()).also {
                            it.appendElement("h4").text(fields.groupValues[1] + " | " + fields.groupValues[2] + " | " + fields.groupValues[3])
                        }
                    } else null
                } else {
                    if (!haveHeader) return Prepared(source, emptyMap(), conflict = true)
                    val container = day ?: continue
                    container.appendElement("p").text(line)
                    if (row.containsMatchIn(line)) {
                        val fields = line.split('|')
                        // Keep the entire physical row for date/venue/cancellation guards.
                        // Only its display title may omit the separately printed celebrant.
                        if (fields.size >= 2 && fields[1].isNotBlank()) {
                            val title = fields.take(2).joinToString(" | ") { it.trim() }
                            val key = normalizedLine(line)
                            val previous = hints.put(key, title)
                            if (previous != null && previous != title) return Prepared(source, emptyMap(), conflict = true)
                        }
                    }
                }
            }
            if (!haveHeader) return Prepared(source, emptyMap(), conflict = true)
            script.replaceWith(expanded)
        }
        return Prepared(document, hints)
    }

    fun withDisplayTitles(document: ScheduleDocument, titles: Map<String, String>): ScheduleDocument = document.copy(
        pages = document.pages.map { page -> page.copy(lines = page.lines.map { line ->
            titles[normalizedLine(line.text)]?.let { line.copy(serviceText = it) } ?: line
        }) }
    )

    private fun agrees(text: String, expected: LocalDate): Boolean {
        val printed = printedDate.findAll(text).toList().singleOrNull() ?: return false
        val month = months.indexOf(printed.groupValues[2].lowercase(Locale.ROOT)) + 1
        val stated = runCatching { LocalDate.of(printed.groupValues[3].toInt(), month, printed.groupValues[1].toInt()) }.getOrNull()
        if (stated != expected) return false
        val days = weekday.findAll(text).toList()
        return days.size <= 1 && days.all { ScheduleWeekdays.agrees(it.value, expected) }
    }

    private fun normalizedLine(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
}
