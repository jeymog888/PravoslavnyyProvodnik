package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey

class BookPrayerBoundariesTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/$name"))
        .bufferedReader().use { it.readText() }
    private val provenance = JsonParser.parseString(resource("translation-prayer-boundaries-provenance.json")).asJsonObject
    private fun JsonObject.text(key: String) = get(key).asString
    private val cases get() = provenance.getAsJsonArray("cases").map { it.asJsonObject }
    private fun source(edition: String) = if (edition == "full") BookTranslationIndex.TIMROT_FULL_SOURCE else BookTranslationIndex.TIMROT_SOURCE
    private fun document(edition: String) = Jsoup.parse(resource("translation-prayer-boundaries-$edition.html"), source(edition).url)
    private fun index(edition: String) = BookTranslationIndex.fromParallelPage(document(edition), source(edition))
    private fun combined() = index("full").plus(index("short"))
    private fun sourceRow(edition: String, row: Int) = requireNotNull(document(edition).selectFirst("tr[data-source-row=$row]"))
    private fun nonemptyParagraph(cell: Element) = cell.select("p").filter { it.hasText() }.single()

    @Test fun threeDatesAndTheAcceptedTempleVariantKeepEveryWordAndSplitTenExactParagraphs() {
        assertEquals(10, cases.size)
        assertEquals(9, cases.count { it.text("previousKind") == "RUBRIC" })
        assertEquals(3, cases.count { it.get("isAltarText").asBoolean })
        val index = BookTranslationIndex.decode(combined().encode())
        val documents = provenance.getAsJsonArray("documents").map { it.asJsonObject }
        assertEquals(setOf("2026-11-11/30", "2026-11-12/30", "2026-11-15/30", "2026-11-12/10"),
            documents.map { "${it.text("date")}/${it.text("churchType")}" }.toSet())
        for (evidence in documents) {
            val html = resource(evidence.text("fixture"))
            assertEquals(evidence.text("fixtureSha256"), BookText.sha256(html))
            val document = Jsoup.parse(html)
            val key = ServiceBookKey(evidence.text("date"), churchType = evidence.text("churchType"))
            assertEquals(evidence.text("sourceUrl"), key.sourceUrl())
            val book = ServiceBookParser().parse(document, key, 12345, index)
            // The following visible-only boundary group adds twelve more blocks;
            // BookVisiblePrayerBoundariesTest verifies that group and every unaffected ID.
            assertEquals(evidence.get("baselineBlockCount").asInt + 10 + 12, book.blocks.size)
            assertEquals(evidence.text("originalTextSha256"), BookText.sha256(book.blocks.joinToString(" ") { it.original }))
            for (case in cases) {
                val paragraph = document.select(".article-single-content.main-page-content p")
                    .single { BookText.clean(it.text()) == case.text("bookOriginal") }
                val leading = requireNotNull(paragraph.children().first())
                assertTrue(case.text("id"), leading.tagName() in setOf("strong", "em"))
                assertEquals(case.text("prefix"), BookText.clean(leading.text()))
                // Later boundaries can expose the same prayer earlier in another chapter.
                // Identify this original occurrence by its chapter and preceding direction.
                val chapter = book.chapters.single { it.id == case.text("chapterId") }
                val position = chapter.blocks.indices.firstOrNull { index -> index > 0 &&
                    chapter.blocks[index].original == case.text("prayer") &&
                    chapter.blocks[index - 1].original == case.text("prefix") } ?: -1
                assertTrue(case.text("id"), position > 0)
                val prayer = chapter.blocks[position]
                val rubric = chapter.blocks[position - 1]
                assertEquals(BookBlockKind.PRAYER, prayer.kind)
                assertEquals(BookBlockKind.RUBRIC, rubric.kind)
                assertEquals(case.text("prefix"), rubric.original)
                assertEquals(case.text("bookOriginal"), "${rubric.original} ${prayer.original}")
                assertEquals(case.get("isAltarText").asBoolean, prayer.isAltarText)
                assertEquals(prayer.isAltarText, rubric.isAltarText)
                assertEquals(case.text("chapterId"), chapter.id)
                assertTrue(chapter.blocks.contains(rubric))
                assertEquals(rubric.original, rubric.russian)
                if (case.get("hasExactTranslation").asBoolean) {
                    val expected = requireNotNull(index.find(case.text("prayer")))
                    assertEquals(expected.russian, prayer.russian)
                    assertEquals(expected.source, prayer.translationSource)
                    assertTrue(prayer.russian!!.startsWith("Тестовый русский текст"))
                    assertNotEquals(prayer.original, prayer.russian)
                } else {
                    assertNull(case.text("id"), prayer.russian)
                    assertNull(case.text("id"), prayer.translationSource)
                }
            }
            // The separate dismissal investigation is deliberately outside this change.
            val dismissal = book.chapters.single { BookText.normalized(it.title) == "отпуст" }.blocks
            val previousDismissal = evidence.getAsJsonArray("baselineDismissalBlocks").map { it.asJsonObject }
            assertEquals(previousDismissal.size, dismissal.size)
            previousDismissal.zip(dismissal).forEach { (previous, current) ->
                assertEquals(previous.text("id"), current.id)
                assertEquals(previous.text("kind"), current.kind.name)
                assertEquals(previous.text("originalSha256"), BookText.sha256(current.original))
            }
            assertTrue(ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun everyOldWholeCellPairSurvivesWithOnlyVerifiedLeadingDirectionsRemoved() {
        val old = BookTranslationIndex.decode(resource("translation-prayer-boundaries-v7.json"))
        val current = combined()
        val prefixes = cases.map { it.text("prefix") }.toSet()
        var cleaned = 0
        for (value in JsonParser.parseString(old.encode()).asJsonArray) {
            val original = value.asJsonObject.get("original").asString
            val before = requireNotNull(old.find(original))
            val after = requireNotNull(current.find(original))
            assertEquals(before.source, after.source)
            assertEquals(before.meaning, after.meaning)
            if (before.russian != after.russian) {
                assertTrue(original, prefixes.any { before.russian == "$it ${after.russian}" })
                cleaned++
            }
        }
        assertEquals(5, cleaned)
        for (edition in listOf("short", "full")) {
            for (row in document(edition).select("table tr")) assertNotNull(index(edition).find(row.children()[0].text()))
        }
        val recovered = current.plus(old)
        val offline = BookTranslationIndex.decode(recovered.encode())
        for (case in cases.filter { it.get("hasExactTranslation").asBoolean })
            assertEquals(current.find(case.text("prayer")), offline.find(case.text("prayer")))
    }

    @Test fun publishedSourceHashesAndTheCompletePairAfterTheChoirResponseAreRetained() {
        for (value in provenance.getAsJsonArray("sources")) {
            val evidence = value.asJsonObject
            assertEquals(evidence.text("fixtureSha256"), BookText.sha256(resource(evidence.text("fixture"))))
        }
        val prayer = cases.single { it.text("id") == "boundary-5" }.text("prayer")
        for ((edition, rowNumber) in listOf("short" to 234, "full" to 340)) {
            val row = sourceRow(edition, rowNumber)
            val left = nonemptyParagraph(row.children()[0])
            val right = nonemptyParagraph(row.children()[1])
            assertEquals("Священник молится:", BookPrayerMetadata.split(left.text()).rubric)
            assertEquals("Священник", BookText.withoutSpeaker(right.text()).first)
            val pair = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row.outerHtml()}</table>"), source(edition))
            assertEquals("Тестовый русский текст $edition-boundary-5", pair.find(prayer)?.russian)
            assertEquals(source(edition), pair.find(prayer)?.source)
        }
        val sameRole = sourceRow("full", 340)
        nonemptyParagraph(sameRole.children()[1]).text("Иерей: Тестовый русский текст full-boundary-5")
        val alias = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${sameRole.outerHtml()}</table>"))
        assertEquals("Тестовый русский текст full-boundary-5", alias.find(prayer)?.russian)
        assertEquals(BookTranslationIndex.TIMROT_FULL_SOURCE, combined().find(prayer)?.source)
    }

    @Test fun differentSpeakersResponsesOrUnknownMetadataCannotAuthorizeAWholePrayerPair() {
        val prayer = cases.single { it.text("id") == "boundary-5" }.text("prayer")
        val negatives: List<Pair<String, (Element) -> Unit>> = listOf(
            "different speaker" to { nonemptyParagraph(it.children()[1]).text("Диакон: Тестовый русский текст full-boundary-5") },
            "unrecognized speaker" to { nonemptyParagraph(it.children()[1]).text("Епископ: Тестовый русский текст full-boundary-5") },
            "unknown composite speaker" to { nonemptyParagraph(it.children()[1]).text("Диакон молится: Тестовый русский текст full-boundary-5") },
            "missing speaker" to { nonemptyParagraph(it.children()[1]).text("Тестовый русский текст full-boundary-5") },
            "unknown metadata" to { nonemptyParagraph(it.children()[0]).selectFirst("span.k")!!.text("Священник громко молится:") },
            "empty prayer" to { nonemptyParagraph(it.children()[0]).text("Священник молится:") },
            "different response" to { it.children()[1].childNodes().filterIsInstance<TextNode>()
                .first { node -> node.text().contains("Аминь") }.text(" Господи, помилуй.") },
            "extra prayer" to { it.children()[0].appendElement("p").text("Еще одна самостоятельная молитва") },
            "changed prayer" to { nonemptyParagraph(it.children()[0]).appendText(" Добавленные слова") }
        )
        for ((name, mutate) in negatives) {
            val row = sourceRow("full", 340)
            mutate(row)
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row.outerHtml()}</table>"))
            assertNull(name, index.find(prayer))
            if (name == "empty prayer") assertNull(index.find("Священник молится:"))
        }
    }

    @Test fun namedEvangelistAndTempleTextCannotInheritTranslationsOfDifferentWords() {
        val index = combined()
        val evangelist = cases.single { it.text("id") == "boundary-0" }.text("prayer")
        val temple = cases.single { it.text("id") == "boundary-6" }.text("prayer")
        assertTrue(BookText.normalized(evangelist).contains("евангелиста луки"))
        assertTrue(BookText.normalized(temple).contains("святый храм сей"))
        val generic = sourceRow("full", 196).children()[0].text()
        val city = BookPrayerMetadata.split(sourceRow("full", 372).children()[0].text()).prayer
        assertTrue(BookText.normalized(generic).contains("имярек"))
        assertTrue(BookText.normalized(city).contains("град сей в немже живем"))
        assertNotNull(index.find(generic))
        assertNotNull(index.find(city))
        assertNull(index.find(evangelist))
        assertNull(index.find(temple))
    }

    @Test fun communionInvitationUsesItsWholePublishedShortEditionAndPreservesTheDirection() {
        val case = cases.single { it.text("id") == "boundary-9" }
        val parts = BookPrayerMetadata.split(case.text("bookOriginal"))
        assertEquals(case.text("prefix"), parts.rubric)
        assertEquals("Со страхом Божиим и верою приступите.", parts.prayer)
        assertEquals(case.text("bookOriginal"), "${parts.rubric} ${parts.prayer}")
        assertEquals(BookTranslationIndex.TIMROT_SOURCE, combined().find(parts.prayer)?.source)
        assertEquals("Тестовый русский текст short-boundary-9", combined().find(parts.prayer)?.russian)
        // The full edition's other, unreviewed Church Slavonic direction remains whole.
        assertNull(BookPrayerMetadata.split(sourceRow("full", 454).children()[0].text()).rubric)
    }

    @Test fun exactLeadingMetadataDoesNotAuthorizeChangedNegatedOrShortenedPrayerWords() {
        val index = combined()
        for (case in cases.filter { it.get("hasExactTranslation").asBoolean }) {
            val prayer = case.text("prayer")
            assertNotNull(index.find(prayer))
            for (wrong in listOf("не $prayer", prayer.replaceFirst(" ", " не "), prayer.substringBefore(' '), prayer.dropLast(16))) {
                val parts = BookPrayerMetadata.split("${case.text("prefix")} $wrong")
                assertEquals(BookText.clean(wrong), parts.prayer)
                assertNull(case.text("id"), index.find(parts.prayer))
            }
        }
        for (prefix in cases.map { it.text("prefix") }.distinct()) {
            for (unknown in listOf(prefix, "$prefix   ", "Вчера. $prefix Господи, помилуй.",
                "${prefix.dropLast(1)} Господи, помилуй.", "${prefix.dropLast(1)} не: Господи, помилуй.")) {
                assertNull(unknown, BookPrayerMetadata.split(unknown).rubric)
                assertEquals(BookText.clean(unknown), BookPrayerMetadata.split(unknown).prayer)
            }
        }
    }
}
