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

/** Isolated real Android disk/acceptance replay; never changes the user's books or preferences. */
@RunWith(AndroidJUnit4::class)
class FeoriaWeekdayAntiphonNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun asset(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }

    @Test fun fifteenWholeAntiphonPairsArriveBeforeNetworkAndBothAcceptedEditionsReopenOffline() = runBlocking {
        val oracleText = asset("russian-weekday-antiphons/oracle.json")
        assertEquals("ae58a078ec768a1082a956066e9d49bf17958f9d918e8a5c894df9a3831b11d0", BookText.sha256(oracleText))
        val oracle = JsonParser.parseString(oracleText).asJsonObject
        val rows = oracle["items"].asJsonArray.map { it.asJsonObject }
        val expected = rows.associateBy { it["sourceBlockId"].asString }
        val oldOwn = BookTranslationIndex.decode(JsonArray().apply { oracle["previous126"].asJsonArray.forEach { value ->
            val row = value.asJsonObject
            add(JsonObject().apply {
                add("original", row["original"]); add("russian", row["russian"])
                addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
            })
        } }.toString())
        assertEquals(126, oldOwn.size)
        val published = BookTranslationIndex.decode(oracle["previousPublishedPairs"].toString())
        assertEquals(201, published.size)
        val fields = oracle["bookKey"].asString.split('|')
        val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), fields[2])
        val html = asset(oracle["source"].asJsonObject["nativeAsset"].asString)
        assertEquals(oracle["source"].asJsonObject["sourceFixtureSha256"].asString, BookText.sha256(html))
        val before = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1L,
            translations = published.plus(oldOwn))
        assertTrue(ServiceBookIntegrity.valid(before))
        // Pin this exact15 history to its independent old141; thanksgiving3 is tested separately.
        val own = BookTranslationIndex.decode(JsonArray().apply {
            (oracle["previous126"].asJsonArray.toList() + oracle["items"].asJsonArray.toList()).forEach { value ->
                val row = value.asJsonObject
                add(JsonObject().apply {
                    add("original", row["original"]); add("russian", row["russian"])
                    addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                    addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
                })
            }
        }.toString())
        assertEquals(5, FeoriaTranslationCatalog.entries.take(141).maxOf { it.revision }); assertEquals(141, own.size)
        assertEquals(14, BookTranslationCorpus.VERSION)
        val context = instrumentation.targetContext
        val name = "weekday-antiphon-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, name)
        try {
            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
            val oldBytes = oldFile.readBytes()
            val row = rows.first()
            val store = BookReadingStore(preferences)
            val position = BookReadingPosition(key, before.contentHash, name, row["sourceBlockId"].asString,
                row["chapterId"].asString, fraction = .43f, mode = BookReadingMode.RUSSIAN,
                wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
            assertTrue(store.save(position))
            val oldPreferences = preferences.all.toMap()
            val corpus = BookTranslationCorpus(published).encode()
            assertTrue(cache.writeTranslationCorpus(corpus))
            var requests = 0
            val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Local data must precede HTTP") }, translations = own)
            // The default allowNetwork=true proves local additions are offered before optional downloads.
            val result = requireNotNull(repository.enrichSaved(before))
            val candidate = result.book
            assertTrue(result.fromCache); assertTrue(result.saved)
            val changed = before.blocks.zip(candidate.blocks).filter { it.first != it.second }
            assertEquals(15, changed.size)
            assertEquals(expected.keys, changed.map { it.first.id }.toSet())
            changed.forEach { (old, next) ->
                val approved = expected.getValue(old.id)
                assertNull(old.russian)
                assertEquals(approved["original"].asString, old.original)
                assertEquals(approved["originalSha256"].asString, BookText.sha256(next.original))
                assertEquals(approved["russian"].asString, next.russian)
                assertEquals(approved["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                assertEquals(old.copy(russian = next.russian, translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), next)
            }
            before.blocks.zip(candidate.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
            assertEquals(before.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
            val scope = BookVisibilityScope.of(before)
            fun missing(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) }
            assertEquals(49, missing(before)); assertEquals(34, missing(candidate))
            assertEquals(position, store.last(name)); assertEquals(oldPreferences, preferences.all)
            assertEquals(before, cache.readVersion(key, position.contentHash))
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertEquals(corpus, cache.readTranslationCorpus())
            assertEquals(0, requests)
            assertTrue(store.acceptVersion(before, candidate, name, position))
            val accepted = position.copy(contentHash = candidate.contentHash, fraction = 0f)
            assertEquals(accepted, BookReadingStore(preferences).last(name))
            val reopened = FileBookCache(directory)
            assertEquals(before, reopened.readVersion(key, before.contentHash))
            assertEquals(candidate, reopened.readVersion(key, candidate.contentHash))
            assertEquals(accepted, BookReadingStore(preferences).restore(candidate, name))
            val offline = ServiceBookRepository(reopened, ScheduleTransport { requests++; error("Offline reopen") }, translations = own)
            assertEquals(candidate, (offline.load(key) as BookLoadResult.Ready).book)
            assertNull(offline.enrichSaved(candidate, allowNetwork = false))
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertEquals(corpus, reopened.readTranslationCorpus())
            assertEquals(0, requests)
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
