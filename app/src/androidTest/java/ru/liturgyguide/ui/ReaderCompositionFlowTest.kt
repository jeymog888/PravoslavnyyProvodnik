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
import ru.liturgyguide.service.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReaderCompositionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun oneReadingSurfaceKeepsSixStatesAndReachableModesWithoutDuplicateHeaders() =
        withCiFailureDiagnostics("composition.singleSurfaceAndContext") {
            val h = launch(reading("creed", track = 1, line = 12))
            val cases = listOf(
                Triple(reading("creed", track = 1, line = 12), "На паузе", "Пауза"),
                Triple(segment("anaphora", PlaybackSegment.Wait::class.java), "Примерный переход остановлен", "Пауза · примерно"),
                Triple(segment("preparation", PlaybackSegment.Wait::class.java), "До начала литургии", "До начала"),
                Triple(segment("epistle", PlaybackSegment.MissingReading::class.java, "2026-11-09"), "Текст на 9 ноября 2026 пока не добавлен · пауза", "Нет текста"),
                Triple(segment("dismissal", PlaybackSegment.End::class.java), "Порядок в приложении завершен", "Конец порядка"))
            for ((candidate, expectedStatus, compactStatus) in cases) {
                compose.runOnIdle { assertTrue(h.player.accept(candidate, false)) }
                compose.onAllNodesWithTag("reader_surface").assertCountEquals(1)
                compose.onNodeWithTag("lyrics_list").assert(hasAnyAncestor(hasTestTag("reader_surface"))).assertHeightIsAtLeast(120.dp)
                listOf("reading_service_date", "current_track_title", "approximate_notice", "saved_earlier_notice")
                    .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
                if (compose.readerHasModeChooser()) {
                    compose.onNodeWithTag("reader_selected_mode", useUnmergedTree = true).assertIsDisplayed()
                }
                compose.inspectReaderModes { _, node -> node.assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
                status(expectedStatus, compactStatus)
                compose.onNodeWithTag("reader_controls").assertIsDisplayed()
                compose.selectReaderMode("meaning")
                compose.onAllNodesWithTag("reader_surface").assertCountEquals(1)
                compose.onNodeWithTag("service_content").assert(hasAnyAncestor(hasTestTag("reader_surface"))).assertHeightIsAtLeast(120.dp)
                compose.onNodeWithText("Что происходит").assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                status("Пояснение · на паузе", "Пояснение")
                compose.onNodeWithTag("return_to_text").performClick()
            }
        }

    @Test fun controlsOpenAndDismissNeitherPauseLivePlaybackNorResetManualBrowsing() =
        withCiFailureDiagnostics("composition.controlsDoNotOwnPlayback") {
            val h = launch(reading("creed", track = 1, line = 12, offset = 1_234))
            compose.onNodeWithTag("play_lyrics").performClick()
            val playing = h.current
            compose.openReaderControls()
            compose.runOnIdle { h.now += 101; assertEquals(playing, h.current); assertEquals(1, h.writes); assertTrue(h.current.state.isPlaying) }
            compose.dismissReaderControls()
            compose.runOnIdle { assertEquals(playing, h.current); assertEquals(1, h.writes); assertTrue(h.current.state.isPlaying) }
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle { assertEquals(1_638L, h.current.state.elapsedQuarterMillis); assertFalse(h.current.state.isPlaying) }
            val away = h.current.tracks[0].lines[0]
            feed(feedLineTag(away))
            compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown(startY = height * .45f, endY = height * .55f) }
            compose.onNodeWithTag("return_to_phrase").assertIsDisplayed()
            val paused = h.current
            val writes = h.writes
            val bounds = compose.onFeedLine(away).assertIsDisplayed().getUnclippedBoundsInRoot()
            compose.openReaderControls()
            compose.readerControl("controls_order").assertIsEnabled()
            compose.dismissReaderControls()
            compose.onNodeWithTag("return_to_phrase").assertIsDisplayed()
            assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
            compose.runOnIdle { assertEquals(paused, h.current); assertEquals(writes, h.writes) }
        }

    @Test fun controlsKeepPhraseBoundariesTempoAndTheIntendedPreviousStageAtAPauseBoundary() =
        withCiFailureDiagnostics("composition.controlActions") {
            val initial = reading("creed", track = 1, line = 19, offset = 1_234)
            val h = launch(initial)
            compose.openReaderControls()
            compose.readerControl("next_line").assertIsNotEnabled()
            compose.readerControl("previous_line").assertIsEnabled().performClick()
            compose.onFeedLine(initial.tracks[1], 18).assertIsSelected().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0L, h.current.state.elapsedQuarterMillis); assertEquals(initial.context, h.current.context) }
            compose.openReaderControl("scroll_options").performClick()
            compose.onNodeWithTag("tempo_slow").performClick()
            compose.onNodeWithText("Готово").performClick()
            compose.openReaderControl("next_stage").performClick()
            compose.runOnIdle { assertEquals("anaphora", h.current.stage.id); assertEquals(PlaybackSpeed.SLOW, h.current.state.speed); assertFalse(h.current.state.isPlaying) }

            val waiting = segment("anaphora", PlaybackSegment.Wait::class.java)
            compose.runOnIdle { assertTrue(h.player.accept(waiting, false)) }
            val writes = h.writes
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.openReaderControls()
            compose.readerControl("previous_stage").assertIsEnabled()
            // The displayed action belongs to anaphora. Pause alone will materialize theotokos.
            compose.runOnIdle { h.now += (waiting.segment as PlaybackSegment.Wait).durationBaseMillis + 123 }
            compose.onNodeWithTag("previous_stage").performClick()
            compose.runOnIdle {
                assertEquals("creed", h.current.stage.id)
                assertEquals(waiting.context, h.current.context)
                assertEquals(0L, h.current.state.elapsedQuarterMillis)
                assertFalse(h.current.state.isPlaying)
                assertEquals(writes + 3, h.writes)
            }
        }

    @Test fun capturedControlsCannotOpenSeekOrPauseTheReplacementOwner() =
        withCiFailureDiagnostics("composition.staleControls") {
            val h = launch(reading("creed", track = 1, line = 12))
            val oldEntry = action(compose.onNodeWithTag("reader_controls"))
            compose.openReaderControls()
            val oldActions = listOf("previous_line", "next_line", "controls_order", "controls_clarify", "previous_stage", "next_stage")
                .map { action(compose.readerControl(it).assertIsEnabled()) }
            compose.runOnIdle {
                assertTrue(h.player.accept(reading("creed", track = 1, line = 12, date = "2026-11-09", offset = 2_345), true))
                assertTrue(h.player.play())
            }
            compose.onNodeWithTag("reader_controls_list").assertDoesNotExist()
            compose.onFeedLine(requireNotNull(h.current.track), 12).assertIsDisplayed()
            val away = h.current.tracks[0].lines[0]
            val awayTrack = h.current.tracks.single { away in it.lines }
            val index = requireNotNull(ReaderFeed(h.current.stage.id, h.current.tracks).lineIndex(awayTrack.id, away.id))
            compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(index)) }
            compose.waitForIdle()
            compose.onFeedLine(away).assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertIsDisplayed()
            compose.runOnIdle { assertTrue(h.current.state.isPlaying) }
            val accepted = h.current
            val writes = h.writes
            val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
            compose.runOnIdle { oldEntry(); oldActions.forEach { it() } }
            compose.onNodeWithTag("reader_controls_list").assertDoesNotExist()
            compose.onFeedLine(away).assertIsDisplayed()
            assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
            compose.runOnIdle { assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying); assertEquals(0, h.orders); assertEquals(0, h.clarifications) }
        }

    @Test fun migrationNoteRemainsReachableAtTheFirstHeaderAcrossTrackBoundaryMeaningAndDetails() =
        withCiFailureDiagnostics("composition.migrationNoteReachability") {
            val initial = reading("anaphora", track = 0, line = 3).copy(migrationNote = PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE)
            val h = launch(initial)
            val first = initial.tracks.first()
            val phrase = initial.segment as PlaybackSegment.Text
            val text = "Тексты дополнены. Вернулись к последней сохраненной фразе"
            feed("playback_migration_notice").assertTextEquals(text).assertHasNoClickAction()
                .assert(hasAnyAncestor(hasTestTag(feedHeaderTag(first))))
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle { h.now += phrase.durationBaseMillis + 123; h.player.tick(h.player.generation) }
            compose.runOnIdle { assertNotEquals(first.id, h.current.track?.id) }
            feed("playback_migration_notice").assertTextEquals(text)
                .assert(hasAnyAncestor(hasTestTag(feedHeaderTag(first))))
            val position = h.current.state.segmentIndex
            compose.selectReaderMode("meaning")
            compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("playback_migration_notice"))
            compose.onNodeWithTag("playback_migration_notice").assertTextEquals(text).assertIsDisplayed()
            val paused = h.current
            val writes = h.writes
            compose.openReaderDetails()
            compose.onNodeWithTag("reading_details_migration_notice").performScrollTo().assertTextEquals(text)
            compose.closeReaderDetails()
            compose.onNodeWithTag("return_to_text").performClick()
            compose.runOnIdle { assertEquals(position, h.current.state.segmentIndex); assertEquals(paused, h.current); assertEquals(writes, h.writes) }

            val empty = segment("preparation", PlaybackSegment.Wait::class.java).copy(migrationNote = initial.migrationNote)
            compose.runOnIdle { assertTrue(h.player.accept(empty, false)) }
            feed("playback_migration_notice").assertTextEquals(text)
                .assert(hasAnyAncestor(hasTestTag("feed_continuation_preparation")))
        }

    @Test fun runningPreparationKeepsPauseDirectAndClarificationReachableInControls() =
        withCiFailureDiagnostics("composition.preparationClarification") {
            val h = launch(segment("preparation", PlaybackSegment.Wait::class.java))
            compose.runOnIdle { assertTrue(h.player.play()); h.now += 101 }
            val pause = fullTarget("before_start_pause")
            val clarify = fullTarget("reader_microphone")
            assertSeparate("Pause and clarification", pause, clarify)
            compose.openReaderControls()
            compose.readerControl("controls_clarify").assertTextEquals("Уточнить место").performClick()
            compose.runOnIdle {
                assertEquals(1, h.clarifications)
                assertFalse(h.current.state.isPlaying)
                assertEquals(404L, h.current.state.elapsedQuarterMillis)
                assertEquals(2, h.writes)
            }
            status("До начала литургии · примерное место", "До начала")
        }

    @Test fun narrowPortraitReturnReservesItsSlotWithoutChangingTheReadingOrPrimaryBounds() =
        withCiFailureDiagnostics("composition.portraitReturnGeometry") { checkReturnGeometry(320, 800, 1.3f) }

    @Test fun landscapeReturnReservesItsSlotWithoutClippingPrimaryOrReading() =
        withCiFailureDiagnostics("composition.landscapeReturnGeometry") {
            try {
                orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                checkReturnGeometry(640, 360, 1.69f)
            } finally {
                orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            }
        }

    private fun checkReturnGeometry(width: Int, height: Int, scale: Float) {
        val h = launch(reading("lords_prayer", offset = 1_234).copy(isApproximate = true), width, height, scale)
        val before = h.current
        val readingBounds = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        val primary = fullTarget("play_lyrics")
        val clarify = fullTarget("reader_microphone")
        val controls = fullTarget("reader_controls")
        feed(feedLineTag(h.current.tracks.last().lines.last()))
        val returnSlot = fullTarget("return_to_phrase")
        assertEquals(clarify, fullTarget("reader_microphone"))
        assertSeparate("Return and clarification", returnSlot, clarify)
        assertSeparate("Return and primary", returnSlot, primary)
        assertSeparate("Return and controls", returnSlot, controls)
        if (compose.readerHasModeChooser()) {
            compose.onNodeWithTag("return_to_phrase").assertContentDescriptionEquals("К выбранной фразе")
            compose.openReaderControls()
            compose.readerControl("controls_return").assertTextEquals("К выбранной фразе").assertIsEnabled()
            compose.dismissReaderControls()
        } else compose.onNodeWithTag("return_to_phrase").assertTextEquals("К выбранной фразе")
        assertEquals(readingBounds, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, fullTarget("play_lyrics"))
        assertEquals(controls, fullTarget("reader_controls"))
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(returnSlot, fullTarget("return_to_phrase"))
        compose.onNodeWithTag("return_to_phrase").performClick()
        compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
        assertEquals(clarify, fullTarget("reader_microphone"))
        assertEquals(readingBounds, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, fullTarget("play_lyrics"))
        compose.onFeedLine(requireNotNull(before.track), 0).assertIsSelected().assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, h.current); assertEquals(0, h.writes) }
        // Changing the transport label must not resize the text viewport either.
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(readingBounds, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, fullTarget("play_lyrics"))
        assertEquals(clarify, fullTarget("reader_microphone"))
        assertEquals(controls, fullTarget("reader_controls"))
        compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += 101 }
        compose.onNodeWithTag("play_lyrics").performClick()
        assertEquals(readingBounds, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, fullTarget("play_lyrics"))
        compose.runOnIdle {
            assertFalse(h.current.state.isPlaying)
            assertEquals(before.state.segmentIndex, h.current.state.segmentIndex)
            assertEquals(before.state.elapsedQuarterMillis + 404, h.current.state.elapsedQuarterMillis)
            assertEquals(before.context, h.current.context)
            assertEquals(before.state.speed, h.current.state.speed)
            assertEquals(2, h.writes)
        }
    }

    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L
        var writes = 0
        var orders = 0
        var clarifications = 0
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
                    ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {},
                        onSelectStage = { h.orders++ }, onFindStage = { h.clarifications++; h.player.pause() }, onSettings = {},
                        dark = false, onDarkChange = {}, onOpenSource = {}, onSoundSettings = {}, onFinish = {},
                        preferredOriginal = h.original, onOriginalChange = { h.original = it })
                } }
            }
        }
        return h
    }
    private fun plan(date: String) = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(date),
        container.readings?.days?.singleOrNull { it.date == date })
    private fun reading(stage: String, track: Int = 0, line: Int = 0, offset: Long = 0, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date)
        val text = plan.tracksByStage.getValue(stage)[track]
        val engine = ServicePlaybackEngine(plan)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", date, "Europe/Moscow", ReadingContextSource.MANUAL),
            engine.seekLine(engine.start(stage), text.id, text.lines[line].id).copy(elapsedQuarterMillis = offset))
    }
    private fun segment(stage: String, type: Class<out PlaybackSegment>, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date)
        val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", date, "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stage).copy(segmentIndex = index))
    }
    private fun feed(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun status(expected: String, compact: String) = compose.assertReaderStatus(expected, compact)
    private fun action(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun fullTarget(tag: String): androidx.compose.ui.unit.DpRect {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val visible = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
        assertTrue("$tag must be fully visible", visible.left <= full.left + .5.dp && visible.top <= full.top + .5.dp &&
            visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
        return full
    }
    private fun assertSeparate(label: String, a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect) {
        assertTrue("$label overlap: $a / $b", a.right <= b.left + .5.dp || b.right <= a.left + .5.dp ||
            a.bottom <= b.top + .5.dp || b.bottom <= a.top + .5.dp)
    }
    private fun orient(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
    }
}
