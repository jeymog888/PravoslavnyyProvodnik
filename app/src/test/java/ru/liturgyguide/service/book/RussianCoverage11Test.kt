package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.model.ServiceBook

/** Independent genuine originals and reviewed Russian, not values read back from the catalog. */
class RussianCoverage11Test {
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
    private val published by lazy { JsonParser.parseString(resource("russian-coverage-11/published-first-batch.json")).asJsonObject["pairs"].asJsonArray }
    private val drafts by lazy { JsonParser.parseString(resource("russian-coverage-11/feoria-refrains-draft.json")).asJsonObject["items"].asJsonArray }
    private fun book(kind: String): ServiceBook = Gson().fromJson(resource("legacy-presentation/$kind-accepted-unchanged.json"), ServiceBook::class.java)
    private val calendar by lazy { requireNotNull(PublishedCalendarSupplements.decode(resource("russian-coverage-11/calendar-2026-11-13.json"), "2026-11-13")) }

    @Test fun fourPublishedBodiesCoverSevenRealArchivedBlocksWithoutLosingTheirTone() {
        val books = mapOf("matins" to book("matins"), "vespers" to book("vespers"))
        var count = 0
        for (value in published) {
            val row = value.asJsonObject
            val original = row["archivedOriginal"].asString
            assertEquals(row["archivedOriginalSha256"].asString, BookText.sha256(original))
            for (occurrence in row["occurrenceProvenance"].asJsonArray) {
                val evidence = occurrence.asJsonObject
                val archived = books.getValue(evidence["book"].asString)
                assertEquals(evidence["bookContentHash"].asString, archived.contentHash)
                val block = archived.blocks.single { it.id == evidence["blockId"].asString }
                assertEquals(original, block.original)
                assertNull("This is an actual untranslated archived block", block.russian)
                val translation = requireNotNull(calendar.translations.find(block.original))
                assertEquals(row["verifiedMetadataPrefix"].asString + row["russian"].asString, translation.russian)
                assertEquals(row["publicSourceUrl"].asString, translation.source.url)
                count++
            }
        }
        assertEquals(7, count)
    }

    @Test fun metadataCannotDiscardAnotherLabelCountChangedSaintOrUntrustedSource() {
        val row = published.first().asJsonObject
        val body = row["archivedBody"].asString
        val negative = listOf("Глас 0: $body", "Глас 9: $body", "Глас 13: $body", "Глас 3 $body", "Глас 3: ",
            "Глас 3: Глас 3: $body", "Глас 3: Хор: $body", "Глас 3: Припев: $body",
            "Глас 3: $body (дважды)", "Глас 3: " + body.replace("Апостоли", "Мученики"),
            "Глас 3: " + body.substringBeforeLast(' '))
        negative.forEach { assertNull(it, calendar.translations.find(it)) }
        for (source in listOf(BookSource("Азбука", "https://azbyka.ru/days/2026-11-13"),
            BookSource("Азбука веры: календарь и API", "https://azbyka.ru.evil.example/days/2026-11-13"),
            BookSource("Азбука веры: календарь и API", "http://azbyka.ru/days/2026-11-13"),
            BookSource("Азбука веры: календарь и API", "https://azbyka.ru/days/2026-13-13"))) {
            assertNull(source.toString(), index(row["sourceOriginal"].asString, row["russian"].asString, source).find("Глас 3: $body"))
        }
    }

    @Test fun threeReviewedRefrainsFillFortyEightMissingBlocksInANewCandidateOnly() {
        val before = book("matins")
        val serializedBefore = Gson().toJson(before)
        val own = BookTranslationIndex.fromFeoriaCatalog()
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        var count = 0
        for (value in drafts) {
            val row = value.asJsonObject
            val original = row["original"].asString
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            val old = before.blocks.filter { it.original == original }
            assertEquals(16, old.size)
            assertTrue(old.all { it.russian == null })
            for (block in old) {
                val next = after.blocks.single { it.id == block.id }
                assertEquals(row["russian"].asString, next.russian)
                assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, next.translationSource)
                assertEquals(block.copy(russian = next.russian, translationSource = next.translationSource), next)
                count++
            }
        }
        assertEquals(48, count)
        assertEquals(before.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
        assertEquals(before.calendarDay, after.calendarDay)
        assertEquals(before.enrichment, after.enrichment)
        assertEquals(serializedBefore, Gson().toJson(before))
        assertNotEquals(before.contentHash, after.contentHash)
    }

    private fun index(original: String, russian: String, source: BookSource): BookTranslationIndex =
        BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", original); addProperty("russian", russian)
            addProperty("source", source.title); addProperty("url", source.url)
        }) }.toString())
}
