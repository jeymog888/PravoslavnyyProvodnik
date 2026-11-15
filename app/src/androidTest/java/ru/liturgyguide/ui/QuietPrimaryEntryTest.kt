package ru.liturgyguide.ui

import android.view.ViewConfiguration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpRect
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.book.ServiceBookSelection
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate

private val QUIET_PARISH = Parish("osm:way:77503842", "Храм Николая Чудотворца (Воронеж)",
    "Храм Николая Чудотворца (Воронеж)", "Воронеж", "Воронеж, улица Таранченко, 19А", "", "",
    "Europe/Moscow", "", "", "", "", emptyList(), "", "", 51.65962070, 39.21276430)
private val QUIET_NOW = Instant.parse("2026-11-12T06:00:00Z")
private val QUIET_DATE = LocalDate.of(2026, 11, 12)
private val QUIET_HOURS = ParishService("2026-11-12", "09:00", "Часы. Литургия. Панихида", "service_block")

/** Native display/font profiles, production ChurchScreen and an exact current publication. */
@RunWith(Parameterized::class)
class QuietPrimaryEntryLayoutTest(private val dark: Boolean, width: Int, height: Int, scale: Float) :
    FallbackReadingHarness(true, width, height, scale, dark) {
    @Test fun freshPublishedHoursUsesCompactChooserAndMeasuresTheWholeReadingGroup() = evidence("quiet-hours-$dark") { report ->
        // Missing chooser intentionally keeps the established standalone full entry.
        fixture = FallbackFixture(now = QUIET_NOW, state = publishedState(QUIET_HOURS, QUIET_NOW), allowChooser = false)
        launch()
        val fullInitial = JSONObject(); report.put("fullInitial", fullInitial)
        observeInitialAction(officialTag, fullInitial)
        capture("full-initial", fullInitial)
        reveal(officialTag)
        val fullSchedule = bounds("church_schedule_card")
        reveal(primaryTag).assertIsEnabled()
        val fullReading = bounds(readingCardTag)
        val fullTotal = (fullSchedule.bottom - fullSchedule.top).value + (fullReading.bottom - fullReading.top).value + 20f
        report.put("fullScheduleDp", rectangle(fullSchedule)).put("fullReadingDp", rectangle(fullReading))
            .put("fullReadingAndScheduleHeightDp", fullTotal)
        capture("full-reading", report)

        replace(fixture.copy(allowChooser = true), freshScreen = true)
        val compactInitial = JSONObject(); report.put("compactInitial", compactInitial)
        observeInitialAction(officialTag, compactInitial)
        if (width == 411) assertTrue("The fresh published action is visible before scrolling", compactInitial.getBoolean("fullyVisible"))
        capture("compact-initial", compactInitial)
        reveal(officialTag).assertIsEnabled().assertHeightIsAtLeast(56.dp)
        val compactSchedule = bounds("church_schedule_card")
        compose.onNodeWithTag("church_service_title").assertTextEquals(QUIET_HOURS.title)
        val scope = ServiceBookSelection.hoursLiturgyScope(QUIET_HOURS.title)
        compose.onNodeWithTag("church_service_scope").assertTextEquals(scope)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("church_service_scope").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertTextFitsLayout(layouts.single(), scope)
        report.put("scopeLineCount", layouts.single().lineCount).put("scopeHeightPx", layouts.single().size.height)
        compose.onNodeWithTag(officialTag).performClick()
        compose.runOnIdle { assertEquals(listOf(QUIET_HOURS), calls.services); assertTrue(calls.books.isEmpty()) }
        val route = requireNotNull(ServiceBookSelection.resolve(calls.services.single(), QUIET_PARISH))
        assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY),
            requireNotNull(route.journeyContext).parts.map { it.worship })
        assertEquals(QUIET_HOURS.title, route.journeyContext!!.title)
        reveal(otherTag).assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag(otherTag).assertCountEquals(1)
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        compose.onNodeWithText("Читать службу").assertDoesNotExist()
        labelFits(otherTag, "Выбрать другой текст")
        val compactReading = bounds(readingCardTag)
        val compactTotal = (compactSchedule.bottom - compactSchedule.top).value + (compactReading.bottom - compactReading.top).value + 20f
        assertTrue("The same-profile compact group must be shorter", compactTotal < fullTotal)
        report.put("compactScheduleDp", rectangle(compactSchedule)).put("compactReadingDp", rectangle(compactReading))
            .put("compactReadingAndScheduleHeightDp", compactTotal).put("heightSavedDp", fullTotal - compactTotal)
            .put("dark", dark).put("publishedParts", "HOUR3/HOUR6/LITURGY")
        capture("compact-reading", report)
        compose.onNodeWithTag(otherTag).performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 12 ноября 2026")
        reveal("service_book_choose_vespers", "service_book_chooser").performClick()
        compose.runOnIdle { assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.VESPERS)), calls.books) }
        report.put("exactPublishedOccurrenceAndCivilChooser", true)

        // The base scope has its own whole-screen and text geometry in every profile.
        val baseService = QUIET_HOURS.copy(title = "Часы. Литургия")
        replace(FallbackFixture(now = QUIET_NOW, state = publishedState(baseService, QUIET_NOW)), freshScreen = true)
        val baseInitial = JSONObject(); report.put("baseScopeInitial", baseInitial)
        observeInitialAction(officialTag, baseInitial)
        capture("base-scope-initial", baseInitial)
        val baseScope = ServiceBookSelection.hoursLiturgyScope(baseService.title)
        reveal("church_service_scope").assertTextEquals(baseScope)
        val baseLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("church_service_scope").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(baseLayouts))
        }
        assertTextFitsLayout(baseLayouts.single(), baseScope)
        val baseSchedule = bounds("church_schedule_card")
        reveal(otherTag).assertIsEnabled()
        val baseReading = bounds(readingCardTag)
        report.put("baseScopeLineCount", baseLayouts.single().lineCount).put("baseScopeHeightPx", baseLayouts.single().size.height)
            .put("baseScheduleDp", rectangle(baseSchedule)).put("baseReadingDp", rectangle(baseReading))
            .put("baseReadingAndScheduleHeightDp", (baseSchedule.bottom - baseSchedule.top).value + (baseReading.bottom - baseReading.top).value + 20f)
        capture("base-scope-reading", report)
    }

    @Test fun freshUnspecifiedEveningRetainsChoiceAndShiftsTheBookOnlyOnce() = evidence("quiet-evening-$dark") { report ->
        val service = QUIET_HOURS.copy(time = "18:00", title = "Вечернее богослужение")
        fixture = FallbackFixture(now = QUIET_NOW, state = publishedState(service, QUIET_NOW))
        launch()
        observeInitialAction(officialTag, report)
        capture("evening-initial", report)
        reveal(officialTag).performClick()
        compose.runOnIdle { assertEquals(listOf(service), calls.services); assertTrue(calls.books.isEmpty()) }
        assertNull(ServiceBookSelection.resolve(service, QUIET_PARISH))
        val evening = requireNotNull(ServiceBookSelection.eveningTexts(service, QUIET_PARISH, WorshipKind.VESPERS))
        assertEquals("2026-11-13", evening.activeKey.date)
        assertEquals("2026-11-12", evening.journeyContext!!.civilDate)
        assertEquals(listOf(WorshipKind.VESPERS, WorshipKind.MATINS), evening.journeyContext!!.parts.map { it.worship })
        reveal(otherTag).assertIsEnabled().performClick()
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 12 ноября 2026")
        reveal("service_book_choose_vespers", "service_book_chooser").performClick()
        compose.runOnIdle { assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.VESPERS)), calls.books) }
        report.put("choicePreserved", true).put("chooserCivilDate", QUIET_DATE.toString())
            .put("bookLiturgicalDate", evening.activeKey.date)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0} width={1} height={2} font={3}")
        fun profiles() = listOf(false, true).flatMap { dark -> listOf(
            arrayOf<Any>(dark, 411, 914, 1f), arrayOf<Any>(dark, 320, 800, 2f)) }
    }
}

/** No fixture generation changes are permitted during the asynchronous transitions. */
@RunWith(Parameterized::class)
class QuietPrimaryEntryContractTest(private val dark: Boolean) : FallbackReadingHarness(true, 411, 914, 1f, dark) {
    @Test fun latePublicationKeepsFullControlsStationaryAndAtAScrolledAnchor() = evidence("quiet-late-$dark") { report ->
        fixture = FallbackFixture(now = QUIET_NOW, state = InternetScheduleState(QUIET_PARISH.id, loading = true))
        launch()
        for ((index, scrolled) in listOf(false, true).withIndex()) {
            if (index > 0) replace(FallbackFixture(now = QUIET_NOW,
                state = InternetScheduleState(QUIET_PARISH.id, loading = true)), freshScreen = true)
            val initial = JSONObject(); report.put(if (scrolled) "loadingScrolled" else "loadingStationary", initial)
            observeInitialAction(primaryTag, initial)
            if (!scrolled) assertTrue(initial.getBoolean("fullyVisible"))
            reveal(primaryTag).assertIsEnabled()
            if (scrolled) alignReadingCardWithViewport()
            val cardBefore = bounds(readingCardTag)
            val density = compose.activity.resources.displayMetrics.density
            fun pixelWidth(rect: DpRect) = ((rect.right - rect.left).value * density).roundToInt()
            fun pixelHeight(rect: DpRect) = ((rect.bottom - rect.top).value * density).roundToInt()
            val actionBefore = bounds(primaryTag)
            val nodeBefore = compose.onNodeWithTag(primaryTag).fetchSemanticsNode().id
            val labelBefore = compose.onNodeWithTag(primaryTag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            val scrollBefore = compose.onNodeWithTag(contentTag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            capture("${index}-before", initial)
            replace(fixture.copy(state = publishedState(QUIET_HOURS, QUIET_NOW)), freshScreen = false)
            // The untouched ordering contract prioritizes publication on an
            // unscrolled screen. A reached scroll anchor retains its exact bounds.
            assertEquals(scrollBefore, compose.onNodeWithTag(contentTag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
            if (scrolled) {
                assertEquals("Retain the entire reached full card", cardBefore, bounds(readingCardTag))
                assertEquals("Retain the action hit target", actionBefore, bounds(primaryTag))
                assertEquals(nodeBefore, compose.onNodeWithTag(primaryTag).fetchSemanticsNode().id)
            } else {
                reveal(primaryTag)
                assertEquals(pixelWidth(cardBefore), pixelWidth(bounds(readingCardTag)))
                assertEquals(pixelHeight(cardBefore), pixelHeight(bounds(readingCardTag)))
                assertEquals(pixelWidth(actionBefore), pixelWidth(bounds(primaryTag)))
                assertEquals(pixelHeight(actionBefore), pixelHeight(bounds(primaryTag)))
            }
            compose.onNodeWithTag(primaryTag).assertTextEquals(labelBefore)
            initial.put("cardBeforeDp", rectangle(cardBefore)).put("cardAfterDp", rectangle(bounds(readingCardTag)))
                .put("actionBeforeDp", rectangle(actionBefore)).put("actionAfterDp", rectangle(bounds(primaryTag)))
                .put("label", labelBefore).put("scrollBefore", scrollBefore)
            capture("${index}-after", initial)
            reveal(primaryTag).performClick()
            compose.runOnIdle { assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.LITURGY)), calls.books) }
            reveal(officialTag, searchBackwards = true).performClick()
            compose.runOnIdle { assertEquals(listOf(QUIET_HOURS), calls.services) }
            if (scrolled) {
                // Removing the read callback must still expose the current sole
                // library callback in the retained full form.
                replace(fixture.copy(allowRead = false, allowOfficial = false), freshScreen = false)
                compose.onNodeWithTag(primaryTag).assertDoesNotExist()
                compose.onNodeWithTag(officialTag).assertDoesNotExist()
                reveal(otherTag).assertIsEnabled().performClick()
                reveal("service_book_choose_vespers", "service_book_chooser").performClick()
                compose.runOnIdle {
                    assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.VESPERS)), calls.books)
                    assertTrue(calls.services.isEmpty())
                }
                initial.put("currentSoleChooserAfterReadRemoval", true)
            }
        }
    }

    @Test fun compactLossShowsFullAndRecoveryCannotCollapseItAgain() = evidence("quiet-loss-$dark") { report ->
        fixture = FallbackFixture(now = QUIET_NOW, state = publishedState(QUIET_HOURS, QUIET_NOW))
        launch()
        reveal(otherTag).assertIsEnabled()
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        replace(fixture.copy(state = fixture.state.copy(sourceNeedsVerification = true)), freshScreen = false)
        reveal(primaryTag).assertIsEnabled()
        alignReadingCardWithViewport()
        val before = bounds(readingCardTag)
        val action = bounds(primaryTag)
        val node = compose.onNodeWithTag(primaryTag).fetchSemanticsNode().id
        capture("lost-proof-full", report)
        replace(fixture.copy(state = publishedState(QUIET_HOURS.copy(time = "10:00"), QUIET_NOW)), freshScreen = false)
        assertEquals(before, bounds(readingCardTag))
        assertEquals(action, bounds(primaryTag))
        assertEquals(node, compose.onNodeWithTag(primaryTag).fetchSemanticsNode().id)
        capture("recovered-keeps-full", report)
        reveal(primaryTag).performClick()
        compose.runOnIdle { assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.LITURGY)), calls.books) }
        reveal(officialTag).performClick()
        compose.runOnIdle { assertEquals(listOf(QUIET_HOURS.copy(time = "10:00")), calls.services) }
        // Removing the current callback must remove its control even with a saved FULL form.
        replace(fixture.copy(allowRead = false, allowOfficial = false), freshScreen = false)
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        compose.onNodeWithTag(officialTag).assertDoesNotExist()
        reveal(otherTag).assertIsEnabled().performClick()
        reveal("service_book_choose_matins", "service_book_chooser").performClick()
        compose.runOnIdle {
            assertEquals(listOf(FallbackBookRequest(QUIET_DATE, WorshipKind.MATINS)), calls.books)
            assertTrue(calls.services.isEmpty())
        }
        report.put("workingFallbackRestored", true).put("noRecoveryOscillation", true).put("currentCallbacksOnly", true)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}

private fun quietState(church: Parish, service: ParishService) = InternetScheduleState(church.id,
    InternetScheduleStatus.READY, website = "https://parish.example/", sourceUrl = "https://parish.example/schedule/",
    checkedAt = QUIET_NOW.toString(), publication = SchedulePublication(church.id, church.timezone,
        "https://parish.example/", "https://parish.example/schedule/", QUIET_NOW.toString(),
        listOf(InternetService(service.date, service.time, service.title, service.kind))))

private fun ComposeContentTestRule.quietReveal(tag: String, backwards: Boolean = false): SemanticsNodeInteraction {
    repeat(32) {
        waitForIdle()
        val viewport = onNodeWithTag("church_content").getBoundsInRoot()
        val target = onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
        val bounds = target?.let { onNodeWithTag(tag).getUnclippedBoundsInRoot() }
        if (bounds != null && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) return onNodeWithTag(tag).assertIsDisplayed()
        val back = bounds?.let { it.top < viewport.top } ?: backwards
        onNodeWithTag("church_content").performTouchInput {
            swipe(Offset(centerX, height * (if (back) .35f else .8f)),
                Offset(centerX, height * (if (back) .7f else .45f)), durationMillis = 500)
        }
    }
    throw AssertionError("Could not reveal $tag with bounded real touch")
}

private fun ComposeContentTestRule.quietReachReadingAnchor() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val slopDp = ViewConfiguration.get(context).scaledTouchSlop / context.resources.displayMetrics.density
    val steps = JSONArray()
    val report = JSONObject().put("touchSlopDp", slopDp).put("reached", false).put("steps", steps)
    val directory = requireNotNull(context.getExternalFilesDir("quiet-primary-entry-anchor"))
    val journal = File(directory, "anchor-${java.util.UUID.randomUUID()}.json")
    var touched = false
    try {
        repeat(24) {
            waitForIdle()
            val viewport = onNodeWithTag("church_content").getBoundsInRoot()
            val card = onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot()
            val scroll = onNodeWithTag("church_content").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val step = JSONObject().put("cardTopDp", card.top.value).put("cardBottomDp", card.bottom.value)
                .put("viewportTopDp", viewport.top.value).put("viewportBottomDp", viewport.bottom.value)
                .put("scroll", scroll)
            steps.put(step)
            if (touched && card.top <= viewport.top && card.bottom > viewport.top + 8.dp) {
                assertTrue(scroll > 0f)
                report.put("reached", true)
                return
            }
            val viewportHeight = (viewport.bottom - viewport.top).value
            // Every new gesture must clear the real device slop before moving the list.
            val distance = (card.top.value - viewport.top.value + slopDp + 8f)
                .coerceIn(2f * slopDp + 8f, viewportHeight * .3f)
            step.put("requestedSwipeDp", distance)
            val fraction = distance / viewportHeight
            onNodeWithTag("church_content").performTouchInput {
                swipe(Offset(centerX, height * .8f), Offset(centerX, height * (.8f - fraction)), durationMillis = 700)
            }
            touched = true
        }
        throw AssertionError("Reach the first-visible church_reading anchor with bounded real touch: $steps")
    } finally {
        journal.writeText(report.toString(2))
    }
}

/** Saved production presentation state, including source-view replacement, rather than a test-only latch. */
@RunWith(Parameterized::class)
class QuietPrimaryEntryRestorationTest(private val change: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun fullFormSurvivesSourceViewAndSameOwnerRestorationOnly() {
        var suppliedChurch = QUIET_PARISH
        var suppliedDate = QUIET_DATE
        var suppliedState = quietState(QUIET_PARISH, QUIET_HOURS.copy(title = "Молебен"))
        lateinit var currentState: MutableState<InternetScheduleState>
        var sourceView by mutableStateOf<Any>(Any())
        val reads = mutableListOf<Pair<String, LocalDate>>()
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val church = remember { suppliedChurch }
            val date = remember { suppliedDate }
            val state = remember { mutableStateOf(suppliedState) }
            SideEffect { currentState = state }
            // This focused viewport permits a real first-visible reading anchor
            // with the production trailing content, without a test/product spacer.
            GuideTheme(false) { Box(Modifier.size(411.dp, 280.dp)) {
                ChurchScreen(listOf(church), church.id, date,
                    onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {},
                    showBack = false, searchQuery = "", onSearchQueryChange = {}, internetSchedule = state.value,
                    onReadLiturgy = { reads += church.id to it }, onReadServiceText = { day, _ -> reads += church.id to day },
                    onOpenService = {}, now = QUIET_NOW, sourceSearchViewKey = sourceView,
                    readingEntryLabel = { "Открыть литургию" })
            } }
        }
        compose.quietReveal("church_read_liturgy")
        compose.quietReachReadingAnchor()
        val before = compose.onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot()
        val actionBefore = compose.onNodeWithTag("church_read_liturgy").getUnclippedBoundsInRoot()
        val labelBefore = compose.onNodeWithTag("church_read_liturgy").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        compose.runOnIdle {
            currentState.value = quietState(QUIET_PARISH, QUIET_HOURS)
            sourceView = Any()
        }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot())
        assertEquals(actionBefore, compose.onNodeWithTag("church_read_liturgy").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("church_read_liturgy").assertTextEquals(labelBefore)
        compose.runOnIdle {
            if (change == "next-date") suppliedDate = QUIET_DATE.plusDays(1)
            if (change == "next-parish") suppliedChurch = QUIET_PARISH.copy(id = "quiet-other-parish")
            suppliedState = quietState(suppliedChurch, QUIET_HOURS.copy(date = suppliedDate.toString()))
        }
        // Plain constructor inputs are consumed only by the replacement composition.
        restore.emulateSavedInstanceStateRestore()
        if (change == "same-owner") {
            compose.quietReveal("church_read_liturgy").assertTextEquals(labelBefore).performClick()
        } else {
            compose.quietReveal("church_other_services")
            compose.onNodeWithTag("church_read_liturgy").assertDoesNotExist()
            compose.onNodeWithTag("church_other_services").performClick()
            compose.onNodeWithTag("service_book_choose_liturgy").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf(suppliedChurch.id to suppliedDate), reads) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "restore={0}") fun owners() = listOf(
            arrayOf<Any>("same-owner"), arrayOf<Any>("next-date"), arrayOf<Any>("next-parish"))
    }
}

private data class QuietReviewCase(val mode: String, val published: Boolean, val personal: Boolean,
    val read: Boolean, val chooser: Boolean, val expected: ChurchReadingEntry, val pinPersonalFirst: Boolean = false)

/** The production source-review scroll consumes the same item count as the renderer. */
@RunWith(Parameterized::class)
class QuietPrimaryEntrySourceReviewTest(private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun retainedControls00PersonalAddedAndRemovedUseOnlyCurrentActionsAndReviewTarget() {
        var personal by mutableStateOf(false)
        var callbackVersion by mutableIntStateOf(0)
        var request by mutableIntStateOf(0)
        val reads = mutableListOf<Pair<Int, LocalDate>>()
        val chooserCalls = mutableListOf<Pair<LocalDate, WorshipKind>>()
        val unsupported = QUIET_HOURS.copy(title = "Молебен")
        val owner = ScheduleEntityIdentity.fingerprint(QUIET_PARISH)
        val candidate = ScheduleSourceCandidate(selectedIdentity = owner,
            provider = "public_index", providerLabel = "Справочник", providerObjectId = "quiet-personal",
            name = QUIET_PARISH.name, address = QUIET_PARISH.address,
            sourceUrl = "https://alternate.example/schedule/", evidenceUrl = "https://index.example/church/quiet-personal",
            evidenceSha256 = "b".repeat(64), checkedAt = QUIET_NOW.toString(),
            latitude = QUIET_PARISH.latitude!!, longitude = QUIET_PARISH.longitude!!,
            outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте название и адрес")
        val own = quietState(QUIET_PARISH, unsupported).copy(sourceCandidates = listOf(candidate), candidateSearchIdentity = owner)
        compose.setContent { GuideTheme(dark) { Box(Modifier.size(411.dp, 914.dp)) {
            // Each recomposition supplies a new capture. A retained callback would
            // report an earlier version even though parish/date remain identical.
            val version = callbackVersion
            ChurchScreen(listOf(QUIET_PARISH), QUIET_PARISH.id, QUIET_DATE,
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                showBack = false, searchQuery = "", onSearchQueryChange = {}, internetSchedule = own,
                onReadLiturgy = { reads += version to it },
                onReadServiceText = { day, kind -> chooserCalls += day to kind },
                onOpenService = {}, onConfirmScheduleCandidate = {}, now = QUIET_NOW,
                userStarts = if (personal) listOf(UserServiceStart(QUIET_PARISH.id, QUIET_DATE.toString(), "08:30")) else emptyList(),
                sourceReviewRequest = request, sourceReviewIdentity = owner)
        } } }
        fun reviewCurrentSource() {
            compose.quietReveal("church_details")
            compose.runOnIdle { request++ }
            compose.waitForIdle()
            compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed().assertTextEquals("Найденные сайты")
            val heading = compose.onNodeWithTag("schedule_sources_heading").getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
            assertTrue(heading.top >= viewport.top - 1.dp && heading.bottom <= viewport.bottom + 1.dp)
            compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertExists()
        }

        // Unsupported publication establishes the FULL outlined/no-chooser form.
        compose.quietReveal("church_read_liturgy").assertTextEquals("Текст литургии на этот день").performClick()
        compose.onNodeWithTag("church_other_services").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(0 to QUIET_DATE), reads) }
        reviewCurrentSource()

        compose.runOnIdle { personal = true; callbackVersion = 1 }
        compose.quietReveal("personal_time_to_service").assertIsEnabled()
        compose.onAllNodesWithTag("personal_time_to_service").assertCountEquals(1)
        compose.onNodeWithTag("personal_start_source").assertTextEquals("Начало указано вами · Местное время храма · UTC+03:00")
        // Compose both neighbors, then prove adjacency. Lazy disposal cannot make
        // an intervening actionless reading item pass an absence assertion.
        compose.quietReveal("church_information_card")
        compose.onNodeWithTag("church_personal_card").assertIsDisplayed()
        compose.onNodeWithTag("church_information_card").assertIsDisplayed()
        val personalBounds = compose.onNodeWithTag("church_personal_card").getUnclippedBoundsInRoot()
        val informationBounds = compose.onNodeWithTag("church_information_card").getUnclippedBoundsInRoot()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals("No empty reading item between personal and information, exact physical gap",
            (20f * density).roundToInt(),
            ((informationBounds.top - personalBounds.bottom).value * density).roundToInt())
        compose.onNodeWithTag("church_reading_card").assertDoesNotExist()
        compose.onNodeWithText("Читать службу").assertDoesNotExist()
        compose.onNodeWithTag("church_read_liturgy").assertDoesNotExist()
        compose.onNodeWithTag("church_other_services").assertDoesNotExist()
        assertEquals(ChurchReadingEntry.NONE, churchReadingEntry(QUIET_PARISH, QUIET_DATE, unsupported,
            showLiturgy = false, showChooser = churchReadingChooser(unsupported, true, true, true, true, retainedChooser = false),
            hasChooser = true, hasOpenService = true, keepFull = true))
        compose.quietReveal("personal_time_to_service", backwards = true).performClick()
        compose.runOnIdle { assertEquals(listOf(0 to QUIET_DATE, 1 to QUIET_DATE), reads); assertTrue(chooserCalls.isEmpty()) }
        reviewCurrentSource()

        compose.runOnIdle { personal = false; callbackVersion = 2 }
        compose.quietReveal("church_read_liturgy").assertTextEquals("Текст литургии на этот день").performClick()
        compose.onNodeWithTag("personal_time_to_service").assertDoesNotExist()
        compose.onNodeWithTag("church_other_services").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(0 to QUIET_DATE, 1 to QUIET_DATE, 2 to QUIET_DATE), reads)
            assertTrue(chooserCalls.isEmpty())
        }
        reviewCurrentSource()
    }

    @Test fun productionReviewFindsTheWholeTargetForEveryReachableReadingAndPersonalShape() {
        val cases = listOf(
            QuietReviewCase("none-published", true, false, false, false, ChurchReadingEntry.NONE),
            QuietReviewCase("none-personal", true, true, true, true, ChurchReadingEntry.NONE),
            QuietReviewCase("none-personal-before", false, true, false, false, ChurchReadingEntry.NONE),
            QuietReviewCase("full-before", false, false, true, true, ChurchReadingEntry.FULL),
            QuietReviewCase("full-personal-before", false, true, false, true, ChurchReadingEntry.FULL),
            QuietReviewCase("full-after", true, false, true, false, ChurchReadingEntry.FULL),
            QuietReviewCase("compact-after", true, false, true, true, ChurchReadingEntry.COMPACT),
            QuietReviewCase("compact-before", true, false, true, true, ChurchReadingEntry.COMPACT, pinPersonalFirst = true))
        var current by mutableStateOf(cases.first())
        var showBack by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        var request by mutableIntStateOf(0)
        val selected = QUIET_PARISH.copy(sourceUrl = "https://parish.example/schedule/",
            scheduleValidFrom = QUIET_DATE.toString(), scheduleValidUntil = QUIET_DATE.toString())
        val candidate = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(selected),
            provider = "public_index", providerLabel = "Справочник", providerObjectId = "quiet42",
            name = selected.name, address = selected.address, sourceUrl = "https://parish.example/schedule/",
            evidenceUrl = "https://index.example/church/quiet42", evidenceSha256 = "a".repeat(64),
            checkedAt = QUIET_NOW.toString(), latitude = selected.latitude!!, longitude = selected.longitude!!,
            outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте название и адрес")
        val openedTexts = mutableListOf<Pair<LocalDate, WorshipKind>>()
        val readCallback: (LocalDate) -> Unit = { day -> openedTexts += day to WorshipKind.LITURGY }
        val chooserCallback: (LocalDate, WorshipKind) -> Unit = { day, kind -> openedTexts += day to kind }
        // Focused source-review viewport: the personal item can be reached as the first
        // visible item before publishing, without a spacer or product geometry change.
        compose.setContent { GuideTheme(dark) { Box(Modifier.size(411.dp, 400.dp)) { key(generation) {
            val currentChurch = selected.copy(schedule = if (current.published) listOf(QUIET_HOURS) else emptyList())
            val own = InternetScheduleState(selected.id, InternetScheduleStatus.NO_WEBSITE,
                sourceCandidates = listOf(candidate), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected))
            ChurchScreen(listOf(currentChurch), currentChurch.id, QUIET_DATE,
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                showBack = showBack, searchQuery = "", onSearchQueryChange = {}, internetSchedule = own,
                onReadLiturgy = readCallback.takeIf { current.read },
                onReadServiceText = chooserCallback.takeIf { current.chooser },
                onOpenService = {}, onConfirmScheduleCandidate = {},
                userStarts = if (current.personal) listOf(UserServiceStart(selected.id, QUIET_DATE.toString(), "08:30")) else emptyList(),
                sourceReviewRequest = request, sourceReviewIdentity = ScheduleEntityIdentity.fingerprint(selected))
        } } } }
        for (case in cases) for (back in listOf(true, false)) {
            compose.runOnIdle {
                // Reaching the independently attributed personal group can retain its
                // order without ever composing a FULL church_reading item.
                current = if (case.pinPersonalFirst) case.copy(published = false, personal = true, read = false, chooser = false) else case
                showBack = back; request = 0; generation++
            }
            compose.waitForIdle()
            if (case.pinPersonalFirst) {
                compose.quietReveal("personal_start_summary")
                var pinned = false
                repeat(16) {
                    val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
                    val card = compose.onNodeWithTag("church_personal_card").getUnclippedBoundsInRoot()
                    if (card.top <= viewport.top && card.bottom > viewport.top + 8.dp) pinned = true
                    if (!pinned) compose.onNodeWithTag("church_content").performTouchInput {
                        swipe(Offset(centerX, height * .8f), Offset(centerX, height * .7f), durationMillis = 700)
                    }
                    compose.waitForIdle()
                }
                assertTrue("Reach the personal group using actual scroll", pinned)
                compose.runOnIdle { current = case }
                compose.waitForIdle()
                compose.quietReveal("church_other_services")
                compose.onNodeWithTag("church_read_liturgy").assertDoesNotExist()
                val reading = compose.onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot()
                val schedule = compose.onNodeWithTag("church_schedule_card").getUnclippedBoundsInRoot()
                assertTrue("The independently retained order can hold a fresh compact item", reading.bottom <= schedule.top)
            }
            val featured = if (case.published) QUIET_HOURS else null
            assertEquals(case.mode, case.expected, churchReadingEntry(selected, QUIET_DATE, featured,
                showLiturgy = case.read && !case.personal,
                showChooser = churchReadingChooser(featured, case.personal && case.read, case.read, case.chooser, true),
                hasChooser = case.chooser, hasOpenService = true))
            // Scroll away first; only the application's request is allowed to reveal the target.
            compose.quietReveal("church_details")
            compose.runOnIdle { request++ }
            compose.waitForIdle()
            compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed()
                .assertTextEquals(if (case.published) "Найденные сайты" else candidate.name)
            val heading = compose.onNodeWithTag("schedule_sources_heading").getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
            assertTrue("${case.mode}/back=$back target top", heading.top >= viewport.top - 1.dp)
            assertTrue("${case.mode}/back=$back target bottom", heading.bottom <= viewport.bottom + 1.dp)
            compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertExists()
            compose.runOnIdle { assertTrue("Source review does not open a text", openedTexts.isEmpty()) }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
