package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Actual dated paragraphs, not a synthetic abbreviated service or a guessed prayer boundary. */
class BookPrayerBoundaryTest {
    private val matins = Source("/book-service-fit/base-2026-11-10.html",
        ServiceBookKey("2026-11-10", WorshipKind.MATINS, "20"), 7)
    private val vespers = listOf(
        Source("/book/vespers-2026-11-12.html", ServiceBookKey("2026-11-12", WorshipKind.VESPERS), 3),
        Source("/book/vespers-2026-11-15.html", ServiceBookKey("2026-11-15", WorshipKind.VESPERS), 3)
    )

    @Test fun sevenRealMatinsExclamationsKeepEveryWordAndArePrayersRatherThanTranslatedInstructions() {
        assertSourceBoundaries(matins)
    }

    @Test fun bothRealVespersOrdersKeepTheirThreeExclamationsAndTheirSpokenDuration() {
        vespers.forEach { assertSourceBoundaries(it) }
    }

    @Test fun nestedMarkupWithinTheReviewedLabelDoesNotConsumePrayerWords() {
        for (case in listOf(matins, vespers.first())) {
            val document = document(case)
            requireNotNull(targetParagraph(document).children().firstOrNull())
                .html("И <em>священник</em> возглашает:")
            assertSourceBoundaries(case, document)
        }
    }

    @Test fun anExactPublishedPairBecomesAvailableOnlyToTheSeparatedPrayer() {
        // Verbatim two-column row 21 of the already reviewed short Timrot edition
        // https://azbyka.ru/bogosluzhenie/liturgiya-svt-ioanna-zlatousta/
        // Cached full HTML SHA-256: abb4dd9cfcc174194af1d1439840385308cba45e9e754fba5ca84b2a36fddf7f
        val source = BookTranslationIndex.TIMROT_SOURCE
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse(PUBLISHED_PAIR, source.url), source)
        val expected = "Ибо Тебе подобает вся слава, честь и поклонение, Отцу и Сыну и Святому Духу, ныне и всегда, и во веки веков."
        for (case in listOf(matins) + vespers) {
            val document = document(case)
            val original = sourceParagraphs(document).map { it.removePrefix("$PREFIX ") }
                .single { BookText.normalized(it).startsWith("яко подобает тебе всякая слава") }
            assertEquals("The unmodified published row must establish this exact lexical pair", expected,
                index.find(original)?.russian)
            assertEquals(source, index.find(original)?.source)
            val book = ServiceBookParser().parse(document, case.key, 12345, index)
            val prayer = separatedPrayers(book).single { it.original == original }
            assertEquals(BookBlockKind.PRAYER, prayer.kind)
            assertEquals(expected, prayer.russian)
            assertEquals(source, prayer.translationSource)
            assertNotEquals(prayer.original, prayer.russian)
            assertTrue("The unchanged editorial instruction remains Russian prose, not a translation source",
                book.blocks.filter { it.original == PREFIX }.all {
                    it.kind == BookBlockKind.RUBRIC && it.russian == PREFIX && it.translationSource == null
                })
        }
    }

    @Test fun aStandaloneOrUnknownInstructionCannotCreateAnEmptyPrayerOrStripWords() {
        for (case in listOf(matins, vespers.first())) {
            val standalone = document(case)
            targetParagraph(standalone).text(PREFIX)
            val book = ServiceBookParser().parse(standalone, case.key, 12345)
            assertTrue(book.blocks.any { it.original == PREFIX && it.kind == BookBlockKind.RUBRIC })
            assertFalse(book.blocks.any { it.original.isBlank() || it.kind == BookBlockKind.PRAYER && it.original == PREFIX })

            val unknown = document(case)
            val paragraph = targetParagraph(unknown)
            val unchanged = "Неизвестное указание: ${BookText.clean(paragraph.text())}"
            paragraph.text(unchanged)
            val unknownBook = ServiceBookParser().parse(unknown, case.key, 12345)
            assertEquals("An unreviewed leading direction must remain a whole paragraph", 1,
                unknownBook.blocks.count { it.original == unchanged })
            assertTrue(ServiceBookIntegrity.valid(book))
            assertTrue(ServiceBookIntegrity.valid(unknownBook))
        }
    }

    private fun assertSourceBoundaries(case: Source, document: Document = document(case)) {
        val paragraphs = sourceParagraphs(document)
        assertEquals("The actual source fixture must still contain every reviewed occurrence", case.count, paragraphs.size)
        val book = ServiceBookParser().parse(document, case.key, 12345)
        val pairs = book.blocks.windowed(2).filter { (label, _) ->
            label.kind == BookBlockKind.RUBRIC && label.original == PREFIX
        }
        assertEquals("Every dated exclamation needs its own instruction and prayer", case.count, pairs.size)
        assertEquals("Splitting must keep every source word, duplicate and occurrence in source order",
            paragraphs, pairs.map { (label, prayer) -> "${label.original} ${prayer.original}" })
        pairs.forEach { (label, prayer) ->
            assertEquals(BookBlockKind.PRAYER, prayer.kind)
            assertFalse(label.isAltarText || prayer.isAltarText)
            assertNull("An untranslated prayer must not be falsely presented as Russian", prayer.russian)
            assertNull(prayer.translationSource)
            assertTrue("The actual prayer has spoken words, not the 0.8-second instruction duration",
                BookAutoScroll.seconds(BookAutoScroll.wordCount(prayer.original), BookAutoScroll.MAX_RATE) > .8f)
        }
        assertTrue(ServiceBookIntegrity.valid(book))
        assertEquals(book.blocks.size, book.blocks.map { it.id }.toSet().size)
    }

    private fun separatedPrayers(book: ServiceBook): List<BookBlock> = book.blocks.windowed(2)
        .filter { (label, _) -> label.kind == BookBlockKind.RUBRIC && label.original == PREFIX }
        .map { it[1] }

    private data class Source(val resource: String, val key: ServiceBookKey, val count: Int)
    private fun document(source: Source) = Jsoup.parse(requireNotNull(javaClass.getResourceAsStream(source.resource))
        .bufferedReader().use { it.readText() })
    private fun sourceParagraphs(document: Document): List<String> = document
        .select(".article-single-content.main-page-content p").filter { paragraph ->
            BookText.clean(paragraph.children().firstOrNull()?.text().orEmpty()) == PREFIX &&
                BookText.clean(paragraph.text()).startsWith("$PREFIX ")
        }.map { BookText.clean(it.text()) }
    private fun targetParagraph(document: Document) = document.select(".article-single-content.main-page-content p")
        .first { BookText.clean(it.text()).startsWith("$PREFIX ") }

    private companion object {
        const val PREFIX = "И священник возглашает:"
        const val PUBLISHED_PAIR = """<table><tr>
<td valign="top" width="50%"><span class="k">Возглашение: </span>Яко подобает Тебе всякая слава, честь и поклонение, Отцу, и Сыну, и Святому Духу, ныне и присно, и во веки веков.</td>
<td valign="top" width="50%"><span class="k">Возглас:</span> Ибо Тебе подобает вся слава, честь и поклонение, Отцу и Сыну и Святому Духу, ныне и всегда, и во веки веков.</td>
</tr></table>"""
    }
}
