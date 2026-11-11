package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale
import ru.liturgyguide.model.Parish

data class InternetService(val date: String, val time: String, val title: String, val kind: String)

/** Conservative, format-based extraction. No year, clock time, venue or missing service is inferred. */
object ScheduleHtmlParser {
    private val months = listOf("январь|января", "февраль|февраля", "март|марта", "апрель|апреля", "май|мая", "июнь|июня",
        "июль|июля", "август|августа", "сентябрь|сентября", "октябрь|октября", "ноябрь|ноября", "декабрь|декабря")
    private val fullDate = Regex("(?iu)(\\d{1,2})\\s+([а-яё]+)\\s+(20\\d{2})(?:\\s*г(?:ода)?\\.?)?")
    private val monthYear = Regex("(?iu)^\\s*([а-яё]+)\\s+(20\\d{2})(?:\\s*г(?:ода)?\\.?)?\\s*$")
    private val timedLine = Regex("^\\s*(\\d{1,2})\\s+(Пн|Вт|Ср|Чт|Пт|Сб|Вс)\\.?\\s+(\\d{1,2}[.:]\\d{2})\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val weekdayNames = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val serviceWord = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val exactLiturgy = Regex("(?iu)^(?:(?:божественная|ранняя|поздняя)\\s+)*литургия\\b")
    private val differentVenue = Regex("(?iu)(приписн|больнич|госпитал|придел|(?:нижн|верхн|нов|стар|мал|больш)[а-яё]{0,5}\\s+(?:храм|собор)|в\\s+(?:[а-яё-]+\\s+){0,4}(?:храме|соборе|часовне)|служб[аы]\\s+(?:соверш|проход))")
    private val globalVenue = Regex("(?iu)(?:все\\s+(?:богослужения|службы)|богослужения\\s+месяца).{0,160}(?:совершаются|проходят|служатся)")
    private val cancelled = Regex("(?iu)(отмен|перенес|перенос|не\\s+(?:будет|соверша|служит|состоится|провод)|без\\s+(?:литургии|богослужения))")

    fun parse(html: String, sourceUrl: String, today: LocalDate): List<InternetService> =
        parse(Jsoup.parse(html, sourceUrl), today)

    /** Empty extraction is not permission to bypass an explicit cancellation or ambiguity. */
    fun inspect(document: Document, today: LocalDate, parish: Parish? = null): ScheduleParseResult {
        val structured = HtmlScheduleDocumentExtractor.fromHtml(document)
        // A named building or a dated cancellation needs the general parser's section context,
        // rather than the legacy parser's whole-document rejection or context-free row extraction.
        if (structured.pages.any { page -> page.lines.any {
            it.venue != null || ScheduleVenue.isLabel(it.text) || cancelled.containsMatchIn(it.text)
        } }) return ScheduleDocumentParser.inspect(structured, today, parish)
        val events = parse(document, today)
        val known = document.select("[data-day],.bg_ortcal_schedule").isNotEmpty()
        return ScheduleParseResult(if (events.isNotEmpty() || known) ScheduleParseDisposition.PARSED else ScheduleParseDisposition.UNSUPPORTED, events)
    }

    fun parse(document: Document, today: LocalDate): List<InternetService> {
        if (globalVenue.containsMatchIn(document.text()) || document.select("h1,h2,h3").any {
                differentVenue.containsMatchIn(it.text()) || cancelled.containsMatchIn(it.text()) }) return emptyList()
        val found = mutableListOf<InternetService>()
        // ISO day containers (e.g. hramkresta.ru). Nested date labels are not another day.
        document.select("[data-day]").take(120).forEach { day ->
            val date = runCatching { LocalDate.parse(day.attr("data-day")) }.getOrNull() ?: return@forEach
            if (differentVenue.containsMatchIn(day.text()) || cancelled.containsMatchIn(day.text()) || day.select("s, del, strike").isNotEmpty()) return@forEach
            day.select("li").take(30).forEach { row ->
                val clock = row.selectFirst("time")?.text().orEmpty()
                val title = row.selectFirst("span:not(.who)")?.text().orEmpty()
                event(date, clock, title, today)?.let(found::add)
            }
        }
        // Shared WordPress bg-orthodox-calendar: civil date only, never .bg_ortcal_old.
        val calendars = document.select(".bg_ortcal_schedule")
        // Multiple calendars may belong to different buildings on one monastery site.
        val tables = if (calendars.size == 1 && !differentVenue.containsMatchIn(calendars.single().text()) &&
            !document.select("h1, h2, h3").any { differentVenue.containsMatchIn(it.text()) }) calendars.single().select("table") else emptyList()
        tables.take(12).forEach { table ->
            var date: LocalDate? = null
            table.select("tr").take(500).forEach { row ->
                val heading = row.selectFirst(".bg_ortcal_date")
                if (heading != null || row.selectFirst(".bg_ortcal_day") != null) {
                    date = heading?.text()?.takeUnless { cancelled.containsMatchIn(row.text()) }?.let(::civilDate)
                } else {
                    val day = date ?: return@forEach
                    if (row.select("s, del, strike").isNotEmpty()) return@forEach
                    val title = row.selectFirst(".bg_ortcal_event")?.text().orEmpty()
                    event(day, row.selectFirst(".bg_ortcal_time")?.text().orEmpty(), title, today)?.let(found::add)
                }
            }
        }
        // A self-contained monthly WordPress article with an explicit month AND year.
        // A copyright year, sidebar calendar or page URL is deliberately insufficient.
        document.select(".hentry, article").take(20).forEach { article ->
            val heading = article.selectFirst("h1.entry-title, h1") ?: return@forEach
            val match = monthYear.matchEntire(heading.text()) ?: return@forEach
            val month = monthNumber(match.groupValues[1]) ?: return@forEach
            val yearMonth = runCatching { YearMonth.of(match.groupValues[2].toInt(), month) }.getOrNull() ?: return@forEach
            val body = article.selectFirst(".entry-content") ?: return@forEach
            if (differentVenue.containsMatchIn(body.text())) return@forEach
            body.select("p").take(100).forEach { paragraph ->
                if (paragraph.select("s, del, strike").isNotEmpty()) return@forEach
                val copy = paragraph.clone()
                copy.select("br").forEach { it.before("\n") }
                copy.wholeText().lines().forEach { line ->
                    val parts = timedLine.matchEntire(line.trim()) ?: return@forEach
                    val date = runCatching { yearMonth.atDay(parts.groupValues[1].toInt()) }.getOrNull() ?: return@forEach
                    if (weekdays.indexOf(parts.groupValues[2].lowercase(Locale.ROOT)) + 1 != date.dayOfWeek.value) return@forEach
                    event(date, parts.groupValues[3], parts.groupValues[4], today)?.let(found::add)
                }
            }
        }
        return found.distinctBy { listOf(it.date, it.time, it.title) }
            .sortedWith(compareBy<InternetService> { it.date }.thenBy { it.time }).take(200)
    }

    private fun civilDate(text: String): LocalDate? {
        val parts = fullDate.find(text) ?: return null
        val month = monthNumber(parts.groupValues[2]) ?: return null
        val date = runCatching { LocalDate.of(parts.groupValues[3].toInt(), month, parts.groupValues[1].toInt()) }.getOrNull() ?: return null
        val namedDay = weekdayNames.indexOfFirst { text.lowercase(Locale.ROOT).contains(it) }
        return date.takeIf { namedDay < 0 || namedDay + 1 == date.dayOfWeek.value }
    }

    private fun monthNumber(value: String): Int? = months.indexOfFirst { value.lowercase(Locale.ROOT) in it.split('|') }.takeIf { it >= 0 }?.plus(1)

    private fun event(date: LocalDate, clock: String, rawTitle: String, today: LocalDate): InternetService? {
        if (date < today.minusDays(1) || date > today.plusDays(45)) return null
        val match = Regex("^(\\d{1,2})[.:](\\d{2})$").matchEntire(clock.trim()) ?: return null
        val time = runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull() ?: return null
        val title = rawTitle.replace(Regex("\\s+"), " ").trim()
        if (title.length !in 2..300 || title.any(Char::isISOControl) || !serviceWord.containsMatchIn(title) || differentVenue.containsMatchIn(title) || cancelled.containsMatchIn(title)) return null
        // "Часы, Литургия" and "Исповедь и Литургия" are block starts, not a liturgy clock anchor.
        val anotherLiturgy = Regex("(?iu)(преждеосвящ|василия|иакова|якова)").containsMatchIn(title)
        return InternetService(date.toString(), time.toString(), title, if (!anotherLiturgy && exactLiturgy.containsMatchIn(title)) "liturgy" else "service_block")
    }

    fun scheduleLinks(document: Document): List<String> = document.select("a[href]").mapNotNull { link ->
        val url = ScheduleUrl.normalized(link.absUrl("href")) ?: return@mapNotNull null
        if (!ScheduleUrl.sameOrigin(document.baseUri(), url)) return@mapNotNull null
        val label = "${link.text()} ${link.attr("href")}".lowercase(Locale.ROOT)
        if (!(label.contains("расписан") || label.contains("raspisan") || label.contains("schedule"))) return@mapNotNull null
        if (Regex("(?i)\\.(pdf|jpe?g|png|webp)(?:\\?|$)").containsMatchIn(url)) return@mapNotNull null
        url
    }.distinct().take(2)
}
