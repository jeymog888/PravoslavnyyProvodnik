package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import java.io.File
import java.nio.file.Files

/** Independent semantic-final data and frozen previous126, never read back from new production entries. */
internal object WeekdayAntiphonFixtures {
    private const val ORACLE_PATH = "app/src/androidTest/assets/russian-weekday-antiphons/oracle.json"
    const val ORACLE_SHA256 = "ae58a078ec768a1082a956066e9d49bf17958f9d918e8a5c894df9a3831b11d0"
    fun sourceFile(path: String): File = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
        ?: error("Missing source-controlled fixture: $path")
    val oracle: JsonObject by lazy {
        val text = sourceFile(ORACLE_PATH).readText()
        check(BookText.sha256(text) == ORACLE_SHA256)
        JsonParser.parseString(text).asJsonObject
    }
    val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    val previousRows get() = oracle["previous126"].asJsonArray.map { it.asJsonObject }
    fun ownIndex(values: List<JsonObject>): BookTranslationIndex = BookTranslationIndex.decode(JsonArray().apply {
        values.forEach { row -> add(JsonObject().apply {
            add("original", row["original"]); add("russian", row["russian"])
            addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
            addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
        }) }
    }.toString())
    val previousOwn126: BookTranslationIndex by lazy { ownIndex(previousRows) }
    // Frozen independent old126+weekday15; later thanksgiving bodies have a separate exact9 replay.
    val previousOwn141: BookTranslationIndex by lazy { ownIndex(previousRows + rows) }
    val previousPublished: BookTranslationIndex by lazy { BookTranslationIndex.decode(oracle["previousPublishedPairs"].toString()) }
    val key get() = oracle["bookKey"].asString.split('|').let { ServiceBookKey(it[0], WorshipKind.valueOf(it[1]), it[2]) }
    fun sourceHtml(): String = requireNotNull(javaClass.classLoader?.getResource(
        oracle["source"].asJsonObject["fixtureResource"].asString)).readText()
    fun before(): ServiceBook = ServiceBookParser().parse(Jsoup.parse(sourceHtml(), key.sourceUrl()), key, 1L,
        translations = previousPublished.plus(previousOwn126))
    fun words(value: String): Int = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*").findAll(value).count()
}

class FeoriaWeekdayAntiphonTranslationsTest {
    private val directory = Files.createTempDirectory("weekday-antiphon-").toFile()
    private val source = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
    private val own = WeekdayAntiphonFixtures.previousOwn141
    private val oracle get() = WeekdayAntiphonFixtures.oracle
    private val rows get() = WeekdayAntiphonFixtures.rows
    @After fun clean() { directory.deleteRecursively() }

    private fun assertOnlyApprovedChanges(before: ServiceBook, after: ServiceBook) {
        val expected = rows.associateBy { it["sourceBlockId"].asString }
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(15, changed.size)
        assertEquals(expected.keys, changed.map { it.first.id }.toSet())
        changed.forEach { (old, next) ->
            val row = expected.getValue(old.id)
            assertNull(old.russian)
            assertEquals(row["original"].asString, old.original)
            assertEquals(row["originalSha256"].asString, BookText.sha256(next.original))
            assertEquals(row["russian"].asString, next.russian)
            assertEquals(row["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(old.copy(russian = next.russian, translationSource = source), next)
        }
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (old, next) -> assertEquals(old, next) }
        assertEquals(before.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun allFifteenFinalWholePairsAndSourcesEqualTheIndependentSemanticOracle() {
        assertEquals(5, FeoriaTranslationCatalog.entries.take(141).maxOf { it.revision })
        assertEquals(141, own.size)
        assertEquals(141, FeoriaTranslationCatalog.entries.take(141).size)
        assertEquals(141, FeoriaTranslationCatalog.entries.take(141).map { BookText.normalized(it.original) }.distinct().size)
        assertEquals(15, rows.size)
        assertEquals(15, FeoriaWeekdayAntiphonTranslations.records.size)
        assertEquals(244, rows.sumOf { it["originalWords"].asInt })
        assertEquals(244, rows.sumOf { WeekdayAntiphonFixtures.words(it["original"].asString) })
        assertEquals(oracle["sourceCandidateSha256"].asString, FeoriaWeekdayAntiphonTranslations.SOURCE_CANDIDATE_SHA256)
        assertEquals(oracle["semanticReviewSha256"].asString, FeoriaWeekdayAntiphonTranslations.SEMANTIC_REVIEW_SHA256)
        assertEquals(oracle["approvedFinalProjectionSha256"].asString, FeoriaWeekdayAntiphonTranslations.APPROVED_FINAL_PROJECTION_SHA256)
        assertEquals(oracle["source"].asJsonObject["sourceFixtureSha256"].asString, BookText.sha256(WeekdayAntiphonFixtures.sourceHtml()))
        val records = FeoriaWeekdayAntiphonTranslations.records.associateBy { it.entry.id }
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString
            val russian = row["russian"].asString
            assertEquals("PASS", row["semanticFinalDecision"].asString)
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedWholeOriginalSha256"].asString, BookText.sha256(BookText.normalized(original)))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(5, record.entry.revision)
            assertEquals(row["originalSourceUrl"].asString, record.entry.originalSourceUrl)
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            assertEquals(row["sourceBlockId"].asString, record.sourceBlockId)
            assertEquals(row["chapterId"].asString, record.sourceChapterId)
            assertEquals(row["sourceSpeaker"].asString, record.sourceSpeaker)
            assertEquals(row["sourceFileSha256"].asString, FeoriaWeekdayAntiphonTranslations.SOURCE_DOCUMENT_SHA256)
            assertEquals(row["sourceContentHash"].asString, FeoriaWeekdayAntiphonTranslations.MEASURED_SOURCE_CONTENT_HASH)
            val pair = BookTranslation(russian, source)
            assertEquals(pair, own.find(original))
            assertTrue(FeoriaWeekdayAntiphonTranslations.recognizes(original, pair))
            assertTrue(FeoriaTranslationCatalog.recognizes(original, pair))
        }
        val corrected = rows.single { it["rank"].asInt == 51 }
        assertEquals("13f74e3d9df5a28495196ea32ce68b4d416ff001d30cb50a2d0aecf369972fa7", corrected["russianSha256"].asString)
        assertTrue(corrected["russian"].asString.contains("воспевать Твое имя"))
        assertFalse(corrected["russian"].asString.contains("петь имени Твоему"))
    }

    @Test fun allPrevious126KeepTheirExactUtf8BodiesRevisionsOrderAndSource() {
        val expected = WeekdayAntiphonFixtures.previousRows
        val previous = FeoriaTranslationCatalog.entries.take(126)
        assertEquals(126, expected.size); assertEquals(126, previous.size)
        val projection = JsonArray()
        expected.zip(previous).forEach { (row, entry) ->
            assertEquals(row["id"].asString, entry.id)
            assertArrayEquals(row["original"].asString.toByteArray(Charsets.UTF_8), entry.original.toByteArray(Charsets.UTF_8))
            assertArrayEquals(row["russian"].asString.toByteArray(Charsets.UTF_8), entry.russian.toByteArray(Charsets.UTF_8))
            assertEquals(row["revision"].asInt, entry.revision)
            assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            projection.add(JsonArray().apply {
                add(entry.id); add(entry.original); add(entry.russian); add(entry.revision); add(entry.originalSourceUrl)
            })
        }
        assertEquals(oracle["previous126ProjectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        assertEquals(126, WeekdayAntiphonFixtures.previousOwn126.size)
        for ((path, seal) in oracle["previous126InputSeals"].asJsonObject.entrySet())
            assertEquals(path, seal.asString, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
        for ((path, seal) in oracle["protectedFiles"].asJsonObject.entrySet())
            assertEquals(path, seal.asString, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
        assertEquals(13, BookTranslationCorpus.VERSION)
    }

    @Test fun literalSourceParagraphsKeepAllWholeBodiesAndSeparateAlternatingChoirRoles() {
        val document = Jsoup.parse(WeekdayAntiphonFixtures.sourceHtml(), WeekdayAntiphonFixtures.key.sourceUrl())
        val originalDom = document.outerHtml()
        val orderedTitles = listOf("Первый вседневный антифон", "Второй вседневный антифон", "Третий вседневный антифон")
        val counts = orderedTitles.map { title ->
            val row = rows.filter { it["chapterTitle"].asString == title }.sortedBy { it["ordinalInChapter"].asInt }
            val div = document.select("div.variable_text").single { it.children().firstOrNull()?.text() == title }
            val paragraphs = div.children().filter { it.tagName() == "p" }
            assertEquals(row.size, paragraphs.size)
            row.zip(paragraphs).forEach { (expected, paragraph) ->
                val copy = paragraph.clone()
                val role = requireNotNull(copy.selectFirst("em"))
                assertEquals(expected["sourceSpeaker"].asString + ":", BookText.clean(role.text()))
                role.remove()
                assertEquals(expected["original"].asString, BookText.clean(copy.text()))
            }
            paragraphs.size
        }
        assertEquals(listOf(6, 4, 5), counts)
        assertEquals(originalDom, document.outerHtml())
        val before = WeekdayAntiphonFixtures.before()
        val chapters = orderedTitles.map { title -> before.chapters.single { it.title == title } }
        assertEquals(listOf(6, 5, 5), chapters.map { it.blocks.count { b -> b.kind == BookBlockKind.PRAYER } })
        val doxology = before.blocks.single { it.id == oracle["excludedDoxology"].asJsonObject["id"].asString }
        assertEquals(oracle["excludedDoxology"].asJsonObject["originalSha256"].asString, BookText.sha256(doxology.original))
        assertNotNull(doxology.russian)
        assertEquals(BookTranslationIndex.HOUR3_SOURCE, doxology.translationSource)
    }

    @Test fun oneRealOrderGainsOnlyTheFifteenApprovedBlocksAndTheThreeChapterGapsAreClosed() {
        val before = WeekdayAntiphonFixtures.before()
        assertTrue(ServiceBookIntegrity.valid(before))
        val oldBytes = Gson().toJson(before)
        val scope = BookVisibilityScope.of(before)
        fun missing(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) }
        assertEquals(49, missing(before))
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertOnlyApprovedChanges(before, after)
        assertEquals(34, missing(after))
        assertEquals(oldBytes, Gson().toJson(before))
        val ids = rows.map { it["chapterId"].asString }.toSet()
        assertEquals(3, ids.size)
        after.chapters.filter { it.id in ids }.flatMap { it.blocks }.filter { it.kind == BookBlockKind.PRAYER }.forEach {
            assertNotNull(it.russian)
        }
        assertNull(SavedBookEnrichment.apply(after, own))
    }

    @Test fun exactWholePairsAndCurrentCorpus13SurviveCodecWithoutInvalidatingCompletedPages() {
        val decodedOwn = BookTranslationIndex.decode(own.encode())
        val old = BookTranslationCorpus(WeekdayAntiphonFixtures.previousPublished, setOf(BookTranslationIndex.HOUR3_SOURCE.url))
        val oldEncoded = old.encode()
        val combined = BookTranslationCorpus(old.index.plus(decodedOwn), old.completedSources)
        val roundTrip = BookTranslationCorpus.decode(combined.encode())
        assertEquals(13, roundTrip.version)
        assertEquals(old.completedSources, roundTrip.completedSources)
        assertEquals(oldEncoded, old.encode())
        for (row in rows) {
            val original = row["original"].asString
            assertEquals(BookTranslation(row["russian"].asString, source), decodedOwn.find(original))
            assertEquals(decodedOwn.find(original), roundTrip.index.find(original))
        }
        val oldDoxology = oracle["excludedDoxology"].asJsonObject["original"].asString
        assertEquals(WeekdayAntiphonFixtures.previousPublished.find(oldDoxology), roundTrip.index.find(oldDoxology))
    }

    @Test fun alteredBodiesNegationsCountsRolesAndForgedTranslationMetadataCannotInheritPairs() {
        for (row in rows) {
            val original = row["original"].asString
            val pair = requireNotNull(own.find(original))
            for (changed in listOf("Не $original", "$original Еще слова", original.substringBeforeLast(' '),
                "Хор: $original", "1 Лик: $original", "2 Лик: $original", "Иерей: $original", "Глас 6: $original",
                "Припев: $original", "$original (дважды)")) {
                assertNull(changed, own.find(changed))
                assertFalse(changed, FeoriaWeekdayAntiphonTranslations.recognizes(changed, pair))
                assertFalse(changed, FeoriaTranslationCatalog.recognizes(changed, pair))
            }
            assertFalse(FeoriaWeekdayAntiphonTranslations.recognizes(original, pair.copy(russian = pair.russian + " Подмена")))
            assertFalse(FeoriaWeekdayAntiphonTranslations.recognizes(original, pair.copy(meaning = "Пояснение вместо перевода")))
            for (other in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                source.copy(url = "https://example.org/other"), source.copy(title = "Иная редакция")))
                assertFalse(FeoriaWeekdayAntiphonTranslations.recognizes(original, pair.copy(source = other)))
            val forged = row.deepCopy().apply { addProperty("russian", pair.russian + " Подмена") }
            assertNull(WeekdayAntiphonFixtures.ownIndex(listOf(forged)).find(original))
        }
        val negated = rows.single { it["rank"].asInt == 50 }["original"].asString
        assertNull(own.find(negated.replace("несть", "есть")))
        val christ = rows.single { it["rank"].asInt == 40 }["original"].asString
        assertNull(own.find(christ.replace("Сы́не Бо́жий", "Святы́й Ду́ше")))
        assertNull(own.find(rows[0]["original"].asString + " " + rows[1]["original"].asString))
    }

    @Test fun existingPublishedTranslationIsNeverReplacedByTheNewOwnFallback() {
        val plain = WeekdayAntiphonFixtures.before()
        val id = rows.first()["sourceBlockId"].asString
        // Synthetic protocol fixture, not a claimed editorial or published translation.
        val before = plain.copy(chapters = plain.chapters.map { c -> c.copy(blocks = c.blocks.map { b ->
            if (b.id == id) b.copy(russian = "Принятый опубликованный текст QA", translationSource = BookTranslationIndex.TIMROT_SOURCE) else b
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertEquals(before.blocks.single { it.id == id }, after.blocks.single { it.id == id })
        assertEquals(14, before.blocks.zip(after.blocks).count { it.first != it.second })
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun repositoryOffersTheLocalFifteenBeforeNetworkAndPreservesCorpusOldBytesAndCursor() = runBlocking {
        val before = WeekdayAntiphonFixtures.before()
        val cache = FileBookCache(directory)
        assertTrue(cache.write(before))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
        val bytes = oldFile.readBytes()
        val corpus = BookTranslationCorpus(WeekdayAntiphonFixtures.previousPublished).encode()
        assertTrue(cache.writeTranslationCorpus(corpus))
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val row = rows.first()
        val position = BookReadingPosition(before.key, before.contentHash, "weekday15", row["sourceBlockId"].asString,
            row["chapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103,
            lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local additions must return before any HTTP") }, translations = own)
        // allowNetwork=true exercises the early local-result route, not merely a disabled network option.
        val result = requireNotNull(repository.enrichSaved(before))
        assertTrue(result.fromCache); assertTrue(result.saved)
        assertOnlyApprovedChanges(before, result.book)
        assertEquals(0, requests)
        assertEquals(position, store.last("weekday15"))
        assertArrayEquals(bytes, oldFile.readBytes())
        assertEquals(corpus, cache.readTranslationCorpus())
        assertTrue(store.acceptVersion(before, result.book, "weekday15", position))
        val accepted = position.copy(contentHash = result.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("weekday15"))
        val reopened = FileBookCache(directory)
        assertEquals(before, reopened.readVersion(before.key, before.contentHash))
        assertEquals(result.book, reopened.readVersion(before.key, result.book.contentHash))
        assertEquals(result.book, (ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline reopen") },
            translations = own).load(before.key) as BookLoadResult.Ready).book)
        assertArrayEquals(bytes, oldFile.readBytes())
        assertEquals(0, requests)
    }
}
