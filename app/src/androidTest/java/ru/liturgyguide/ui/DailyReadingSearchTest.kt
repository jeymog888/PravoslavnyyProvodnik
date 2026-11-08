package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Calendar fixtures keep the reading search regression useful after November 8. */
@RunWith(AndroidJUnit4::class)
class DailyReadingSearchTest {
    @get:Rule val compose = createComposeRule()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun bothSpellingsFindTheSameStageAndPreserveTheEnteredQuery() {
        val selected = AtomicReference<Pair<String, String?>>()
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), null, true, null,
                    onSelect = { id, query -> selected.set(id to query) }, onSearch = {}, onBack = {})
            }
        }
        listOf(
            "святого евангелия" to "gospel", "святаго евангелия" to "gospel",
            "святой боже" to "troparia_trisagion", "святый боже" to "troparia_trisagion"
        ).forEach { (query, stageId) ->
            compose.onNodeWithTag("stage_search").performTextReplacement(query)
            closeSoftKeyboard()
            compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_$stageId"))
            compose.onNodeWithTag("stage_$stageId").assertIsDisplayed().performClick()
            assertEquals(stageId to query, selected.get())
        }
        compose.onNodeWithTag("stage_search").performTextReplacement("святого евангелия квазислово")
        closeSoftKeyboard()
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithTag("stage_gospel").assertDoesNotExist()
    }

    @Test
    fun aWordFromTheDailyGospelFindsItsStageAndPreservesTheQuery() {
        val day = requireNotNull(container.readings?.days?.firstOrNull { it.date == "2026-11-08" })
        val selected = AtomicReference<Pair<String, String?>>()
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), null, true, day,
                    onSelect = { id, query -> selected.set(id to query) }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_search").performTextInput("Лазарь")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_gospel"))
        compose.onNodeWithTag("stage_gospel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("stage_epistle").assertDoesNotExist()
        assertEquals("gospel" to "Лазарь", selected.get())
    }

    @Test
    fun aReadingFromAnotherDayIsNotOfferedAsTodaysText() {
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), null, true, null,
                    onSelect = { _, _ -> }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_search").performTextInput("Лазарь")
        closeSoftKeyboard()
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithTag("stage_gospel").assertDoesNotExist()
    }

    @Test
    fun aMissingDailyReadingIsExplainedInBothTextAndMeaning() {
        val date = LocalDate.of(2026, 11, 9)
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date, null)
        val context = ReadingContext(null, date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
        val player = ServicePlayerState(ActiveServicePlayback(plan, context, ServicePlaybackEngine(plan).start("gospel")),
            monotonicMillis = { 0L }, writer = ServicePlaybackWriter { _, _, _ -> true })
        compose.setContent {
            GuideTheme(dark = false) {
                ServiceScreen(player = player, onStageChange = {},
                    onBack = {}, onSelectStage = {}, onFindStage = {}, onSettings = {}, dark = false,
                    onDarkChange = {}, onOpenSource = {}, onSoundSettings = {}, onFinish = {},
                    preferredOriginal = false, onOriginalChange = {})
            }
        }
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag("reading_unavailable"))
        compose.onNodeWithTag("reading_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("reading_unavailable"))
        compose.onNodeWithTag("reading_unavailable").assertIsDisplayed()
    }
}
