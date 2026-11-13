package ru.liturgyguide.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
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
import kotlin.math.roundToInt

/** Real modal, mounted reader and durable positions. No provider or speech engine is needed. */
@RunWith(AndroidJUnit4::class)
class BookMeaningSheetFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var inputModeManager: InputModeManager
    private val preferenceName = "book-meaning-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val dark = mutableStateOf(false)
    private val book = mutableStateOf(fixture())
    private val parish = "meaning-parish"
    private var contentSet = false

    @After fun clean() {
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun detailsAndTheirScrollingCannotMoveTheBookAndCloseRestoresTheRussianLineAndTouchableEntry() {
        seed()
        launch()
        val before = saved()
        val top = paragraphTop()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.onNodeWithTag("book_meaning_title").assertTextEquals("Неизвестная часть")
        compose.onNodeWithTag("book_meaning_what").assertDoesNotExist()
        compose.onNodeWithTag("book_meaning_excerpt_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextEquals(DETAIL)
        assertAnchor(before)
        pressBack(); settle()
        compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
        assertAnchor(before)
        assertTrue(abs((paragraphTop() - top).value) <= 2f)
        compose.onNodeWithTag("book_mode_meaning").assertIsDisplayed().assertIsEnabled().performClick(); settle()
        compose.onNodeWithTag("book_meaning_title").assertTextEquals("Неизвестная часть")
        compose.onNodeWithTag("book_meaning_close").performClick(); settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        compose.mainClock.advanceTimeBy(3_000); compose.waitForIdle()
        assertAnchor(before)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test fun aKeyboardUserReturnsToTheSameFocusedEntryAndRussianLineAfterClosingHelp() {
        seed(); launch()
        val before = saved(); val top = paragraphTop()
        try {
            compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
            settle()
            compose.onNodeWithTag("book_mode_meaning").performSemanticsAction(SemanticsActions.RequestFocus) {
                assertTrue(it())
            }
            compose.onNodeWithTag("book_mode_meaning").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            settle()
            compose.onNodeWithTag("book_meaning_sheet").assertIsDisplayed()
            pressBack(); settle()
            compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
            compose.onNodeWithTag("book_mode_meaning").assertIsFocused()
            assertAnchor(before)
            assertTrue(abs((paragraphTop() - top).value) <= 2f)
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        } finally {
            compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Touch) }
        }
    }

    @Test fun aReviewedBriefExpandsInsideTheSheetAndItsCueBelongsToTheSelectedBook() {
        book.value = fixture(cherubic = true)
        seed()
        launch()
        val before = saved()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.onNodeWithTag("book_meaning_what").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("book_meaning_cue").performScrollTo().assertTextEquals("Иже Херувимы")
        compose.onNodeWithTag("book_meaning_detail").assertDoesNotExist()
        compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick()
        compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextContains("Их освящение будет позже", substring = true)
        compose.onNodeWithTag("book_meaning_source").performScrollTo().assertIsDisplayed()
        assertAnchor(before)
        compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().performClick(); settle()
        assertAnchor(before)
    }

    @Test fun theOldMeaningLanguageAndPrivateAnchorSurviveSavedStateWhileTheTemporarySheetDoesNot() {
        book.value = fixture(privateText = true)
        seed(mode = BookReadingMode.MEANING)
        val restoration = launch()
        compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
        assertEquals(BookReadingMode.RUSSIAN, saved().mode)
        assertTrue(saved().showAltar)
        val before = saved()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.onNodeWithText("О тихой молитве").assertIsDisplayed()
        compose.onNodeWithTag("book_meaning_cue").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore(); settle()
        compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
        assertAnchor(before)
        compose.onNodeWithTag("book_text_long").assertIsDisplayed()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    @Test fun anOldCloseActionCannotTouchAReplacementEditionOrItsAnchor() {
        seed(); launch()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        val oldClose = requireNotNull(compose.onNodeWithTag("book_meaning_close").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        val replacement = fixture(detail = "Другое пояснение")
        assertNotEquals(book.value.contentHash, replacement.contentHash)
        assertTrue(store.save(BookReadingPosition(replacement.key, replacement.contentHash, parish, "long", "chapter",
            fraction = .61f, mode = BookReadingMode.ORIGINAL)))
        compose.runOnIdle { book.value = replacement }; settle()
        val before = saved()
        compose.runOnIdle { assertTrue(oldClose()) }; settle()
        compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
        assertAnchor(before)
        assertEquals(replacement.contentHash, saved().contentHash)
    }

    @Test fun aRealCompactWindowAtTwoHundredPercentKeepsReadableScrollableHelpAndASeparateReturnInBothThemes() {
        withNativeCompactFont {
            book.value = fixture(cherubic = true)
            seed(); launch()
            compose.onNodeWithTag("book_mode_meaning").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp).performClick(); settle()
            for (theme in listOf(false, true)) {
                compose.runOnIdle { dark.value = theme }; settle()
                val close = compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("book_meaning_what").performScrollTo()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("book_meaning_what").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(layouts))
                }
                assertEquals(2f, layouts.single().layoutInput.density.fontScale, .001f)
                assertFalse("Summary text must wrap instead of clipping", layouts.single().didOverflowWidth)
                val range = compose.onNodeWithTag("book_meaning_content").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(range.maxValue() > 0f)
                compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick()
                compose.onNodeWithTag("book_meaning_source").performScrollTo().assertIsDisplayed()
                val contentBounds = compose.onNodeWithTag("book_meaning_content").getBoundsInRoot()
                assertTrue("The fixed return must not overlap the scrolling text", contentBounds.bottom <= close.getBoundsInRoot().top)
                // Collapse again, keeping both theme passes in the same captured session.
                compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick()
            }
            compose.onNodeWithTag("book_meaning_close").performClick(); settle()
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        }
    }

    private fun launch(): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent { GuideTheme(dark.value) {
            inputModeManager = LocalInputModeManager.current
            if (mounted.value) BookReaderScreen(book.value, parish, store, 0, onBack = {}, onOpenUrl = {},
                onChooseVersion = {}, onSettings = {})
        } }
        contentSet = true
        settle()
    }
    private fun seed(mode: BookReadingMode = BookReadingMode.RUSSIAN) {
        assertTrue(store.save(BookReadingPosition(book.value.key, book.value.contentHash, parish, "long", "chapter",
            fraction = .42f, mode = mode, lastTextMode = BookReadingMode.RUSSIAN)))
    }
    private fun saved() = requireNotNull(BookReadingStore(preferences).last(parish))
    private fun assertAnchor(before: BookReadingPosition) {
        val after = saved()
        assertEquals(before.key, after.key)
        assertEquals(before.contentHash, after.contentHash)
        assertEquals(before.parishId, after.parishId)
        assertEquals(before.chapterId, after.chapterId)
        assertEquals(before.blockId, after.blockId)
        assertEquals(before.anchorKind, after.anchorKind)
        assertEquals(before.mode, after.mode)
        assertEquals(before.lastTextMode, after.lastTextMode)
        assertEquals(before.fraction, after.fraction, .001f)
        assertEquals(before.followingGapFraction, after.followingGapFraction, .001f)
    }
    private fun paragraphTop() = compose.onNodeWithTag("book_block_long").getUnclippedBoundsInRoot().top
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun fixture(cherubic: Boolean = false, privateText: Boolean = false, detail: String = DETAIL): ServiceBook {
        val key = ServiceBookKey("2026-11-14")
        val text = (if (cherubic) "Иже Херувимы тайно образующе. " else "Молитва выбранной части. ") +
            "Помоги нам сохранить мир и любовь в наших сердцах. ".repeat(65)
        val value = ServiceBook(key = key, title = "Литургия Иоанна Златоуста", sourceUrl = key.sourceUrl(),
            sourceTitle = "Сохраненная книга", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", if (cherubic) "Херувимская песнь" else "Неизвестная часть", detail,
                listOf(BookBlock("long", BookBlockKind.PRAYER, null, text, "Русское изложение: $text", isAltarText = privateText)))),
            contentHash = "")
        return value.copy(contentHash = ServiceBookIntegrity.contentHash(value))
    }
    private fun withNativeCompactFont(body: () -> Unit) {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val oldFont = shell("settings get system font_scale").toFloatOrNull()
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { activity ->
                    ready = abs(activity.resources.configuration.fontScale - 2f) < .001f &&
                        activity.resources.configuration.screenWidthDp in 300..340 && activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } finally {
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == null) "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
    private companion object { const val DETAIL = "Пояснение выбранной части, сохраненное вместе с книгой" }
}
