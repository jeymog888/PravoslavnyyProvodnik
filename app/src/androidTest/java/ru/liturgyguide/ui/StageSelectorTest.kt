package ru.liturgyguide.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
}
