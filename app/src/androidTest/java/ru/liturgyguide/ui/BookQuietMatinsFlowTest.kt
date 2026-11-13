package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID

/** A real accepted archive and real reader. No claim about a parish's performed order. */
@RunWith(AndroidJUnit4::class)
class BookQuietMatinsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "quiet-matins-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(prefs)
    private val mounted = mutableStateOf(true)
    private val parish = "quiet-matins-reader"
    private val bytes = instrumentation.context.assets.open("quiet-matins/matins-accepted-unchanged.json").use { it.readBytes() }
    private val book = Gson().fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java)
    private val scope = BookVisibilityScope.of(book)
    private val group = requireNotNull(scope.quietGroup)
    private val chapter = book.chapters.single { it.id == group.chapterId }
    private val privatePrayer = chapter.blocks.first { it.id in group.blockIds && it.kind == BookBlockKind.PRAYER }

    @After fun clean() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun publicPlaybackContinuesAfterPsalm62IntoPsalm87WithoutTheParallelPrayers() {
        val introduction = chapter.blocks.indexOfFirst { it.id == group.introductionId }
        val before = chapter.blocks.take(introduction).last { BookBlockPresentation.isSpoken(book.key, it) }
        val psalm87 = chapter.blocks.indexOfFirst { it.original == "Псалом 87" }
        val psalm102 = chapter.blocks.indexOfFirst { it.original == "Псалом 102" }
        val expected = chapter.blocks.subList(psalm87, psalm102).map { it.id }.toSet()
        launch(BookReadingPosition(book.key, book.contentHash, parish, before.id, chapter.id,
            fraction = .93f, wordsPerMinute = BookAutoScroll.MAX_RATE))
        assertFalse(saved().showAltar)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performClick()
        // Reading positions are deliberately debounced until the viewport settles. Observe
        // the committed cursor after pausing, rather than polling a stale cursor mid-playback.
        // Eight seconds covers the remaining doxology and the short unspoken instructions;
        // it cannot cover the 1,278 words of parallel priestly prayers in the old public flow.
        advance(8_000)
        compose.onNodeWithTag("book_play").performClick()
        advance(650)
        assertTrue("Public reading must continue into the source's Psalm 87; actual=${saved().blockId}",
            saved().blockId in expected)
        assertFalse("A quiet prayer must never become the accepted public playback cursor", saved().blockId in group.blockIds)
        assertFalse(saved().showAltar)
        group.blockIds.forEach { compose.onNodeWithTag("book_block_$it").assertDoesNotExist() }
        unchanged()
    }

    @Test fun anOldPrivateCursorReopensExactlyPausedAndSurvivesAllThreeModesAndRestart() {
        val initial = BookReadingPosition(book.key, book.contentHash, parish, privatePrayer.id, chapter.id,
            fraction = .34f, showAltar = false)
        launch(initial)
        assertTrue("A saved private cursor is an explicit reason to reveal its group", saved().showAltar)
        compose.onNodeWithTag("book_text_${privatePrayer.id}").assertIsDisplayed()
        assertEquals(privatePrayer.id, saved().blockId)
        assertEquals(initial.fraction, saved().fraction, .015f)
        paused()
        for (mode in listOf(BookReadingMode.RUSSIAN, BookReadingMode.MEANING, BookReadingMode.ORIGINAL)) {
            compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").performClick()
            settle()
            assertEquals(mode, saved().mode)
            assertEquals(privatePrayer.id, saved().blockId)
            assertEquals(initial.fraction, saved().fraction, .015f)
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            settle()
            assertEquals(mode, saved().mode)
            assertEquals(privatePrayer.id, saved().blockId)
            assertEquals(initial.fraction, saved().fraction, .015f)
            if (mode != BookReadingMode.MEANING) {
                compose.onNodeWithTag("book_text_${privatePrayer.id}").assertIsDisplayed()
                paused()
            }
        }
        unchanged()
    }

    @Test fun manualSearchMarksAndRevealsTheQuietPrayerAndCollapseReturnsToItsPublicExplanation() {
        val public = chapter.blocks.first { scope.isVisible(it) && it.kind == BookBlockKind.PRAYER }
        launch(BookReadingPosition(book.key, book.contentHash, parish, public.id, chapter.id, mode = BookReadingMode.MEANING))
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput(privatePrayer.original.split(' ').take(7).joinToString(" "))
        closeSoftKeyboard()
        val hit = compose.scrollToBookHit(privatePrayer.id)
        compose.onNodeWithTag("book_hit_private_${privatePrayer.id}", useUnmergedTree = true).assertIsDisplayed()
        hit.performClick()
        settle()
        assertEquals(privatePrayer.id, saved().blockId)
        assertTrue(saved().showAltar)
        compose.onNodeWithTag("book_text_${privatePrayer.id}").assertIsDisplayed()
        paused()
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Тихие молитвы и алтарные тексты").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        settle()
        assertEquals(group.introductionId, saved().blockId)
        assertEquals(0f, saved().fraction, .001f)
        assertFalse(saved().showAltar)
        androidx.test.espresso.Espresso.pressBack()
        settle()
        compose.onNodeWithTag("book_quiet_toggle").assertIsDisplayed()
        compose.onNodeWithText("Показать тихие и алтарные тексты").assertIsDisplayed()
        compose.onNodeWithTag("book_block_${privatePrayer.id}").assertDoesNotExist()
        paused()
        unchanged()
    }

    private fun launch(position: BookReadingPosition) {
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", book.contentHash)
        assertTrue(ServiceBookIntegrity.valid(book))
        assertTrue(store.save(position))
        compose.setContent { GuideTheme(false) {
            if (mounted.value) BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {},
                onChooseVersion = {}, onSettings = {})
        } }
        settle()
    }
    private fun saved() = requireNotNull(store.positionFor(book.key, parish))
    private fun paused() = compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }
    private fun settle() { compose.waitForIdle(); advance(750) }
    private fun unchanged() {
        assertArrayEquals(bytes, instrumentation.context.assets.open("quiet-matins/matins-accepted-unchanged.json").use { it.readBytes() })
        assertEquals(book.contentHash, Gson().fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java).contentHash)
    }
}
