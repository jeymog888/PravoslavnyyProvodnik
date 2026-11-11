package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.WorshipKind

/** Actual published original DOM, synthetic Russian bodies: this tests boundaries, not a new translation. */
class BookPairedStructureTest {
    private val source = BookTranslationIndex.TIMROT_FULL_SOURCE
    private val prayer = "О благорастворении воздухов, Господу помолимся."
    private val russian = "Тестовый русский текст."
    private fun row(a: String, b: String) = "<tr><td>$a</td><td>$b</td></tr>"
    private fun index(a: String, b: String) = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row(a, b)}</table>"), source)
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/translation-paired-structure-$name.html"))
        .bufferedReader().use { Jsoup.parse(it.readText()) }
    private fun body(cell: Element): String = cell.select("p").singleOrNull()?.text() ?: cell.clone().apply { select("span.n").remove() }.text()

    @Test fun actualPublishedBoundariesKeepEachWholePrayerAndAllPreviouslyAvailableWholeCells() {
        for ((name, source) in listOf("full" to source, "short" to BookTranslationIndex.TIMROT_SOURCE)) {
            val document = fixture(name)
            val index = BookTranslationIndex.fromParallelPage(document, source)
            for (row in document.select("tr")) {
                val cells = row.children()
                val original = body(cells[0])
                val expected = body(cells[1])
                assertEquals("$name row ${row.attr("data-source-row")}", expected, index.find(original)?.russian)
                assertEquals(source, index.find(original)?.source)
                assertEquals(BookText.withoutSpeaker(cells[1].text()).second, index.find(cells[0].text())?.russian)
                assertNull(index.find(original.replaceFirst(" ", " не ")))
            }
        }
    }

    @Test fun tripleResponsesMustHaveTheSameSeparateTrailingMarkerBeforeThePrayer() {
        val left = "<span class=k>Лик:</span> Господи, помилуй, <span class=k>трижды.</span><p>$prayer</p>"
        val right = "<span class=k>Хор:</span> Господи, помилуй <span class=k>(3)</span>.<p>$russian</p>"
        assertEquals(russian, index(left, right).find(prayer)?.russian)
        for (changed in listOf(
            right.replace("(3)", "(12)"), right.replace("<span class=k>(3)</span>", ""),
            right.replace("<span class=k>(3)</span>", "(3)"),
            right.replace("(3)", "(3) и еще раз"),
            right.replace("Хор:", "Диакон:"), right.replace("Господи, помилуй", "Подай, Господи"),
            right.replace("<span class=k>(3)</span>.<p>", ".<p><span class=k>(3)</span>"),
            right.replace("<span class=k>(3)</span>", "<span class=k><span>(3)</span></span>"),
            right.replace("<span class=k>(3)</span>", "<span class=k>(3)</span><span class=k>(3)</span>"),
            right + "<p>Другой текст.</p>", right + "Другой текст."
        )) assertNull(changed, index(left, changed).find(prayer))
        val prefixBeforeWords = right.replace("Господи, помилуй <span class=k>(3)</span>", "<span class=k>(3)</span> Господи, помилуй")
        assertNull(index(left, prefixBeforeWords).find(prayer))
        assertNull(index(left.replace("трижды.", "(12)"), right.replace("(3)", "(12)")).find(prayer))
    }

    @Test fun amenBoundaryPreservesPrayerNamesAndDoesNotAcceptCrossedRepliesOrParagraphs() {
        val original = "Еще молимся о упокоении душ усопших рабов Божиих имярек, и о прощении."
        val left = "<span class=k>Лик:</span> Аминь.<p>Еще молимся о упокоении душ усопших рабов Божиих <span class=k>имярек,</span> и о прощении.</p>"
        val right = "<span class=k>Хор:</span> Аминь.<p>Тестовый текст <span class=k>(имена)</span> и о прощении.</p>"
        assertEquals("Тестовый текст (имена) и о прощении.", index(left, right).find(original)?.russian)
        assertNull(index(left, right).find(original.replace("имярек, ", "")))
        for (changed in listOf(
            right.replace("Аминь.", "Господи, помилуй."),
            right.replace("Хор:", "Диакон:"), right.replace("<p>", "<p>Диакон: "),
            right.replace("Аминь.", "Аминь.<span class=k>(3)</span>"),
            right + "<p>Другая молитва.</p>", "<p>$russian</p><span class=k>Хор:</span> Аминь."
        )) assertNull(index(left, changed).find(original))
    }

    @Test fun headingMustBeTheOnlyDirectLeadingH3AndAgreeInBothColumns() {
        val left = "<h3>И ныне, Богородичен:</h3><p>$prayer</p>"
        val right = "<h3>И ныне, Богородичен</h3><p>$russian</p>"
        assertEquals(russian, index(left, right).find(prayer)?.russian)
        for (changed in listOf(
            right.replace("И ныне, Богородичен", "Кондак, глас 6"), right.replace("<h3>И ныне, Богородичен</h3>", ""),
            right.replace("<h3>", "<h2>").replace("</h3>", "</h2>"),
            "Непустой текст $right", "<p>$russian</p><h3>И ныне, Богородичен</h3>",
            "<div>$right</div>", right + "<h3>Еще заголовок</h3>",
            right.replace("<p>", "<p>Диакон: ")
        )) assertNull(changed, index(left, changed).find(prayer))
        for (unverified in listOf("Неизвестная ремарка", "Псалом 999", "Отпустительный тропарь, глас 6", "И ныне"))
            assertNull(unverified, index("<h3>$unverified</h3>$prayer", "<h3>$unverified</h3>$russian").find(prayer))
        assertNull(index(left, right).find(prayer.replace("благорастворении", "прощении")))
        // Identical headings do not authorize changing speakers while stripping a prefix.
        assertNull(index(left.replace(prayer, "Диакон: $prayer"), right.replace(russian, "Хор: $russian")).find(prayer))
    }

    @Test fun antiphonEditionNumbersAreSymmetricStandaloneAndLeading() {
        val left = "<span class=n>1.</span> $prayer"
        val right = "<span class=n>1.</span> $russian"
        assertEquals(russian, index(left, right).find(prayer)?.russian)
        assertEquals(russian, index(left.replace("1.", "2."), right.replace("1.", "2.")).find(prayer)?.russian)
        for (changed in listOf(
            right.replace("1.", "2."), right.replace("<span class=n>1.</span>", ""),
            right.replace("1.", "1"), right.replace("1.", "12."), right.replace("1.", "Пс 18:5А"),
            right.replace("1.", "<a href='#'>1.</a>"), right.replace("1.", "1. молитва"),
            "Текст $right", "<div>$right</div>", right + "<span class=n>2.</span>",
            right.replace("class=n", "class=k"), right.replace(russian, "Диакон: $russian")
        )) assertNull(changed, index(left, changed).find(prayer))
        // The Creed's one-sided chapter enumeration and biblical references remain part of the source cell.
        assertEquals("1 $russian 2 Еще текст",
            index(prayer, "<span class=n>1</span> $russian <span class=n>2</span> Еще текст").find(prayer)?.russian)
        assertNull(index(left.replace("1.", "Глава 1"), right.replace("1.", "Глава 1")).find(prayer))
    }

    @Test fun derivedHeadingsAndNumbersNeverReplaceAnExistingWholeCellEdition() {
        val whole = row(prayer, "Ранее доступный полный текст.")
        val derived = listOf(
            row("<h3>Отпустительный тропарь, глас 8:</h3>$prayer", "<h3>Отпустительный тропарь, глас 8</h3>$russian"),
            row("<span class=n>1.</span>$prayer", "<span class=n>1.</span>$russian")
        )
        for (part in derived) for (rows in listOf(part + whole, whole + part)) {
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>$rows</table>"), source)
            assertEquals("Ранее доступный полный текст.", index.find(prayer)?.russian)
        }
    }

    @Test fun newRulesPersistWithSourceAndOldVersionCannotSuppressReload() {
        val index = index("<h3>И ныне, Богородичен:</h3>$prayer", "<h3>И ныне, Богородичен</h3>$russian")
        val sources = BookTranslationIndex.requiredSources(WorshipKind.LITURGY).map { it.url }.toSet()
        val previous = BookTranslationCorpus.decode(BookTranslationCorpus(index, sources, version = 5).encode())
        assertFalse(previous.current)
        assertFalse(previous.completeFor(WorshipKind.LITURGY))
        assertEquals(index.find(prayer), previous.index.find(prayer))
        val current = BookTranslationCorpus.decode(BookTranslationCorpus(index, sources).encode())
        assertTrue(current.completeFor(WorshipKind.LITURGY))
        assertEquals(index.find(prayer), current.index.find(prayer))
        assertEquals(source, current.index.find(prayer)?.source)
    }
}
