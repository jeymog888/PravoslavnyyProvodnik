package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** The monotonic reading clock is independent of the parish clock and Compose's animation clock. */
@RunWith(AndroidJUnit4::class)
class UnifiedPlayerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val monotonic = AtomicLong(1_000L)
    private val wall = AdjustableClock(Instant.parse("2026-11-08T12:00:00Z"))

    @Test
    fun explicitRestartReplacesOnlyTheCorruptSnapshotEvenWhenContextAndMarkerAreIdentical() {
        val instant = Instant.parse("2026-11-08T12:00:00Z")
        val fixedClock = Clock.fixed(instant, ZoneOffset.UTC)
        val parish = container.parishes.getOrThrow().selected(null)
        val accepted = ReadingContext(parish.id, "2026-11-08", parish.timezone, ReadingContextSource.MANUAL)
        val oldMarker = ServiceSession("preparation", instant.toEpochMilli())
        val encodedContext = ReadingContextCodec.encode(accepted)
        val encodedMarker = Gson().toJson(oldMarker)
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString(ReadingContextStore.CONTEXT_KEY, encodedContext)
            .putString("session", encodedMarker).putString("stage_id", "preparation")
            .putString(ServicePlaybackStore.PLAYBACK_KEY, "broken").commit())
        compose.setContent { AppNavigation(container, fixedClock, monotonic::get) }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
        compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        assertEquals("broken", preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("start_liturgy").assertIsDisplayed().assertContentDescriptionEquals("Литургия началась")
        compose.onNodeWithTag("reader_status").assertTextEquals("До начала литургии")
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        assertEquals(encodedContext, preferences.getString(ReadingContextStore.CONTEXT_KEY, null))
        assertEquals(encodedMarker, preferences.getString("session", null))
        assertEquals(accepted, saved().context)
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.of(2026, 11, 8),
            container.readings?.days?.firstOrNull { it.date == "2026-11-08" })
        assertTrue(ServicePlaybackStore(preferences).restore(plan, accepted) is PlaybackRestoreResult.Valid)
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
    }

    @Test
    fun onePlayMovesThroughTextsAUsefulWaitAndTheNextStage() {
        launch("anaphora")
        val tracks = tracks("anaphora")
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals(tracks[0].title)
        play()
        // The first audible dialogue now precedes the preserved "Горе имеим" track.
        advanceBy(22_500) { currentText()?.trackId == tracks[1].id }
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals(tracks[1].title)
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
        advanceBy(85_000) { segment() is PlaybackSegment.Wait }
        compose.onNodeWithTag("stage_wait").assertIsDisplayed()
        compose.onNodeWithText("Слушаем службу").assertIsDisplayed()
        compose.onNodeWithText("Доступный текст закончился, но этот этап в храме может продолжаться.").assertExists()
        compose.onNodeWithTag("reader_status").assertTextEquals("Примерный переход включен")
        compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals("Пауза")
        assertEquals(tracks.last().lines.last().id, preferences.getString("lyric_phrase_${tracks.last().id}", null))
        // Added audible text reduces the remainder within the same editorial twelve-minute budget.
        advanceBy(612_500) { marker().anchorStageId == "theotokos" }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals(requireNotNull(container.getStageMatcher().getStageById("theotokos")).name)
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
        assertFalse(marker().autoAdvance)
        assertEquals(0L, saved().snapshot.elapsedQuarterMillis)
    }

    @Test
    fun missingDatedReadingsStopHugeTicksAndEachNeedsItsOwnManualContinuation() {
        val restoration = launch("epistle", date = "2026-11-09")
        play()
        advanceBy(86_400_000) { segment() is PlaybackSegment.MissingReading }
        val stopped = saved()
        assertEquals("epistle", marker().anchorStageId)
        assertEquals("2026-11-09", stopped.context.serviceDate)
        assertEquals(0L, stopped.snapshot.elapsedQuarterMillis)
        compose.onNodeWithTag("missing_reading_barrier").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        compose.onNodeWithTag("reader_status").assertTextEquals("Текст на 9 ноября 2026 пока не добавлен · пауза")
        compose.onNodeWithText("Текста чтения пока нет").assertIsDisplayed()
        compose.onAllNodesWithTag("continue_missing_reading").assertCountEquals(1)
        val continueButton = compose.onNodeWithTag("continue_missing_reading")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val buttonBounds = continueButton.getUnclippedBoundsInRoot()
        val readerBounds = compose.onNodeWithTag("lyrics_list").getBoundsInRoot()
        val visibleBounds = continueButton.getBoundsInRoot()
        assertTrue("The footer continuation must be fully visible", visibleBounds.top <= buttonBounds.top + .5.dp &&
            visibleBounds.bottom >= buttonBounds.bottom - .5.dp && visibleBounds.left <= buttonBounds.left + .5.dp &&
            visibleBounds.right >= buttonBounds.right - .5.dp)
        assertTrue("The continuation must not overlap the reading area", readerBounds.bottom <= buttonBounds.top)
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("reading_unavailable").assertExists()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.runOnIdle {
            monotonic.addAndGet(86_400_000)
            wall.current = Instant.parse("2026-11-11T12:00:00Z")
        }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(stopped, saved())
        compose.onNodeWithTag("reading_service_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("reader_status").assertTextEquals("Текст на 9 ноября 2026 пока не добавлен · пауза")
        continueMissing()
        assertEquals("gospel", marker().anchorStageId)
        assertEquals(0L, saved().snapshot.elapsedQuarterMillis)
        assertPaused()
        play()
        advanceBy(86_400_000) { segment() is PlaybackSegment.MissingReading }
        assertEquals("gospel", marker().anchorStageId)
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        compose.onNodeWithTag("reader_status").assertTextEquals("Текст на 9 ноября 2026 пока не добавлен · пауза")
        continueMissing()
        assertEquals("litanies", marker().anchorStageId)
        assertEquals("2026-11-09", saved().context.serviceDate)
        assertPaused()
    }

    @Test
    fun changingSpeedCapturesTheOldMultiplierWithoutMovingToAnotherPhrase() {
        launch("creed", trackIndex = 1, lineIndex = 12)
        play()
        compose.runOnIdle { monotonic.addAndGet(1_000) }
        compose.onNodeWithTag("scroll_options").performClick()
        compose.onNodeWithTag("tempo_slow").performClick().assertIsSelected()
        assertEquals(4_000L, saved().snapshot.elapsedQuarterMillis)
        compose.runOnIdle { monotonic.addAndGet(1_001) }
        compose.onNodeWithTag("tempo_fast").performClick().assertIsSelected()
        assertEquals(7_003L, saved().snapshot.elapsedQuarterMillis)
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { monotonic.addAndGet(1) }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(7_008L, saved().snapshot.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.FAST, saved().snapshot.speed)
        compose.onNodeWithTag("scroll_options").assertTextContains("1,25×")
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertPaused()
    }

    @Test
    fun losingResumedStatePausesAndDoesNotConsumeTimeBeforeReturning() {
        launch("creed", trackIndex = 1, lineIndex = 12)
        play()
        compose.runOnIdle { monotonic.addAndGet(1_234) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        val stopped = saved()
        assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
        monotonic.addAndGet(86_400_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertPaused()
        assertEquals(stopped, saved())
        // An explicit new Play starts from the pause, not from the pre-background monotonic anchor.
        play()
        compose.runOnIdle { monotonic.incrementAndGet() }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(4_940L, saved().snapshot.elapsedQuarterMillis)
    }

    @Test
    fun losingTopResumedPausesSynchronouslyEvenWhenLifecycleStaysResumed() {
        val activityForeground = ActivityForegroundState()
        compose.runOnUiThread { activityForeground.onTopResumedActivityChanged(true) }
        launch("creed", trackIndex = 1, lineIndex = 12, activityForeground = activityForeground)
        play()
        lateinit var stopped: StoredServicePlayback
        compose.runOnUiThread {
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            monotonic.addAndGet(1_234)
            activityForeground.onTopResumedActivityChanged(false)
            stopped = saved()
            assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
            // No recomposition can observe the loss before this same callback regains the top position.
            monotonic.addAndGet(86_400_000)
            activityForeground.onTopResumedActivityChanged(true)
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            assertEquals(stopped, saved())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertPaused()
        assertEquals(stopped, saved())
        play()
        compose.runOnUiThread { monotonic.incrementAndGet() }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(4_940L, saved().snapshot.elapsedQuarterMillis)
        assertPaused()
    }

    @Test
    fun languageAndProgrammaticScrollKeepPlayingButMeaningAndUserDragPause() {
        launch("creed", trackIndex = 1)
        compose.onNodeWithTag("player_original_tab").performClick().assertIsSelected()
        play()
        val track = tracks("creed")[1]
        advanceBy(4_000) { currentText()?.lineId == track.lines[1].id }
        compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
        compose.onNodeWithTag("player_text_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("lyric_line_1").assertTextEquals(track.lines[1].russian).assertIsSelected()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Пояснение · на паузе")
        compose.onNodeWithTag("return_to_text").assertIsDisplayed().assertContentDescriptionEquals("К тексту")
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        val stopped = saved()
        compose.runOnIdle { monotonic.addAndGet(600_000) }
        compose.onNodeWithTag("player_original_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("lyric_line_1").assertTextEquals(requireNotNull(track.lines[1].original)).assertIsSelected()
        assertPaused()
        assertEquals(stopped, saved())
        play()
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Двигать текст").fetchSemanticsNodes().size == 1
        }
        assertEquals(track.lines[1].id, preferences.getString("lyric_phrase_${track.id}", null))
        assertPaused()
    }

    @Test
    fun shortenedDuplicateTitleKeepsBothTextsDiscoverableAndRestoresTheSelectedPhrase() {
        launch("creed", trackIndex = 1, lineIndex = 12)
        val before = saved()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals("Полный текст · 2 из 2")
        compose.onNodeWithTag("choose_track").assertContentDescriptionEquals("Выбрать текст: Символ веры")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 из 2"))
            .performClick()
        compose.onNodeWithTag("track_1").assertIsSelected().assertTextContains("Символ веры")
        compose.onNodeWithTag("track_0").assertIsNotSelected().assertTextContains("Возлюбим друг друга")
        assertEquals(before, saved())
        compose.onNodeWithTag("track_0").performClick()
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals("Возлюбим друг друга")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("choose_track").assertContentDescriptionEquals("Выбрать текст: Возлюбим друг друга")
            .performClick()
        compose.onNodeWithTag("track_0").assertIsSelected()
        compose.onNodeWithTag("track_1").assertIsNotSelected().performClick()
        compose.onNodeWithTag("lyric_line_12").assertIsSelected().assertIsDisplayed()
        assertEquals(before, saved())
        assertPaused()
    }

    @Test
    fun scriptureCannotMasqueradeAsOriginalAndThePreferredLanguageReturnsForPrayer() {
        launch("gospel")
        compose.onNodeWithTag("player_original_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNodeWithTag("track_1").performClick()
        compose.onNodeWithTag("player_original_tab").assertIsNotEnabled()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("original_unavailable").assertExists()
        assertTrue(preferences.getBoolean("lyric_original", false))
        compose.onNodeWithTag("lyric_line_0").assertTextEquals(tracks("gospel")[1].lines[0].russian).assertIsSelected()
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNodeWithTag("track_0").performClick()
        compose.onNodeWithTag("player_original_tab").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("lyric_line_0").assertTextEquals(requireNotNull(tracks("gospel")[0].lines[0].original))
        assertPaused()
    }

    @Test
    fun oldRunningSessionAndWallClockChangesCannotMoveTheUnifiedReader() {
        launch("creed", trackIndex = 1, legacyAutoAdvance = true)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
        assertFalse(marker().autoAdvance)
        play()
        val before = saved()
        compose.runOnIdle { wall.current = wall.current.plusSeconds(8 * 3_600) }
        // Language changes force recomposition while the monotonic reading clock is fixed.
        repeat(3) {
            compose.onNodeWithTag("player_original_tab").performClick()
            compose.onNodeWithTag("player_text_tab").performClick()
        }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
        assertEquals(before, saved())
        advanceBy(4_000) { currentText()?.lineId == tracks("creed")[1].lines[1].id }
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        assertFalse(marker().autoAdvance)
    }

    @Test
    fun endStopsThePlayerAndFinishingClearsTheAcceptedReading() {
        launch("dismissal")
        play()
        advanceBy(60_000) { segment() is PlaybackSegment.Wait }
        compose.onNodeWithTag("stage_wait").assertIsDisplayed()
        compose.onNodeWithText("Доступный текст закончился. Продолжайте слушать заключительные молитвы в храме.")
            .assertIsDisplayed()
        compose.onNodeWithTag("next_stage_cue").assertDoesNotExist()
        compose.onNodeWithTag("reader_status").assertTextEquals("Примерный переход включен")
        advanceBy(86_400_000) { segment() is PlaybackSegment.End }
        compose.onNodeWithTag("playback_end").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        compose.onNodeWithTag("reader_status").assertTextEquals("Порядок в приложении завершен")
        compose.onAllNodesWithTag("finish_reading").assertCountEquals(1)
        val end = saved()
        compose.runOnIdle { monotonic.addAndGet(86_400_000) }
        assertEquals(end, saved())
        compose.onNodeWithTag("finish_reading").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("main_content").assertExists()
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        assertNull(preferences.getString("session", null))
        assertFalse(preferences.all.keys.any(ReadingAcceptanceStore::isBookmarkKey))
    }

    private fun launch(stageId: String, date: String = "2026-11-08", trackIndex: Int = 0, lineIndex: Int = 0,
        legacyAutoAdvance: Boolean = false, activityForeground: ActivityForegroundState? = null): StateRestorationTester {
        wall.current = Instant.parse("${date}T12:00:00Z")
        val track = tracks(stageId, date)[trackIndex]
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString("stage_id", stageId)
            .putString("session", Gson().toJson(ServiceSession(stageId, wall.instant().toEpochMilli(), autoAdvance = legacyAutoAdvance)))
            .putString("lyric_track_$stageId", track.id)
            .putString("lyric_phrase_${track.id}", track.lines[lineIndex].id)
            .putInt("lyric_line_${track.id}", lineIndex).commit())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppNavigation(container, wall, monotonic::get, activityForeground) }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        assertPaused()
        return restoration
    }

    private fun play() {
        compose.onNodeWithTag("play_lyrics").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
    }
    private fun assertPaused() {
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertContentDescriptionEquals("Двигать текст")
        compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
    }
    private fun continueMissing() {
        compose.onNodeWithTag("continue_missing_reading").assertIsDisplayed()
            .assertContentDescriptionEquals("Чтение в храме закончилось").performClick()
    }
    private fun advanceBy(delta: Long, reached: () -> Boolean) {
        compose.runOnIdle { monotonic.addAndGet(delta) }
        compose.waitUntil(5_000) { runCatching(reached).getOrDefault(false) }
        compose.waitForIdle()
    }
    private fun saved(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun marker(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun segment(): PlaybackSegment {
        val value = saved()
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(value.context.serviceDate),
            container.readings?.days?.firstOrNull { it.date == value.context.serviceDate })
        return plan.segments[value.snapshot.segmentIndex]
    }
    private fun currentText() = segment() as? PlaybackSegment.Text
    private fun tracks(stageId: String, date: String = "2026-11-08") = PrayerTrackFactory.build(
        requireNotNull(container.getStageMatcher().getStageById(stageId)), container.readings?.days?.firstOrNull { it.date == date })
    private class AdjustableClock(@Volatile var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
