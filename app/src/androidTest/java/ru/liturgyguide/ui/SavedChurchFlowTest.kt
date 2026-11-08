package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import ru.liturgyguide.service.ChurchDirectoryStore

@RunWith(AndroidJUnit4::class)
class SavedChurchFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val clean = object : ExternalResource() {
        override fun before() { check(preferences.edit().clear().putBoolean("dark", false).putString("stage_id", "creed").commit()) }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)

    @Test fun aTempleInAnotherCityAndItsTimeZoneSurviveRecreation() {
        openPicker()
        compose.onNodeWithTag("add_church").performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("new_church_error").assertTextEquals("Введите название храма")
        compose.onNodeWithTag("new_church_name").performScrollTo().performTextInput("Храм в поездке")
        compose.onNodeWithTag("new_church_city").performScrollTo().performTextInput("Владивосток")
        closeSoftKeyboard()
        compose.onNodeWithTag("church_timezone_picker").performScrollTo().performClick()
        compose.onNodeWithTag("zone_Asia/Vladivostok").performScrollTo().performClick()
        val beforeSave = preferences.all.toMap()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        val beforeChoice = preferences.all.toMap()
        assertEquals(beforeSave, beforeChoice - ChurchDirectoryStore.RECORDS_KEY)
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("new_church_error").assertDoesNotExist()
        compose.onNodeWithTag("cancel_church_change").performClick()
        compose.runOnIdle { assertEquals(beforeChoice, preferences.all) }
        compose.onNodeWithTag("add_church_form").assertIsDisplayed()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithText("Храм в поездке").assertIsDisplayed()
        val saved = ChurchDirectoryStore(preferences).records().single()
        assertEquals("Asia/Vladivostok", saved.timezone)
        assertEquals("Владивосток", saved.city)
        assertEquals(saved.id, preferences.getString("church_id", null))
        assertNull(saved.latitude)
        assertTrue(saved.asParish().schedule.isEmpty())
        // Selecting a different parish clears the old service, not the reading settings.
        assertNull(preferences.getString("stage_id", null))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Храм в поездке").assertIsDisplayed()
        assertEquals(saved, ChurchDirectoryStore(preferences).records().single())
        compose.onNodeWithText("Сайт прихода").assertDoesNotExist()
        compose.onNodeWithText("Позвонить в храм").assertDoesNotExist()
    }

    @Test fun cancellingANewTempleAndAnEmptySearchKeepTheCurrentParish() {
        openPicker()
        compose.onNodeWithTag("church_search").performTextInput("Томск")
        closeSoftKeyboard()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_search_empty"))
        compose.onNodeWithTag("church_search_empty").assertIsDisplayed()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("add_church"))
        compose.onNodeWithTag("add_church").performClick()
        compose.onNodeWithTag("new_church_name").performTextInput("Незавершенный храм")
        closeSoftKeyboard()
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("church_search").assertTextContains("Томск")
        assertTrue(ChurchDirectoryStore(preferences).records().isEmpty())
        assertNull(preferences.getString("church_id", null))
        assertEquals("creed", preferences.getString("stage_id", null))
        compose.onNodeWithContentDescription("Очистить поиск храмов").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_elisavetinsky_opalikha"))
        compose.onNodeWithTag("church_elisavetinsky_opalikha").assertIsSelected()
    }

    @Test fun favoriteDoesNotChooseAnotherParishOrEraseTheSavedStage() {
        openPicker()
        compose.onNodeWithTag("church_search").performTextInput("Нахабино")
        closeSoftKeyboard()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("favorite_vsetsaritsa_nakhabino"))
        compose.onNodeWithTag("favorite_vsetsaritsa_nakhabino").performClick()
        assertEquals(setOf("vsetsaritsa_nakhabino"), ChurchDirectoryStore(preferences).favorites())
        assertNull(preferences.getString("church_id", null))
        assertEquals("creed", preferences.getString("stage_id", null))
        compose.onNodeWithTag("church_vsetsaritsa_nakhabino").assertIsNotSelected()
        compose.activityRule.scenario.recreate()
        assertEquals(setOf("vsetsaritsa_nakhabino"), ChurchDirectoryStore(preferences).favorites())
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("favorite_vsetsaritsa_nakhabino"))
        compose.onNodeWithTag("favorite_vsetsaritsa_nakhabino").performClick()
        assertTrue(ChurchDirectoryStore(preferences).favorites().isEmpty())
        assertEquals("creed", preferences.getString("stage_id", null))
        pressBack()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
    }

    private fun openPicker() {
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("change_church").performClick()
    }
}
