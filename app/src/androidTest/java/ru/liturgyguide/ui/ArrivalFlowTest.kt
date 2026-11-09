package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
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
import java.time.ZoneId
import java.time.ZoneOffset

/** Direct entry replaces the former mandatory schedule preview. Manual confirmation remains separate. */
@RunWith(AndroidJUnit4::class)
class ArrivalFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-11-08T04:35:20Z")
    private val parish get() = container.parishes.getOrThrow().selected(null)
    private val creedTracks = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById("creed")), null)

    @Test fun watchingRecalculatesTheStageAtTheClickAndOpensTextPaused() {
        seed()
        val clock = AdjustableClock(instant)
        launch(clock)
        // The visible card still describes 07:35; no periodic UI tick has run after this clock change.
        compose.runOnIdle { clock.current = instant.plusSeconds(600) }
        watch("current")
        compose.onNodeWithTag("arrival_content").assertDoesNotExist()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Великий вход с Дарами")
        val saved = savedSession()
        assertEquals("great_entrance", saved.anchorStageId)
        assertEquals(0L, saved.elapsedInStageMillis)
        assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        assertEquals(clock.instant().toEpochMilli(), saved.anchorEpochMillis)
        assertFalse(saved.autoAdvance)
        assertTrue(saved.anchorIsApproximate)
        assertEquals("2026-11-08", savedContext().serviceDate)
        assertEquals(ReadingContextSource.PUBLISHED, savedContext().source)
        assertOldPositionsCleared()
        assertGlobals()
        compose.onFeedLine(container.recordedFeedTrack(savedPlayback()), 0).assertIsSelected()
        compose.onNodeWithContentDescription("Двигать текст").assertExists()
        compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
    }

    @Test fun anExpiredClickDoesNotReplaceOrClearTheSavedReading() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(instant)
        launch(clock)
        compose.runOnIdle { clock.current = instant.plusSeconds(24 * 3_600) }
        watch("current")
        compose.onNodeWithTag("main_content").assertExists()
        assertEquals(before, preferences.all)
        assertNull(ReadingContextStore(preferences).restore())
    }

    @Test fun aNextServiceBecomesCurrentBetweenDisplayAndClickWithoutSwitchingOccurrence() {
        seed()
        val clock = AdjustableClock(Instant.parse("2026-11-08T03:59:59Z"))
        launch(clock)
        compose.runOnIdle { clock.current = Instant.parse("2026-11-08T04:00:01Z") }
        watch("next")
        assertEquals("beginning", savedSession().anchorStageId)
        assertEquals(0L, savedSession().elapsedInStageMillis)
        assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        assertFalse(savedSession().autoAdvance)
        assertEquals(Instant.parse("2026-11-08T04:00:00Z").toEpochMilli(), savedContext().startEpochMillis)
    }

    @Test fun tomorrowsReadingKeepsItsDateAcrossCorrectionMidnightAndRestoration() {
        seed()
        val clock = AdjustableClock(Instant.parse("2026-11-08T12:00:00Z"))
        val restoration = launch(clock)
        watch("next")
        assertEquals("preparation", savedSession().anchorStageId)
        assertFalse(savedSession().autoAdvance)
        assertFalse(savedSession().anchorIsApproximate)
        compose.onNodeWithTag("reading_service_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("clarify_moment").performClick()
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_creed"))
        compose.onNodeWithTag("manual_creed").performClick()
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
        assertEquals("creed", savedSession().anchorStageId)
        assertEquals("2026-11-09", savedContext().serviceDate)
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_epistle"))
        compose.onNodeWithTag("stage_epistle").performClick()
        compose.onNodeWithTag("reading_unavailable").assertExists()
        compose.runOnIdle { clock.current = Instant.parse("2026-11-09T21:05:00Z") }
        compose.mainClock.advanceTimeBy(6_000)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reading_service_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("reading_unavailable").assertExists()
        assertEquals("2026-11-09", savedContext().serviceDate)
    }

    @Test fun explicitlyStartingAgainUsesTodayInsteadOfThePreviouslyOpenedFutureDate() {
        seed()
        launch(AdjustableClock(Instant.parse("2026-11-08T12:00:00Z")))
        watch("next")
        assertEquals("2026-11-09", savedContext().serviceDate)
        compose.onNodeWithContentDescription("К службе").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").performClick()
        assertEquals("preparation", savedSession().anchorStageId)
        assertEquals("2026-11-08", savedContext().serviceDate)
        assertEquals(ReadingContextSource.MANUAL, savedContext().source)
        assertNull(savedContext().startEpochMillis)
    }

    @Test fun aPersonalStartOpensItsOwnEstimateAndIsRecordedAsPersonal() {
        seed()
        assertTrue(UserServiceStartStore(preferences).save(UserServiceStart(parish.id, "2026-11-08", "07:10"), parish))
        launch(AdjustableClock(instant))
        watch("current")
        assertEquals("epistle", savedSession().anchorStageId) // 25m20s after the personal start; Epistle starts at minute 21.
        assertEquals(ReadingContextSource.USER, savedContext().source)
        assertEquals(Instant.parse("2026-11-08T04:10:00Z").toEpochMilli(), savedContext().startEpochMillis)
        assertFalse(savedSession().autoAdvance)
        assertGlobals()
    }

    @Test fun resumingALegacyBookmarkPinsItsOriginalAnchorDateWithoutClearingThePhrase() {
        seed()
        launch(AdjustableClock(Instant.parse("2026-11-10T12:00:00Z")))
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        // The 0.4 bookmark has no context; its saved anchor was 7 November in the parish timezone.
        assertEquals("2026-11-07", savedContext().serviceDate)
        assertEquals(ReadingContextSource.MANUAL, savedContext().source)
        compose.onFeedLine(container.recordedFeedTrack(savedPlayback()), 3).assertIsSelected()
        assertGlobals()
    }

    @Test fun restorationStraightIntoLegacyServicePinsTheAnchorDateBeforeAnyCorrection() {
        seed()
        val clock = AdjustableClock(Instant.parse("2026-11-10T12:00:00Z"))
        val restoration = launch(clock)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        // Simulate saved SERVICE state produced by the old app, which had no date metadata.
        compose.runOnIdle {
            check(preferences.edit().remove(ReadingContextStore.CONTEXT_KEY).remove(ServicePlaybackStore.PLAYBACK_KEY)
                .putString("session", Gson().toJson(ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli())))
                .commit())
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reading_service_date").assertTextEquals("Тексты на 7 ноября 2026")
        compose.onNodeWithTag("clarify_moment").performClick()
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_lords_prayer"))
        compose.onNodeWithTag("manual_lords_prayer").performClick()
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
        assertEquals("2026-11-07", savedContext().serviceDate)
        assertEquals("lords_prayer", savedSession().anchorStageId)
    }

    @Test fun foreignOrCorruptContextRestoredInServiceRequiresANewExplicitChoice() {
        seed()
        check(preferences.edit().putString("church_id", parish.id).commit())
        val restoration = launch(AdjustableClock(instant))
        watch("current")
        val wrong = ReadingContext("another-parish", "2026-11-08", "Europe/Moscow", ReadingContextSource.MANUAL)
        for (invalid in listOf(ReadingContextCodec.encode(wrong), "broken")) {
            compose.runOnIdle { check(preferences.edit().putString(ReadingContextStore.CONTEXT_KEY, invalid).commit()) }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("main_content").assertExists()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
            compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            assertEquals(invalid, preferences.getString(ReadingContextStore.CONTEXT_KEY, null))
            watch("current")
            assertEquals(parish.id, savedContext().parishId)
            assertEquals("2026-11-08", savedContext().serviceDate)
        }
    }

    @Test fun anInvalidLegacySessionCannotSupplyTheDateForAnOtherwiseValidBookmark() {
        seed()
        check(preferences.edit().putString("session", Gson().toJson(
            ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli(), elapsedInStageMillis = -1))).commit())
        launch(AdjustableClock(Instant.parse("2026-11-10T12:00:00Z")))
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithText("Символ веры · Дата не сохранена · откроется на сегодня").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").performClick()
        assertEquals("2026-11-10", savedContext().serviceDate)
        assertEquals("creed", savedSession().anchorStageId)
        assertEquals(0L, savedSession().elapsedInStageMillis)
    }

    private fun launch(clock: Clock) = StateRestorationTester(compose).also {
        it.setContent { AppNavigation(container, clock) }
    }
    private fun watch(which: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("watch_$which"))
        compose.onNodeWithTag("watch_$which").performClick()
    }
    private fun seed() {
        val track = creedTracks[1]
        check(preferences.edit().clear().putBoolean("dark", true).putInt("text_size", 0)
            .putBoolean("lyric_original", true).putInt("lyric_tempo", 8).putBoolean("lyric_intro_seen", true)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli(),
                autoAdvance = true, elapsedInStageMillis = 2_000L, anchorIsApproximate = true)))
            .putString("lyric_track_creed", track.id).putInt("lyric_line_${track.id}", 3)
            .putString("lyric_phrase_${track.id}", track.lines[3].id).putInt("lyric_saved_tempo_${track.id}", 8)
            .putLong("lyric_offset_${track.id}", 2_000L)
            .putString("lyric_track_old_stage", "old_track").putInt("lyric_line_old_track", 9)
            .putString("lyric_phrase_old_track", "old_phrase").putLong("lyric_offset_old_track", 500L)
            .putInt("lyric_saved_tempo_old_track", 16).commit())
    }
    private fun savedSession(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun savedContext(): ReadingContext = requireNotNull(ReadingContextStore(preferences).restore())
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
        assertFalse(preferences.all.keys.any { "old_track" in it || "old_stage" in it })
    }
    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
