package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
import java.time.Instant

/** Cold Activity launches exercise disk-backed migration and restore, not only rememberSaveable. */
@RunWith(AndroidJUnit4::class)
class SavedReadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val tracks = PrayerTrackFactory.build(requireNotNull(AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules).getStageMatcher().getStageById("creed")), null)

    @Test
    fun legacyTrackBookmarksKeepTheirPhraseButDiscardTheOldTimerOffsets() {
        seed(line = 1, offset = 12_000L, selectedTrack = 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            resumeReading()
            selectTrack(1)
            compose.onFeedLine(tracks[1], 1).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithContentDescription("Двигать текст").assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            assertEquals(tracks[1].lines[1].id, preferences.getString("lyric_phrase_${tracks[1].id}", null))
            scenario.recreate()
            compose.onFeedLine(tracks[1], 1).assertIsSelected()
            selectTrack(0)
            selectTrack(1)
            compose.onFeedLine(tracks[1], 1).assertIsSelected()
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        }
    }

    @Test
    fun aLegacyFinishedTextRestoresItsLastPhrasePausedWithoutInventingNewElapsedTime() {
        seed(line = tracks[1].lines.lastIndex, offset = 8_000L, selectedTrack = 1, savedTempo = 8, globalTempo = 16)
        lateinit var saved: StoredServicePlayback
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(tracks[1], tracks[1].lines.lastIndex).assertIsSelected()
            compose.onNodeWithContentDescription("Двигать текст").assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            selectTrack(0)
            selectTrack(1)
            compose.onFeedLine(tracks[1], tracks[1].lines.lastIndex).assertIsSelected()
            saved = savedPlayback()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(tracks[1], tracks[1].lines.lastIndex).assertIsSelected()
            compose.onNodeWithContentDescription("Двигать текст").assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
            assertEquals(saved, savedPlayback())
        }
    }

    @Test
    fun anOldSavedReadingIsLabelledPausedAndANewServiceClearsItsPositions() {
        seed(line = 3, offset = 0L, selectedTrack = 1, stale = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onNodeWithTag("reader_status").assertIsDisplayed().assertTextEquals("На паузе · сохраненное чтение · примерное место")
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
            compose.onFeedLine(tracks[1], 3).assertIsSelected()
            compose.onNodeWithContentDescription("Двигать текст").assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе · сохраненное чтение · примерное место")
            pressBack()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
            compose.onNodeWithTag("start_guide").performClick()
            compose.onNodeWithTag("reader_status").assertTextEquals("До начала литургии")
            compose.onNodeWithTag("open_map").performClick()
            compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
            compose.onNodeWithTag("stage_creed").performClick()
            selectTrack(1)
            compose.onFeedLine(tracks[1], 0).assertIsSelected()
            val session = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
            assertFalse(session.autoAdvance)
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        }
    }

    @Test
    fun aPresentCorruptSnapshotCannotSilentlyReimportLegacyBookmarks() {
        seed(line = 3, offset = 2_000L, selectedTrack = 1)
        check(preferences.edit().putString(ServicePlaybackStore.PLAYBACK_KEY, "broken").commit())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
            compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            assertEquals("broken", preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
            assertEquals(tracks[1].lines[3].id, preferences.getString("lyric_phrase_${tracks[1].id}", null))
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
            compose.onNodeWithTag("start_guide").performClick()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
            assertNotNull(savedPlayback())
            assertFalse(preferences.contains("lyric_phrase_${tracks[1].id}"))
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

    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))

    private fun resumeReading() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
    }

    private fun selectTrack(index: Int) {
        compose.openReaderTracks()
        compose.onNodeWithTag("track_list").performScrollToNode(hasTestTag("track_$index"))
        compose.onNodeWithTag("track_$index").performClick()
    }
}
