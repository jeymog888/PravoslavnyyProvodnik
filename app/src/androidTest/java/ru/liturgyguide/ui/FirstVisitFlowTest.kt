package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.model.WorshipKind
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class FirstVisitFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "first-visit-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences("$prefix-guide", Context.MODE_PRIVATE)
    private val bookPreferences = context.getSharedPreferences("$prefix-books", Context.MODE_PRIVATE)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "guide_preferences" -> preferences
            "service_book_reading" -> bookPreferences
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private val mounted = mutableStateOf(true)
    private val books = NavigationMigrationBooks()
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, booksOverride = books)
    private val now = Instant.parse("2026-11-08T06:00:00Z")
    private val parish get() = container.parishes.getOrThrow().selected(null)

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences("$prefix-guide")
        context.deleteSharedPreferences("$prefix-books")
    }

    @Test fun firstVisitOpensSearchDirectlyAndReadingKeepsNoParishAfterRestore() {
        clear()
        val restoration = launch()
        compose.onNodeWithTag("open_church").assertTextContains("Найти храм").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_saved_${parish.id}"))
        compose.onNodeWithTag("discovery_saved_${parish.id}").assertIsDisplayed()
        assertFalse(preferences.contains("church_id"))
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithText(parish.shortName).assertDoesNotExist()
        mainClick("today_choose_without_church")
        compose.onNodeWithTag("service_book_choose_liturgy").performScrollTo().performClick()
        awaitBook()
        assertNull(ReadingContextStore(preferences).restore())
        assertEquals("2026-11-08", savedBook(null).key.date)
        assertNull(savedBook(null).parishId)
        restoration.emulateSavedInstanceStateRestore()
        awaitBook()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertNull(savedBook(null).parishId)
        compose.onNodeWithTag("book_back").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
        compose.onNodeWithTag("open_church").assertTextContains("Найти храм")
        assertFalse(preferences.contains("church_id"))
    }

    @Test fun firstChoiceFromHomeSurvivesRecreationAndReturnsToTheExactService() {
        clear()
        val restoration = launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        chooseDiscoveredParish(parish.id)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithText(parish.shortName).assertIsDisplayed()
        // At exactly 09:00, the 07:00 occurrence is still in the two-hour window.
        home("today_service_date").assertTextEquals("8 ноября")
        home("today_service_time").assertTextEquals("07:00")
        assertEquals(parish.id, preferences.getString("church_id", null))
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        mainClick("open_service_book")
        awaitBook()
        val reading = savedBook(parish.id)
        assertEquals(parish.id, reading.parishId)
        assertEquals("2026-11-08", reading.key.date)
        assertEquals(WorshipKind.LITURGY, reading.key.worship)
        assertEquals(BookAnchorKind.HEADER, reading.anchorKind)
        assertEquals(0f, reading.fraction, .001f)
        assertNull(ReadingContextStore(preferences).restore())
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    @Test fun firstChoiceFromTheChurchTabStaysOnTheChurchPage() {
        clear()
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        chooseDiscoveredParish(parish.id)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(parish.shortName).performScrollTo().assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
        // Ordinary directory navigation must not inherit the first Home choice origin.
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasText("Сайт и сведения о храме"))
        compose.onNodeWithText("Сайт и сведения о храме").performClick()
        compose.onNodeWithTag("change_church").performScrollTo().performClick()
        val other = container.parishes.getOrThrow().directory.churches.first { it.id != parish.id }
        chooseDiscoveredParish(other.id)
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(other.shortName).performScrollTo().assertIsDisplayed()
        assertEquals(other.id, preferences.getString("church_id", null))
    }

    @Test fun dismissingHomeSearchKeepsTheFirstHomeEntryUntilAChoiceSucceeds() {
        clear()
        val before = preferences.all.toMap()
        val restoration = launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("open_church").performClick()
        chooseDiscoveredParish(parish.id)
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
    }

    @Test fun leavingFirstHomeEntryThenOpeningTheChurchTabDoesNotReturnHomeOnSelection() {
        clear()
        launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("nav_church").performClick()
        chooseDiscoveredParish(parish.id)
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        assertEquals(parish.id, preferences.getString("church_id", null))
    }

    @Test fun newTempleWithoutScheduleLeadsThroughPersonalTimeToTheExactService() {
        clear()
        launch()
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_manual"))
        compose.onNodeWithTag("discovery_manual").performClick()
        replace("new_church_name", "Храм в поездке")
        replace("new_church_city", "Казань")
        compose.onNodeWithTag("church_timezone_picker").performScrollTo().performClick()
        compose.onNodeWithTag("zone_Europe/Moscow").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        val saved = ChurchDirectoryStore(preferences).records().single()
        assertEquals(saved.id, preferences.getString("church_id", null))
        assertTrue(saved.asParish().schedule.isEmpty())
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithText(saved.name).assertIsDisplayed()
        home("typical_schedule").assertIsDisplayed()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_tomorrow"))
        compose.onNodeWithTag("schedule_tomorrow").performClick()
        compose.onNodeWithTag("nav_home").performClick()
        home("today_service_date").assertTextEquals("8 ноября")
        // A personal time is entered for the explicitly selected date in My temple.
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_details"))
        compose.onNodeWithTag("church_details").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("edit_personal_time"))
        compose.onNodeWithTag("edit_personal_time").performClick()
        compose.onNodeWithTag("personal_time_form").assertIsDisplayed()
        compose.onNodeWithTag("personal_date_today").assertIsSelected()
        replace("personal_time_input", "09:30")
        compose.onNodeWithTag("personal_time_save").performScrollTo().performClick()
        assertEquals(listOf(UserServiceStart(saved.id, "2026-11-08", "09:30")), UserServiceStartStore(preferences).records())
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        compose.onNodeWithTag("nav_home").performClick()
        home("today_service_time").assertTextEquals("09:30")
        home("today_personal_source").assertTextEquals("Начало указано вами")
        mainClick("today_open_personal_liturgy")
        awaitBook()
        val reading = savedBook(saved.id)
        assertEquals(saved.id, reading.parishId)
        assertEquals("2026-11-08", reading.key.date)
        assertEquals(WorshipKind.LITURGY, reading.key.worship)
        assertEquals(BookAnchorKind.HEADER, reading.anchorKind)
        assertNull(ReadingContextStore(preferences).restore())
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    @Test fun confirmingRecoveredLegacyParishPreservesTheReadingAndPersistsTheChoice() {
        clear()
        val session = ServiceSession("creed", now.minusSeconds(600).toEpochMilli())
        check(preferences.edit().putString("stage_id", "creed").putString("session", Gson().toJson(session))
            .putString("lyric_phrase_creed", "saved phrase").commit())
        val before = preferences.all.toMap()
        val restoration = launch()
        compose.onNodeWithText(parish.shortName).assertIsDisplayed()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("change_church").performScrollTo().performClick()
        chooseDiscoveredParish(parish.id)
        assertEquals(before + ("church_id" to parish.id), preferences.all)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(parish.shortName).performScrollTo().assertIsDisplayed()
        assertEquals(before + ("church_id" to parish.id), preferences.all)
    }

    @Test fun corruptModernMetadataNeverAssignsTheLegacyDefault() {
        clear()
        check(preferences.edit().putString("stage_id", "creed")
            .putString(ReadingContextStore.CONTEXT_KEY, "broken").commit())
        launch()
        compose.onNodeWithTag("open_church").assertTextContains("Найти храм")
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
        compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        compose.onNodeWithTag("open_service_book").assertDoesNotExist()
        assertNull(BookReadingStore(bookPreferences).last(null))
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
        compose.onNodeWithTag("change_church").performScrollTo().performClick()
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

    private fun dismissPickerWithBack() {
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        // The modal owns focus. Espresso's default root can select the unfocused Activity.
        // Send one real Back key to the dialog, keeping the navigation contract under test.
        withCiFailureDiagnostics("firstVisit.dismissFocusedChurchPicker") {
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.pressKey(android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
    }

    private fun chooseParish() {
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_${parish.id}"))
        compose.onNodeWithTag("church_${parish.id}").performClick()
    }

    private fun chooseDiscoveredParish(id: String) {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_saved_$id"))
        compose.onNodeWithTag("discovery_saved_$id").performClick()
    }
    private fun clear() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        check(bookPreferences.edit().clear().commit())
    }
    private fun launch(): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent {
            CompositionLocalProvider(LocalContext provides localContext, LocalActivityResultRegistryOwner provides compose.activity) {
                if (mounted.value) AppNavigation(container, Clock.fixed(now, ZoneOffset.UTC))
            }
        }
    }
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(650)
        compose.waitForIdle()
    }
    private fun savedBook(parishId: String?) = requireNotNull(BookReadingStore(bookPreferences).last(parishId))
    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
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
