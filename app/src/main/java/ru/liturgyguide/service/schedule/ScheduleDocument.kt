package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.math.abs
import kotlin.math.max

/** Word geometry survives OCR so date and time columns are joined by position, not output order. */
data class ScheduleWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val confidence: Int = 100)
data class ScheduleDocumentLine(val text: String, val confidence: Int = 100, val struck: Boolean = false,
    val temporalConfidence: Int = 100, val headingLevel: Int = 0, val venue: String? = null, val endsTable: Boolean = false,
    val startsDayContainer: Boolean = false, val endsDayContainer: Boolean = false,
    // Optional display-title material. Full text above still governs dates and cancellation.
    val serviceText: String? = null,
    // Only complete known calendar names reconstructed in a proven column may be removed.
    val venueEvidence: String? = null)
enum class ScheduleOcrFailure { UNSUPPORTED_LAYOUT, CONFLICTING_EVIDENCE }
data class ScheduleDocumentPage(val lines: List<ScheduleDocumentLine>,
    // Audit-only text from every completed pass, including an unsuccessful repair. Never
    // parse it as another page of events or silently discard conflicting safety evidence.
    val rawOcrEvidence: List<String>? = null,
    val ocrFailure: ScheduleOcrFailure? = null)
data class ScheduleDocument(val sourceUrl: String, val format: String, val pages: List<ScheduleDocumentPage>)
enum class ScheduleParseDisposition { PARSED, UNSUPPORTED, REJECTED }
data class ScheduleParseResult(
    val disposition: ScheduleParseDisposition,
    val events: List<InternetService> = emptyList(),
    val withdrawnDates: Set<String> = emptySet(),
    val withdrawnStarts: Set<String> = emptySet(),
    val withdrawsAll: Boolean = false,
    val yearInferred: Boolean = false,
    // Scope could not be proved. Stop this source; this is not a claim of a global withdrawal.
    val safetyConflict: Boolean = false
)

fun interface ScheduleDocumentExtractor {
    suspend fun extract(response: ScheduleResponse): ScheduleDocument?
}

object HtmlScheduleDocumentExtractor : ScheduleDocumentExtractor {
    override suspend fun extract(response: ScheduleResponse): ScheduleDocument? = response.html()?.let(::fromHtml)

    fun fromHtml(document: Document): ScheduleDocument {
        val copy = document.clone()
        copy.select("script,style,nav,footer,aside,header,[hidden],.sidebar,.comments,.comment-list").remove()
        // Sibling headings may identify the building for each of several articles. Selecting only
        // the first article would lose that scope and silently discard the selected church's section.
        // Older parish sites use a named content div instead of semantic HTML. Only select
        // it when it contains the page heading, so a nested widget cannot hide parish scope.
        val body = copy.selectFirst("main,[role=main]") ?: copy.select("#content").singleOrNull()
            ?.takeIf { it.select("h1").size == 1 } ?: copy.body()
        val lines = mutableListOf<ScheduleDocumentLine>()
        // An article's date heading may be its sibling. Only headings, never copyright/sidebar years.
        if (body != copy.body()) copy.select("h1").filter { it !in body.getAllElements() }.take(2)
            .forEach { lines += ScheduleDocumentLine(it.text(), headingLevel = 1) }
        fun plain(element: Element): List<ScheduleDocumentLine> {
            val clone = element.clone()
            // Known ISO calendar cards render the celebrant separately. Keep every word,
            // including cancellation notices, without joining it to the service name.
            if (element.closest("[data-day]") != null) clone.select(".who").forEach {
                it.before(" "); it.after(" ")
            }
            clone.select("br").forEach { it.before("\n") }
            return clone.wholeText().lines().map { it.replace(Regex("[\\t ]+"), " ").trim() }
                .filter(String::isNotBlank).map { ScheduleDocumentLine(it, struck = element.select("s,del,strike").isNotEmpty(),
                    headingLevel = element.tagName().takeIf { tag -> tag.matches(Regex("h[1-6]")) }?.drop(1)?.toInt() ?: 0) }
        }
        fun table(element: Element) {
            data class Span(val value: String, var remaining: Int)
            val spans = mutableMapOf<Int, Span>()
            element.select("tr").filter { it.closest("table") == element }.take(600).forEach { row ->
                val cells = sortedMapOf<Int, String>()
                val old = spans.toMap()
                old.forEach { (column, span) ->
                    cells[column] = span.value
                    span.remaining--
                    if (span.remaining == 0) spans.remove(column)
                }
                var column = 0
                row.children().filter { it.tagName() in setOf("td", "th") }.forEach { cell ->
                    while (column in cells) column++
                    val text = plain(cell).joinToString("\n") { it.text }
                    val columns = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 20)
                    val rows = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 100)
                    repeat(columns) {
                        cells[column] = if (it == 0) text else ""
                        if (rows > 1) spans[column] = Span(cells.getValue(column), rows - 1)
                        column++
                    }
                }
                // Match parallel subrows while the column structure still exists. Splitting the
                // concatenated text afterwards would pair the second clock with the first title.
                val groups = cells.values.filter(String::isNotBlank).map { value ->
                    val parts = value.lines().filter(String::isNotBlank)
                    val joined = parts.joinToString(" ")
                    // A date with its weekday wraps inside one cell. It is not a list of clocks.
                    if (dateCell.matches(joined.trim())) listOf(joined) else parts
                }
                if (groups.isNotEmpty()) {
                    val multiple = groups.filter { it.size > 1 }
                    val widths = multiple.map { it.size }.distinct()
                    val split = widths.size == 1 &&
                        multiple.any { column -> column.all { clockCell.matches(it.trim()) } }
                    val physicalRows = if (split) (0 until widths.single()).map { index ->
                        groups.map { column -> if (column.size == 1) column.single() else column[index] }
                    } else if (multiple.count { column -> column.any { clockCell.containsMatchIn(it) } } > 0 && multiple.size > 1) {
                        // Different line counts do not tell us which title belongs to which time.
                        emptyList()
                    } else listOf(groups.map { it.joinToString("\n") })
                    physicalRows.forEach { values ->
                        val places = values.filter(ScheduleVenue::isLabel)
                        val place = places.distinct().singleOrNull()
                        if (places.distinct().size <= 1) {
                            val text = values.filterNot(ScheduleVenue::isLabel).joinToString(" | ")
                            lines += if (text.isBlank() && place != null) ScheduleDocumentLine(place, headingLevel = 2)
                                else ScheduleDocumentLine(text, struck = row.select("s,del,strike").isNotEmpty(), venue = place)
                        }
                    }
                }
            }
            // A general note after the table must not inherit the last row's date.
            // Explicit outer date/building headings are handled by the parser separately.
            lines += ScheduleDocumentLine("", endsTable = true)
        }
        fun walk(element: Element) {
            if (element.hasClass("sch-day-item")) {
                lines += ScheduleCalendarCards.extract(element, document.baseUri())
                return
            }
            val dayContainer = element.hasAttr("data-day")
            if (dayContainer) lines += ScheduleDocumentLine(element.attr("data-day"), startsDayContainer = true)
            when (element.tagName()) {
                "table" -> table(element)
                "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "pre" -> lines.addAll(plain(element))
                else -> {
                    val own = StringBuilder()
                    fun flush() { if (own.isNotBlank()) { lines += ScheduleDocumentLine(own.toString().trim()); own.clear() } }
                    element.childNodes().forEach { node ->
                        when (node) {
                            is TextNode -> own.append(node.text())
                            is Element -> if (node.tagName() == "br") flush() else { flush(); walk(node) }
                        }
                    }
                    flush()
                }
            }
            if (dayContainer) lines += ScheduleDocumentLine("", endsDayContainer = true)
        }
        walk(body)
        return ScheduleDocument(document.baseUri(), "html", listOf(ScheduleDocumentPage(lines.take(2_000))))
    }

    private val clockCell = ScheduleClocks.pattern
    private val monthWord = "(?:январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])"
    private val dateCell = Regex("(?iu)^\\d{1,2}(?:\\s*[./]\\s*\\d{1,2}(?:[./](?:20\\d{2}|\\d{2}))?|\\s+$monthWord(?:\\s+20\\d{2})?)\\s*,?\\s*(?:(?:пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?)?$")
}

object ScheduleOcrLayout {
    private val box = Regex("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
    private val confidence = Regex("x_wconf\\s+(\\d+)")

    fun fromHocr(hocr: String): List<ScheduleDocumentLine> {
        val words = Jsoup.parse(hocr).select(".ocrx_word,.ocr_word").mapNotNull { word ->
            val b = box.find(word.attr("title")) ?: return@mapNotNull null
            val v = b.groupValues.drop(1).map(String::toInt)
            val text = word.text().trim()
            if (text.isBlank() || v[2] <= v[0] || v[3] <= v[1]) return@mapNotNull null
            ScheduleWord(text, v[0], v[1], v[2], v[3],
                confidence.find(word.attr("title"))?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }
        return lines(words)
    }

    fun lines(words: List<ScheduleWord>): List<ScheduleDocumentLine> {
        data class Row(val members: MutableList<ScheduleWord>) {
            val middle: Double get() = members.map { (it.top + it.bottom) / 2.0 }.average()
            val height: Double get() = members.map { it.bottom - it.top }.sorted().let { it[it.size / 2].toDouble() }
        }
        val rows = mutableListOf<Row>()
        words.sortedBy { it.top + it.bottom }.forEach { word ->
            val middle = (word.top + word.bottom) / 2.0
            val row = rows.lastOrNull { abs(it.middle - middle) <= max(it.height, (word.bottom - word.top).toDouble()) * .48 }
            if (row == null) rows += Row(mutableListOf(word)) else row.members += word
        }
        val orderedRows = rows.sortedBy { it.middle }.map { it.members.sortedBy { word -> word.left } }
        val serviceColumn = ScheduleOcrServiceColumn.texts(orderedRows)
        return orderedRows.mapIndexed { index, ordered ->
            ScheduleDocumentLine(ordered.joinToString(" ") { it.text }, ordered.map { it.confidence }.average().toInt(),
                temporalConfidence = ordered.filter { it.text.any(Char::isDigit) }.minOfOrNull { it.confidence } ?: 100,
                serviceText = serviceColumn[index]?.service, venueEvidence = serviceColumn[index]?.venueEvidence)
        }
    }
}
