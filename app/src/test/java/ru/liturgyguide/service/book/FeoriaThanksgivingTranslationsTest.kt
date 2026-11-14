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
import java.nio.file.Files

/** Semantic-final three and previous141 come from independent frozen oracles, never the new catalog. */
internal object ThanksgivingTranslationFixtures {
    private const val ORACLE_PATH = "app/src/androidTest/assets/russian-thanksgiving-three/oracle.json"
    const val ORACLE_SHA256 = "2cee98edf7fc1029c8fdaaa80352295eab86f871ce604479a991f4bc1230ff1a"
    val oracle: JsonObject by lazy {
        val input = WeekdayAntiphonFixtures.sourceFile(ORACLE_PATH).readText()
        check(BookText.sha256(input) == ORACLE_SHA256)
        JsonParser.parseString(input).asJsonObject
    }
    val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    val cases get() = oracle["cases"].asJsonArray.map { it.asJsonObject }
    val previousRows get() = WeekdayAntiphonFixtures.previousRows + WeekdayAntiphonFixtures.rows
    val previousOwn141 get() = WeekdayAntiphonFixtures.previousOwn141
    val previousOwn144: BookTranslationIndex by lazy { WeekdayAntiphonFixtures.ownIndex(previousRows + rows) }
    fun text(path: String) = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
    fun key(case: JsonObject) = case["bookKey"].asString.split('|').let { ServiceBookKey(it[0], WorshipKind.valueOf(it[1]), it[2]) }
    fun baselinePublished(case: JsonObject): BookTranslationIndex {
        if (case["baselinePublishedKind"].asString == "WEEKDAY201") return WeekdayAntiphonFixtures.previousPublished
        val proof = oracle["previousSundayBaseline"].asJsonObject
        val input = text(proof["resource"].asString)
        check(BookText.sha256(input) == proof["sha256"].asString)
        val saved = JsonParser.parseString(input).asJsonObject["cases"].asJsonArray.map { it.asJsonObject }
            .single { it["bookKey"] == case["bookKey"] }
        return BookTranslationIndex.decode(saved["pairs"].toString())
    }
    fun before(case: JsonObject): ServiceBook {
        val body = text(case["fixtureResource"].asString)
        check(BookText.sha256(body) == case["sourceFixtureSha256"].asString)
        val key = key(case)
        val base = ServiceBookParser().parse(Jsoup.parse(body, key.sourceUrl()), key, 1L,
            translations = baselinePublished(case).plus(previousOwn141))
        var priorSunday = BookTranslationIndex.empty()
        case["previousSundaySources"].asJsonArray.map { it.asJsonObject }.forEach { p ->
            val input = text(p["fixture"].asString)
            check(BookText.sha256(input) == p["sourceFileSha256"].asString)
            val source = BookSource(p["title"].asString, p["url"].asString)
            priorSunday = priorSunday.fillingMissing(BookTranslationIndex.fromParallelPage(Jsoup.parse(input, source.url), source))
        }
        return SavedBookEnrichment.apply(base, priorSunday) ?: base
    }
    fun old141Projection(): JsonArray = JsonArray().apply {
        previousRows.forEach { r -> add(JsonArray().apply {
            add(r["id"]); add(r["original"]); add(r["russian"]); add(r["revision"]); add(r["originalSourceUrl"])
        }) }
    }
}

class FeoriaThanksgivingTranslationsTest {
    private val directory = Files.createTempDirectory("thanksgiving-three-").toFile()
    private val own = ThanksgivingTranslationFixtures.previousOwn144
    private val source = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
    private val rows get() = ThanksgivingTranslationFixtures.rows
    private val cases get() = ThanksgivingTranslationFixtures.cases
    private val oracle get() = ThanksgivingTranslationFixtures.oracle
    @After fun cleanup() { directory.deleteRecursively() }

    private fun assertExactThree(before: ServiceBook, after: ServiceBook, case: JsonObject) {
        val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
        val items = rows.associateBy { it["id"].asString }
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(3, changed.size)
        assertEquals(expected.keys, changed.map { it.first.id }.toSet())
        changed.forEach { (old, next) ->
            val proof = expected.getValue(old.id)
            val row = items.getValue(proof["itemId"].asString)
            assertNull(old.russian)
            assertEquals(row["original"].asString, old.original)
            assertEquals(proof["originalSha256"].asString, BookText.sha256(next.original))
            assertEquals(row["russian"].asString, next.russian)
            assertEquals(proof["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(old.copy(russian = next.russian, translationSource = source), next)
        }
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        assertEquals(before.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun allThreeFinalPairsMatchTheirIndependentFullSemanticHashesAndSource() {
        assertEquals(6, FeoriaTranslationCatalog.entries.take(144).maxOf { it.revision })
        assertEquals(144, FeoriaTranslationCatalog.entries.take(144).size); assertEquals(144, own.size)
        assertEquals(144, FeoriaTranslationCatalog.entries.take(144).map { BookText.normalized(it.original) }.distinct().size)
        assertEquals(3, rows.size); assertEquals(3, FeoriaThanksgivingTranslations.records.size)
        assertEquals(340, rows.sumOf { WeekdayAntiphonFixtures.words(it["original"].asString) })
        assertEquals(340, rows.sumOf { it["originalWords"].asInt })
        assertEquals(9, rows.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(1020, rows.sumOf { it["originalWords"].asInt * it["occurrences"].asJsonArray.size() })
        assertEquals(oracle["sourceCandidateSha256"].asString, FeoriaThanksgivingTranslations.SOURCE_CANDIDATE_SHA256)
        assertEquals(oracle["semanticReviewSha256"].asString, FeoriaThanksgivingTranslations.SEMANTIC_REVIEW_SHA256)
        val records = FeoriaThanksgivingTranslations.records.associateBy { it.entry.id }
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString
            val russian = row["russian"].asString
            assertEquals("approved", row["semanticFinalDecision"].asString)
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedWholeOriginalSha256"].asString, BookText.sha256(BookText.normalized(original)))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(6, record.entry.revision)
            assertEquals(row["originalSourceUrl"].asString, record.entry.originalSourceUrl)
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            assertEquals(row["sourceBlockId"].asString, record.sourceBlockId)
            assertEquals(row["sourceChapterId"].asString, record.sourceChapterId)
            assertEquals(row["sourcePrintedPrayerNumber"].asInt, record.printedPrayerNumber)
            assertEquals(row["primarySourceDocumentSha256"].asString, FeoriaThanksgivingTranslations.PRIMARY_SOURCE_DOCUMENT_SHA256)
            assertEquals(BookTranslation(russian, source), own.find(original))
            assertTrue(FeoriaThanksgivingTranslations.recognizes(original, requireNotNull(own.find(original))))
        }
        val corrected = rows.single { it["rank"].asInt == 3 }
        assertEquals("e0d5f2bc71301d4eb5ca3658f6370b40e91fedf47edac8adbeeb10beff99493c", corrected["russianSha256"].asString)
        assertTrue(corrected["russian"].asString.contains("\u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0435\u043d\u0438\u0435 \u043c\u043e\u0438\u0445 \u043f\u043e\u043c\u044b\u0441\u043b\u043e\u0432 \u0438\u0437 \u043f\u043b\u0435\u043d\u0430"))
        assertTrue(corrected["russian"].asString.contains("\u043d\u0435 \u0432 \u043e\u0441\u0443\u0436\u0434\u0435\u043d\u0438\u0435"))
    }

    @Test fun everyOld141BodyRevisionOrderAndSourceStaysByteForByteAndCorpus13StaysCurrent() {
        val previous = ThanksgivingTranslationFixtures.previousRows
        assertEquals(141, previous.size)
        assertEquals(141, ThanksgivingTranslationFixtures.previousOwn141.size)
        val projection = JsonArray()
        previous.zip(FeoriaTranslationCatalog.entries.take(141)).forEach { (row, entry) ->
            assertEquals(row["id"].asString, entry.id)
            assertArrayEquals(row["original"].asString.toByteArray(Charsets.UTF_8), entry.original.toByteArray(Charsets.UTF_8))
            assertArrayEquals(row["russian"].asString.toByteArray(Charsets.UTF_8), entry.russian.toByteArray(Charsets.UTF_8))
            assertEquals(row["revision"].asInt, entry.revision)
            assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            projection.add(JsonArray().apply {
                add(entry.id); add(entry.original); add(entry.russian); add(entry.revision); add(entry.originalSourceUrl)
            })
        }
        assertEquals(oracle["previous141"].asJsonObject["projectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        for ((path, seal) in oracle["protectedFiles"].asJsonObject.entrySet())
            assertEquals(path, seal.asString, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
        assertEquals(13, BookTranslationCorpus.VERSION)
    }

    @Test fun nineActualWholeSourceOccurrencesGainOnlyThreeEachAndOptionalContextAndMeaningStayUnchanged() {
        var total = 0
        for (case in cases) {
            val html = ThanksgivingTranslationFixtures.text(case["fixtureResource"].asString)
            assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(html))
            val doc = Jsoup.parse(html, ThanksgivingTranslationFixtures.key(case).sourceUrl())
            val unchanged = doc.outerHtml()
            val before = ThanksgivingTranslationFixtures.before(case)
            val beforeBytes = Gson().toJson(before)
            assertTrue(ServiceBookIntegrity.valid(before))
            val scope = BookVisibilityScope.of(before)
            fun missing(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) }
            assertEquals(case["expectedPublicMissingBefore"].asInt, missing(before))
            for (row in rows) {
                val paragraph = doc.select("p").single { BookText.clean(it.text()) == row["original"].asString }
                val optional = paragraph.parents().single { it.tagName() == "details" && it.hasClass("user_choice") }
                assertEquals(oracle["optionalSourceMetadata"].asJsonObject["summary"].asString,
                    BookText.clean(requireNotNull(optional.selectFirst("summary")).text()))
                val expected = row["occurrences"].asJsonArray.map { it.asJsonObject }.single { it["bookKey"] == case["bookKey"] }
                val chapter = before.chapters.single { it.id == expected["chapterId"].asString }
                assertEquals(expected["sourceOwnHeading"].asString, chapter.title)
                assertEquals(row["original"].asString, chapter.blocks.single { it.id == expected["blockId"].asString }.original)
            }
            val after = requireNotNull(SavedBookEnrichment.apply(before, own))
            assertExactThree(before, after, case)
            assertEquals(case["expectedPublicMissingAfter"].asInt, missing(after))
            assertEquals(beforeBytes, Gson().toJson(before))
            assertEquals(unchanged, doc.outerHtml())
            assertNull(SavedBookEnrichment.apply(after, own))
            total += 3
        }
        assertEquals(3, cases.size); assertEquals(9, total)
    }

    @Test fun fullPairCodecRejectsAlteredBodiesPublishedNearVariantsRolesAndForgedRussianOrMeaning() {
        val decoded = BookTranslationIndex.decode(own.encode())
        val corpus = BookTranslationCorpus(WeekdayAntiphonFixtures.previousPublished.plus(decoded), setOf(BookTranslationIndex.HOUR3_SOURCE.url))
        val roundTrip = BookTranslationCorpus.decode(corpus.encode())
        assertEquals(13, roundTrip.version); assertEquals(corpus.completedSources, roundTrip.completedSources)
        for (row in rows) {
            val original = row["original"].asString
            val pair = requireNotNull(own.find(original))
            assertEquals(pair, decoded.find(original)); assertEquals(pair, roundTrip.index.find(original))
            for (changed in listOf("\u041d\u0435 $original", "$original QA", original.substringBeforeLast(' '),
                "\u0425\u043e\u0440: $original", "1 \u041b\u0438\u043a: $original", "\u0418\u0435\u0440\u0435\u0439: $original", "\u0413\u043b\u0430\u0441 6: $original", "\u041f\u0440\u0438\u043f\u0435\u0432: $original")) {
                assertNull(changed, own.find(changed))
                assertFalse(FeoriaThanksgivingTranslations.recognizes(changed, pair))
            }
            assertFalse(FeoriaThanksgivingTranslations.recognizes(original, pair.copy(russian = pair.russian + " QA")))
            assertFalse(FeoriaThanksgivingTranslations.recognizes(original, pair.copy(meaning = "QA meaning")))
            for (other in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                source.copy(url = "https://example.org/other"), source.copy(title = "Other edition")))
                assertFalse(FeoriaThanksgivingTranslations.recognizes(original, pair.copy(source = other)))
            val forged = row.deepCopy().apply { addProperty("russian", pair.russian + " QA") }
            assertNull(WeekdayAntiphonFixtures.ownIndex(listOf(forged)).find(original))
        }
        for (near in oracle["publishedNearOriginalsNotAccepted"].asJsonArray.map { it.asJsonObject }) {
            val original = rows.single { it["rank"] == near["rank"] }["original"].asString
            assertNotEquals(BookText.normalized(original), BookText.normalized(near["publishedOriginal"].asString))
            assertNull(own.find(near["publishedOriginal"].asString))
        }
        val fifth = rows.single { it["rank"].asInt == 3 }["original"].asString
        assertEquals(1, fifth.count { it == 'o' })
        assertNull(own.find(fifth.replace('o', '\u043e')))
        val fourth = rows.single { it["rank"].asInt == 4 }
        assertFalse(BookText.normalized(fourth["original"].asString).endsWith("\u0430\u043c\u0438\u043d\u044c"))
        assertFalse(BookText.normalized(fourth["russian"].asString).endsWith("\u0430\u043c\u0438\u043d\u044c"))
        assertNull(own.find(fourth["original"].asString + " \u0410\u043c\u0438\u043d\u044c."))
    }

    @Test fun anAcceptedPublishedPairIsNeverRewrittenAndUnknownSourceCannotBecomeOwn() {
        val case = cases.first()
        val plain = ThanksgivingTranslationFixtures.before(case)
        val target = rows.first()["sourceBlockId"].asString
        // Synthetic protocol string, not a published or editorial prayer translation.
        val before = plain.copy(chapters = plain.chapters.map { c -> c.copy(blocks = c.blocks.map { b ->
            if (b.id == target) b.copy(russian = "Accepted published QA", translationSource = BookTranslationIndex.TIMROT_SOURCE) else b
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertEquals(2, before.blocks.zip(after.blocks).count { it.first != it.second })
        assertEquals(before.blocks.single { it.id == target }, after.blocks.single { it.id == target })
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        val unknown = BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", rows.first()["original"].asString); addProperty("russian", "Unknown QA")
            addProperty("source", "Unknown source"); addProperty("url", "https://example.org/unknown")
        }) }.toString())
        assertNull(SavedBookEnrichment.apply(plain, unknown))
    }

    @Test fun localThreeArePublishedBeforeAnyHttpAndKeepOldBytesCorpusAndCursorUntilExplicitAccept() = runBlocking {
        val case = cases.first()
        val before = ThanksgivingTranslationFixtures.before(case)
        val cache = FileBookCache(directory)
        assertTrue(cache.write(before))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
        val oldBytes = oldFile.readBytes()
        val corpus = BookTranslationCorpus(ThanksgivingTranslationFixtures.baselinePublished(case)).encode()
        assertTrue(cache.writeTranslationCorpus(corpus))
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val row = rows.first()
        val position = BookReadingPosition(before.key, before.contentHash, "thanksgiving3", row["sourceBlockId"].asString,
            row["sourceChapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
            wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local three must return before optional network") }, translations = own)
        val result = requireNotNull(repository.enrichSaved(before))
        assertTrue(result.saved); assertTrue(result.fromCache)
        assertExactThree(before, result.book, case)
        assertEquals(0, requests)
        assertEquals(position, store.last("thanksgiving3"))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, cache.readTranslationCorpus())
        assertTrue(store.acceptVersion(before, result.book, "thanksgiving3", position))
        val accepted = position.copy(contentHash = result.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("thanksgiving3"))
        val reopened = FileBookCache(directory)
        assertEquals(before, reopened.readVersion(before.key, before.contentHash))
        assertEquals(result.book, reopened.readVersion(before.key, result.book.contentHash))
        assertEquals(result.book, (ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
            .load(before.key) as BookLoadResult.Ready).book)
        assertNull(repository.enrichSaved(result.book, allowNetwork = false))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(0, requests)
    }
}
