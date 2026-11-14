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
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual accepted MATINS and phone v12 corpus, real explicit update, then a new offline repository. */
class BookPublishedPsalm19FlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "published-psalm19-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val mounted = mutableStateOf(true)
    private val dark = mutableStateOf(false)
    private val calls = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val parish = "published-psalm19-fixture"
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
        calls += url; error("Published Psalm19 must stay offline")
    }, translations = BookTranslationIndex.fromBundledRussian(), downloadRussian = false)

    @Test fun acceptedMatinsReceivesTheFullPublishedPsalmOnlyAfterExplicitAcceptAndReopensWithItsSourceOffline() {
        val evidenceText = instrumentation.context.assets.open("published-psalm19/evidence.json").bufferedReader().use { it.readText() }
        assertEquals("d6e82505b6b152e936f222040b74bb5ded329b76f1f4d646cd2eda194160441d", BookText.sha256(evidenceText))
        val evidence = JsonParser.parseString(evidenceText).asJsonObject
        val original = evidence["book"].asJsonObject["original"].asString
        val targetId = evidence["book"].asJsonObject["blockId"].asString
        val russian = evidence["published"].asJsonObject["russian"].asString
        val bookText = instrumentation.context.assets.open("polyeleos-journey/ranked-matins-2026-11-13-church34.json")
            .bufferedReader().use { it.readText() }
        assertEquals("440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", BookText.sha256(bookText))
        val old = Gson().fromJson(bookText, ServiceBook::class.java)
        assertTrue(ServiceBookIntegrity.valid(old))
        val target = old.blocks.single { it.id == targetId }
        val chapter = old.chapters.single { target in it.blocks }
        assertEquals(original, target.original); assertNull(target.russian)
        assertEquals(BookBlockKind.PRAYER, target.kind); assertTrue(target.scriptureReferences.isEmpty())
        // AAPT expands .gz asset inputs and removes their suffix in the APK.
        // The packaged raw corpus is still checked against its original SHA below.
        val corpusText = instrumentation.context.assets.open("published-psalm19/phone-corpus-v12.json")
            .bufferedReader().use { it.readText() }
        assertEquals("c04424b8ee039c560968680e42e7b87ecd829f46b843ab7098aebe21b1a927f1", BookText.sha256(corpusText))
        assertEquals(12, JsonParser.parseString(corpusText).asJsonObject["version"].asInt)
        val corpus = BookTranslationCorpus.decode(corpusText)
        assertNull(corpus.index.find(original))
        val baseline = SavedBookEnrichment.apply(old, corpus.index.plus(BookTranslationIndex.fromFeoriaCatalog())) ?: old
        val expected = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(BookTranslationIndex.fromBundledRussian())))
        val gained = baseline.blocks.zip(expected.blocks).filter { (a, b) -> a != b }
        assertEquals(listOf(targetId), gained.map { it.first.id })
        assertEquals(gained.single().first.copy(russian = russian, translationSource = BookTranslationIndex.PSALTER_SOURCE), gained.single().second)
        assertEquals(old.blocks.map { it.id to it.original }, expected.blocks.map { it.id to it.original })
        assertEquals(old.chapters.map { it.id to it.meaning }, expected.chapters.map { it.id to it.meaning })
        assertEquals(baseline, expected.copy(chapters = baseline.chapters, sources = baseline.sources, contentHash = baseline.contentHash))
        assertTrue(cache.write(old)); assertTrue(cache.writeTranslationCorpus(corpusText))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }
        val oldBytes = oldFile.readBytes()
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, parish, targetId, chapter.id,
            fraction = .24f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)))
        val release = CompletableDeferred<Unit>()
        val base = repository()
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
                GuideTheme(dark.value) { if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                    ServiceBookScreen(old.key, parish, books.value, store, 0,
                        onBack = {}, onSettings = {}, onOpenUrl = { opened += it }, onOpenCommonLiturgy = {})
                } }
            }
        }
        contentSet = true
        waitFor("book_content"); settle()
        val before = saved(); val preferencesBefore = preferences.all.toMap()
        assertEquals(old.contentHash, before.contentHash); assertEquals(targetId, before.blockId)
        compose.onNodeWithTag("book_text_$targetId").assertTextEquals(original)
        capture("before")
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(30_000) { cache.read(old.key)?.contentHash == expected.contentHash }
        compose.onNodeWithTag("book_reader_settings").performTouchInput { click(center) }
        waitFor("book_accept_update")
        assertEquals(before, saved()); assertEquals(preferencesBefore, preferences.all)
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpusText, cache.readTranslationCorpus())
        assertEquals(expected, cache.read(old.key))
        assertEquals(old, cache.readVersion(old.key, old.contentHash))
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("book_accept_update").performScrollTo().performTouchInput { click(center) }
        settle()
        val accepted = saved()
        assertEquals(expected.contentHash, accepted.contentHash)
        assertEquals(targetId, accepted.blockId); assertEquals(chapter.id, accepted.chapterId)
        assertEquals(BookReadingMode.RUSSIAN, accepted.mode); assertEquals(0f, accepted.fraction, .003f)
        compose.onNodeWithTag("book_text_$targetId").assertIsDisplayed().assertTextEquals(russian)
        compose.onNodeWithTag("book_feoria_translation_$targetId").assertDoesNotExist()
        assertEquals(BookTranslationIndex.PSALTER_SOURCE, cache.read(old.key)?.blocks?.single { it.id == targetId }?.translationSource)
        assertArrayEquals(oldBytes, oldFile.readBytes())
        capture("accepted-light")
        compose.runOnIdle { dark.value = true }; settle()
        compose.onNodeWithTag("book_text_$targetId").assertTextEquals(russian)
        capture("accepted-dark")
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        compose.runOnIdle { books.value = repository(); mounted.value = true }
        waitFor("book_content"); settle()
        assertEquals(accepted, saved())
        compose.onNodeWithTag("book_text_$targetId").assertTextEquals(russian)
        compose.onNodeWithTag("book_reader_settings").performTouchInput { click(center) }
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performTouchInput { click(center) }
        settle()
        compose.onNodeWithText("Состав книги").assertIsDisplayed()
        // A LazyColumn does not compose source links below the viewport.
        // Scroll its actual container to the source before querying that node.
        val sourceListId = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Состав книги")))
            .fetchSemanticsNode().id
        // The heading is virtualized away while scrolling; the container id stays stable.
        compose.onNode(hasScrollAction() and SemanticsMatcher("Opened source list") { it.id == sourceListId })
            .performScrollToNode(hasText(BookTranslationIndex.PSALTER_SOURCE.title))
        compose.onNodeWithText(BookTranslationIndex.PSALTER_SOURCE.title).performScrollTo().assertIsDisplayed()
            .performTouchInput { click(center) }
        assertEquals(listOf(BookTranslationIndex.PSALTER_SOURCE.url), opened.toList())
        capture("source-dark", sourceDialog = true)
        pressBack(); settle()
        assertEquals(accepted, saved()); assertTrue(calls.isEmpty())
        assertEquals(corpusText, cache.readTranslationCorpus()); assertArrayEquals(oldBytes, oldFile.readBytes())
        assertEquals(old, FileBookCache(directory).readVersion(old.key, old.contentHash))
        // This is a fresh repository/remount. It does not claim an Android process restart.
    }

    private fun saved() = requireNotNull(store.last(parish))
    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun capture(label: String, sourceDialog: Boolean = false) {
        val out = File(context.getExternalFilesDir("published-psalm19-qa"), name).apply { mkdirs() }
        File(out, "$label.png").outputStream().use {
            val root = if (sourceDialog) compose.onNode(isRoot() and
                hasAnyDescendant(hasText(BookTranslationIndex.PSALTER_SOURCE.title))) else compose.onRoot()
            root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
