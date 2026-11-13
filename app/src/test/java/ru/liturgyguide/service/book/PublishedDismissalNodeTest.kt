package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class PublishedDismissalNodeTest {
    private fun document(name: String): Document = Jsoup.parse(requireNotNull(
        javaClass.classLoader?.getResource(name)).readText())
    private fun parse(document: Document): ServiceBook {
        fun selected(id: String) = document.selectFirst("#$id option[selected]")?.attr("value")
        val kind = WorshipKind.entries.single { it.queryValue == selected("bg_worship_type") }
        return ServiceBookParser().parse(document, ServiceBookKey(
            requireNotNull(document.selectFirst("#bg_worships_setDay")).attr("value"), kind,
            selected("bg_worships_church") ?: "20"), 12345)
    }
    private fun dismissalText(document: Document): String = requireNotNull(document.selectFirst("h3:has(a#otpust)"))
        .nextSibling().let { require(it is TextNode); BookText.clean(it.text()) }

    @Test fun fourWholeRealDismissalsArePrayerWithoutInventedSpeakerOrLostWords() {
        for (name in listOf("prayer-dom/cross-matins.html", "prayer-dom/cross-vespers.html",
            "prayer-dom/sunday-matins.html", "book/matins-2026-04-12.html")) {
            val document = document(name)
            val original = dismissalText(document)
            val htmlBefore = document.outerHtml()
            val book = parse(document)
            val dismissal = book.chapters.single { BookText.normalized(it.title) == "отпуст" }
            val block = dismissal.blocks.single { it.original == original }
            assertEquals(name, BookBlockKind.PRAYER, block.kind)
            assertNull(name, block.speaker)
            assertFalse(name, block.isAltarText)
            assertNull("Slavonic must not acquire a false Russian copy", block.russian)
            assertFalse(dismissal.blocks.any { it.original == "Отпуст" || it.original == "Многолетие" })
            assertTrue(ServiceBookIntegrity.valid(book))
            assertEquals("Parsing may not mutate the published input", htmlBefore, document.outerHtml())
        }
    }

    @Test fun anInterveningUnfilledPatronalSlotDoesNotBecomeAWholePrayer() {
        val document = document("book/matins-2026-11-15.html")
        val heading = requireNotNull(document.selectFirst("h3:has(a#otpust)"))
        assertTrue(requireNotNull(heading.parent()).select("em").any { it.text().contains("Престольный праздник") })
        val raw = dismissalText(document)
        val book = parse(document)
        val first = book.chapters.single { BookText.normalized(it.title) == "отпуст" }.blocks.first { it.original == raw }
        assertEquals(BookBlockKind.RUBRIC, first.kind)
        assertFalse(book.blocks.any { it.kind == BookBlockKind.PRAYER && it.original == "Престольный праздник" })
    }

    @Test fun anUnmarkedRegionOrAnAltarRegionCannotGainTheWholeDismissalProfile() {
        for (mutation in listOf<(Document) -> Unit>(
            { requireNotNull(it.selectFirst("h3 a#otpust")).removeAttr("id") },
            { requireNotNull(it.selectFirst("h3:has(a#otpust)")).text("Другое указание") },
            { requireNotNull(it.selectFirst("h3:has(a#otpust)")?.parent()).removeClass("variable_text") },
            {
                val region = requireNotNull(it.selectFirst("h3:has(a#otpust)")?.parent())
                region.wrap("<details></details>")
                requireNotNull(region.parent()).prependElement("summary").text("В алтаре")
            }
        )) {
            val document = document("prayer-dom/sunday-matins.html")
            val raw = dismissalText(document)
            mutation(document)
            assertEquals(BookBlockKind.RUBRIC, parse(document).blocks.single { it.original == raw }.kind)
        }
    }
}
