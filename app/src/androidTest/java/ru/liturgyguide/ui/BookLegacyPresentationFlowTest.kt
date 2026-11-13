package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID

/** Opt-in genuine Android archives, never rehashed or silently rewritten for a test. */
@RunWith(AndroidJUnit4::class)
class BookLegacyPresentationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "legacy-reader-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(prefs)
    private val directory = File(context.cacheDir, name)
    private val mounted = mutableStateOf(true)
    private val pending = mutableStateOf<BookLocatedSearchHit?>(null)
    private val parish = "legacy-reader-test-parish"
    private var launched = false
    private val originalFile by lazy { File(context.cacheDir, "qa11-offline-matins.json") }
    private val originalBytes by lazy { originalFile.readBytes() }
    private val book by lazy { Gson().fromJson(originalBytes.toString(Charsets.UTF_8), ServiceBook::class.java) }
    private val target by lazy { book.chapters.flatMap { chapter -> chapter.blocks.map { chapter to it } }
        .first { (_, block) -> block.kind == BookBlockKind.RUBRIC && !block.isAltarText && block.original.startsWith(PREFIX) } }

    @After fun clean() {
        if (launched) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun aRealSavedExclamationMovesAsPrayerInsteadOfTheRubricPauseAtMaximumRate() {
        prepare()
        launch(BookReadingPosition(book.key, book.contentHash, parish, target.second.id, target.first.id,
            wordsPerMinute = BookAutoScroll.MAX_RATE))
        val before = text().getUnclippedBoundsInRoot().top
        assertEquals(16.sp, layout().layoutInput.style.fontSize)
        assertEquals(24.sp, layout().layoutInput.style.lineHeight)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performClick()
        advance(1_600)
        text().assertIsDisplayed()
        assertTrue("The actual prayer text must move", text().getUnclippedBoundsInRoot().top < before - 2.dp)
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        assertEquals("A 21-26 word prayer cannot finish in the old 0.8-second rubric pause", target.second.id, saved().blockId)
        assertTrue(saved().fraction > .1f)
        assertTrue(saved().fraction < .95f)
        compose.onNodeWithTag("book_play").performClick()
        advance(5_000)
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        assertNotEquals("The corrected prayer must eventually advance", target.second.id, saved().blockId)
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test fun anOldRussianSearchTargetKeepsItsFullTextOffsetsAndReopensTheNewLayoutExactlyOffline() {
        prepare()
        val query = target.second.original.substring(PREFIX.length).trim().split(' ').take(6).joinToString(" ")
        val original = BookSearch.find(book, query, BookReadingMode.ORIGINAL).single { it.blockId == target.second.id }
        val oldTarget = BookLocatedSearchHit(book.key, book.contentHash, original.copy(mode = BookReadingMode.RUSSIAN))
        assertEquals(oldTarget, oldTarget.validatedFor(book))
        pending.value = oldTarget
        launch(BookReadingPosition(book.key, book.contentHash, parish, target.second.id, target.first.id))
        compose.onNodeWithTag("book_missing_russian_${target.second.id}").assertIsDisplayed()
        text().assertTextEquals(target.second.original)
        val measured = layout()
        val line = measured.getLineForOffset(oldTarget.hit.matchStart)
        val top = text().getUnclippedBoundsInRoot().top
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val nativeDensity = context.resources.displayMetrics.density
        val lineTop = top.value + measured.getLineTop(line) / nativeDensity
        val lineBottom = top.value + measured.getLineBottom(line) / nativeDensity
        assertTrue("The requested original UTF-16 line must be visible below its fallback caption", lineTop >= viewport.top.value - 1f)
        assertTrue(lineBottom <= viewport.bottom.value + 1f)
        assertEquals(BookReadingMode.RUSSIAN, saved().mode)
        val before = saved()
        val cachedBefore = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("Network disabled") })
        val reopened = runBlocking { offline.load(book.key) }
        assertTrue(reopened is BookLoadResult.Ready)
        assertEquals(book, (reopened as BookLoadResult.Ready).book)
        compose.runOnIdle { mounted.value = true }
        settle()
        compose.onNodeWithTag("book_missing_russian_${target.second.id}").assertIsDisplayed()
        assertEquals(top.value, text().getUnclippedBoundsInRoot().top.value, 1f)
        assertEquals(before, saved())
        for ((filename, bytes) in cachedBefore) assertArrayEquals(filename, bytes, directory.resolve(filename).readBytes())
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    private fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("legacyArchives") == "true")
        assertTrue("The unchanged genuine archive must exist", originalFile.isFile)
        assertTrue("Production Android integrity must accept the archive", ServiceBookIntegrity.valid(book))
        assertTrue(FileBookCache(directory).write(book))
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
    }
    private fun launch(position: BookReadingPosition) {
        assertTrue(store.save(position))
        launched = true
        compose.setContent {
            GuideTheme(false) {
                if (mounted.value) BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {},
                    onChooseVersion = {}, onSettings = {}, pendingSearchTarget = pending.value,
                    onPendingSearchHandled = { pending.value = null; true })
            }
        }
        settle()
    }
    private fun text() = compose.onNodeWithTag("book_text_${target.second.id}")
    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        text().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }
    private fun saved() = requireNotNull(store.positionFor(book.key, parish))
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }
    private fun settle() { compose.waitForIdle(); advance(750) }
    private companion object { const val PREFIX = "И священник возглашает:" }
}
