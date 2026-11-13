package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

/** Frozen source oracle + actual accepted archive; does not derive expected text from the catalog. */
class FeoriaCanonTranslationsTest {
    private val gson = Gson()
    private fun text(path: String) = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
    private val oracle by lazy { JsonParser.parseString(text("russian-coverage-12/canon-105-oracle.json")).asJsonObject }
    private val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    // Pin this historical canon/common delta to its independently approved previous126.
    private val own = WeekdayAntiphonFixtures.previousOwn126
    private val directory = Files.createTempDirectory("feoria-canon-12-").toFile()
    private val archiveText by lazy { text("legacy-presentation/matins-accepted-unchanged.json") }
    private val archive by lazy { gson.fromJson(archiveText, ServiceBook::class.java) }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun all105CompletePairsAndProvenanceMatchThePinnedSourceOracle() {
        assertEquals(4, FeoriaTranslationCatalog.entries.take(126).maxOf { it.revision })
        assertEquals(126, own.size)
        assertEquals(105, rows.size)
        assertEquals(105, FeoriaCanonTranslations.records.size)
        assertEquals(mapOf("cross-tone6-friday" to 40,
            "apostles-stachys-amplias-urban-narcissus-apelles-aristobulus-tone1" to 32,
            "martyr-epimachus-tone4" to 29, "katavasia-tone4-four-odes" to 4),
            rows.groupingBy { it["cycleId"].asString }.eachCount())
        assertEquals(108, rows.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(2254, rows.sumOf { it["originalWords"].asInt })
        assertEquals(2307, rows.sumOf { it["originalWords"].asInt * it["occurrences"].asJsonArray.size() })
        val records = FeoriaCanonTranslations.records.associateBy { it.entry.id }
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString
            val russian = row["russian"].asString
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(3, record.entry.revision)
            assertEquals(row["source"].asJsonObject["url"].asString, record.entry.originalSourceUrl)
            assertEquals(FeoriaCanonTranslations.SOURCE_ARCHIVE_SHA256, BookText.sha256(archiveText))
            val pair = requireNotNull(own.find(original))
            assertEquals(russian, pair.russian); assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, pair.source)
            assertTrue(FeoriaTranslationCatalog.recognizes(original, pair))
            assertTrue(FeoriaCanonTranslations.recognizes(original, pair))
            assertNull(pair.meaning)
            for (occurrence in row["occurrences"].asJsonArray) {
                val block = archive.blocks.single { it.id == occurrence.asJsonObject["blockId"].asString }
                assertEquals(original, block.original)
                assertEquals(BookBlockKind.PRAYER, block.kind)
                assertFalse(BookVisibilityScope.of(archive).isPrivate(block))
                assertNull(block.russian)
            }
        }
    }

    @Test fun previousElevenPairsRemainByteForByteAndRevisionForRevision() {
        val before = gson.fromJson(text("russian-coverage-12/previous11-approved.json"),
            Array<FeoriaTranslationCatalog.Entry>::class.java).toList()
        assertEquals(11, before.size)
        assertEquals(before, FeoriaTranslationCatalog.entries.take(11))
        assertEquals(126, FeoriaTranslationCatalog.entries.take(126).map { BookTranslationIndex.normal(it.original) }.distinct().size)
        assertTrue(FeoriaTranslationCatalog.entries.all { entry ->
            FeoriaTranslationCatalog.recognizes(entry.original,
                BookTranslation(entry.russian, BookTranslationIndex.FEORIA_TRANSLATION_SOURCE))
        })
    }

    @Test fun changedBodiesNamesNegationsPrefixesAndFraudulentRussianNeverInheritTheTranslation() {
        for (row in rows) {
            val original = row["original"].asString
            val pair = requireNotNull(own.find(original))
            for (changed in listOf("Не $original", "$original Нет", original.substringBeforeLast(' '),
                "Хор: $original", "Глас 6: $original", original + " (дважды)")) {
                assertNull(row["id"].asString + ": " + changed, own.find(changed))
                assertFalse(FeoriaTranslationCatalog.recognizes(changed, pair))
            }
            assertFalse(FeoriaTranslationCatalog.recognizes(original, pair.copy(russian = pair.russian + " Не так")))
            assertFalse(FeoriaTranslationCatalog.recognizes(original, pair.copy(meaning = "Объяснение вместо перевода")))
            for (source in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.copy(url = "https://example.org/other-edition"))) {
                assertFalse(FeoriaCanonTranslations.recognizes(original, pair.copy(source = source)))
                assertFalse(FeoriaTranslationCatalog.recognizes(original, pair.copy(source = source)))
            }
            assertNull(index(original, pair.russian + " Поддельная редакция").find(original))
            assertNull(BookTranslationIndex.decode(index(original, pair.russian + " Иная редакция").encode()).find(original))
        }
        val named = rows.first { it["original"].asString.contains("Епима́х") }["original"].asString
        assertNull(own.find(named.replace("Епима́х", "Гео́ргий")))
        val negated = rows.first { it["original"].asString.contains("не отверго́стеся") }["original"].asString
        assertNull(own.find(negated.replace("не отверго́стеся", "отверго́стеся")))
    }

    @Test fun actualArchiveGains108CanonBlocksAndRetainsTheEightPublishedIrmosPairs() {
        val baseline = previousEdition()
        val beforeSerialized = gson.toJson(baseline)
        val after = requireNotNull(SavedBookEnrichment.apply(baseline, own))
        val expected = rows.flatMap { row -> row["occurrences"].asJsonArray.map {
            it.asJsonObject["blockId"].asString to row["russian"].asString
        } }.toMap()
        assertEquals(108, expected.size)
        val common = CommonTranslationFixtures.archiveExpected.associate { row ->
            row["blockId"].asString to CommonTranslationFixtures.rowsById.getValue(row["itemId"].asString)["russian"].asString
        }
        assertEquals(3, common.size)
        assertTrue(expected.keys.intersect(common.keys).isEmpty())
        val changed = baseline.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(108, changed.count { it.first.id in expected })
        assertEquals(3, changed.count { it.first.id in common })
        assertEquals(111, changed.size)
        assertEquals(expected.keys + common.keys, changed.map { it.first.id }.toSet())
        for ((old, next) in changed) {
            assertEquals(old.copy(russian = (expected + common).getValue(old.id), translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), next)
        }
        val publishedBefore = baseline.blocks.filter { it.translationSource in BookPublishedIrmos6.SOURCES }
        assertEquals(8, publishedBefore.size)
        assertEquals(publishedBefore, after.blocks.filter { it.id in publishedBefore.map(BookBlock::id) })
        assertEquals(beforeSerialized, gson.toJson(baseline))
        assertEquals(baseline, after.copy(chapters = baseline.chapters, sources = baseline.sources, contentHash = baseline.contentHash))
        assertTrue(ServiceBookIntegrity.valid(after))
        assertNull(SavedBookEnrichment.apply(after, own))
        val repeated = rows.filter { it["occurrences"].asJsonArray.size() > 1 }
        assertEquals(3, repeated.size)
        repeated.forEach { row -> assertEquals(2, after.blocks.count { it.original == row["original"].asString && it.russian == row["russian"].asString }) }
        // Synthetic second civil date tests reuse identity, not a claim about that day's actual service.
        val otherKey = baseline.key.copy(date = "2026-11-20")
        val moved = baseline.copy(key = otherKey, sourceUrl = otherKey.sourceUrl(), calendarDay = null)
        val movedCandidate = requireNotNull(SavedBookEnrichment.apply(moved, own))
        expected.forEach { (id, russian) -> assertEquals(russian, movedCandidate.blocks.single { it.id == id }.russian) }
    }

    @Test fun localCandidateAndExplicitAcceptanceKeepBothOfflineEditionsAndTheOriginalArchive() = runBlocking {
        val baseline = previousEdition()
        val oldBytes = archiveText.toByteArray()
        val cache = FileBookCache(directory)
        assertTrue(cache.write(baseline))
        val disk = directory.listFiles()!!.single { it.name.endsWith("-${baseline.contentHash}.json") }
        val baselineBytes = disk.readBytes()
        val row = rows.first()
        val id = row["occurrences"].asJsonArray.first().asJsonObject["blockId"].asString
        val chapter = baseline.chapters.single { c -> c.blocks.any { it.id == id } }
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val position = BookReadingPosition(baseline.key, baseline.contentHash, "canon12", id, chapter.id,
            fraction = .35f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        val repository = ServiceBookRepository(cache, ScheduleTransport { error("Own catalog must work offline") }, translations = own)
        val candidate = requireNotNull(repository.enrichSaved(baseline, allowNetwork = false)).book
        assertEquals(position, store.last("canon12"))
        assertEquals(baseline, cache.readVersion(baseline.key, position.contentHash))
        assertArrayEquals(baselineBytes, disk.readBytes())
        assertTrue(store.acceptVersion(baseline, candidate, "canon12", position))
        assertEquals(position.copy(contentHash = candidate.contentHash, fraction = 0f), store.last("canon12"))
        val reopened = FileBookCache(directory)
        assertEquals(candidate, reopened.readVersion(candidate.key, candidate.contentHash))
        assertEquals(baseline, reopened.readVersion(baseline.key, baseline.contentHash))
        assertArrayEquals(oldBytes, archiveText.toByteArray())
        assertArrayEquals(baselineBytes, disk.readBytes())
        assertNull(repository.enrichSaved(candidate, allowNetwork = false))
    }

    private fun previousEdition(): ServiceBook {
        val previous = gson.fromJson(text("russian-coverage-12/previous11-approved.json"), Array<FeoriaTranslationCatalog.Entry>::class.java)
        var index = BookTranslationIndex.empty()
        previous.forEach { index = index.plus(index(it.original, it.russian)) }
        for ((source, file) in listOf(BookPublishedIrmos6.SATURDAY to "great-saturday.html", BookPublishedIrmos6.FRIDAY to "great-friday.html")) {
            index = index.plus(BookTranslationIndex.fromParallelPage(Jsoup.parse(text("book/published-russian/$file"), source.url), source))
        }
        return requireNotNull(SavedBookEnrichment.apply(archive, index))
    }
    private fun index(original: String, russian: String): BookTranslationIndex = BookTranslationIndex.decode(JsonArray().apply {
        add(JsonObject().apply {
            addProperty("original", original); addProperty("russian", russian)
            addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
            addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
        })
    }.toString())
}
