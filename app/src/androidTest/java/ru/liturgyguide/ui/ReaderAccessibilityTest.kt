package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

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
import ru.liturgyguide.service.ServicePlaybackStore
import ru.liturgyguide.service.StoredServicePlaybackCodec
import ru.liturgyguide.service.ServicePlaybackPlan
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ReaderAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-11-08T12:00:00Z")
    private val readingDay = requireNotNull(container.readings).days.first { it.date == "2026-11-08" }

    @Test
    fun ordinaryFooterKeepsSeparateTargetsAtTheNarrowWidthAndFastestSpeed() {
        launchReader("creed", 1, 3, width = 320, height = 800, systemFontScale = 1f)
        assertReadingSpaceAndScale(3, 1.3f, 25.sp)
        assertFooterTargets()
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithTag("tempo_fast").performScrollTo().performClick()
        compose.onNodeWithTag("tempo_done").performClick()
        assertFooterTargets()
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithTag("tempo_fast").performScrollTo().assertIsSelected()
        assertReadable(compose.onNodeWithText("Быстрее · 1,25×", useUnmergedTree = true))
        compose.onNodeWithTag("tempo_done").performClick()
        compose.selectReaderMode("meaning")
        assertFooterTargets(textVisible = false)
    }

    @Test
    fun tempoOptionsAndFixedActionsStayReachableWithVeryLargeText() {
        launchReader("creed", 1, 3, width = 320, height = 640, systemFontScale = 2f)
        compose.openReaderControl("scroll_options").performClick()
        fun assertWholeAction(tag: String) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val full = node.getUnclippedBoundsInRoot(); val visible = node.getBoundsInRoot()
            assertTrue("Tempo action $tag is clipped", visible.left <= full.left + .5.dp &&
                visible.top <= full.top + .5.dp && visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
            val label = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            assertReadable(label)
            assertEquals(2.6f.toRawBits(), textLayout(label).layoutInput.density.fontScale.toRawBits())
        }
        assertWholeAction("tempo_done")
        listOf("tempo_slow", "tempo_normal", "tempo_fast").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo()
            assertWholeAction(tag)
        }
        compose.onNodeWithTag("tempo_fast").performClick().assertIsSelected()
        compose.onNodeWithTag("tempo_done").performClick()
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertContentDescriptionEquals("Автопрокрутка")
    }

    @Test
    fun narrowLargeTextKeepsNamesReadableAndCancellationPreservesThePhrase() {
        launchReader("creed", 1, 3, width = 320, height = 640, systemFontScale = 2f)
        assertReadableHeader()
        assertReadingSpaceAndScale(3, 2.6f)
        assertFooterTargets()
        compose.selectReaderMode("original")
        compose.onFeedLine(acceptedTrack(), 3).assertIsSelected()
        assertReadingSpaceAndScale(3, 2.6f)

        compose.selectReaderMode("meaning")
        compose.onNodeWithText("Что происходит").assertIsDisplayed()
        compose.onNodeWithTag("track_explanation").performScrollTo()
        assertEquals(2.6f, textLayout(compose.onNodeWithTag("track_explanation")).layoutInput.density.fontScale, .001f)
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.assertReaderMode("meaning", selected = true)
        compose.assertReaderMode("original", selected = false)
        compose.selectReaderMode("original")
        compose.onFeedLine(acceptedTrack(), 3).assertIsSelected().assertIsDisplayed()
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertReadableHeader()
    }

    @Test
    fun landscapeKeepsModeChoicesReadableAndLeavesSpaceForTheRequestedPrayerSize() {
        try {
            withCiFailureDiagnostics("landscape.nativeFontMetrics") {
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            launchReader("creed", 1, 3, width = 640, height = 360, systemFontScale = 1.3f)
            assertReadableHeader()
            // Match the fixture's exact float product; a rounded literal selects another native converter cache key.
            assertReadingSpaceAndScale(3, 1.3f * 1.3f)
            assertFooterTargets()
            selectPhrase(4)
            compose.onFeedLine(acceptedTrack(), 4).assertIsSelected().assertIsDisplayed()
            compose.selectReaderMode("meaning")
            compose.selectReaderMode("text")
            compose.onFeedLine(acceptedTrack(), 4).assertIsSelected()
            }
        } finally {
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun aTallNarrowScreenKeepsReadableActionsWithoutReducingThePrayerSize() {
        launchReader("creed", 1, 3, width = 320, height = 800, systemFontScale = 2f)
        assertReadableHeader()
        compose.assertReaderMode("original", enabled = true)
        compose.inspectReaderModes { _, node -> assertReadableModeText(node) }
        assertReadingSpaceAndScale(3, 2.6f, 25.sp)
    }

    @Test
    fun aTallScreenAtTheHeaderWidthBoundaryKeepsItsReadingArea() {
        launchReader("creed", 1, 3, width = 400, height = 800, systemFontScale = 2f)
        assertReadableHeader()
        compose.assertReaderMode("original", enabled = true)
        assertReadingSpaceAndScale(3, 2.6f, 25.sp)
        selectPhrase(4)
        compose.onFeedLine(acceptedTrack(), 4).assertIsSelected().assertIsDisplayed()
    }

    @Test
    fun aSingleTextRetainsItsFullTitleAndTheClarificationAction() {
        launchReader("beginning", 0, 0, width = 320, height = 640, systemFontScale = 2f)
        assertEquals(1, tracks("beginning").size)
        assertReadableHeader()
        assertReadingSpaceAndScale(0, 2.6f)
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onFeedLine(acceptedTrack(), 0).assertIsSelected()
    }

    @Test
    fun scriptureAndPreparationKeepAccessibleLanguageAndClarification() {
        launchReader("gospel", 1, 0, width = 320, height = 640, systemFontScale = 2f)
        assertTrue(acceptedTrack().isScripture)
        compose.assertReaderMode("original", enabled = false)
        compose.assertReaderMode("text", selected = true)
        assertReadableHeader()
        assertReadingSpaceAndScale(0, 2.6f)
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").performScrollTo().performClick()
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag("start_liturgy"))
        compose.onNodeWithTag("start_liturgy").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Перейти к началу литургии")
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("reader_microphone").assertCountEquals(1)
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(
            if (compose.readerHasModeChooser()) "До начала" else "Подготовка к литургии")
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
                            LocalDate.of(2026, 11, 8), searchQuery = "", onSearchQueryChange = {}, onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {})
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
    fun ordinaryNavigationKeepsThreeColumnsAndWholeLabels() {
        check(preferences.edit().clear().putString("church_id", "elisavetinsky_opalikha")
            .putBoolean("dark", true).putInt("text_size", 0).commit())
        launchApp(width = 320, height = 640, systemFontScale = 1f)
        compose.onNodeWithTag("navigation_columns").assertIsDisplayed()
        compose.onNodeWithTag("navigation_stacked").assertDoesNotExist()
        val targets = mutableListOf<androidx.compose.ui.unit.DpRect>()
        val icons = mutableListOf<androidx.compose.ui.unit.DpRect>()
        listOf("nav_home" to "Служба", "nav_church" to "Храм", "open_settings" to "Настройки").forEach { (tag, label) ->
            val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            assertReadable(text)
            assertEquals(1, textLayout(text).lineCount)
            assertEquals(12.sp, textLayout(text).layoutInput.style.fontSize)
            targets += compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            icons += compose.onNodeWithTag(if (tag == "open_settings") "nav_settings_icon" else "${tag}_icon", useUnmergedTree = true)
                .assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        assertTrue(icons.maxOf { it.top } - icons.minOf { it.top } <= .5.dp)
        targets.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left + .5.dp) }
        compose.onNodeWithTag("nav_home").assertIsSelected()
        compose.onNodeWithTag("nav_church").performClick().assertIsSelected()
        compose.onNodeWithTag("church_content").assertIsDisplayed().assertHeightIsAtLeast(300.dp)
        compose.onNodeWithTag("open_settings").performClick().assertIsSelected()
        compose.onNodeWithTag("settings_content").assertIsDisplayed().assertHeightIsAtLeast(300.dp)
        compose.onNodeWithTag("nav_home").performClick().assertIsSelected()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
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
        churchHeader.assert(hasAnyAncestor(hasTestTag("church_content")))
        compose.onNodeWithTag("church_content").assertHeightIsAtLeast(300.dp)
        val church = container.parishes.getOrThrow().selected(null)
        assertEquals(2.6f, textLayout(compose.onNodeWithText(church.shortName, useUnmergedTree = true).performScrollTo())
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
        settingsHeader.assert(hasAnyAncestor(hasTestTag("settings_content")))
        compose.onNodeWithTag("settings_content").assertHeightIsAtLeast(300.dp)
        assertEquals(2.6f, textLayout(compose.onNodeWithText("Экран", useUnmergedTree = true))
            .layoutInput.density.fontScale, .001f)
    }

    private fun launchReader(stageId: String, trackIndex: Int, phraseIndex: Int,
        width: Int, height: Int, systemFontScale: Float) {
        val available = tracks(stageId)
        val track = when (stageId) {
            "creed" -> available.single { it.title == "Символ веры" }
            "gospel" -> available.single { it.isScripture && it.title == "От Луки 16:19-31" }
            else -> available[trackIndex]
        }
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
        val targets = mutableListOf<androidx.compose.ui.unit.DpRect>()
        val icons = mutableListOf<androidx.compose.ui.unit.DpRect>()
        compose.onNodeWithTag("navigation_stacked").assertIsDisplayed()
        listOf("nav_home" to "Служба", "nav_church" to "Храм", "open_settings" to "Настройки").forEach { (tag, label) ->
            val node = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            assertReadable(node)
            val layout = textLayout(node)
            assertEquals("Centered navigation must use the real full cell width",
                layout.size.width.toFloat(), layout.multiParagraph.width, 1f)
            // Full-width destinations must keep each native label whole at this profile.
            assertEquals("A 320dp/2.6 label must remain on one line", 1, layout.lineCount)
            for (line in 0 until layout.lineCount) {
                val part = label.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true)).trim()
                assertTrue("A navigation label must not leave one isolated letter: $label", part.length >= 2)
            }
            assertEquals(12.sp, layout.layoutInput.style.fontSize)
            assertEquals(2.6f, layout.layoutInput.density.fontScale, .001f)
            assertNativeTextMetrics(layout, Density(layout.layoutInput.density.density, 2.6f))
            val target = compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val full = target.getUnclippedBoundsInRoot(); val visible = target.getBoundsInRoot()
            assertTrue("Navigation target is clipped", visible.left <= full.left + .5.dp && visible.top <= full.top + .5.dp &&
                visible.right >= full.right - .5.dp && visible.bottom >= full.bottom - .5.dp)
            targets += full
            icons += compose.onNodeWithTag(if (tag == "open_settings") "nav_settings_icon" else "${tag}_icon", useUnmergedTree = true)
                .assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        assertTrue("Stacked navigation icons must share one column", icons.maxOf { it.left } - icons.minOf { it.left } <= .5.dp)
        targets.zipWithNext().forEach { (a, b) -> assertTrue("Navigation targets overlap", a.bottom <= b.top + .5.dp) }
    }

    private fun assertFooterTargets(textVisible: Boolean = true) {
        val primary = if (textVisible) "play_lyrics" else "return_to_text"
        val compact = compose.readerHasModeChooser()
        val tags = listOf(primary, "reader_microphone", "clarify_stage", "open_map", "player_menu") +
            if (compact) listOf("reader_mode_chooser") else listOf("player_original_tab", "player_text_tab", "player_meaning_tab")
        compose.onNodeWithTag("clarify_stage").assertIsEnabled()
        compose.onNodeWithTag("reader_microphone").assertIsEnabled()
        compose.onNodeWithTag("open_map").assertIsEnabled()
        if (compact) {
            compose.onNodeWithTag("reader_microphone").assertContentDescriptionEquals("Микрофон")
            compose.onNodeWithTag("clarify_stage").assertContentDescriptionEquals("Найти место")
            compose.onNodeWithTag(primary).assertContentDescriptionEquals(if (textVisible) "Автопрокрутка" else "К тексту")
        }
        val tolerance = .5.dp
        val bounds = tags.associateWith { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val visible = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
            assertTrue("Fixed target $tag is clipped", visible.left <= full.left + tolerance &&
                visible.top <= full.top + tolerance && visible.right >= full.right - tolerance && visible.bottom >= full.bottom - tolerance)
            val labels = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            labels.fetchSemanticsNodes().indices.forEach { assertReadable(labels[it]) }
            full
        }
        fun separate(a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect) =
            a.right <= b.left + tolerance || b.right <= a.left + tolerance || a.bottom <= b.top + tolerance || b.bottom <= a.top + tolerance
        tags.forEachIndexed { index, first -> tags.drop(index + 1).forEach { second ->
            assertTrue("Fixed targets $first and $second intersect", separate(bounds.getValue(first), bounds.getValue(second)))
        } }
        val content = compose.onNodeWithTag(if (textVisible) "lyrics_list" else "service_content")
            .assertHeightIsAtLeast(120.dp).getBoundsInRoot()
        bounds.forEach { (tag, target) -> assertTrue("Control $tag overlaps the reading area", separate(content, target)) }
        compose.inspectReaderModes { _, node -> assertReadableModeText(node) }
    }

    private fun assertReadableHeader() {
        assertReadable(compose.onNodeWithTag("current_stage_title", useUnmergedTree = true))
        val track = acceptedTrack()
        val saved = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(saved.context.serviceDate), readingDay)
        val feed = ContinuousServiceFeed(plan)
        val header = feed.indexOf("track:${track.id}")
        if (header != null) {
            compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(header)) }
            compose.waitForIdle()
            assertReadable(compose.onNodeWithTag(feedHeaderTag(track)).assertTextEquals(track.title))
        } else assertFalse(bookShowsTrackHeading(track))
        val phrase = track.lines.single { it.id == preferences.getString("lyric_phrase_${track.id}", null) }
        val line = requireNotNull(feed.indexOf("line:${phrase.id}"))
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(line)) }
        compose.waitForIdle()
        compose.onFeedLine(phrase).assertIsSelected().assertIsDisplayed()
        val chooser = compose.readerHasModeChooser()
        if (chooser) assertReadable(compose.onNodeWithTag("reader_selected_mode", useUnmergedTree = true))
        compose.inspectReaderModes { _, node ->
            val label = assertReadableModeText(node)
            if (!chooser) assertEquals("A short normal tab label must stay on one line", 1, textLayout(label).lineCount)
        }
        compose.onAllNodesWithTag("reader_microphone").assertCountEquals(1)
        compose.onNodeWithTag("reader_microphone").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        if (chooser) compose.onNodeWithTag("reader_microphone").assertContentDescriptionEquals("Микрофон")
        else assertReadable(compose.onNodeWithText("Микрофон", useUnmergedTree = true))
        compose.onNodeWithTag("player_menu").assertIsDisplayed()
    }

    private fun assertReadableModeText(action: SemanticsNodeInteraction): SemanticsNodeInteraction {
        val actionTag = action.fetchSemanticsNode().config[SemanticsProperties.TestTag]
        val text = compose.onNode(hasAnyAncestor(hasTestTag(actionTag)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertReadable(text)
        return text
    }

    private fun selectPhrase(index: Int) {
        compose.waitForIdle()
        val line = acceptedTrack().lines[index]
        val saved = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
        val date = LocalDate.parse(saved.context.serviceDate)
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date,
            container.readings?.days?.singleOrNull { it.date == date.toString() })
        val bookIndex = requireNotNull(ContinuousServiceFeed(plan).indexOf("line:${line.id}"))
        // Scroll through the real UI action. A whole-book scan can inspect a disappearing
        // lazy item while its font layout is being recomposed; use its stable identity index.
        compose.onNodeWithTag("lyrics_list").performSemanticsAction(SemanticsActions.ScrollToIndex) { assertTrue(it(bookIndex)) }
        compose.waitForIdle()
        compose.onFeedLine(line).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun assertReadingSpaceAndScale(line: Int, expectedScale: Float, expectedFontSize: TextUnit = 25.sp) {
        compose.onNodeWithTag("lyrics_list").assertHeightIsAtLeast(120.dp)
        val lyric = compose.onFeedLine(acceptedTrack(), line).assertIsSelected().assertIsDisplayed()
        val layout = textLayout(lyric)
        assertEquals(expectedScale, layout.layoutInput.density.fontScale, .001f)
        assertEquals("Exact native font scale from the fixture", expectedScale.toRawBits(),
            layout.layoutInput.density.fontScale.toRawBits())
        assertEquals(expectedFontSize, layout.layoutInput.style.fontSize)
        assertEquals(36.sp, layout.layoutInput.style.lineHeight)
        assertNativeTextMetrics(layout, Density(layout.layoutInput.density.density, expectedScale))
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
    private fun acceptedTrack() = container.recordedFeedTrack(requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))))

    private fun orient(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(timeoutMillis = 10_000) { compose.activity.resources.configuration.orientation == expected }
        awaitPlatformReady()
    }

    private fun awaitPlatformReady() = withCiFailureDiagnostics("reader.awaitPlatformReady") {
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
