package ru.liturgyguide.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.service.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReaderCompositionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)

    @Test fun oneReadingSurfaceKeepsSixStatesAndReachableModesWithoutDuplicateHeaders() = withCiFailureDiagnostics("composition.singleSurfaceAndContext") {
        val h = launch(reading("creed", title = "Символ веры", line = 12))
        val cases = listOf(reading("creed", title = "Символ веры", line = 12), segment("anaphora", PlaybackSegment.Wait::class.java),
            segment("preparation", PlaybackSegment.Wait::class.java), segment("epistle", PlaybackSegment.MissingReading::class.java, "2026-11-09"),
            segment("dismissal", PlaybackSegment.End::class.java))
        cases.forEach { candidate ->
            compose.runOnIdle { assertTrue(h.player.accept(candidate, false)) }
            compose.onAllNodesWithTag("reader_surface").assertCountEquals(1)
            compose.onNodeWithTag("lyrics_list").assert(hasAnyAncestor(hasTestTag("reader_surface"))).assertHeightIsAtLeast(120.dp)
            listOf("reading_service_date", "current_track_title", "approximate_notice", "saved_earlier_notice").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            compose.inspectReaderModes { _, node -> node.assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
            compose.onNodeWithTag("clarify_stage").assertIsDisplayed()
            compose.onNodeWithTag("reader_microphone").assertIsDisplayed()
            compose.selectReaderMode("meaning")
            compose.onAllNodesWithTag("reader_surface").assertCountEquals(1)
            compose.onNodeWithTag("service_content").assert(hasAnyAncestor(hasTestTag("reader_surface"))).assertHeightIsAtLeast(120.dp)
            compose.onNodeWithText("Что происходит").assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.runOnIdle { assertFalse(h.current.state.isPlaying); assertEquals(candidate.context, h.current.context) }
            compose.onNodeWithTag("return_to_text").performClick()
        }
    }

    @Test fun openingAndDismissingTheModeMenuDoesNotPauseOrMoveManualReading() = withCiFailureDiagnostics("composition.modeMenuDoesNotOwnPlayback") {
        val h = launch(reading("creed", title = "Символ веры", line = 12, offset = 1_234), width = 320, scale = 1.3f)
        compose.onNodeWithTag("play_lyrics").performClick()
        val playing = h.current
        compose.openReaderControls()
        compose.runOnIdle { h.now += 101; assertEquals(playing, h.current); assertEquals(1, h.writes); assertTrue(h.current.state.isPlaying) }
        compose.dismissReaderControls()
        compose.runOnIdle { assertEquals(playing, h.current); assertEquals(1, h.writes); assertTrue(h.current.state.isPlaying) }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertEquals(1_638L, h.current.state.elapsedQuarterMillis); assertFalse(h.current.state.isPlaying) }
        val away = h.current.plan.tracksByStage.getValue("lords_prayer").first().lines.first()
        choose(h, away)
        val paused = h.current; val writes = h.writes
        val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
        compose.openReaderControls()
        compose.readerControl("controls_original").assertIsEnabled()
        compose.dismissReaderControls()
        assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(paused, h.current); assertEquals(writes, h.writes) }
    }

    @Test fun directBookChoicesKeepTempoContextAndTheIntendedPlaceAcrossATimerBoundary() = withCiFailureDiagnostics("composition.bookChoices") {
        val initial = reading("creed", title = "Символ веры", line = 19, offset = 1_234)
        val h = launch(initial)
        val creed = requireNotNull(initial.track)
        choose(h, creed.lines[18])
        compose.runOnIdle { assertEquals(creed.lines[18].id, h.current.text?.lineId); assertEquals(0L, h.current.state.elapsedQuarterMillis); assertEquals(initial.context, h.current.context) }
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithTag("tempo_slow").performClick()
        compose.onNodeWithText("Готово").performClick()
        choose(h, h.current.plan.tracksByStage.getValue("anaphora").first().lines.first())
        compose.runOnIdle { assertEquals("anaphora", h.current.stage.id); assertEquals(PlaybackSpeed.SLOW, h.current.state.speed); assertFalse(h.current.state.isPlaying) }

        val waiting = segment("anaphora", PlaybackSegment.Wait::class.java)
        compose.runOnIdle { assertTrue(h.player.accept(waiting, false)) }
        val writes = h.writes
        compose.onNodeWithTag("play_lyrics").performClick()
        position(h, creed.lines[18])
        compose.runOnIdle { h.now += (waiting.segment as PlaybackSegment.Wait).durationBaseMillis + 123 }
        compose.onFeedLine(creed.lines[18]).performClick()
        compose.runOnIdle {
            assertEquals("creed", h.current.stage.id)
            assertEquals(creed.lines[18].id, h.current.text?.lineId)
            assertEquals(waiting.context, h.current.context)
            assertEquals(0L, h.current.state.elapsedQuarterMillis)
            assertFalse(h.current.state.isPlaying)
            assertEquals(writes + 3, h.writes)
        }
    }

    @Test fun capturedBookAndNavigationActionsCannotTouchTheReplacementOwner() = withCiFailureDiagnostics("composition.staleBookActions") {
        val h = launch(reading("creed", title = "Символ веры", line = 12))
        val oldActions = listOf("play_lyrics", "open_map", "clarify_stage", "reader_microphone", "player_text_tab")
            .map { action(compose.onNodeWithTag(it).assertIsDisplayed()) } + action(compose.onFeedLine(requireNotNull(h.current.track), 12))
        // Semantics wraps a mutable ClickableNode. Invoke the genuinely old action
        // before recomposition can update that node to the new owner's onClick.
        val (accepted, writes) = compose.runOnIdle {
            assertTrue(h.player.accept(reading("creed", title = "Символ веры", line = 12, date = "2026-11-09", offset = 2_345), true))
            assertTrue(h.player.play())
            val accepted = h.current; val writes = h.writes
            oldActions.forEach { it() }
            assertEquals(accepted, h.current); assertEquals(writes, h.writes)
            assertTrue(h.current.state.isPlaying); assertEquals(0, h.orders); assertEquals(0, h.clarifications)
            accepted to writes
        }
        compose.waitForIdle()
        compose.onFeedLine(requireNotNull(accepted.track), 12).assertIsSelected().assertIsDisplayed()
        val away = h.current.plan.tracksByStage.getValue("lords_prayer").first().lines.first()
        position(h, away)
        compose.waitForIdle()
        val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying)
            assertEquals(0, h.orders); assertEquals(0, h.clarifications)
        }
    }

    @Test fun migrationNoteRemainsInDetailsAcrossPlaybackAndMeaningUntilANewExplicitChoice() = withCiFailureDiagnostics("composition.migrationDetails") {
        val base = reading("anaphora")
        val first = base.tracks.first()
        val initial = base.copy(state = base.engine.seekLine(base.state, first.id, first.lines.last().id), migrationNote = PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE)
        val h = launch(initial)
        val note = "Тексты дополнены. Вернулись к последней сохраненной фразе"
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_migration_notice").performScrollTo().assertTextEquals(note)
        compose.closeReaderDetails()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { h.now += (initial.segment as PlaybackSegment.Text).durationBaseMillis + 123; h.player.tick(h.player.generation) }
        compose.runOnIdle { assertNotEquals(first.id, h.current.track?.id); assertEquals(initial.migrationNote, h.current.migrationNote) }
        compose.selectReaderMode("meaning")
        val paused = h.current; val writes = h.writes
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_migration_notice").performScrollTo().assertTextEquals(note)
        compose.closeReaderDetails()
        compose.onNodeWithTag("return_to_text").performClick()
        compose.runOnIdle { assertEquals(paused, h.current); assertEquals(writes, h.writes) }
        choose(h, h.current.plan.tracksByStage.getValue("lords_prayer").first().lines.first())
        compose.runOnIdle { assertNull(h.current.migrationNote) }
        val empty = segment("preparation", PlaybackSegment.Wait::class.java).copy(migrationNote = initial.migrationNote)
        compose.runOnIdle { assertTrue(h.player.accept(empty, false)) }
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_migration_notice").performScrollTo().assertTextEquals(note)
        compose.closeReaderDetails()
    }

    @Test fun runningPreparationKeepsPauseAndClarificationSeparateAndDirect() = withCiFailureDiagnostics("composition.preparationClarification") {
        val h = launch(segment("preparation", PlaybackSegment.Wait::class.java))
        compose.runOnIdle { assertTrue(h.player.play()); h.now += 101 }
        assertSeparate("Pause and microphone", fullTarget("play_lyrics"), fullTarget("reader_microphone"))
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.runOnIdle { assertEquals(1, h.clarifications); assertFalse(h.current.state.isPlaying); assertEquals(404L, h.current.state.elapsedQuarterMillis); assertEquals(2, h.writes) }
    }

    @Test fun narrowPortraitKeepsReadingAndActionsSeparateDuringManualReadingAndPlayback() = withCiFailureDiagnostics("composition.portraitBookGeometry") {
        checkGeometry(320, 640, 2f)
    }

    @Test fun landscapeKeepsReadingAndActionsSeparateDuringManualReadingAndPlayback() = withCiFailureDiagnostics("composition.landscapeBookGeometry") {
        try { orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE); checkGeometry(640, 360, 1.69f) }
        finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) }
    }

    private fun checkGeometry(width: Int, height: Int, scale: Float) {
        val h = launch(reading("lords_prayer", offset = 1_234).copy(isApproximate = true), width, height, scale)
        val context = h.current.context; val speed = h.current.state.speed
        fun check() {
            val area = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
            val primary = fullTarget("play_lyrics"); val search = fullTarget("clarify_stage"); val microphone = fullTarget("reader_microphone")
            assertSeparate("Search and microphone", search, microphone)
            assertSeparate("Primary and search", primary, search)
            assertSeparate("Primary and microphone", primary, microphone)
            assertTrue("The controls must not cover the text", area.bottom <= primary.top + .5.dp)
            compose.onNodeWithTag("reader_mode_chooser").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        check()
        val line = h.current.plan.tracksByStage.getValue("communion").first().lines.first()
        choose(h, line)
        check()
        compose.runOnIdle { assertEquals(context, h.current.context); assertEquals(speed, h.current.state.speed); assertFalse(h.current.state.isPlaying) }
        compose.onNodeWithTag("play_lyrics").performClick()
        check()
        compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += 101 }
        compose.onNodeWithTag("play_lyrics").performClick()
        check()
        compose.runOnIdle { assertFalse(h.current.state.isPlaying); assertEquals(line.id, h.current.text?.lineId); assertEquals(404L, h.current.state.elapsedQuarterMillis); assertEquals(context, h.current.context); assertEquals(speed, h.current.state.speed) }
    }

    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L; var writes = 0; var orders = 0; var clarifications = 0
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { _, _, _ -> writes++; true })
        val current get() = requireNotNull(player.reading)
    }
    private fun launch(initial: ActiveServicePlayback, width: Int = 360, height: Int = 800, scale: Float = 1f): Harness {
        val h = Harness(initial)
        compose.setContent {
            val density = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = width; screenHeightDp = height }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, scale)) {
                GuideTheme(false) { Box(Modifier.size(width.dp, height.dp)) {
                    ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = { h.orders++ },
                        onFindStage = { h.clarifications++; h.player.pause() }, onSettings = {}, dark = false, onDarkChange = {},
                        onOpenSource = {}, onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original, onOriginalChange = { h.original = it })
                } }
            }
        }
        return h
    }
    private fun plan(date: String) = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(date), container.readings?.days?.singleOrNull { it.date == date })
    private fun reading(stage: String, title: String? = null, line: Int = 0, offset: Long = 0, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date); val tracks = plan.tracksByStage.getValue(stage)
        val track = if (title == null) tracks.first() else tracks.single { it.title == title }
        val engine = ServicePlaybackEngine(plan)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", date, "Europe/Moscow", ReadingContextSource.MANUAL),
            engine.seekLine(engine.start(stage), track.id, track.lines[line].id).copy(elapsedQuarterMillis = offset))
    }
    private fun segment(stage: String, type: Class<out PlaybackSegment>, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date); val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }; check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", date, "Europe/Moscow", ReadingContextSource.MANUAL), ServicePlaybackEngine(plan).start(stage).copy(segmentIndex = index))
    }
    private fun position(h: Harness, line: PlayerLine) {
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(ContinuousServiceFeed(h.current.plan).indexOf("line:${line.id}")))
        compose.onFeedLine(line).assertIsDisplayed()
    }
    private fun choose(h: Harness, line: PlayerLine) { position(h, line); compose.onFeedLine(line).performClick(); compose.onFeedLine(line).assertIsSelected().assertIsDisplayed() }
    private fun action(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun fullTarget(tag: String): androidx.compose.ui.unit.DpRect {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val visible = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
        assertTrue("$tag must be fully visible", visible.left <= full.left + .5.dp && visible.top <= full.top + .5.dp && visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
        return full
    }
    private fun assertSeparate(label: String, a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect) { assertTrue("$label overlap: $a / $b", a.right <= b.left + .5.dp || b.right <= a.left + .5.dp || a.bottom <= b.top + .5.dp || b.bottom <= a.top + .5.dp) }
    private fun orient(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000); compose.waitForIdle()
    }
}
