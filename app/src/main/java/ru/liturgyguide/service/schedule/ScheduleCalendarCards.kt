package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/** Civil-date cards with explicit per-service buildings. No host or device-year inference. */
internal object ScheduleCalendarCards {
    private val idDate = DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT)
    private val linkDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    private val visibleDate = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("EEEE, d MMMM uuuu").toFormatter(Locale.forLanguageTag("ru"))
        .withResolverStyle(ResolverStyle.STRICT)
    private val cancellation = ScheduleCancellation

    private val serviceName = Regex("(?iu)(богослужен|служб[ауыое]|литург|вечерн|утрен|всенощ|молеб|панихид)")

    fun extract(card: Element, sourceUrl: String): List<ScheduleDocumentLine> {
        if (!card.id().matches(Regex("\\d{8}"))) return emptyList()
        val date = runCatching { LocalDate.parse(card.id(), idDate) }.getOrNull() ?: return emptyList()
        val header = card.children().singleOrNull { it.hasClass("sch-day-title") } ?: return emptyList()
        val civil = header.select(".day-new").singleOrNull() ?: return emptyList()
        val printed = runCatching { LocalDate.parse("${civil.text()} ${date.year}", visibleDate) }.getOrNull()
        if (printed != date) return emptyList()
        // A day-card id alone is not publication evidence. Its calendar link must agree
        // with the civil label and be on the same public source, not an external widget.
        val calendar = header.select(".holiday a[href]").singleOrNull() ?: return emptyList()
        val calendarUrl = calendar.absUrl("href")
        if (!ScheduleDocumentLinks.sameSite(sourceUrl, calendarUrl)) return emptyList()
        val published = runCatching {
            val parameters = URI(calendarUrl).rawQuery.orEmpty().split('&').map { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            val value = parameters.filter { it.first == "day" }.single().second
            LocalDate.parse(value, linkDate)
        }.getOrNull()
        if (published != date) return emptyList()

        val lines = mutableListOf(ScheduleDocumentLine(date.toString(), startsDayContainer = true))
        val notices = listOf(header.clone().apply { select(".day-new,.day-old,.holiday").remove() }.text()) +
            header.select(".holiday").map { it.text() }.filter { cancellation.containsMatchIn(it) }
        notices.filter(String::isNotBlank).forEach { lines += ScheduleDocumentLine("$date $it") }
        card.children().filter { !it.hasClass("sch-day-title") && !it.hasClass("sch-day-row") }
            .filter { it.text().isNotBlank() }.forEach { lines += ScheduleDocumentLine("$date ${it.text()}") }
        card.children().filter { it.hasClass("sch-day-row") }.take(30).forEach { row ->
            val clock = row.children().singleOrNull { it.hasClass("time") }?.text() ?: return@forEach
            if (!ScheduleClocks.pattern.matches(clock)) return@forEach
            val place = row.select(".place").singleOrNull() ?: return@forEach
            val column = place.parent()?.takeIf { it in row.children() } ?: return@forEach
            val venue = place.text().takeIf { it.length in 3..300 } ?: return@forEach
            val title = column.clone().apply { select(".place").remove() }.text()
            // A replacement celebrant (including a struck name or "не служит") does
            // not cancel the service. Metadata must explicitly name a service to withdraw it.
            val withdrawal = (listOf(venue) + row.children().filter { it != column && !it.hasClass("time") }
                .map { it.text() }).filter { cancellation.containsMatchIn(it) && serviceName.containsMatchIn(it) }.joinToString(" ")
            val struckService = (listOf(column) + row.children().filter { it.hasClass("time") })
                .any { it.select("s,del,strike").isNotEmpty() }
            // This structured row has an explicit date/time/building. Preserve the withdrawal
            // so a rejected publication cannot revive the same service from an older cache.
            val strikeNotice = if (struckService) "богослужение отменено" else ""
            lines += ScheduleDocumentLine("$date $clock $title $withdrawal $strikeNotice".trim(), venue = venue)
        }
        lines += ScheduleDocumentLine("", endsDayContainer = true)
        return lines
    }
}
