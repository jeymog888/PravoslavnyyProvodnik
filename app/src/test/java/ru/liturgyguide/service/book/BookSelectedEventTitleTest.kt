package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

class BookSelectedEventTitleTest {
    private val parser = ServiceBookParser()
    private val key = ServiceBookKey("2026-11-12", WorshipKind.VESPERS)
    private val mainId = "bg_worships_mainEvent"
    private val secondId = "bg_worships_secondEvent"
    private val fullZinovy = "сщмч. Зино́вия Егейскаго (Киликийскаго), епископа, и сестры его мц. Зинови́и (285)"
    private fun document() = Jsoup.parse(BookFixtures.html("vespers-2026-11-12"))

    @Test fun realNovemberTwelveRestoresTheWholePublishedNameWithoutChangingPrayersOrServiceTitle() {
        val document = document()
        val fallback = document.clone().also { it.select("#$mainId, #$secondId").removeAttr("title") }
        val before = parser.parse(fallback, key, 12345)
        val after = parser.parse(document, key, 12345)
        assertTrue(requireNotNull(before.selectedEventTitle).endsWith("..."))
        assertEquals(fullZinovy, after.selectedEventTitle)
        assertEquals(before.title, after.title)
        assertEquals(before.chapters, after.chapters)
        assertEquals(before.sources, after.sources)
        assertEquals(before.mainEventOptions, after.mainEventOptions)
        assertEquals(before.secondEventOptions, after.secondEventOptions)
        assertEquals(before.key, after.key)
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun realNovemberElevenKeepsBothAlreadyCompleteSelectedNames() {
        val book = BookFixtures.book()
        assertEquals("прмц. Анастаси́и Римляныни (III) · прп. Авраа́мия Затворника, пресвитера (ок. 360)", book.selectedEventTitle)
        val stale = Jsoup.parse(BookFixtures.html()).also {
            it.selectFirst("#$mainId")!!.attr("title", "Выбрать основное событие\n$fullZinovy")
        }
        assertEquals(book.selectedEventTitle, parser.parse(stale, ServiceBookKey("2026-11-11"), 12345).selectedEventTitle)
    }

    @Test fun eachExplicitMainAndSecondVariantUsesItsOwnSelectedTooltip() {
        // These controls exercise selection mechanics, not liturgical assembly for this date.
        val mainNames = listOf(fullZinovy,
            "мчч. Акинди́на, Пига́сия, Аффо́ния, Елпидифо́ра, Анемподи́ста и иже с ними (ок. 341-345)")
        val secondNames = listOf("прп. Авраа́мия Затворника, пресвитера (ок. 360)",
            "Священномучеников Константина Юрганова и Анании Аристова")
        for (main in 0..1) for (second in 0..1) {
            val document = document()
            setOptions(document, mainId, "Выбрать основное событие", mainNames, main)
            setOptions(document, secondId, "Выбрать второе событие", secondNames, second)
            val result = parser.parse(document, key.copy(mainEvent = main.toString(), secondEvent = second.toString()), 12345)
            assertEquals("${mainNames[main]} · ${secondNames[second]}", result.selectedEventTitle)
        }
    }

    @Test fun missingMalformedOrUnrelatedTooltipRetainsTheExactSelectedLabel() {
        val expected = document().selectFirst("#$mainId option[selected]")!!.text()
        val invalid = listOf("", "Выбрать основное событие", "Выбрать второе событие\n$fullZinovy",
            "Выбрать основное событие\nпрп. Авраа́мия Затворника, пресвитера (ок. 360)",
            "Выбрать основное событие\n<b>$fullZinovy</b>", "Выбрать основное событие\n$expected",
            "Выбрать основное событие\n$fullZinovy" + "x".repeat(2001),
            "Выбрать основное событие\n$fullZinovy\u0001")
        for (tooltip in invalid) {
            val document = document().also { it.selectFirst("#$mainId")!!.attr("title", tooltip) }
            assertEquals(tooltip.take(80), expected, parser.parse(document, key, 12345).selectedEventTitle)
        }
    }

    @Test fun ambiguousOtherOptionOrTooShortPrefixCannotSupplyAnIdentity() {
        for (otherLabel in listOf(fullZinovy, fullZinovy.take(32) + "...")) {
            val document = document()
            val control = document.selectFirst("#$mainId")!!
            val expected = control.selectFirst("option[selected]")!!.text()
            control.appendElement("option").attr("value", "17").text(otherLabel)
            assertEquals(expected, parser.parse(document, key, 12345).selectedEventTitle)
        }
        val short = document().also { it.selectFirst("#$mainId option[selected]")!!.text("сщмч. Зино́вия...") }
        assertEquals("сщмч. Зино́вия...", parser.parse(short, key, 12345).selectedEventTitle)
    }

    @Test fun unicodeEllipsisIsSupportedAndAnEmptySelectionNeverBorrowsAStaleTooltip() {
        val unicode = document().also {
            val option = it.selectFirst("#$mainId option[selected]")!!
            option.text(option.text().removeSuffix("...") + "…")
        }
        assertEquals(fullZinovy, parser.parse(unicode, key, 12345).selectedEventTitle)
        val empty = document().also {
            val control = it.selectFirst("#$mainId")!!
            control.select("option").removeAttr("selected")
            control.selectFirst("option[value='']")!!.attr("selected", "selected")
        }
        assertNull(parser.parse(empty, key, 12345).selectedEventTitle)
    }

    private fun setOptions(document: Document, id: String, instruction: String, names: List<String>, chosen: Int) {
        val control = document.selectFirst("#$id")!!.empty().attr("title", "$instruction\n${names[chosen]}")
        names.forEachIndexed { index, name ->
            val option = control.appendElement("option").attr("value", index.toString()).text(name.take(32) + "...")
            if (index == chosen) option.attr("selected", "selected")
        }
    }
}
