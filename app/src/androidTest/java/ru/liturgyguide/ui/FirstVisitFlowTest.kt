package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class FirstVisitFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val now = Instant.parse("2026-11-08T06:00:00Z")
    private val parish get() = container.parishes.getOrThrow().selected(null)

    @Test fun firstVisitOpensThePickerDirectlyAndManualReadingKeepsNoParishAfterRestore() {
        clear()
        val restoration = launch()
        compose.onNodeWithTag("open_church").assertTextContains("Выбрать храм")
        compose.onNodeWithTag("service_card_current").assertDoesNotExist()
        compose.onNodeWithTag("service_card_next").assertDoesNotExist()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").assertIsNotSelected()
        pressBack()
        compose.onNodeWithText("Выберите или добавьте храм").assertIsDisplayed()
        compose.onNodeWithText(parish.name).assertDoesNotExist()
        compose.onNodeWithTag("nav_home").performClick()
        mainClick("start_guide")
        assertNull(ReadingContextStore(preferences).restore()!!.parishId)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertExists()
        pressBack()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
        compose.onNodeWithTag("open_church").assertTextContains("Выбрать храм")
        assertFalse(preferences.contains("church_id"))
    }

    @Test fun firstChoiceFromHomeSurvivesRecreationAndReturnsToTheExactService() {
        clear()
        val restoration = launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        chooseParish()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("open_church").assertTextContains(parish.shortName)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("service_start_next"))
        compose.onNodeWithTag("service_start_next").assertTextEquals("8 ноября · 09:30")
        assertEquals(parish.id, preferences.getString("church_id", null))
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        mainClick("watch_next")
        val reading = requireNotNull(ReadingContextStore(preferences).restore())
        assertEquals(parish.id, reading.parishId)
        assertEquals("2026-11-08", reading.serviceDate)
        assertEquals(Instant.parse("2026-11-08T06:30:00Z").toEpochMilli(), reading.startEpochMillis)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
    }

    @Test fun firstChoiceFromTheChurchTabStaysOnTheChurchPage() {
        clear()
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        chooseParish()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(parish.name).assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
        // Once a church is selected, opening its Home card is ordinary directory navigation.
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("change_church").performClick()
        val other = container.parishes.getOrThrow().directory.churches.first { it.id != parish.id }
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${other.id}"))
        compose.onNodeWithTag("church_${other.id}").performClick()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(other.name).assertIsDisplayed()
        assertEquals(other.id, preferences.getString("church_id", null))
    }

    @Test fun dismissingThePickerKeepsTheFirstHomeEntryUntilAChoiceSucceeds() {
        clear()
        val before = preferences.all.toMap()
        val restoration = launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        withCiFailureDiagnostics("firstVisit.dismissFocusedChurchPicker") {
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.pressKey(android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("change_church").performClick()
        chooseParish()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
    }

    @Test fun leavingFirstHomeEntryThenOpeningTheChurchTabDoesNotReturnHomeOnSelection() {
        clear()
        launch()
        compose.onNodeWithTag("open_church").performClick()
        pressBack()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("nav_church").performClick()
        chooseParish()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
    }

    @Test fun newTempleWithoutScheduleLeadsThroughPersonalTimeToTheExactService() {
        clear()
        launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("add_church"))
        compose.onNodeWithTag("add_church").performClick()
        replace("new_church_name", "Храм в поездке")
        replace("new_church_city", "Казань")
        compose.onNodeWithTag("church_timezone_picker").performScrollTo().performClick()
        compose.onNodeWithTag("zone_Europe/Moscow").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        val saved = ChurchDirectoryStore(preferences).records().single()
        assertEquals(saved.id, preferences.getString("church_id", null))
        assertTrue(saved.asParish().schedule.isEmpty())
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("open_church").assertTextContains(saved.name)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("schedule_unknown"))
        compose.onNodeWithTag("schedule_unknown").assertTextEquals("Литургия")
        compose.onNodeWithTag("nav_church").performClick()
        // A previous look at tomorrow must not change the date of the Main screen's action.
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_tomorrow"))
        compose.onNodeWithTag("schedule_tomorrow").performClick()
        compose.onNodeWithTag("nav_home").performClick()
        mainClick("set_service_time")
        compose.onNodeWithTag("personal_time_form").assertIsDisplayed()
        compose.onNodeWithTag("personal_date_today").assertIsSelected()
        replace("personal_time_input", "09:30")
        compose.onNodeWithTag("personal_time_save").performScrollTo().performClick()
        assertEquals(listOf(UserServiceStart(saved.id, "2026-11-08", "09:30")), UserServiceStartStore(preferences).records())
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("personal_time_to_service"))
        compose.onNodeWithTag("personal_time_to_service").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("service_start_next"))
        compose.onNodeWithTag("service_start_next").assertTextEquals("8 ноября · 09:30")
        mainClick("watch_next")
        val reading = ReadingContextStore(preferences).restore()!!
        assertEquals(saved.id, reading.parishId)
        assertEquals("2026-11-08", reading.serviceDate)
        assertEquals(ReadingContextSource.USER, reading.source)
        assertEquals(Instant.parse("2026-11-08T06:30:00Z").toEpochMilli(), reading.startEpochMillis)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("start_liturgy").assertIsDisplayed().assertContentDescriptionEquals("Литургия началась")
        compose.onNodeWithTag("reader_status").assertTextEquals("До начала литургии")
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
    }

    @Test fun confirmingRecoveredLegacyParishPreservesTheReadingAndPersistsTheChoice() {
        clear()
        val session = ServiceSession("creed", now.minusSeconds(600).toEpochMilli())
        check(preferences.edit().putString("stage_id", "creed").putString("session", Gson().toJson(session))
            .putString("lyric_phrase_creed", "saved phrase").commit())
        val before = preferences.all.toMap()
        val restoration = launch()
        compose.onNodeWithTag("open_church").assertTextContains(parish.shortName)
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").performClick()
        assertEquals(before + ("church_id" to parish.id), preferences.all)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(parish.name).assertIsDisplayed()
        assertEquals(before + ("church_id" to parish.id), preferences.all)
    }

    @Test fun corruptModernMetadataNeverAssignsTheLegacyDefault() {
        clear()
        check(preferences.edit().putString("stage_id", "creed")
            .putString(ReadingContextStore.CONTEXT_KEY, "broken").commit())
        launch()
        compose.onNodeWithTag("open_church").assertTextContains("Выбрать храм")
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
        compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        compose.onNodeWithTag("service_card_current").assertDoesNotExist()
        compose.onNodeWithTag("service_card_next").assertDoesNotExist()
    }

    @Test fun aFailedChoiceKeepsThePickerOpenAndCanBeRetried() {
        var accept = false
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), null, LocalDate.of(2026, 11, 8),
            searchQuery = "", onSearchQueryChange = {}, onSelect = { _, selected -> if (accept) { selected(); ChurchSelectionResult.SELECTED } else ChurchSelectionResult.FAILED }, onOpenUrl = {}, onBack = {}, pickerRequest = 1) } }
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("church_${parish.id}").assertIsNotSelected()
        compose.runOnIdle { accept = true }
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").performClick()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
    }

    @Test fun retryAfterDirectorySaveAndFailedSelectionReusesTheSameRecord() {
        val records = linkedMapOf<String, SavedChurchRecord>()
        var choices = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GuideTheme(false) { ChurchScreen(emptyList(), null, LocalDate.of(2026, 11, 8),
            searchQuery = "", onSearchQueryChange = {}, onSelect = { _, selected -> if (++choices > 1) { selected(); ChurchSelectionResult.SELECTED } else ChurchSelectionResult.FAILED }, onOpenUrl = {}, onBack = {}, pickerRequest = 1,
            onAddChurch = { records[it.id] = it; true }) } }
        compose.onNodeWithTag("add_church").performClick()
        replace("new_church_name", "Храм в поездке")
        replace("new_church_city", "Казань")
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("add_church_form").assertIsDisplayed()
        compose.onNodeWithTag("new_church_error").assertExists()
        val first = records.keys.single()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("new_church_name").assertTextContains("Храм в поездке")
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        assertEquals(setOf(first), records.keys)
        assertEquals(2, choices)
        // A separately added temple must start a new draft, not overwrite the recovered one.
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("add_church").performClick()
        compose.onNodeWithTag("new_church_name").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        replace("new_church_name", "Второй храм")
        replace("new_church_city", "Казань")
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        assertEquals(2, records.size)
        assertEquals("Храм в поездке", records[first]?.name)
        assertEquals("Второй храм", records.values.single { it.id != first }.name)
    }

    private fun chooseParish() {
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").performClick()
    }

    private fun clear() { check(preferences.edit().clear().putBoolean("dark", false).commit()) }
    private fun launch(): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent { AppNavigation(container, Clock.fixed(now, ZoneOffset.UTC)) }
    }
    private fun mainClick(tag: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun replace(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
        closeSoftKeyboard()
    }
}
