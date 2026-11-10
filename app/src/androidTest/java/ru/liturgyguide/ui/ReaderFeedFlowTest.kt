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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

/** Whole-book regression: real manual input accepts the visible place; it never returns to an obsolete selection. */
@RunWith(AndroidJUnit4::class)
class ReaderFeedFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)

    @Test fun browsingAnotherTextAcceptsItsVisiblePlaceAndShowsItsMeaning() = withCiFailureDiagnostics("feed.browseAndMeaning") {
        val h = launch(reading("anaphora", lineIndex = 1, offset = 1_234))
        val before = h.current
        val other = before.plan.tracksByStage.getValue("lords_prayer").first()
        browse(h, other.lines.first())
        compose.onFeedLine(other, 0).assertIsSelected().assertIsDisplayed()
        compose.selectReaderMode("text")
        compose.onFeedLine(other, 0).assertTextEquals(other.lines.first().russian)
        compose.selectReaderMode("original")
        compose.onFeedLine(other, 0).assertTextEquals(requireNotNull(other.lines.first().original))
        compose.selectReaderMode("meaning")
        compose.onNodeWithTag("track_explanation").performScrollTo().assertTextEquals(other.explanation)
        compose.runOnIdle {
            assertEquals(other.id, h.current.text?.trackId); assertEquals(other.lines.first().id, h.current.text?.lineId)
            assertEquals(0L, h.current.state.elapsedQuarterMillis); assertFalse(h.current.state.isPlaying)
            assertEquals(before.context, h.current.context); assertEquals(before.state.speed, h.current.state.speed)
            assertEquals(0, h.player.generation)
        }
        compose.onNodeWithTag("return_to_text").performClick()
        compose.onFeedLine(other, 0).assertIsSelected().assertIsDisplayed()
    }

    @Test fun realDragMaterializesATrackBoundaryThenSavesManualReadingWithoutFollowingBack() = withCiFailureDiagnostics("feed.dragAcrossTrackBoundary") {
        val initial = reading("anaphora")
        val h = launch(seek(initial, initial.tracks.first().lines.last()))
        val text = h.current.segment as PlaybackSegment.Text
        val next = h.current.plan.segments[h.current.state.segmentIndex + 1] as PlaybackSegment.Text
        assertNotEquals(text.trackId, next.trackId)
        play()
        compose.runOnIdle { h.now += text.durationBaseMillis + 123 }
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown() }
        settle()
        compose.runOnIdle {
            val materializedPause = h.attempts.first { it.segment == next && !it.state.isPlaying }
            assertEquals(492L, materializedPause.state.elapsedQuarterMillis)
            assertEquals(initial.context, h.current.context); assertFalse(h.current.state.isPlaying)
        }
        assertManualFrozen(h)
    }

    @Test fun realDragAtAStageBoundaryKeepsOneBookAndAcceptsTheVisibleStage() = withCiFailureDiagnostics("feed.dragAcrossStageBoundary") {
        val initial = atSegment("anaphora", PlaybackSegment.Wait::class.java)
        val wait = initial.segment as PlaybackSegment.Wait
        val next = initial.plan.segments[initial.state.segmentIndex + 1] as PlaybackSegment.Text
        val h = launch(initial)
        play()
        compose.runOnIdle { h.now += wait.durationBaseMillis + 123 }
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeDown() }
        settle()
        compose.runOnIdle {
            assertTrue(h.attempts.any { it.segment == next && it.state.elapsedQuarterMillis == 492L && !it.state.isPlaying })
            assertEquals(initial.context, h.current.context); assertFalse(h.current.state.isPlaying); assertEquals(0, h.player.generation)
        }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(h.current.stage.name)
        assertManualFrozen(h)
        // Earlier and later parts remain reachable in the same book after the boundary.
        choose(h, initial.plan.tracksByStage.getValue("creed").single { it.title == "Символ веры" }.lines.first())
        compose.runOnIdle { assertEquals("creed", h.current.stage.id); assertEquals(0, h.player.generation) }
    }

    @Test fun aPartialLongParagraphRemainsTheSameCanonicalPlaceDuringManualScrolling() = withCiFailureDiagnostics("feed.partialLongText") {
        val base = syntheticShrinkReading()
        val longLine = base.tracks.single { it.title == "Верую, Господи, и исповедую" }.lines[2]
        val initial = seek(base, longLine).let { it.copy(state = it.state.copy(elapsedQuarterMillis = 1_234)) }
        val h = launch(initial, fontScale = 2f)
        val viewport = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        val before = compose.onFeedLine(longLine).assertIsSelected().getUnclippedBoundsInRoot()
        assertTrue(before.bottom - before.top > viewport.bottom - viewport.top)
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .60f) }
        settle()
        val after = compose.onFeedLine(longLine).assertIsDisplayed().assertIsSelected().getUnclippedBoundsInRoot()
        assertTrue(after.top < before.top)
        unchanged(h, initial, 0)
    }

    @Test fun playFromAnotherPartStartsAtTheVisibleAcceptedPhrase() = withCiFailureDiagnostics("feed.playFromVisiblePlace") {
        val h = launch(reading("creed", title = "Символ веры", lineIndex = 12, offset = 1_234))
        val before = h.current
        val away = before.plan.tracksByStage.getValue("lords_prayer").first().lines.first()
        browse(h, away)
        play()
        compose.onFeedLine(away).assertIsSelected().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(away.id, h.current.text?.lineId); assertEquals(0L, h.current.state.elapsedQuarterMillis)
            assertEquals(before.context, h.current.context); assertEquals(before.state.speed, h.current.state.speed); assertTrue(h.current.state.isPlaying)
            h.now += 101
        }
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertEquals(404L, h.current.state.elapsedQuarterMillis); assertFalse(h.current.state.isPlaying) }
    }

    @Test fun aRejectedLineChoiceCannotTurnBoundaryPauseIntoSuccessfulAcceptance() = withCiFailureDiagnostics("feed.failedWriterNoFocus") {
        val initial = reading("anaphora")
        val h = launch(seek(initial, initial.tracks[1].lines.last()))
        play()
        val old = h.current.segment as PlaybackSegment.Text
        val next = h.current.plan.segments[h.current.state.segmentIndex + 1] as PlaybackSegment.Text
        assertNotEquals(old.trackId, next.trackId)
        val requested = initial.tracks.last().lines.last()
        position(h, requested)
        val browsedBounds = compose.onFeedLine(requested).getUnclippedBoundsInRoot()
        val successes = h.successes.size
        compose.runOnIdle { assertTrue(h.current.state.isPlaying); h.now += old.durationBaseMillis + 123; h.writable = false }
        compose.onFeedLine(requested).performClick()
        settle()
        compose.onFeedLine(requested).assertIsDisplayed().assertIsNotSelected()
        assertEquals(browsedBounds, compose.onFeedLine(requested).getUnclippedBoundsInRoot())
        compose.onNodeWithText("Не удалось сохранить место. Попробуйте ещё раз").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(next, h.current.segment); assertEquals(492L, h.current.state.elapsedQuarterMillis)
            assertFalse(h.current.state.isPlaying); assertEquals(successes, h.successes.size)
            assertTrue(h.attempts.any { it.text?.lineId == requested.id })
        }
        assertManualFrozen(h)
    }

    @Test fun choosingTheSameCanonicalLineKeepsItsOffsetAndDoesNotWriteOrReplaceTheOwner() = withCiFailureDiagnostics("feed.sameLineExplicitFocus") {
        val h = launch(reading("creed", title = "Символ веры", lineIndex = 12, offset = 1_234))
        val before = h.current; val focus = h.player.focusRequest
        val line = requireNotNull(before.track).lines[12]
        compose.onFeedLine(line).performClick()
        compose.onFeedLine(line).assertIsSelected().assertIsDisplayed()
        unchanged(h, before, 0)
        compose.runOnIdle { assertEquals(focus, h.player.focusRequest); assertEquals(0, h.player.generation) }
    }

    @Test fun rejectedPlayAndLineChoiceKeepTheAcceptedPositionAndTheBrowsedText() = withCiFailureDiagnostics("feed.rejectedPlayAndLineChoice") {
        val h = launch(reading("creed", title = "Символ веры", lineIndex = 12, offset = 1_234))
        val before = h.current; val focus = h.player.focusRequest
        val away = before.plan.tracksByStage.getValue("creed").first().lines.first()
        compose.runOnIdle { h.writable = false }
        val acceptedBounds = compose.onFeedLine(requireNotNull(before.track).lines[before.lineIndex]).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle {
            assertTrue(h.attempts.any { it.state.isPlaying })
            assertEquals(before, h.current)
            assertEquals(0, h.successes.size)
        }
        assertEquals(acceptedBounds, compose.onFeedLine(requireNotNull(before.track).lines[before.lineIndex]).getUnclippedBoundsInRoot())
        browse(h, away)
        val browsedBounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onFeedLine(away).assertIsDisplayed().assertIsNotSelected()
        assertEquals(browsedBounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
        compose.onFeedLine(away).performClick()
        settle()
        compose.onFeedLine(away).assertIsDisplayed().assertIsNotSelected()
        assertEquals(browsedBounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(before, h.current); assertFalse(h.current.state.isPlaying); assertEquals(0, h.successes.size)
            assertTrue(h.writes >= 2); assertEquals(focus, h.player.focusRequest)
        }
    }

    @Test fun scriptureUsesRussianAndManualReturnToPrayerRestoresThePreferredOriginal() = withCiFailureDiagnostics("feed.mixedScripture") {
        val plan = plan()
        val scripture = plan.tracksByStage.getValue("gospel").first { it.isScripture }
        val initial = ActiveServicePlayback(plan, context(plan.serviceDate), ServicePlaybackEngine(plan).seekLine(ServicePlaybackEngine(plan).start("gospel"), scripture.id, scripture.lines[3].id))
        val h = launch(initial)
        compose.assertReaderMode("original", enabled = false)
        compose.assertReaderMode("text", selected = true)
        compose.onNodeWithTag("scripture_language_notice").assertIsDisplayed()
        compose.onFeedLine(scripture, 3).assertTextEquals(scripture.lines[3].russian).assertIsSelected()
        val prayer = initial.tracks.first { !it.isScripture }
        choose(h, prayer.lines.first())
        compose.assertReaderMode("original", selected = true, enabled = true)
        compose.onFeedLine(prayer, 0).assertTextEquals(requireNotNull(prayer.lines.first().original)).assertIsSelected()
        compose.selectReaderMode("meaning")
        compose.onNodeWithTag("track_explanation").performScrollTo().assertTextEquals(prayer.explanation)
        compose.runOnIdle { assertTrue(h.original); assertEquals(initial.context, h.current.context); assertEquals(initial.state.speed, h.current.state.speed) }
    }

    @Test fun aScriptureOnlyFixtureDoesNotAdvertiseAnOriginalItDoesNotHave() = withCiFailureDiagnostics("feed.scriptureOnly") {
        val full = plan()
        val only = ServicePlaybackPlan.build(full.stages.map { if (it.id == "gospel") it.copy(texts = emptyList()) else it }, full.serviceDate,
            container.readings!!.days.single { it.date == full.serviceDate.toString() })
        val initial = ActiveServicePlayback(only, context(only.serviceDate), ServicePlaybackEngine(only).start("gospel"))
        val h = launch(initial)
        val scripture = requireNotNull(h.current.track)
        assertTrue(h.current.tracks.all { it.isScripture })
        compose.assertReaderMode("original", enabled = false)
        compose.assertReaderMode("text", selected = true)
        compose.onFeedLine(scripture, 0).assertTextEquals(scripture.lines.first().russian).assertIsSelected()
        compose.onNodeWithTag("scripture_language_notice").assertIsDisplayed()
        compose.runOnIdle { assertTrue(h.original); assertEquals(0, h.writes) }
    }

    @Test fun nonTextPositionsDoNotAdvanceUntilPlaybackOrAnExplicitBookChoice() = withCiFailureDiagnostics("feed.nonTextAndManualChoice") {
        val preparation = atSegment("preparation", PlaybackSegment.Wait::class.java)
        val h = launch(preparation)
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag("start_liturgy"))
        compose.onNodeWithTag("start_liturgy").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
        unchanged(h, preparation, 0)
        val cases = listOf(atSegment("great_litany", PlaybackSegment.Wait::class.java), atSegment("epistle", PlaybackSegment.MissingReading::class.java, "2026-11-09"), atSegment("dismissal", PlaybackSegment.End::class.java))
        for (initial in cases) {
            compose.runOnIdle { assertTrue(h.player.accept(initial, false)) }
            val writes = h.writes
            compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
            unchanged(h, initial, writes)
            val line = initial.plan.tracksByStage.getValue(initial.stage.id).first().lines.first()
            choose(h, line)
            compose.runOnIdle {
                assertEquals(line.id, h.current.text?.lineId); assertTrue(h.current.segment is PlaybackSegment.Text)
                assertFalse(h.current.state.isPlaying); assertEquals(initial.context, h.current.context); assertEquals(initial.state.speed, h.current.state.speed)
            }
        }
    }

    @Test fun oldLinePlaybackAndModeCallbacksCannotTouchTheReplacementStateOrViewport() = withCiFailureDiagnostics("feed.staleBookActions") {
        val h = launch(reading("creed", title = "Символ веры", lineIndex = 12, offset = 1_234))
        val oldActions = listOf(action(compose.onFeedLine(requireNotNull(h.current.track), 12)), action(compose.onNodeWithTag("play_lyrics")), action(compose.onNodeWithTag("player_text_tab")))
        // Keep the callback stale: after recomposition Compose may reuse its
        // ClickableNode and the semantics wrapper would invoke a new action.
        val (accepted, writes) = compose.runOnIdle {
            assertTrue(h.player.accept(reading("creed", title = "Символ веры", lineIndex = 12, offset = 2_345, date = "2026-11-09"), true))
            assertTrue(h.player.play())
            val accepted = h.current; val writes = h.writes
            oldActions.forEach { it() }
            assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying); assertTrue(h.original)
            accepted to writes
        }
        compose.waitForIdle()
        compose.onFeedLine(requireNotNull(h.current.track), 12).assertIsSelected().assertIsDisplayed()
        val away = h.current.plan.tracksByStage.getValue("lords_prayer").first().lines.first()
        position(h, away)
        compose.waitForIdle()
        val bounds = compose.onFeedLine(away).getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(bounds, compose.onFeedLine(away).getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(accepted, h.current); assertEquals(writes, h.writes); assertTrue(h.current.state.isPlaying) }
    }

    @Test fun aRealEarlyDragCancelsTimedFollowingAndPreventsLaterMotion() = withCiFailureDiagnostics("feed.earlyDragCancelsTimedFocus") {
        val initial = atSegment("anaphora", PlaybackSegment.Wait::class.java)
        val h = launch(initial)
        play()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { h.now += (initial.segment as PlaybackSegment.Wait).durationBaseMillis + 123; h.player.tick(h.player.generation) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("lyrics_list").performTouchInput {
                down(Offset(centerX, height * .7f)); moveTo(Offset(centerX, height * .4f), delayMillis = 1); cancel(delayMillis = 1)
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.mainClock.autoAdvance = true
            settle()
            compose.runOnIdle { assertFalse(h.current.state.isPlaying); assertEquals(initial.context, h.current.context) }
            assertManualFrozen(h)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun narrowFooterKeepsItsBoundsWhenTheReaderChoosesAnotherPhrase() = withCiFailureDiagnostics("feed.normalBookGeometry") { checkGeometry(1.3f, 800) }
    @Test fun compactFooterKeepsItsBoundsWhenTheReaderChoosesAnotherPhrase() = withCiFailureDiagnostics("feed.compactBookGeometry") { checkGeometry(2.6f, 640) }

    @Test fun measuredLanguageShrinkKeepsTheManuallyAcceptedCanonicalLine() = withCiFailureDiagnostics("feed.measuredLanguageShrink") {
        val h = launch(syntheticShrinkReading(), height = 800)
        val measured = browseMeasuredShrink(h)
        val accepted = h.current
        compose.selectReaderMode("text")
        val russian = compose.onFeedLine(measured.line).assertIsDisplayed().assertIsSelected().assertTextEquals(measured.line.russian)
        val bounds = russian.getUnclippedBoundsInRoot()
        val onePhysicalPixelDp = 1f / InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals(measured.shortHeight.value, (bounds.bottom - bounds.top).value, onePhysicalPixelDp)
        assertTrue("The old offset exceeds the newly measured paragraph", measured.oldOffset > measured.shortExtent)
        compose.runOnIdle { assertEquals(accepted, h.current); assertFalse(h.current.state.isPlaying) }
        compose.mainClock.advanceTimeBy(1_000)
        russian.assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { assertEquals(accepted, h.current) }
    }

    @Test fun aNewGestureAndReplacementCancelPendingLanguageRestoreBeforeItsFrame() = withCiFailureDiagnostics("feed.cancelPendingLanguageRestore") {
        val h = launch(syntheticShrinkReading(), height = 800)
        val measured = browseMeasuredShrink(h)
        val russianAction = action(compose.onNodeWithTag("player_text_tab"))
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeByFrame()
            val beforeRequest = compose.mainClock.currentTime
            compose.runOnIdle { russianAction(); assertFalse(h.original) }
            compose.onNodeWithTag("lyrics_list").performTouchInput {
                down(Offset(centerX, height * .75f)); moveTo(Offset(centerX, height * .35f), delayMillis = 1); cancel(delayMillis = 1)
            }
            assertTrue("The user gesture must precede the language restore frame", compose.mainClock.currentTime - beforeRequest < 16)
            compose.mainClock.advanceTimeBy(1_000); compose.mainClock.autoAdvance = true
            settle()
            compose.onFeedLine(measured.line).assertIsNotDisplayed()
            assertManualFrozen(h)

            val again = browseMeasuredShrink(h)
            val replacement = syntheticShrinkReading("2026-11-09", offset = 2_345)
            assertEquals(again.line.id, replacement.tracks.single { it.title == "Верую, Господи, и исповедую" }.lines[2].id)
            val nextRussianAction = action(compose.onNodeWithTag("player_text_tab"))
            compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeByFrame()
            val beforeReplacement = compose.mainClock.currentTime
            compose.runOnIdle { nextRussianAction(); assertFalse(h.original); assertTrue(h.player.accept(replacement, true)) }
            assertEquals(beforeReplacement, compose.mainClock.currentTime)
            compose.mainClock.advanceTimeBy(1_000); compose.mainClock.autoAdvance = true
            compose.onFeedLine(requireNotNull(replacement.track), 0).assertIsSelected().assertIsDisplayed()
            compose.onFeedLine(again.line).assertIsNotDisplayed()
            compose.runOnIdle { assertEquals(replacement, h.current); assertFalse(h.current.state.isPlaying) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private data class ShrinkGeometry(val line: PlayerLine, val shortHeight: Dp, val shortExtent: Dp, val oldOffset: Dp)
    /** Synthetic geometry isolates the large-to-small translation case from packaged prayer content. */
    private fun syntheticShrinkReading(date: String = "2026-11-08", offset: Long = 1_234): ActiveServicePlayback {
        val packaged = plan(date)
        val stages = packaged.stages.map { stage -> if (stage.id != "communion") stage else stage.copy(texts = stage.texts.map { text ->
            if (text.title != "Верую, Господи, и исповедую") text else {
                val lines = text.lines.mapIndexed { index, line -> if (index != 2) line else line.copy(original = "Длинный текст для проверки прокрутки. ".repeat(24), translation = "Кратко") }
                text.copy(lines = lines, original = lines.joinToString("\n") { it.original }, translation = lines.joinToString("\n") { it.translation })
            }
        }) }
        val plan = ServicePlaybackPlan.build(stages, packaged.serviceDate, container.readings?.days?.singleOrNull { it.date == date })
        return ActiveServicePlayback(plan, context(plan.serviceDate), ServicePlaybackEngine(plan).start("communion").copy(elapsedQuarterMillis = offset))
    }
    private fun browseMeasuredShrink(h: Harness): ShrinkGeometry {
        val track = h.current.plan.tracksByStage.getValue("communion").single { it.title == "Верую, Господи, и исповедую" }
        val line = track.lines[2]
        compose.selectReaderMode("text"); position(h, line)
        val short = compose.onFeedLine(line).getUnclippedBoundsInRoot()
        val next = compose.onFeedLine(track, 3).assertIsDisplayed().getUnclippedBoundsInRoot()
        val shortExtent = next.top - short.top
        compose.selectReaderMode("original"); position(h, line)
        val original = compose.onFeedLine(line).getUnclippedBoundsInRoot()
        assertTrue("This pair must really shrink", original.bottom - original.top > shortExtent * 3f)
        compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .8f, endY = height * .2f, durationMillis = 1_000) }
        settle()
        val browsed = compose.onFeedLine(line).assertIsDisplayed().assertIsSelected().getUnclippedBoundsInRoot()
        val oldOffset = original.top - browsed.top
        assertTrue(oldOffset > shortExtent)
        assertTrue(oldOffset < original.bottom - original.top)
        return ShrinkGeometry(line, short.bottom - short.top, shortExtent, oldOffset)
    }
    private fun checkGeometry(scale: Float, height: Int) {
        val h = launch(reading("lords_prayer", offset = 1_234), scale, height)
        val before = h.current
        val list = compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        val primary = compose.onNodeWithTag("play_lyrics").assertHeightIsAtLeast(48.dp).getBoundsInRoot()
        val microphone = compose.onNodeWithTag("reader_microphone").assertHeightIsAtLeast(48.dp).getBoundsInRoot()
        val line = before.tracks.last().lines.last()
        choose(h, line)
        assertEquals(list, compose.onNodeWithTag("lyrics_list").getBoundsInRoot())
        assertEquals(primary, compose.onNodeWithTag("play_lyrics").getBoundsInRoot())
        assertEquals(microphone, compose.onNodeWithTag("reader_microphone").getBoundsInRoot())
        compose.runOnIdle { assertEquals(line.id, h.current.text?.lineId); assertEquals(before.context, h.current.context); assertEquals(before.state.speed, h.current.state.speed) }
        assertManualFrozen(h)
    }
    private class Harness(initial: ActiveServicePlayback) {
        var now = 1_000L; var writable = true; var writes = 0
        val attempts = mutableListOf<ActiveServicePlayback>(); val successes = mutableListOf<ActiveServicePlayback>()
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { now }, ServicePlaybackWriter { value, _, _ -> writes++; attempts += value; if (writable) successes += value; writable })
        val current get() = requireNotNull(player.reading)
    }
    private fun launch(initial: ActiveServicePlayback, fontScale: Float = 1f, height: Int = 640): Harness {
        val h = Harness(initial)
        compose.setContent {
            val density = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 320; screenHeightDp = height }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) { Box(Modifier.size(320.dp, height.dp)) {
                    ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {}, onFindStage = {}, onSettings = {},
                        dark = false, onDarkChange = {}, onOpenSource = {}, onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original, onOriginalChange = { h.original = it })
                } }
            }
        }
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        return h
    }
    private fun plan(date: String = "2026-11-08") = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(date), container.readings?.days?.singleOrNull { it.date == date })
    private fun context(date: LocalDate) = ReadingContext("selected-test-church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private fun reading(stageId: String, title: String? = null, lineIndex: Int = 0, offset: Long = 0, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date); val tracks = plan.tracksByStage.getValue(stageId)
        val track = if (title == null) tracks.first() else tracks.single { it.title == title }
        val engine = ServicePlaybackEngine(plan)
        return ActiveServicePlayback(plan, context(plan.serviceDate), engine.seekLine(engine.start(stageId), track.id, track.lines[lineIndex].id).copy(elapsedQuarterMillis = offset))
    }
    private fun atSegment(stageId: String, type: Class<out PlaybackSegment>, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date); val index = plan.segments.indexOfFirst { it.stageId == stageId && type.isInstance(it) }; check(index >= 0)
        return ActiveServicePlayback(plan, context(plan.serviceDate), ServicePlaybackEngine(plan).start(stageId).copy(segmentIndex = index))
    }
    private fun seek(reading: ActiveServicePlayback, line: PlayerLine): ActiveServicePlayback {
        val track = reading.plan.tracksByStage.values.flatten().single { line in it.lines }
        return reading.copy(state = reading.engine.seekLine(reading.state, track.id, line.id))
    }
    private fun play() { compose.onNodeWithTag("play_lyrics").assertIsEnabled().performClick(); compose.onNodeWithTag("play_lyrics").assert(hasText("Пауза") or hasContentDescription("Пауза")) }
    private fun position(h: Harness, line: PlayerLine) { compose.onNodeWithTag("lyrics_list").performScrollToIndex(requireNotNull(ContinuousServiceFeed(h.current.plan).indexOf("line:${line.id}"))); compose.onFeedLine(line).assertIsDisplayed() }
    private fun choose(h: Harness, line: PlayerLine) { position(h, line); compose.onFeedLine(line).performClick(); compose.onFeedLine(line).assertIsSelected().assertIsDisplayed() }
    private fun browse(h: Harness, line: PlayerLine) { position(h, line); compose.onNodeWithTag("lyrics_list").performTouchInput { swipeUp(startY = height * .75f, endY = height * .73f) }; settle() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(400); compose.waitForIdle() }
    private fun action(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun unchanged(h: Harness, before: ActiveServicePlayback, writes: Int) = compose.runOnIdle { assertEquals(before, h.current); assertEquals(writes, h.writes); assertFalse(h.current.state.isPlaying) }
    private fun assertManualFrozen(h: Harness) {
        val before = h.current; val writes = h.writes
        val scroll = compose.onNodeWithTag("lyrics_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { assertFalse(h.current.state.isPlaying); h.now += 86_400_000; h.player.tick(h.player.generation) }
        compose.mainClock.advanceTimeBy(1_000)
        unchanged(h, before, writes)
        assertEquals(scroll, compose.onNodeWithTag("lyrics_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
    }
}
