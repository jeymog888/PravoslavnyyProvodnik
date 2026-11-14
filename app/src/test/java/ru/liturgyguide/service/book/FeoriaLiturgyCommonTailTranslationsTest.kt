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

/** Expected whole bodies/IDs come from semantic-final source data, never a new parser projection. */
internal object LiturgyCommonTailFixtures {
    const val ORACLE_PATH = "app/src/androidTest/assets/russian-liturgy-common-tail/oracle.json"
    const val ORACLE_SHA256 = "66f6cef7e7debc91d3eeed3010ddc3e251022daaa2cb2450cede5df6778850f8"
    val oracle: JsonObject by lazy {
        val input = WeekdayAntiphonFixtures.sourceFile(ORACLE_PATH).readText()
        check(BookText.sha256(input) == ORACLE_SHA256)
        JsonParser.parseString(input).asJsonObject
    }
    val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    val cases get() = oracle["cases"].asJsonArray.map { it.asJsonObject }
    val previousRows get() = ThanksgivingTranslationFixtures.previousRows + ThanksgivingTranslationFixtures.rows
    val previousOwn144 get() = ThanksgivingTranslationFixtures.previousOwn144
    fun before(case: JsonObject): ServiceBook {
        val old141 = ThanksgivingTranslationFixtures.before(case)
        return SavedBookEnrichment.apply(old141, previousOwn144) ?: old141
    }
    fun publicPrayer(book: ServiceBook): List<BookBlock> {
        val scope = BookVisibilityScope.of(book)
        return book.blocks.filter { it.kind == BookBlockKind.PRAYER && !scope.isPrivate(it) }
    }
}

class FeoriaLiturgyCommonTailTranslationsTest {
    private val directory = Files.createTempDirectory("liturgy-common-tail-").toFile()
    private val own = BookTranslationIndex.fromFeoriaCatalog()
    private val source = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
    private val rows get() = LiturgyCommonTailFixtures.rows
    private val cases get() = LiturgyCommonTailFixtures.cases
    private val oracle get() = LiturgyCommonTailFixtures.oracle
    @After fun cleanup() { directory.deleteRecursively() }

    private fun assertOnlyApproved(before: ServiceBook, after: ServiceBook, expected: List<JsonObject>) {
        val proofs = expected.associateBy { it["blockId"].asString }
        val byId = rows.associateBy { it["id"].asString }
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(proofs.size, changed.size)
        assertEquals(proofs.keys, changed.map { it.first.id }.toSet())
        val scope = BookVisibilityScope.of(before)
        for ((old, next) in changed) {
            val proof = proofs.getValue(old.id)
            val row = byId.getValue(proof["itemId"].asString)
            assertEquals(BookBlockKind.PRAYER, old.kind); assertFalse(scope.isPrivate(old))
            assertNull(old.russian)
            assertEquals(row["original"].asString, old.original)
            assertEquals(proof["originalSha256"].asString, BookText.sha256(next.original))
            assertEquals(row["russian"].asString, next.russian)
            assertEquals(proof["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(row["russian"].asString, BookBlockPresentation.effectiveRussian(after.key, next))
            assertEquals(old.copy(russian = next.russian, translationSource = source), next)
        }
        before.blocks.zip(after.blocks).filter { it.first.russian != null || it.first.kind != BookBlockKind.PRAYER || scope.isPrivate(it.first) }
            .forEach { (a, b) -> assertEquals(a, b) }
        assertEquals(before.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun nineteenFinalWholeSlotsHaveExactApprovedTextSourceAndHonestSixteenPrayerMetrics() {
        assertEquals(7, FeoriaTranslationCatalog.REVISION)
        assertEquals(163, FeoriaTranslationCatalog.entries.size); assertEquals(163, own.size)
        assertEquals(163, FeoriaTranslationCatalog.entries.map { BookText.normalized(it.original) }.distinct().size)
        assertEquals(19, rows.size); assertEquals(19, FeoriaLiturgyCommonTailTranslations.records.size)
        assertEquals(setOf(1) + (5..22).toSet(), rows.map { it["rank"].asInt }.toSet())
        assertEquals(648, rows.sumOf { WeekdayAntiphonFixtures.words(it["original"].asString) })
        assertEquals(623, rows.sumOf { it["newPrayerWords"].asInt })
        assertEquals(16, rows.count { it["countAsNewPrayerTranslationBody"].asBoolean })
        assertEquals(66, rows.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(57, rows.filter { it["countAsNewPrayerTranslationBody"].asBoolean }.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(1971, rows.sumOf { it["originalWords"].asInt * it["occurrences"].asJsonArray.size() })
        assertEquals(1896, rows.sumOf { it["newPrayerWords"].asInt * it["occurrences"].asJsonArray.size() })
        assertEquals(19, rows.sumOf { it["existingRussianMetadataWords"].asInt })
        assertEquals(6, rows.sumOf { it["unchangedRussianPrayerWords"].asInt })
        assertEquals(oracle["sourceCandidateSha256"].asString, FeoriaLiturgyCommonTailTranslations.SOURCE_CANDIDATE_SHA256)
        assertEquals(oracle["semanticReviewSha256"].asString, FeoriaLiturgyCommonTailTranslations.SEMANTIC_REVIEW_SHA256)
        assertEquals(oracle["approvedFinalProjectionSha256"].asString, FeoriaLiturgyCommonTailTranslations.APPROVED_FINAL_PROJECTION_SHA256)
        val records = FeoriaLiturgyCommonTailTranslations.records.associateBy { it.entry.id }
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString; val russian = row["russian"].asString
            assertEquals("approved", row["semanticFinalDecision"].asString)
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedWholeOriginalSha256"].asString, BookText.sha256(BookText.normalized(original)))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(7, record.entry.revision)
            assertEquals(row["originalSourceUrl"].asString, record.entry.originalSourceUrl)
            assertEquals(row["rank"].asInt, record.rank)
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            assertEquals(row["sourceBlockId"].asString, record.sourceBlockId)
            assertEquals(row["sourceChapterId"].asString, record.sourceChapterId)
            assertEquals(row["originalWords"].asInt, record.originalWords)
            assertEquals(row["newPrayerWords"].asInt, record.newPrayerWords)
            assertEquals(BookTranslation(russian, source), own.find(original))
            assertTrue(FeoriaLiturgyCommonTailTranslations.recognizes(original, requireNotNull(own.find(original))))
        }
        assertEquals("2c33bd2e9dfb17ee85ff362cacef51e5d4f6a7bf3e8f4926f181096aff1ca63e", rows.single { it["rank"].asInt == 1 }["russianSha256"].asString)
        assertEquals("3db79da4ea327b8eb5cc6f0a62ba6d2194792c5adfd0aa4bae84d0f9adaedf30", rows.single { it["rank"].asInt == 11 }["russianSha256"].asString)
    }

    @Test fun previous144OrderBodiesRevisionsAndSourcesAndProtectedHistoriesStayByteIdentical() {
        val previous = LiturgyCommonTailFixtures.previousRows
        assertEquals(144, previous.size); assertEquals(144, LiturgyCommonTailFixtures.previousOwn144.size)
        val projection = JsonArray()
        previous.zip(FeoriaTranslationCatalog.entries.take(144)).forEach { (row, entry) ->
            assertEquals(row["id"].asString, entry.id)
            assertArrayEquals(row["original"].asString.toByteArray(Charsets.UTF_8), entry.original.toByteArray(Charsets.UTF_8))
            assertArrayEquals(row["russian"].asString.toByteArray(Charsets.UTF_8), entry.russian.toByteArray(Charsets.UTF_8))
            assertEquals(row["revision"].asInt, entry.revision)
            assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            projection.add(JsonArray().apply { add(entry.id); add(entry.original); add(entry.russian); add(entry.revision); add(entry.originalSourceUrl) })
        }
        assertEquals(oracle["previous144"].asJsonObject["projectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        for ((path, seal) in oracle["protectedFiles"].asJsonObject.entrySet())
            assertEquals(path, seal.asString, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
        assertEquals(13, BookTranslationCorpus.VERSION)
    }

    @Test fun threeRealDatesGainExactSixtySixIdsAndWordCoverageWithoutEditingDirectionBiblePrivateOrMeaning() {
        var gained = 0; var gainedWords = 0; var newPrayerWords = 0
        for (case in cases) {
            val input = ThanksgivingTranslationFixtures.text(case["fixtureResource"].asString)
            assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(input))
            val key = ThanksgivingTranslationFixtures.key(case)
            val doc = Jsoup.parse(input, key.sourceUrl()); val untouchedDoc = doc.outerHtml()
            val before = LiturgyCommonTailFixtures.before(case); val untouchedBook = Gson().toJson(before)
            val expected = case["expected"].asJsonArray.map { it.asJsonObject }
            val coverage = case["publicPrayerCoverage"].asJsonObject
            val beforePublic = LiturgyCommonTailFixtures.publicPrayer(before)
            assertEquals(coverage["blockCount"].asInt, beforePublic.size)
            assertEquals(coverage["wholeSourceWords"].asInt, beforePublic.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            assertEquals(coverage["availableBlocksBefore"].asInt, beforePublic.count { it.russian != null })
            assertEquals(coverage["availableWholeSourceWordsBefore"].asInt, beforePublic.filter { it.russian != null }.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            fun printed(proof: JsonObject): String {
                val row = rows.single { it["id"] == proof["itemId"] }
                return listOf(proof["sourcePrefix"].asString, row["original"].asString).filter(String::isNotBlank).joinToString(" ")
            }
            // Repeated Alleluia is four real paragraphs, not one paragraph reused as four proofs.
            expected.groupBy(::printed).forEach { (wholeParagraph, proofs) ->
                assertEquals(wholeParagraph, proofs.size, doc.select("p").count { BookText.clean(it.text()) == wholeParagraph })
            }
            for (proof in expected) {
                val row = rows.single { it["id"] == proof["itemId"] }
                val paragraph = doc.select("p").firstOrNull { BookText.clean(it.text()) == printed(proof) }
                    ?: error("Missing whole source paragraph: ${proof["blockId"]}")
                val isOptional = paragraph.parents().any { it.tagName() == "details" && it.hasClass("user_choice") }
                assertEquals(proof["optionalPostCommunion"].asBoolean, isOptional)
                val block = before.chapters.single { it.id == proof["chapterId"].asString }.blocks.single { it.id == proof["blockId"].asString }
                assertEquals(row["original"].asString, block.original)
            }
            val after = requireNotNull(SavedBookEnrichment.apply(before, own))
            assertOnlyApproved(before, after, expected)
            val afterPublic = LiturgyCommonTailFixtures.publicPrayer(after)
            assertEquals(case["expectedPublicMissingBefore"].asInt, beforePublic.count { it.russian == null })
            assertEquals(case["expectedPublicMissingAfter"].asInt, afterPublic.count { it.russian == null })
            assertEquals(coverage["availableBlocksAfter"].asInt, afterPublic.count { it.russian != null })
            assertEquals(coverage["availableWholeSourceWordsAfter"].asInt, afterPublic.filter { it.russian != null }.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            assertEquals(22, expected.size); assertEquals(657, expected.sumOf { it["originalWords"].asInt })
            assertEquals(632, expected.sumOf { it["newPrayerWords"].asInt })
            assertEquals(untouchedBook, Gson().toJson(before)); assertEquals(untouchedDoc, doc.outerHtml())
            assertNull(SavedBookEnrichment.apply(after, own))
            gained += expected.size; gainedWords += expected.sumOf { it["originalWords"].asInt }; newPrayerWords += expected.sumOf { it["newPrayerWords"].asInt }
        }
        assertEquals(3, cases.size); assertEquals(66, gained); assertEquals(1971, gainedWords); assertEquals(1896, newPrayerWords)
    }

    @Test fun wholePairCodecAndRecognizerRejectNearBodiesRolesCountsMixedGlyphChangesForgedRussianAndWrongSources() {
        val decoded = BookTranslationIndex.decode(own.encode())
        val corpus = BookTranslationCorpus(WeekdayAntiphonFixtures.previousPublished.plus(decoded), setOf(BookTranslationIndex.HOUR3_SOURCE.url))
        val roundTrip = BookTranslationCorpus.decode(corpus.encode())
        assertEquals(13, roundTrip.version); assertEquals(corpus.completedSources, roundTrip.completedSources)
        for (row in rows) {
            val original = row["original"].asString; val pair = requireNotNull(own.find(original))
            assertEquals(pair, decoded.find(original)); assertEquals(pair, roundTrip.index.find(original))
            for (changed in listOf("Не $original", "$original QA", original.substringBeforeLast(' '),
                "Хор: $original", "1 Лик: $original", "Иерей: $original", "Глас 6: $original", "Припев: $original")) {
                assertNull(changed, own.find(changed))
                assertFalse(FeoriaLiturgyCommonTailTranslations.recognizes(changed, pair))
            }
            assertFalse(FeoriaLiturgyCommonTailTranslations.recognizes(original, pair.copy(russian = pair.russian + " QA")))
            assertFalse(FeoriaLiturgyCommonTailTranslations.recognizes(original, pair.copy(meaning = "QA meaning")))
            for (other in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                source.copy(url = "https://example.org/other"), source.copy(title = "Other edition")))
                assertFalse(FeoriaLiturgyCommonTailTranslations.recognizes(original, pair.copy(source = other)))
            val forged = row.deepCopy().apply { addProperty("russian", pair.russian + " QA") }
            assertNull(WeekdayAntiphonFixtures.ownIndex(listOf(forged)).find(original))
        }
        for (near in oracle["publishedNearOriginalsNotAccepted"].asJsonArray.map { it.asJsonObject }) {
            assertEquals(near["originalSha256"].asString, BookText.sha256(near["original"].asString))
            assertNull(own.find(near["original"].asString))
        }
        val trisagion = rows.single { it["rank"].asInt == 16 }["original"].asString
        assertEquals(1, trisagion.count { it == 'T' }); assertNull(own.find(trisagion.replace('T', 'Т')))
        val twelve = rows.single { it["rank"].asInt == 19 }["original"].asString
        assertNull(own.find(twelve.replace("12", "3")))
        val bogorodichen = rows.single { it["rank"].asInt == 9 }["original"].asString
        assertNull(own.find(bogorodichen.substringAfter(": ")))
    }

    @Test fun anAcceptedPublishedPairWinsAndAnUnknownSourceCannotFillTheApprovedSlot() {
        val case = cases.first(); val plain = LiturgyCommonTailFixtures.before(case)
        val target = rows.first()["sourceBlockId"].asString
        val before = plain.copy(chapters = plain.chapters.map { c -> c.copy(blocks = c.blocks.map { b ->
            if (b.id == target) b.copy(russian = "Accepted published QA", translationSource = BookTranslationIndex.TIMROT_SOURCE) else b
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertEquals(21, before.blocks.zip(after.blocks).count { it.first != it.second })
        assertEquals(before.blocks.single { it.id == target }, after.blocks.single { it.id == target })
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        val unknown = BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", rows.first()["original"].asString); addProperty("russian", "Unknown QA")
            addProperty("source", "Unknown source"); addProperty("url", "https://example.org/unknown")
        }) }.toString())
        assertNull(SavedBookEnrichment.apply(plain, unknown))
    }

    @Test fun acceptedC1MatinsGainsOnlyTwoAdditionalApprovedPetitionsBeyondThePinnedHistorical111() {
        val proof = oracle["matinsC1"].asJsonObject
        val input = ThanksgivingTranslationFixtures.text(proof["jvmResource"].asString)
        assertEquals(proof["sha256"].asString, BookText.sha256(input))
        val accepted = Gson().fromJson(input, ServiceBook::class.java)
        val before = SavedBookEnrichment.apply(accepted, LiturgyCommonTailFixtures.previousOwn144) ?: accepted
        val expected = proof["newApprovedOccurrencesOnly"].asJsonArray.map { it.asJsonObject }
        assertEquals(setOf(12, 13), expected.map { it["rank"].asInt }.toSet())
        assertEquals(2, expected.size)
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertOnlyApproved(before, after, expected)
        assertEquals(input, ThanksgivingTranslationFixtures.text(proof["jvmResource"].asString))
        // The unchanged Android witness keeps its accepted hash; JVM serialization differs.
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", accepted.contentHash)
        assertTrue(ServiceBookIntegrity.valid(after)); assertNull(SavedBookEnrichment.apply(after, own))
    }

    @Test fun localCandidatePrecedesAnyHttpAndKeepsOldBytesCorpusAndCursorUntilExplicitAcceptAndDiskReopen() = runBlocking {
        val case = cases.first(); val before = LiturgyCommonTailFixtures.before(case)
        val cache = FileBookCache(directory); assertTrue(cache.write(before))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }; val oldBytes = oldFile.readBytes()
        val corpus = BookTranslationCorpus(ThanksgivingTranslationFixtures.baselinePublished(case)).encode()
        assertTrue(cache.writeTranslationCorpus(corpus))
        val store = BookReadingStore(AcceptanceMemoryPreferences()); val row = rows.first()
        val position = BookReadingPosition(before.key, before.contentHash, "commonTail19", row["sourceBlockId"].asString,
            row["sourceChapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
            wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position)); var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local19 must precede HTTP") }, translations = own)
        val result = requireNotNull(repository.enrichSaved(before))
        assertTrue(result.saved); assertTrue(result.fromCache)
        assertOnlyApproved(before, result.book, case["expected"].asJsonArray.map { it.asJsonObject })
        assertEquals(0, requests); assertEquals(position, store.last("commonTail19"))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, cache.readTranslationCorpus())
        assertTrue(store.acceptVersion(before, result.book, "commonTail19", position))
        val accepted = position.copy(contentHash = result.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("commonTail19"))
        val reopened = FileBookCache(directory)
        assertEquals(before, reopened.readVersion(before.key, before.contentHash))
        assertEquals(result.book, reopened.readVersion(before.key, result.book.contentHash))
        val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
        assertEquals(result.book, (offline.load(before.key) as BookLoadResult.Ready).book)
        assertEquals(before, offline.cachedVersion(before.key, before.contentHash))
        assertEquals(accepted, store.restore(result.book, "commonTail19"))
        assertNull(offline.enrichSaved(result.book, allowNetwork = false))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, reopened.readTranslationCorpus()); assertEquals(0, requests)
    }
}
