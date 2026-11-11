package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey

class BookPrayerConcordanceTest {
    // The fixtures preserve full public-domain originals from the real dated book and source rows.
    // Russian markers below are intentionally synthetic, not a redistribution of the modern edition.
    private fun cases(name: String): List<JsonObject> = requireNotNull(javaClass.getResourceAsStream("/book/$name.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonArray.map { value -> value.asJsonObject } }
    private fun JsonObject.text(key: String) = get(key).asString
    private fun row(original: String, russian: String) =
        "<tr><td>${Entities.escape(original)}</td><td>${Entities.escape(russian)}</td></tr>"
    private fun marker(case: JsonObject) = "Тестовый русский текст ${case.text("id")}"

    @Test fun fullSourceOriginalsRecoverAllTwentySixRealBookOccurrencesWithTheirOwnProvenance() {
        val cases = cases("translation-exact-originals")
        assertEquals(22, cases.size)
        var index = BookTranslationIndex.empty()
        for ((url, rows) in cases.groupBy { it.text("sourceUrl") }) {
            val document = Jsoup.parse("<table>${rows.joinToString("") { row(it.text("sourceOriginal"), marker(it)) }}</table>", url)
            index = index.plus(BookTranslationIndex.fromParallelPage(document))
        }
        val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html()), ServiceBookKey("2026-11-11"), 12345, index)
        var found = 0
        for (case in cases) {
            val (speaker, prayer) = BookText.withoutSpeaker(case.text("bookOriginal"))
            val matching = book.blocks.filter { it.kind == BookBlockKind.PRAYER &&
                BookText.normalized(it.original) == BookText.normalized(prayer) }
            assertEquals(case.text("id"), case.get("occurrences").asInt, matching.size)
            // An editorial label is retained as the block's speaker, not silently dropped.
            if (speaker != null) assertTrue(case.text("id"), matching.any { it.speaker == speaker })
            matching.forEach {
                assertEquals(case.text("id"), marker(case), it.russian)
                assertEquals(case.text("sourceUrl"), it.translationSource?.url)
            }
            found += matching.size
        }
        assertEquals(26, found)
        assertTrue(ServiceBookIntegrity.valid(book))
        val restored = BookTranslationIndex.decode(index.encode())
        cases.forEach { assertEquals(it.text("sourceUrl"), restored.find(it.text("bookOriginal"))?.source?.url) }
    }

    @Test fun elevenKnownMetadataCasesRetainRubricAndMatchTheEntirePrayer() {
        val cases = cases("translation-metadata-originals")
        assertEquals(11, cases.size)
        val legacy = requireNotNull(javaClass.classLoader?.getResourceAsStream("liturgy_stages.json"))
            .bufferedReader().use { it.readText() }
        var index = BookTranslationIndex.fromAssets(legacy)
        val networkCases = cases.filter { it.text("sourceUrl").startsWith("https://") }
        for ((url, rows) in networkCases.groupBy { it.text("sourceUrl") }) {
            val document = Jsoup.parse("<table>${rows.joinToString("") {
                row(it.text("sourceOriginal"), "${it.text("russianMetadata")} ${marker(it)}")
            }}</table>", url)
            // Test the fetched source pairing independently from overlapping bundled prayers.
            index = BookTranslationIndex.fromParallelPage(document).plus(index)
        }
        val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html()), ServiceBookKey("2026-11-11"), 12345, index)
        for (case in cases) {
            val position = book.blocks.indexOfFirst { it.kind == BookBlockKind.PRAYER &&
                BookText.normalized(it.original) == BookText.normalized(case.text("prayer")) }
            assertTrue(case.text("title"), position >= 0)
            val prayer = book.blocks[position]
            assertEquals(BookText.clean(case.text("prayer")), prayer.original)
            if (case in networkCases) {
                assertEquals(case.text("title"), marker(case), prayer.russian)
                assertEquals(case.text("sourceUrl"), prayer.translationSource?.url)
            } else {
                assertNotNull("Existing bundled translation: ${case.text("title")}", prayer.russian)
                assertEquals(BookTranslationIndex.EDITORIAL_SOURCE, prayer.translationSource)
            }
            val prefix = case.text("bookMetadata")
            if (prefix.isNotEmpty()) {
                assertTrue(position > 0)
                val rubric = book.blocks[position - 1]
                assertEquals(BookBlockKind.RUBRIC, rubric.kind)
                assertEquals(prefix, rubric.original)
                assertEquals(prayer.isAltarText, rubric.isAltarText)
                assertEquals(BookText.clean(case.text("bookOriginal")), "${rubric.original} ${prayer.original}")
            }
        }
        assertTrue(ServiceBookIntegrity.valid(book))
    }

    @Test fun metadataCannotAuthorizeChangedWordsNegationOrShorterPrayer() {
        val prayer = "Приимите, ядите, сие есть Тело Мое, еже за вы ломимое во оставление грехов."
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row("Возглашение: $prayer", "Возвышая голос: Тестовый перевод всей молитвы")}</table>"))
        assertEquals("Тестовый перевод всей молитвы", index.find(prayer)?.russian)
        assertNull(index.find(prayer.replace("есть Тело", "не есть Тело")))
        assertNull(index.find(prayer.replace("Мое", "Твое")))
        assertNull(index.find("Приимите, ядите, сие есть Тело Мое"))
        assertNull(index.find("Неизвестная ремарка: $prayer"))
        val unknown = "Священник благословляет народ: $prayer"
        assertEquals(unknown, BookPrayerMetadata.split(unknown).prayer)
        assertNull(BookPrayerMetadata.split(unknown).rubric)
        assertNull(BookPrayerMetadata.split("Возглашение:").rubric)
    }

    @Test fun russianOnlyMetadataIsRemovedWithoutDroppingPrayerWords() {
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row("Спаси, Боже, люди Твоя.", "Возглас: Тестовый перевод")}</table>"))
        assertEquals("Тестовый перевод", index.find("Спаси, Боже, люди Твоя")?.russian)
    }
}
