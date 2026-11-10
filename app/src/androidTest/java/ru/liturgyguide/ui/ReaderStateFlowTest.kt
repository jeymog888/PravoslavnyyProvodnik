package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
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

/** Real whole-book actions with the production owner; only clock and durable writes are controlled. */
@RunWith(AndroidJUnit4::class)
class ReaderStateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val date = LocalDate.of(2026, 11, 9)
    private val primaryTags = listOf("play_lyrics", "return_to_text", "continue_missing_reading", "finish_reading")

    @Test fun changingLanguagePausesTheLiveClockAndKeepsItsPhraseAndSpeed() = withCiFailureDiagnostics("readerState.languagePause") {
        val h = launch(reading("creed", PlaybackSegment.Text::class.java))
        assertPrimary("play_lyrics", "Автопрокрутка")
        compose.onNodeWithTag("play_lyrics").performClick()
        assertPrimary("play_lyrics", "Пауза")
        compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += 101 }
        compose.selectReaderMode("text")
        assertPrimary("play_lyrics", "Автопрокрутка")
        compose.runOnIdle {
            assertEquals(303L, h.current.state.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.SLOW, h.current.state.speed)
            assertFalse(h.current.state.isPlaying)
            assertFalse(h.original)
        }
        assertFrozenAfterTime(h)
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithText("Текст движется без звука, с примерной скоростью службы. Любая ручная прокрутка остановит его").assertExists()
        compose.onNodeWithText("Готово").performClick()
    }

    @Test fun waitingExplainsTheNextCueAndItsOwnApproximateTransition() = withCiFailureDiagnostics("readerState.waitActionAndNextCue") {
        val h = launch(reading("great_litany", PlaybackSegment.Wait::class.java))
        val before = h.current
        body("stage_wait").assertIsDisplayed()
        compose.onNodeWithText("Слушаем службу").assertExists()
        compose.onNodeWithText("Эта часть в храме может продолжаться. Следующий переход по времени будет примерным").assertExists()
        compose.onNodeWithText(requireNotNull(nextStage(before).cue)).assertExists()
        compose.onNodeWithTag("play_lyrics").performClick()
        assertPrimary("play_lyrics", "Пауза")
        compose.runOnIdle { h.now += 101 }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle {
            assertEquals(before.text, h.current.text)
            assertEquals(before.context, h.current.context)
            assertEquals(before.state.speed, h.current.state.speed)
            assertEquals(303L, h.current.state.elapsedQuarterMillis)
        }
        assertFrozenAfterTime(h)
        body("next_stage").performClick()
        assertPrimary("play_lyrics", "Автопрокрутка")
        compose.runOnIdle { assertEquals(nextStage(before).id, h.current.stage.id); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun beforeStartHasAnInlineExplicitBeginningWithoutBlockingManualReading() = withCiFailureDiagnostics("readerState.inlineBeginning") {
        val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
        val before = h.current
        assertPrimary("play_lyrics", "Автопрокрутка")
        body("start_liturgy").assertTextEquals("Перейти к началу литургии")
        assertFrozenAfterTime(h)
        compose.onNodeWithTag("start_liturgy").performClick()
        compose.runOnIdle {
            assertEquals("beginning", h.current.stage.id)
            assertEquals(before.context, h.current.context)
            assertEquals(before.state.speed, h.current.state.speed)
            assertEquals(0L, h.current.state.elapsedQuarterMillis)
            assertFalse(h.current.state.isPlaying)
        }
        assertPrimary("play_lyrics", "Автопрокрутка")
    }

    @Test fun anAlreadyRunningPreparationCanBeStoppedAndAFailedBeginningDoesNotLookAccepted() = withCiFailureDiagnostics("readerState.runningPreparationAndFailedBeginning") {
        val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
        compose.runOnIdle { assertTrue(h.player.play()); h.now += 101 }
        assertPrimary("play_lyrics", "Пауза")
        compose.onNodeWithTag("play_lyrics").performClick()
        val paused = compose.runOnIdle { h.current.also { assertEquals(303L, it.state.elapsedQuarterMillis); h.writable = false } }
        body("start_liturgy").performClick()
        compose.runOnIdle { assertEquals(paused, h.current); assertFalse(h.current.state.isPlaying) }
        assertFrozenAfterTime(h)
        compose.runOnIdle { h.writable = true }
        compose.onNodeWithTag("start_liturgy").performClick()
        compose.runOnIdle { assertEquals("beginning", h.current.stage.id); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun meaningPausesTheActualOwnerAndReturnsToTheSameOriginalPhraseAndTempo() = withCiFailureDiagnostics("readerState.meaningPreservesAcceptedPhrase") {
        val h = launch(reading("creed", PlaybackSegment.Text::class.java))
        compose.assertReaderMode("original", selected = true)
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { h.now += 101 }
        compose.selectReaderMode("meaning")
        assertPrimary("return_to_text", "К тексту")
        assertNoTransport()
        val paused = compose.runOnIdle { h.current.also { assertFalse(it.state.isPlaying); assertEquals(303L, it.state.elapsedQuarterMillis) } }
        assertFrozenAfterTime(h)
        compose.onNodeWithTag("return_to_text").performClick()
        compose.assertReaderMode("original", selected = true)
        assertPrimary("play_lyrics", "Автопрокрутка")
        compose.runOnIdle { assertEquals(paused, h.current); assertTrue(h.original) }
    }

    @Test fun missingReadingRetainsItsDateAndBarrierAcrossMeaningAndARejectedWrite() = withCiFailureDiagnostics("readerState.missingMeaningAndRejectedContinuation") {
        val h = launch(reading("epistle", PlaybackSegment.Text::class.java))
        compose.onNodeWithTag("play_lyrics").performClick()
        advance(h, 86_400_000)
        val missing = compose.runOnIdle { h.current.also { assertTrue(it.segment is PlaybackSegment.MissingReading); assertFalse(it.state.isPlaying) } }
        assertPrimary("continue_missing_reading", "Чтение закончилось")
        assertNoTransport()
        // Both Apostle and Gospel notices can be laid out in the whole book.
        // The Apostle is the first barrier, and its canonical row is the current one.
        val feed = ContinuousServiceFeed(missing.plan)
        compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(feed.indexOf("missing:epistle")))
        compose.onAllNodesWithTag("missing_reading_barrier").onFirst().performScrollTo().assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("Текст на 9 ноября 2026 пока не добавлен. Послушайте чтение в храме, затем продолжайте ниже")))
        compose.selectReaderMode("meaning")
        assertPrimary("return_to_text", "К тексту")
        compose.onNodeWithTag("return_to_text").performClick()
        assertPrimary("continue_missing_reading", "Чтение закончилось")
        assertFrozenAfterTime(h)
        compose.runOnIdle { h.writable = false }
        compose.onNodeWithTag("continue_missing_reading").performClick()
        compose.runOnIdle { assertEquals(missing, h.current); h.writable = true }
        compose.onNodeWithTag("continue_missing_reading").performClick()
        compose.runOnIdle {
            assertEquals("gospel", h.current.stage.id)
            assertEquals(missing.context, h.current.context)
            assertEquals(missing.state.speed, h.current.state.speed)
            assertFalse(h.current.state.isPlaying)
        }
    }

    @Test fun finalWaitAndEndDoNotPromiseAnotherStageAndFinishNeedsAnExplicitAction() = withCiFailureDiagnostics("readerState.finalWaitAndExplicitFinish") {
        val h = launch(reading("dismissal", PlaybackSegment.Wait::class.java))
        body("stage_wait").assertIsDisplayed()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        compose.onNodeWithTag("play_lyrics").performClick()
        advance(h, 86_400_000)
        assertPrimary("finish_reading", "Закончить чтение")
        assertNoTransport()
        body("book_end").assertIsDisplayed()
        compose.selectReaderMode("meaning")
        assertPrimary("return_to_text", "К тексту")
        compose.onNodeWithTag("return_to_text").performClick()
        assertFrozenAfterTime(h)
        compose.runOnIdle { assertEquals(0, h.finishes); assertTrue(h.current.segment is PlaybackSegment.End) }
        compose.onNodeWithTag("finish_reading").performClick()
        compose.runOnIdle { assertEquals(1, h.finishes); assertNull(h.player.reading) }
    }

    @Test fun capturedBeginningContinuationAndFinishCannotChangeAReplacementReading() = withCiFailureDiagnostics("readerState.stalePrimaryActions") {
        val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
        val cases = listOf(reading("preparation", PlaybackSegment.Wait::class.java) to "start_liturgy",
            reading("epistle", PlaybackSegment.MissingReading::class.java) to "continue_missing_reading",
            reading("dismissal", PlaybackSegment.End::class.java) to "finish_reading")
        for ((old, tag) in cases) {
            compose.runOnIdle { assertTrue(h.player.accept(old, false)) }
            val node = if (tag == "start_liturgy") body(tag) else compose.onNodeWithTag(tag).assertIsDisplayed()
            val action = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val (accepted, writes) = compose.runOnIdle {
                assertTrue(h.player.accept(reading("creed", PlaybackSegment.Text::class.java, date.plusDays(1)), true))
                assertTrue(h.player.play())
                val accepted = h.current; val writes = h.writes
                // Before a new composition, this still wraps the previous owner's action.
                action()
                assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying); assertEquals(0, h.finishes)
                accepted to writes
            }
            compose.waitForIdle()
            assertPrimary("play_lyrics", "Пауза")
            compose.runOnIdle { assertEquals(accepted, h.current); assertTrue(h.current.state.isPlaying); assertEquals(writes, h.writes); assertEquals(0, h.finishes) }
        }
    }

    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L; var writable = true; var writes = 0; var finishes = 0
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { _, _, _ -> writes++; writable })
        val current get() = requireNotNull(player.reading)
    }
    private fun launch(initial: ActiveServicePlayback): Harness {
        val h = Harness(initial)
        compose.setContent { GuideTheme(false) {
            ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {}, onFindStage = {},
                onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {}, onSoundSettings = {},
                onFinish = { h.finishes++; h.player.forgetAccepted() }, preferredOriginal = h.original, onOriginalChange = { h.original = it })
        } }
        return h
    }
    private fun reading(stageId: String, type: Class<out PlaybackSegment>, serviceDate: LocalDate = date): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), serviceDate, container.readings?.days?.singleOrNull { it.date == serviceDate.toString() })
        val index = plan.segments.indexOfFirst { it.stageId == stageId && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext("selected-test-church", serviceDate.toString(), "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stageId, PlaybackSpeed.SLOW).copy(segmentIndex = index))
    }
    private fun assertPrimary(tag: String, label: String) {
        compose.onAllNodes(primaryTags.map(::hasTestTag).reduce { a, b -> a or b }).assertCountEquals(1)
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        if (compose.readerHasModeChooser()) node.assertContentDescriptionEquals(label) else node.assertTextEquals(label)
        val full = node.getUnclippedBoundsInRoot(); val visible = node.getBoundsInRoot()
        assertTrue("Primary action must be fully visible", visible.left <= full.left + 1.dp && visible.top <= full.top + 1.dp && visible.right >= full.right - 1.dp && visible.bottom >= full.bottom - 1.dp)
    }
    private fun assertNoTransport() {
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("open_map").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("reader_microphone").assertIsDisplayed().assertIsEnabled()
    }
    private fun body(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun nextStage(reading: ActiveServicePlayback) = reading.plan.stages[reading.plan.stages.indexOf(reading.stage) + 1]
    private fun advance(h: Harness, millis: Long) = compose.runOnIdle { h.now += millis; h.player.tick(h.player.generation) }
    private fun assertFrozenAfterTime(h: Harness) {
        val before = compose.runOnIdle { h.current.also { assertFalse(it.state.isPlaying) } }; val writes = h.writes
        advance(h, 86_400_000)
        compose.runOnIdle { assertEquals(before, h.current); assertEquals(writes, h.writes) }
    }
}
