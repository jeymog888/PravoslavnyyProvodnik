package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import kotlin.math.abs

/** Frames are controlled while playing, so a continuing frame loop cannot fake test idleness. */
@RunWith(AndroidJUnit4::class)
class BookReaderPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-playback-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val parishId = "playback-test-parish"
    private val book = fixtureBook()

    @After fun clean() {
        // A failed assertion must not leave autoplay running when automatic time resumes.
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun playMovesTheTextAndARealManualDragStopsAllFurtherAutomaticMotion() {
        launch()
        val before = paragraphTop()
        compose.mainClock.autoAdvance = false
        play()
        advance(3_000)
        assertTrue("Play must move actual text on screen", paragraphTop() < before - 4.dp)

        // A real pointer drag, cancelled after movement to avoid adding fling inertia.
        compose.onNodeWithTag("book_content").performTouchInput {
            down(Offset(centerX, height * .7f))
            moveTo(Offset(centerX, height * .4f), delayMillis = 100)
            cancel(delayMillis = 1)
        }
        advance(700)
        assertPaused()
        val stoppedTop = paragraphTop()
        val stopped = saved()
        assertEquals("long-prayer", stopped.blockId)
        advance(3_000)
        assertPaused()
        assertTrue("Autoplay must not resume after manual scrolling", abs((paragraphTop() - stoppedTop).value) <= 1f)
        assertEquals(stopped.blockId, saved().blockId)
        assertEquals(stopped.fraction, saved().fraction, .001f)
    }

    @Test fun leavingResumedStateSavesTheCurrentPlaceAndReturningDoesNotRestartPlay() {
        launch()
        compose.mainClock.autoAdvance = false
        play()
        advance(2_000)
        val beforePause = paragraphTop()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        val paused = saved() // The lifecycle observer must save immediately, without debounce.
        assertEquals("long-prayer", paused.blockId)
        assertTrue("The saved place must include progress made while playing", paused.fraction > .2f)
        // Do not ask the Compose harness to wait for an active UI while it is paused.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(paused, saved())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        advance(700)
        assertPaused()
        assertTrue("Returning to the app must keep the same visible line", abs((paragraphTop() - beforePause).value) <= 1f)
        val resumedTop = paragraphTop()
        advance(3_000)
        assertPaused()
        assertTrue("Time spent away must not cause delayed movement", abs((paragraphTop() - resumedTop).value) <= 1f)
        assertEquals(paused.blockId, saved().blockId)
        assertEquals(paused.fraction, saved().fraction, .001f)
    }

    @Test fun changingLanguageWhilePlayingKeepsTheFragmentAndLeavesItPaused() {
        launch()
        compose.mainClock.autoAdvance = false
        play()
        advance(2_000)
        selectMode(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)
        advance(700)
        assertPaused()
        val russian = saved()
        assertEquals(BookReadingMode.RUSSIAN, russian.mode)
        assertEquals("long-prayer", russian.blockId)
        assertTrue(russian.fraction > .2f)
        val russianTop = paragraphTop()
        advance(3_000)
        assertTrue("A language switch must not restart autoplay", abs((paragraphTop() - russianTop).value) <= 1f)
        assertEquals(russian.fraction, saved().fraction, .001f)

        selectMode(BookReadingMode.RUSSIAN, BookReadingMode.ORIGINAL)
        advance(700)
        assertPaused()
        val original = saved()
        assertEquals(BookReadingMode.ORIGINAL, original.mode)
        assertEquals(russian.blockId, original.blockId)
        assertEquals(russian.chapterId, original.chapterId)
        assertEquals(russian.fraction, original.fraction, .001f)
        val originalTop = paragraphTop()
        advance(3_000)
        assertTrue(abs((paragraphTop() - originalTop).value) <= 1f)
    }

    @Test fun meaningAtAnAltarOnlyChapterReturnsToTheNextVisibleChapterInsteadOfTheBeginning() {
        launch(mode = BookReadingMode.MEANING, chapterId = "opening", blockId = "opening-1", fraction = 0f)
        // Meaning contains one entry per chapter; choose the middle altar-only chapter.
        compose.onNodeWithTag("book_content").performScrollToIndex(1)
        settle()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Тихая молитва в алтаре")
        assertEquals("altar-only", saved().chapterId)
        assertEquals(BookAnchorKind.HEADER, saved().anchorKind)
        assertFalse(saved().showAltar)

        selectMode(BookReadingMode.MEANING, BookReadingMode.ORIGINAL)
        settle()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Общая молитва")
        compose.onNodeWithTag("book_block_long-prayer").assertIsDisplayed()
        val position = saved()
        assertEquals("reading", position.chapterId)
        assertEquals("long-prayer", position.blockId)
        assertEquals(BookAnchorKind.HEADER, position.anchorKind)
        assertEquals(0f, position.fraction, .001f)
        assertFalse(position.showAltar)
        assertNotEquals("opening", position.chapterId)
    }

    @Test fun endPageKeepsTheLastChapterInTheHeader() {
        launch()
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_end"))
        settle()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Завершение")
    }

    private fun launch(mode: BookReadingMode = BookReadingMode.ORIGINAL, chapterId: String = "reading",
        blockId: String = "long-prayer", fraction: Float = .2f) {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId, blockId, chapterId,
            fraction = fraction, mode = mode, wordsPerMinute = 180, showAltar = false)))
        compose.setContent {
            GuideTheme(false) {
                if (mounted.value) BookReaderScreen(book, parishId, store, 0, onBack = {}, onOpenUrl = {},
                    onChooseVersion = {}, onSettings = {})
            }
        }
        settle()
    }

    private fun play() {
        compose.onNodeWithTag("book_play").performClick()
        advance(64)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку")
    }

    private fun selectMode(from: BookReadingMode, to: BookReadingMode) {
        val tag = "book_mode_${to.name.lowercase()}"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(tag).performClick()
        else {
            // The accessible mode picker becomes a dropdown under large system fonts.
            compose.onNode(hasText(from.title) and hasClickAction()).performClick()
            advance(400)
            compose.onNodeWithText(to.title).performClick()
        }
    }

    private fun assertPaused() = compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    private fun paragraphTop() = compose.onNodeWithTag("book_block_long-prayer").assertIsDisplayed().getUnclippedBoundsInRoot().top
    private fun saved(): BookReadingPosition = requireNotNull(BookReadingStore(preferences).last(parishId))
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }
    private fun settle() { compose.waitForIdle(); advance(650) }

    private fun fixtureBook(): ServiceBook {
        fun prayer(id: String, original: String, russian: String = original, altar: Boolean = false) =
            BookBlock(id, BookBlockKind.PRAYER, null, original, russian, isAltarText = altar)
        val chapters = listOf(
            BookChapter("opening", "Начало", "Собираемся для молитвы", (1..7).map {
                prayer("opening-$it", "Господи, помоги нам внимательно слушать и понимать молитву. ".repeat(4)) }),
            BookChapter("altar-only", "Тихая молитва в алтаре", "Священник молится о собравшихся", listOf(
                prayer("altar-1", "Молитва в алтаре о мире и помощи всем людям. ".repeat(8), altar = true),
                prayer("altar-2", "Продолжение тихой молитвы священника. ".repeat(8), altar = true))),
            BookChapter("reading", "Общая молитва", "Вместе благодарим Бога и просим помощи", listOf(
                prayer("long-prayer", "Господи, помоги нам сохранить мир и любовь в наших сердцах. ".repeat(100),
                    "Господь, помоги нам жить в мире, беречь друг друга и совершать добрые дела. ".repeat(110)))),
            BookChapter("ending", "Завершение", "Продолжаем молитву делами", (1..6).map {
                prayer("ending-$it", "Благодарим Тебя за помощь и просим научить нас добру. ".repeat(4)) })
        )
        val key = ServiceBookKey("2026-11-14")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовое последование",
            fetchedAtMillis = 1_794_348_000_000, selectedEventTitle = null, chapters = chapters, contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
