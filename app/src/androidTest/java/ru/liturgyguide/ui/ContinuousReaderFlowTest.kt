package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.LocalDate

/** Product journeys for the whole-service reader; old stage-local browsing rules do not apply. */
@RunWith(AndroidJUnit4::class)
class ContinuousReaderFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)

    @Test fun scrollingIntoAnotherPartChangesItsTitleAndExplanationWithoutOpeningANewSession() {
        val h = launch("beginning")
        val target = h.feed.plan.stages.first { it.id == "creed" }
        val generation = h.player.generation
        browseTo(h.feed.stageIndex(target.id))
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(target.name)
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithText(target.description).assertIsDisplayed()
        compose.onNodeWithTag("return_to_text").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(target.name)
        compose.runOnIdle { assertEquals(generation, h.player.generation); assertEquals(target.id, h.current.stage.id); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun russianAndOriginalKeepTheLongVisiblePhraseAndPlayStartsThere() {
        val h = launch("beginning")
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().filter { it.line.original != null }.maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        val listTop = compose.onNodeWithTag("lyrics_list").getBoundsInRoot().top
        val beforeTop = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("player_original_tab").performClick()
        compose.onNodeWithTag("feed_line_${line.line.id}").assertIsDisplayed().assertTextEquals(requireNotNull(line.line.original))
        val afterTop = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top
        if (beforeTop < listTop - 2.dp) assertTrue("Language switch must keep progress inside the paragraph", afterTop < listTop)
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("feed_line_${line.line.id}").assertIsDisplayed().assertTextEquals(line.line.russian)
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertEquals(line.line.id, h.current.text?.lineId); assertTrue(h.current.state.isPlaying) }
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .60f) }
        compose.runOnIdle { assertFalse(h.current.state.isPlaying) }
    }

    @Test fun automaticFollowingStopsAtMissingReadingAndContinuesOnlyByAnExplicitAction() {
        val h = launch("epistle")
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(h.current.segment is PlaybackSegment.MissingReading); assertFalse(h.current.state.isPlaying) }
        compose.onNodeWithTag("continue_missing_reading").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("gospel", h.current.stage.id); assertFalse(h.current.state.isPlaying) }
        browseTo(h.feed.stageIndex("anaphora"))
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(h.feed.plan.stages.first { it.id == "anaphora" }.name)
    }

    @Test fun aRejectedBookmarkWriteCannotStartAutoplayFromAnUnacceptedVisiblePlace() {
        val h = launch("beginning")
        val accepted = h.current
        compose.runOnIdle { h.writable = false }
        browseTo(h.feed.stageIndex("creed"))
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertEquals(accepted, h.current); assertFalse(h.current.state.isPlaying) }
        compose.onNodeWithText("Не удалось сохранить место. Попробуйте ещё раз").assertIsDisplayed()
    }

    @Test fun tappingALineChoosesItAndPlayDoesNotReturnToThePreviousViewportAnchor() {
        val h = launch("beginning")
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().first { it.stageId == "anaphora" }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(h.feed.indexOf(line.key)))
        compose.onNodeWithTag("feed_line_${line.line.id}").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(line.line.id, h.current.text?.lineId); assertFalse(h.current.state.isPlaying) }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertEquals(line.line.id, h.current.text?.lineId); assertTrue(h.current.state.isPlaying) }
    }

    @Test fun explanationAndReturnKeepTheVisiblePlaceDateAndSpeed() {
        val h = launch("beginning")
        compose.runOnIdle { h.player.changeSpeed(PlaybackSpeed.SLOW) }
        browseTo(h.feed.stageIndex("lords_prayer"))
        compose.onNodeWithTag("player_meaning_tab").performClick()
        val saved = compose.runOnIdle { h.current }
        compose.onNodeWithTag("return_to_text").performClick()
        compose.runOnIdle {
            assertEquals("lords_prayer", h.current.stage.id)
            assertEquals(saved.context, h.current.context)
            assertEquals(saved.state, h.current.state)
            assertEquals(PlaybackSpeed.SLOW, h.current.state.speed)
            assertFalse(h.current.state.isPlaying)
        }
    }

    @Test fun aTallParagraphScrollsToItsLastLineBeforeTheClockChoosesTheNextPhrase() {
        val h = launch("beginning", fontScale = 2f, smallPhone = true)
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        compose.onNodeWithTag("feed_line_${line.line.id}").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        val viewport = compose.onNodeWithTag("lyrics_list").getBoundsInRoot()
        val initial = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot()
        assertTrue("The test needs a real paragraph taller than the viewport", initial.bottom - initial.top > viewport.bottom - viewport.top)
        compose.runOnIdle {
            val segment = h.current.segment as PlaybackSegment.Text
            h.now += segment.durationBaseMillis * 9 / 10
            h.player.tick(h.player.generation)
        }
        compose.waitForIdle()
        val later = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot()
        assertTrue("Autoplay must reveal the lower lines", later.top < initial.top - 24.dp)
        assertTrue("The end of the paragraph must be visible before moving on", later.bottom <= viewport.bottom + 2.dp)
        compose.runOnIdle { assertEquals(line.line.id, h.current.text?.lineId); assertTrue(h.current.state.isPlaying) }
    }

    @Test fun cancellingMicrophoneReturnsToTheSameManualParagraphOffset() {
        val h = launch("beginning")
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        val before = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("reader_microphone").performClick()
        compose.onNodeWithTag("service_book").assertDoesNotExist()
        compose.runOnIdle { h.showReader = true }
        val after = compose.onNodeWithTag("feed_line_${line.line.id}").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(before.top, after.top)
        compose.runOnIdle { assertEquals(line.line.id, h.current.text?.lineId); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun pausingAndResumingATallParagraphWithoutElapsedTimeKeepsItsExactVisiblePosition() {
        val h = launch("beginning", fontScale = 2f, smallPhone = true)
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        compose.onNodeWithTag("feed_line_${line.line.id}").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        val viewport = compose.onNodeWithTag("lyrics_list").getBoundsInRoot()
        val initial = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot()
        assertTrue("The pause/resume scenario needs real paragraph overflow", initial.bottom - initial.top > viewport.bottom - viewport.top)
        compose.runOnIdle {
            val segment = h.current.segment as PlaybackSegment.Text
            h.now += segment.durationBaseMillis * 45 / 100
            h.player.tick(h.player.generation)
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot()
        assertTrue("The paragraph must actually advance before pause/resume is checked", before.top < initial.top - 24.dp)
        val elapsed = compose.runOnIdle { h.current.state.elapsedQuarterMillis }
        val clock = compose.runOnIdle { h.now }
        repeat(2) {
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.waitForIdle()
            assertEquals(before.top, compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top)
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.waitForIdle()
            assertEquals(before.top, compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top)
            compose.runOnIdle {
                assertEquals(clock, h.now)
                assertEquals(elapsed, h.current.state.elapsedQuarterMillis)
                assertEquals(line.line.id, h.current.text?.lineId)
                assertTrue(h.current.state.isPlaying)
            }
        }
    }

    @Test fun scriptureShowsRussianWhileTheOriginalPreferenceReturnsAtTheNextPrayer() {
        val h = launch("beginning", date = LocalDate.of(2026, 11, 8), preferredOriginal = true)
        val scripture = h.feed.items.filterIsInstance<ServiceBookItem.Line>().first { h.feed.plan.track(it.trackId)?.isScripture == true }
        browseTo(requireNotNull(h.feed.indexOf(scripture.key)))
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("player_original_tab").assertIsNotEnabled()
        compose.onNodeWithTag("scripture_language_notice").assertIsDisplayed()
        val prayer = h.feed.items.filterIsInstance<ServiceBookItem.Line>().first { it.stageId == "anaphora" }
        browseTo(requireNotNull(h.feed.indexOf(prayer.key)))
        compose.onNodeWithTag("player_original_tab").assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("scripture_language_notice").assertDoesNotExist()
    }

    @Test fun reachingTheEndByHandOffersAnImmediateFinishAction() {
        val h = launch("beginning")
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag("book_end"))
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .55f) }
        compose.waitForIdle()
        compose.onNode(hasText("Закончить чтение") and hasClickAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, h.finishes); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun returningFromMicrophoneKeepsTheTallParagraphPlaybackBaseline() {
        val h = launch("beginning", fontScale = 2f, smallPhone = true)
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        compose.onNodeWithTag("feed_line_${line.line.id}").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle {
            val segment = h.current.segment as PlaybackSegment.Text
            h.now += segment.durationBaseMillis * 45 / 100
            h.player.tick(h.player.generation)
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top
        assertTrue(before < compose.onNodeWithTag("lyrics_list").getBoundsInRoot().top - 24.dp)
        compose.onNodeWithTag("reader_microphone").performClick()
        compose.runOnIdle { h.showReader = true }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { assertFalse(h.current.state.isPlaying) }
    }

    @Test fun aRecreatedPlayerKeepsManualPlaybackProgressAfterItsGenerationRestarts() {
        val h = launch("beginning", fontScale = 2f, smallPhone = true)
        compose.runOnIdle { assertTrue(h.player.accept(h.current, false)); assertTrue(h.player.generation > 0) }
        val line = h.feed.items.filterIsInstance<ServiceBookItem.Line>().maxBy { it.line.russian.length }
        browseTo(requireNotNull(h.feed.indexOf(line.key)))
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .8f, endY = height * .6f) }
        compose.waitForIdle()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle {
            val segment = h.current.segment as PlaybackSegment.Text
            h.now += segment.durationBaseMillis * 45 / 100
            h.player.tick(h.player.generation)
        }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.waitForIdle()
        val before = compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top
        assertTrue(before < compose.onNodeWithTag("lyrics_list").getBoundsInRoot().top - 24.dp)
        compose.runOnIdle { h.player = ServicePlayerState(h.current, { h.now }, ServicePlaybackWriter { _, _, _ -> true }) }
        assertEquals(0, h.player.generation)
        h.restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("feed_line_${line.line.id}").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { assertFalse(h.current.state.isPlaying) }
    }

    @Test fun aSmallPhoneWithDoubleSizeTextKeepsReadingSpaceAndEveryEssentialAction() {
        launch("great_litany", fontScale = 2f, smallPhone = true)
        compose.onNodeWithTag("reader_surface").assertHeightIsAtLeast(120.dp).assertIsDisplayed()
        compose.onNodeWithTag("reader_mode_chooser").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("controls_original").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("controls_text").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("controls_meaning").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_surface").assertHeightIsAtLeast(120.dp)
        compose.onNodeWithTag("return_to_text").assertIsDisplayed().assertContentDescriptionEquals("К тексту").performClick()
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertContentDescriptionEquals("Автопрокрутка")
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reader_microphone").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    private fun browseTo(index: Int) {
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(index)
        // A real touch makes this manual reading; programmatic test positioning alone must not.
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .73f) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private class Harness(var player: ServicePlayerState, val feed: ContinuousServiceFeed) {
        lateinit var restoration: StateRestorationTester
        var now = 10_000L
        var writable = true
        var finishes = 0
        var showReader by mutableStateOf(true)
        val current get() = requireNotNull(player.reading)
    }

    private fun launch(stageId: String, date: LocalDate = LocalDate.of(2026, 11, 10), fontScale: Float = 1f,
        preferredOriginal: Boolean = false, smallPhone: Boolean = false): Harness {
        val stages = container.getStageMatcher().getAllStages()
        val plan = ServicePlaybackPlan.build(stages, date, container.readings?.days?.firstOrNull { it.date == date.toString() })
        val context = requireNotNull(ReadingContextFactory.manualOnDate(null, date))
        val initial = ActiveServicePlayback(plan, context, ServicePlaybackEngine(plan).start(stageId))
        lateinit var h: Harness
        val player = ServicePlayerState(initial, { h.now }, ServicePlaybackWriter { _, _, _ -> h.writable })
        h = Harness(player, ContinuousServiceFeed(plan))
        h.restoration = StateRestorationTester(compose)
        h.restoration.setContent {
            var original by remember { mutableStateOf(preferredOriginal) }
            val holder = rememberSaveableStateHolder()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) {
                    if (h.showReader) holder.SaveableStateProvider("reader") {
                        Box(if (smallPhone) Modifier.size(320.dp, 640.dp) else Modifier.fillMaxSize()) {
                        ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {}, onFindStage = {},
                            onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {}, onSoundSettings = {}, onFinish = { h.finishes++ },
                            preferredOriginal = original, onOriginalChange = { original = it }, onMicrophone = { h.showReader = false })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return h
    }
}
