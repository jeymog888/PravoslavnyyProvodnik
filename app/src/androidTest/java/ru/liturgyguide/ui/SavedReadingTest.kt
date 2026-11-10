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

    private val creed get() = tracks.single { it.title == "Символ веры" }

    @Test
    fun legacyTrackBookmarksKeepTheirPhraseButDiscardTheOldTimerOffsets() {
        seed(line = 1, offset = 12_000L, selectedTitle = "Символ веры")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            resumeReading()
            compose.onFeedLine(creed, 1).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            assertEquals(creed.lines[1].id, preferences.getString("lyric_phrase_${creed.id}", null))
            chooseCreedLine(2)
            scenario.recreate()
            compose.onFeedLine(creed, 2).assertIsSelected().assertIsDisplayed()
            assertEquals(creed.lines[2].id, preferences.getString("lyric_phrase_${creed.id}", null))
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        }
    }

    @Test
    fun aLegacyFinishedTextRestoresItsLastPhrasePausedWithoutInventingNewElapsedTime() {
        seed(line = creed.lines.lastIndex, offset = 8_000L, selectedTitle = "Символ веры", savedTempo = 8, globalTempo = 16)
        lateinit var saved: StoredServicePlayback
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            saved = savedPlayback()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(saved, savedPlayback())
        }
    }

    @Test
    fun anOldSavedReadingIsLabelledPausedAndReadingOnAnotherDateStartsFresh() {
        seed(line = 3, offset = 0L, selectedTitle = "Символ веры", stale = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            assertSavedReadingNotice()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
            compose.onFeedLine(creed, 3).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
            compose.onNodeWithTag("start_guide").performClick()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
            compose.onNodeWithTag("open_map").performClick()
            compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
            compose.onNodeWithTag("stage_creed").performClick()
            chooseCreedLine(0)
            compose.onFeedLine(creed, 0).assertIsSelected()
            val session = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
            assertFalse(session.autoAdvance)
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        }
    }

    @Test
    fun aPresentCorruptSnapshotCannotSilentlyReimportLegacyBookmarks() {
        seed(line = 3, offset = 2_000L, selectedTitle = "Символ веры")
        check(preferences.edit().putString(ServicePlaybackStore.PLAYBACK_KEY, "broken").commit())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
            compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            assertEquals("broken", preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
            assertEquals(creed.lines[3].id, preferences.getString("lyric_phrase_${creed.id}", null))
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
            compose.onNodeWithTag("start_guide").performClick()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
            assertNotNull(savedPlayback())
            assertFalse(preferences.contains("lyric_phrase_${creed.id}"))
        }
    }

    private fun seed(line: Int, offset: Long, selectedTitle: String, stale: Boolean = false, savedTempo: Int = 16, globalTempo: Int = 8) {
        val anchor = Instant.now().minusSeconds(if (stale) 48 * 3600L else 0L)
        val track = creed
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", anchor.toEpochMilli(), autoAdvance = stale)))
            .putString("lyric_track_creed", tracks.single { it.title == selectedTitle }.id)
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

    private fun chooseCreedLine(index: Int) {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(feedLineTag(creed.lines[index])))
        compose.onFeedLine(creed, index).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun assertSavedReadingNotice() {
        compose.openReaderDetails()
        compose.onNodeWithText("Сохранённое чтение. Место в храме можно уточнить по словам или микрофону")
            .performScrollTo().assertIsDisplayed()
        compose.closeReaderDetails()
    }
}
