package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Two direct italic runs in an unchanged captured source, not a general rule for italic prose. */
class PublishedDoxologyNodeTest {
    @Test fun theTwoActualRunsArePrayersInPlaceBetweenTheirUnchangedHymnsAndRubricLabels() {
        val document = document()
        val runs = actualRuns(document)
        val hymns = listOf(neighbour(runs[0], before = true), neighbour(runs[0], before = false),
            neighbour(runs[1], before = false)).map { require(it is Element && it.tagName() == "p"); it }
        assertSame("The middle signed hymn is shared by the two actual boundaries", hymns[1], neighbour(runs[1], before = true))
        val labels = hymns.map { BookText.clean(it.select("label.bg_worships_info").single().text()) }
        assertEquals(listOf("Тропа́рь Креста́ (изнесе́ние Дре́ва)", "Тропарь семи мучеников Маккавеев",
            "Тропа́рь Креста́ (изнесе́ние Дре́ва)"), labels)
        val prayerTexts = hymns.map { paragraph ->
            val copy = paragraph.clone()
            copy.select("label.bg_worships_info").remove()
            BookText.clean(copy.text())
        }
        val expected = listOf(labels[0], prayerTexts[0], GLORY, labels[1], prayerTexts[1], NOW, labels[2], prayerTexts[2])
        val htmlBefore = document.outerHtml()
        val book = parse(document)
        val window = chapter(book).blocks.windowed(expected.size).single { it.map { block -> block.original } == expected }
        assertEquals(expected, window.map { it.original })
        assertEquals(listOf(BookBlockKind.RUBRIC, BookBlockKind.PRAYER, BookBlockKind.PRAYER,
            BookBlockKind.RUBRIC, BookBlockKind.PRAYER, BookBlockKind.PRAYER,
            BookBlockKind.RUBRIC, BookBlockKind.PRAYER), window.map { it.kind })
        for (text in listOf(GLORY, NOW)) {
            val block = window.single { it.original == text }
            assertFalse(block.isAltarText)
            assertNull("The source has no explicit speaker for these runs", block.speaker)
            assertNull("Slavonic must not gain a false Russian copy", block.russian)
        }
        assertTrue(ServiceBookIntegrity.valid(book))
        assertEquals("Parsing must not rewrite the repaired source", htmlBefore, document.outerHtml())
    }

    @Test fun whitespaceBetweenTheActualSiblingsDoesNotHideEitherConfirmedPrayer() {
        val document = document()
        for (run in actualRuns(document)) {
            run.before(TextNode("\n \t"))
            run.after(TextNode("\n\u00a0 \t"))
        }
        val book = parse(document)
        for (text in listOf(GLORY, NOW))
            assertEquals(BookBlockKind.PRAYER, chapter(book).blocks.single { it.original == text }.kind)
    }

    @Test fun existingNestedItalicPrayersAndAccentedCanonLabelsAreNeitherDuplicatedNorReclassified() {
        val document = document()
        val leafOccurrences = listOf(GLORY, NOW).associateWith { text ->
            document.select(".article-single-content.main-page-content i").count { element ->
                element.childNodeSize() == 1 && element.childNode(0) is TextNode && BookText.clean(element.text()) == text
            }
        }
        assertEquals(mapOf(GLORY to 10, NOW to 11), leafOccurrences)
        val book = parse(document)
        for ((text, occurrences) in leafOccurrences) {
            assertEquals("Every actual occurrence must survive exactly once", occurrences,
                book.blocks.sumOf { count(it.original, text) })
            assertTrue("Existing italic paragraphs remain real prayers", book.chapters.filter { it.id != chapter(book).id }
                .flatMap { it.blocks }.any { it.original == text && it.kind == BookBlockKind.PRAYER })
        }
        val canon = "Кано́н святы́х апо́столов (в четверто́к у́тра). Глас 8. На 4."
        assertTrue(document.select("label.bg_worships_info").any { BookText.clean(it.text()) == canon })
        val canonLabels = book.blocks.filter { it.original == canon }
        assertTrue(canonLabels.isNotEmpty())
        assertTrue("Accents in a source label do not turn it into a prayer", canonLabels.all { it.kind == BookBlockKind.RUBRIC })
        assertEquals(book.blocks.size, book.blocks.map { it.id }.toSet().size)
    }

    @Test fun anotherParentOrANonTextItalicBodyCannotGainTheDirectRunProfile() {
        val mutations: List<Pair<String, (Element) -> Unit>> = listOf(
            "not a variable region" to { requireNotNull(it.parent()).removeClass("variable_text") },
            "not a div region" to { requireNotNull(it.parent()).tagName("section") },
            "an intervening wrapper" to { it.wrap("<span></span>") },
            "an element inside the run" to { it.html("<span>$GLORY</span>") },
            "more than one text node" to { it.appendChild(TextNode(" ")) }
        )
        for ((label, mutate) in mutations) {
            val document = document()
            val run = actualRuns(document).first()
            mutate(run)
            assertEquals(label, BookBlockKind.RUBRIC, chapter(parse(document)).blocks.single { it.original == GLORY }.kind)
        }
    }

    @Test fun bothImmediateSignedParagraphsAreRequiredAndOnlyWhitespaceMayBeSkipped() {
        val mutations: List<Pair<String, (Element) -> Unit>> = listOf(
            "previous label missing" to { (neighbour(it, true) as Element).select("label.bg_worships_info").remove() },
            "next label missing" to { (neighbour(it, false) as Element).select("label.bg_worships_info").remove() },
            "previous label has another class" to { (neighbour(it, true) as Element).select("label.bg_worships_info").single().removeClass("bg_worships_info") },
            "next label has another tag" to { (neighbour(it, false) as Element).select("label.bg_worships_info").single().tagName("span") },
            "previous sibling is not p" to { (neighbour(it, true) as Element).tagName("div") },
            "next sibling is not p" to { (neighbour(it, false) as Element).tagName("div") },
            "nonblank text before" to { it.before(TextNode("Редакторская пометка")) },
            "nonblank text after" to { it.after(TextNode("Редакторская пометка")) },
            "soft hyphen is not whitespace" to { it.before(TextNode("\u00ad")) },
            "empty element before" to { it.before(Element("span")) },
            "comment after" to { it.after(Comment("This is not whitespace")) }
        )
        for ((label, mutate) in mutations) {
            val document = document()
            val run = actualRuns(document).first()
            mutate(run)
            assertEquals(label, BookBlockKind.RUBRIC, chapter(parse(document)).blocks.single { it.original == GLORY }.kind)
        }
    }

    @Test fun aDifferentTextOrAnEditorialInstructionInTheSameShapeRemainsARubric() {
        for (text in listOf(GLORY.replace("Отцу́", "Отцу"), "$GLORY ", "$GLORY Редакторская пометка.", "Указание: здесь поют следующий тропарь")) {
            val document = document()
            actualRuns(document).first().text(text)
            val block = chapter(parse(document)).blocks.single { it.original == BookText.clean(text) }
            assertEquals(BookBlockKind.RUBRIC, block.kind)
            assertEquals(BookText.clean(text), block.russian)
        }
    }

    @Test fun theSameTwoActualRunsStayRubricsInsideAnAltarRegion() {
        val document = document()
        val runs = actualRuns(document)
        val region = requireNotNull(runs.first().parent())
        assertSame(region, runs.last().parent())
        region.wrap("<details></details>")
        requireNotNull(region.parent()).prependElement("summary").text("В алтаре")
        val book = parse(document)
        for (text in listOf(GLORY, NOW)) {
            val block = chapter(book).blocks.single { it.original == text }
            assertTrue(block.isAltarText)
            assertEquals(BookBlockKind.RUBRIC, block.kind)
        }
    }

    private fun document(): Document {
        val raw = requireNotNull(javaClass.classLoader?.getResource("prayer-dom/cross-matins.html")).readText()
        assertEquals("The genuine captured source fixture must remain unchanged",
            "7cf267a6a2e196825437990d195f9773d33b6db267e69ffd6cae056a1d8c492c", BookText.sha256(raw))
        return Jsoup.parse(raw)
    }

    private fun actualRuns(document: Document): List<Element> = document.select("div.variable_text > i")
        .filter { it.childNodeSize() == 1 && it.childNode(0) is TextNode && it.text() in listOf(GLORY, NOW) }
        .also { assertEquals(listOf(GLORY, NOW), it.map(Element::text)) }

    private fun neighbour(node: Node, before: Boolean): Node? {
        var result = if (before) node.previousSibling() else node.nextSibling()
        while (result is TextNode && BookText.clean(result.text()).isEmpty())
            result = if (before) result.previousSibling() else result.nextSibling()
        return result
    }

    private fun parse(document: Document): ServiceBook {
        fun selected(id: String) = requireNotNull(document.selectFirst("#$id option[selected]")).attr("value")
        val key = ServiceBookKey(requireNotNull(document.selectFirst("#bg_worships_setDay")).attr("value"),
            WorshipKind.entries.single { it.queryValue == selected("bg_worship_type") }, selected("bg_worships_church"))
        return ServiceBookParser().parse(document, key, 12345)
    }

    private fun chapter(book: ServiceBook) = book.chapters.single { BookText.normalized(it.title) == "бог господь" }
    private fun count(text: String, value: String): Int {
        var count = 0
        var at = text.indexOf(value)
        while (at >= 0) { count++; at = text.indexOf(value, at + value.length) }
        return count
    }

    private companion object {
        const val GLORY = "Сла́ва Отцу́, и Сы́ну, и Свято́му Ду́ху."
        const val NOW = "И ны́не, и при́сно, и во ве́ки веко́в. Ами́нь."
    }
}
