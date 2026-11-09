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
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val monotonic = AtomicLong(1_000L)
    private var clarified = 0

    @Test
    fun allEighteenPortraitStageNamesRemainWholeWithLargeTextAndAnApproximationNotice() {
        val initial = reading("preparation")
        val player = launch(320, 640, 2.6f, initial)
        assertEquals(18, initial.plan.stages.size)
        initial.plan.stages.forEachIndexed { index, stage ->
            compose.runOnIdle { assertTrue(player.accept(initial.copy(state = initial.engine.start(stage.id)), false)) }
            assertHeader(stage.shortTitle, stage.name, index + 1)
            assertReadable(compose.onNodeWithTag("reader_status"))
            val fullStatus = if (stage.id == "preparation") "До начала литургии · примерное место" else "На паузе · примерное место"
            compose.assertReaderStatus(fullStatus, if (stage.id == "preparation") "До начала" else "Пауза · примерно").assertHasNoClickAction()
            compose.assertReaderStatusDetails(fullStatus)
            assertTrackHeader(player)
        }
        compose.runOnIdle { assertTrue(player.accept(initial, false)) }
        compose.onNodeWithTag("current_track_title").assertDoesNotExist()
        compose.onNodeWithTag("reader_microphone").performClick()
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
                assertReadable(compose.onNodeWithTag("reader_status"))
                val fullStatus = if (stage.id == "preparation") "До начала литургии · примерное место" else "На паузе · примерное место"
                compose.assertReaderStatus(fullStatus, if (stage.id == "preparation") "До начала" else "Пауза · примерно").assertHasNoClickAction()
                compose.assertReaderStatusDetails(fullStatus)
                assertTrackHeader(player)
                try {
                    compose.selectReaderMode("meaning")
                    compose.onNodeWithTag("service_content").assertHeightIsAtLeast(120.dp)
                } catch (failure: AssertionError) {
                    android.util.Log.e("CompactReaderMatrix", "Stage ${stage.id}: ${compose.onRoot().printToString()}")
                    captureFailure(stage.id)
                    throw failure
                }
                val originalAvailable = player.reading?.tracks?.any { track -> track.lines.any { it.original != null } } == true
                compose.selectReaderMode(if (originalAvailable) "original" else "text")
            }
        } finally {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun epistleAt412DpRetainsTheRequestedClarifyScaleAndTheWholeTitle() {
        val player = launch(412, 800, 1.3f, reading("epistle"))
        assertHeader("Апостол", "Прокимен и чтение Апостола", 7)
        assertTrackHeader(player)
        val clarify = compose.onNodeWithText("Микрофон", useUnmergedTree = true)
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
            compose.selectReaderMode("original")
            val original = compose.onFeedLine(line).assertIsSelected()
                .assertTextEquals(requireNotNull(line.original))
            assertEquals(2.6f, textLayout(original).layoutInput.density.fontScale, .001f)
            assertEquals(28.sp, textLayout(original).layoutInput.style.fontSize)
            assertEquals(39.2.sp, textLayout(original).layoutInput.style.lineHeight)
            assertNativeTextMetrics(textLayout(original), Density(textLayout(original).layoutInput.density.density, 2.6f))
            compose.selectReaderMode("text")
            val russian = compose.onFeedLine(line).assertIsSelected().assertTextEquals(line.russian)
            assertEquals(2.6f, textLayout(russian).layoutInput.density.fontScale, .001f)
            assertEquals(28.sp, textLayout(russian).layoutInput.style.fontSize)
            assertEquals(39.2.sp, textLayout(russian).layoutInput.style.lineHeight)
            assertNativeTextMetrics(textLayout(russian), Density(textLayout(russian).layoutInput.density.density, 2.6f))
            compose.runOnIdle { assertEquals(before, player.reading!!.state) }
        }
    }

    @Test
    fun acceptingTheSameReadingRefreshesControlsWhileCapturedOldActionsStayInvalid() {
        val initial = reading("creed", approximate = false)
        val player = launch(360, 800, 1f, initial)
        compose.onNodeWithTag("reader_layout_normal").assertExists()
        val oldActions = listOf("player_meaning_tab", "reader_microphone").map { tag -> requireNotNull(compose.onNodeWithTag(tag)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action) }.toMutableList()
        compose.onNodeWithTag("player_menu").performClick()
        oldActions += requireNotNull(compose.onNodeWithTag("choose_track").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        compose.openReaderControls()
        oldActions += requireNotNull(compose.readerControl("next_stage").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.dismissReaderControls()
        compose.runOnIdle {
            assertTrue(player.accept(initial, false))
            oldActions.forEach { it() }
        }
        compose.assertReaderMode("original", selected = true)
        compose.onNodeWithTag("track_list").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, clarified); assertEquals("creed", player.reading!!.stage.id) }
        compose.selectReaderMode("meaning")
        compose.onNodeWithTag("reader_microphone").performClick()
        compose.runOnIdle { assertEquals(1, clarified) }
        compose.openReaderTracks()
        compose.onNodeWithTag("track_list").assertIsDisplayed()
        compose.onNodeWithTag("track_0").performClick()
        compose.assertReaderMode("meaning", selected = true)
        compose.openReaderControls()
        compose.onNodeWithTag("next_stage").assertDoesNotExist()
        compose.dismissReaderControls()
        compose.onNodeWithTag("return_to_text").performClick()
        compose.openReaderControl("next_stage").performClick()
        compose.runOnIdle { assertEquals("anaphora", player.reading!!.stage.id) }
    }

    @Test
    fun nextStageUsesTheCurrentlyDisplayedStageAfterAutomaticMovement() {
        val player = launch(360, 800, 1f, reading("anaphora", approximate = false))
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { monotonic.addAndGet(720_000); player.tick(player.generation) }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals("Поминовения и песнь Богородице")
        compose.openReaderControl("next_stage").performClick()
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
        compose.onNodeWithContentDescription("Двигать текст").assertIsDisplayed()
        compose.assertReaderStatus("На паузе · примерное место", "Пауза · примерно")
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
        compose.assertReaderStatus("На паузе · сохраненное чтение", "Пауза").assertHasNoClickAction()
        compose.assertReaderStatusDetails("На паузе · сохраненное чтение")
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
        val title = compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals(if (compose.readerHasModeChooser()) displayTitle else fullTitle)
        assertReadable(title)
        compose.onNodeWithTag("open_map").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$fullTitle, $position из 18"))
        compose.openReaderControls()
        compose.onNodeWithTag("reader_controls_list").performScrollToNode(hasTestTag("controls_stage_title"))
        assertReadable(compose.onNodeWithTag("controls_stage_title").performScrollTo().assertTextEquals(fullTitle))
        compose.dismissReaderControls()
        compose.onAllNodesWithTag("reader_microphone").assertCountEquals(1)
        compose.onNodeWithTag("reader_microphone").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertReadable(compose.onNodeWithText("Микрофон", useUnmergedTree = true))
        compose.onNodeWithTag("player_menu").assertIsDisplayed()
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    private fun assertTrackHeader(player: ServicePlayerState) {
        val reading = requireNotNull(player.reading)
        val track = reading.track ?: return
        val feed = ReaderFeed(reading.stage.id, reading.tracks)
        val header = feed.items.indexOfFirst { it is ReaderFeedItem.Header && it.track.id == track.id }
        assertTrue(header >= 0)
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(header)) }
        compose.waitForIdle()
        val title = compose.onNodeWithTag(feedTitleTag(track)).assertTextEquals(track.title)
        assertReadableFeedTitle(title)
        // Full names now live in the feed, without a fixed two-line header budget.
        val line = requireNotNull(feed.lineIndex(track.id, track.lines[reading.lineIndex].id))
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(line)) }
        compose.waitForIdle()
        compose.onFeedLine(track, reading.lineIndex).performScrollTo().assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        compose.runOnIdle { assertEquals(reading, player.reading) }
    }

    private fun assertReadableFeedTitle(node: SemanticsNodeInteraction) {
        val layout = assertCompleteTextLayout(node)
        val density = layout.layoutInput.density.density
        val text = layout.layoutInput.text.text
        fun origin(): androidx.compose.ui.geometry.Rect {
            val full = node.getUnclippedBoundsInRoot()
            return androidx.compose.ui.geometry.Rect(full.left.value * density, full.top.value * density,
                full.right.value * density, full.bottom.value * density)
        }
        val viewport = compose.onNodeWithTag("lyrics_list").fetchSemanticsNode().boundsInRoot
        android.util.Log.i("FeoriaTitle", "$text viewport=$viewport full=${origin()} lines=${layout.lineCount}")
        // A feed title is scrollable content. Read each laid-out line in order; fixed
        // navigation labels still use assertReadable and must fit wholly in one frame.
        for (line in 0 until layout.lineCount) {
            val before = origin()
            val delta = before.top + layout.getLineBottom(line) - viewport.bottom
            if (delta > 1f) {
                compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
                compose.waitForIdle()
                android.util.Log.i("FeoriaTitle", "$text line=$line scroll=$delta before=$before after=${origin()}")
            }
            val full = origin()
            val currentViewport = compose.onNodeWithTag("lyrics_list").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport, currentViewport)
            assertTrue("Feed title line $line clipped: $text, full=$full viewport=$viewport",
                full.top + layout.getLineTop(line) >= viewport.top - 1f &&
                    full.top + layout.getLineBottom(line) <= viewport.bottom + 1f &&
                    full.left + layout.getLineLeft(line) >= viewport.left - 1f &&
                    full.left + layout.getLineRight(line) <= viewport.right + 1f)
        }
    }

    private fun assertCompleteTextLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        node.assertIsDisplayed()
        val layout = textLayout(node)
        val message = layout.layoutInput.text.text
        assertFalse("Clipped line count: $message", layout.multiParagraph.didExceedMaxLines)
        (0 until layout.lineCount).forEach { line ->
            assertFalse("Ellipsized: $message", layout.isLineEllipsized(line))
            assertTrue("Horizontal clipping: $message", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Vertical clipping: $message", layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
        }
        return layout
    }

    private fun assertReadable(node: SemanticsNodeInteraction) {
        val layout = assertCompleteTextLayout(node)
        val message = layout.layoutInput.text.text
        val visible = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
        val pixel = (1f / layout.layoutInput.density.density).dp
        assertTrue("Clipped node: $message; visible=$visible full=$full", visible.left <= full.left + pixel && visible.top <= full.top + pixel &&
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
