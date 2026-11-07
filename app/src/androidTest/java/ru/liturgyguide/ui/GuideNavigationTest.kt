package ru.liturgyguide.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.ServiceSession
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GuideNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val preferences
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)

    // Reset before the activity reads its preferences, not after saved Compose
    // state has already captured values from a previous test.
    private val cleanPreferences = object : ExternalResource() {
        override fun before() {
            check(preferences.edit().clear().putBoolean("dark", false).commit())
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(cleanPreferences).around(compose)

    @Test
    fun readingCanAdvanceAndReturnWithoutLosingTheStage() {
        tapMain("start_guide")
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("previous_stage").assertDoesNotExist()

        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Начало литургии")
        compose.onNodeWithTag("previous_stage").performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Подготовка к литургии")
    }

    @Test
    fun heardWordsLeadToTheRelevantExplanation() {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextInput("херувимы")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_cherubic_hymn"))
        compose.onNodeWithTag("stage_cherubic_hymn").performClick()

        compose.onNodeWithTag("current_stage_title").assertTextEquals("Херувимская песнь")
        compose.onNodeWithTag("service_content").performScrollToNode(hasText("Простыми словами"))
        compose.onNodeWithText("Простыми словами").assertIsDisplayed()
    }

    @Test
    fun anUnknownPhraseCanBeClearedToRestoreTheGuide() {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextInput("несуществующаяфраза")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasText("Пока не нашли эти слова"))
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()

        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithTag("stage_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
    }

    @Test
    fun systemBackFromTheStageMapReturnsToTheCurrentReading() {
        tapMain("start_guide")
        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_search").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Начало литургии")
    }

    @Test
    fun recreationAndReturningHomeKeepTheSelectedStage() {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextInput("Отче наш")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_lords_prayer"))
        compose.onNodeWithTag("stage_lords_prayer").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Подготовка к Причащению")
        assertEquals("lords_prayer", preferences.getString("stage_id", null))

        pressBack()
        tapMain("resume_guide")
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Подготовка к Причащению")
    }

    @Test
    fun largeTextAndDarkModePersistAndNavigationRemainsUsable() {
        tapMain("open_settings")
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("dark_switch"))
        compose.onNodeWithTag("dark_switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").performClick()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("dark_switch"))
        compose.onNodeWithTag("dark_switch").assertIsOn()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").assertIsDisplayed().assertIsSelected()
        assertTrue(preferences.getBoolean("dark", false))
        assertEquals(2, preferences.getInt("text_size", -1))

        pressBack()
        tapMain("start_guide")
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("next_stage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Начало литургии")
    }

    @Test
    fun automaticFollowingNeedsConfirmationAndCanBePaused() {
        tapMain("start_guide")
        compose.onNodeWithTag("toggle_follow").assertTextContains("Авто").performClick()
        compose.onNodeWithText("Примерное продвижение").assertIsDisplayed()
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithText("Менять этапы самому").performClick()
        assertFalse(savedSession().autoAdvance)

        compose.onNodeWithTag("toggle_follow").performClick()
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithTag("toggle_follow").assertTextContains("Пауза")
        compose.onNodeWithText("ПРИМЕРНЫЙ ЭТАП ПО ВРЕМЕНИ").assertIsDisplayed()
        assertTrue(savedSession().autoAdvance)

        compose.onNodeWithTag("toggle_follow").performClick()
        compose.onNodeWithTag("toggle_follow").assertTextContains("Авто")
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Начало литургии")
        compose.onNodeWithText("ПРИМЕРНЫЙ ЭТАП ПО ВРЕМЕНИ").assertDoesNotExist()
        assertFalse(savedSession().autoAdvance)
    }

    @Test
    fun prayerMeaningsStayOpenAcrossStagesAndActivityRecreation() {
        tapMain("start_guide")
        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("toggle_texts"))
        compose.onNodeWithTag("toggle_texts").performClick().assertTextContains("Свернуть тексты")
        assertTrue(preferences.getBoolean("show_texts", false))
        compose.onNodeWithTag("service_content").performScrollToNode(hasText("СМЫСЛ ПО-РУССКИ"))
        compose.onAllNodesWithText("СМЫСЛ ПО-РУССКИ").onFirst().assertIsDisplayed()

        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Молитва о мире и обо всех")
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("toggle_texts"))
        compose.onNodeWithTag("toggle_texts").assertTextContains("Свернуть тексты")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("toggle_texts"))
        compose.onNodeWithTag("toggle_texts").assertTextContains("Свернуть тексты")
        compose.onNodeWithTag("service_content").performScrollToNode(hasText("СМЫСЛ ПО-РУССКИ"))
        compose.onAllNodesWithText("СМЫСЛ ПО-РУССКИ").onFirst().assertIsDisplayed()
    }

    @Test
    fun defaultParishShowsTodaysDataAndManualChoiceIsAlwaysAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ParishRepository(context.assets.open("churches.json").bufferedReader().use { it.readText() })
        val parish = repository.selected(null)
        val today = LocalDate.now(ZoneId.of(parish.timezone))
        assertEquals("elisavetinsky_opalikha", parish.id)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
        compose.onNodeWithTag("open_church").assertTextContains("Елисаветинский храм")
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("church_details"))
        compose.onNodeWithTag("church_details").assertTextContains(friendlyDate(today))
        if (repository.scheduleFor(parish, today).isEmpty()) {
            compose.onNodeWithTag("church_details").assertTextContains("На сегодня нет подтвержденных данных в приложении")
        }

        // This route remains usable whether a timed suggestion is present or
        // the current day has no published schedule. It never requires a mic.
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextInput("благодарение")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        compose.onNodeWithTag("stage_dismissal").performClick()
        compose.onNodeWithTag("current_stage_title").assertTextEquals("Благодарение и завершение")
        assertFalse(savedSession().autoAdvance)
    }

    @Test
    fun landscapeWithLargestAppTextKeepsTheStageAndNavigationVisible() {
        tapMain("open_settings")
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").performClick()
        pressBack()

        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            tapMain("start_guide")
            compose.onNodeWithTag("current_stage_title")
                .assertTextEquals("Подготовка к литургии").assertIsDisplayed()
            compose.onNodeWithTag("next_stage").assertIsDisplayed().performClick()
            compose.onNodeWithTag("current_stage_title")
                .assertTextEquals("Начало литургии").assertIsDisplayed()
            compose.onNodeWithTag("previous_stage").assertIsDisplayed()
            compose.onNodeWithTag("next_stage").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            compose.waitForIdle()
        }
    }

    private fun savedSession(): ServiceSession = requireNotNull(
        Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    )

    private fun tapMain(tag: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
}
