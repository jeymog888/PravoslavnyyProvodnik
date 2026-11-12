package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey

class BookLeadingMetadataTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/$name"))
        .bufferedReader().use { it.readText() }
    private val provenance = JsonParser.parseString(resource("translation-leading-metadata-provenance.json")).asJsonObject
    private fun JsonObject.text(key: String) = get(key).asString
    private val cases get() = provenance.getAsJsonArray("cases").map { it.asJsonObject }
    private fun source(edition: String) = if (edition == "full") BookTranslationIndex.TIMROT_FULL_SOURCE else BookTranslationIndex.TIMROT_SOURCE
    private fun index(edition: String) = BookTranslationIndex.fromParallelPage(
        Jsoup.parse(resource("translation-leading-metadata-$edition.html"), source(edition).url), source(edition))
    private fun combined() = index("full").plus(index("short"))

    @Test fun threeDatedOrdersKeepAllWordsAndSplitOnlyTheSevenVerifiedParagraphs() {
        assertEquals(7, cases.size)
        assertEquals(5, cases.count { it.text("previousKind") == "RUBRIC" })
        // Reopening the serialized index must preserve the same exact pairs offline.
        val index = BookTranslationIndex.decode(combined().encode())
        for (value in provenance.getAsJsonArray("documents")) {
            val evidence = value.asJsonObject
            val html = resource(evidence.text("fixture"))
            assertEquals(evidence.text("fixtureSha256"), BookText.sha256(html))
            val document = Jsoup.parse(html)
            val key = ServiceBookKey(evidence.text("date"))
            assertEquals(evidence.text("sourceUrl"), key.sourceUrl())
            val book = ServiceBookParser().parse(document, key, 12345, index)
            val present = cases.filter { c -> document.select(".article-single-content.main-page-content p")
                .any { BookText.clean(it.text()) == c.text("bookOriginal") } }
            assertEquals(evidence.get("expectedSplitCount").asInt, present.size)
            // Subsequent verified splits add blocks. Keep this original baseline intact;
            // BookPrayerBoundariesTest checks the exact current count and lossless boundaries.
            assertEquals(evidence.text("originalTextSha256"), BookText.sha256(book.blocks.joinToString(" ") { it.original }))
            for (case in present) {
                val position = book.blocks.indexOfFirst { it.original == case.text("prayer") }
                assertTrue(case.text("id"), position > 0)
                val prayer = book.blocks[position]
                val rubric = book.blocks[position - 1]
                assertEquals(case.text("id"), BookBlockKind.PRAYER, prayer.kind)
                assertEquals(BookBlockKind.RUBRIC, rubric.kind)
                assertEquals(case.text("prefix"), rubric.original)
                assertEquals(case.text("bookOriginal"), "${rubric.original} ${prayer.original}")
                assertEquals(case.get("isAltarText").asBoolean, prayer.isAltarText)
                assertEquals(prayer.isAltarText, rubric.isAltarText)
                assertEquals(case.text("chapterId"), book.chapters.single { prayer in it.blocks }.id)
                assertTrue(book.chapters.single { prayer in it.blocks }.blocks.contains(rubric))
                val translated = requireNotNull(index.find(prayer.original))
                assertEquals(translated.russian, prayer.russian)
                assertEquals(translated.source, prayer.translationSource)
                assertTrue(prayer.russian!!.startsWith("Тестовый русский текст"))
                assertNotEquals(prayer.original, prayer.russian)
                assertEquals(rubric.original, rubric.russian)
            }
            assertEquals(5, present.count { it.text("previousKind") == "RUBRIC" })
            assertTrue(ServiceBookIntegrity.valid(book))
        }
        assertTrue(cases.first().text("prayer").endsWith("Единожды."))
    }

    @Test fun sourceRowsKeepWholeCellPairsAndTheFullEditionWinsForTheSameWholePrayer() {
        for (value in provenance.getAsJsonArray("sources")) {
            val evidence = value.asJsonObject
            val html = resource(evidence.text("fixture"))
            assertEquals(evidence.text("fixtureSha256"), BookText.sha256(html))
            val document = Jsoup.parse(html, evidence.text("sourceUrl"))
            val index = BookTranslationIndex.fromParallelPage(document)
            for (row in document.select("table tr")) {
                val cells = row.children().filter { it.tagName() == "td" }
                val wholeOriginal = BookText.clean(cells[0].text())
                val wholeRussian = BookText.withoutSpeaker(cells[1].text()).second
                assertEquals("Whole source row ${row.attr("data-source-row")}", wholeRussian, index.find(wholeOriginal)?.russian)
            }
        }
        val communion = cases.single { it.text("id") == "prefix-6" }.text("prayer")
        val full = requireNotNull(index("full").find(communion))
        assertEquals(BookTranslationIndex.TIMROT_FULL_SOURCE, full.source)
        for (merged in listOf(index("full").plus(index("short")), index("short").plus(index("full")))) {
            assertEquals(full, merged.find(communion))
            assertEquals(full, BookTranslationIndex.decode(merged.encode()).find(communion))
        }
    }

    @Test fun thePriestsPrayerAfterTheChoirResponseKeepsItsExactEndingAndSource() {
        val case = cases.single { it.text("id") == "prefix-5" }
        val prayer = case.text("prayer")
        assertEquals(BookTranslationIndex.TIMROT_SOURCE, combined().find(prayer)?.source)
        // The full edition actually says "и нам и всем", whereas the dated order says "и нами всем".
        assertNull(index("full").find(prayer))
        val fullDocument = Jsoup.parse(resource("translation-leading-metadata-full.html"))
        // Jsoup preserves an empty repair paragraph before the actual prayer in this source row.
        val alternate = fullDocument.selectFirst("tr[data-case=alternate-vonmi] td")!!
            .select("p").filter { it.hasText() }.single()
        val alternatePrayer = BookPrayerMetadata.split(alternate.text()).prayer
        assertNotEquals(BookText.normalized(prayer), BookText.normalized(alternatePrayer))
        assertNotNull(index("full").find(alternatePrayer))
        assertTrue(prayer.endsWith("и на́ми всем лю́дем."))
    }

    @Test fun changedNegatedOrShortenedPrayerCannotInheritTheFullPublishedTranslation() {
        val index = combined()
        for (case in cases) {
            val prayer = case.text("prayer")
            assertNotNull(case.text("id"), index.find(prayer))
            val changed = listOf("не $prayer", prayer.replaceFirst(" ", " не "), prayer.substringBefore(' '), prayer.dropLast(16))
            for (wrong in changed) {
                val split = BookPrayerMetadata.split("${case.text("prefix")} $wrong")
                assertEquals(BookText.clean(wrong), split.prayer)
                assertNull(case.text("id"), index.find(split.prayer))
            }
            if (case.text("id") == "prefix-0") assertNull(index.find(prayer.removeSuffix("Единожды.").trim()))
        }
    }

    @Test fun onlyAnExactLeadingPrefixWithANonemptyPrayerMaySplit() {
        val prefixes = listOf("В будни:", "Священник же молится:",
            "Перед причастием верных священник по обычаю читает молитвы:",
            "Перед причастием верных священник, по обычаю читает молитвы:")
        val prayer = cases.single { it.text("id") == "prefix-6" }.text("prayer")
        for (prefix in prefixes) {
            val complete = "$prefix $prayer"
            val split = BookPrayerMetadata.split(complete)
            assertEquals(prefix, split.rubric)
            assertEquals(prayer, split.prayer)
            assertEquals(complete, "${split.rubric} ${split.prayer}")
            for (unverified in listOf(prefix, "$prefix   ", "Вчера. $complete", "${prefix.dropLast(1)} $prayer",
                "${prefix.dropLast(1)} не: $prayer", "Неизвестное указание: $prayer")) {
                assertNull(unverified, BookPrayerMetadata.split(unverified).rubric)
                assertEquals(BookText.clean(unverified), BookPrayerMetadata.split(unverified).prayer)
            }
        }
    }
}
