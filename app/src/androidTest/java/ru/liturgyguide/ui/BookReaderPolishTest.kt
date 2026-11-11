package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
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

@RunWith(AndroidJUnit4::class)
class BookReaderPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-reader-polish-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val parish = "reader-polish-parish"
    private val phrase = "И́же Херуви́мы та́йно образу́юще"
    private val book = fixture()

    @After fun clean() {
        // Failed autoplay assertions must still unmount without advancing an endless reader.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun findingWordsPastSeventyPercentOfALongPrayerDisplaysTheirActualLine() {
        launch()
        val source = book.blocks.first { it.id == "long" }.original
        val offset = source.indexOf(phrase)
        assertTrue("A shallow hit would not exercise the regression", offset.toFloat() / source.length > .7f)
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput("Иже Херувимы")
        closeSoftKeyboard()
        compose.onNodeWithTag("book_hit_long").performScrollTo().performClick()
        settle()
        val text = compose.onNodeWithTag("book_text_long", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(source, layout.layoutInput.text.text)
        val line = layout.getLineForOffset(offset)
        val textTop = text.getUnclippedBoundsInRoot().top
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val lineTop = textTop + (layout.getLineTop(line) / density).dp
        val lineBottom = textTop + (layout.getLineBottom(line) / density).dp
        assertTrue("The found words themselves must be visible, not merely their huge parent block", lineTop >= viewport.top)
        assertTrue("The found line must fit above the footer", lineBottom <= viewport.bottom)
        assertEquals("long", saved().blockId)
        assertTrue(saved().fraction > .6f)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    @Test fun meaningReturnsToRussianAtTheSameFragmentEvenAfterReaderRecreation() {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parish, "long", "chapter-12",
            fraction = .42f, mode = BookReadingMode.RUSSIAN)))
        launch()
        val before = saved()
        compose.onNodeWithTag("book_mode_meaning").performClick()
        settle()
        assertEquals(BookReadingMode.RUSSIAN, saved().lastTextMode)
        compose.onNodeWithText("К тексту").assertIsDisplayed()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        settle()
        compose.onNodeWithTag("book_mode_meaning").assertIsSelected()
        compose.onNodeWithTag("book_play").performClick()
        settle()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        val after = saved()
        assertEquals(BookReadingMode.RUSSIAN, after.mode)
        assertEquals(before.blockId, after.blockId)
        assertEquals(before.chapterId, after.chapterId)
        assertEquals(before.fraction, after.fraction, .001f)
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals(after.fraction, saved().fraction, .001f)
    }

    @Test fun continuousScrollUpdatesHeadingAndOrderOpensAtTheCorrectRepeatedSection() {
        launch()
        // Each chapter has one heading and one block. Several have the same human title.
        compose.onNodeWithTag("book_content").performScrollToIndex(24 * 2)
        settle()
        assertEquals("chapter-24", saved().chapterId)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Малая ектения")
        compose.onNodeWithTag("book_order").performClick()
        compose.onNodeWithTag("book_order_item_chapter-24").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("25 · Вы здесь").assertIsDisplayed()
        compose.onNodeWithTag("book_order_item_chapter-0").assertDoesNotExist()
        compose.onNodeWithTag("book_order_item_chapter-25").performScrollTo().performClick()
        settle()
        assertEquals("chapter-25", saved().chapterId)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Раздел 26")
        compose.onNodeWithTag("book_block_block-24").assertIsNotDisplayed()
        assertEquals(BookAnchorKind.HEADER, saved().anchorKind)
        assertEquals(0f, saved().fraction, .001f)
        compose.onNodeWithTag("book_mode_russian").performClick()
        settle()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Раздел 26")
        compose.onNodeWithTag("book_block_block-24").assertIsNotDisplayed()
        assertEquals(BookAnchorKind.HEADER, saved().anchorKind)
        assertEquals(0f, saved().fraction, .001f)
    }

    @Test fun compactTwoHundredPercentFooterKeepsNamedTargetsAndUsefulReadingSpace() {
        launch(fontScale = 2f, height = 480)
        val buttons = listOf("book_find", "book_play", "book_microphone").map {
            compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled().getBoundsInRoot()
        }
        buttons.forEach { assertTrue(it.right - it.left >= 48.dp); assertTrue(it.bottom - it.top >= 48.dp) }
        assertTrue(buttons[1].left - buttons[0].right >= 12.dp)
        assertTrue(buttons[2].left - buttons[1].right >= 12.dp)
        for (description in listOf("Найти место", "Прокручивать текст", "Найти по микрофону"))
            compose.onNodeWithContentDescription(description).assertIsDisplayed().assertHasClickAction()
        val content = compose.onNodeWithTag("book_content").getBoundsInRoot()
        context.cacheDir.resolve("book-compact-200.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("Reader must retain useful space above the controls: ${content.bottom - content.top}", content.bottom - content.top >= 160.dp)
        // Exercise real movement in a long prayer, not only the Play icon's state.
        compose.onNodeWithTag("book_content").performScrollToIndex(12 * 2 + 1)
        settle()
        fun paragraphTop() = compose.onNodeWithTag("book_text_long", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot().top
        val before = paragraphTop()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue("Autoplay must actually move the text before the menu pauses it", paragraphTop() < before - 4.dp)
        compose.onNodeWithText("Оригинал", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        val paused = saved()
        val pausedTop = paragraphTop()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals("Reading must stay still while the language menu is open", paused, saved())
        assertEquals("Visible text must also stay still", pausedTop, paragraphTop())
        androidx.test.espresso.Espresso.pressBack()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    private fun launch(fontScale: Float = 1f, height: Int = 640) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) {
                    if (mounted.value) Box(Modifier.size(320.dp, height.dp)) {
                        BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
                    }
                }
            }
        }
        settle()
    }

    private fun saved() = requireNotNull(store.last(parish))
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun fixture(): ServiceBook {
        val chapters = (0..29).map { index ->
            val long = index == 12
            val original = if (long) "Господи, помоги нам внимательно слушать молитву. ".repeat(140) + phrase +
                " Помоги сохранить мир в сердце. ".repeat(10) else "Господи, помилуй нас и помоги жить в мире. ".repeat(5)
            BookChapter("chapter-$index", if (index % 3 == 0) "Малая ектения" else "Раздел ${index + 1}",
                "Вместе молимся о мире и просим помощи у Бога",
                listOf(BookBlock(if (long) "long" else "block-$index", BookBlockKind.PRAYER, "Хор", original,
                    russian = if (long) "Господь, помоги нам внимательно слушать. ".repeat(145) else "Просим Бога о мире. ".repeat(8))))
        }
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = chapters, contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
