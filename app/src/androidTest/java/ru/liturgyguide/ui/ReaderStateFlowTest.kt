package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Real reader actions and the real owner; only the clock and durable writer are controlled. */
@RunWith(AndroidJUnit4::class)
class ReaderStateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    private val date = LocalDate.of(2026, 11, 9)
    private val primaryTags = listOf("play_lyrics", "start_liturgy", "return_to_text", "continue_missing_reading", "finish_reading")

    @Test fun textNamesItsSoundlessMovementAndPauseFreezesTheLivePosition() =
        withCiFailureDiagnostics("readerState.textNamesMovementAndPause") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            soundless()
            compose.onNodeWithTag("play_lyrics").performClick()
            assertPrimary("play_lyrics", "Пауза", "Движение по времени · примерно")
            compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += 101 }
            compose.onNodeWithTag("player_text_tab").performClick().assertIsSelected()
            compose.runOnIdle { assertTrue(h.current.state.isPlaying) }
            compose.onNodeWithTag("play_lyrics").performClick()
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            compose.runOnIdle {
                assertEquals(303L, h.current.state.elapsedQuarterMillis)
                assertEquals(PlaybackSpeed.SLOW, h.current.state.speed)
                assertFalse(h.current.state.isPlaying)
            }
            assertFrozenAfterTime(h)
        }

    @Test fun waitingExplainsTheNextCueAndItsOwnApproximateTransition() =
        withCiFailureDiagnostics("readerState.waitActionAndNextCue") {
            val h = launch(reading("great_litany", PlaybackSegment.Wait::class.java))
            val before = h.current
            assertPrimary("play_lyrics", "Продолжить по времени", "Примерный переход остановлен")
            soundless()
            body("stage_wait").assertIsDisplayed()
            compose.onNodeWithText("Слушаем службу").assertExists()
            compose.onNodeWithText("Доступный текст закончился, но этот этап в храме может продолжаться.").assertExists()
            body("next_stage_cue").assertTextEquals(requireNotNull(nextStage(before).cue))
            compose.onNodeWithTag("wait_next_stage").assertDoesNotExist()
            compose.onNodeWithTag("play_lyrics").performClick()
            assertPrimary("play_lyrics", "Пауза", "Примерный переход включен")
            compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += 101 }
            compose.onNodeWithTag("play_lyrics").performClick()
            assertPrimary("play_lyrics", "Продолжить по времени", "Примерный переход остановлен")
            compose.runOnIdle {
                assertEquals(before.text, h.current.text)
                assertEquals(before.context, h.current.context)
                assertEquals(before.state.speed, h.current.state.speed)
                assertEquals(303L, h.current.state.elapsedQuarterMillis)
            }
            assertFrozenAfterTime(h)
            compose.onNodeWithTag("next_stage").performClick()
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            compose.runOnIdle { assertEquals(nextStage(before).id, h.current.stage.id); assertFalse(h.current.state.isPlaying) }
        }

    @Test fun beforeStartUsesAnObservedCueAndAnExplicitPausedBeginning() =
        withCiFailureDiagnostics("readerState.beforeStartExplicitBeginning") {
            val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
            val before = h.current
            assertPrimary("start_liturgy", "Литургия началась", "До начала литургии")
            soundless()
            compose.onNodeWithTag("next_stage").assertDoesNotExist()
            compose.onNodeWithTag("before_start_pause").assertDoesNotExist()
            body("before_start").assertIsDisplayed()
            body("next_stage_cue").assertTextEquals(requireNotNull(nextStage(before).cue))
            assertFrozenAfterTime(h)
            compose.onNodeWithTag("start_liturgy").performClick()
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            compose.runOnIdle {
                assertEquals("beginning", h.current.stage.id)
                assertEquals(before.context, h.current.context)
                assertEquals(before.state.speed, h.current.state.speed)
                assertEquals(0L, h.current.state.elapsedQuarterMillis)
                assertFalse(h.current.state.isPlaying)
            }
        }

    @Test fun anAlreadyRunningPreparationCanBeStoppedAndAFailedBeginningDoesNotLookAccepted() =
        withCiFailureDiagnostics("readerState.runningPreparationAndFailedBeginning") {
            val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
            compose.runOnIdle { assertTrue(h.player.play()); h.now += 101 }
            assertPrimary("start_liturgy", "Литургия началась", "До начала литургии · примерный переход включен")
            compose.onNodeWithTag("before_start_pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertPrimary("start_liturgy", "Литургия началась", "До начала литургии")
            compose.onNodeWithTag("before_start_pause").assertDoesNotExist()
            val paused = compose.runOnIdle { h.current.also { assertEquals(303L, it.state.elapsedQuarterMillis); h.writable = false } }
            compose.onNodeWithTag("start_liturgy").performClick()
            assertPrimary("start_liturgy", "Литургия началась", "До начала литургии")
            compose.runOnIdle { assertEquals(paused, h.current); assertFalse(h.current.state.isPlaying) }
            assertFrozenAfterTime(h)
            compose.runOnIdle { h.writable = true }
            compose.onNodeWithTag("start_liturgy").performClick()
            compose.runOnIdle { assertEquals("beginning", h.current.stage.id); assertFalse(h.current.state.isPlaying) }
        }

    @Test fun meaningPausesTheActualOwnerAndReturnsToTheSameOriginalPhraseAndTempo() =
        withCiFailureDiagnostics("readerState.meaningPreservesAcceptedPhrase") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            compose.onNodeWithTag("player_original_tab").assertIsSelected()
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle { h.now += 101 }
            compose.onNodeWithTag("player_meaning_tab").performClick()
            assertPrimary("return_to_text", "К тексту", "Пояснение · на паузе")
            noTransport()
            val paused = compose.runOnIdle { h.current.also { assertFalse(it.state.isPlaying); assertEquals(303L, it.state.elapsedQuarterMillis) } }
            assertFrozenAfterTime(h)
            compose.onNodeWithTag("return_to_text").performClick()
            compose.onNodeWithTag("player_original_tab").assertIsSelected()
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            compose.runOnIdle { assertEquals(paused, h.current); assertTrue(h.original) }
        }

    @Test fun missingReadingRetainsItsDateAndBarrierAcrossMeaningAndARejectedWrite() =
        withCiFailureDiagnostics("readerState.missingMeaningAndRejectedContinuation") {
            val h = launch(reading("epistle", PlaybackSegment.Text::class.java))
            compose.onNodeWithTag("play_lyrics").performClick()
            advance(h, 86_400_000)
            val missing = compose.runOnIdle { h.current.also { assertTrue(it.segment is PlaybackSegment.MissingReading); assertFalse(it.state.isPlaying) } }
            val status = "Текст на 9 ноября 2026 пока не добавлен · пауза"
            assertPrimary("continue_missing_reading", "Чтение в храме закончилось", status)
            noTransport()
            body("missing_reading_barrier").assertIsDisplayed()
            compose.onNodeWithTag("player_meaning_tab").performClick()
            assertPrimary("return_to_text", "К тексту", "Пояснение · на паузе")
            noTransport()
            compose.onNodeWithTag("return_to_text").performClick()
            assertPrimary("continue_missing_reading", "Чтение в храме закончилось", status)
            assertFrozenAfterTime(h)
            compose.runOnIdle { h.writable = false }
            compose.onNodeWithTag("continue_missing_reading").performClick()
            assertPrimary("continue_missing_reading", "Чтение в храме закончилось", status)
            compose.runOnIdle { assertEquals(missing, h.current); h.writable = true }
            compose.onNodeWithTag("continue_missing_reading").performClick()
            assertPrimary("play_lyrics", "Двигать текст", "На паузе")
            compose.runOnIdle {
                assertEquals("gospel", h.current.stage.id)
                assertEquals(missing.context, h.current.context)
                assertEquals(missing.state.speed, h.current.state.speed)
                assertFalse(h.current.state.isPlaying)
            }
        }

    @Test fun finalWaitAndEndDoNotPromiseAnotherStageAndFinishNeedsAnExplicitAction() =
        withCiFailureDiagnostics("readerState.finalWaitAndExplicitFinish") {
            val h = launch(reading("dismissal", PlaybackSegment.Wait::class.java))
            body("stage_wait").assertIsDisplayed()
            compose.onNodeWithText("Доступный текст закончился. Продолжайте слушать заключительные молитвы в храме.").assertExists()
            compose.onNodeWithTag("next_stage_cue").assertDoesNotExist()
            compose.onNodeWithTag("play_lyrics").performClick()
            advance(h, 86_400_000)
            assertPrimary("finish_reading", "Закончить чтение", "Порядок в приложении завершен")
            noTransport()
            body("playback_end").assertIsDisplayed()
            compose.onNodeWithTag("player_meaning_tab").performClick()
            assertPrimary("return_to_text", "К тексту", "Пояснение · на паузе")
            compose.onNodeWithTag("return_to_text").performClick()
            assertPrimary("finish_reading", "Закончить чтение", "Порядок в приложении завершен")
            assertFrozenAfterTime(h)
            compose.runOnIdle { assertEquals(0, h.finishes); assertTrue(h.current.segment is PlaybackSegment.End) }
            compose.onNodeWithTag("finish_reading").performClick()
            compose.runOnIdle { assertEquals(1, h.finishes); assertNull(h.player.reading) }
        }

    @Test fun capturedBeginningContinuationAndFinishCannotChangeAReplacementReading() =
        withCiFailureDiagnostics("readerState.stalePrimaryActions") {
            val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
            val cases = listOf(
                reading("preparation", PlaybackSegment.Wait::class.java) to "start_liturgy",
                reading("epistle", PlaybackSegment.MissingReading::class.java) to "continue_missing_reading",
                reading("dismissal", PlaybackSegment.End::class.java) to "finish_reading")
            for ((old, tag) in cases) {
                compose.runOnIdle { assertTrue(h.player.accept(old, resetBookmarks = false)) }
                val action = requireNotNull(compose.onNodeWithTag(tag).assertIsDisplayed()
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                compose.runOnIdle {
                    assertTrue(h.player.accept(reading("creed", PlaybackSegment.Text::class.java, date.plusDays(1)), resetBookmarks = true))
                    assertTrue(h.player.play())
                }
                val accepted = compose.runOnIdle { h.current }
                val writes = h.writes
                compose.runOnIdle { action() }
                assertPrimary("play_lyrics", "Пауза", "Движение по времени · примерно")
                compose.runOnIdle {
                    assertEquals(accepted, h.current)
                    assertTrue(h.current.state.isPlaying)
                    assertEquals(writes, h.writes)
                    assertEquals(0, h.finishes)
                }
            }
        }

    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L
        var writable = true
        var writes = 0
        var finishes = 0
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { _, _, _ -> writes++; writable })
        val current get() = requireNotNull(player.reading)
    }

    private fun launch(initial: ActiveServicePlayback): Harness {
        val h = Harness(initial)
        compose.setContent {
            GuideTheme(false) {
                ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {},
                    onFindStage = {}, onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {},
                    onSoundSettings = {}, onFinish = { h.finishes++; h.player.forgetAccepted() },
                    preferredOriginal = h.original, onOriginalChange = { h.original = it })
            }
        }
        return h
    }

    private fun reading(stageId: String, segmentClass: Class<out PlaybackSegment>, serviceDate: LocalDate = date): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), serviceDate,
            container.readings?.days?.singleOrNull { it.date == serviceDate.toString() })
        val index = plan.segments.indexOfFirst { it.stageId == stageId && segmentClass.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan,
            ReadingContext("selected-test-church", serviceDate.toString(), "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stageId, PlaybackSpeed.SLOW).copy(segmentIndex = index))
    }

    private fun assertPrimary(tag: String, label: String, status: String) {
        compose.onAllNodes(primaryTags.map(::hasTestTag).reduce { a, b -> a or b }).assertCountEquals(1)
        val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertContentDescriptionEquals(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
        val visible = button.getBoundsInRoot()
        val full = button.getUnclippedBoundsInRoot()
        assertTrue("The primary action must be fully visible", visible.left <= full.left + 1.dp &&
            visible.top <= full.top + 1.dp && visible.right >= full.right - 1.dp && visible.bottom >= full.bottom - 1.dp)
        compose.onNodeWithTag("reader_primary_label", useUnmergedTree = true).assertTextEquals(label).assertIsDisplayed()
        compose.onNodeWithTag("reader_status").assertTextEquals(status).assertIsDisplayed()
    }

    private fun soundless() = compose.onNodeWithTag("soundless_note").assertTextEquals("Без звука").assertIsDisplayed()
    private fun noTransport() = listOf("play_lyrics", "next_stage", "previous_stage", "next_line", "previous_line", "scroll_options")
        .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    private fun body(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun nextStage(reading: ActiveServicePlayback) = reading.plan.stages[reading.plan.stages.indexOf(reading.stage) + 1]
    private fun advance(h: Harness, millis: Long) = compose.runOnIdle { h.now += millis; h.player.tick(h.player.generation) }
    private fun assertFrozenAfterTime(h: Harness) {
        val before = compose.runOnIdle { h.current.also { assertFalse(it.state.isPlaying) } }
        val writes = h.writes
        advance(h, 86_400_000)
        compose.runOnIdle { assertEquals(before, h.current); assertEquals(writes, h.writes) }
    }
}
