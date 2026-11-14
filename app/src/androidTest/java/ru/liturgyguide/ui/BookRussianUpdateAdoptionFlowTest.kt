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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/** Real historical bytes, production local enrichment, exact file cache and explicit acceptance. */
@RunWith(Parameterized::class)
class BookRussianUpdateAdoptionFlowTest(private val dark: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val owner = "russian-adoption-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(owner, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, owner)
    private val cache = FileBookCache(directory)
    private val mounted = mutableStateOf(true)
    private val calls = CopyOnWriteArrayList<String>()
    private val parish = "russian-adoption-fixture"
    private val original = "Прииди́те, поклони́мся и припаде́м Самому́ Христу́, Царе́ви и Бо́гу на́шему."
    private var contentSet = false

    @After fun clean() {
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        context.deleteSharedPreferences(owner); directory.deleteRecursively()
    }

    @Test fun bundledEnrichmentExplainsTheCurrentMissingRussianBeforeExplicitAcceptance() = exercise("enrich")
    @Test fun alreadyCachedCandidateExplainsTheCurrentMissingRussianAfterReopening() = exercise("cached")
    @Test fun explicitRefreshPublishesOnlyAVerifiedFixtureCandidateAndUsesTheSameAcceptance() = exercise("manual")

    private fun repository() = ServiceBookRepository(cache, ScheduleTransport { url ->
        calls += url; error("This saved local translation must work without network")
    }, translations = BookTranslationIndex.fromFeoriaCatalog(), downloadRussian = false)

    private fun exercise(producer: String) {
        val old = instrumentation.context.assets.open("polyeleos-journey/ranked-matins-2026-11-13-church34.json")
            .bufferedReader().use { Gson().fromJson(it, ServiceBook::class.java) }
        assertTrue(ServiceBookIntegrity.valid(old))
        val target = old.blocks.single { it.original == original }
        assertNull(BookBlockPresentation.effectiveRussian(old.key, target))
        val chapter = old.chapters.single { target in it.blocks }
        val expected = requireNotNull(BookTranslationIndex.fromFeoriaCatalog().find(original)).russian
        assertTrue(cache.write(old))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }
        val bytes = oldFile.readBytes()
        val initial = BookReadingPosition(old.key, old.contentHash, parish, target.id, chapter.id,
            fraction = .24f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 113, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(initial))
        val prepared = if (producer == "enrich") null else runBlocking {
            requireNotNull(repository().enrichSaved(old, allowNetwork = false))
        }
        // Manual test delivers real production-enriched bytes through the existing load result.
        // It is a controlled provider result, not a claim that live HTTP refresh was tested.
        if (producer == "manual") assertTrue(cache.write(old))
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val base = repository()
        val delivery = object : ServiceBooks by base {
            override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
                if (producer != "enrich" || accepted.contentHash != old.contentHash) return null
                release.await()
                return base.enrichSaved(accepted, allowNetwork = false)
            }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                loads.incrementAndGet()
                check(producer == "manual" && force && key == old.key)
                val ready = requireNotNull(prepared)
                check(cache.write(ready.book))
                return ready
            }
        }
        val books = mutableStateOf<ServiceBooks>(delivery)
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, fontScale)) {
                GuideTheme(dark) { if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                    ServiceBookScreen(old.key, parish, books.value, store, 0,
                        onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
                } }
            }
        }
        contentSet = true
        waitFor("book_content"); settle()
        val before = saved()
        assertEquals(old.contentHash, before.contentHash)
        assertEquals(target.id, before.blockId); assertEquals(BookReadingMode.RUSSIAN, before.mode)
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(original)
        val prefsBefore = preferences.all.toMap()
        val controls = controlBounds()
        if (fontScale == 1f) assertWholeFooterLabels()
        if (producer == "manual") {
            compose.onNodeWithTag("book_reader_settings").performClick()
            compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
            waitFor("book_accept_update")
            compose.dismissBookUpdateOptions()
        } else if (producer == "enrich") compose.runOnIdle { release.complete(Unit) }
        waitFor("book_russian_update_notice", 30_000)
        compose.onNodeWithText("Есть русский перевод").assertIsDisplayed()
        compose.onNodeWithTag("book_russian_notice_action").assertHeightIsAtLeast(48.dp).assertTextContains("Посмотреть")
        compose.onNodeWithTag("book_russian_notice_dismiss").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertEquals(before, saved()); assertEquals(prefsBefore, preferences.all)
        assertArrayEquals(bytes, oldFile.readBytes())
        assertSameBounds(controls, controlBounds())
        assertOverlayGeometry(target.id)
        capture(producer + "-offer")
        compose.onNodeWithTag("book_russian_notice_action").performTouchInput { click(center) }
        compose.onNodeWithTag("book_options").assertIsDisplayed()
        compose.onNodeWithTag("book_update_russian_context").assertIsDisplayed()
            .assertTextEquals("В обновленной редакции есть перевод выбранного фрагмента")
        assertEquals(before, saved())
        compose.onNodeWithTag("book_accept_update").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        settle()
        val accepted = saved()
        assertNotEquals(old.contentHash, accepted.contentHash)
        assertEquals(target.id, accepted.blockId); assertEquals(chapter.id, accepted.chapterId)
        assertEquals(BookReadingMode.RUSSIAN, accepted.mode); assertEquals(0f, accepted.fraction, .003f)
        assertEquals(before.wordsPerMinute, accepted.wordsPerMinute); assertEquals(before.showAltar, accepted.showAltar)
        compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed().assertTextEquals(expected)
        compose.onNodeWithTag("book_feoria_translation_${target.id}").assertIsDisplayed()
        assertEquals(cache.read(old.key)?.contentHash, accepted.contentHash)
        assertArrayEquals(bytes, oldFile.readBytes())
        capture(producer + "-accepted")
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        compose.runOnIdle { books.value = repository(); mounted.value = true }
        waitFor("book_content"); settle()
        assertEquals(accepted, saved())
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(expected)
        compose.onNodeWithTag("book_russian_update_notice").assertDoesNotExist()
        assertTrue(calls.isEmpty())
        assertEquals(if (producer == "manual") 1 else 0, loads.get())
    }

    private fun assertOverlayGeometry(blockId: String) {
        val notice = compose.onNodeWithTag("book_russian_update_notice").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val text = compose.onNodeWithTag("book_text_${blockId}").getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("book_text_${blockId}").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val density = compose.activity.resources.displayMetrics.density
        val line = (0 until layout.lineCount).first { index ->
            text.top.value + layout.getLineTop(index) / density >= viewport.top.value &&
                text.top.value + layout.getLineBottom(index) / density <= viewport.bottom.value
        }
        val lineBottom = text.top.value + layout.getLineBottom(line) / density
        assertTrue("The readable real line stays above the overlay", notice.top.value - lineBottom >= 12f - 1f / density)
        assertTrue(notice.top >= viewport.top && notice.bottom <= viewport.bottom)
        controlBounds().forEach { (name, bounds) ->
            assertTrue("$name must not be covered", notice.bottom <= bounds.top || notice.top >= bounds.bottom ||
                notice.right <= bounds.left || notice.left >= bounds.right)
        }
        val action = compose.onNodeWithTag("book_russian_notice_action").getUnclippedBoundsInRoot()
        val dismiss = compose.onNodeWithTag("book_russian_notice_dismiss").getUnclippedBoundsInRoot()
        assertTrue(dismiss.left - action.right >= 12.dp)
    }

    private fun assertWholeFooterLabels() {
        listOf("book_find", "book_play", "book_microphone").forEach { tag ->
            val text = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            text.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$tag keeps its whole label on one line", 1, layout.lineCount)
            assertFalse("$tag label must fit without clipping", layout.hasVisualOverflow)
        }
    }

    private fun controlBounds(): Map<String, DpRect> = listOf("book_reader_settings", "book_find", "book_play", "book_microphone",
        if (fontScale > 1.5f) "book_mode_menu" else "book_mode_russian").associateWith {
        compose.onNodeWithTag(it).getUnclippedBoundsInRoot()
    }
    private fun assertSameBounds(before: Map<String, DpRect>, after: Map<String, DpRect>) {
        before.forEach { (tag, bounds) ->
            val next = after.getValue(tag)
            assertEquals(tag, bounds.left.value, next.left.value, .5f); assertEquals(tag, bounds.top.value, next.top.value, .5f)
            assertEquals(tag, bounds.right.value, next.right.value, .5f); assertEquals(tag, bounds.bottom.value, next.bottom.value, .5f)
        }
    }
    private fun saved() = requireNotNull(store.last(parish))
    private fun waitFor(tag: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun capture(name: String) {
        val output = File(context.getExternalFilesDir("russian-adoption-qa"), owner).apply { mkdirs() }
        File(output, name + "-dark${dark}-font${fontScale}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},font={1}")
        fun configurations() = listOf(false, true).flatMap { dark -> listOf(1f, 2f).map { arrayOf<Any>(dark, it) } }
    }
}
