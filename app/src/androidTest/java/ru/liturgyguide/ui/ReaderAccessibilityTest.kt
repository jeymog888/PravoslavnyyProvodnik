package ru.liturgyguide.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.ServiceSession
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ReaderAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-11-08T12:00:00Z")
    private val readingDay = requireNotNull(container.readings).days.first { it.date == "2026-11-08" }

    @Test
    fun ordinaryFooterKeepsSeparateTargetsAtTheNarrowWidthAndFastestSpeed() {
        launchReader("creed", 1, 3, width = 320, height = 800, systemFontScale = 1f)
        assertReadingSpaceAndScale(3, 1.3f, 28.sp)
        assertFooterTargets()
        assertReadable(compose.onNodeWithTag("line_status", useUnmergedTree = true))
        compose.onNodeWithTag("scroll_options").performClick()
        compose.onNodeWithTag("tempo_fast").performClick()
        compose.onNodeWithText("Готово").performClick()
        assertFooterTargets()
        assertReadable(compose.onNodeWithText("1,25×", useUnmergedTree = true))
        val playTexts = compose.onAllNodes(hasAnyAncestor(hasTestTag("play_lyrics")) and
            SemanticsMatcher("Has text") { it.config.contains(SemanticsProperties.Text) }, useUnmergedTree = true)
        playTexts.fetchSemanticsNodes().indices.forEach { assertReadable(playTexts[it]) }
        compose.onNodeWithTag("player_meaning_tab").performClick()
        assertFooterTargets(textVisible = false)
    }

    @Test
    fun narrowLargeTextKeepsNamesReadableAndCancellationPreservesThePhrase() {
        launchReader("creed", 1, 3, width = 320, height = 640, systemFontScale = 2f)
        assertReadableHeader()
        assertReadingSpaceAndScale(3, 2.6f)
        assertFooterTargets()
        compose.onNodeWithTag("player_original_tab").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lyric_line_3").assertIsSelected()
        assertReadingSpaceAndScale(3, 2.6f)

        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("track_explanation"))
        assertEquals(2.6f, textLayout(compose.onNodeWithTag("track_explanation")).layoutInput.density.fontScale, .001f)
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        compose.onNodeWithTag("player_original_tab").performClick()
        compose.onNodeWithTag("lyric_line_3").assertIsSelected().assertIsDisplayed()
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertReadableHeader()
    }

    @Test
    fun landscapeKeepsTabsOnOneLineAndLeavesSpaceForTheRequestedPrayerSize() {
        try {
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            launchReader("creed", 1, 3, width = 640, height = 360, systemFontScale = 1.3f)
            assertReadableHeader()
            assertReadingSpaceAndScale(3, 1.69f)
            assertFooterTargets()
            compose.onNodeWithTag("next_line").assertIsDisplayed().performClick()
            compose.onNodeWithTag("lyric_line_4").assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
            compose.onNodeWithTag("player_text_tab").performClick().assertIsSelected()
            compose.onNodeWithTag("lyric_line_4").assertIsSelected()
        } finally {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun aTallNarrowScreenKeepsReadableActionsWithoutReducingThePrayerSize() {
        launchReader("creed", 1, 3, width = 320, height = 800, systemFontScale = 2f)
        assertReadableHeader()
        compose.onNodeWithTag("player_original_tab").assertIsDisplayed().assertIsEnabled()
        assertReadable(compose.onNodeWithText("По-русски", useUnmergedTree = true))
        assertReadingSpaceAndScale(3, 2.6f, 28.sp)
    }

    @Test
    fun aTallScreenAtTheHeaderWidthBoundaryKeepsItsReadingArea() {
        launchReader("creed", 1, 3, width = 400, height = 800, systemFontScale = 2f)
        assertReadableHeader()
        compose.onNodeWithTag("player_original_tab").assertIsDisplayed().assertIsEnabled()
        assertReadingSpaceAndScale(3, 2.6f, 28.sp)
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("lyric_line_4").assertIsSelected().assertIsDisplayed()
    }

    @Test
    fun aSingleTextRetainsItsFullTitleAndTheClarificationAction() {
        launchReader("beginning", 0, 0, width = 320, height = 640, systemFontScale = 2f)
        assertEquals(1, tracks("beginning").size)
        compose.onNodeWithTag("choose_track").assertDoesNotExist()
        assertReadableHeader()
        assertReadingSpaceAndScale(0, 2.6f)
        compose.onNodeWithTag("clarify_moment").performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
    }

    @Test
    fun scriptureAndAnEmptyTextStageKeepClarificationWithoutALanguageMenu() {
        launchReader("gospel", 1, 0, width = 320, height = 640, systemFontScale = 2f)
        assertTrue(tracks("gospel")[1].isScripture)
        compose.onNodeWithTag("player_original_tab").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        assertReadableHeader()
        assertReadingSpaceAndScale(0, 2.6f)
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").performScrollTo().performClick()
        compose.onNodeWithTag("current_track_title").assertDoesNotExist()
        compose.onAllNodesWithTag("clarify_moment").assertCountEquals(1)
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
    }

    @Test
    fun narrowScheduleKeepsBothDayLabelsOnOneLineAndSelectable() {
        val parishes = container.parishes.getOrThrow()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2.6f)) {
                GuideTheme(dark = true) {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        ChurchScreen(parishes.directory.churches, parishes.selected(null).id,
                            LocalDate.of(2026, 11, 8), onSelect = { true }, onOpenUrl = {}, onBack = {})
                    }
                }
            }
        }
        listOf("schedule_today" to "Сегодня", "schedule_tomorrow" to "Завтра").forEach { (tag, label) ->
            compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertReadable(text)
            assertEquals("The day must not be split into separate letters", 1, textLayout(text).lineCount)
        }
        compose.onNodeWithTag("schedule_today").assertIsNotSelected()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_date"))
        compose.onNodeWithTag("schedule_date").assertTextEquals("9 ноября 2026, понедельник").assertIsDisplayed()
    }

    @Test
    fun largeTextNavigationLeavesSpaceForChurchAndSettingsWithoutScalingTheirContent() {
        check(preferences.edit().clear().putString("church_id", "elisavetinsky_opalikha")
            .putBoolean("dark", true).putInt("text_size", 2).commit())
        launchApp(width = 320, height = 640, systemFontScale = 2f)
        assertNavigationLabels()
        compose.onNodeWithTag("nav_church").performClick()
        assertNavigationLabels()
        val churchHeader = compose.onNodeWithTag("church_title", useUnmergedTree = true)
        assertReadable(churchHeader)
        assertEquals(1, textLayout(churchHeader).lineCount)
        compose.onNodeWithTag("church_content").assertHeightIsAtLeast(300.dp)
        val church = container.parishes.getOrThrow().selected(null)
        assertEquals(2.6f, textLayout(compose.onNodeWithText(church.name, useUnmergedTree = true))
            .layoutInput.density.fontScale, .001f)
        listOf("schedule_today" to "Сегодня", "schedule_tomorrow" to "Завтра").forEach { (tag, label) ->
            compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertReadable(text)
            assertEquals(1, textLayout(text).lineCount)
        }
        compose.onNodeWithTag("open_settings").performClick()
        assertNavigationLabels()
        val settingsHeader = compose.onNodeWithTag("settings_title", useUnmergedTree = true)
        assertReadable(settingsHeader)
        assertEquals(1, textLayout(settingsHeader).lineCount)
        compose.onNodeWithTag("settings_content").assertHeightIsAtLeast(300.dp)
        assertEquals(2.6f, textLayout(compose.onNodeWithText("Экран", useUnmergedTree = true))
            .layoutInput.density.fontScale, .001f)
    }

    private fun launchReader(stageId: String, trackIndex: Int, phraseIndex: Int,
        width: Int, height: Int, systemFontScale: Float) {
        val track = tracks(stageId)[trackIndex]
        check(preferences.edit().clear().putBoolean("dark", true).putInt("text_size", 2)
            .putString("stage_id", stageId).putString("session", Gson().toJson(ServiceSession(stageId, instant.toEpochMilli())))
            .putString("lyric_track_$stageId", track.id).putInt("lyric_line_${track.id}", phraseIndex)
            .putString("lyric_phrase_${track.id}", track.lines[phraseIndex].id).commit())
        launchApp(width, height, systemFontScale)
        awaitPlatformReady()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performScrollTo().performClick()
    }

    private fun launchApp(width: Int, height: Int, systemFontScale: Float) {
        compose.setContent {
            val deviceDensity = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = width; screenHeightDp = height
                orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                fontScale = systemFontScale
            }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(deviceDensity.density, systemFontScale)) {
                Box(Modifier.size(width.dp, height.dp)) {
                    AppNavigation(container, Clock.fixed(instant, ZoneOffset.UTC))
                }
            }
        }
    }

    private fun assertNavigationLabels() {
        listOf("nav_home" to "Служба", "nav_church" to "Храм", "open_settings" to "Настройки").forEach { (tag, label) ->
            val node = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            assertReadable(node)
            assertEquals("A navigation label must stay on one line", 1, textLayout(node).lineCount)
        }
    }

    private fun assertFooterTargets(textVisible: Boolean = true) {
        val tags = if (textVisible) listOf("previous_stage", "previous_line", "play_lyrics", "next_line", "scroll_options", "next_stage")
            else listOf("previous_stage", "next_stage")
        val tolerance = .5.dp
        val bounds = tags.associateWith { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val visible = node.getBoundsInRoot()
            val full = node.getUnclippedBoundsInRoot()
            assertTrue("Footer target $tag is clipped", visible.left <= full.left + tolerance &&
                visible.top <= full.top + tolerance && visible.right >= full.right - tolerance && visible.bottom >= full.bottom - tolerance)
            full
        }
        tags.forEachIndexed { index, first ->
            tags.drop(index + 1).forEach { second ->
                val a = bounds.getValue(first); val b = bounds.getValue(second)
                assertTrue("Footer targets $first and $second intersect", a.right <= b.left + tolerance ||
                    b.right <= a.left + tolerance || a.bottom <= b.top + tolerance || b.bottom <= a.top + tolerance)
            }
        }
        val content = compose.onNodeWithTag(if (textVisible) "lyrics_list" else "service_content")
            .assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        assertTrue("The footer overlaps the reading area", content.bottom <= bounds.values.minOf { it.top } + tolerance)
    }

    private fun assertReadableHeader() {
        assertReadable(compose.onNodeWithTag("current_stage_title", useUnmergedTree = true))
        assertReadable(compose.onNodeWithTag("current_track_title", useUnmergedTree = true))
        listOf("Оригинал", "По-русски", "Смысл").forEach {
            val node = compose.onNodeWithText(it, useUnmergedTree = true)
            assertReadable(node)
            assertEquals("A short tab label must stay on one line", 1, textLayout(node).lineCount)
        }
        compose.onAllNodesWithTag("clarify_moment").assertCountEquals(1)
        compose.onNodeWithTag("clarify_moment").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertReadable(compose.onNodeWithText("Уточнить", useUnmergedTree = true))
        compose.onNodeWithTag("player_menu").assertIsDisplayed()
    }

    private fun assertReadingSpaceAndScale(line: Int, expectedScale: Float, expectedFontSize: TextUnit = 20.sp) {
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        val lyric = compose.onNodeWithTag("lyric_line_$line").assertIsSelected().assertIsDisplayed()
        val layout = textLayout(lyric)
        assertEquals(expectedScale, layout.layoutInput.density.fontScale, .001f)
        assertEquals(expectedFontSize, layout.layoutInput.style.fontSize)
    }

    private fun assertReadable(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val result = textLayout(node)
        val message = "Label ${result.layoutInput.text}; size=${result.size}"
        // String Text semantics rebuild a wider MultiParagraph in Compose 1.7.3.
        // Check the actual line extents, not its unused paragraph width.
        assertFalse("$message exceeds its line limit", result.multiParagraph.didExceedMaxLines)
        assertFalse("Visible label must not be ellipsized: ${result.layoutInput.text}",
            (0 until result.lineCount).any(result::isLineEllipsized))
        (0 until result.lineCount).forEach { line ->
            assertTrue("$message clips the left of line $line", result.getLineLeft(line) >= -1f)
            assertTrue("$message clips the right of line $line", result.getLineRight(line) <= result.size.width + 1f)
            assertTrue("$message clips the top of line $line", result.getLineTop(line) >= -1f)
            assertTrue("$message clips the bottom of line $line", result.getLineBottom(line) <= result.size.height + 1f)
        }
        val visible = node.getBoundsInRoot()
        val full = node.getUnclippedBoundsInRoot()
        val pixel = (1f / result.layoutInput.density.density).dp
        assertTrue("$message is partly outside its visible node", visible.left <= full.left + pixel &&
            visible.top <= full.top + pixel && visible.right >= full.right - pixel && visible.bottom >= full.bottom - pixel)
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
        return results.single()
    }

    private fun tracks(stageId: String) = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById(stageId)), readingDay)

    private fun orient(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(timeoutMillis = 10_000) { compose.activity.resources.configuration.orientation == expected }
        awaitPlatformReady()
    }

    private fun awaitPlatformReady() {
        compose.waitUntil(timeoutMillis = 10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
            }
            ready
        }
        // Native transitions and layout are not driven by Compose's test clock.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
    }
}
