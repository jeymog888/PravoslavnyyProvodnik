package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Transient evidence from complete visible DOM cells, never an HTML data-* attribute. */
data class ScheduleHtmlCalendarRow(
    val rowId: Int,
    val groupId: Int,
    val groupLastRow: Int,
    val heading: String,
    val cells: List<String>,
    val tableKey: String = "",
    val permitted: Boolean = true
) {
    internal fun validFor(raw: String): Boolean = rowId in groupId..groupLastRow && groupId > 0 &&
        Regex("[a-f0-9]{64}").matches(tableKey) && cells.size == 5 && cells.joinToString(" | ") == raw &&
        ScheduleWeekdayTable.month(heading) != null &&
        cells.all { it.length <= 2_000 && !ScheduleWeekdayTable.unsafe(it) }

    internal fun hasDateColumns(): Boolean = cells.size == 5 && ScheduleWeekdayTable.weekday.matches(cells[0]) &&
        Regex("[1-9]|[12][0-9]|3[01]").matches(cells[1])

    internal fun date(): LocalDate? = runCatching {
        requireNotNull(ScheduleWeekdayTable.month(heading)).atDay(cells[1].toInt())
    }.getOrNull()

    internal fun clock(): String? {
        val match = ScheduleClocks.pattern.matchEntire(cells[3]) ?: return null
        val all = ScheduleClocks.pattern.findAll(cells.joinToString(" | ")).toList()
        if (all.size != 1 || all.single().groupValues.drop(1) != match.groupValues.drop(1)) return null
        return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString() }.getOrNull()
    }

    internal fun month(): YearMonth = requireNotNull(ScheduleWeekdayTable.month(heading))
}

/** Extractor-local canonical block identity, never supplied by HTML attributes. */
data class ScheduleHtmlCalendarScope(val id: Int, val text: String)

/** Complete non-row scope stays separate from the row whose date it may constrain. */
data class ScheduleHtmlCalendarNotice(
    val text: String,
    val heading: String,
    val rows: List<ScheduleHtmlCalendarRow>,
    val temporalScopeProven: Boolean,
    val scope: ScheduleHtmlCalendarScope? = null
) {
    internal fun starts(parish: ru.liturgyguide.model.Parish?): Set<String>? {
        if (!temporalScopeProven || rows.isEmpty() || rows.size > 600 || !ScheduleWeekdayTable.generalCancellation(text)) return null
        val month = ScheduleWeekdayTable.month(heading) ?: return null
        if (rows.map { it.tableKey }.distinct().size != 1 || rows.map { it.rowId }.distinct().size != rows.size) return null
        val starts = linkedSetOf<String>()
        for (row in rows) {
            if (!row.validFor(row.cells.joinToString(" | ")) || row.month() != month) return null
            if (row.cells[3].none(Char::isDigit)) continue
            if (!row.hasDateColumns()) return null
            val date = row.date() ?: return null
            val weekday = row.cells[0].removeSuffix(".")
            if (!ScheduleWeekdays.agrees(weekday, date)) return null
            val clock = row.clock() ?: return null
            val raw = row.cells.joinToString(" | ")
            // A matching name is not positive venue authority after "не/кроме/или".
            if (ScheduleVenue.hasSharedScopeRestriction(raw)) return null
            if (!ScheduleVenue.matches(ScheduleVenue.inlineLabel(raw, date, datedPreface = true), parish)) {
                if (ScheduleVenue.provesDifferentLocatedBuilding(raw, parish)) continue
                return null
            }
            starts += "${date}T$clock"
        }
        return starts
    }
}

/** One conservative calendar profile; all ordinary HTML extraction remains available. */
internal object ScheduleWeekdayTable {
    private val months = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private const val MONTH = "(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])"
    private val title = Regex("(?iu)^расписание\\s+(?:богослужений|служб)\\s+(?:(?:в|на)\\s+)?$MONTH\\s+(20\\d{2})(?:\\s+г(?:ода)?\\.?)?[.!]?$")
    private val anyMonthYear = Regex("(?iu)$MONTH\\s+(20\\d{2})")
    private val pluralCancellation = Regex("(?iu)(?<![а-яё])(?:все\\s+)?(?:богослужения|службы)\\s+не\\s+(?:состоятся|будут(?:\\s+совершаться)?)(?![а-яё])")
    private val restriction = Regex("(?iu)(?<![а-яё])(?:не|нет|ни|без|только|лишь|кроме|вместо|или|либо|соответственно)(?![а-яё])")
    private val scopeWord = Regex("(?iu)(служб|богослуж|литург|вечерн|утрен|всенощ|храм|церк|собор|молеб|панихид|акафист)")
    internal val weekday = Regex("(?iu)^(?:${ScheduleWeekdays.dateWords})\\.?$")
    private const val FOREIGN = "nav,header,footer,aside,blockquote,q,[hidden],[aria-hidden=true],.sidebar,#comments,.comments,.comment-list,.commentlist,.comment,.comment-content,#respond,.comment-respond"

    internal fun unsafe(value: String): Boolean = value.codePoints().anyMatch {
        Character.isISOControl(it) && it != 9 && it != 10 && it != 13 ||
            Character.getType(it) == Character.FORMAT.toInt() || Character.getType(it) == Character.SURROGATE.toInt()
    }

    internal fun month(heading: String): YearMonth? {
        if (heading.length > 1_000 || unsafe(heading)) return null
        val match = title.matchEntire(heading) ?: return null
        return runCatching { YearMonth.of(match.groupValues[2].toInt(), months.indexOfFirst { match.groupValues[1].startsWith(it, true) } + 1) }.getOrNull()
    }

    internal fun restricted(value: String): Boolean = unsafe(value) || restriction.containsMatchIn(value)

    internal fun cancellation(value: String): Boolean = ScheduleCancellation.containsMatchIn(value) || pluralCancellation.containsMatchIn(value)

    // Only a general unqualified statement can cancel every listed start. Other scopes stay unresolved.
    internal fun generalCancellation(value: String): Boolean =
        Regex("(?iu)^(?:все\\s+)?(?:богослужения|службы)\\s+(?:отменены|не\\s+состоятся|не\\s+будут(?:\\s+совершаться)?)[.!]?$")
            .matches(value.trim())

    private fun visible(element: Element): Boolean = element.closest(FOREIGN) == null &&
        (element.parents() + element).none {
            Regex("(?iu)(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(it.attr("style")) ||
                it.attr("aria-hidden").trim().equals("true", true) || it.hasAttr("hidden")
        }

    private fun completeVisible(element: Element): Boolean = element.getAllElements().all(::visible)

    // Preserve inline words such as н<strong>е</strong>, without joining separate block scopes.
    private fun inlineScope(node: org.jsoup.nodes.Node): String = when (node) {
        is org.jsoup.nodes.TextNode -> node.wholeText
        is Element -> if (node.tagName() in setOf("article", "aside", "blockquote", "body", "br", "hr", "div", "footer", "form", "header", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tbody", "thead", "tfoot", "tr", "td", "th", "ul", "h1", "h2", "h3", "h4", "h5", "h6")) " "
            else if (!visible(node)) " " else node.childNodes().joinToString("") { inlineScope(it) }
        else -> ""
    }

    private fun scopeText(element: Element): String = element.childNodes().joinToString("") { inlineScope(it) }

    private val scopeBlocks = setOf("article", "body", "caption", "div", "form", "li", "main", "ol", "p", "pre", "section", "table", "ul",
        "h1", "h2", "h3", "h4", "h5", "h6")
    private data class ScopePart(val element: Element, val text: String, val textNodes: List<org.jsoup.nodes.TextNode>,
        val proof: ScheduleHtmlCalendarScope)

    /** A containing block owns its qualifiers; inline children never become independent notices. */
    private fun canonicalScopes(boundary: Element, scopeIds: Map<Element, Int>, eligible: (Element) -> Boolean): List<ScopePart> {
        val candidates = boundary.getAllElements().filter { element ->
            eligible(element) && element.tagName() in scopeBlocks &&
                (element.tagName() == "caption" || scopeText(element).isNotBlank())
        }.toSet()
        return candidates.filter { element -> element.parents().none { it in candidates } }.mapNotNull { root ->
            val consumed = mutableListOf<org.jsoup.nodes.TextNode>()
            fun collect(node: org.jsoup.nodes.Node): String = when (node) {
                is org.jsoup.nodes.TextNode -> {
                    val heading = (node.parent() as? Element)?.closest("h1,h2,h3,h4,h5,h6")
                    // Keep the independently parsed source month heading in the ordinary stream.
                    if (heading == null || month(text(heading)) == null) consumed += node
                    node.wholeText
                }
                is Element -> if (!eligible(node)) " " else if (node.tagName() in setOf("br", "hr")) " " else {
                    val inner = node.childNodes().joinToString("") { collect(it) }
                    if (node.tagName() in scopeBlocks) " $inner " else inner
                }
                else -> ""
            }
            val complete = collect(root).replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
            complete.takeIf(String::isNotBlank)?.let { ScopePart(root, it, consumed, ScheduleHtmlCalendarScope(requireNotNull(scopeIds[root]), it)) }
        }
    }

    internal fun clearlyForeignNotice(value: String, parish: ru.liturgyguide.model.Parish?): Boolean {
        if (parish == null || Regex("(?iu)(?<![а-яё])(?:не|кроме|вместо|или|либо)(?![а-яё])").containsMatchIn(value)) return false
        if (Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])").findAll(value).count() != 1) return false
        val label = ScheduleVenue.inlineLabel(value) ?: return false
        return ScheduleVenue.isLabel(value) && !ScheduleVenue.matches(label, parish)
    }

    private fun owner(element: Element): Element? = element.closest("article,.hentry")
    private fun text(element: Element): String {
        val copy = element.clone()
        copy.select("br").forEach { it.before(" ") }
        val nodes = mutableListOf<org.jsoup.nodes.TextNode>()
        copy.traverse { node, _ -> if (node is org.jsoup.nodes.TextNode) nodes += node }
        nodes.zipWithNext().forEach { (previous, next) ->
            // Restore a sentence boundary at an inline DOM edge, never split words
            // such as н<strong>е</strong> or invent whitespace within source text.
            if (previous.parent() !== next.parent() && previous.wholeText.lastOrNull() in listOf('.', '!', '?') &&
                next.wholeText.firstOrNull()?.let { it in 'А'..'Я' || it == 'Ё' } == true)
                next.text(" " + next.wholeText)
        }
        return copy.wholeText().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    data class TablePlan(val rows: List<ScheduleDocumentLine>?, val notices: List<ScheduleDocumentLine>)
    data class Extraction(private val tables: Map<Element, TablePlan>, val representedTextNodes: Map<org.jsoup.nodes.TextNode, ScheduleHtmlCalendarScope>) {
        operator fun get(table: Element): TablePlan? = tables[table]
    }
    private data class Candidate(val table: Element, val heading: String, val rows: List<ScheduleDocumentLine>?,
        val ownerScope: List<ScopePart>, val tableScope: List<ScopePart>, val globalScope: List<ScopePart>,
        val yearConflict: Boolean, val oneOwnedTable: Boolean)

    private fun yearConflict(values: List<String>, declared: YearMonth): Boolean = values.any { value ->
        Regex("(?<![0-9])(?:19|20|21)[0-9]{2}(?![0-9])").findAll(value).any { it.value.toInt() != declared.year } ||
            anyMonthYear.findAll(value).any { match ->
                match.groupValues[2].toInt() != declared.year ||
                    months.indexOfFirst { match.groupValues[1].startsWith(it, true) } + 1 != declared.monthValue
            }
    }

    private fun canRecover(candidate: Candidate): Boolean {
        val outside = (candidate.ownerScope + candidate.globalScope).map { it.text }.distinct()
        val restrictive = outside.any { restricted(it) && scopeWord.containsMatchIn(it) } ||
            candidate.tableScope.any { restricted(it.text) || ScheduleVenue.inlineLabel(it.text) != null }
        return candidate.rows != null && candidate.oneOwnedTable && !candidate.yearConflict && !restrictive &&
            (outside + candidate.tableScope.map { it.text }).none(::cancellation)
    }

    fun extract(body: Element): Extraction {
        val scopeIds = body.getAllElements().withIndex().associate { it.value to it.index + 1 }
        val globalScope = canonicalScopes(body, scopeIds) { it.closest("table") == null && owner(it) == null && visible(it) }
        val candidates = body.select("table").mapNotNull { table ->
            if (!visible(table) || table.select("table").any { it !== table }) return@mapNotNull null
            val scope = owner(table)?.takeIf(::visible) ?: return@mapNotNull null
            val headings = scope.select("h1,h2,h3,h4,h5,h6").filter { owner(it) === scope && it.closest("table") == null }
            val heading = headings.singleOrNull { month(text(it)) != null && completeVisible(it) } ?: return@mapNotNull null
            val headingText = text(heading)
            val declaredMonth = requireNotNull(month(headingText))
            val ownerScope = canonicalScopes(scope, scopeIds) { owner(it) === scope && it.closest("table") == null && visible(it) }
            // Caption/other visible text outside physical rows is never discarded by the fast path.
            val tableScope = canonicalScopes(table, scopeIds) { it.closest("tr") == null && visible(it) }
            Candidate(table, headingText, rows(table, headingText), ownerScope, tableScope, globalScope,
                yearConflict((ownerScope + tableScope).map { it.text }, declaredMonth) || headings.any { it !== heading && anyMonthYear.containsMatchIn(text(it)) },
                scope.select("table").filter { owner(it) === scope }.size == 1)
        }
        val recoverableCount = candidates.count(::canRecover)
        val represented = linkedMapOf<org.jsoup.nodes.TextNode, ScheduleHtmlCalendarScope>()
        val tables = candidates.associate { candidate ->
            val scopeValues = (candidate.ownerScope + candidate.globalScope).distinctBy { it.element }
            val cancellations = (scopeValues + candidate.tableScope).filter { cancellation(it.text) }.distinctBy { it.element }
            cancellations.forEach { part -> part.textNodes.forEach { represented[it] = part.proof } }
            val permit = recoverableCount == 1 && canRecover(candidate)
            // Table-local identity is unchanged when an independently qualified external publisher h1 is removed.
            val localKeyBytes = (candidate.heading + "\u0001" + candidate.tableScope.joinToString("\u0000") { it.text } + "\u0001" +
                candidate.rows.orEmpty().joinToString("\u0000") { it.text }).toByteArray(Charsets.UTF_8)
            val tableKey = java.security.MessageDigest.getInstance("SHA-256").digest(localKeyBytes).joinToString("") { "%02x".format(it) }
            val boundRows = candidate.rows?.map { line -> line.copy(htmlCalendarRow = line.htmlCalendarRow?.copy(tableKey = tableKey, permitted = permit)) }
            val proofRows = boundRows.orEmpty().mapNotNull { it.htmlCalendarRow }
            val notices = (candidate.tableScope + cancellations).distinctBy { it.element }.map { part ->
                val raw = part.text
                // An owner-wide cancellation cannot be narrowed to this table if other
                // timed service text remains outside it. A caption is table-local.
                val tableLocal = part.element.closest("table") === candidate.table
                val extraTimedScope = candidate.ownerScope.any { ScheduleClocks.pattern.containsMatchIn(it.text) && scopeWord.containsMatchIn(it.text) }
                val temporalScopeProven = !candidate.yearConflict && (tableLocal ||
                    (part in candidate.ownerScope && candidate.oneOwnedTable && !extraTimedScope))
                val notice = ScheduleHtmlCalendarNotice(raw, candidate.heading, proofRows, temporalScopeProven, part.proof)
                ScheduleDocumentLine(raw, htmlScopeText = raw, htmlCalendarNotice = notice)
            }
            candidate.table to TablePlan(boundRows, notices)
        }
        return Extraction(tables, represented)
    }

    private data class Cell(val text: String, val origin: Int, val lastRow: Int, val struck: Boolean)

    private fun rows(table: Element, heading: String): List<ScheduleDocumentLine>? {
        val rows = table.select("tr").filter { it.closest("table") === table }
        if (rows.size !in 1..600 || rows.any { !visible(it) }) return null
        val spans = mutableMapOf<Int, Cell>()
        val result = mutableListOf<ScheduleDocumentLine>()
        var dateColumnsSeen = false
        for ((zeroIndex, row) in rows.withIndex()) {
            val index = zeroIndex + 1
            val grid = sortedMapOf<Int, Cell>()
            spans.entries.removeAll { it.value.lastRow < index }
            grid.putAll(spans)
            var column = 0
            for (element in row.children()) {
                if (element.tagName() !in setOf("td", "th") || !completeVisible(element) || element.select("table").isNotEmpty()) return null
                if (element.hasAttr("colspan") && element.attr("colspan") != "1") return null
                val span = if (element.hasAttr("rowspan")) element.attr("rowspan").takeIf { Regex("[1-9][0-9]{0,2}").matches(it) }?.toInt() ?: return null else 1
                if (index + span - 1 > rows.size) return null
                while (column in grid) column++
                if (column > 4) return null
                val raw = element.wholeText()
                if (unsafe(raw)) return null
                val cell = Cell(text(element), index, index + span - 1, element.select("s,del,strike").isNotEmpty())
                if (cell.text.length > 2_000) return null
                grid[column] = cell
                if (span > 1) spans[column] = cell
                column++
            }
            if (grid.keys.toList() != (0..4).toList()) return null
            val values = grid.values.toList()
            if (values[0].origin != values[1].origin || values[0].lastRow != values[1].lastRow ||
                values[3].origin != index || values[3].lastRow != index || values[4].origin != index || values[4].lastRow != index ||
                values[4].text.isBlank()) return null
            val cells = values.map { it.text }
            val raw = cells.joinToString(" | ")
            val proof = ScheduleHtmlCalendarRow(index, values[0].origin, values[0].lastRow, heading, cells)
            dateColumnsSeen = dateColumnsSeen || proof.hasDateColumns()
            result += ScheduleDocumentLine(raw, struck = values.any { it.struck },
                serviceText = proof.clock()?.let { "${cells[3]} | ${cells[4]}" }, htmlScopeText = raw,
                htmlCalendarRow = proof)
        }
        return result.takeIf { dateColumnsSeen }
    }
}
