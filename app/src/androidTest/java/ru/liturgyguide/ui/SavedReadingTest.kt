package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
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
import java.time.Instant

/** Launch after seeding preferences, so restore checks exercise a real cold activity. */
@RunWith(AndroidJUnit4::class)
class SavedReadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val tracks = PrayerTrackFactory.build(requireNotNull(AppContainer(context).getStageMatcher().getStageById("creed")), null)

    @Test
    fun changingGlobalTempoDoesNotAdvanceARestoredPhraseWhenChangingTexts() {
        seed(line = 1, offset = 12_000L, selectedTrack = 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            resumeReading()
            selectTrack(1)
            compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
            compose.onNodeWithContentDescription("Начать автопрокрутку").assertIsDisplayed()
            assertEquals(6_000L, preferences.getLong("lyric_offset_${tracks[1].id}", -1L))
            assertEquals(8, preferences.getInt("lyric_saved_tempo_${tracks[1].id}", -1))
            scenario.recreate()
            compose.onNodeWithTag("lyric_line_1").assertIsSelected()
            selectTrack(0)
            selectTrack(1)
            compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        }
    }

    @Test
    fun aFinishedSavedTextStaysFinishedAfterTempoChangeAndColdLaunch() {
        seed(line = tracks[1].lines.lastIndex, offset = 8_000L, selectedTrack = 1, savedTempo = 8, globalTempo = 16)
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onNodeWithTag("lyric_line_${tracks[1].lines.lastIndex}").assertIsSelected()
            compose.onNodeWithContentDescription("Повторить текст сначала").assertIsDisplayed()
            selectTrack(0)
            selectTrack(1)
            compose.onNodeWithContentDescription("Повторить текст сначала").assertIsDisplayed()
            assertEquals(16_000L, preferences.getLong("lyric_offset_${tracks[1].id}", -1L))
        }
    }

    @Test
    fun anOldSavedReadingIsLabelledPausedAndANewServiceClearsItsPositions() {
        seed(line = 3, offset = 0L, selectedTrack = 1, stale = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
            compose.onNodeWithTag("resume_guide").assertIsDisplayed().performClick()
            compose.onNodeWithTag("saved_earlier_notice").assertIsDisplayed()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
            compose.onNodeWithTag("lyric_line_3").assertIsSelected()
            compose.onNodeWithContentDescription("Начать автопрокрутку").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
            compose.onNodeWithTag("start_guide").performClick()
            compose.onNodeWithTag("saved_earlier_notice").assertDoesNotExist()
            compose.onNodeWithTag("open_map").performClick()
            compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
            compose.onNodeWithTag("stage_creed").performClick()
            selectTrack(1)
            compose.onNodeWithTag("lyric_line_0").assertIsSelected()
            val session = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
            assertFalse(session.autoAdvance)
        }
    }

    private fun seed(line: Int, offset: Long, selectedTrack: Int, stale: Boolean = false, savedTempo: Int = 16, globalTempo: Int = 8) {
        val anchor = Instant.now().minusSeconds(if (stale) 8 * 3600L else 0L)
        val track = tracks[1]
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", anchor.toEpochMilli(), autoAdvance = stale)))
            .putString("lyric_track_creed", tracks[selectedTrack].id)
            .putInt("lyric_tempo", globalTempo).putInt("lyric_saved_tempo_${track.id}", savedTempo)
            .putInt("lyric_line_${track.id}", line).putString("lyric_phrase_${track.id}", track.lines[line].id)
            .putLong("lyric_offset_${track.id}", offset).commit())
    }

    private fun resumeReading() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
    }

    private fun selectTrack(index: Int) {
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNodeWithTag("track_list").performScrollToNode(hasTestTag("track_$index"))
        compose.onNodeWithTag("track_$index").performClick()
    }
}
