package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
        discovery("discovery_manual").performClick()
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
        compose.onNodeWithTag("discovery_query").performTextInput("Томск")
        closeSoftKeyboard()
        // Typing filters the offline directory without starting an internet search.
        // The old picker-empty oracle concerns this local list, not remote results.
        discovery("discovery_saved_empty").assertTextEquals("В сохраненных храмах совпадений нет")
        compose.onNodeWithTag("discovery_loading").assertDoesNotExist()
        discovery("discovery_manual").performClick()
        compose.onNodeWithTag("new_church_name").performTextInput("Незавершенный храм")
        closeSoftKeyboard()
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        discovery("discovery_query").assertTextContains("Томск")
        assertTrue(ChurchDirectoryStore(preferences).records().isEmpty())
        assertNull(preferences.getString("church_id", null))
        assertEquals("creed", preferences.getString("stage_id", null))
        compose.onNodeWithContentDescription("Очистить поисковый запрос").performClick()
        discovery("discovery_saved_elisavetinsky_opalikha").assertIsDisplayed()
        // The unified result row is a button, without selected semantics. Clearing
        // the filter must still leave the actual selected church and reading intact.
        assertNull(preferences.getString("church_id", null))
        assertEquals("creed", preferences.getString("stage_id", null))
    }

    @Test fun favoriteDoesNotChooseAnotherParishOrEraseTheSavedStage() {
        openPicker()
        compose.onNodeWithTag("discovery_query").performTextInput("Нахабино")
        closeSoftKeyboard()
        val favorite = discovery("favorite_vsetsaritsa_nakhabino")
        val row = compose.onNodeWithTag("discovery_saved_vsetsaritsa_nakhabino").assertIsDisplayed()
        val rowBounds = row.getBoundsInRoot()
        val favoriteBounds = favorite.getBoundsInRoot()
        assertTrue("Favorite must be a separate touch target", favoriteBounds.left - rowBounds.right >= 12.dp)
        assertTrue(favoriteBounds.right - favoriteBounds.left >= 48.dp)
        assertTrue(favoriteBounds.bottom - favoriteBounds.top >= 48.dp)
        val beforeFavorite = preferences.all.toMap()
        favorite.performClick()
        assertEquals(setOf("vsetsaritsa_nakhabino"), ChurchDirectoryStore(preferences).favorites())
        assertNull(preferences.getString("church_id", null))
        assertEquals("creed", preferences.getString("stage_id", null))
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("favorite_vsetsaritsa_nakhabino").assert(hasContentDescription("Убрать из избранного:", substring = true))
        compose.onNodeWithTag("discovery_saved_vsetsaritsa_nakhabino").assertHasClickAction()
        assertEquals(beforeFavorite, preferences.all - ChurchDirectoryStore.FAVORITES_KEY)
        compose.activityRule.scenario.recreate()
        discovery("discovery_query").assertTextContains("Нахабино")
        assertEquals(setOf("vsetsaritsa_nakhabino"), ChurchDirectoryStore(preferences).favorites())
        discovery("favorite_vsetsaritsa_nakhabino").assertHasClickAction().performClick()
        assertTrue(ChurchDirectoryStore(preferences).favorites().isEmpty())
        assertEquals("creed", preferences.getString("stage_id", null))
        assertNull(preferences.getString("church_id", null))
        assertEquals(beforeFavorite, preferences.all - ChurchDirectoryStore.FAVORITES_KEY)
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
    }

    private fun openPicker() {
        compose.onNodeWithTag("nav_church").assertIsDisplayed().performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
    }

    private fun discovery(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
}
