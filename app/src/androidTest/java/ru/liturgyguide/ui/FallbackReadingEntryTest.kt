package ru.liturgyguide.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual display/font/bottom-bar profiles. Schedule data is isolated, not a live-provider claim. */
@RunWith(Parameterized::class)
class FallbackReadingEntryTest(church: Boolean, width: Int, height: Int, scale: Float) :
    FallbackReadingHarness(church, width, height, scale) {
    @Test fun missingLoadingAndUnavailableKeepReadingAvailableBeforeSourceDetails() = evidence("fallback") { report ->
        val observations = JSONArray()
        report.put("states", observations)
        launch()
        listOf("no-website", "loading", "unavailable", "empty-published-day").forEachIndexed { index, name ->
            if (index > 0) replace(FallbackFixture(state = emptyState(name)), freshScreen = true)
            val observation = JSONObject().put("state", name)
            observations.put(observation)
            observeInitialAction(primaryTag, observation)
            capture("$name-initial", observation)
            if (width == 411) {
                assertTrue("The WHOLE reading action must be visible before any scroll on $name", observation.getBoolean("fullyVisible"))
            }
            var swipes = 0
            val primary = reveal(primaryTag, onSwipe = { swipes++ }).assertIsEnabled().assertHeightIsAtLeast(56.dp)
            observation.put("swipesToPrimary", swipes).put("visibleActionDp", rectangle(bounds(primaryTag)))
            compose.onAllNodesWithTag(primaryTag).assertCountEquals(1)
            primary.assertTextEquals("Открыть литургию")
            labelFits(primaryTag, "Открыть литургию")
            // A library entry is not a fabricated scheduled or currently running service.
            compose.onNodeWithTag(officialTag).assertDoesNotExist()
            compose.onNodeWithTag(if (churchPage) "church_service_time" else "today_service_time").assertDoesNotExist()
            capture("$name-reading", observation)
            primary.performClick()
            compose.runOnIdle {
                assertEquals(listOf(FallbackBookRequest(fixture.date, WorshipKind.LITURGY)), calls.books)
                assertTrue(calls.services.isEmpty())
            }
        }
        val other = reveal(otherTag).assertIsEnabled().assertHeightIsAtLeast(48.dp)
        labelFits(otherTag, "Выбрать другой текст")
        compose.onAllNodesWithTag(otherTag).assertCountEquals(1)
        val primary = bounds(primaryTag)
        val chooser = bounds(otherTag)
        assertTrue("Keep a separate touch target next to reading", chooser.top - primary.bottom >= 12.dp)
        assertTrue("Do not put schedule/source details between reading and another text", chooser.top - primary.bottom <= 64.dp)
        report.put("otherActionDp", rectangle(chooser)).put("primaryBeforeChooserDp", rectangle(primary))
        other.performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 12 ноября 2026")
        reveal("service_book_choose_vespers", "service_book_chooser").performClick()
        compose.runOnIdle {
            assertEquals(listOf(FallbackBookRequest(fixture.date, WorshipKind.LITURGY),
                FallbackBookRequest(fixture.date, WorshipKind.VESPERS)), calls.books)
            assertTrue(calls.services.isEmpty())
        }
        report.put("otherTextCallbackExact", true)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0} width={1} height={2} font={3}")
        fun profiles() = listOf(false, true).flatMap { church -> listOf(
            arrayOf<Any>(church, 411, 914, 1f), arrayOf<Any>(church, 320, 800, 2f)) }
    }
}

/** Stateful transitions are tested once per screen, without multiplying them by layout profiles. */
@RunWith(Parameterized::class)
class FallbackReadingEntryContractTest(church: Boolean) : FallbackReadingHarness(church, 411, 914, 1f) {
    @Test fun personalTomorrowHasOnePrimaryAndKeepsTheExactDateAcrossTheYear() = evidence("personal") { report ->
        val tomorrow = LocalDate.of(2027, 1, 1)
        fixture = FallbackFixture(now = Instant.parse("2026-12-31T09:00:00Z"),
            personal = UserServiceStart(PARISH.id, tomorrow.toString(), "09:30"))
        launch()
        reveal(tomorrowTag).performClick().assertIsSelected()
        val primary = if (churchPage) "personal_time_to_service" else "today_open_personal_liturgy"
        observeInitialAction(primary, report)
        assertTrue("Personal reading must precede missing schedule details", report.getBoolean("fullyVisible"))
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        compose.onAllNodesWithTag(primary).assertCountEquals(1)
        compose.onNodeWithTag(primary).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(FallbackBookRequest(tomorrow, WorshipKind.LITURGY)), calls.books) }
        capture("personal-first", report)
        val other = reveal(otherTag)
        // The whole reading/chooser group is now composed. An off-screen lazy item
        // could otherwise hide the old duplicate generic action from an absence assertion.
        compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        other.performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 1 января 2027")
        reveal("service_book_choose_vespers", "service_book_chooser").performClick()
        compose.runOnIdle {
            assertEquals(listOf(FallbackBookRequest(tomorrow, WorshipKind.LITURGY),
                FallbackBookRequest(tomorrow, WorshipKind.VESPERS)), calls.books)
        }
        if (churchPage) {
            replace(FallbackFixture(allowRead = false), freshScreen = true)
            compose.onNodeWithTag(primaryTag).assertDoesNotExist()
            compose.onNodeWithTag("personal_time_to_service").assertDoesNotExist()
            compose.onNodeWithTag(otherTag).assertDoesNotExist()
            compose.runOnIdle { assertTrue(calls.books.isEmpty()); assertTrue(calls.services.isEmpty()) }
            val now = Instant.parse("2026-12-31T09:00:00Z")
            val service = ParishService("2026-12-31", "17:00", "Вечерня", "vespers")
            replace(FallbackFixture(now = now, state = publishedState(service, now),
                allowRead = false, allowChooser = true, allowOfficial = false), freshScreen = true)
            compose.onNodeWithTag(primaryTag).assertDoesNotExist()
            compose.onNodeWithTag(officialTag).assertDoesNotExist()
            reveal(otherTag).assertIsEnabled().performClick()
            compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 31 декабря 2026")
            reveal("service_book_choose_vespers", "service_book_chooser").performClick()
            compose.runOnIdle {
                assertEquals(listOf(FallbackBookRequest(LocalDate.of(2026, 12, 31), WorshipKind.VESPERS)), calls.books)
                assertTrue(calls.services.isEmpty())
            }
            report.put("soleChooserCallbackPreserved", true)
        }
    }

    @Test fun publicationArrivesWithoutResettingDayOrTheVisibleReadingAnchor() = evidence("transition") { report ->
        val now = Instant.parse("2026-12-31T09:00:00Z")
        fixture = FallbackFixture(now = now, state = InternetScheduleState(PARISH.id, loading = true))
        launch()
        reveal(tomorrowTag).performClick().assertIsSelected()
        reveal(primaryTag).assertIsEnabled()
        alignReadingCardWithViewport()
        val before = bounds(readingCardTag)
        report.put("readingAnchorBeforeDp", rectangle(before))
        capture("loading-anchor", report)
        val published = ParishService("2027-01-01", "17:00", "Вечерня", "vespers")
        replace(fixture.copy(state = publishedState(published, now)), freshScreen = false)
        // Retain the same composition, parish and selected tomorrow. No fixture key can reset scroll.
        val after = bounds(readingCardTag)
        report.put("readingAnchorAfterDp", rectangle(after))
        assertTrue("A publication must not displace the card the person is reading", abs(after.top.value - before.top.value) <= 2f)
        capture("ready-anchor", report)
        compose.runOnIdle { assertTrue(calls.books.isEmpty()); assertTrue(calls.services.isEmpty()) }
        if (!churchPage) compose.onNodeWithTag(primaryTag).assertDoesNotExist()
        reveal(officialTag, searchBackwards = true).assertIsEnabled()
        compose.onAllNodesWithTag(officialTag).assertCountEquals(1)
        compose.onNodeWithTag(if (churchPage) "church_service_time" else "today_service_time").assertTextEquals("17:00")
        reveal(tomorrowTag, searchBackwards = true).assertIsSelected()
        reveal(officialTag).performClick()
        compose.runOnIdle {
            assertEquals(if (churchPage) listOf(published) else emptyList<ParishService>(), calls.services)
            assertEquals(if (churchPage) emptyList<FallbackBookRequest>() else
                listOf(FallbackBookRequest(LocalDate.of(2027, 1, 1), WorshipKind.VESPERS)), calls.books)
        }
        // A temporary refresh failure must retain the actual published service, not resurrect the fallback.
        replace(fixture.copy(state = fixture.state.copy(status = InternetScheduleStatus.UNAVAILABLE)), freshScreen = false)
        reveal(officialTag).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(if (churchPage) listOf(published) else emptyList<ParishService>(), calls.services)
            assertEquals(if (churchPage) emptyList<FallbackBookRequest>() else
                listOf(FallbackBookRequest(LocalDate.of(2027, 1, 1), WorshipKind.VESPERS)), calls.books)
        }
        report.put("selectedTomorrowPreserved", true).put("publishedOccurrencePreservedOffline", true)
    }

    @Test fun unscrolledPublicationKeepsPriorityAndPinnedReadingResetsOnDayChange() = evidence("priority-reset") { report ->
        val now = Instant.parse("2026-12-31T09:00:00Z")
        val today = ParishService("2026-12-31", "17:00", "Вечерня", "vespers")
        val tomorrow = ParishService("2027-01-01", "17:00", "Вечерня", "vespers")
        fixture = FallbackFixture(now = now, state = InternetScheduleState(PARISH.id, loading = true))
        launch()
        fun assertPublishedPriority(name: String) {
            val observation = JSONObject()
            report.put(name, observation)
            observeInitialAction(officialTag, observation)
            capture(name, observation)
            assertTrue("An unscrolled/new day must prioritize the actual published service", observation.getBoolean("fullyVisible"))
            compose.onAllNodesWithTag(officialTag).assertCountEquals(1)
            compose.onNodeWithTag(officialTag).assertIsEnabled()
            if (compose.onAllNodesWithTag(readingCardTag).fetchSemanticsNodes().isNotEmpty()) {
                val reading = bounds(readingCardTag)
                observation.put("readingCardDp", rectangle(reading))
                assertTrue("An earlier day's reading order must not precede this day's service", reading.top >= bounds(officialTag).bottom)
            }
        }
        fun assertTodayCallback() = compose.runOnIdle {
            assertEquals(if (churchPage) listOf(today) else emptyList<ParishService>(), calls.services)
            assertEquals(if (churchPage) emptyList<FallbackBookRequest>() else
                listOf(FallbackBookRequest(LocalDate.of(2026, 12, 31), WorshipKind.VESPERS)), calls.books)
        }
        // No gesture has happened: merely displaying fallback reading must not pin its order.
        replace(fixture.copy(state = publishedState(today, now)), freshScreen = false)
        assertPublishedPriority("unscrolled-ready")
        compose.onNodeWithTag(officialTag).performClick()
        assertTodayCallback()

        // Tomorrow is unresolved, while today's real publication remains available.
        replace(fixture.copy(state = fixture.state.copy(loading = true)), freshScreen = false)
        reveal(tomorrowTag).performClick().assertIsSelected()
        reveal(primaryTag).assertIsEnabled()
        alignReadingCardWithViewport()
        val before = bounds(readingCardTag)
        report.put("pinnedTomorrowBeforeDp", rectangle(before))
        capture("tomorrow-reading", report)
        val ready = publishedState(today, now)
        val bothDays = ready.copy(publication = requireNotNull(ready.publication).copy(events = listOf(
            InternetService(today.date, today.time, today.title, today.kind),
            InternetService(tomorrow.date, tomorrow.time, tomorrow.title, tomorrow.kind))))
        replace(fixture.copy(state = bothDays), freshScreen = false)
        val after = bounds(readingCardTag)
        report.put("pinnedTomorrowAfterDp", rectangle(after))
        assertTrue("A person already reading tomorrow keeps that visual anchor", abs(after.top.value - before.top.value) <= 2f)
        capture("tomorrow-ready", report)
        compose.runOnIdle { assertTrue(calls.books.isEmpty()); assertTrue(calls.services.isEmpty()) }

        // Return by a real gesture and day control, without remounting the screen.
        reveal(if (churchPage) "schedule_today" else "today_day_today", searchBackwards = true)
            .performClick().assertIsSelected()
        assertPublishedPriority("today-after-pinned-tomorrow")
        compose.onNodeWithTag(officialTag).performClick()
        assertTodayCallback()

        if (churchPage) {
            // Personal information may exist even when only the explicit text chooser can open a book.
            replace(FallbackFixture(now = now, state = InternetScheduleState(PARISH.id, loading = true),
                personal = UserServiceStart(PARISH.id, tomorrow.date, "09:30"),
                allowRead = false, allowChooser = true, allowOfficial = false), freshScreen = true)
            reveal(tomorrowTag).performClick().assertIsSelected()
            reveal(otherTag).assertIsEnabled()
            alignReadingCardWithViewport()
            val chooserBefore = bounds(readingCardTag)
            capture("personal-sole-chooser-loading", report)
            replace(fixture.copy(state = publishedState(tomorrow, now)), freshScreen = false)
            val chooserAfter = bounds(readingCardTag)
            report.put("soleChooserBeforeDp", rectangle(chooserBefore)).put("soleChooserAfterDp", rectangle(chooserAfter))
            assertTrue("Publishing a service cannot displace the only usable chooser", abs(chooserAfter.top.value - chooserBefore.top.value) <= 2f)
            reveal(otherTag).assertIsEnabled()
            compose.onAllNodesWithTag(otherTag).assertCountEquals(1)
            compose.onNodeWithTag(primaryTag).assertDoesNotExist()
            compose.onNodeWithTag(officialTag).assertDoesNotExist()
            capture("personal-sole-chooser-ready", report)
            compose.onNodeWithTag(otherTag).performClick()
            compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 1 января 2027")
            reveal("service_book_choose_vespers", "service_book_chooser").performClick()
            compose.runOnIdle {
                assertEquals(listOf(FallbackBookRequest(LocalDate.of(2027, 1, 1), WorshipKind.VESPERS)), calls.books)
                assertTrue(calls.services.isEmpty())
            }
        }
        report.put("unscrolledPriorityAndDayReset", true)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0}")
        fun screens() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}

/** Saved state is restored into a new owner without an intervening ordinary input update. */
@RunWith(Parameterized::class)
class FallbackReadingEntryRestorationTest(private val change: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reachedReadingRestoresOnlyForTheSameParishAndDate() {
        val initialOwner = FallbackRestoreOwner("parish-a", LocalDate.of(2026, 12, 31))
        val restoredOwner = when (change) {
            "next-date" -> initialOwner.copy(date = initialOwner.date.plusDays(1))
            "next-parish" -> initialOwner.copy(parishId = "parish-b")
            else -> initialOwner
        }
        // These are deliberately NOT snapshot state. Existing composition keeps the old
        // remembered inputs until restoration; normal recomposition must not reset the latch.
        var suppliedOwner = initialOwner
        var suppliedPublished = false
        lateinit var observedListState: LazyListState
        val opened = mutableListOf<Pair<FallbackRestoreOwner, String>>()
        val restoration = StateRestorationTester(compose)
        val report = JSONObject().put("allPassed", false).put("change", change)
            .put("scope", "Real LazyColumn, touch scroll and production ordering helper under StateRestorationTester; no real preferences or process-death claim")
        val run = InstrumentationRegistry.getArguments().getString("fallbackReadingRun") ?: "run-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("fallback-reading-entry"))
        val output = File(root, "$run/restore-$change")
        check(!output.exists() && output.mkdirs()) { "Use a fresh evidence run ID" }
        try {
            restoration.setContent { GuideTheme(false) {
                val owner = remember { suppliedOwner }
                val published = remember { suppliedPublished }
                val state = rememberLazyListState()
                val readingFirst = rememberReadingBeforeSchedule(state, owner.parishId, owner.date, published,
                    "restore_reading", "restore_personal")
                SideEffect { observedListState = state }
                LazyColumn(Modifier.width(300.dp).height(480.dp).testTag("restore_content"), state = state) {
                    item(key = "restore_header") {
                        Text("${owner.parishId} | ${owner.date} | $published",
                            Modifier.fillMaxWidth().height(120.dp).testTag("restore_owner"))
                    }
                    fun readingItem() {
                        item(key = "restore_reading") {
                            Button(onClick = { opened += owner to "reading" },
                                modifier = Modifier.fillMaxWidth().height(180.dp).testTag("restore_reading_action")) {
                                Text("Открыть текст")
                            }
                        }
                    }
                    if (readingFirst) readingItem()
                    item(key = "restore_schedule") {
                        Button(onClick = { opened += owner to "schedule" },
                            modifier = Modifier.fillMaxWidth().height(180.dp).testTag("restore_schedule_action")) {
                            Text(if (published) "Открыть службу" else "Проверить расписание")
                        }
                    }
                    if (!readingFirst) readingItem()
                    item(key = "restore_footer") { Spacer(Modifier.height(480.dp)) }
                }
            } }
            compose.waitForIdle()
            var toReading = 0
            while (!compose.runOnIdle { observedListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == "restore_reading" }) {
                assertTrue("Reach the actual reading item with bounded touch", toReading < 12)
                compose.onNodeWithTag("restore_content").performTouchInput {
                    swipe(Offset(centerX, height * .8f), Offset(centerX, height * .7f), durationMillis = 800)
                }
                compose.waitForIdle()
                toReading++
            }
            var toTop = 0
            while (!compose.runOnIdle { observedListState.firstVisibleItemIndex == 0 && observedListState.firstVisibleItemScrollOffset == 0 }) {
                assertTrue("Return to the first screen with bounded touch", toTop < 12)
                compose.onNodeWithTag("restore_content").performTouchInput {
                    swipe(Offset(centerX, height * .35f), Offset(centerX, height * .65f), durationMillis = 800)
                }
                compose.waitForIdle()
                toTop++
            }
            report.put("swipesToReading", toReading).put("swipesBackToTop", toTop)
            compose.runOnIdle { suppliedOwner = restoredOwner; suppliedPublished = true }
            // A mutable-state change here would exercise the easy input-change path instead
            // of restoring yesterday's saved latch with today's constructor inputs.
            compose.onNodeWithTag("restore_owner").assertTextEquals("${initialOwner.parishId} | ${initialOwner.date} | false")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("restore_owner").assertTextEquals("${restoredOwner.parishId} | ${restoredOwner.date} | true")
            val reading = compose.onNodeWithTag("restore_reading_action").getUnclippedBoundsInRoot()
            val schedule = compose.onNodeWithTag("restore_schedule_action").getUnclippedBoundsInRoot()
            fun rect(bounds: DpRect) = JSONObject().put("left", bounds.left.value).put("top", bounds.top.value)
                .put("right", bounds.right.value).put("bottom", bounds.bottom.value)
            report.put("readingDp", rect(reading)).put("scheduleDp", rect(schedule))
            if (change == "same-owner") {
                assertTrue("The same parish/day retains reading reached before restoration", reading.bottom <= schedule.top)
            } else {
                assertTrue("A different parish/day must prioritize its published service", schedule.bottom <= reading.top)
            }
            val first = if (change == "same-owner") "reading" else "schedule"
            compose.onNodeWithTag("restore_${first}_action").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf(restoredOwner to first), opened) }
            report.put("restoredParishId", restoredOwner.parishId).put("restoredDate", restoredOwner.date.toString())
                .put("exactCallback", true).put("allPassed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.name).put("failureMessage", failure.message.orEmpty())
            throw failure
        } finally { File(output, "receipt.json").writeText(report.toString(2)) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "restore={0}")
        fun owners() = listOf(arrayOf<Any>("same-owner"), arrayOf<Any>("next-date"), arrayOf<Any>("next-parish"))
    }
}

private data class FallbackRestoreOwner(val parishId: String, val date: LocalDate)

private val PARISH = Parish("osm:way:77503842", "Храм Николая Чудотворца (Воронеж)",
    "Храм Николая Чудотворца (Воронеж)", "Воронеж", "Воронеж, улица Таранченко, 19А", "", "",
    "Europe/Moscow", "", "", "", "", emptyList(), "", "", 51.65962070, 39.21276430)

data class FallbackBookRequest(val date: LocalDate, val kind: WorshipKind, val evening: Boolean = false)
class FallbackCalls {
    val books = mutableListOf<FallbackBookRequest>()
    val services = mutableListOf<ParishService>()
    fun clear() { books.clear(); services.clear() }
}
data class FallbackFixture(val now: Instant = Instant.parse("2026-11-12T06:00:00Z"),
    val state: InternetScheduleState = InternetScheduleState(PARISH.id, InternetScheduleStatus.NO_WEBSITE),
    val personal: UserServiceStart? = null, val allowRead: Boolean = true,
    val allowChooser: Boolean = allowRead, val allowOfficial: Boolean = true) {
    val date: LocalDate get() = now.atZone(java.time.ZoneId.of(PARISH.timezone)).toLocalDate()
}

/** Native configuration only, as in PrimaryReadingActionHarness. No preference/cache/provider injection. */
abstract class FallbackReadingHarness(protected val churchPage: Boolean, protected val width: Int,
    private val height: Int, private val scale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected var fixture by mutableStateOf(FallbackFixture())
    protected val calls = FallbackCalls()
    protected val contentTag get() = if (churchPage) "church_content" else "main_content"
    protected val primaryTag get() = if (churchPage) "church_read_liturgy" else "start_guide"
    protected val officialTag get() = if (churchPage) "church_open_service" else "open_service_book"
    protected val otherTag get() = if (churchPage) "church_other_services" else "today_other_services"
    protected val tomorrowTag get() = if (churchPage) "schedule_tomorrow" else "today_day_tomorrow"
    protected val readingCardTag get() = if (churchPage) "church_reading_card" else "today_reading_card"
    private var generation by mutableIntStateOf(0)
    private var mounted by mutableStateOf(true)
    private var contentSet = false
    private var previousFont: String? = null
    private var previousSize: String? = null
    private var displayChanged = false
    private var directory: File? = null

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before fun configureDisplay() {
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) { "Native display tests require the owned emulator" }
        previousFont = shell("settings get system font_scale").takeUnless { it == "null" }
        previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        displayChanged = true
        shell("settings put system font_scale $scale")
        shell("wm size ${(width * density).roundToInt()}x${(height * density).roundToInt()}")
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = abs(it.resources.configuration.fontScale - scale) < .001f &&
                    abs(it.resources.configuration.screenWidthDp - width) <= 1 && it.window.decorView.hasWindowFocus()
            }
            ready
        }
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
    }

    @After fun restoreDisplay() {
        try {
            if (contentSet) { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
        } finally {
            if (displayChanged) try { shell("wm size ${previousSize ?: "reset"}") }
            finally { shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale") }
        }
    }

    protected fun launch() {
        compose.setContent { GuideTheme(false) {
            Box(Modifier.fillMaxSize().testTag("fallback_entry_host")) {
                if (mounted) key(generation) {
                    val current = fixture
                    val starts = listOfNotNull(current.personal)
                    val bottom: @Composable () -> Unit = { GuideBottomBar(if (churchPage) "church" else "home", {}, {}, {}) }
                    if (churchPage) ChurchScreen(listOf(PARISH), PARISH.id, current.date,
                        onSelect = { _, _ -> ChurchSelectionResult.FAILED }, onOpenUrl = {}, onBack = {},
                        showBack = false, bottomBar = bottom, searchQuery = "", onSearchQueryChange = {},
                        internetSchedule = current.state, now = current.now, userStarts = starts,
                        onRefreshSchedule = {}, onSetScheduleSource = {}, readingEntryLabel = { "Открыть литургию" },
                        onReadLiturgy = if (current.allowRead) ({ date -> calls.books += FallbackBookRequest(date, WorshipKind.LITURGY) }) else null,
                        onReadServiceText = if (current.allowChooser) ({ date, kind -> calls.books += FallbackBookRequest(date, kind) }) else null,
                        onOpenService = if (current.allowOfficial) ({ service -> calls.services += service }) else null)
                    else TodayScreen(PARISH, current.now, current.state, null, null,
                        onFindChurch = {}, onChurchDetails = {}, onResumeBook = {}, onRefresh = {}, onResumeLegacy = null,
                        bottomBar = bottom, userStarts = starts, onSetScheduleSource = {},
                        onOpenBook = { date, kind, evening -> calls.books += FallbackBookRequest(date, kind, evening) })
                }
            }
        } }
        contentSet = true
        compose.onNodeWithTag("fallback_entry_host").assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
    }

    protected fun replace(next: FallbackFixture, freshScreen: Boolean) = compose.runOnIdle {
        calls.clear()
        fixture = next
        if (freshScreen) generation++
    }

    protected fun emptyState(name: String): InternetScheduleState = when (name) {
        "loading" -> InternetScheduleState(PARISH.id, loading = true)
        "unavailable" -> InternetScheduleState(PARISH.id, InternetScheduleStatus.UNAVAILABLE,
            checkedAt = "2026-11-12T05:00:00Z")
        "empty-published-day" -> publishedState(ParishService("2026-11-13", "08:00", "Литургия", "liturgy"),
            Instant.parse("2026-11-12T06:00:00Z"))
        else -> InternetScheduleState(PARISH.id, InternetScheduleStatus.NO_WEBSITE)
    }

    protected fun publishedState(service: ParishService, now: Instant) = InternetScheduleState(PARISH.id,
        InternetScheduleStatus.READY, website = "https://parish.example/", sourceUrl = "https://parish.example/schedule/",
        checkedAt = now.toString(), publication = SchedulePublication(PARISH.id, PARISH.timezone,
            "https://parish.example/", "https://parish.example/schedule/", now.toString(),
            listOf(InternetService(service.date, service.time, service.title, service.kind))))

    protected fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun inside(target: DpRect, viewport: DpRect) = target.right > target.left && target.bottom > target.top &&
        target.top >= viewport.top && target.bottom <= viewport.bottom && target.left >= viewport.left && target.right <= viewport.right

    protected fun observeInitialAction(tag: String, report: JSONObject) {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(contentTag).getBoundsInRoot()
        val composed = exists(tag)
        val actual = if (composed) bounds(tag) else null
        val clipped = if (composed) compose.onNodeWithTag(tag).getBoundsInRoot() else null
        report.put("viewportDp", rectangle(viewport)).put("actionDp", actual?.let(::rectangle) ?: JSONObject.NULL)
            .put("clippedActionDp", clipped?.let(::rectangle) ?: JSONObject.NULL)
            .put("fullyVisible", actual?.let { inside(it, viewport) } == true)
    }

    protected fun reveal(tag: String, containerTag: String = contentTag, searchBackwards: Boolean = false,
        onSwipe: () -> Unit = {}): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        repeat(25) {
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            val viewport = container.getBoundsInRoot()
            val box = if (exists(tag)) bounds(tag) else null
            if (box != null && inside(box, viewport)) return compose.onNodeWithTag(tag).assertIsDisplayed()
            // LazyColumn can dispose a target above the viewport. The transition knows
            // which direction the earlier service/day lies in even before it is composed.
            val backward = if (box != null) box.top < viewport.top else searchBackwards
            container.performTouchInput {
                swipe(Offset(centerX, height * (if (backward) .35f else .8f)),
                    Offset(centerX, height * (if (backward) .65f else .5f)), durationMillis = 700)
            }
            onSwipe()
        }
        throw AssertionError("Could not reveal $tag by bounded touch in $containerTag")
    }

    protected fun alignReadingCardWithViewport() {
        val container = compose.onNodeWithTag(contentTag)
        repeat(12) {
            compose.waitForIdle()
            val card = bounds(readingCardTag)
            val viewport = container.getBoundsInRoot()
            if (card.top <= viewport.top && card.bottom > viewport.top + 48.dp) return
            assertTrue("Reading card must remain before the viewport bottom", card.top < viewport.bottom)
            val fraction = ((card.top.value - viewport.top.value + 8f) / (viewport.bottom.value - viewport.top.value)).coerceIn(.03f, .45f)
            container.performTouchInput {
                swipe(Offset(centerX, height * .8f), Offset(centerX, height * (.8f - fraction)), durationMillis = 700)
            }
        }
        throw AssertionError("Could not anchor the actual reading card with bounded touch")
    }

    protected fun labelFits(buttonTag: String, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(buttonTag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertTextFitsLayout(layout, label)
        assertEquals("Do not shrink accessibility font scale", scale, layout.layoutInput.density.fontScale, .001f)
        Regex("[А-Яа-яЁё]+").findAll(label).forEach { word ->
            assertEquals("Keep words whole: ${word.value}", layout.getLineForOffset(word.range.first), layout.getLineForOffset(word.range.last))
        }
    }

    protected fun evidence(label: String, body: (JSONObject) -> Unit) {
        val run = InstrumentationRegistry.getArguments().getString("fallbackReadingRun") ?: "run-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("fallback-reading-entry"))
        directory = File(root, "$run/${if (churchPage) "church" else "main"}-${width}x${height}-font${(scale * 100).toInt()}-$label")
            .also { check(!it.exists() && it.mkdirs()) { "Use a fresh evidence run ID" } }
        val report = JSONObject().put("allPassed", false).put("churchPage", churchPage).put("widthDp", width)
            .put("heightDp", height).put("fontScale", scale).put("test", label)
            .put("scope", "Native production screens with isolated callbacks and schedule states; no user data, provider or human 20-second claim")
        try { body(report); report.put("allPassed", true) }
        catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.name).put("failureMessage", failure.message.orEmpty())
            throw failure
        } finally { File(requireNotNull(directory), "receipt.json").writeText(report.toString(2)) }
    }

    protected fun capture(name: String, report: JSONObject) {
        val directory = requireNotNull(directory)
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val tree = compose.runOnIdle { buildString {
            fun appendNode(node: SemanticsNode, depth: Int) {
                append("  ".repeat(depth)).append(node.id).append(' ').append(node.boundsInRoot).append(' ').append(node.config).append('\n')
                if (depth < 80) node.children.forEach { appendNode(it, depth + 1) }
            }
            roots.forEach { appendNode(it, 0) }
        } }
        File(directory, "$name.semantics.txt").writeText(tree)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            compose.activityRule.scenario.onActivity {
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                it.window.decorView.invalidate()
            }
            assertTrue("A real frame must be committed before capture", committed.await(5, TimeUnit.SECONDS))
        }
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            for (attempt in 0 until 10) {
                SystemClock.sleep(120)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                stable = bitmap.sameAs(next)
                bitmap.recycle()
                bitmap = next
                if (stable) break
            }
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            // Loading may be honestly animated. Preserve the frame without claiming a static result.
            report.put("capture-$name", JSONObject().put("twoIdenticalFrames", stable)
                .put("widthPx", bitmap.width).put("heightPx", bitmap.height))
            if (!fixture.state.loading) assertTrue("A static screen must settle before capture", stable)
        } finally { bitmap.recycle() }
    }

    protected fun rectangle(value: DpRect) = JSONObject().put("left", value.left.value).put("top", value.top.value)
        .put("right", value.right.value).put("bottom", value.bottom.value)
}
