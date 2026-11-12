package ru.liturgyguide.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Synthetic content exercises reading geometry, not the contents of a real service. */
@RunWith(AndroidJUnit4::class)
class BookReaderIntroductionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "reader-introduction-${UUID.randomUUID()}"
    private val store = BookReadingStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    private val mounted = mutableStateOf(true)
    private val parish = "synthetic-introduction-parish"
    private val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "20")
    private val journey = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечернее богослужение",
        listOf(key.copy(worship = WorshipKind.VESPERS), key), partSet = BookPartSetKind.EVENING_TEXTS)
    private val book = fixture()

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun openingTheBookShowsSeveralPrayerLinesAndKeepsTheDetailsOneTapAway() {
        launch(1f)
        val text = compose.onNodeWithTag("book_text_opening-1", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val textTop = text.getUnclippedBoundsInRoot().top
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(layout.lineCount >= 3)
        assertTrue("The opening screen must show at least three real prayer lines",
            textTop + (layout.getLineBottom(2) / density).dp <= viewport.bottom)
        compose.onNodeWithTag("book_open_calendar").assertIsDisplayed().performClick()
        checkDetailsAndReturn()
        compose.onNodeWithTag("book_text_opening-1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun thePublishedDateAndSelectedMemoryRemainReachableAt320DpAndTwoHundredPercentFont() {
        launch(2f)
        compose.onNodeWithTag("book_open_calendar").performScrollTo()
        settle()
        val before = requireNotNull(store.last(parish))
        compose.onNodeWithTag("book_open_calendar").assertIsDisplayed().performClick()
        checkDetailsAndReturn()
        val after = requireNotNull(store.last(parish))
        assertEquals(before.key, after.key)
        assertEquals(before.mode, after.mode)
        assertEquals(before.blockId, after.blockId)
        assertEquals(before.anchorKind, after.anchorKind)
        assertEquals(before.fraction, after.fraction, .001f)
        assertEquals(before.followingGapFraction, after.followingGapFraction, .02f)
    }

    private fun checkDetailsAndReturn() {
        val details = compose.onNodeWithTag("book_calendar")
        details.performScrollToNode(hasTestTag("book_calendar_schedule_title"))
        compose.onNodeWithTag("book_calendar_schedule_title").assertTextEquals(journey.title).assertIsDisplayed()
        details.performScrollToNode(hasTestTag("book_journey_schedule"))
        compose.onNodeWithTag("book_journey_schedule").assertTextContains("12 ноября", substring = true)
            .assertTextContains("18:00", substring = true)
        details.performScrollToNode(hasTestTag("book_journey_date"))
        compose.onNodeWithTag("book_journey_date").assertTextContains("13 ноября", substring = true)
        details.performScrollToNode(hasTestTag("book_calendar_selected_event"))
        compose.onNodeWithTag("book_calendar_selected_event").assertTextEquals(book.selectedEventTitle!!)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .assertCountEquals(1)[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    private fun launch(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = if (fontScale > 1f) 320 else 360
                screenHeightDp = if (fontScale > 1f) 640 else 780
                this.fontScale = fontScale
            }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) {
                    if (mounted.value) Box(Modifier.size(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)) {
                        BookReaderScreen(book, parish, store, 0, {}, {}, {}, {}, journeyContext = journey)
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun fixture(): ServiceBook {
        val blocks = (1..15).map { index -> BookBlock("opening-$index", BookBlockKind.PRAYER, null,
            "Проверочный текст для измерения строк и свободного пространства на экране. ".repeat(if (index == 1) 25 else 3)) }
        val value = ServiceBook(key = key, title = "Утреня", sourceUrl = key.sourceUrl(),
            sourceTitle = "Синтетическое последование для проверки интерфейса", fetchedAtMillis = 1,
            selectedEventTitle = "Длинное проверочное название памяти дня, которое не должно вытеснять молитвы с первого экрана. ".repeat(3),
            chapters = listOf(BookChapter("opening", "Перед началом", "Проверочное пояснение", blocks)), contentHash = "")
        return value.copy(contentHash = ServiceBookIntegrity.contentHash(value))
    }
}
