package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
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
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** The monotonic reading clock remains independent of parish time, navigation and text layout. */
@RunWith(AndroidJUnit4::class)
class UnifiedPlayerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val monotonic = AtomicLong(1_000L)
    private val wall = AdjustableClock(Instant.parse("2026-11-08T12:00:00Z"))

    @Test fun explicitRestartReplacesOnlyTheCorruptSnapshotEvenWhenContextAndMarkerAreIdentical() {
        val instant = Instant.parse("2026-11-08T12:00:00Z")
        val parish = container.parishes.getOrThrow().selected(null)
        val accepted = ReadingContext(parish.id, "2026-11-08", parish.timezone, ReadingContextSource.MANUAL)
        val encodedContext = ReadingContextCodec.encode(accepted)
        val encodedMarker = Gson().toJson(ServiceSession("beginning", instant.toEpochMilli()))
        check(preferences.edit().clear().putBoolean("dark", false).putString(ReadingContextStore.CONTEXT_KEY, encodedContext)
            .putString("session", encodedMarker).putString("stage_id", "beginning").putString(ServicePlaybackStore.PLAYBACK_KEY, "broken").commit())
        compose.setContent { AppNavigation(container, Clock.fixed(instant, ZoneOffset.UTC), monotonic::get) }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
        compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        assertEquals("broken", preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        compose.onFeedLine(plan().tracksByStage.getValue("beginning").first(), 0).assertIsDisplayed().assertIsSelected()
        assertPaused()
        assertEquals(encodedContext, preferences.getString(ReadingContextStore.CONTEXT_KEY, null))
        assertEquals(encodedMarker, preferences.getString("session", null))
        assertEquals(accepted, saved().context)
        assertTrue(ServicePlaybackStore(preferences).restore(plan(), accepted) is PlaybackRestoreResult.Valid)
        compose.mainClock.advanceTimeBy(6_000)
        assertEquals(accepted, saved().context)
        compose.onNodeWithTag("main_content").assertDoesNotExist()
    }

    @Test fun onePlayMovesThroughTextsAUsefulWaitAndTheNextStage() {
        launch("anaphora")
        val tracks = tracks("anaphora")
        compose.onFeedLine(tracks.first(), 0).assertIsSelected().assertIsDisplayed()
        play()
        val nextTrack = tracks[1]
        advanceTo { it is PlaybackSegment.Text && it.trackId == nextTrack.id }
        assertEquals(nextTrack.id, currentText()?.trackId)
        compose.onFeedLine(nextTrack, 0).assertIsSelected().assertIsDisplayed()
        assertAction("play_lyrics", "Пауза")
        advanceTo { it is PlaybackSegment.Wait && it.stageId == "anaphora" }
        compose.onNodeWithTag("stage_wait").assertIsDisplayed()
        compose.onNodeWithText("Слушаем службу").assertIsDisplayed()
        assertAction("play_lyrics", "Пауза")
        assertEquals(tracks.last().lines.last().id, preferences.getString("lyric_phrase_${tracks.last().id}", null))
        advanceTo { it is PlaybackSegment.Text && it.stageId == "theotokos" }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(requireNotNull(container.getStageMatcher().getStageById("theotokos")).name)
        compose.onFeedLine(container.recordedFeedTrack(saved()), 0).assertIsSelected().assertIsDisplayed()
        assertAction("play_lyrics", "Пауза")
        assertFalse(marker().autoAdvance)
        assertEquals(0L, saved().snapshot.elapsedQuarterMillis)
    }

    @Test fun missingDatedReadingsStopHugeTicksAndEachNeedsItsOwnManualContinuation() {
        val restoration = launch("epistle", date = "2026-11-09")
        play()
        advanceBy(86_400_000) { segment() is PlaybackSegment.MissingReading }
        val stopped = saved()
        assertEquals("epistle", marker().anchorStageId)
        assertEquals("2026-11-09", stopped.context.serviceDate)
        assertEquals(0L, stopped.snapshot.elapsedQuarterMillis)
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(ContinuousServiceFeed(plan()).indexOf("missing:epistle")))
        compose.onAllNodesWithTag("missing_reading_barrier").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onAllNodesWithTag("continue_missing_reading").assertCountEquals(1)
        val continuation = compose.onNodeWithTag("continue_missing_reading").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val full = continuation.getUnclippedBoundsInRoot(); val visible = continuation.getBoundsInRoot()
        assertTrue(visible.top <= full.top + .5.dp && visible.bottom >= full.bottom - .5.dp && visible.left <= full.left + .5.dp && visible.right >= full.right - .5.dp)
        assertTrue(compose.onNodeWithTag("lyrics_list").getBoundsInRoot().bottom <= full.top)
        compose.selectReaderMode("meaning")
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("return_to_text").performClick()
        compose.runOnIdle { monotonic.addAndGet(86_400_000); wall.current = Instant.parse("2026-11-11T12:00:00Z") }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(stopped, saved())
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.closeReaderDetails()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        continueMissing()
        assertEquals("gospel", marker().anchorStageId)
        assertEquals(0L, saved().snapshot.elapsedQuarterMillis)
        assertPaused()
        play()
        advanceBy(86_400_000) { segment() is PlaybackSegment.MissingReading }
        assertEquals("gospel", marker().anchorStageId)
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        continueMissing()
        assertEquals("litanies", marker().anchorStageId)
        assertEquals("2026-11-09", saved().context.serviceDate)
        assertPaused()
    }

    @Test fun changingSpeedCapturesTheOldMultiplierWithoutMovingToAnotherPhrase() {
        launch("creed", trackTitle = "Символ веры", lineIndex = 12)
        play()
        compose.runOnIdle { monotonic.addAndGet(1_000) }
        compose.openReaderControl("scroll_options").performClick()
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
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithTag("tempo_fast").assertIsSelected()
        compose.onNodeWithText("Готово").performClick()
        compose.onFeedLine(container.recordedFeedTrack(saved()), 12).assertIsSelected().assertIsDisplayed()
        assertPaused()
    }

    @Test fun losingResumedStatePausesAndDoesNotConsumeTimeBeforeReturning() {
        launch("creed", trackTitle = "Символ веры", lineIndex = 12)
        play()
        compose.runOnIdle { monotonic.addAndGet(1_234) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        val stopped = saved()
        assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
        monotonic.addAndGet(86_400_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onFeedLine(container.recordedFeedTrack(saved()), 12).assertIsSelected().assertIsDisplayed()
        assertPaused()
        assertEquals(stopped, saved())
        play()
        compose.runOnIdle { monotonic.incrementAndGet() }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(4_940L, saved().snapshot.elapsedQuarterMillis)
    }

    @Test fun losingTopResumedPausesSynchronouslyEvenWhenLifecycleStaysResumed() {
        val foreground = ActivityForegroundState()
        compose.runOnUiThread { foreground.onTopResumedActivityChanged(true) }
        launch("creed", trackTitle = "Символ веры", lineIndex = 12, activityForeground = foreground)
        play()
        lateinit var stopped: StoredServicePlayback
        compose.runOnUiThread {
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            monotonic.addAndGet(1_234); foreground.onTopResumedActivityChanged(false)
            stopped = saved()
            assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
            monotonic.addAndGet(86_400_000); foreground.onTopResumedActivityChanged(true)
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            assertEquals(stopped, saved())
        }
        compose.waitForIdle()
        compose.onFeedLine(container.recordedFeedTrack(saved()), 12).assertIsSelected().assertIsDisplayed()
        assertPaused(); assertEquals(stopped, saved())
        play()
        compose.runOnUiThread { monotonic.incrementAndGet() }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(4_940L, saved().snapshot.elapsedQuarterMillis)
        assertPaused()
    }

    @Test fun languageMeaningAndManualReadingPauseWithoutLosingTheAcceptedDate() {
        launch("creed", trackTitle = "Символ веры")
        compose.selectReaderMode("original")
        val track = tracks("creed").single { it.title == "Символ веры" }
        play()
        advanceTo { it is PlaybackSegment.Text && it.lineId == track.lines[1].id }
        compose.onFeedLine(track, 1).assertIsSelected().assertIsDisplayed()
        compose.selectReaderMode("text")
        compose.onFeedLine(track, 1).assertTextEquals(track.lines[1].russian).assertIsSelected()
        assertPaused()
        compose.selectReaderMode("meaning")
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        val stopped = saved()
        compose.runOnIdle { monotonic.addAndGet(600_000) }
        compose.selectReaderMode("original")
        compose.onFeedLine(track, 1).assertTextEquals(requireNotNull(track.lines[1].original)).assertIsSelected()
        assertPaused(); assertEquals(stopped, saved())
        play()
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .60f) }
        assertPaused()
        val browsed = saved()
        assertEquals(stopped.context, browsed.context)
        assertEquals(stopped.snapshot.speed, browsed.snapshot.speed)
        compose.runOnIdle { monotonic.addAndGet(600_000) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(browsed, saved())
    }

    @Test fun bothTextsRemainReachableInTheBookAndExplicitLineChoiceRestoresTheRequestedPhrase() {
        launch("creed", trackTitle = "Символ веры", lineIndex = 12)
        val before = saved()
        val creed = tracks("creed").single { it.title == "Символ веры" }
        val other = tracks("creed").single { it.title == "Возлюбим друг друга" }
        chooseLine(other.lines.first())
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_track").assertTextEquals(other.title)
        compose.closeReaderDetails()
        compose.onFeedLine(other, 0).assertIsSelected().assertIsDisplayed()
        assertEquals(before.context, saved().context)
        chooseLine(creed.lines[12])
        compose.onFeedLine(creed, 12).assertIsSelected().assertIsDisplayed()
        assertEquals(before, saved())
        assertPaused()
    }

    @Test fun scriptureCannotMasqueradeAsOriginalAndThePreferredLanguageReturnsForPrayer() {
        launch("gospel")
        compose.selectReaderMode("original")
        val scripture = tracks("gospel").first { it.isScripture }
        val prayer = tracks("gospel").first { !it.isScripture }
        chooseLine(scripture.lines.first())
        compose.assertReaderMode("original", enabled = false)
        compose.assertReaderMode("text", selected = true)
        compose.onNodeWithTag("scripture_language_notice").assertIsDisplayed()
        assertTrue(preferences.getBoolean("lyric_original", false))
        compose.onFeedLine(scripture, 0).assertTextEquals(scripture.lines[0].russian).assertIsSelected()
        chooseLine(prayer.lines.first())
        compose.assertReaderMode("original", selected = true, enabled = true)
        compose.onFeedLine(prayer, 0).assertTextEquals(requireNotNull(prayer.lines[0].original))
        compose.onNodeWithTag("scripture_language_notice").assertDoesNotExist()
        assertPaused()
    }

    @Test fun oldRunningSessionAndWallClockChangesCannotMoveTheUnifiedReader() {
        launch("creed", trackTitle = "Символ веры", legacyAutoAdvance = true)
        val creed = tracks("creed").single { it.title == "Символ веры" }
        compose.onFeedLine(creed, 0).assertIsSelected()
        assertFalse(marker().autoAdvance)
        play()
        val before = saved()
        compose.runOnIdle { wall.current = wall.current.plusSeconds(8 * 3_600) }
        repeat(3) { index ->
            compose.onNodeWithTag("player_menu").performClick()
            compose.onNodeWithText(if (index % 2 == 0) "Темный экран" else "Светлый экран").performClick()
        }
        compose.onFeedLine(creed, 0).assertIsSelected()
        assertAction("play_lyrics", "Пауза")
        assertEquals(before, saved())
        advanceTo { it is PlaybackSegment.Text && it.lineId == creed.lines[1].id }
        compose.onFeedLine(creed, 1).assertIsSelected()
        assertFalse(marker().autoAdvance)
    }

    @Test fun endStopsThePlayerAndFinishingClearsTheAcceptedReading() {
        launch("dismissal")
        play()
        advanceTo { it is PlaybackSegment.Wait && it.stageId == "dismissal" }
        compose.onNodeWithTag("stage_wait").assertIsDisplayed()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        advanceBy(86_400_000) { segment() is PlaybackSegment.End }
        compose.onNodeWithTag("book_end").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
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

    private fun launch(stageId: String, date: String = "2026-11-08", trackTitle: String? = null, lineIndex: Int = 0,
        legacyAutoAdvance: Boolean = false, activityForeground: ActivityForegroundState? = null): StateRestorationTester {
        wall.current = Instant.parse("${date}T12:00:00Z")
        val track = if (trackTitle == null) tracks(stageId, date).first() else tracks(stageId, date).single { it.title == trackTitle }
        check(preferences.edit().clear().putBoolean("dark", false).putString("stage_id", stageId)
            .putString("session", Gson().toJson(ServiceSession(stageId, wall.instant().toEpochMilli(), autoAdvance = legacyAutoAdvance)))
            .putString("lyric_track_$stageId", track.id).putString("lyric_phrase_${track.id}", track.lines[lineIndex].id)
            .putInt("lyric_line_${track.id}", lineIndex).commit())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppNavigation(container, wall, monotonic::get, activityForeground) }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        assertPaused()
        return restoration
    }
    private fun assertAction(tag: String, label: String) = compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasText(label) or hasContentDescription(label))
    private fun play() { compose.onNodeWithTag("play_lyrics").assertIsEnabled().performClick(); assertAction("play_lyrics", "Пауза") }
    private fun assertPaused() { assertAction("play_lyrics", "Автопрокрутка") }
    private fun continueMissing() { assertAction("continue_missing_reading", "Чтение закончилось").performClick() }
    private fun chooseLine(line: PlayerLine) {
        val feed = ContinuousServiceFeed(plan())
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(feed.indexOf("line:${line.id}")))
        compose.onFeedLine(line).assertIsDisplayed().performClick()
    }
    private fun advanceTo(predicate: (PlaybackSegment) -> Boolean) {
        val value = saved(); val plan = plan()
        assertEquals(PlaybackSpeed.NORMAL, value.snapshot.speed)
        val target = ((value.snapshot.segmentIndex + 1)..plan.segments.lastIndex).first { predicate(plan.segments[it]) }
        val millis = plan.segments.subList(value.snapshot.segmentIndex, target).sumOf {
            when (it) { is PlaybackSegment.Text -> it.durationBaseMillis; is PlaybackSegment.Wait -> it.durationBaseMillis; else -> error("An automatic jump must not cross a barrier") }
        } - value.snapshot.elapsedQuarterMillis / 4
        advanceBy(millis) { saved().snapshot.segmentIndex == target }
    }
    private fun advanceBy(delta: Long, reached: () -> Boolean) {
        compose.runOnIdle { monotonic.addAndGet(delta) }
        compose.waitUntil(5_000) { runCatching(reached).getOrDefault(false) }
        compose.waitForIdle()
    }
    private fun saved(): StoredServicePlayback = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun marker(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun plan(): ServicePlaybackPlan { val value = saved(); return ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(value.context.serviceDate), container.readings?.days?.firstOrNull { it.date == value.context.serviceDate }) }
    private fun segment(): PlaybackSegment = plan().segments[saved().snapshot.segmentIndex]
    private fun currentText() = segment() as? PlaybackSegment.Text
    private fun tracks(stageId: String, date: String = "2026-11-08") = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById(stageId)), container.readings?.days?.firstOrNull { it.date == date })
    private class AdjustableClock(@Volatile var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
