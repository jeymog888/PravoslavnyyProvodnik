package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey

class BookRussianRubricClassificationTest {
    private val provenance: JsonObject = requireNotNull(javaClass.getResourceAsStream("/book/rubric-classification-provenance.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    private val directions get() = provenance.getAsJsonArray("directions").map { it.asJsonObject }
    private fun sourceHtml(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/$name"))
        .bufferedReader().use { it.readText() }
    private fun JsonObject.text(name: String) = get(name).asString
    private fun paragraph(document: Document, text: String) = document.select(".article-single-content.main-page-content p")
        .single { BookText.clean(it.text()) == text }

    @Test fun threeRealDatesKeepEverySourceWordAndPlaceTheDirectionsInTheirOriginalChapters() {
        assertEquals(27, directions.size)
        assertEquals(18, directions.count { it.get("isAltarText").asBoolean })
        for (source in provenance.getAsJsonArray("documents").map { it.asJsonObject }) {
            val date = source.text("date")
            val html = sourceHtml(source.text("fixture"))
            assertEquals(date, source.text("fixtureSha256"), BookText.sha256(html))
            val document = Jsoup.parse(html)
            val key = ServiceBookKey(date)
            assertEquals(source.text("sourceUrl"), key.sourceUrl())
            val book = ServiceBookParser().parse(document, key, 12345)
            assertEquals(date, source.get("blockCount").asInt, book.blocks.size)
            assertEquals("No removed, inserted or reordered original words: $date", source.text("originalTextSha256"),
                BookText.sha256(book.blocks.joinToString(" ") { it.original }))
            for (direction in directions) {
                val text = direction.text("original")
                val element = paragraph(document, text)
                val altar = element.parents().any { parent -> parent.tagName() == "details" &&
                    parent.selectFirst("summary")?.text()?.let(BookText::normalized) == "в алтаре" }
                assertEquals(text, direction.get("isAltarText").asBoolean, altar)
                val block = book.blocks.single { it.original == text }
                assertEquals(text, BookBlockKind.RUBRIC, block.kind)
                assertEquals(text, text, block.russian)
                assertEquals(text, text, block.meaning)
                assertEquals(text, altar, block.isAltarText)
                assertNull(text, block.translationSource)
                assertNull(text, block.speaker)
                assertEquals(text, direction.text("chapterId"), book.chapters.single { block in it.blocks }.id)
                // A quoted chant or a rubric naming a troparion is not a new chapter or prayer.
                assertFalse(text, book.chapters.any { BookText.normalized(it.title) == BookText.normalized(text) })
            }
            assertTrue(date, ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun aPrayerBeforeOrAfterADirectionCannotBecomeAnAlreadyRussianRubric() {
        val prayer = "Господи, помилуй."
        for (before in listOf(false, true)) {
            val document = Jsoup.parse(sourceHtml("liturgy-2026-11-12.html"))
            val mixed = directions.map { direction ->
                val text = direction.text("original")
                (if (before) "$prayer $text" else "$text $prayer").also { paragraph(document, text).text(it) }
            }
            val book = ServiceBookParser().parse(document, ServiceBookKey("2026-11-12"), 12345)
            for (text in mixed) {
                val block = book.blocks.single { it.original == text }
                assertEquals(text, BookBlockKind.PRAYER, block.kind)
                assertNull("Unverified mixed text must not claim a Russian translation: $text", block.russian)
            }
            assertTrue(ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun negationAndUnknownActionDoNotMatchTheVerifiedWholeDirection() {
        val document = Jsoup.parse(sourceHtml("liturgy-2026-11-12.html"))
        val replacements = mapOf(
            "И открываются Царские врата." to "И не открываются Царские врата.",
            "И раскрывает антиминс священник." to "И закрывает антиминс священник.")
        replacements.forEach { (original, changed) -> paragraph(document, original).text(changed) }
        val book = ServiceBookParser().parse(document, ServiceBookKey("2026-11-12"), 12345)
        replacements.values.forEach { changed ->
            val block = book.blocks.single { it.original == changed }
            assertEquals(changed, BookBlockKind.PRAYER, block.kind)
            assertNull(changed, block.russian)
        }
    }

    @Test fun theSamePublishedDirectionsRemainDirectionsInThePaschalOrder() {
        val document = Jsoup.parse(sourceHtml("liturgy-2026-04-12.html"))
        val book = ServiceBookParser().parse(document, ServiceBookKey("2026-04-12"), 12345)
        val actual = directions.map { it.text("original") }.filter { text -> book.blocks.any { it.original == text } }
        assertEquals(26, actual.size)
        actual.forEach { text ->
            val block = book.blocks.single { it.original == text }
            assertEquals(text, BookBlockKind.RUBRIC, block.kind)
            assertEquals(text, text, block.russian)
        }
        assertTrue(ServiceBookIntegrity.valid(book))
    }
}
