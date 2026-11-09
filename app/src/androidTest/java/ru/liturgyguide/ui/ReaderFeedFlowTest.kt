package ru.liturgyguide.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
class ReaderFeedFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun browsingAnotherHeaderDoesNotAcceptItOrChangeTheMeaning() = withCiFailureDiagnostics("feed.browseAndMeaning") {
        val h = launch(reading("anaphora", trackIndex = 0, lineIndex = 1, offset = 1_234))
        val before = h.current
        val other = before.tracks[5]
        scroll(feedHeaderTag(other)).assertHasNoClickAction()
        scroll(feedContextTag(other)).assertHasNoClickAction()
        scroll(feedLineTag(other.lines[0]))
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown(startY = height * .45f, endY = height * .55f) }
        compose.onFeedLine(other, 0).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onFeedLine(other, 0).assertIsDisplayed().assertTextEquals(other.lines[0].russian)
        compose.onNodeWithTag("player_original_tab").performClick()
        compose.onFeedLine(other, 0).assertIsDisplayed().assertTextEquals(requireNotNull(other.lines[0].original))
        returnButton().assertIsDisplayed()
        unchanged(h, before, 0)
        compose.onNodeWithTag("player_meaning_tab").performClick()
        scroll("track_explanation", "service_content").assertTextEquals(requireNotNull(before.track).explanation)
        compose.onNodeWithTag("reader_status").assertTextEquals("Пояснение · на паузе")
        unchanged(h, before, 0)
        compose.onNodeWithTag("return_to_text").performClick()
        scroll(feedLineTag(other.lines[0])).performClick()
        compose.runOnIdle {
            assertEquals(other.id, h.current.text?.trackId)
            assertEquals(other.lines[0].id, h.current.text?.lineId)
            assertEquals(0L, h.current.state.elapsedQuarterMillis)
            assertFalse(h.current.state.isPlaying)
            assertEquals(before.context, h.current.context)
            assertEquals(before.state.speed, h.current.state.speed)
        }
        compose.onNodeWithTag("player_meaning_tab").performClick()
        scroll("track_explanation", "service_content").assertTextEquals(other.explanation)
    }

    @Test fun realDragMaterializesATrackBoundaryBeforeBrowsingWithoutFollowingItBack() =
        withCiFailureDiagnostics("feed.dragAcrossTrackBoundary") {
            val initial = reading("anaphora")
            val last = initial.tracks[0].lines.last()
            val h = launch(seek(initial, last))
            val text = h.current.segment as PlaybackSegment.Text
            val next = h.current.plan.segments[h.current.state.segmentIndex + 1] as PlaybackSegment.Text
            assertNotEquals(text.trackId, next.trackId)
            play()
            compose.runOnIdle { h.now += text.durationBaseMillis + 123 }
            // The owner has not received a tick. The real first user delta must pause and materialize it.
            compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown() }
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе · примерное место")
            compose.runOnIdle {
                assertEquals(next, h.current.segment)
                assertEquals(492L, h.current.state.elapsedQuarterMillis)
                assertFalse(h.current.state.isPlaying)
                assertEquals(2, h.writes)
                assertEquals(initial.context, h.current.context)
            }
            val nextLine = h.current.plan.line(next)
            compose.onFeedLine(nextLine).assertIsNotDisplayed()
            returnButton().assertIsDisplayed()
            val paused = h.current
            val writes = h.writes
            compose.mainClock.advanceTimeBy(1_000)
            compose.onFeedLine(nextLine).assertIsNotDisplayed()
            unchanged(h, paused, writes)
            returnButton().performClick()
            compose.onFeedLine(nextLine).assertIsSelected().assertIsDisplayed()
            unchanged(h, paused, writes)
        }

    @Test fun realDragAtAStageBoundaryInstallsOnlyTheMaterializedStageFeed() =
        withCiFailureDiagnostics("feed.dragAcrossStageBoundary") {
            val initial = atSegment("anaphora", PlaybackSegment.Wait::class.java)
            val wait = initial.segment as PlaybackSegment.Wait
            val next = initial.plan.segments[initial.state.segmentIndex + 1] as PlaybackSegment.Text
            val h = launch(initial)
            play(waiting = true)
            compose.runOnIdle { h.now += wait.durationBaseMillis + 123 }
            compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown() }
            val nextLine = h.current.plan.line(next)
            compose.onFeedLine(nextLine).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе · примерное место")
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            compose.onNodeWithTag("feed_continuation_anaphora").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(next, h.current.segment)
                assertEquals(492L, h.current.state.elapsedQuarterMillis)
                assertFalse(h.current.state.isPlaying)
                assertEquals(initial.context, h.current.context)
                assertEquals(2, h.writes)
            }
        }

    @Test fun returnChangesOnlyViewportAndPartialLongTextIsStillVisible() =
        withCiFailureDiagnostics("feed.returnAndPartialText") {
            val first = reading("creed", trackIndex = 1)
            val longest = requireNotNull(first.track).lines.maxBy { requireNotNull(it.original).length }
            val h = launch(seek(first, longest).let { it.copy(state = it.state.copy(elapsedQuarterMillis = 1_234)) }, fontScale = 2.6f)
            val before = h.current
            val viewport = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
            val text = compose.onFeedLine(longest).assertIsSelected().assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Use an actual long phrase that does not fit in the viewport", text.height > viewport.height)
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .65f, endY = height * .45f) }
            compose.onFeedLine(longest).assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            unchanged(h, before, 0)
            scroll(feedLineTag(first.tracks[0].lines[0]))
            returnButton().assertIsDisplayed().performClick()
            compose.onFeedLine(longest).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            unchanged(h, before, 0)
        }

    @Test fun playFromAnotherTrackReturnsToTheAcceptedOffsetBeforeContinuing() =
        withCiFailureDiagnostics("feed.playFromAway") {
            val h = launch(reading("creed", trackIndex = 1, lineIndex = 12, offset = 1_234))
            val before = h.current
            val accepted = requireNotNull(before.track).lines[12]
            scroll(feedLineTag(before.tracks[0].lines[0]))
            returnButton().assertIsDisplayed()
            play()
            compose.onFeedLine(accepted).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(before.state.segmentIndex, h.current.state.segmentIndex)
                assertEquals(before.state.elapsedQuarterMillis, h.current.state.elapsedQuarterMillis)
                assertEquals(before.context, h.current.context)
                assertEquals(before.state.speed, h.current.state.speed)
                assertTrue(h.current.state.isPlaying)
                assertEquals(1, h.writes)
                h.now += 101
            }
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle { assertEquals(1_638L, h.current.state.elapsedQuarterMillis); assertFalse(h.current.state.isPlaying) }
        }

    @Test fun aRejectedLineChoiceCannotTurnBoundaryPauseIntoSuccessfulFocus() =
        withCiFailureDiagnostics("feed.failedWriterNoFocus") {
            val initial = reading("anaphora")
            // These adjacent tracks have short headers, isolating focus from a header-height change.
            val h = launch(seek(initial, initial.tracks[1].lines.last()))
            play()
            val old = h.current.segment as PlaybackSegment.Text
            val next = h.current.plan.segments[h.current.state.segmentIndex + 1] as PlaybackSegment.Text
            assertNotEquals(old.trackId, next.trackId)
            val requested = initial.tracks.last().lines.last()
            // ScrollToIndex is programmatic setup; ScrollBy is an accessibility user action.
            positionAtLine(h, requested)
            compose.runOnIdle { assertTrue(h.current.state.isPlaying); assertEquals(1, h.writes); h.now += old.durationBaseMillis + 123; h.writable = false }
            val beforeBounds = compose.onFeedLine(requested).getUnclippedBoundsInRoot()
            compose.onFeedLine(requested).performClick()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе · примерное место")
            compose.onFeedLine(requested).assertIsDisplayed().assertIsNotSelected()
            assertEquals(beforeBounds, compose.onFeedLine(requested).getUnclippedBoundsInRoot())
            compose.onFeedLine(h.current.plan.line(next)).assertIsNotDisplayed()
            returnButton().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(next, h.current.segment)
                assertEquals(492L, h.current.state.elapsedQuarterMillis)
                assertFalse(h.current.state.isPlaying)
                assertEquals(3, h.writes) // Play, mandatory boundary pause, rejected explicit choice.
            }
        }

    @Test fun choosingTheSameTrackReturnsWithoutASeekOrAnOwnerFocusIncrement() =
        withCiFailureDiagnostics("feed.sameTrackExplicitFocus") {
            val h = launch(reading("creed", trackIndex = 1, lineIndex = 12, offset = 1_234))
            val before = h.current
            val focus = h.player.focusRequest
            scroll(feedLineTag(before.tracks[0].lines[0]))
            returnButton().assertIsDisplayed()
            compose.openReaderTracks()
            compose.onNodeWithTag("track_1").assertIsSelected().performClick()
            compose.onFeedLine(requireNotNull(before.track), 12).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            unchanged(h, before, 0)
            compose.runOnIdle { assertEquals(focus, h.player.focusRequest) }
        }

    @Test fun rejectedPlayAndTrackChoiceKeepBothTheAcceptedPositionAndBrowsedText() =
        withCiFailureDiagnostics("feed.rejectedPlayAndTrackChoice") {
            val h = launch(reading("creed", trackIndex = 1, lineIndex = 12, offset = 1_234))
            val before = h.current
            val away = before.tracks[0].lines[0]
            val focus = h.player.focusRequest
            scroll(feedLineTag(away)).assertIsNotSelected()
            val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
            returnButton()
            compose.runOnIdle { h.writable = false }
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе")
            compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals("Двигать текст")
            compose.onFeedLine(away).assertIsDisplayed().assertIsNotSelected()
            assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
            returnButton()
            unchanged(h, before, 1)
            compose.openReaderTracks()
            compose.onNodeWithTag("track_0").assertIsNotSelected().performClick()
            // A rejected choice leaves the sheet open and its accepted selection intact.
            compose.onNodeWithTag("track_0").assertIsNotSelected().assertIsDisplayed()
            compose.onNodeWithTag("track_1").assertIsSelected().assertIsDisplayed()
            unchanged(h, before, 2)
            val dismiss = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            dismiss.assertCountEquals(1)
            dismiss[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
            compose.waitForIdle()
            compose.onNodeWithTag("track_list").assertDoesNotExist()
            compose.onFeedLine(away).assertIsDisplayed().assertIsNotSelected()
            assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
            returnButton()
            unchanged(h, before, 2)
            compose.runOnIdle { assertEquals(focus, h.player.focusRequest) }
        }

    @Test fun acceptedScriptureKeepsItsRussianLabelAndThePrayerOriginalInTheSameFeed() =
        withCiFailureDiagnostics("feed.mixedScripture") {
            val h = launch(reading("gospel", trackIndex = 1, lineIndex = 3, offset = 1_234))
            val before = h.current
            val scripture = requireNotNull(before.track)
            val prayer = before.tracks.first { !it.isScripture }
            compose.onNodeWithTag("player_original_tab").assertIsEnabled().assertIsSelected()
            compose.onNodeWithTag("reader_status").assertTextEquals("На паузе · по-русски").assertIsDisplayed()
            compose.onFeedLine(scripture, 3).assertIsSelected().assertIsDisplayed().assertTextEquals(scripture.lines[3].russian)
            scroll(feedLanguageTag(scripture)).assertTextEquals("${scripture.subtitle}. Церковнославянский текст пока не добавлен")
            scroll(feedLineTag(prayer.lines[0])).assertTextEquals(requireNotNull(prayer.lines[0].original)).assertIsNotSelected()
            unchanged(h, before, 0)
            compose.onNodeWithTag("player_meaning_tab").performClick()
            scroll("track_explanation", "service_content").assertTextEquals(scripture.explanation)
            compose.onNodeWithTag("return_to_text").performClick()
            compose.onNodeWithTag("player_original_tab").assertIsSelected()
            compose.onFeedLine(scripture, 3).assertIsSelected().assertIsDisplayed()
            unchanged(h, before, 0)
        }

    @Test fun aScriptureOnlyFixtureDoesNotAdvertiseAnOriginalItDoesNotHave() =
        withCiFailureDiagnostics("feed.scriptureOnly") {
            val full = plan()
            val only = ServicePlaybackPlan.build(full.stages.map { if (it.id == "gospel") it.copy(texts = emptyList()) else it },
                full.serviceDate, container.readings!!.days.single { it.date == full.serviceDate.toString() })
            val initial = ActiveServicePlayback(only, context(only.serviceDate), ServicePlaybackEngine(only).start("gospel"))
            val h = launch(initial)
            val scripture = requireNotNull(h.current.track)
            assertTrue(h.current.tracks.all { it.isScripture })
            compose.onNodeWithTag("player_original_tab").assertIsNotEnabled()
            compose.onNodeWithTag("player_text_tab").assertIsSelected()
            compose.onFeedLine(scripture, 0).assertTextEquals(scripture.lines[0].russian).assertIsSelected()
            scroll(feedLanguageTag(scripture)).assertTextContains("Церковнославянский текст пока не добавлен", substring = true)
            compose.runOnIdle { assertTrue(h.original); assertEquals(0, h.writes) }
        }

    @Test fun nonTextPositionsStayOnTheirContinuationUntilAnExplicitOldLineIsChosen() =
        withCiFailureDiagnostics("feed.continuationsAreNotLines") {
            val preparation = atSegment("preparation", PlaybackSegment.Wait::class.java)
            val h = launch(preparation)
            compose.onNodeWithTag("feed_continuation_preparation").assertIsDisplayed()
            compose.onNodeWithTag("before_start").assertIsDisplayed()
            compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
            compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
            unchanged(h, preparation, 0)
            val cases = listOf(atSegment("great_litany", PlaybackSegment.Wait::class.java),
                atSegment("epistle", PlaybackSegment.MissingReading::class.java, "2026-11-09"),
                atSegment("dismissal", PlaybackSegment.End::class.java))
            for (initial in cases) {
                compose.runOnIdle { assertTrue(h.player.accept(initial, false)) }
                compose.onNodeWithTag("feed_continuation_${initial.stage.id}").assertIsDisplayed()
                compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
                val before = h.current
                val writes = h.writes
                compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown() }
                unchanged(h, before, writes)
                compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
                val oldLine = requireNotNull(before.track).lines.last()
                positionAtLine(h, oldLine).assertIsNotSelected()
                compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
                compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
                unchanged(h, before, writes)
                compose.onFeedLine(oldLine).performClick()
                compose.onFeedLine(oldLine).assertIsSelected().assertIsDisplayed()
                compose.runOnIdle { assertEquals(oldLine.id, h.current.text?.lineId); assertTrue(h.current.segment is PlaybackSegment.Text); assertFalse(h.current.state.isPlaying) }
                compose.openReaderControls()
                compose.readerControl("next_line").assertIsNotEnabled()
                compose.dismissReaderControls()
            }
        }

    @Test fun oldLineReturnAndAdjacentCallbacksCannotTouchTheReplacementStateOrViewport() =
        withCiFailureDiagnostics("feed.staleLineAndReturn") {
            val h = launch(reading("creed", trackIndex = 1, lineIndex = 12, offset = 1_234))
            val oldLine = action(compose.onFeedLine(requireNotNull(h.current.track), 12))
            compose.openReaderControls()
            val oldNext = action(compose.readerControl("next_line").assertIsEnabled())
            val oldPrevious = action(compose.readerControl("previous_line").assertIsEnabled())
            compose.dismissReaderControls()
            scroll(feedLineTag(h.current.tracks[0].lines[0]))
            val oldReturn = action(returnButton())
            compose.runOnIdle { assertTrue(h.player.accept(reading("creed", trackIndex = 1, lineIndex = 12,
                offset = 2_345, date = "2026-11-09"), true)) }
            // Same canonical line ID, but new owner/date. Complete replacement recomposition first.
            compose.onFeedLine(requireNotNull(h.current.track), 12).assertIsSelected().assertIsDisplayed()
            play()
            val away = h.current.tracks[0].lines[0]
            positionAtLine(h, away).assertIsDisplayed()
            returnButton().assertIsDisplayed()
            compose.runOnIdle { assertTrue(h.current.state.isPlaying) }
            val accepted = h.current
            val writes = h.writes
            val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
            compose.runOnIdle { oldLine(); oldReturn(); oldNext(); oldPrevious() }
            compose.onFeedLine(away).assertIsDisplayed()
            assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
            returnButton().assertIsDisplayed()
            compose.onNodeWithTag("reader_status").assertTextEquals("Движение по времени · примерно")
            compose.runOnIdle { assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying) }
        }

    @Test fun aRealEarlyDragCancelsInitialFocusAndStillOffersReturn() =
        withCiFailureDiagnostics("feed.earlyDragCancelsInitialFocus") {
            val initial = reading("creed", trackIndex = 1, lineIndex = 19, offset = 1_234)
            val h = Harness(initial)
            val shown = mutableStateOf(false)
            render(h, shown = shown)
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { shown.value = true }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
                val selected = requireNotNull(initial.track).lines[19]
                // Witness that the first focus has not reached its target before the gesture.
                compose.onFeedLine(selected).assertIsNotDisplayed()
                compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp() }
                compose.mainClock.advanceTimeBy(1_000)
                compose.mainClock.autoAdvance = true
                compose.onFeedLine(selected).assertIsNotDisplayed()
                returnButton().performClick()
                compose.onFeedLine(selected).assertIsSelected().assertIsDisplayed()
                unchanged(h, initial, 0)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    @Test fun normalNarrowFooterKeepsItsReadingBoundsWhenReturnReplacesALongNextLabel() =
        withCiFailureDiagnostics("feed.normalReturnGeometry") { checkReturnGeometry(fontScale = 1.3f, height = 800) }

    @Test fun compactFooterKeepsItsReadingBoundsAndPrimaryWhenReturnAppears() =
        withCiFailureDiagnostics("feed.compactReturnGeometry") { checkReturnGeometry(fontScale = 2.6f, height = 640) }

    @Test fun measuredLanguageShrinkKeepsTheBrowsedCanonicalLineWithoutAcceptingIt() =
        withCiFailureDiagnostics("feed.measuredLanguageShrink") {
            val initial = syntheticShrinkReading()
            val h = launch(initial, height = 800)
            val measured = browseMeasuredShrink(h)
            compose.onNodeWithTag("player_text_tab").performClick()
            val russian = compose.onFeedLine(measured.line).assertIsDisplayed().assertIsNotSelected()
                .assertTextEquals(measured.line.russian)
            assertEquals(measured.shortHeight, russian.getUnclippedBoundsInRoot().height)
            assertTrue("The former offset must exceed the newly measured item including its spacing",
                measured.oldOffset > measured.shortExtent)
            compose.onFeedLine(requireNotNull(initial.track), 0).assertIsNotDisplayed()
            returnButton()
            unchanged(h, initial, 0)
            compose.mainClock.advanceTimeBy(1_000)
            russian.assertIsDisplayed().assertIsNotSelected()
            unchanged(h, initial, 0)
        }

    @Test fun newDragAndReplacementCancelPendingLanguageRestoreBeforeItsFrame() =
        withCiFailureDiagnostics("feed.cancelPendingLanguageRestore") {
            val initial = syntheticShrinkReading()
            val h = launch(initial, height = 800)
            val measured = browseMeasuredShrink(h)
            val russianAction = action(compose.onNodeWithTag("player_text_tab"))
            compose.mainClock.autoAdvance = false
            try {
                compose.mainClock.advanceTimeByFrame()
                val beforeRequest = compose.mainClock.currentTime
                compose.runOnIdle { russianAction(); assertFalse(h.original) }
                // InputDispatcher advances the clock per input event even with autoAdvance=false.
                // Send a real delta before the next frame, then cancel without adding a fling.
                compose.onNodeWithTag("lyrics_list").performTouchInput {
                    down(Offset(centerX, height * .75f))
                    moveTo(Offset(centerX, height * .35f), delayMillis = 1)
                    cancel(delayMillis = 1)
                }
                assertTrue("New drag must reach the reader before the restore frame",
                    compose.mainClock.currentTime - beforeRequest < 16)
                compose.mainClock.advanceTimeBy(1_000)
                compose.mainClock.autoAdvance = true
                compose.onFeedLine(measured.line).assertIsNotDisplayed()
                compose.onFeedLine(requireNotNull(initial.track), 0).assertIsNotDisplayed()
                returnButton()
                unchanged(h, initial, 0)

                // A second request is replaced before a frame, with the same canonical line IDs.
                val again = browseMeasuredShrink(h)
                val replacement = syntheticShrinkReading("2026-11-09", offset = 2_345)
                assertEquals(again.line.id, replacement.tracks[1].lines[2].id)
                val nextRussianAction = action(compose.onNodeWithTag("player_text_tab"))
                compose.mainClock.autoAdvance = false
                compose.mainClock.advanceTimeByFrame()
                val beforeReplacement = compose.mainClock.currentTime
                compose.runOnIdle {
                    nextRussianAction()
                    assertFalse(h.original)
                    assertTrue(h.player.accept(replacement, true))
                }
                assertEquals(beforeReplacement, compose.mainClock.currentTime)
                compose.mainClock.advanceTimeBy(1_000)
                compose.mainClock.autoAdvance = true
                compose.onFeedLine(requireNotNull(replacement.track), 0).assertIsSelected().assertIsDisplayed()
                compose.onFeedLine(again.line).assertIsNotDisplayed()
                compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
                unchanged(h, replacement, 1)
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    private data class ShrinkGeometry(val line: PlayerLine, val shortHeight: Dp, val shortExtent: Dp, val oldOffset: Dp)

    /** Synthetic long/short pair, explicitly isolated from published content and the engine's timing rules. */
    private fun syntheticShrinkReading(date: String = "2026-11-08", offset: Long = 1_234): ActiveServicePlayback {
        val packaged = plan(date)
        val stages = packaged.stages.map { stage ->
            if (stage.id != "communion") stage else stage.copy(texts = stage.texts.mapIndexed { index, text ->
                if (index != 1) text else {
                    val lines = text.lines.mapIndexed { lineIndex, line ->
                        if (lineIndex != 2) line else line.copy(original = "Длинный текст для проверки прокрутки. ".repeat(24), translation = "Кратко")
                    }
                    text.copy(lines = lines, original = lines.joinToString("\n") { it.original },
                        translation = lines.joinToString("\n") { it.translation })
                }
            })
        }
        val plan = ServicePlaybackPlan.build(stages, packaged.serviceDate,
            container.readings?.days?.singleOrNull { it.date == date })
        return ActiveServicePlayback(plan, context(plan.serviceDate),
            ServicePlaybackEngine(plan).start("communion").copy(elapsedQuarterMillis = offset))
    }

    private fun browseMeasuredShrink(h: Harness): ShrinkGeometry {
        val track = h.current.tracks[1]
        val line = track.lines[2]
        assertNull(line.speaker)
        assertNull(track.lines[3].speaker)
        val index = requireNotNull(ReaderFeed(h.current.stage.id, h.current.tracks).lineIndex(track.id, line.id))
        fun positionAtStart() {
            compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(index)) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("player_text_tab").performClick()
        positionAtStart()
        val short = compose.onFeedLine(line).assertIsDisplayed().assertTextEquals(line.russian).getUnclippedBoundsInRoot()
        val next = compose.onFeedLine(track, 3).assertIsDisplayed().getUnclippedBoundsInRoot()
        val shortExtent = next.top - short.top // Actual item height plus actual inter-item spacing.
        assertTrue(shortExtent > short.height)
        compose.onNodeWithTag("player_original_tab").performClick()
        positionAtStart()
        val original = compose.onFeedLine(line).assertIsDisplayed().assertTextEquals(requireNotNull(line.original)).getUnclippedBoundsInRoot()
        assertTrue("This synthetic pair must produce a measured height reduction", original.height > shortExtent * 3f)
        compose.onNodeWithTag("lyrics_list").performTouchInput {
            swipeUp(startY = height * .8f, endY = height * .2f, durationMillis = 1_000)
        }
        val browsed = compose.onFeedLine(line).assertIsDisplayed().assertIsNotSelected().getUnclippedBoundsInRoot()
        val oldOffset = original.top - browsed.top
        assertTrue("Real drag must put the old offset beyond the short item and its spacing", oldOffset > shortExtent)
        assertTrue("The long original must still be the first visible item", oldOffset < original.height)
        return ShrinkGeometry(line, short.height, shortExtent, oldOffset)
    }

    private fun checkReturnGeometry(fontScale: Float, height: Int) {
        val h = launch(reading("lords_prayer", offset = 1_234), fontScale = fontScale, height = height)
        val before = h.current
        val track = requireNotNull(before.track)
        val viewport = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        val primary = compose.onNodeWithTag("play_lyrics").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("reader_controls").assertIsDisplayed()
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed()
        scroll(feedLineTag(track.lines.last()))
        returnButton()
        assertEquals(viewport, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, compose.onNodeWithTag("play_lyrics").getUnclippedBoundsInRoot())
        compose.mainClock.advanceTimeBy(1_000)
        returnButton()
        assertEquals(viewport, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        returnButton().performClick()
        compose.onFeedLine(track, 0).assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("return_to_phrase").assertDoesNotExist()
        assertEquals(viewport, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, compose.onNodeWithTag("play_lyrics").getUnclippedBoundsInRoot())
        unchanged(h, before, 0)
    }

    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L
        var writable = true
        var writes = 0
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { _, _, _ -> writes++; writable })
        val current get() = requireNotNull(player.reading)
    }

    private fun launch(initial: ActiveServicePlayback, fontScale: Float = 1f, height: Int = 640): Harness {
        val h = Harness(initial)
        render(h, fontScale, height)
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        return h
    }

    private fun render(h: Harness, fontScale: Float = 1f, height: Int = 640, shown: State<Boolean>? = null) {
        compose.setContent {
            val density = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 320; screenHeightDp = height }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) { Box(Modifier.size(320.dp, height.dp)) {
                    if (shown?.value != false) ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {},
                        onFindStage = {}, onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {},
                        onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original, onOriginalChange = { h.original = it })
                } }
            }
        }
    }

    private fun plan(date: String = "2026-11-08") = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(),
        LocalDate.parse(date), container.readings?.days?.singleOrNull { it.date == date })
    private fun context(date: LocalDate) = ReadingContext("selected-test-church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private fun reading(stageId: String, trackIndex: Int = 0, lineIndex: Int = 0, offset: Long = 0, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date)
        val track = plan.tracksByStage.getValue(stageId)[trackIndex]
        val engine = ServicePlaybackEngine(plan)
        val state = engine.seekLine(engine.start(stageId, PlaybackSpeed.NORMAL), track.id, track.lines[lineIndex].id)
            .copy(elapsedQuarterMillis = offset)
        return ActiveServicePlayback(plan, context(plan.serviceDate), state)
    }
    private fun atSegment(stageId: String, type: Class<out PlaybackSegment>, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date)
        val index = plan.segments.indexOfFirst { it.stageId == stageId && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, context(plan.serviceDate), ServicePlaybackEngine(plan).start(stageId).copy(segmentIndex = index))
    }
    private fun seek(reading: ActiveServicePlayback, line: PlayerLine): ActiveServicePlayback {
        val track = reading.tracks.single { line in it.lines }
        return reading.copy(state = reading.engine.seekLine(reading.state, track.id, line.id))
    }
    private fun play(waiting: Boolean = false) {
        compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals(if (waiting) "Продолжить по времени" else "Двигать текст").performClick()
        compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals("Пауза")
    }
    private fun returnButton(): SemanticsNodeInteraction {
        val button = compose.onNodeWithTag("return_to_phrase").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val visible = button.getBoundsInRoot()
        val full = button.getUnclippedBoundsInRoot()
        assertTrue("Return must be fully visible", visible.left <= full.left + .5.dp && visible.top <= full.top + .5.dp &&
            visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
        return button
    }
    private fun scroll(tag: String, parent: String = "lyrics_list"): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun positionAtLine(h: Harness, line: PlayerLine): SemanticsNodeInteraction {
        val track = h.current.tracks.single { line in it.lines }
        val index = requireNotNull(ReaderFeed(h.current.stage.id, h.current.tracks).lineIndex(track.id, line.id))
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(index)) }
        compose.waitForIdle()
        return compose.onFeedLine(line).assertIsDisplayed()
    }
    private fun action(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun unchanged(h: Harness, before: ActiveServicePlayback, writes: Int) = compose.runOnIdle {
        assertEquals(before, h.current)
        assertEquals(writes, h.writes)
        assertFalse(h.current.state.isPlaying)
    }
}
