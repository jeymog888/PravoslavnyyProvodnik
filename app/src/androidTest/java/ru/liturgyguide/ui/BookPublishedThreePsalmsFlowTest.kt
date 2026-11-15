package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Current production bundles own195 and four published bodies; archived inputs stay pinned. */
@RunWith(Parameterized::class)
class BookPublishedThreePsalmsFlowTest(private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "published-three-psalms-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val mounted = mutableStateOf(true)
    private val calls = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val parish = "published-three-psalms-fixture"
    private var contentSet = false

    @After fun clean() {
        if (contentSet) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name); directory.deleteRecursively()
    }

    private fun repository() = ServiceBookRepository(cache, ScheduleTransport { url ->
        calls += url; error("The reviewed whole pairs must remain offline")
    }, translations = BookTranslationIndex.fromBundledRussian(), downloadRussian = false)

    @Test fun currentWholePairsRequireExplicitAcceptanceRenderEachPublishedBodyAndReopenWithAttributionOffline() {
        val evidenceText = instrumentation.context.assets.open("published-three-psalms/evidence.json")
            .bufferedReader().use { it.readText() }
        assertEquals("f366b671122954fe4db8ac7d39bad7413461e9e148dc18628d7356b21683df3b", BookText.sha256(evidenceText))
        val evidence = JsonParser.parseString(evidenceText).asJsonObject
        val rows = evidence["items"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(20, 134, 135), rows.map { it["psalm"].asInt })
        val bookText = instrumentation.context.assets.open(evidence["book"].asJsonObject["asset"].asString)
            .bufferedReader().use { it.readText() }
        assertEquals(evidence["book"].asJsonObject["rawSha256"].asString, BookText.sha256(bookText))
        val old = Gson().fromJson(bookText, ServiceBook::class.java)
        assertEquals(evidence["book"].asJsonObject["storedContentHash"].asString, old.contentHash)
        assertTrue(ServiceBookIntegrity.valid(old))
        val corpusText = instrumentation.context.assets.open(evidence["corpus"].asJsonObject["asset"].asString)
            .bufferedReader().use { it.readText() }
        assertEquals(evidence["corpus"].asJsonObject["rawSha256"].asString, BookText.sha256(corpusText))
        assertEquals(12, JsonParser.parseString(corpusText).asJsonObject["version"].asInt)
        val corpus = BookTranslationCorpus.decode(corpusText)
        val own = BookTranslationIndex.fromFeoriaCatalog()
        val previous = own.fillingMissing(BookTranslationIndex.fromBundledPsalm19())
        val current = BookTranslationIndex.fromBundledRussian()
        assertEquals(195, own.size); assertEquals(196, previous.size); assertEquals(199, current.size)
        val baseline193 = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(own)))
        val baseline194 = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(previous)))
        val expected = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(current)))
        val delta = baseline194.blocks.zip(expected.blocks).filter { (before, after) -> before != after }
        assertEquals(rows.map { it["blockId"].asString }, delta.map { it.first.id })
        assertEquals(623, delta.sumOf { BookTranslationIndex.normal(it.first.original).split(' ').size })
        for ((before, after) in delta) {
            val row = rows.single { it["blockId"].asString == before.id }
            assertEquals(row["original"].asString, before.original)
            assertEquals(before.copy(russian = row["russian"].asString, translationSource = BookTranslationIndex.PSALTER_SOURCE), after)
            assertEquals(BookBlockKind.PRAYER, before.kind); assertTrue(before.scriptureReferences.isEmpty())
        }
        val old19EvidenceText = instrumentation.context.assets.open("published-psalm19/evidence.json").bufferedReader().use { it.readText() }
        assertEquals(evidence["old19EvidenceSha256"].asString, BookText.sha256(old19EvidenceText))
        val old19 = JsonParser.parseString(old19EvidenceText).asJsonObject["book"].asJsonObject["blockId"].asString
        assertEquals((rows.map { it["blockId"].asString } + old19).toSet(), baseline193.blocks.zip(expected.blocks)
            .filter { (before, after) -> before != after }.map { it.first.id }.toSet())
        assertEquals(baseline194, expected.copy(chapters = baseline194.chapters, sources = baseline194.sources, contentHash = baseline194.contentHash))
        assertEquals(old.blocks.map { it.id to it.original }, expected.blocks.map { it.id to it.original })
        assertEquals(old.chapters.map { it.id to it.meaning }, expected.chapters.map { it.id to it.meaning })
        assertTrue(cache.write(old)); assertTrue(cache.writeTranslationCorpus(corpusText))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }; val oldBytes = oldFile.readBytes()
        val first = rows.first(); val targetId = first["blockId"].asString
        val target = old.blocks.single { it.id == targetId }; val chapter = old.chapters.single { target in it.blocks }
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, parish, targetId, chapter.id,
            fraction = .24f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)))
        val release = CompletableDeferred<Unit>(); val base = repository()
        val delivery = object : ServiceBooks by base {
            override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
                if (accepted.contentHash != old.contentHash) return null
                release.await()
                return base.enrichSaved(accepted, allowNetwork = false)
            }
        }
        val books = mutableStateOf<ServiceBooks>(delivery)
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(dark) { if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                    ServiceBookScreen(old.key, parish, books.value, store, 0, onBack = {}, onSettings = {},
                        onOpenUrl = { opened += it }, onOpenCommonLiturgy = {})
                } }
            }
        }
        contentSet = true; waitFor("book_content"); settle()
        val before = saved(); val preferencesBefore = preferences.all.toMap()
        assertEquals(old.contentHash, before.contentHash); assertEquals(targetId, before.blockId)
        compose.onNodeWithTag("book_text_$targetId").assertTextEquals(first["original"].asString)
        capture("before")
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(30_000) { cache.read(old.key)?.contentHash == expected.contentHash }
        compose.onNodeWithTag("book_reader_settings").performTouchInput { click(center) }
        waitFor("book_accept_update")
        assertEquals(before, saved()); assertEquals(preferencesBefore, preferences.all)
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpusText, cache.readTranslationCorpus())
        assertEquals(old, cache.readVersion(old.key, old.contentHash)); assertTrue(calls.isEmpty())
        compose.onNodeWithTag("book_accept_update").performScrollTo().performTouchInput { click(center) }; settle()
        assertEquals(expected.contentHash, saved().contentHash); assertEquals(targetId, saved().blockId)
        assertEquals(BookReadingMode.RUSSIAN, saved().mode); assertEquals(0f, saved().fraction, .003f)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        for (row in rows) {
            val id = row["blockId"].asString
            compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_text_$id"))
            compose.onNodeWithTag("book_text_$id").performScrollTo().assertIsDisplayed().assertTextEquals(row["russian"].asString)
            compose.onNodeWithTag("book_feoria_translation_$id").assertDoesNotExist()
            assertEquals(BookTranslationIndex.PSALTER_SOURCE, cache.read(old.key)?.blocks?.single { it.id == id }?.translationSource)
            settle(); capture("psalm-${row["psalm"].asInt}")
        }
        val readingBeforeSource = saved()
        compose.onNodeWithTag("book_reader_settings").performTouchInput { click(center) }
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performTouchInput { click(center) }; settle()
        compose.onNodeWithText("Состав книги").assertIsDisplayed()
        val sourceListId = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Состав книги"))).fetchSemanticsNode().id
        compose.onNode(hasScrollAction() and SemanticsMatcher("Opened source list") { it.id == sourceListId })
            .performScrollToNode(hasText(BookTranslationIndex.PSALTER_SOURCE.title))
        compose.onNodeWithText(BookTranslationIndex.PSALTER_SOURCE.title).performScrollTo().assertIsDisplayed()
            .performTouchInput { click(center) }
        assertEquals(listOf(BookTranslationIndex.PSALTER_SOURCE.url), opened.toList()); settle()
        capture("source", sourceDialog = true)
        pressBack(); settle(); assertEquals(readingBeforeSource, saved())
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        compose.runOnIdle { books.value = repository(); mounted.value = true }
        waitFor("book_content"); settle()
        assertEquals(readingBeforeSource, saved())
        assertEquals(expected, cache.read(old.key)); assertTrue(calls.isEmpty())
        for (row in rows) assertEquals(row["russian"].asString, cache.read(old.key)?.blocks?.single { it.id == row["blockId"].asString }?.russian)
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpusText, cache.readTranslationCorpus())
        assertEquals(old, FileBookCache(directory).readVersion(old.key, old.contentHash))
        // New repository/cache/composition in the same Android process, not a physical restart.
    }

    private fun saved() = requireNotNull(store.last(parish))
    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun capture(label: String, sourceDialog: Boolean = false) {
        val out = File(context.getExternalFilesDir("published-three-psalms-qa"), name).apply { mkdirs() }
        compose.waitForIdle()
        if (sourceDialog) {
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(out, "$label.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { screenshot.recycle() }
        } else File(out, "$label.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun configurations() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
