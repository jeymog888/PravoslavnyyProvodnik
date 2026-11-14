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
internal object RemainingThirtyFixtures {
    const val ORACLE_PATH = "app/src/androidTest/assets/russian-remaining-thirty/oracle.json"
    const val ORACLE_SHA256 = "676d833a5e04395f315f7d5a88c2db030db4b2f66b879e47adf41aa4b8606aa4"
    val oracle: JsonObject by lazy {
        val input = WeekdayAntiphonFixtures.sourceFile(ORACLE_PATH).readText()
        check(BookText.sha256(input) == ORACLE_SHA256)
        JsonParser.parseString(input).asJsonObject
    }
    val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    val cases get() = oracle["cases"].asJsonArray.map { it.asJsonObject }
    val previousRows get() = LiturgyCommonTailFixtures.previousRows + LiturgyCommonTailFixtures.rows
    val previousOwn163 get() = LiturgyCommonTailFixtures.previousOwn163
    fun before(case: JsonObject): ServiceBook {
        val old144 = LiturgyCommonTailFixtures.before(case)
        return SavedBookEnrichment.apply(old144, previousOwn163) ?: old144
    }
    fun publicPrayer(book: ServiceBook): List<BookBlock> {
        val scope = BookVisibilityScope.of(book)
        return book.blocks.filter { it.kind == BookBlockKind.PRAYER && !scope.isPrivate(it) }
    }
}

class FeoriaRemainingThirtyTranslationsTest {
    private val directory = Files.createTempDirectory("remaining-thirty-").toFile()
    private val own = BookTranslationIndex.fromFeoriaCatalog()
    private val source = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
    private val rows get() = RemainingThirtyFixtures.rows
    private val cases get() = RemainingThirtyFixtures.cases
    private val oracle get() = RemainingThirtyFixtures.oracle
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
            val literalOriginal = proof.get("original")?.asString ?: row["original"].asString
            assertEquals(literalOriginal, old.original)
            assertEquals(BookText.normalized(row["original"].asString), BookText.normalized(old.original))
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

    @Test fun thirtyFinalWholeSlotsHaveExactApprovedHashesSourceAndSeparatePrayerAnnouncementMetadata() {
        assertEquals(8, FeoriaTranslationCatalog.REVISION)
        assertEquals(193, FeoriaTranslationCatalog.entries.size); assertEquals(193, own.size)
        assertEquals(193, FeoriaTranslationCatalog.entries.map { BookText.normalized(it.original) }.distinct().size)
        assertEquals(30, rows.size); assertEquals(30, FeoriaRemainingThirtyTranslations.records.size)
        assertEquals(22, rows.count { it["group"].asString == "COMMON_STAGES_22" })
        assertEquals(8, rows.count { it["group"].asString == "CHANGING_HYMNS_8" })
        assertEquals(369, rows.sumOf { WeekdayAntiphonFixtures.words(it["original"].asString) })
        assertEquals(319, rows.sumOf { it["newPrayerWords"].asInt })
        assertEquals(28, rows.count { it["countAsNewPrayerTranslationBody"].asBoolean })
        assertEquals(32, rows.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(29, rows.filter { it["countAsNewPrayerTranslationBody"].asBoolean }.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(386, rows.sumOf { it["originalWords"].asInt * it["occurrences"].asJsonArray.size() })
        assertEquals(325, rows.sumOf { it["newPrayerWords"].asInt * it["occurrences"].asJsonArray.size() })
        assertEquals(36, rows.sumOf { it["existingRussianMetadataWords"].asInt })
        assertEquals(14, rows.sumOf { it["announcementSourceWords"].asInt })
        assertEquals(oracle["sourceCandidateSha256"].asString, FeoriaRemainingThirtyTranslations.SOURCE_CANDIDATE_SHA256)
        assertEquals(oracle["semanticReviewSha256"].asString, FeoriaRemainingThirtyTranslations.SEMANTIC_REVIEW_SHA256)
        assertEquals(oracle["approvedFinalProjectionSha256"].asString, FeoriaRemainingThirtyTranslations.APPROVED_FINAL_PROJECTION_SHA256)
        val records = FeoriaRemainingThirtyTranslations.records.associateBy { it.entry.id }
        val projection = JsonArray()
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString; val russian = row["russian"].asString
            assertEquals("approved", row["semanticFinalDecision"].asString)
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedWholeOriginalSha256"].asString, BookText.sha256(BookText.normalized(original)))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(8, record.entry.revision); assertEquals(row["originalSourceUrl"].asString, record.entry.originalSourceUrl)
            assertEquals(row["rank"].asInt, record.rank)
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            assertEquals(row["sourceBlockId"].asString, record.sourceBlockId)
            assertEquals(row["sourceChapterId"].asString, record.sourceChapterId)
            assertEquals(row["originalWords"].asInt, record.originalWords)
            assertEquals(row["newPrayerWords"].asInt, record.newPrayerWords)
            assertEquals(BookTranslation(russian, source), own.find(original))
            assertTrue(FeoriaRemainingThirtyTranslations.recognizes(original, requireNotNull(own.find(original))))
            projection.add(JsonObject().apply {
                add("id", row["inventoryId"]); add("rank", row["rank"])
                add("originalSha256", row["originalSha256"]); add("finalRussian", row["russian"])
            })
            val segments = row["segmentMetadata"].asJsonArray.map { it.asJsonObject }
            if (segments.isNotEmpty()) {
                val separator = if (row["rank"].asInt in 70..72) ", " else " "
                assertEquals(original, segments.joinToString(separator) { it["original"].asString })
                assertEquals(russian, segments.joinToString(separator) { it["russian"].asString })
            }
        }
        assertEquals(oracle["approvedFinalProjectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        for (rank in listOf(26, 57)) assertTrue(rows.single { it["rank"].asInt == rank }["russian"].asString.startsWith("Чтец объявляет: "))
        assertTrue(oracle["rank58AlreadySupportedInOptionalThursdaySource"].asBoolean)
        assertFalse(oracle["rank58NewParserBoundaryGainClaimed"].asBoolean)
    }

    @Test fun previous163OrderBodiesRevisionsSourcesAndAllHistoricalGuardsStayByteIdentical() {
        val previous = RemainingThirtyFixtures.previousRows
        assertEquals(163, previous.size); assertEquals(163, RemainingThirtyFixtures.previousOwn163.size)
        val projection = JsonArray()
        previous.zip(FeoriaTranslationCatalog.entries.take(163)).forEach { (row, entry) ->
            assertEquals(row["id"].asString, entry.id)
            assertArrayEquals(row["original"].asString.toByteArray(Charsets.UTF_8), entry.original.toByteArray(Charsets.UTF_8))
            assertArrayEquals(row["russian"].asString.toByteArray(Charsets.UTF_8), entry.russian.toByteArray(Charsets.UTF_8))
            assertEquals(row["revision"].asInt, entry.revision)
            assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            projection.add(JsonArray().apply { add(entry.id); add(entry.original); add(entry.russian); add(entry.revision); add(entry.originalSourceUrl) })
        }
        assertEquals(oracle["previous163"].asJsonObject["projectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        for ((path, seal) in oracle["protectedFiles"].asJsonObject.entrySet())
            assertEquals(path, seal.asString, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
        assertEquals(13, BookTranslationCorpus.VERSION)
    }

    @Test fun threeRealDatesGainOnlyThirtyTwoExactIdsAndReachZeroPublicPrayerGapsWithoutBiblePrivateOrDirectionClaims() {
        var gained = 0; var gainedWords = 0; var newPrayerWords = 0
        for (case in cases) {
            val input = ThanksgivingTranslationFixtures.text(case["fixtureResource"].asString)
            assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(input))
            val key = ThanksgivingTranslationFixtures.key(case)
            val doc = Jsoup.parse(input, key.sourceUrl()); val untouchedDoc = doc.outerHtml()
            val before = RemainingThirtyFixtures.before(case); val untouchedBook = Gson().toJson(before)
            val expected = case["expected"].asJsonArray.map { it.asJsonObject }
            val coverage = case["publicPrayerCoverage"].asJsonObject
            val beforePublic = RemainingThirtyFixtures.publicPrayer(before)
            assertEquals(coverage["blockCount"].asInt, beforePublic.size)
            assertEquals(coverage["wholeSourceWords"].asInt, beforePublic.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            assertEquals(coverage["availableBlocksBefore"].asInt, beforePublic.count { it.russian != null })
            assertEquals(coverage["availableWholeSourceWordsBefore"].asInt, beforePublic.filter { it.russian != null }.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            fun printed(proof: JsonObject): String {
                val row = rows.single { it["id"] == proof["itemId"] }
                return listOf(proof["sourcePrefix"].asString, row["original"].asString).filter(String::isNotBlank).joinToString(" ")
            }
            // Repeated complete responses have distinct real paragraphs and IDs, not reused presence proofs.
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
            val afterPublic = RemainingThirtyFixtures.publicPrayer(after)
            assertEquals(case["expectedPublicMissingBefore"].asInt, beforePublic.count { it.russian == null })
            assertEquals(case["expectedPublicMissingAfter"].asInt, afterPublic.count { it.russian == null })
            assertEquals(coverage["availableBlocksAfter"].asInt, afterPublic.count { it.russian != null })
            assertEquals(coverage["availableWholeSourceWordsAfter"].asInt, afterPublic.filter { it.russian != null }.sumOf { WeekdayAntiphonFixtures.words(it.original) })
            assertEquals(coverage["gainWholeBlocks"].asInt, expected.size)
            assertEquals(coverage["gainWholeSourceWords"].asInt, expected.sumOf { it["originalWords"].asInt })
            assertEquals(coverage["newPrayerTranslationSourceWords"].asInt, expected.sumOf { it["newPrayerWords"].asInt })
            assertEquals(untouchedBook, Gson().toJson(before)); assertEquals(untouchedDoc, doc.outerHtml())
            assertNull(SavedBookEnrichment.apply(after, own))
            gained += expected.size; gainedWords += expected.sumOf { it["originalWords"].asInt }; newPrayerWords += expected.sumOf { it["newPrayerWords"].asInt }
        }
        assertEquals(3, cases.size); assertEquals(32, gained); assertEquals(386, gainedWords); assertEquals(325, newPrayerWords)
    }

    @Test fun wholeCodecRejectsWrongSaintNearMixedScriptRoleToneBodyRussianMeaningAndSource() {
        val decoded = BookTranslationIndex.decode(own.encode())
        val corpus = BookTranslationCorpus(WeekdayAntiphonFixtures.previousPublished.plus(decoded), setOf(BookTranslationIndex.HOUR3_SOURCE.url))
        val roundTrip = BookTranslationCorpus.decode(corpus.encode())
        assertEquals(13, roundTrip.version); assertEquals(corpus.completedSources, roundTrip.completedSources)
        for (row in rows) {
            val original = row["original"].asString; val pair = requireNotNull(own.find(original))
            assertEquals(pair, decoded.find(original)); assertEquals(pair, roundTrip.index.find(original))
            for (changed in listOf("Не $original", "$original QA", original.substringBeforeLast(' '),
                "Хор: $original", "1 Лик: $original", "Иерей: $original", "Глас 9: $original", "Припев: $original")) {
                assertNull(changed, own.find(changed))
                assertFalse(FeoriaRemainingThirtyTranslations.recognizes(changed, pair))
            }
            assertFalse(FeoriaRemainingThirtyTranslations.recognizes(original, pair.copy(russian = pair.russian + " QA")))
            assertFalse(FeoriaRemainingThirtyTranslations.recognizes(original, pair.copy(meaning = "QA meaning")))
            for (other in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                source.copy(url = "https://example.org/other"), source.copy(title = "Other edition")))
                assertFalse(FeoriaRemainingThirtyTranslations.recognizes(original, pair.copy(source = other)))
            val forged = row.deepCopy().apply { addProperty("russian", pair.russian + " QA") }
            assertNull(WeekdayAntiphonFixtures.ownIndex(listOf(forged)).find(original))
        }
        for (near in oracle["publishedNearOriginalsNotAccepted"].asJsonArray.map { it.asJsonObject }) {
            assertEquals(near["originalSha256"].asString, BookText.sha256(near["original"].asString))
            assertNull(own.find(near["original"].asString))
        }
        val matrona = rows.single { it["rank"].asInt == 30 }["original"].asString
        assertTrue(matrona.contains("Матро́но")); assertNull(own.find(matrona.replace("Матро́но", "Мари́е")))
        for (rank in listOf(70, 71, 72)) {
            val original = rows.single { it["rank"].asInt == rank }["original"].asString
            assertNull(own.find(original.replace(Regex("глас [178]"), "глас 9")))
        }
    }

    @Test fun anAcceptedPublishedPairWinsAndAnUnknownSourceCannotFillTheApprovedSlot() {
        val case = cases.first(); val plain = RemainingThirtyFixtures.before(case)
        val targetProof = case["expected"].asJsonArray.first().asJsonObject
        val targetRow = rows.single { it["id"] == targetProof["itemId"] }
        val target = targetProof["blockId"].asString
        val before = plain.copy(chapters = plain.chapters.map { c -> c.copy(blocks = c.blocks.map { b ->
            if (b.id == target) b.copy(russian = "Accepted published QA", translationSource = BookTranslationIndex.TIMROT_SOURCE) else b
        }) }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertEquals(case["expected"].asJsonArray.size() - 1, before.blocks.zip(after.blocks).count { it.first != it.second })
        assertEquals(before.blocks.single { it.id == target }, after.blocks.single { it.id == target })
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        val unknown = BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", targetRow["original"].asString); addProperty("russian", "Unknown QA")
            addProperty("source", "Unknown source"); addProperty("url", "https://example.org/unknown")
        }) }.toString())
        assertNull(SavedBookEnrichment.apply(plain, unknown))
    }

    @Test fun acceptedC1GainsOnlyOneNewWholeLexical58BeyondHistorical111AndPriorCommon19Two() {
        val proof = oracle["matinsC1"].asJsonObject
        val input = ThanksgivingTranslationFixtures.text(proof["jvmResource"].asString)
        assertEquals(proof["sha256"].asString, BookText.sha256(input))
        val accepted = Gson().fromJson(input, ServiceBook::class.java)
        val originalBytes = Gson().toJson(accepted)
        assertEquals(proof["literalAcceptedContentHash"].asString, accepted.contentHash)
        val before = SavedBookEnrichment.apply(accepted, RemainingThirtyFixtures.previousOwn163) ?: accepted
        val expected = proof["newApprovedOccurrencesOnly"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(58), expected.map { it["rank"].asInt }); assertEquals(1, expected.size)
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        assertOnlyApproved(before, after, expected)
        assertEquals(originalBytes, Gson().toJson(accepted))
        assertEquals(input, ThanksgivingTranslationFixtures.text(proof["jvmResource"].asString))
        // Do not rewrite the accepted Android contentHash to satisfy JVM serialization.
        assertEquals(proof["literalAcceptedContentHash"].asString, accepted.contentHash)
        assertTrue(ServiceBookIntegrity.valid(after)); assertNull(SavedBookEnrichment.apply(after, own))
    }

    @Test fun localCandidatePrecedesAnyHttpAndKeepsOldBytesCorpusAndCursorUntilExplicitAcceptAndDiskReopen() = runBlocking {
        val case = cases.first(); val before = RemainingThirtyFixtures.before(case)
        val cache = FileBookCache(directory); assertTrue(cache.write(before))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }; val oldBytes = oldFile.readBytes()
        val corpus = BookTranslationCorpus(ThanksgivingTranslationFixtures.baselinePublished(case)).encode()
        assertTrue(cache.writeTranslationCorpus(corpus))
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val target = case["expected"].asJsonArray.first().asJsonObject
        val row = rows.single { it["id"] == target["itemId"] }
        val position = BookReadingPosition(before.key, before.contentHash, "remaining30", row["sourceBlockId"].asString,
            row["sourceChapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
            wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position)); var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local30 must precede HTTP") }, translations = own)
        val result = requireNotNull(repository.enrichSaved(before))
        assertTrue(result.saved); assertTrue(result.fromCache)
        assertOnlyApproved(before, result.book, case["expected"].asJsonArray.map { it.asJsonObject })
        assertEquals(0, requests); assertEquals(position, store.last("remaining30"))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, cache.readTranslationCorpus())
        assertTrue(store.acceptVersion(before, result.book, "remaining30", position))
        val accepted = position.copy(contentHash = result.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("remaining30"))
        val reopened = FileBookCache(directory)
        assertEquals(before, reopened.readVersion(before.key, before.contentHash))
        assertEquals(result.book, reopened.readVersion(before.key, result.book.contentHash))
        val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
        assertEquals(result.book, (offline.load(before.key) as BookLoadResult.Ready).book)
        assertEquals(before, offline.cachedVersion(before.key, before.contentHash))
        assertEquals(accepted, store.restore(result.book, "remaining30"))
        assertNull(offline.enrichSaved(result.book, allowNetwork = false))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, reopened.readTranslationCorpus()); assertEquals(0, requests)
    }
}
