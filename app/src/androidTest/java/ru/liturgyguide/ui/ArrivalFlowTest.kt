package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.ServiceSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ArrivalFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-11-08T04:35:20Z")
    private val fixedClock = Clock.fixed(instant, ZoneOffset.UTC)
    private val creedTracks = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById("creed")), null)

    @Test
    fun previewSearchBackAndCancelPreserveSavedReadingAndSettingsThroughRestoration() {
        seed()
        val before = preferences.all.toMap()
        val restoration = launch()
        join()
        compose.onNodeWithText("Елисаветинский храм").assertIsDisplayed()
        compose.onNodeWithText("Начало по расписанию в 07:00").assertIsDisplayed()
        compose.onNodeWithText("Предположение по времени").assertIsDisplayed()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Молитвы после Евангелия")
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(before, preferences.all)
        openSearch()
        typeSearch("духа святого животворящаго")
        compose.onNodeWithTag("stage_creed").assertIsNotSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stage_search").assertTextContains("духа святого животворящаго")
        assertEquals(before, preferences.all)
        back()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Молитвы после Евангелия")
        back()
        compose.onNodeWithTag("main_content").assertExists()
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("lyric_line_3").assertIsSelected()
        assertGlobals()
    }

    @Test
    fun acceptingScheduleUsesTheSnapshotAndClearsOnlyOldReadingPositions() {
        seed()
        val clock = AdjustableClock(instant)
        val restoration = launch(clock)
        join()
        compose.runOnIdle { clock.current = instant.plusSeconds(600) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Молитвы после Евангелия")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Молитвы после Евангелия")
        accept()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Молитвы после Евангелия")
        val session = savedSession()
        assertEquals("litanies", session.anchorStageId)
        assertEquals(clock.instant().toEpochMilli(), session.anchorEpochMillis)
        assertEquals(260_000L, session.elapsedInStageMillis)
        assertTrue(session.anchorIsApproximate)
        assertFalse(session.autoAdvance)
        assertOldPositionsCleared()
        assertGlobals()
        compose.onNodeWithContentDescription("Начать автопрокрутку").assertExists()
    }

    @Test
    fun alternativeSearchStartsANewManualServiceAtTheFoundTrackAndPhrase() {
        seed()
        launch()
        join()
        openSearch()
        typeSearch("духа святого животворящаго")
        compose.onNodeWithTag("stage_creed").assertIsNotSelected().assertIsDisplayed().performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        val track = creedTracks[1]
        assertEquals(track.id, preferences.getString("lyric_track_creed", null))
        assertEquals(track.lines[12].id, preferences.getString("lyric_phrase_${track.id}", null))
        assertEquals(12, preferences.getInt("lyric_line_${track.id}", -1))
        assertEquals(0L, preferences.getLong("lyric_offset_${track.id}", -1L))
        assertFalse(preferences.contains("lyric_phrase_old_track"))
        val session = savedSession()
        assertEquals("creed", session.anchorStageId)
        assertEquals(instant.toEpochMilli(), session.anchorEpochMillis)
        assertEquals(0L, session.elapsedInStageMillis)
        assertFalse(session.autoAdvance)
        assertFalse(session.anchorIsApproximate)
        assertGlobals()
        compose.onNodeWithContentDescription("Начать автопрокрутку").assertExists()
    }

    @Test
    fun newArrivalDoesNotReuseTheCancelledSearchQueryListeningOrScroll() {
        seed()
        val before = preferences.all.toMap()
        launch()
        join()
        openSearch()
        typeSearch("верую")
        back()
        openSearch()
        compose.onNodeWithTag("stage_search").assertTextContains("верую")
        back()
        back()
        join()
        openSearch()
        compose.onNodeWithTag("stage_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription("Очистить поиск").assertDoesNotExist()
        compose.onNodeWithTag("open_listening").performClick()
        compose.onNodeWithText("Скрыть аудиоподсказку").assertIsDisplayed()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        back()
        back()
        join()
        openSearch()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithText("Подсказка по звуку").assertIsDisplayed()
        compose.onNodeWithText("Скрыть аудиоподсказку").assertDoesNotExist()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
        assertEquals(before, preferences.all)
    }

    @Test
    fun upcomingServiceOffersPreparationWithoutClaimingACurrentStage() {
        seed()
        val before = preferences.all.toMap()
        launch(Clock.fixed(Instant.parse("2026-11-08T03:30:00Z"), ZoneOffset.UTC))
        join()
        compose.onNodeWithTag("arrival_upcoming").assertTextEquals("По расписанию служба еще не началась")
        compose.onNodeWithTag("arrival_stage").assertDoesNotExist()
        compose.onNodeWithText("Предположение по времени").assertIsDisplayed()
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithText("Открыть подготовку").assertIsDisplayed()
        accept()
        val session = savedSession()
        assertEquals("preparation", session.anchorStageId)
        assertEquals(0L, session.elapsedInStageMillis)
        assertFalse(session.autoAdvance)
        assertFalse(session.anchorIsApproximate)
        assertOldPositionsCleared()
        assertGlobals()
    }

    @Test
    fun dateChangeRejectsTheOldEstimateButStillAllowsAManualChoice() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(instant)
        launch(clock)
        join()
        // Accept before the five-second refresh to also exercise the action's own date check.
        compose.runOnIdle { clock.current = instant.plusSeconds(24 * 3_600) }
        accept()
        compose.onNodeWithTag("accept_arrival").assertIsNotEnabled()
        compose.onNodeWithTag("arrival_expired").assertExists()
        assertEquals(before, preferences.all)
        openSearch()
        typeSearch("духа святого животворящаго")
        compose.onNodeWithTag("stage_creed").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected()
        assertEquals(clock.instant().toEpochMilli(), savedSession().anchorEpochMillis)
        assertFalse(savedSession().anchorIsApproximate)
    }

    private fun launch(clock: Clock = fixedClock): StateRestorationTester = StateRestorationTester(compose).also {
        it.setContent { AppNavigation(container, clock) }
    }

    private fun seed() {
        val track = creedTracks[1]
        check(preferences.edit().clear()
            .putBoolean("dark", true).putInt("text_size", 0)
            .putBoolean("lyric_original", true).putInt("lyric_tempo", 8).putBoolean("lyric_intro_seen", true)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli(),
                autoAdvance = true, elapsedInStageMillis = 2_000L, anchorIsApproximate = true)))
            .putString("lyric_track_creed", track.id)
            .putInt("lyric_line_${track.id}", 3).putString("lyric_phrase_${track.id}", track.lines[3].id)
            .putInt("lyric_saved_tempo_${track.id}", 8).putLong("lyric_offset_${track.id}", 2_000L)
            .putString("lyric_track_old_stage", "old_track").putInt("lyric_line_old_track", 9)
            .putString("lyric_phrase_old_track", "old_phrase").putLong("lyric_offset_old_track", 500L)
            .putInt("lyric_saved_tempo_old_track", 16).commit())
    }

    private fun join() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("join_service"))
        compose.onNodeWithTag("join_service").performClick()
    }

    private fun openSearch() {
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("arrival_search"))
        compose.onNodeWithTag("arrival_search").performClick()
    }

    private fun accept() {
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
    }

    private fun typeSearch(query: String) {
        compose.onNodeWithTag("stage_search").performTextInput(query)
        closeSoftKeyboard()
    }

    private fun back() { compose.onNodeWithContentDescription("Назад").performClick() }

    private fun savedSession(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)

    private fun assertGlobals() {
        assertTrue(preferences.getBoolean("dark", false))
        assertEquals(0, preferences.getInt("text_size", -1))
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertEquals(8, preferences.getInt("lyric_tempo", -1))
        assertTrue(preferences.getBoolean("lyric_intro_seen", false))
    }

    private fun assertOldPositionsCleared() {
        assertFalse(preferences.contains("lyric_track_creed"))
        assertFalse(preferences.contains("lyric_line_${creedTracks[1].id}"))
        assertFalse(preferences.contains("lyric_phrase_${creedTracks[1].id}"))
        assertFalse(preferences.contains("lyric_offset_${creedTracks[1].id}"))
        assertFalse(preferences.contains("lyric_saved_tempo_${creedTracks[1].id}"))
        assertFalse(preferences.all.keys.any { "old_track" in it || "old_stage" in it })
    }

    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
