package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.ServiceSession
import ru.liturgyguide.service.ServicePlaybackStore
import ru.liturgyguide.service.StoredServicePlayback
import ru.liturgyguide.service.StoredServicePlaybackCodec
import java.util.concurrent.atomic.AtomicLong
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ManualMomentFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-12-01T12:00:00Z") // Outside the bundled schedule.
    private val creed = tracks("creed")[1]
    private val other = tracks("communion")[1]
    private val monotonic = AtomicLong(1_000L)

    @Test
    fun allFiveLandmarksWithoutAScheduleOpenTheExpectedTextAndPhraseAfterVerification() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        val restoration = launch()
        compose.onNodeWithTag("join_service").assertDoesNotExist()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("find_stage"))
        compose.onNodeWithTag("find_stage").assertIsDisplayed()
        compose.onAllNodesWithTag("find_stage").assertCountEquals(1)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithText("Читать с начала").assertIsDisplayed()
        val cases = listOf(
            Triple("little_entrance", "Выносят Евангелие", 0),
            Triple("cherubic_hymn", "Слышно \"Иже Херувимы\"", 0),
            Triple("creed", "Слышно \"Верую во единаго Бога\"", 1),
            Triple("lords_prayer", "Слышно \"Отче наш\"", 0),
            Triple("communion", "Выносят чашу: \"Со страхом Божиим\"", 0)
        )
        cases.forEach { (stageId, label, trackIndex) ->
            check(preferences.edit().putString("lyric_phrase_old_track", "old_phrase").commit())
            val before = preferences.all.toMap()
            findFromMain()
            compose.onNodeWithTag("manual_content").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            val stage = requireNotNull(container.getStageMatcher().getStageById(stageId))
            compose.onNodeWithText("Вы выбрали ориентир").assertIsDisplayed()
            compose.onNodeWithText("Предположение по времени").assertDoesNotExist()
            compose.onNodeWithTag("arrival_stage").assertTextEquals(stage.name)
            compose.onNodeWithText(requireNotNull(stage.cue)).assertIsDisplayed()
            if (stageId == "creed") restoration.emulateSavedInstanceStateRestore()
            assertEquals(before, preferences.all)
            accept()
            val expectedTrack = tracks(stageId)[trackIndex]
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(stage.name)
            if (stageId == "creed") {
                compose.onNodeWithTag("choose_track").assertContentDescriptionEquals("Выбрать текст: Символ веры")
            } else {
                compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals(expectedTrack.title)
            }
            compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed().assertTextEquals(expectedTrack.lines[0].russian)
            assertEquals(expectedTrack.id, preferences.getString("lyric_track_$stageId", null))
            assertEquals(expectedTrack.lines[0].id, preferences.getString("lyric_phrase_${expectedTrack.id}", null))
            assertFalse(preferences.contains("lyric_phrase_old_track"))
            assertManualSession(stageId)
            compose.onNodeWithContentDescription("К службе").performClick()
            // Complete navigation before comparing the next unaccepted draft.
            compose.waitForIdle()
        }
    }

    @Test
    fun choosingAnotherLandmarkShowsItsNewCueBeforeConfirmation() {
        seed()
        compose.setContent {
            Box(Modifier.size(width = 320.dp, height = 320.dp)) {
                AppNavigation(container, Clock.fixed(instant, ZoneOffset.UTC))
            }
        }
        findFromMain()
        landmark("little_entrance")
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("arrival_search"))
        compose.onNodeWithTag("arrival_stage").assertIsNotDisplayed()
        back()
        landmark("communion")
        compose.onNodeWithTag("arrival_stage").assertIsDisplayed().assertTextEquals("Причащение верующих")
        compose.onNodeWithText("Чашу выносят со словами \"Со страхом Божиим и верою приступите\"").assertIsDisplayed()
    }

    @Test
    fun correctionCancelRestoresMeaningAndANonInitialPhraseAcrossAllThreeScreens() {
        seed()
        val restoration = launch()
        resumeMeaning()
        val before = preferences.all.toMap()
        clarify()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("manual_content").assertExists()
        landmark("communion")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Причащение верующих")
        searchFromArrival()
        typeSearch("Отче наш")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stage_search").assertTextContains("Отче наш")
        back()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Причащение верующих")
        back()
        compose.onNodeWithTag("manual_content").assertExists()
        back()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertOtherBookmark()
    }

    @Test
    fun acceptingACorrectionOpensTextAtTheLandmarkAndPreservesOtherBookmarks() {
        seed()
        launch()
        resumeMeaning()
        clarify()
        landmark("creed")
        accept()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("choose_track").assertContentDescriptionEquals("Выбрать текст: Символ веры")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        assertEquals(creed.id, preferences.getString("lyric_track_creed", null))
        assertEquals(creed.lines[0].id, preferences.getString("lyric_phrase_${creed.id}", null))
        assertEquals(0L, preferences.getLong("lyric_offset_${creed.id}", -1L))
        assertManualSession("creed")
        assertOtherBookmark()
    }

    @Test
    fun correctionSearchAcceptsTheMatchingPhraseAndPreservesOtherBookmarks() {
        seed()
        launch()
        resumeMeaning()
        clarify()
        searchFromManual()
        typeSearch("духа святого животворящаго")
        compose.onNodeWithTag("stage_creed").assertIsNotSelected().performClick()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertEquals(creed.lines[12].id, preferences.getString("lyric_phrase_${creed.id}", null))
        assertEquals(0L, preferences.getLong("lyric_offset_${creed.id}", -1L))
        assertManualSession("creed")
        assertOtherBookmark()
    }

    @Test
    fun activePlayerPausesBeforeThePhraseBoundaryAndCorrectionNeverConsumesBackgroundTime() {
        seed()
        val restoration = launch()
        resumeMeaning()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        // The canonical phrase has six words: its four-second minimum ends at 5000 ms.
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                monotonic.set(4_999L)
                click()
            }
        val paused = savedPlayback()
        assertEquals("creed", savedSession().anchorStageId)
        assertEquals(15_996L, paused.snapshot.elapsedQuarterMillis)
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithText("Автоматические переходы остановлены").assertIsDisplayed()
        compose.runOnIdle { monotonic.addAndGet(600_000L) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Автоматические переходы остановлены").assertIsDisplayed()
        landmark("communion")
        searchFromArrival()
        back()
        back()
        back()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Включить плеер").assertIsDisplayed()
        assertEquals(paused, savedPlayback())
        assertOtherBookmark()
    }

    @Test
    fun correctionStopsPhraseScrollingAndCancelKeepsThePausedPhrase() {
        seed()
        val restoration = launch()
        resumeMeaning()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithContentDescription("Остановить плеер").assertIsDisplayed()
        compose.runOnIdle { monotonic.addAndGet(1_234L) }
        clarify()
        val session = savedSession()
        val stoppedLine = preferences.getInt("lyric_line_${creed.id}", -1)
        val stopped = savedPlayback()
        assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
        restoration.emulateSavedInstanceStateRestore()
        landmark("communion")
        back()
        back()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("lyric_line_$stoppedLine").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Включить плеер").assertIsDisplayed()
        assertEquals(stopped, savedPlayback())
        assertEquals(session, savedSession())
        assertOtherBookmark()
    }

    @Test
    fun savedReadingNoticeUsesCorrectionAndKeepsTheAcceptedPausedSnapshot() {
        val old = ServiceSession("creed", instant.minusSeconds(8 * 3_600L).toEpochMilli(),
            autoAdvance = true, elapsedInStageMillis = 2_000L, anchorIsApproximate = true)
        seed(session = old)
        launch()
        resumeMeaning()
        val accepted = savedPlayback()
        val normalized = savedSession()
        assertFalse(normalized.autoAdvance)
        assertEquals("creed", normalized.anchorStageId)
        compose.onNodeWithTag("saved_earlier_notice").performClick()
        compose.onNodeWithTag("manual_content").assertExists()
        compose.onNodeWithText("Автоматические переходы остановлены").assertDoesNotExist()
        assertEquals(normalized, savedSession())
        assertEquals(accepted, savedPlayback())
        searchFromManual()
        back()
        compose.onNodeWithTag("manual_content").assertExists()
        back()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected()
        assertEquals(normalized, savedSession())
        assertEquals(accepted, savedPlayback())
        assertOtherBookmark()
    }

    @Test
    fun aFreshManualEntryResetsSearchListeningAndScrollButBackPreservesThem() {
        seed()
        val before = preferences.all.toMap()
        val restoration = launch()
        findFromMain()
        searchFromManual()
        typeSearch("верую")
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.onNodeWithTag("manual_content").assertExists()
        searchFromManual()
        compose.onNodeWithTag("stage_search").assertTextContains("верую")
        back()
        back()
        findFromMain()
        searchFromManual()
        compose.onNodeWithTag("stage_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("open_listening").performClick()
        compose.onNodeWithText("Скрыть аудиоподсказку").assertIsDisplayed()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        back()
        back()
        findFromMain()
        searchFromManual()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithText("Подсказка по звуку").assertIsDisplayed()
        compose.onNodeWithText("Скрыть аудиоподсказку").assertDoesNotExist()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_dismissal").assertIsNotDisplayed()
        assertEquals(before, preferences.all)
    }

    private fun launch(clock: Clock = Clock.fixed(instant, ZoneOffset.UTC)) = StateRestorationTester(compose).also {
        it.setContent { AppNavigation(container, clock, monotonic::get) }
    }

    private fun seed(session: ServiceSession = ServiceSession("creed", instant.toEpochMilli())) {
        check(preferences.edit().clear().putBoolean("dark", false).putInt("text_size", 0)
            .putBoolean("lyric_original", false).putInt("lyric_tempo", 8).putBoolean("lyric_intro_seen", true)
            .putString("stage_id", "creed").putString("session", Gson().toJson(session))
            .putString("lyric_track_creed", creed.id)
            .putInt("lyric_line_${creed.id}", 12).putString("lyric_phrase_${creed.id}", creed.lines[12].id)
            .putInt("lyric_saved_tempo_${creed.id}", 8).putLong("lyric_offset_${creed.id}", 2_000L)
            .putString("lyric_track_communion", other.id)
            .putInt("lyric_line_${other.id}", 4).putString("lyric_phrase_${other.id}", other.lines[4].id)
            .putInt("lyric_saved_tempo_${other.id}", 16).putLong("lyric_offset_${other.id}", 1_234L).commit())
    }

    private fun resumeMeaning() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected()
        compose.onNodeWithTag("player_meaning_tab").performClick()
    }

    private fun findFromMain() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("find_stage"))
        compose.onNodeWithTag("find_stage").performClick()
    }

    private fun clarify() { compose.onNodeWithTag("clarify_moment").assertIsDisplayed().performClick() }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick() }
    private fun landmark(id: String) {
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_$id"))
        compose.onNodeWithTag("manual_$id").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun searchFromManual() {
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_search"))
        compose.onNodeWithTag("manual_search").performClick()
    }
    private fun searchFromArrival() {
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
    private fun tracks(id: String) = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById(id)), null)
    private fun savedSession(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun assertOtherBookmark() {
        assertEquals(other.id, preferences.getString("lyric_track_communion", null))
        assertEquals(4, preferences.getInt("lyric_line_${other.id}", -1))
        assertEquals(other.lines[4].id, preferences.getString("lyric_phrase_${other.id}", null))
        assertEquals(16, preferences.getInt("lyric_saved_tempo_${other.id}", -1))
        assertEquals(1_234L, preferences.getLong("lyric_offset_${other.id}", -1L))
    }
    private fun assertManualSession(stageId: String) {
        val session = savedSession()
        assertEquals(stageId, session.anchorStageId)
        assertEquals(instant.toEpochMilli(), session.anchorEpochMillis)
        assertEquals(0L, session.elapsedInStageMillis)
        assertFalse(session.autoAdvance)
        assertFalse(session.anchorIsApproximate)
        compose.onNodeWithContentDescription("Включить плеер").assertIsDisplayed()
    }
    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
