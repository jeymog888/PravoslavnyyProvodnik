package ru.liturgyguide.service.book

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID

/** Three isolated Android persistence replays cover all30 full pairs; no physical process restart claimed. */
@RunWith(AndroidJUnit4::class)
class FeoriaRemainingThirtyNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun asset(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }

    private fun ownIndex(rows: List<JsonObject>): BookTranslationIndex = BookTranslationIndex.decode(JsonArray().apply {
        rows.forEach { row -> add(JsonObject().apply {
            add("original", row["original"]); add("russian", row["russian"])
            addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
            addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
        }) }
    }.toString())

    @Test fun allThirtyFullRussianPairsReachExplicitAcceptanceAndBothEditionsSurviveObjectRecreationWithoutHttp() = runBlocking {
        val input = asset("russian-remaining-thirty/oracle.json")
        assertEquals("676d833a5e04395f315f7d5a88c2db030db4b2f66b879e47adf41aa4b8606aa4", BookText.sha256(input))
        val oracle = JsonParser.parseString(input).asJsonObject
        val proof = oracle["previous163"].asJsonObject
        fun readSealed(name: String, seal: String): JsonObject {
            val value = asset(name); assertEquals(seal, BookText.sha256(value))
            return JsonParser.parseString(value).asJsonObject
        }
        val weekday = readSealed(proof["weekday141Asset"].asString, proof["weekday141Sha256"].asString)
        val thanks = readSealed(proof["thanksgiving3Asset"].asString, proof["thanksgiving3Sha256"].asString)
        val common = readSealed(proof["common19Asset"].asString, proof["common19Sha256"].asString)
        val oldRows = (weekday["previous126"].asJsonArray.toList() + weekday["items"].asJsonArray.toList() +
            thanks["items"].asJsonArray.toList() + common["items"].asJsonArray.toList()).map { it.asJsonObject }
        assertEquals(163, oldRows.size)
        val projection = JsonArray().apply { oldRows.forEach { row -> add(JsonArray().apply {
            add(row["id"]); add(row["original"]); add(row["russian"]); add(row["revision"]); add(row["originalSourceUrl"])
        }) } }
        assertEquals(proof["projectionSha256"].asString,
            BookText.sha256(GsonBuilder().disableHtmlEscaping().create().toJson(projection)))
        val oldOwn = ownIndex(oldRows); assertEquals(163, oldOwn.size)
        val sundayProof = oracle["nativeSunday8"].asJsonObject
        val sunday = readSealed(sundayProof["asset"].asString, sundayProof["sha256"].asString)
        val baseline = readSealed(sundayProof["baselineAsset"].asString, sundayProof["baselineSha256"].asString)
        val priorSunday8 = BookTranslationIndex.decode(JsonArray().apply {
            sunday["items"].asJsonArray.map { it.asJsonObject }.forEach { row -> add(JsonObject().apply {
                add("original", row["original"]); add("russian", row["russian"])
                add("source", row["source"].asJsonObject["title"]); add("url", row["source"].asJsonObject["url"])
            }) }
        }.toString())
        assertEquals(8, priorSunday8.size)
        val rows = oracle["items"].asJsonArray.map { it.asJsonObject }.associateBy { it["id"].asString }
        val cases = oracle["cases"].asJsonArray.map { it.asJsonObject }
        val own = BookTranslationIndex.fromFeoriaCatalog()
        assertEquals(8, FeoriaTranslationCatalog.REVISION); assertEquals(193, own.size); assertEquals(13, BookTranslationCorpus.VERSION)
        val seenItems = mutableSetOf<String>(); val seenOccurrences = mutableSetOf<Pair<String, String>>()
        for (case in cases) {
            val fields = case["bookKey"].asString.split('|')
            val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), fields[2])
            val html = asset(case["nativeSourceAsset"].asString)
            assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(html))
            val published = if (case["baselinePublishedKind"].asString == "WEEKDAY201")
                BookTranslationIndex.decode(weekday["previousPublishedPairs"].toString())
            else BookTranslationIndex.decode(baseline["cases"].asJsonArray.map { it.asJsonObject }
                .single { it["bookKey"] == case["bookKey"] }["pairs"].toString())
            val parsed = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1L, translations = published.plus(oldOwn))
            // Reconstruct only the independently approved earlier Sunday8; never derive expected pairs from current193.
            val before = SavedBookEnrichment.apply(parsed, priorSunday8) ?: parsed
            assertTrue(ServiceBookIntegrity.valid(before))
            val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
            val scope = BookVisibilityScope.of(before)
            fun public(book: ServiceBook) = book.blocks.filter { it.kind == BookBlockKind.PRAYER && !scope.isPrivate(it) }
            assertEquals(case["expectedPublicMissingBefore"].asInt, public(before).count { it.russian == null })
            val context = instrumentation.targetContext
            val name = "remaining-thirty-${UUID.randomUUID()}"
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val directory = File(context.cacheDir, name)
            try {
                val cache = FileBookCache(directory); assertTrue(cache.write(before))
                val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
                val oldBytes = oldFile.readBytes(); val target = expected.values.first()
                val store = BookReadingStore(preferences)
                val position = BookReadingPosition(key, before.contentHash, name, target["blockId"].asString,
                    target["chapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
                    wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
                assertTrue(store.save(position)); val originalPreferences = preferences.all.toMap()
                val corpus = BookTranslationCorpus(published).encode(); assertTrue(cache.writeTranslationCorpus(corpus))
                var requests = 0
                val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local30 must precede HTTP") }, translations = own)
                // Default allowNetwork=true must return the local candidate before any optional request.
                val result = requireNotNull(repository.enrichSaved(before)); val candidate = result.book
                assertTrue(result.saved); assertTrue(result.fromCache)
                val changed = before.blocks.zip(candidate.blocks).filter { it.first != it.second }
                assertEquals(expected.keys, changed.map { it.first.id }.toSet()); assertEquals(expected.size, changed.size)
                for ((old, next) in changed) {
                    val occurrence = expected.getValue(old.id); val row = rows.getValue(occurrence["itemId"].asString)
                    assertEquals(BookBlockKind.PRAYER, old.kind); assertFalse(scope.isPrivate(old)); assertNull(old.russian)
                    assertEquals(row["original"].asString, old.original)
                    assertEquals(occurrence["originalSha256"].asString, BookText.sha256(next.original))
                    assertEquals(row["russian"].asString, next.russian)
                    assertEquals(occurrence["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                    assertEquals(row["russian"].asString, BookBlockPresentation.effectiveRussian(key, next))
                    assertEquals(old.copy(russian = next.russian, translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), next)
                    seenItems += row["id"].asString; seenOccurrences += case["bookKey"].asString to old.id
                }
                before.blocks.zip(candidate.blocks).filter { it.first.russian != null || it.first.kind != BookBlockKind.PRAYER || scope.isPrivate(it.first) }
                    .forEach { (a, b) -> assertEquals(a, b) }
                assertEquals(before.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
                assertEquals(before, candidate.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
                assertEquals(0, public(candidate).count { it.russian == null })
                assertEquals(position, store.last(name)); assertEquals(originalPreferences, preferences.all)
                assertEquals(before, cache.readVersion(key, before.contentHash))
                assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, cache.readTranslationCorpus()); assertEquals(0, requests)
                assertTrue(store.acceptVersion(before, candidate, name, position))
                val accepted = position.copy(contentHash = candidate.contentHash, fraction = 0f)
                assertEquals(accepted, BookReadingStore(preferences).last(name))
                val reopened = FileBookCache(directory)
                val restoredOld = requireNotNull(reopened.readVersion(key, before.contentHash))
                val restoredNew = requireNotNull(reopened.readVersion(key, candidate.contentHash))
                assertEquals(before, restoredOld); assertEquals(candidate, restoredNew)
                assertEquals(accepted, BookReadingStore(preferences).restore(restoredNew, name))
                for (occurrence in expected.values) {
                    val row = rows.getValue(occurrence["itemId"].asString)
                    assertNull(BookBlockPresentation.effectiveRussian(key, restoredOld.blocks.single { it.id == occurrence["blockId"].asString }))
                    val block = restoredNew.blocks.single { it.id == occurrence["blockId"].asString }
                    assertEquals(row["russian"].asString, BookBlockPresentation.effectiveRussian(key, block))
                    assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, block.translationSource)
                }
                val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
                assertEquals(restoredNew, (offline.load(key) as BookLoadResult.Ready).book)
                assertEquals(restoredOld, offline.cachedVersion(key, before.contentHash)); assertEquals(restoredNew, offline.cachedVersion(key, candidate.contentHash))
                assertNull(offline.enrichSaved(restoredNew, allowNetwork = false))
                assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, reopened.readTranslationCorpus()); assertEquals(0, requests)
            } finally {
                context.deleteSharedPreferences(name); directory.deleteRecursively()
            }
        }
        assertEquals(3, cases.size); assertEquals(30, seenItems.size); assertEquals(32, seenOccurrences.size)
    }
}
