package ru.liturgyguide.service.book

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Current163 on Android persistence; accepted144 and current candidate are distinct durable editions. */
@RunWith(AndroidJUnit4::class)
class FeoriaLiturgyCommonTailNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun asset(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }

    @Test fun approvedWholeRussianArrivesBeforeHttpAndBothEditionsReopenOfflineOnlyAfterExplicitAcceptance() = runBlocking {
        val input = asset("russian-liturgy-common-tail/oracle.json")
        assertEquals("66f6cef7e7debc91d3eeed3010ddc3e251022daaa2cb2450cede5df6778850f8", BookText.sha256(input))
        val oracle = JsonParser.parseString(input).asJsonObject
        val previousProof = oracle["previous144"].asJsonObject
        val weekdayInput = asset(previousProof["weekday141Asset"].asString)
        val thanksgivingInput = asset(previousProof["thanksgiving3Asset"].asString)
        assertEquals(previousProof["weekday141Sha256"].asString, BookText.sha256(weekdayInput))
        assertEquals(previousProof["thanksgiving3Sha256"].asString, BookText.sha256(thanksgivingInput))
        val weekday = JsonParser.parseString(weekdayInput).asJsonObject
        val thanksgiving = JsonParser.parseString(thanksgivingInput).asJsonObject
        val oldRows = weekday["previous126"].asJsonArray.toList() + weekday["items"].asJsonArray.toList() + thanksgiving["items"].asJsonArray.toList()
        val oldOwn = BookTranslationIndex.decode(JsonArray().apply {
            oldRows.forEach { value ->
                val row = value.asJsonObject
                add(JsonObject().apply {
                    add("original", row["original"]); add("russian", row["russian"])
                    addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                    addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
                })
            }
        }.toString())
        assertEquals(144, oldOwn.size)
        val published = BookTranslationIndex.decode(weekday["previousPublishedPairs"].toString())
        assertEquals(201, published.size)
        val case = oracle["cases"].asJsonArray.first().asJsonObject
        val fields = case["bookKey"].asString.split('|')
        val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), fields[2])
        val html = asset(oracle["nativeSourceAsset"].asString)
        assertEquals(oracle["nativeSourceSha256"].asString, BookText.sha256(html))
        val before = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1L, translations = published.plus(oldOwn))
        assertTrue(ServiceBookIntegrity.valid(before))
        val rows = oracle["items"].asJsonArray.map { it.asJsonObject }.associateBy { it["id"].asString }
        val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
        assertEquals(19, rows.size); assertEquals(22, expected.size)
        val own = BookTranslationIndex.fromFeoriaCatalog()
        assertEquals(7, FeoriaTranslationCatalog.REVISION); assertEquals(163, own.size); assertEquals(13, BookTranslationCorpus.VERSION)
        val context = instrumentation.targetContext
        val name = "liturgy-common-tail-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, name)
        try {
            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
            val oldBytes = oldFile.readBytes()
            val target = expected.values.first()
            val store = BookReadingStore(preferences)
            val position = BookReadingPosition(key, before.contentHash, name, target["blockId"].asString,
                target["chapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
                wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
            assertTrue(store.save(position))
            val originalPreferences = preferences.all.toMap()
            val corpus = BookTranslationCorpus(published).encode()
            assertTrue(cache.writeTranslationCorpus(corpus))
            var requests = 0
            val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local19 must precede HTTP") }, translations = own)
            // Default allowNetwork=true: prove local data returns before even an optional request.
            val result = requireNotNull(repository.enrichSaved(before))
            assertTrue(result.saved); assertTrue(result.fromCache)
            val candidate = result.book
            val changed = before.blocks.zip(candidate.blocks).filter { it.first != it.second }
            assertEquals(expected.keys, changed.map { it.first.id }.toSet()); assertEquals(22, changed.size)
            val scope = BookVisibilityScope.of(before)
            for ((old, next) in changed) {
                val proof = expected.getValue(old.id); val row = rows.getValue(proof["itemId"].asString)
                assertEquals(BookBlockKind.PRAYER, old.kind); assertFalse(scope.isPrivate(old)); assertNull(old.russian)
                assertEquals(row["original"].asString, old.original)
                assertEquals(proof["originalSha256"].asString, BookText.sha256(next.original))
                assertEquals(row["russian"].asString, next.russian)
                assertEquals(proof["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                assertEquals(row["russian"].asString, BookBlockPresentation.effectiveRussian(key, next))
                assertEquals(old.copy(russian = next.russian, translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), next)
            }
            before.blocks.zip(candidate.blocks).filter { it.first.russian != null || it.first.kind != BookBlockKind.PRAYER || scope.isPrivate(it.first) }
                .forEach { (a, b) -> assertEquals(a, b) }
            assertEquals(before.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
            assertEquals(before, candidate.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
            fun public(book: ServiceBook) = book.blocks.filter { it.kind == BookBlockKind.PRAYER && !scope.isPrivate(it) }
            assertEquals(case["expectedPublicMissingBefore"].asInt, public(before).count { it.russian == null })
            assertEquals(case["expectedPublicMissingAfter"].asInt, public(candidate).count { it.russian == null })
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
            // Every whole approved RU body is the real reading text after durable reopen, not a UI label.
            for (proof in expected.values) {
                val row = rows.getValue(proof["itemId"].asString)
                assertNull(BookBlockPresentation.effectiveRussian(key, restoredOld.blocks.single { it.id == proof["blockId"].asString }))
                val block = restoredNew.blocks.single { it.id == proof["blockId"].asString }
                assertEquals(row["russian"].asString, BookBlockPresentation.effectiveRussian(key, block))
            }
            val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
            assertEquals(restoredNew, (offline.load(key) as BookLoadResult.Ready).book)
            assertEquals(restoredOld, offline.cachedVersion(key, before.contentHash))
            assertEquals(restoredNew, offline.cachedVersion(key, candidate.contentHash))
            assertNull(offline.enrichSaved(restoredNew, allowNetwork = false))
            assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, reopened.readTranslationCorpus()); assertEquals(0, requests)
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
