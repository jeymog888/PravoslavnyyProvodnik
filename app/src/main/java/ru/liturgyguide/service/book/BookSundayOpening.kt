package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.BookSource

/** Finite publisher metadata contracts. Prayer words remain one complete exact body. */
internal object BookSundayOpening {
    data class Key(val count: String, val body: String)
    data class Body(val original: String, val russian: String, val count: String? = null)
    private val counts = setOf("4", "6", "8", "10", "12")
    private val sourceCounts = counts.map { "на $it:" }.toSet()
    private val bookPrefixes = counts.associate { "на $it: 1 Лик:" to "на $it:" }
    private val inlineTags = setOf("b", "strong", "i", "em", "br")
    private val anotherCount = Regex("^на [0-9]+:", RegexOption.IGNORE_CASE)

    fun key(count: String, body: String): Key? = count.takeIf { it in sourceCounts }
        ?.let { Key(it, BookTranslationIndex.normal(body)) }

    fun find(original: String, bodyPair: (Key) -> BookTranslation?): BookTranslation? {
        val text = BookText.clean(original)
        // The choir label is taken literally from this book, never inferred from source "1.".
        val prefix = bookPrefixes.keys.singleOrNull { text.startsWith("$it ") } ?: return null
        val body = text.substring(prefix.length).trim()
        if (body.isBlank() || anotherCount.containsMatchIn(body) ||
            BookText.withoutSpeaker(body).first != null) return null
        val pair = bodyPair(key(bookPrefixes.getValue(prefix), body) ?: return null) ?: return null
        if (pair.source != BookTranslationIndex.TIMROT_FULL_SOURCE) return null
        val russian = BookText.clean(pair.russian)
        if (russian.isBlank() || anotherCount.containsMatchIn(russian) ||
            BookText.withoutSpeaker(russian).first != null) return null
        return pair.copy(russian = "$prefix $russian")
    }

    fun fromRow(document: Document, source: BookSource, left: Element, right: Element): Body? {
        val row = left.parent() ?: return null
        if (right.parent() !== row || row.tagName() != "tr" || row.children().size != 2 ||
            listOf(left, right).any { it.tagName() != "td" || it.attr("colspan") !in setOf("", "1") ||
                it.attr("rowspan") !in setOf("", "1") }) return null
        if (fullLiturgy(document, source)) return pairedCount(left, right) ?: pairedPsalmCitation(left, right)
        val tone = BookTranslationIndex.OCTOECHOS_SOURCES.indexOf(source).takeIf { it in setOf(6, 7) }?.plus(1)
            ?: return null
        if (document.location() != source.url || document.select("h1").singleOrNull()?.text()?.let(BookText::normalized) !=
            "октоих воскресный глас $tone") return null
        val expectedLeft = if (tone == 7) "блаженна глас 7" else "блаженны глас 8"
        val a = hymn(left, expectedLeft) ?: return null
        val b = hymn(right, "блаженны на 8") ?: return null
        return Body(a, b)
    }

    private fun fullLiturgy(document: Document, source: BookSource): Boolean =
        source == BookTranslationIndex.TIMROT_FULL_SOURCE && document.location() == source.url &&
            document.select("h1").singleOrNull()?.text()?.let(BookText::normalized) ==
                "божественная литургия святого отца нашего иоанна златоуста"

    private fun nodes(cell: Element): List<Node> = cell.childNodes().filter {
        it !is TextNode || BookText.clean(it.text()).isNotEmpty()
    }

    private fun takeMarker(cell: Element, style: String, allowed: Set<String>): String? {
        val marker = nodes(cell).firstOrNull() as? Element ?: return null
        if (marker.tagName() != "span" || marker.classNames() != setOf(style) || marker.children().isNotEmpty()) return null
        val text = BookText.clean(marker.text()).takeIf { it in allowed } ?: return null
        marker.remove()
        return text
    }

    private fun wholeInlineBody(cell: Element): String? {
        if (cell.getAllElements().drop(1).any { it.tagName() !in inlineTags }) return null
        val text = BookText.clean(cell.text()).takeIf(String::isNotBlank) ?: return null
        if (BookText.withoutSpeaker(text).first != null) return null
        return text
    }

    private fun pairedCount(left: Element, right: Element): Body? {
        fun part(cell: Element): Pair<String, String>? {
            val copy = cell.clone()
            val count = takeMarker(copy, "k", sourceCounts) ?: return null
            // Other standalone 1./2. rows retain their existing extraction path.
            takeMarker(copy, "n", setOf("1.")) ?: return null
            return count to (wholeInlineBody(copy) ?: return null)
        }
        val a = part(left) ?: return null
        val b = part(right) ?: return null
        return if (a.first == b.first) Body(a.second, b.second, a.first) else null
    }

    private fun pairedPsalmCitation(left: Element, right: Element): Body? {
        val a = left.clone()
        val b = right.clone()
        takeMarker(a, "n", setOf("1.")) ?: return null
        takeMarker(b, "n", setOf("1.")) ?: return null
        val citation = nodes(b).lastOrNull() as? Element ?: return null
        if (!psalm102Citation(citation)) return null
        citation.remove()
        return Body(wholeInlineBody(a) ?: return null, wholeInlineBody(b) ?: return null)
    }

    /** Only the reviewed terminal bibliography node; never trim prayer text after a "Пс" string. */
    private fun psalm102Citation(node: Element): Boolean {
        if (node.tagName() != "span" || node.classNames() != setOf("n") || node.ownText().isNotBlank() ||
            node.children().size != 1) return false
        val reference = node.children().single()
        if (reference.tagName() != "span" || reference.classNames() != setOf("bg_data_title", "bg_bibrefs") ||
            reference.ownText().isNotBlank() || reference.attr("data-title") !=
                "?title=Ps&chapter=102:1-8&type=t_verses&lang=ru" ||
            reference.attr("title") != "Псалтирь пс. 102:1-8" || reference.children().size != 2) return false
        val tooltip = reference.children()[0]
        val link = reference.children()[1]
        return tooltip.tagName() == "span" && tooltip.classNames() == setOf("bg_data_tooltip") &&
            tooltip.children().isEmpty() && tooltip.text().isBlank() && link.tagName() == "a" &&
            link.children().isEmpty() && link.attr("href") == "https://azbyka.ru/biblia/?Ps.102:1-8&c~r" &&
            BookText.clean(link.text()) == "Пс 102:1-8"
    }

    private fun hymn(cell: Element, heading: String): String? {
        val content = nodes(cell)
        if (content.size != 3 || content.any { it !is Element }) return null
        val a = content[0] as Element
        val b = content[1] as Element
        val paragraph = content[2] as Element
        if (a.tagName() != "h2" || BookText.normalized(a.text()) != "на литургии" ||
            b.tagName() != "h3" || BookText.normalized(b.text()) != heading || paragraph.tagName() != "p") return null
        return wholeInlineBody(paragraph)
    }
}
