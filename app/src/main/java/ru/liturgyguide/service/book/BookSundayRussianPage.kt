package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.BookSource

/** The legacy edition uses Latin p as a Cyrillic glyph only in its Slavonic column. */
internal object BookSundayRussianPage {
    // Classify aliases so they cannot escape into the permissive generic parser.
    // prepare() still requires the complete exact publication URL and BookSource.
    fun registered(source: BookSource): Boolean = BookTranslationIndex.OCTOECHOS_SOURCES.any {
        it.url == source.url.substringBefore('?').substringBefore('#')
    }

    fun prepare(document: Document, source: BookSource): Document? {
        val tone = BookTranslationIndex.OCTOECHOS_SOURCES.indexOfFirst { it == source }.takeIf { it >= 0 }?.plus(1) ?: return null
        if (document.location() != source.url) return null
        val title = document.select("h1").singleOrNull()?.text() ?: return null
        if (BookText.normalized(title) != "октоих воскресный глас $tone") return null
        val article = document.select(".article-single-content.main-page-content").singleOrNull() ?: return null
        if (article.select("table").isEmpty() || article.select("table table").isNotEmpty()) return null
        val rows = article.select("table tr")
        if (rows.none { row -> cells(row).let { it.size == 2 &&
                BookText.clean(it[0].text()) == "Церковнославянский" && BookText.clean(it[1].text()) == "Русский" } }) return null
        if (rows.any { row ->
                val cells = cells(row)
                !(cells.size == 2 && cells.all { it.attr("colspan") in setOf("", "1") && it.attr("rowspan") in setOf("", "1") } ||
                    cells.size == 1 && cells[0].attr("colspan") == "3" &&
                    cells[0].attr("rowspan") in setOf("", "1") &&
                    BookText.normalized(cells[0].text()) == "глас $tone" && cells[0].select("h2").size == 1)
            }) return null
        val copy = document.clone()
        val clonedArticle = copy.select(".article-single-content.main-page-content").single()
        clonedArticle.select("table tr").forEach { row -> cells(row).takeIf { it.size == 2 }?.first()?.let(::correctNodes) }
        return copy
    }

    private fun cells(row: Element) = row.children().filter { it.tagName() in setOf("td", "th") }
    private fun correctNodes(node: Node) {
        data class Fragment(val node: TextNode, val start: Int, val end: Int)
        val stream = StringBuilder()
        val fragments = mutableListOf<Fragment>()
        fun collect(current: Node) {
            if (current is TextNode) {
                val value = current.wholeText
                val start = stream.length
                stream.append(value)
                fragments += Fragment(current, start, stream.length)
            } else {
                // Inline markup does not split a word. Only actual block/line boundaries do.
                val boundary = current is Element && (current.tag().isBlock || current.tagName() == "br")
                if (boundary) stream.append(' ')
                current.childNodes().forEach(::collect)
                if (boundary) stream.append(' ')
            }
        }
        collect(node)
        val corrected = correctText(stream.toString())
        // The approved p/P -> р/Р substitution is length preserving. Retain the complete DOM.
        fragments.forEach { it.node.text(corrected.substring(it.start, it.end)) }
    }

    /** Inspect the complete original token before the general concordance normalizer. */
    internal fun correctText(text: String): String = Regex("[\\p{L}\\p{M}\\p{N}]+").replace(text) { match ->
        val word = match.value
        val hasCyrillic = word.any { Character.isLetter(it) && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
        val allowed = word.all { it == 'p' || it == 'P' ||
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC ||
            Character.getType(it) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) }
        if (hasCyrillic && allowed) word.replace('p', 'р').replace('P', 'Р') else word
    }
}
