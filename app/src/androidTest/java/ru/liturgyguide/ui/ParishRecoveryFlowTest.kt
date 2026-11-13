package ru.liturgyguide.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** User-facing recovery on both entry screens; callbacks are isolated and do not alter app data. */
@RunWith(Parameterized::class)
class ParishRecoveryFlowTest(private val churchPage: Boolean, private val width: Int, private val scale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = Parish("recovery-a", "Храм Илии Пророка", "Храм Илии Пророка", "Воронеж", "Улица, 1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val second = first.copy(id = "recovery-b", name = "Троицкий храм", shortName = "Троицкий храм")
    private val selected = mutableStateOf(first)
    private val mounted = mutableStateOf(true)
    private val sourceSetterEnabled = mutableStateOf(true)
    private val websiteActionEnabled = mutableStateOf(true)
    private val state = mutableStateOf(InternetScheduleState(first.id, InternetScheduleStatus.NO_WEBSITE,
        checkedAt = "2026-12-31T10:00:00Z"))
    private val submitted = mutableListOf<Pair<String, String>>()
    private val sites = mutableListOf<String>()
    private val books = mutableListOf<Pair<LocalDate, WorshipKind>>()
    private val now = Instant.parse("2026-12-31T10:00:00Z")
    private val today = LocalDate.of(2026, 12, 31)
    private val contentTag get() = if (churchPage) "church_content" else "main_content"
    private val cardTag get() = if (churchPage) "church_schedule_card" else "today_schedule_card"
    private val editTag get() = if (churchPage) "edit_schedule_website" else "today_edit_schedule_website"
    private val refreshTag get() = if (churchPage) "refresh_schedule" else "today_refresh_schedule"
    private val websiteTag get() = if (churchPage) "open_schedule_website" else "today_schedule_source"
    private val sourceChoiceTag get() = if (churchPage) "church_schedule_source_choice" else "today_schedule_source_choice"
    private val emptyTag get() = if (churchPage) "church_schedule_empty" else "today_schedule_empty"
    private val receiptTag get() = if (churchPage) "church_schedule_checked" else "today_schedule_checked"
    private val loadingTag get() = if (churchPage) "schedule_loading_summary" else "today_schedule_loading"
    private var previousFont: String? = null
    private var previousSize: String? = null
    private var displayChanged = false
    private var contentSet = false

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before fun configureDisplay() {
        previousFont = shell("settings get system font_scale").takeUnless { it == "null" }
        previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        displayChanged = true
        shell("settings put system font_scale $scale")
        shell("wm size ${(width * density).roundToInt()}x${(800 * density).roundToInt()}")
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = abs(it.resources.configuration.fontScale - scale) < .001f &&
                    abs(it.resources.configuration.screenWidthDp - width) <= 1 && it.window.decorView.hasWindowFocus()
            }
            ready
        }
    }

    @After fun restoreDisplay() {
        try {
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        } finally {
            if (displayChanged) try { shell("wm size ${previousSize ?: "reset"}") }
            finally { shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale") }
        }
    }

    @Test fun emptyScheduleOffersLocalWebsiteInputAndKeepsRefreshFeedback() {
        launch()
        val edit = reveal(contentTag, editTag)
        edit.assertHeightIsAtLeast(48.dp).assert(hasAnyAncestor(hasTestTag(cardTag)))
        val refreshBounds = compose.onNodeWithTag(refreshTag).getUnclippedBoundsInRoot()
        val editBounds = edit.getUnclippedBoundsInRoot()
        assertTrue("The two actions need at least 12 dp between their touch targets",
            (editBounds.top - refreshBounds.bottom).value >= 12f)
        assertLabelFits("Указать сайт")
        capture("empty", "parish_recovery_host")
        edit.performClick()
        compose.onNodeWithTag("schedule_source_content").assertWidthIsEqualTo(width.dp)
        compose.onNodeWithTag("schedule_source_church").assertTextEquals(first.shortName)
        capture("source-form", "schedule_source_content")

        reveal("schedule_source_content", "schedule_website_input").performTextInput("http://127.0.0.1/private")
        reveal("schedule_source_content", "save_schedule_website").performClick()
        reveal("schedule_source_content", "schedule_website_input")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        val published = "http://example.org/schedule/?week=2026-12-31"
        compose.onNodeWithTag("schedule_website_input").performTextReplacement("  $published  ")
        reveal("schedule_source_content", "save_schedule_website").assertIsEnabled().performClick()
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        reveal(contentTag, loadingTag).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(first.id to published), submitted)
            state.value = state.value.copy(status = InternetScheduleStatus.UNSUPPORTED, loading = false,
                website = published, sourceUrl = published, checkedAt = "2026-12-31T10:01:00Z")
        }
        reveal(contentTag, websiteTag).assertHeightIsAtLeast(48.dp)
            .assert(hasAnyAncestor(hasTestTag(cardTag))).performClick()
        assertLabelFits("Открыть сайт")
        reveal(contentTag, receiptTag)
            .assertTextEquals("Не удалось прочитать даты и время\nПопытка обновления: 31 декабря 2026, 13:01:00")
        reveal(contentTag, refreshTag).assertIsEnabled()
        capture("checked", "parish_recovery_host")
        compose.runOnIdle { assertEquals(listOf(published), sites) }
    }

    @Test fun anUnverifiedSourceCanBeChosenFromTheScheduleCardWithoutAutomaticAcceptance() {
        val website = "https://example.org/parish/schedule/?week=2026-12-31"
        state.value = state.value.copy(status = InternetScheduleStatus.UNSUPPORTED,
            website = website, sourceUrl = website, sourceNeedsVerification = true)
        launch()
        reveal(contentTag, websiteTag).assertHeightIsAtLeast(48.dp)
            .assert(hasAnyAncestor(hasTestTag(cardTag)))
        val choose = reveal(contentTag, sourceChoiceTag)
        choose.assertHeightIsAtLeast(48.dp).assert(hasAnyAncestor(hasTestTag(cardTag)))
        val websiteBounds = compose.onNodeWithTag(websiteTag).getUnclippedBoundsInRoot()
        val choiceBounds = choose.getUnclippedBoundsInRoot()
        assertTrue("The source choice needs at least 12 dp after the website touch target",
            (choiceBounds.top - websiteBounds.bottom).value >= 12f)
        assertLabelFits("Указать источник")
        capture("unverified-source-choice", "parish_recovery_host")
        choose.performClick()
        reveal("schedule_source_content", "schedule_website_input")
        assertEquals("The existing editor must start with this church's website", website,
            compose.onNodeWithTag("schedule_website_input").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("schedule_source_church").assertTextEquals(first.shortName)
        compose.runOnIdle { assertTrue(submitted.isEmpty()); assertTrue(sites.isEmpty()) }
        capture("unverified-source-form", "schedule_source_content")
        reveal("schedule_source_content", "cancel_schedule_website").performClick()
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Cancellation must not accept this source", submitted.isEmpty()) }

        reveal(contentTag, sourceChoiceTag).performClick()
        reveal("schedule_source_content", "schedule_website_input")
        assertEquals(website, compose.onNodeWithTag("schedule_website_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        val submit = reveal("schedule_source_content", "save_schedule_website").assertIsEnabled()
        val oldSubmit = action(submit)
        submit.performClick()
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        compose.runOnIdle {
            oldSubmit()
            assertEquals("Only an explicit submit may accept the exact URL, once", listOf(first.id to website), submitted)
            assertTrue(sites.isEmpty())
        }
        reveal(contentTag, loadingTag).assertIsDisplayed()
        compose.onNodeWithTag(sourceChoiceTag).assertDoesNotExist()
        capture("unverified-source-loading", "parish_recovery_host")
    }

    @Test fun sourceChoiceShortcutDoesNotBypassRejectedWithdrawnLoadingOrUnsafeSources() {
        val website = "https://example.org/parish/schedule/"
        val unverified = state.value.copy(status = InternetScheduleStatus.UNSUPPORTED,
            website = website, sourceUrl = website, sourceNeedsVerification = true)
        state.value = unverified
        launch()
        reveal(contentTag, sourceChoiceTag).assertIsDisplayed()
        listOf(
            unverified.copy(loading = true),
            unverified.copy(publicationRejected = true),
            unverified.copy(sourceHasWithdrawals = true),
            unverified.copy(sourceNeedsVerification = false),
            unverified.copy(website = "http://127.0.0.1/private", sourceUrl = "http://127.0.0.1/private"),
            unverified.copy(parishId = second.id)
        ).forEach { guarded ->
            compose.runOnIdle { state.value = guarded }
            // Reveal an eager child of the schedule card so absence is not caused by LazyColumn recycling.
            reveal(contentTag, emptyTag).assert(hasAnyAncestor(hasTestTag(cardTag)))
            compose.onNodeWithTag(sourceChoiceTag).assertDoesNotExist()
            compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        }
        compose.runOnIdle { state.value = unverified; sourceSetterEnabled.value = false }
        reveal(contentTag, emptyTag)
        compose.onNodeWithTag(sourceChoiceTag).assertDoesNotExist()
        compose.runOnIdle { sourceSetterEnabled.value = true }
        reveal(contentTag, sourceChoiceTag).assertIsDisplayed()
        if (!churchPage) {
            compose.runOnIdle { websiteActionEnabled.value = false }
            reveal(contentTag, editTag).assertIsDisplayed()
            compose.onNodeWithTag(sourceChoiceTag).assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(submitted.isEmpty()); assertTrue(sites.isEmpty()) }
    }

    @Test fun aChangedChurchRejectsOldSubmitActionsAndDoesNotReuseTheDraft() {
        launch()
        reveal(contentTag, editTag).performClick()
        reveal("schedule_source_content", "schedule_website_input").performTextInput("https://example.org/old-church/")
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        // A known manual URL may replace loading, but only for the editor's current owner.
        val beforeChange = action(reveal("schedule_source_content", "save_schedule_website").assertIsEnabled())
        compose.runOnIdle {
            selected.value = second
            state.value = InternetScheduleState(second.id, InternetScheduleStatus.NO_WEBSITE)
        }
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        compose.runOnIdle { beforeChange(); assertTrue(submitted.isEmpty()) }
        reveal(contentTag, editTag).performClick()
        compose.onNodeWithTag("schedule_source_church").assertTextEquals(second.shortName)
        reveal("schedule_source_content", "schedule_website_input")
        val value = compose.onNodeWithTag("schedule_website_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("A different church must start with its own empty source", "", value)
        val correct = "https://example.org/second-church/"
        compose.onNodeWithTag("schedule_website_input").performTextInput(correct)
        reveal("schedule_source_content", "save_schedule_website").performClick()
        compose.runOnIdle { assertEquals(listOf(second.id to correct), submitted) }
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
    }

    @Test fun cancelSystemDismissAndDisposalInvalidateOldActionsEvenAfterReopeningTheSameChurch() {
        launch()
        fun openDraft(url: String): () -> Boolean {
            reveal(contentTag, editTag).performClick()
            reveal("schedule_source_content", "schedule_website_input")
            assertEquals("A new editor must not restore a cancelled draft", "", compose.onNodeWithTag("schedule_website_input")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("schedule_website_input").performTextInput(url)
            return action(reveal("schedule_source_content", "save_schedule_website").assertIsEnabled())
        }
        val afterCancel = openDraft("https://example.org/cancelled/")
        val oldCancel = action(reveal("schedule_source_content", "cancel_schedule_website"))
        compose.onNodeWithTag("cancel_schedule_website").performClick()
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        compose.runOnIdle { afterCancel(); assertTrue(submitted.isEmpty()) }

        val afterBack = openDraft("https://example.org/dismissed/")
        compose.runOnIdle { afterCancel(); oldCancel(); assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("schedule_source_content").assertIsDisplayed()
        closeSoftKeyboardWithCiDiagnostics()
        pressBackWithCiDiagnostics()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("schedule_source_content").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { afterBack(); assertTrue(submitted.isEmpty()) }

        val afterDisposal = openDraft("https://example.org/removed/")
        compose.runOnIdle { mounted.value = false }
        compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
        compose.runOnIdle { afterDisposal(); assertTrue(submitted.isEmpty()); mounted.value = true }
        val valid = openDraft("https://example.org/current/")
        compose.runOnIdle { afterCancel(); afterBack(); afterDisposal(); assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("schedule_source_content").assertIsDisplayed()
        reveal("schedule_source_content", "save_schedule_website").performClick()
        compose.runOnIdle {
            valid(); valid()
            assertEquals(listOf(first.id to "https://example.org/current/"), submitted)
        }
    }

    @Test fun aMissingOrLoadingScheduleDoesNotBlockEveningTextsOnTheSelectedDay() {
        launch()
        val tomorrowTag = if (churchPage) "schedule_tomorrow" else "today_day_tomorrow"
        val todayTag = if (churchPage) "schedule_today" else "today_day_today"
        val otherTag = if (churchPage) "church_other_services" else "today_other_services"
        reveal(contentTag, tomorrowTag).performClick()
        reveal(contentTag, otherTag).assertIsEnabled().performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 1 января 2027")
        reveal("service_book_chooser", "service_book_choose_vespers").performClick()
        compose.onNodeWithTag("service_book_chooser").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(LocalDate.of(2027, 1, 1) to WorshipKind.VESPERS), books)
            state.value = state.value.copy(status = InternetScheduleStatus.UNSUPPORTED, loading = true)
        }
        reveal(contentTag, todayTag).performClick()
        reveal(contentTag, otherTag).assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 31 декабря 2026")
        reveal("service_book_chooser", "service_book_choose_vigil").performClick()
        compose.runOnIdle {
            assertEquals(listOf(LocalDate.of(2027, 1, 1) to WorshipKind.VESPERS, today to WorshipKind.VIGIL), books)
            assertTrue(submitted.isEmpty())
        }
    }

    private fun launch() {
        // Native display configuration also reaches ModalBottomSheet's separate window.
        compose.setContent {
                GuideTheme(scale > 1f) { Box(Modifier.fillMaxSize().testTag("parish_recovery_host")) {
                    val parish = selected.value
                    val current = state.value
                    val setSource: (String) -> Unit = { url ->
                        submitted += parish.id to url
                        state.value = current.copy(loading = true)
                    }
                    if (mounted.value) {
                        if (churchPage) ChurchScreen(listOf(first, second), parish.id, today,
                            onSelect = { _, _ -> ChurchSelectionResult.FAILED }, onOpenUrl = { sites += it }, onBack = { error("Recovery must stay here") },
                            searchQuery = "", onSearchQueryChange = {}, internetSchedule = current,
                            onRefreshSchedule = { state.value = current.copy(loading = true) },
                            onSetScheduleSource = setSource.takeIf { sourceSetterEnabled.value },
                            onReadLiturgy = { error("The requested evening book is not a liturgy") },
                            onReadServiceText = { date, worship -> books += date to worship }, now = now)
                        else TodayScreen(parish, now, current, null, null,
                            onFindChurch = {}, onChurchDetails = { error("Recovery must not require another screen") },
                            onOpenBook = { date, worship, _ -> books += date to worship }, onResumeBook = {},
                            onRefresh = { state.value = current.copy(loading = true) }, onResumeLegacy = null, bottomBar = {},
                            onOpenScheduleSource = scheduleSourceFor(parish, current)?.takeIf { websiteActionEnabled.value }
                                ?.let { url -> { sites += url; Unit } },
                            onSetScheduleSource = setSource.takeIf { sourceSetterEnabled.value })
                    }
                } }
        }
        contentSet = true
        compose.onNodeWithTag("parish_recovery_host").assertWidthIsEqualTo(width.dp)
    }

    private fun reveal(containerTag: String, tag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        container.performScrollToIndex(0)
        repeat(40) {
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull() != null) {
                val viewport = container.getBoundsInRoot()
                val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                    return compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            container.performTouchInput {
                swipe(Offset(centerX, height * .8f), Offset(centerX, height * .55f), durationMillis = 800)
            }
        }
        throw AssertionError("Could not reveal $tag by touch in $containerTag")
    }

    private fun action(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun assertLabelFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTextFitsLayout(results.single(), text)
        Regex("[А-Яа-яЁё]+").findAll(text).forEach { word ->
            assertEquals("Button words must not break across lines: ${word.value}",
                results.single().getLineForOffset(word.range.first),
                results.single().getLineForOffset(word.range.last))
        }
        assertEquals("The actual window must use the requested font scale", scale,
            results.single().layoutInput.density.fontScale, .001f)
    }

    private fun capture(name: String, tag: String) {
        val run = InstrumentationRegistry.getArguments().getString("parishRecoveryRun") ?: return
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = "${if (churchPage) "church" else "today"}-${width}dp-font${(scale * 100).toInt()}"
        val output = File(requireNotNull(context.getExternalFilesDir("parish-recovery")), "$run/$profile")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0} width={1} font={2}")
        fun profiles() = listOf(false, true).flatMap { church -> listOf(arrayOf<Any>(church, 360, 1f), arrayOf<Any>(church, 320, 2f)) }
    }
}
