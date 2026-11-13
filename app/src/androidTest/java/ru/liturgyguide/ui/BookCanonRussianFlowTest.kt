package ru.liturgyguide.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID

/** Full catalog on Android: real archive,108 canon +3 common blocks, explicit acceptance and disk remount. */
@RunWith(AndroidJUnit4::class)
class BookCanonRussianFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "canon-russian-${UUID.randomUUID()}"
    private val directory = File(context.cacheDir, name)
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun asset(path: String) = instrumentation.context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    @After fun clean() { context.deleteSharedPreferences(name); directory.deleteRecursively() }

    @Test fun all105BodiesAdd108CanonAnd3CommonBlocksOnlyToTheCandidateAndBothEditionsRemainOffline() = runBlocking {
        val json = asset("quiet-matins/matins-accepted-unchanged.json")
        val expected = JsonParser.parseString(asset("russian-coverage-12/canon105-expected.json")).asJsonObject
        val common = JsonParser.parseString(asset("russian-coverage-12/common10-oracle.json")).asJsonObject
        val commonArchive = common["acceptedMatinsArchive"].asJsonObject
        assertEquals(expected["originalArchiveSha256"].asString, BookText.sha256(json))
        assertEquals(commonArchive["sourceFixtureSha256"].asString, BookText.sha256(json))
        val original = Gson().fromJson(json, ServiceBook::class.java)
        assertTrue(ServiceBookIntegrity.valid(original))
        val cache = FileBookCache(directory)
        assertTrue(cache.write(original))
        val originalDisk = directory.listFiles()!!.single { it.name.endsWith("-${original.contentHash}.json") }
        val originalBytes = originalDisk.readBytes()
        // Reconstruct the prior accepted translation edition from its pinned previous11 pairs and published8 irmoi.
        val previous = JsonParser.parseString(asset("russian-coverage-12/previous11-approved.json")).asJsonArray
        var oldIndex = BookTranslationIndex.decode(JsonArray().apply { previous.forEach { value ->
            val row = value.asJsonObject
            add(JsonObject().apply {
                add("original", row["original"]); add("russian", row["russian"])
                addProperty("source", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.title)
                addProperty("url", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE.url)
            })
        } }.toString())
        for ((source, file) in listOf(BookPublishedIrmos6.SATURDAY to "great-saturday.html", BookPublishedIrmos6.FRIDAY to "great-friday.html")) {
            oldIndex = oldIndex.plus(BookTranslationIndex.fromParallelPage(Jsoup.parse(asset("published-russian/$file"), source.url), source))
        }
        val baseline = requireNotNull(SavedBookEnrichment.apply(original, oldIndex))
        val publishedBefore = baseline.blocks.filter { it.translationSource in BookPublishedIrmos6.SOURCES }
        assertEquals(8, publishedBefore.size)
        assertTrue(cache.write(baseline))
        val baselineDisk = directory.listFiles()!!.single { it.name.endsWith("-${baseline.contentHash}.json") }
        val baselineBytes = baselineDisk.readBytes()
        val rows = expected["items"].asJsonArray.map { it.asJsonObject }
        assertEquals(105, rows.size)
        val target = rows.first()["blockIds"].asJsonArray.first().asString
        val chapter = baseline.chapters.single { c -> c.blocks.any { it.id == target } }
        val store = BookReadingStore(preferences)
        val position = BookReadingPosition(baseline.key, baseline.contentHash, "canon12", target, chapter.id,
            fraction = .35f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        val own = BookTranslationIndex.fromFeoriaCatalog()
        assertEquals(126, own.size)
        var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Full own catalog is offline") }, translations = own)
        val candidate = requireNotNull(repository.enrichSaved(baseline, allowNetwork = false)).book
        val changed = baseline.blocks.zip(candidate.blocks).filter { it.first != it.second }
        val canonIds = rows.flatMap { row -> row["blockIds"].asJsonArray.map { it.asString } }.toSet()
        val commonRows = commonArchive["expected"].asJsonArray.map { it.asJsonObject }
        val commonIds = commonRows.map { it["blockId"].asString }.toSet()
        assertEquals(108, canonIds.size)
        assertEquals(3, commonRows.size); assertEquals(3, commonIds.size)
        assertTrue(canonIds.intersect(commonIds).isEmpty())
        assertEquals(108, changed.count { it.first.id in canonIds })
        assertEquals(3, changed.count { it.first.id in commonIds })
        assertEquals(111, changed.size)
        assertEquals(canonIds + commonIds, changed.map { it.first.id }.toSet())
        for (row in rows) for (blockId in row["blockIds"].asJsonArray) {
            val before = baseline.blocks.single { it.id == blockId.asString }
            val after = candidate.blocks.single { it.id == blockId.asString }
            assertNull(before.russian)
            assertEquals(row["originalSha256"].asString, BookText.sha256(after.original))
            assertEquals(row["russianSha256"].asString, BookText.sha256(requireNotNull(after.russian)))
            assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, after.translationSource)
            assertEquals(before.copy(russian = after.russian, translationSource = after.translationSource), after)
        }
        for (row in commonRows) {
            val before = baseline.blocks.single { it.id == row["blockId"].asString }
            val after = candidate.blocks.single { it.id == row["blockId"].asString }
            assertNull(before.russian)
            assertEquals(row["originalSha256"].asString, BookText.sha256(after.original))
            assertEquals(row["russianSha256"].asString, BookText.sha256(requireNotNull(after.russian)))
            assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, after.translationSource)
            assertEquals(before.copy(russian = after.russian, translationSource = after.translationSource), after)
        }
        assertEquals(baseline.blocks.map { it.id to it.original }, candidate.blocks.map { it.id to it.original })
        assertEquals(publishedBefore, candidate.blocks.filter { it.id in publishedBefore.map(BookBlock::id) })
        assertEquals(position, store.last("canon12"))
        assertEquals(baseline, cache.readVersion(baseline.key, position.contentHash))
        assertArrayEquals(baselineBytes, baselineDisk.readBytes())
        assertTrue(store.acceptVersion(baseline, candidate, "canon12", position))
        val accepted = position.copy(contentHash = candidate.contentHash, fraction = 0f)
        assertEquals(accepted, BookReadingStore(context.getSharedPreferences(name, Context.MODE_PRIVATE)).last("canon12"))
        val reopened = FileBookCache(directory)
        assertEquals(candidate, reopened.readVersion(candidate.key, accepted.contentHash))
        assertEquals(baseline, reopened.readVersion(baseline.key, baseline.contentHash))
        assertEquals(original, reopened.readVersion(original.key, original.contentHash))
        assertArrayEquals(originalBytes, originalDisk.readBytes())
        assertArrayEquals(baselineBytes, baselineDisk.readBytes())
        assertEquals(json, asset("quiet-matins/matins-accepted-unchanged.json"))
        assertEquals(0, requests)
        assertNull(repository.enrichSaved(candidate, allowNetwork = false))
    }
}
