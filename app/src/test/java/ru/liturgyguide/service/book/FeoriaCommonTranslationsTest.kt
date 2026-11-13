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
import java.io.File
import java.nio.file.Files

/** One approved data oracle shared with instrumentation; never derived from production entries. */
internal object CommonTranslationFixtures {
    private const val ORACLE = "app/src/androidTest/assets/russian-coverage-12/common10-oracle.json"
    private fun sourceFile(path: String): File = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
        ?: error("Source-controlled fixture is missing: $path")
    val oracle: JsonObject by lazy { JsonParser.parseString(sourceFile(ORACLE).readText()).asJsonObject }
    val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    val rowsById get() = rows.associateBy { it["id"].asString }
    val cases get() = oracle["cases"].asJsonArray.map { it.asJsonObject }
    val archiveExpected get() = oracle["acceptedMatinsArchive"].asJsonObject["expected"].asJsonArray.map { it.asJsonObject }
    fun text(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
    fun nativeAsset(path: String): String = sourceFile("app/src/androidTest/assets/$path").readText()
    fun index(values: List<JsonObject>): BookTranslationIndex = BookTranslationIndex.decode(JsonArray().apply {
        values.forEach { row -> add(JsonObject().apply {
            add("original", row["original"]); add("russian", row["russian"])
            addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
            addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
        }) }
    }.toString())
    val previousRows: List<JsonObject> by lazy {
        JsonParser.parseString(text("russian-coverage-12/previous11-approved.json")).asJsonArray.map { it.asJsonObject } +
            JsonParser.parseString(text("russian-coverage-12/canon-105-oracle.json")).asJsonObject["items"].asJsonArray.map { it.asJsonObject }
    }
    val previousIndex by lazy { index(previousRows) }
    val previousWithPublished by lazy {
        val corpus = BookTranslationCorpus.decode(text(oracle["baselinePublishedCorpus"].asJsonObject["fixture"].asString))
        corpus.index.plus(previousIndex)
    }
    fun archivedMatins(): ServiceBook = Gson().fromJson(text("legacy-presentation/matins-accepted-unchanged.json"), ServiceBook::class.java)
    fun previousEdition(book: ServiceBook): ServiceBook = SavedBookEnrichment.apply(book, previousWithPublished) ?: book
    fun plain(case: JsonObject): ServiceBook {
        val body = text(case["fixture"].asString)
        if (case["kind"].asString == "ARCHIVE") return Gson().fromJson(body, ServiceBook::class.java)
        val fields = case["bookKey"].asString.split('|')
        val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), fields[2])
        return ServiceBookParser().parse(Jsoup.parse(body, key.sourceUrl()), key, 1L)
    }
}

class FeoriaCommonTranslationsTest {
    // New weekday pairs have a separate exact15 oracle; preserve this exact48 historical delta.
    private val own = WeekdayAntiphonFixtures.previousOwn126
    private val source = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
    private val directory = Files.createTempDirectory("feoria-common-12-").toFile()
    private val rows get() = CommonTranslationFixtures.rows
    @After fun clean() { directory.deleteRecursively() }

    @Test fun allTenCompletePairsAndTheirProvenanceEqualTheIndependentApprovedOracle() {
        val oracle = CommonTranslationFixtures.oracle
        assertEquals("PASS", oracle["semanticVerdict"].asString)
        assertEquals(4, FeoriaTranslationCatalog.entries.take(126).maxOf { it.revision })
        assertEquals(126, own.size)
        assertEquals(10, rows.size)
        assertEquals(10, FeoriaCommonTranslations.records.size)
        assertEquals(206, rows.sumOf { it["originalWords"].asInt })
        assertEquals(48, rows.sumOf { it["occurrences"].asJsonArray.size() })
        assertEquals(oracle["sourceCandidateSha256"].asString, FeoriaCommonTranslations.SOURCE_CANDIDATE_SHA256)
        assertEquals(oracle["independentSemanticReviewSha256"].asString, FeoriaCommonTranslations.SEMANTIC_REVIEW_SHA256)
        val corpusProof = oracle["baselinePublishedCorpus"].asJsonObject
        val corpusText = CommonTranslationFixtures.text(corpusProof["fixture"].asString)
        assertEquals(corpusProof["sourceFixtureSha256"].asString, BookText.sha256(corpusText))
        assertEquals(3694, BookTranslationCorpus.decode(corpusText).index.size)
        val records = FeoriaCommonTranslations.records.associateBy { it.entry.id }
        val decoded = BookTranslationIndex.decode(own.encode())
        for (row in rows) {
            val record = records.getValue(row["id"].asString)
            val original = row["original"].asString
            val russian = row["russian"].asString
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedWholeOriginal"].asString, BookText.normalized(original))
            assertEquals(row["normalizedWholeOriginalSha256"].asString, BookText.sha256(BookText.normalized(original)))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(original, record.entry.original); assertEquals(russian, record.entry.russian)
            assertEquals(4, record.entry.revision)
            assertEquals(row["originalSha256"].asString, record.originalSha256)
            assertEquals(row["normalizedWholeOriginalSha256"].asString, record.normalizedOriginalSha256)
            assertEquals(row["russianSha256"].asString, record.russianSha256)
            val provenance = row["source"].asJsonObject
            assertEquals(provenance["originalSourceUrl"].asString, record.entry.originalSourceUrl)
            assertEquals(provenance["sourceFileSha256"].asString, record.sourceDocumentSha256)
            assertEquals(provenance["acceptedContentHashUnmodified"].asString, record.sourceContentHash)
            assertEquals(provenance["blockId"].asString, record.sourceBlockId)
            val pair = BookTranslation(russian, source)
            assertEquals(pair, own.find(original)); assertEquals(pair, decoded.find(original))
            assertTrue(FeoriaCommonTranslations.recognizes(original, pair))
            assertTrue(FeoriaTranslationCatalog.recognizes(original, pair))
            assertEquals(pair, own.find(BookText.normalized(original).uppercase()))
        }
    }

    @Test fun previousElevenAndAll105CanonPairsKeepTheirExactTextRevisionAndSource() {
        val previous = CommonTranslationFixtures.previousRows
        assertEquals(116, previous.size)
        assertEquals(116, CommonTranslationFixtures.previousIndex.size)
        for ((row, entry) in previous.zip(FeoriaTranslationCatalog.entries.take(116))) {
            assertEquals(row["id"].asString, entry.id)
            assertEquals(row["original"].asString.toByteArray().toList(), entry.original.toByteArray().toList())
            assertEquals(row["russian"].asString.toByteArray().toList(), entry.russian.toByteArray().toList())
            if (row.has("revision")) {
                assertEquals(row["revision"].asInt, entry.revision)
                assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            } else {
                assertEquals(3, entry.revision)
                assertEquals(row["source"].asJsonObject["url"].asString, entry.originalSourceUrl)
            }
        }
        val preserved = CommonTranslationFixtures.oracle["protectedPrevious"].asJsonObject
        for ((resource, field) in listOf("russian-coverage-12/previous11-approved.json" to "previous11OracleSha256",
            "russian-coverage-12/canon-105-oracle.json" to "canon105OracleSha256",
            "legacy-presentation/matins-accepted-unchanged.json" to "archiveMatinsSha256",
            "legacy-presentation/vespers-accepted-unchanged.json" to "archiveVespersSha256")) {
            assertEquals(resource, preserved[field].asString, BookText.sha256(CommonTranslationFixtures.text(resource)))
        }
        assertEquals(126, FeoriaTranslationCatalog.entries.take(126).map { BookTranslationIndex.normal(it.original) }.distinct().size)
    }

    @Test fun namesNegationsTruncationExtraLabelsAndForgedRussianCannotInheritAnyCommonPair() {
        for (row in rows) {
            val original = row["original"].asString
            val pair = requireNotNull(own.find(original))
            for (changed in listOf("Не $original", "$original Нет", original.substringBeforeLast(' '),
                "Хор: $original", "Иерей: $original", "Глас 6: $original", "Припев: $original", "$original (дважды)")) {
                assertNull(changed, own.find(changed))
                assertFalse(changed, FeoriaCommonTranslations.recognizes(changed, pair))
                assertFalse(changed, FeoriaTranslationCatalog.recognizes(changed, pair))
            }
            assertFalse(FeoriaCommonTranslations.recognizes(original, pair.copy(russian = pair.russian + " Не так")))
            assertFalse(FeoriaCommonTranslations.recognizes(original, pair.copy(meaning = "Пояснение")))
            for (other in listOf(BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
                source.copy(url = "https://example.org/other-edition"))) {
                assertFalse(FeoriaCommonTranslations.recognizes(original, pair.copy(source = other)))
            }
            val forged = row.deepCopy().apply { addProperty("russian", pair.russian + " Подмена") }
            assertNull(CommonTranslationFixtures.index(listOf(forged)).find(original))
        }
        val named = rows.single { it["number"].asInt == 4 }["original"].asString
        assertNull(own.find(named.replace("Иоа́нне Златоу́сте", "Васи́лие Вели́кий")))
        val temple = rows.first()["original"].asString
        assertNull(own.find(temple.replace("сего́", "иного")))
        assertNull(own.find(rows[0]["original"].asString + " " + rows[1]["original"].asString))
    }

    @Test fun nineMeasuredBookKeysGainExactly48CommonBlocksWithoutChangingAnyOldRussianOrOriginal() {
        val gson = Gson()
        val cases = CommonTranslationFixtures.cases
        assertEquals(9, cases.size)
        assertEquals(9, cases.map { it["bookKey"].asString }.distinct().size)
        assertEquals(listOf(7, 3, 6, 7, 3, 6, 7, 3, 6), cases.map { it["expectedCommonCount"].asInt })
        var total = 0
        for (case in cases) {
            val fixture = case["fixture"].asString
            val input = CommonTranslationFixtures.text(fixture)
            assertEquals(fixture, case["sourceFixtureSha256"].asString, BookText.sha256(input))
            val plain = CommonTranslationFixtures.plain(case)
            val plainSerialized = gson.toJson(plain)
            val before = CommonTranslationFixtures.previousEdition(plain)
            assertEquals(case["bookKey"].asString, before.key.canonical())
            val beforeSerialized = gson.toJson(before)
            val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
            val after = requireNotNull(SavedBookEnrichment.apply(before, own))
            val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
            assertEquals(case["bookKey"].asString, case["expectedCommonCount"].asInt, changed.size)
            assertEquals(expected.keys, changed.map { it.first.id }.toSet())
            for ((old, next) in changed) {
                val occurrence = expected.getValue(old.id)
                val row = CommonTranslationFixtures.rowsById.getValue(occurrence["itemId"].asString)
                assertNull("Each expected common block was missing in the previous edition", old.russian)
                assertEquals(occurrence["originalSha256"].asString, BookText.sha256(old.original))
                assertEquals(row["originalSha256"].asString, BookText.sha256(next.original))
                assertEquals(row["russian"].asString, next.russian)
                assertEquals(occurrence["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                assertEquals(old.copy(russian = row["russian"].asString, translationSource = source), next)
            }
            before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (old, next) -> assertEquals(old, next) }
            assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
            assertEquals(beforeSerialized, gson.toJson(before)); assertEquals(plainSerialized, gson.toJson(plain))
            assertTrue(ServiceBookIntegrity.valid(after))
            assertNull(SavedBookEnrichment.apply(after, own))
            assertEquals(input, CommonTranslationFixtures.text(fixture))
            total += changed.size
        }
        assertEquals(48, total)
    }

    @Test fun jvmEnrichedMatinsBaselineGetsOnlyThreeCommonBlocksAndBothJvmEditionsSurvive() = runBlocking {
        val archiveText = CommonTranslationFixtures.text("legacy-presentation/matins-accepted-unchanged.json")
        val proof = CommonTranslationFixtures.oracle["acceptedMatinsArchive"].asJsonObject
        assertEquals(proof["sourceFixtureSha256"].asString, BookText.sha256(archiveText))
        val original = CommonTranslationFixtures.archivedMatins()
        val archiveContentHash = proof["expected"].asJsonArray.first().asJsonObject["acceptedContentHashUnmodified"].asString
        assertEquals(archiveContentHash, original.contentHash)
        val serializedArchive = Gson().toJson(original)
        val archiveBytes = archiveText.toByteArray(Charsets.UTF_8)
        // The Android archive stays raw evidence. Its genuine Android hash is not
        // recomputed or rewritten to make it pass FileBookCache validation on the JVM.
        val jvmBaseline = CommonTranslationFixtures.previousEdition(original)
        assertTrue(ServiceBookIntegrity.valid(jvmBaseline))
        val cache = FileBookCache(directory)
        assertTrue(cache.write(jvmBaseline))
        val baselineDisk = directory.listFiles()!!.single { it.name.endsWith("-${jvmBaseline.contentHash}.json") }
        val baselineBytes = baselineDisk.readBytes()
        val expected = CommonTranslationFixtures.archiveExpected.associateBy { it["blockId"].asString }
        assertEquals(3, expected.size)
        val target = expected.keys.first()
        val chapter = jvmBaseline.chapters.single { c -> c.blocks.any { it.id == target } }
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val position = BookReadingPosition(jvmBaseline.key, jvmBaseline.contentHash, "common12", target, chapter.id,
            fraction = .42f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 104, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Common catalog is offline") }, translations = own)
        val candidate = requireNotNull(repository.enrichSaved(jvmBaseline, allowNetwork = false)).book
        val changed = jvmBaseline.blocks.zip(candidate.blocks).filter { it.first != it.second }
        assertEquals(3, changed.size); assertEquals(expected.keys, changed.map { it.first.id }.toSet())
        for ((old, next) in changed) {
            val occurrence = expected.getValue(old.id)
            assertEquals(occurrence["originalSha256"].asString, BookText.sha256(next.original))
            assertEquals(occurrence["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(old.copy(russian = next.russian, translationSource = source), next)
        }
        assertEquals(jvmBaseline.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
        assertEquals(position, store.last("common12"))
        assertArrayEquals(baselineBytes, baselineDisk.readBytes())
        assertTrue(store.acceptVersion(jvmBaseline, candidate, "common12", position))
        assertEquals(position.copy(contentHash = candidate.contentHash, fraction = 0f), store.last("common12"))
        val reopened = FileBookCache(directory)
        assertNull(reopened.readVersion(original.key, archiveContentHash))
        assertEquals(jvmBaseline, reopened.readVersion(jvmBaseline.key, jvmBaseline.contentHash))
        assertEquals(candidate, reopened.readVersion(candidate.key, candidate.contentHash))
        assertArrayEquals(baselineBytes, baselineDisk.readBytes())
        assertEquals(archiveContentHash, original.contentHash)
        assertEquals(serializedArchive, Gson().toJson(original))
        assertArrayEquals(archiveBytes, CommonTranslationFixtures.text("legacy-presentation/matins-accepted-unchanged.json").toByteArray(Charsets.UTF_8))
        assertEquals(0, requests)
        assertNull(repository.enrichSaved(candidate, allowNetwork = false))
    }

    @Test fun anExistingPublishedCommonPairCannotBeReplacedByTheNewOwnFallback() {
        val before = CommonTranslationFixtures.previousEdition(CommonTranslationFixtures.archivedMatins())
        val target = CommonTranslationFixtures.archiveExpected.first()["blockId"].asString
        val chapters = before.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map { block ->
            if (block.id == target) block.copy(russian = "Опубликованный перевод QA", translationSource = BookTranslationIndex.TIMROT_SOURCE) else block
        }) }
        val published = before.copy(chapters = chapters).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val after = requireNotNull(SavedBookEnrichment.apply(published, own))
        assertEquals(published.blocks.single { it.id == target }, after.blocks.single { it.id == target })
        assertEquals(2, published.blocks.zip(after.blocks).count { it.first != it.second })
        assertEquals(published.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
    }

    @Test fun historicalC34ArchiveGainsExactlyItsThreeCommonBodiesWithoutRewritingItsAcceptedHash() {
        val proof = CommonTranslationFixtures.oracle["historicalRankedMatinsArchive"].asJsonObject
        val raw = CommonTranslationFixtures.nativeAsset(proof["nativeFixture"].asString)
        assertEquals(proof["sourceFixtureSha256"].asString, BookText.sha256(raw))
        val original = Gson().fromJson(raw, ServiceBook::class.java)
        assertEquals(proof["acceptedContentHashUnmodified"].asString, original.contentHash)
        // Preserve the source Android hash; validate only the enriched JVM edition.
        val serializedOriginal = Gson().toJson(original)
        val before = CommonTranslationFixtures.previousEdition(original)
        assertTrue(ServiceBookIntegrity.valid(before))
        val serializedBefore = Gson().toJson(before)
        val expected = proof["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
        assertEquals(3, expected.size)
        val after = requireNotNull(SavedBookEnrichment.apply(before, own))
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(3, changed.size); assertEquals(expected.keys, changed.map { it.first.id }.toSet())
        for ((old, next) in changed) {
            val occurrence = expected.getValue(old.id)
            val row = CommonTranslationFixtures.rowsById.getValue(occurrence["itemId"].asString)
            assertNull(old.russian)
            assertEquals(occurrence["originalSha256"].asString, BookText.sha256(next.original))
            assertEquals(row["russian"].asString, next.russian)
            assertEquals(occurrence["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(old.copy(russian = next.russian, translationSource = source), next)
        }
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (old, next) -> assertEquals(old, next) }
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
        assertEquals(serializedOriginal, Gson().toJson(original)); assertEquals(serializedBefore, Gson().toJson(before))
        assertEquals(proof["acceptedContentHashUnmodified"].asString, original.contentHash)
        assertEquals(raw, CommonTranslationFixtures.nativeAsset(proof["nativeFixture"].asString))
    }
}
