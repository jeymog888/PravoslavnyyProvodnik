package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.UserServiceStart
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PersonalServiceTimeTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 11, 8)
    private val parish = Parish("test", "Храм в поездке", "Храм в поездке", "Москва", "", "", "", "Europe/Moscow",
        "", "", "2026-11-01", "2026-11-30", listOf(ParishService("2026-11-08", "09:00", "Поздняя литургия", "liturgy")), "", "")

    @Test fun changingTheDateLoadsOnlyThatDatesSavedTime() {
        val saved = listOf(UserServiceStart(parish.id, today.toString(), "09:30"),
            UserServiceStart(parish.id, today.plusDays(1).toString(), "08:00"),
            UserServiceStart(parish.id, today.plusDays(4).toString(), "10:15"))
        var writes = 0
        compose.setContent { GuideTheme(false) { PersonalServiceTimeForm(parish, today, userStarts = saved,
            onSave = { writes++; true }, onCancel = {}) } }
        compose.onNodeWithTag("personal_time_input").assertTextContains("09:30")
        click("personal_date_tomorrow")
        compose.onNodeWithTag("personal_time_input").assertTextContains("08:00")
        click("personal_date_other")
        replace("personal_date_input", "12.11.2026")
        compose.onNodeWithTag("personal_time_input").assertTextContains("10:15")
        replace("personal_date_input", "13.11.2026")
        compose.onNodeWithTag("personal_time_input").assertTextContains("Начало литургии")
        compose.onNodeWithTag("personal_time_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun impossibleDateAndOutOfRangeTimeRemainEditableWithoutSaving() {
        var writes = 0
        compose.setContent { GuideTheme(false) { PersonalServiceTimeForm(parish, today, userStarts = emptyList(),
            onSave = { writes++; true }, onCancel = {}) } }
        click("personal_date_other")
        replace("personal_date_input", "31.02.2026")
        replace("personal_time_input", "09:30")
        click("personal_time_save")
        compose.onNodeWithTag("personal_time_error").assertTextContains("существующую дату", substring = true)
        compose.runOnIdle { assertEquals(0, writes) }
        replace("personal_date_input", "08.11.2026")
        replace("personal_time_input", "25:00")
        click("personal_time_save")
        compose.onNodeWithTag("personal_time_error").assertTextContains("00:00 до 23:59", substring = true)
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("personal_time_input").assertTextContains("25:00")
        replace("personal_time_input", "09:30:15")
        click("personal_time_save")
        compose.onNodeWithTag("personal_time_input").assertTextContains("09:30:15")
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun daylightSavingGapAndRepeatedHourAreNotSilentlyCorrected() {
        var writes = 0
        val berlin = parish.copy(timezone = "Europe/Berlin")
        compose.setContent { GuideTheme(false) { PersonalServiceTimeForm(berlin, today, userStarts = emptyList(),
            onSave = { writes++; true }, onCancel = {}) } }
        click("personal_date_other")
        for (date in listOf("29.03.2026", "25.10.2026")) {
            replace("personal_date_input", date)
            replace("personal_time_input", "02:30")
            click("personal_time_save")
            compose.onNodeWithTag("personal_time_error").assertTextContains("нельзя однозначно", substring = true)
            compose.onNodeWithTag("personal_time_input").assertTextContains("02:30")
        }
        compose.runOnIdle { assertEquals(0, writes) }
        // Before Berlin's autumn transition the same date still has the summer offset.
        replace("personal_time_input", "01:30")
        compose.onNodeWithTag("personal_time_zone").assertTextEquals("Местное время храма · UTC+02:00")
    }

    @Test fun failedStorageKeepsTheDraftAndCancelDoesNotTryAgain() {
        var writes = 0
        var cancels = 0
        compose.setContent { GuideTheme(false) { PersonalServiceTimeForm(parish, today, userStarts = emptyList(),
            onSave = { writes++; false }, onCancel = { cancels++ }) } }
        replace("personal_time_input", "10:20")
        click("personal_time_save")
        compose.onNodeWithTag("personal_time_error").assertTextContains("Не удалось сохранить", substring = true)
        compose.onNodeWithTag("personal_time_input").assertTextContains("10:20")
        click("personal_time_cancel")
        compose.runOnIdle { assertEquals(1, writes); assertEquals(1, cancels) }
    }

    @Test fun anAbsoluteDateAndDraftSurviveRestorationAndSaveWithTheParishContext() {
        val restore = StateRestorationTester(compose)
        var saved: UserServiceStart? = null
        restore.setContent { GuideTheme(false) { PersonalServiceTimeForm(parish, today, userStarts = emptyList(),
            onSave = { saved = it; true }, onCancel = {}) } }
        click("personal_date_other")
        replace("personal_date_input", "03.12.2026")
        replace("personal_time_input", "07:45")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("personal_date_input").assertTextContains("03.12.2026")
        compose.onNodeWithTag("personal_time_input").assertTextContains("07:45")
        compose.onNodeWithTag("personal_time_zone").assertTextEquals("Местное время храма · UTC+03:00")
        click("personal_time_save")
        compose.runOnIdle { assertEquals(UserServiceStart("test", "2026-12-03", "07:45"), saved) }
    }

    @Test fun removalTargetsOnlyTheSelectedDateAndReportsFailure() {
        val saved = listOf(UserServiceStart(parish.id, today.toString(), "09:30"),
            UserServiceStart(parish.id, today.plusDays(1).toString(), "08:00"))
        val removed = mutableListOf<LocalDate>()
        var writes = 0
        compose.setContent { GuideTheme(false) { PersonalServiceTimeForm(parish, today, userStarts = saved,
            onSave = { writes++; true }, onRemove = { removed += it; false }, onCancel = {}) } }
        click("personal_date_tomorrow")
        click("personal_time_remove")
        compose.onNodeWithTag("personal_time_error").assertTextContains("Не удалось убрать", substring = true)
        compose.runOnIdle { assertEquals(listOf(today.plusDays(1)), removed); assertEquals(0, writes) }
        click("personal_date_other")
        replace("personal_date_input", "15.11.2026")
        compose.onNodeWithTag("personal_time_remove").assertDoesNotExist()
    }

    @Test fun publishedAndPersonalStartsStaySeparateAndRemovingPersonalRestoresTheEmptyState() {
        val starts = mutableStateOf(listOf(UserServiceStart(parish.id, today.toString(), "10:00")))
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, today,
            searchQuery = "", onSearchQueryChange = {}, onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {}, userStarts = starts.value,
            onSaveStart = { value -> starts.value = listOf(value); true },
            onRemoveStart = { date -> starts.value = starts.value.filterNot { it.date == date.toString() }; true }) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasText("Расписание прихода"))
        compose.onNodeWithText("09:00").assertIsDisplayed()
        compose.onNodeWithText("Поздняя литургия").assertIsDisplayed()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("personal_start_summary"))
        compose.onNodeWithTag("personal_start_summary").assertTextEquals("10:00 · Литургия")
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("edit_personal_time"))
        compose.onNodeWithTag("edit_personal_time").performClick()
        click("personal_time_remove")
        compose.onNodeWithTag("personal_time_form").assertDoesNotExist()
        compose.onNodeWithTag("personal_start_summary").assertDoesNotExist()
        compose.onNodeWithTag("church_content").performScrollToNode(hasText("09:00"))
        compose.onNodeWithText("09:00").assertIsDisplayed()
        compose.runOnIdle { assertTrue(starts.value.isEmpty()); assertEquals("09:00", parish.schedule.single().time) }
    }

    private fun click(tag: String) { closeSoftKeyboard(); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        closeSoftKeyboard()
    }
}
