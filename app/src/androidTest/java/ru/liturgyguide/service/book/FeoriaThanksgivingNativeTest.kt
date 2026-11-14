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

/** New three on real Android persistence, with isolated UUID preferences/cache and no live requests. */
@RunWith(AndroidJUnit4::class)
class FeoriaThanksgivingNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun asset(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }

    @Test fun finalThreeArriveBeforeHttpAndOldBytesAndCursorStayPinnedUntilAcceptanceAndOfflineRecreation() = runBlocking {
        val input = asset("russian-thanksgiving-three/oracle.json")
        assertEquals("2cee98edf7fc1029c8fdaaa80352295eab86f871ce604479a991f4bc1230ff1a", BookText.sha256(input))
        val oracle = JsonParser.parseString(input).asJsonObject
        val previousProof = oracle["previous141"].asJsonObject
        val previousText = asset(previousProof["fixturePath"].asString.removePrefix("app/src/androidTest/assets/"))
        assertEquals(previousProof["fixtureSha256"].asString, BookText.sha256(previousText))
        val previous = JsonParser.parseString(previousText).asJsonObject
        val oldOwn = BookTranslationIndex.decode(JsonArray().apply {
            (previous["previous126"].asJsonArray.toList() + previous["items"].asJsonArray.toList()).forEach { value ->
                val row = value.asJsonObject
                add(JsonObject().apply {
                    add("original", row["original"]); add("russian", row["russian"])
                    addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                    addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
                })
            }
        }.toString())
        assertEquals(141, oldOwn.size)
        val published = BookTranslationIndex.decode(previous["previousPublishedPairs"].toString())
        assertEquals(201, published.size)
        val case = oracle["cases"].asJsonArray.first().asJsonObject
        val fields = case["bookKey"].asString.split('|')
        val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), fields[2])
        val html = asset(oracle["nativeSourceAsset"].asString)
        assertEquals(oracle["nativeSourceSha256"].asString, BookText.sha256(html))
        val before = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1L,
            translations = published.plus(oldOwn))
        assertTrue(ServiceBookIntegrity.valid(before))
        val rows = oracle["items"].asJsonArray.map { it.asJsonObject }.associateBy { it["id"].asString }
        val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
        // Retain the exact3 history on the independently pinned144; common-tail19 is tested separately.
        val own = BookTranslationIndex.decode(JsonArray().apply {
            (previous["previous126"].asJsonArray.toList() + previous["items"].asJsonArray.toList() + oracle["items"].asJsonArray.toList()).forEach { value ->
                val row = value.asJsonObject
                add(JsonObject().apply {
                    add("original", row["original"]); add("russian", row["russian"])
                    addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                    addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
                })
            }
        }.toString())
        assertEquals(6, FeoriaTranslationCatalog.entries.take(144).maxOf { it.revision }); assertEquals(144, own.size)
        assertEquals(13, BookTranslationCorpus.VERSION)
        val context = instrumentation.targetContext
        val name = "thanksgiving-three-${UUID.randomUUID()}"
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
            val oldPreferences = preferences.all.toMap()
            val corpus = BookTranslationCorpus(published).encode()
            assertTrue(cache.writeTranslationCorpus(corpus))
            var requests = 0
            val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local data must arrive before HTTP") }, translations = own)
            // Default allowNetwork=true proves this is a local early result, not only a disabled option.
            val result = requireNotNull(repository.enrichSaved(before))
            assertTrue(result.saved); assertTrue(result.fromCache)
            val candidate = result.book
            val changed = before.blocks.zip(candidate.blocks).filter { it.first != it.second }
            assertEquals(3, changed.size); assertEquals(expected.keys, changed.map { it.first.id }.toSet())
            changed.forEach { (old, next) ->
                val proof = expected.getValue(old.id)
                val row = rows.getValue(proof["itemId"].asString)
                assertNull(old.russian)
                assertEquals(row["original"].asString, old.original)
                assertEquals(proof["originalSha256"].asString, BookText.sha256(next.original))
                assertEquals(row["russian"].asString, next.russian)
                assertEquals(proof["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                assertEquals(old.copy(russian = next.russian, translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), next)
            }
            before.blocks.zip(candidate.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
            assertEquals(before.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
            assertEquals(before, candidate.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
            val scope = BookVisibilityScope.of(before)
            fun missing(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) }
            assertEquals(34, missing(before)); assertEquals(31, missing(candidate))
            assertEquals(position, store.last(name)); assertEquals(oldPreferences, preferences.all)
            assertEquals(before, cache.readVersion(key, before.contentHash))
            assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, cache.readTranslationCorpus())
            assertEquals(0, requests)
            assertTrue(store.acceptVersion(before, candidate, name, position))
            val accepted = position.copy(contentHash = candidate.contentHash, fraction = 0f)
            assertEquals(accepted, BookReadingStore(preferences).last(name))
            val reopened = FileBookCache(directory)
            assertEquals(before, reopened.readVersion(key, before.contentHash))
            assertEquals(candidate, reopened.readVersion(key, candidate.contentHash))
            assertEquals(accepted, BookReadingStore(preferences).restore(candidate, name))
            val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline") }, translations = own)
            assertEquals(candidate, (offline.load(key) as BookLoadResult.Ready).book)
            assertNull(offline.enrichSaved(candidate, allowNetwork = false))
            assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpus, reopened.readTranslationCorpus())
            assertEquals(0, requests)
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
