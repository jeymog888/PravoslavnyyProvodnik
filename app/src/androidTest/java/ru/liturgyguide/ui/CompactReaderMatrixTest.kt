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
    fun allEighteenPortraitStageNamesRemainWholeWithLargeTextAndAccessibleContext() {
        val initial = reading("preparation")
        val player = launch(320, 640, 2.6f, initial)
        assertEquals(18, initial.plan.stages.size)
        initial.plan.stages.forEachIndexed { index, stage ->
            compose.runOnIdle { assertTrue(player.accept(initial.copy(state = initial.engine.start(stage.id)), false)) }
            assertHeader(stage.shortTitle, stage.name, index + 1)
            assertTrackHeader(player)
        }
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
                try {
                    assertHeader(stage.shortTitle, stage.name, index + 1)
                    assertTrackHeader(player)
                    compose.selectReaderMode("meaning")
                    compose.onNodeWithTag("service_content").assertHeightIsAtLeast(120.dp)
                    compose.selectReaderMode("text")
                } catch (failure: AssertionError) {
                    android.util.Log.e("CompactReaderMatrix", "Stage ${stage.id}: ${compose.onRoot().printToString()}")
                    captureFailure(stage.id)
                    throw failure
                }
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
        val tracks = initial.plan.tracksByStage.values.flatten()
            .filter { bookShowsTrackHeading(it) && it.lines.any { line -> line.original != null } }
            .sortedByDescending { it.title.length }.take(4)
        assertEquals(4, tracks.size)
        tracks.forEach { track ->
            val line = track.lines.first { it.original != null }
            compose.runOnIdle {
                val state = initial.engine.seekLine(initial.state, track.id, line.id)
                assertTrue(player.accept(initial.copy(state = state), false))
            }
            assertTrackHeader(player)
            val before = requireNotNull(player.reading).state
            compose.selectReaderMode("original")
            val original = compose.onFeedLine(line).assertIsSelected().assertTextEquals(requireNotNull(line.original))
            assertEquals(2.6f, textLayout(original).layoutInput.density.fontScale, .001f)
            assertEquals(25.sp, textLayout(original).layoutInput.style.fontSize)
            assertEquals(36.sp, textLayout(original).layoutInput.style.lineHeight)
            assertNativeTextMetrics(textLayout(original), Density(textLayout(original).layoutInput.density.density, 2.6f))
            compose.selectReaderMode("text")
            val russian = compose.onFeedLine(line).assertIsSelected().assertTextEquals(line.russian)
            assertEquals(2.6f, textLayout(russian).layoutInput.density.fontScale, .001f)
            assertEquals(25.sp, textLayout(russian).layoutInput.style.fontSize)
            assertEquals(36.sp, textLayout(russian).layoutInput.style.lineHeight)
            assertNativeTextMetrics(textLayout(russian), Density(textLayout(russian).layoutInput.density.density, 2.6f))
            compose.runOnIdle { assertEquals(before, player.reading!!.state) }
        }
    }

    @Test
    fun acceptingTheSameReadingRefreshesControlsWhileCapturedOldActionsStayInvalid() {
        val initial = reading("creed", approximate = false)
        val player = launch(360, 800, 1f, initial)
        val oldActions = listOf("player_meaning_tab", "reader_microphone", "clarify_stage").map { tag ->
            requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        }
        compose.runOnIdle {
            assertTrue(player.accept(initial, false))
            oldActions.forEach { it() }
        }
        compose.assertReaderMode("original", selected = true)
        compose.runOnIdle { assertEquals(0, clarified); assertEquals("creed", player.reading!!.stage.id) }
        compose.selectReaderMode("meaning")
        compose.onNodeWithTag("reader_microphone").performClick()
        compose.runOnIdle { assertEquals(1, clarified) }
        compose.onNodeWithTag("return_to_text").performClick()
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    @Test
    fun headerUsesTheCurrentlyDisplayedStageAfterAutomaticMovement() {
        val player = launch(360, 800, 1f, reading("anaphora", approximate = false))
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { monotonic.addAndGet(720_000); player.tick(player.generation) }
        compose.waitForIdle()
        val current = requireNotNull(player.reading)
        assertNotEquals("anaphora", current.stage.id)
        assertHeader(current.stage.shortTitle, current.stage.name, current.plan.stages.indexOf(current.stage) + 1)
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { assertFalse(player.reading!!.state.isPlaying) }
    }

    @Test
    fun readingDetailsUseTheAcceptedDateAndTheVisibleStageAfterAutomaticMovement() {
        val player = launch(360, 800, 1f, reading("anaphora", date = "2026-11-09", approximate = false))
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.runOnIdle { monotonic.addAndGet(720_000); player.tick(player.generation) }
        compose.waitForIdle()
        val stage = requireNotNull(player.reading).stage
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithTag("reading_details_stage").assertTextEquals(stage.name)
        val paused = requireNotNull(player.reading).state
        assertFalse(paused.isPlaying)
        compose.runOnIdle { monotonic.addAndGet(86_400_000) }
        compose.closeReaderDetails()
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertTextEquals("Автопрокрутка")
        compose.runOnIdle { assertEquals(paused, player.reading!!.state) }
    }

    @Test
    fun staleDetailsActionCannotPauseOrDescribeAReplacementReading() {
        val player = launch(360, 800, 1f, reading("creed"))
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
        val player = launch(320, 640, 2.6f, reading("creed", date = "2026-11-09", approximate = false), restored = true)
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 9 ноября 2026")
        compose.onNodeWithText("Сохранённое чтение. Место в храме можно уточнить по словам или микрофону")
            .performScrollTo().assertIsDisplayed()
        compose.closeReaderDetails()
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertContentDescriptionEquals("Автопрокрутка")
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
        compose.onNodeWithTag("open_map").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, fullTitle))
        if (!compose.readerHasModeChooser()) compose.onNodeWithText("$position из 18 · Порядок службы").assertIsDisplayed()
        listOf("reader_microphone", "clarify_stage", "player_menu").forEach {
            compose.onAllNodesWithTag(it).assertCountEquals(1)
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
    }

    private fun assertTrackHeader(player: ServicePlayerState) {
        val reading = requireNotNull(player.reading)
        val track = reading.track ?: return
        val feed = ContinuousServiceFeed(reading.plan)
        val header = feed.indexOf("track:${track.id}")
        if (header != null) {
            compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(header)) }
            compose.waitForIdle()
            val title = compose.onNodeWithTag(feedHeaderTag(track)).assertTextEquals(track.title)
            assertReadableFeedTitle(title)
        } else assertFalse("Only short dialogue headings may be omitted", bookShowsTrackHeading(track))
        val line = requireNotNull(feed.indexOf("line:${track.lines[reading.lineIndex].id}"))
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(line)) }
        compose.waitForIdle()
        compose.onFeedLine(track, reading.lineIndex).assertIsSelected().assertIsDisplayed()
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
