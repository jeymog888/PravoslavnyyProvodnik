package ru.liturgyguide.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DateRolloverTest {
    @get:Rule val compose = createComposeRule()
    private val parishes = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext).parishes.getOrThrow()

    @Test
    fun mainDateUpdatesWhileTheScreenStaysOpen() {
        val currentDate = mutableStateOf(LocalDate.of(2026, 11, 8))
        compose.setContent {
            GuideTheme(dark = false) {
                MainScreen(lastStage = null, currentDate = currentDate.value,
                    onStart = {}, onResume = {}, onFindStage = {}, church = parishes.selected(null),
                    onChurch = {}, suggestion = null, onJoinService = {}, onOrder = {}, bottomBar = {})
            }
        }
        compose.onNodeWithText("8 ноября, воскресенье").assertIsDisplayed()

        compose.runOnIdle { currentDate.value = LocalDate.of(2026, 11, 9) }

        compose.onNodeWithText("9 ноября, понедельник").assertIsDisplayed()
        compose.onNodeWithText("8 ноября, воскресенье").assertDoesNotExist()
    }

    @Test
    fun scheduleKeepsTodayAndTomorrowRelativeAcrossTheMonthBoundary() {
        val currentDate = mutableStateOf(LocalDate.of(2026, 11, 29))
        compose.setContent {
            GuideTheme(dark = false) {
                ChurchScreen(churches = parishes.directory.churches, selectedId = parishes.selected(null).id,
                    currentDate = currentDate.value, onSelect = {}, onOpenUrl = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").assertIsSelected()
        assertScheduleText("29 ноября 2026, воскресенье")
        assertScheduleText("07:00")
        assertScheduleText("09:30")

        compose.runOnIdle { currentDate.value = LocalDate.of(2026, 11, 30) }

        compose.onNodeWithTag("schedule_today").assertIsSelected()
        assertScheduleText("30 ноября 2026, понедельник")
        assertScheduleText("08:00")
        compose.onNodeWithText("07:00").assertDoesNotExist()
        compose.onNodeWithText("09:30").assertDoesNotExist()

        chooseScheduleDay("schedule_tomorrow")
        assertScheduleText("1 декабря 2026, вторник")
        assertNoConfirmedSchedule()

        compose.runOnIdle { currentDate.value = LocalDate.of(2026, 12, 1) }

        compose.onNodeWithTag("schedule_tomorrow").assertIsSelected()
        compose.onNodeWithTag("schedule_today").assertIsNotSelected()
        assertScheduleText("2 декабря 2026, среда")
        compose.onNodeWithText("1 декабря 2026, вторник").assertDoesNotExist()
        assertNoConfirmedSchedule()

        chooseScheduleDay("schedule_today")
        assertScheduleText("1 декабря 2026, вторник")
        assertNoConfirmedSchedule()
    }

    private fun chooseScheduleDay(tag: String) {
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }

    private fun assertScheduleText(text: String) {
        compose.onNodeWithTag("church_content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun assertNoConfirmedSchedule() {
        assertScheduleText("На этот день нет опубликованного расписания в приложении")
        compose.onNodeWithText("07:00").assertDoesNotExist()
        compose.onNodeWithText("08:00").assertDoesNotExist()
        compose.onNodeWithText("09:30").assertDoesNotExist()
    }
}
