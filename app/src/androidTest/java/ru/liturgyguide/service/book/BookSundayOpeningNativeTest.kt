package ru.liturgyguide.service.book

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** One isolated real Android cache/acceptance replay; no UI mount or live network. */
@RunWith(AndroidJUnit4::class)
class BookSundayOpeningNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun text(name: String) = instrumentation.context.assets.open("russian-sunday-opening/$name")
        .bufferedReader().use { it.readText() }

    @Test fun wholePublishedSundayOpeningIsOfferedOfflineAcceptedExplicitlyAndReopenedWithoutChangingOldBytes() = runBlocking {
        val oracle = JsonParser.parseString(text("oracle.json")).asJsonObject
        val case = oracle["cases"].asJsonArray.first().asJsonObject
        val rows = oracle["items"].asJsonArray.map { it.asJsonObject }.associateBy { it["id"].asString }
        val frozen = JsonParser.parseString(text("current126-baseline.json")).asJsonObject["cases"].asJsonArray.first().asJsonObject
        val keyFields = case["bookKey"].asString.split('|')
        val key = ServiceBookKey(keyFields[0], WorshipKind.valueOf(keyFields[1]), keyFields[2])
        val html = text("liturgy-2026-11-15.html")
        assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(html))
        val before = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1L,
            translations = BookTranslationIndex.decode(frozen["pairs"].toString()))
        assertTrue(ServiceBookIntegrity.valid(before))
        var fresh = BookTranslationIndex.empty()
        for ((file, source) in listOf("full-liturgy.html" to BookTranslationIndex.TIMROT_FULL_SOURCE,
            "sunday-7.html" to BookTranslationIndex.OCTOECHOS_SOURCES[6])) {
            val proof = rows.values.first { it["source"].asJsonObject["url"].asString == source.url }["source"].asJsonObject
            val body = text(file)
            assertEquals(proof["sourceFileSha256"].asString, BookText.sha256(body))
            fresh = fresh.fillingMissing(BookTranslationIndex.fromParallelPage(Jsoup.parse(body, source.url), source))
        }
        val expected = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
        assertEquals(7, expected.size)
        val id = expected.keys.first()
        val chapter = before.chapters.single { c -> c.blocks.any { it.id == id } }
        val context = instrumentation.targetContext
        val name = "sunday-opening-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, name)
        try {
            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
            val oldBytes = oldFile.readBytes()
            val store = BookReadingStore(preferences)
            val position = BookReadingPosition(key, before.contentHash, name, id, chapter.id, fraction = .4f,
                mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
            assertTrue(store.save(position))
            val oldPreferences = preferences.all.toMap()
            assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(fresh).encode()))
            var requests = 0
            val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Offline replay") },
                translations = BookTranslationIndex.empty())
            val candidate = requireNotNull(repository.enrichSaved(before, allowNetwork = false)).book
            val changed = before.blocks.zip(candidate.blocks).filter { it.first != it.second }
            assertEquals(expected.keys, changed.map { it.first.id }.toSet())
            assertEquals(7, changed.size)
            changed.forEach { (old, next) ->
                val item = rows.getValue(expected.getValue(old.id)["itemId"].asString)
                val proof = item["source"].asJsonObject
                assertNull(old.russian)
                assertEquals(item["original"].asString, next.original)
                assertEquals(item["russian"].asString, next.russian)
                assertEquals(item["originalSha256"].asString, BookText.sha256(next.original))
                assertEquals(item["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
                assertEquals(BookSource(proof["title"].asString, proof["url"].asString), next.translationSource)
            }
            assertEquals(before.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
            before.blocks.zip(candidate.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
            assertEquals(position, store.last(name)); assertEquals(oldPreferences, preferences.all)
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertTrue(store.acceptVersion(before, candidate, name, position))
            val accepted = requireNotNull(store.last(name))
            assertEquals(position.copy(contentHash = candidate.contentHash, fraction = 0f), accepted)
            val reopened = FileBookCache(directory)
            assertEquals(before, reopened.readVersion(key, before.contentHash))
            assertEquals(candidate, reopened.readVersion(key, candidate.contentHash))
            assertEquals(accepted, BookReadingStore(preferences).restore(candidate, name))
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertEquals(0, requests)
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
