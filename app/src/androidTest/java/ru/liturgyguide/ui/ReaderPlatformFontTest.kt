package ru.liturgyguide.ui

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** The OS parent is real; only the production settings UI chooses the app's native font scale. */
@RunWith(AndroidJUnit4::class)
class ReaderPlatformFontTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val instant = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val parent = AtomicReference<PlatformParent?>()
    private val expectedSystemScale: Float? = InstrumentationRegistry.getArguments()
        .getString("feoriaExpectedSystemFontScale")?.let { value ->
            requireNotNull(value.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }) {
                "Invalid feoriaExpectedSystemFontScale: $value"
            }
        }

    @Test fun nativeSystemFontCurveSurvivesAppSettingsWindowsAndActivityRecreation() =
        withCiFailureDiagnostics("platformFont.settingsWindowsAndRecreation") {
            val initial = seedAcceptedReading()
            val saved = savedPlayback()
            val bookmarks = bookmarkValues()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                attachProductionApp(scenario)
                assertSystemParent()
                val expectedBrandSize = brandSize()
                for ((step, setting) in listOf(0, 1, 2, 0, 2).withIndex()) {
                    if (step > 0) compose.onNodeWithContentDescription("К службе").performClick()
                    compose.onNodeWithTag("main_content").assertIsDisplayed()
                    assertEquals("The decorative logo must not grow with content font size", expectedBrandSize, brandSize())
                    compose.onNodeWithTag("open_settings").performClick()
                    compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_$setting"))
                    compose.onNodeWithTag("text_size_$setting").performScrollTo().assertIsDisplayed().performClick()
                    // Font reflow may dispose the old lazy item. Find its new instance.
                    compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_$setting"))
                    compose.onNodeWithTag("text_size_$setting").performScrollTo().assertIsSelected()
                    val factor = listOf(1f, 1.15f, 1.3f)[setting]
                    assertEquals(setting, preferences.getInt("text_size", -1))
                    assertSystemParent()
                    compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("settings_title"))
                    sample(compose.onNodeWithTag("settings_title", useUnmergedTree = true).performScrollTo(), factor)
                    // The selected scrollable row can move when its own text becomes larger.
                    compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_$setting"))
                    compose.onNodeWithTag("text_size_$setting").performScrollTo().assertIsSelected()
                    sample(textWithin("text_size_$setting", listOf("Обычный", "Крупный", "Очень крупный")[setting]), factor)
                    sample(textWithin("nav_home", "Служба"), factor)
                    compose.onNodeWithTag("nav_home").performClick()
                    resumeReading()
                    assertReaderAndStoredState(initial, saved, bookmarks, factor)
                    if (step < 3) {
                        assertReaderWindows(factor)
                        assertReaderAndStoredState(initial, saved, bookmarks, factor)
                    }
                }
                val beforeRecreation = requireNotNull(parent.get()).activity
                scenario.recreate()
                attachProductionApp(scenario)
                assertNotSame("The test must observe a new real Activity", beforeRecreation, requireNotNull(parent.get()).activity)
                assertSystemParent()
                // Production saveable state may restore the reader; disk restore also has an explicit home entry.
                if (compose.onAllNodesWithTag("play_lyrics").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("main_content").assertIsDisplayed()
                    resumeReading()
                }
                assertEquals(2, preferences.getInt("text_size", -1))
                assertReaderAndStoredState(initial, saved, bookmarks, 1.3f)
                compose.onNodeWithContentDescription("К службе").performClick()
                assertEquals(expectedBrandSize, brandSize())
                sample(textWithin("nav_home", "Служба"), 1.3f)
                assertEquals(saved, savedPlayback())
                assertEquals(bookmarks, bookmarkValues())
            }
        }

    private data class PlatformParent(val activity: ComponentActivity, val density: Density, val configurationScale: Float)

    private fun brandSize(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
        compose.onNodeWithTag("main_content").performScrollToNode(hasContentDescription("Феория"))
        val node = compose.onNodeWithContentDescription("Феория").performScrollTo().assertIsDisplayed()
        val full = node.getUnclippedBoundsInRoot(); val visible = node.getBoundsInRoot()
        assertTrue("Logo artwork must fit its visible bounds", visible.left <= full.left + .5.dp && visible.top <= full.top + .5.dp &&
            visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
        assertTrue("Logo is still visibly present", full.width >= 100.dp && full.height >= 40.dp)
        return full.width to full.height
    }

    private fun attachProductionApp(scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                val configurationScale = LocalConfiguration.current.fontScale
                SideEffect { parent.set(PlatformParent(activity, density, configurationScale)) }
                AppNavigation(container, clock, monotonicMillis = { 1_000L })
            }
        }
        compose.waitForIdle()
        assertNotNull("AppNavigation must have a real Android density parent", parent.get())
    }

    private fun assertSystemParent() {
        val observed = requireNotNull(parent.get())
        val density = observed.density
        assertEquals(observed.configurationScale, density.fontScale, .001f)
        expectedSystemScale?.let {
            assertEquals("Declared OS font scale must match LocalConfiguration", it, observed.configurationScale, .001f)
            assertEquals("Declared OS font scale must match the real LocalDensity", it, density.fontScale, .001f)
        }
        if (expectedSystemScale == 2f) {
            with(density) {
                val smallRatio = 12.sp.toDp().value / 12f
                val largeRatio = 32.sp.toDp().value / 32f
                assertTrue("Real 200% parent must expose nonlinear conversion: $smallRatio versus $largeRatio", smallRatio - largeRatio > .05f)
                assertTrue("200% metadata alone is insufficient", abs(32.sp.toDp().value - 32f * fontScale) > 1f)
            }
        }
    }

    private fun assertReaderWindows(factor: Float) {
        compose.onNodeWithTag("player_menu").performClick()
        sample(textWithin("scroll_options", "Скорость автопрокрутки"), factor)
        compose.onNodeWithTag("open_reading_details").performClick()
        sample(compose.onNodeWithTag("reading_details_date").performScrollTo()
            .assertTextEquals("Тексты на 8 ноября 2026"), factor)
        sample(textWithin("close_reading_details", "Закрыть"), factor)
        compose.closeReaderDetails()
        compose.inspectReaderModes { mode, node ->
            if (mode == "original") node.assertIsSelected()
            val tag = node.fetchSemanticsNode().config[SemanticsProperties.TestTag]
            sample(textWithin(tag, when (mode) { "original" -> "Оригинал"; "text" -> "Русский"; else -> "Смысл" }), factor)
        }
    }

    private fun assertReaderAndStoredState(initial: ActiveServicePlayback, saved: StoredServicePlayback,
        bookmarks: Map<String, *>, factor: Float) {
        val line = requireNotNull(initial.track).lines[initial.lineIndex]
        val node = compose.onFeedLine(line).assertIsSelected().assertIsDisplayed().assertTextEquals(requireNotNull(line.original))
        val actual = sample(node, factor, fixed = false)
        assertEquals(25.sp, actual.layoutInput.style.fontSize)
        assertEquals(36.sp, actual.layoutInput.style.lineHeight)
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        val play = compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        if (compose.readerHasModeChooser()) play.assertContentDescriptionEquals("Автопрокрутка")
        else play.assertTextEquals("Автопрокрутка")
        sample(compose.onNode(hasAnyAncestor(hasTestTag("play_lyrics")) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true), factor)
        assertEquals(saved, savedPlayback())
        assertEquals(initial.context, ReadingContextStore(preferences).restore())
        assertEquals(bookmarks, bookmarkValues())
        assertTrue(preferences.getBoolean("lyric_original", false))
    }

    private fun sample(node: SemanticsNodeInteraction, factor: Float, fixed: Boolean = true): TextLayoutResult {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val actual = layouts.single()
        val platform = requireNotNull(parent.get()).density
        val expected = Density(platform.density, platform.fontScale * factor)
        val child = actual.layoutInput.density
        assertEquals("No physical DP density changes", platform.density, child.density, 0f)
        assertEquals("No hidden font cap", expected.fontScale, child.fontScale, .001f)
        val values = listOf(12f, 20f, 24f, 25f, 32f, 36f).map { size ->
            val unit = size.sp
            val dp = with(expected) { unit.toDp() }
            val px = with(expected) { unit.toPx() }
            assertEquals("Native $unit DP at ${expected.fontScale}", dp.value, with(child) { unit.toDp().value }, .001f)
            assertEquals("Native $unit PX at ${expected.fontScale}", px, with(child) { unit.toPx() }, .001f)
            assertEquals(size, with(child) { dp.toSp().value }, .001f)
            assertEquals(size, with(child) { px.toSp().value }, .001f)
            "$size:${dp.value}dp/$px px"
        }
        assertEquals(with(platform) { 48.dp.toPx() }, with(child) { 48.dp.toPx() }, 0f)
        if (expectedSystemScale == 2f && factor == 1f) with(child) {
            assertTrue("Native A1 text must preserve real nonlinear scaling", 12.sp.toDp().value / 12f - 32.sp.toDp().value / 32f > .05f)
        }
        assertNativeTextMetrics(actual, expected)
        // Match the established oracle: unused reconstructed paragraph width is not glyph clipping.
        assertFalse(actual.multiParagraph.didExceedMaxLines)
        for (line in 0 until actual.lineCount) {
            assertFalse(actual.isLineEllipsized(line))
            assertTrue("Text left/right clipped: ${actual.layoutInput.text}", actual.getLineLeft(line) >= -1f && actual.getLineRight(line) <= actual.size.width + 1f)
            assertTrue("Text top/bottom clipped: ${actual.layoutInput.text}", actual.getLineTop(line) >= -1f && actual.getLineBottom(line) <= actual.size.height + 1f)
        }
        if (fixed) {
            val full = node.getUnclippedBoundsInRoot(); val visible = node.getBoundsInRoot()
            val tolerance = (1f / child.density).dp
            assertTrue("Fixed text partly hidden: ${actual.layoutInput.text}; full=$full visible=$visible",
                visible.left <= full.left + tolerance && visible.top <= full.top + tolerance &&
                    visible.right >= full.right - tolerance && visible.bottom >= full.bottom - tolerance)
        }
        Log.i("FeoriaNativeFont", "system=${platform.fontScale} app=$factor requested=${expected.fontScale} text=${actual.layoutInput.text.text.take(60)} values=$values")
        return actual
    }

    private fun textWithin(tag: String, text: String) = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)

    private fun resumeReading() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performScrollTo().performClick()
    }

    private fun seedAcceptedReading(): ActiveServicePlayback {
        val date = LocalDate.parse("2026-11-08")
        val day = requireNotNull(container.readings).days.single { it.date == date.toString() }
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date, day)
        val track = plan.tracksByStage.getValue("creed").single { it.title == "Символ веры" }
        val engine = ServicePlaybackEngine(plan)
        val state = engine.seekLine(engine.start("creed", PlaybackSpeed.FAST), track.id, track.lines[3].id)
            .copy(elapsedQuarterMillis = 1_234)
        val reading = ActiveServicePlayback(plan, ReadingContext("elisavetinsky_opalikha", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL), state)
        check(preferences.edit().clear().putString("church_id", reading.context.parishId)
            .putBoolean("dark", false).putBoolean("lyric_original", true).putInt("text_size", 0).commit())
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, reading.context, engine.snapshot(state, 1_000L),
            ServiceSession("creed", instant.toEpochMilli()), resetBookmarks = true, bookmark = PlaybackBookmark.at(plan, state)))
        return reading
    }

    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun bookmarkValues(): Map<String, *> = preferences.all.filterKeys(ReadingAcceptanceStore::isBookmarkKey)
}
