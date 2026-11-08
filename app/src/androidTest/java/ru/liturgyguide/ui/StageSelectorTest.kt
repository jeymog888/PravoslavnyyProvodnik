package ru.liturgyguide.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer

@RunWith(AndroidJUnit4::class)
class StageSelectorTest {
    @get:Rule val compose = createComposeRule()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun updatingTheCurrentStageDoesNotInterruptManualBrowsing() {
        val selectedId = mutableStateOf("creed")
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), selectedId.value, false, null,
                    onSelect = { _, _ -> }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_creed").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        val beforeUpdate = compose.onNodeWithTag("stage_preparation").getBoundsInRoot()
        compose.runOnIdle { selectedId.value = "dismissal" }
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        assertEquals(beforeUpdate, compose.onNodeWithTag("stage_preparation").getBoundsInRoot())
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
    }

    @Test
    fun searchStartsAtTheTopEvenWhenTheCurrentStageIsLate() {
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), "dismissal", true, null,
                    onSelect = { _, _ -> }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_search").assertIsDisplayed()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
    }

    @Test
    fun editingAndClearingAQueryShowTheBeginningOfTheNewResults() {
        compose.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), null, true, null,
                    onSelect = { _, _ -> }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        compose.onNodeWithTag("stage_dismissal").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsNotDisplayed()
        compose.onNodeWithTag("stage_search").performTextReplacement("молит")
        closeSoftKeyboard()
        // Both the old late item and the first item match this query.
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        compose.onNodeWithTag("stage_dismissal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithTag("stage_search").assertIsFocused()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
    }

    @Test
    fun restoringSearchKeepsTheQueryAndTheManuallyBrowsedPosition() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            GuideTheme(dark = false) {
                StageSelector(container.getStageMatcher(), null, true, null,
                    onSelect = { _, _ -> }, onSearch = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("stage_search").performTextReplacement("молит")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        compose.onNodeWithTag("stage_dismissal").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsNotDisplayed()
        val before = compose.onNodeWithTag("stage_dismissal").getBoundsInRoot()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stage_search").assertTextContains("молит")
        compose.onNodeWithTag("stage_dismissal").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsNotDisplayed()
        assertEquals(before, compose.onNodeWithTag("stage_dismissal").getBoundsInRoot())
    }
}
