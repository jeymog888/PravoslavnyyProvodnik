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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** Real packaged names and text with controlled viewport/font scale, not an OS process-death test. */
@RunWith(AndroidJUnit4::class)
class CompactReaderMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    private val monotonic = AtomicLong(1_000L)
    private var clarified = 0

    @Test
    fun allEighteenPortraitStageNamesRemainWholeWithLargeTextAndAnApproximationNotice() {
        val initial = reading("preparation")
        val player = launch(320, 640, 2.6f, initial)
        assertEquals(18, initial.plan.stages.size)
        initial.plan.stages.forEachIndexed { index, stage ->
            compose.runOnIdle { assertTrue(player.accept(initial.copy(state = initial.engine.start(stage.id)), false)) }
            assertHeader(stage.name, stage.name, index + 1)
            assertReadable(compose.onNodeWithTag("approximate_notice"))
            compose.onNodeWithTag("approximate_notice").assertTextEquals("Примерно").assertHasNoClickAction()
            assertTrackHeader(player)
        }
        compose.runOnIdle { assertTrue(player.accept(initial, false)) }
        compose.onNodeWithTag("current_track_title").assertDoesNotExist()
        compose.onNodeWithTag("clarify_moment").performClick()
        compose.runOnIdle { assertEquals(1, clarified) }
    }

    @Test
    fun allEighteenLandscapeNamesKeepFullAccessibleContextAndReadingSpace() {
        try {
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            val initial = reading("preparation")
            val player = launch(640, 360, 1.69f, initial)
            assertEquals(18, initial.plan.stages.size)
            initial.plan.stages.forEachIndexed { index, stage ->
                compose.runOnIdle { assertTrue(player.accept(initial.copy(state = initial.engine.start(stage.id)), false)) }
                assertHeader(stage.shortTitle, stage.name, index + 1)
                assertReadable(compose.onNodeWithTag("approximate_notice"))
                compose.onNodeWithTag("approximate_notice").assertHasNoClickAction()
                assertTrackHeader(player)
                try {
                    compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
                    compose.onNodeWithTag("service_content").assertHeightIsAtLeast(120.dp)
                } catch (failure: AssertionError) {
                    android.util.Log.e("CompactReaderMatrix", "Stage ${stage.id}: ${compose.onRoot().printToString()}")
                    captureFailure(stage.id)
                    throw failure
                }
                val originalAvailable = player.reading?.track?.lines?.any { it.original != null } == true
                compose.onNodeWithTag(if (originalAvailable) "player_original_tab" else "player_text_tab")
                    .performClick().assertIsSelected()
            }
        } finally {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun epistleAt412DpRetainsTheRequestedClarifyScaleAndTheWholeTitle() {
        val player = launch(412, 800, 1.3f, reading("epistle"))
        assertHeader("Прокимен и чтение Апостола", "Прокимен и чтение Апостола", 7)
        assertTrackHeader(player)
        val clarify = compose.onNodeWithText("Уточнить", useUnmergedTree = true)
        assertReadable(clarify)
        assertEquals(1.3f, textLayout(clarify).layoutInput.density.fontScale, .001f)
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    @Test
    fun longTrackNamesWrapAndLanguageChangesKeepTheCanonicalPhraseAndFullScale() {
        val initial = reading("great_entrance")
        val player = launch(320, 640, 2.6f, initial)
        listOf("great_entrance" to 1, "cherubic_hymn" to 0, "antiphons" to 0, "communion" to 1).forEach { (stage, trackIndex) ->
            compose.runOnIdle {
                assertTrue(player.seekStage(stage))
                assertTrue(player.selectTrack(initial.plan.tracksByStage.getValue(stage)[trackIndex].id))
            }
            assertTrackHeader(player)
            val selected = requireNotNull(player.reading)
            val line = requireNotNull(selected.track).lines[selected.lineIndex]
            val before = selected.state
            compose.onNodeWithTag("player_original_tab").performClick()
            val original = compose.onNodeWithTag("lyric_line_${selected.lineIndex}").assertIsSelected()
                .assertTextEquals(requireNotNull(line.original))
            assertEquals(2.6f, textLayout(original).layoutInput.density.fontScale, .001f)
            assertEquals(20.sp, textLayout(original).layoutInput.style.fontSize)
            compose.onNodeWithTag("player_text_tab").performClick()
            val russian = compose.onNodeWithTag("lyric_line_${selected.lineIndex}").assertIsSelected().assertTextEquals(line.russian)
            assertEquals(2.6f, textLayout(russian).layoutInput.density.fontScale, .001f)
            compose.runOnIdle { assertEquals(before, player.reading!!.state) }
        }
    }

    @Test
    fun acceptingTheSameReadingRefreshesControlsWhileCapturedOldActionsStayInvalid() {
        val initial = reading("creed", approximate = false)
        val player = launch(360, 800, 1f, initial)
        val tags = listOf("player_meaning_tab", "clarify_moment", "choose_track", "next_stage")
        val oldActions = tags.map { tag -> requireNotNull(compose.onNodeWithTag(tag)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action) }
        compose.runOnIdle {
            assertTrue(player.accept(initial, false))
            oldActions.forEach { it() }
        }
        compose.onNodeWithTag("player_original_tab").assertIsSelected()
        compose.onNodeWithTag("track_list").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, clarified); assertEquals("creed", player.reading!!.stage.id) }
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("clarify_moment").performClick()
        compose.runOnIdle { assertEquals(1, clarified) }
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNodeWithTag("track_list").assertIsDisplayed()
        compose.onNodeWithTag("track_0").performClick()
        compose.onNodeWithTag("next_stage").performClick()
        compose.runOnIdle { assertEquals("anaphora", player.reading!!.stage.id) }
    }

    @Test
    fun nextStageUsesTheCurrentlyDisplayedStageAfterAutomaticMovement() {
        val player = launch(360, 800, 1f, reading("anaphora", approximate = false))
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { monotonic.addAndGet(720_000); player.tick(player.generation) }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals("Поминовения и песнь Богородице")
        compose.onNodeWithTag("next_stage").performClick()
        compose.runOnIdle {
            assertEquals("lords_prayer", player.reading!!.stage.id)
            assertFalse(player.reading!!.state.isPlaying)
        }
    }

    @Test
    fun readingDetailsUseTheAcceptedDateAndTheStageMaterializedByPause() {
        val initial = reading("anaphora", date = "2026-11-09", approximate = false)
        val player = launch(360, 800, 1f, initial)
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithTag("player_menu").performClick()
        // ServiceScreen has no clock loop here. Only opening details will materialize this boundary.
        compose.runOnIdle { monotonic.addAndGet(720_000) }
        compose.onNodeWithTag("open_reading_details").performClick()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("reading_details_stage").assertTextEquals("Поминовения и песнь Богородице")
        compose.onNodeWithTag("reading_details_track").assertTextEquals("Достойно есть")
        compose.onNodeWithTag("reading_details_status").assertTextEquals("Примерное место · можно уточнить")
        val paused = requireNotNull(player.reading).state
        assertFalse(paused.isPlaying)
        compose.runOnIdle { monotonic.addAndGet(86_400_000) }
        compose.onNodeWithTag("close_reading_details").performClick()
        compose.onNodeWithContentDescription("Включить плеер").assertIsDisplayed()
        compose.runOnIdle { assertEquals(paused, player.reading!!.state) }
    }

    @Test
    fun staleDetailsActionCannotPauseOrDescribeAReplacementReading() {
        val initial = reading("creed")
        val player = launch(360, 800, 1f, initial)
        compose.onNodeWithTag("player_menu").performClick()
        val stale = requireNotNull(compose.onNodeWithTag("open_reading_details")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val replacement = reading("lords_prayer", date = "2026-11-09")
        compose.runOnIdle {
            assertTrue(player.accept(replacement, false))
            assertTrue(player.play())
            stale()
        }
        compose.onNodeWithTag("reading_details_date").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(player.reading!!.state.isPlaying)
            assertEquals("2026-11-09", player.reading!!.context.serviceDate)
            assertEquals("lords_prayer", player.reading!!.stage.id)
        }
    }

    @Test
    fun compactSavedReadingExposesItsDateWithoutPretendingToBeSynchronized() {
        val initial = reading("creed", date = "2026-11-09", approximate = false)
        val player = launch(320, 640, 2.6f, initial, restored = true)
        compose.onNodeWithTag("saved_earlier_notice").assertIsDisplayed().assertTextEquals("Примерно").assertHasNoClickAction()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithTag("open_reading_details").performClick()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("reading_details_status").performScrollTo().assertTextEquals("Сохраненное чтение · уточните момент")
        compose.onNodeWithTag("close_reading_details").performClick()
        compose.runOnIdle { assertFalse(player.reading!!.state.isPlaying) }
    }

    private fun reading(stageId: String, date: String = "2026-11-08", approximate: Boolean = true): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(date),
            container.readings?.days?.firstOrNull { it.date == date })
        val context = ReadingContext(null, date, "Europe/Moscow", ReadingContextSource.MANUAL)
        return ActiveServicePlayback(plan, context, ServicePlaybackEngine(plan).start(stageId), approximate)
    }

    private fun launch(width: Int, height: Int, requestedScale: Float, initial: ActiveServicePlayback, restored: Boolean = false): ServicePlayerState {
        val player = ServicePlayerState(initial, monotonic::get, ServicePlaybackWriter { _, _, _ -> true })
        compose.setContent {
            val deviceDensity = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = width; screenHeightDp = height
                orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                fontScale = requestedScale
            }
            var original by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(deviceDensity.density, requestedScale)) {
                GuideTheme(dark = true) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        ServiceScreen(player, onStageChange = { player.seekStage(it) }, onBack = {}, onSelectStage = {},
                            onFindStage = { clarified++ }, onSettings = {}, dark = true, onDarkChange = {}, onOpenSource = {},
                            onSoundSettings = {}, onFinish = {}, preferredOriginal = original, onOriginalChange = { original = it },
                            restoredNotice = restored)
                    }
                }
            }
        }
        return player
    }

    private fun assertHeader(displayTitle: String, fullTitle: String, position: Int) {
        val title = compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(displayTitle)
        assertReadable(title)
        compose.onNodeWithTag("open_map").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$fullTitle, $position из 18"))
        compose.onAllNodesWithTag("clarify_moment").assertCountEquals(1)
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertReadable(compose.onNodeWithText("Уточнить", useUnmergedTree = true))
        compose.onNodeWithTag("player_menu").assertIsDisplayed()
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    private fun assertTrackHeader(player: ServicePlayerState) {
        val reading = requireNotNull(player.reading)
        val track = reading.track ?: return
        val title = compose.onNodeWithTag("current_track_title", useUnmergedTree = true)
        assertReadable(title)
        assertTrue("Track header must use at most two lines", textLayout(title).lineCount <= 2)
        if (reading.tracks.size > 1) compose.onNodeWithTag("choose_track")
            .assertContentDescriptionEquals("Выбрать текст: ${track.title}").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    private fun assertReadable(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layout = textLayout(node)
        val message = layout.layoutInput.text.text
        assertFalse("Clipped line count: $message", layout.multiParagraph.didExceedMaxLines)
        (0 until layout.lineCount).forEach { line ->
            assertFalse("Ellipsized: $message", layout.isLineEllipsized(line))
            assertTrue("Horizontal clipping: $message", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Vertical clipping: $message", layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
        }
        val visible = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
        val pixel = (1f / layout.layoutInput.density.density).dp
        assertTrue("Clipped node: $message", visible.left <= full.left + pixel && visible.top <= full.top + pixel &&
            visible.right >= full.right - pixel && visible.bottom >= full.bottom - pixel)
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
        return results.single()
    }

    private fun orient(requested: Int, expected: Int) = withCiFailureDiagnostics("matrix.orient($expected)") {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val landscape = expected == Configuration.ORIENTATION_LANDSCAPE
                ready = activity.resources.configuration.orientation == expected && decor.hasWindowFocus() &&
                    decor.width > 0 && decor.height > 0 && (decor.width > decor.height) == landscape
            }
            ready
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
    }

    private fun captureFailure(stageId: String) {
        // Diagnostic only: an artifact error cannot replace or swallow the original failed assertion.
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            listOf("mkdir -p /sdcard/Download/feoria-qa", "screencap -p /sdcard/Download/feoria-qa/cycle3-matrix-$stageId.png").forEach { command ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
        }.onFailure { android.util.Log.e("CompactReaderMatrix", "Could not save failure PNG", it) }
    }
}
