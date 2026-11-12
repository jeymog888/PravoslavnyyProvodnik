package ru.liturgyguide.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Real screen/bottom-bar layouts. The service row is an archived publication, not a live schedule test. */
@RunWith(Parameterized::class)
class PrimaryReadingActionTest(churchPage: Boolean, width: Int, scale: Float) :
    PrimaryReadingActionHarness(churchPage, width, 800, scale) {
    @Test fun openingPrecedesPublishedDetailsWithoutLosingTheSourceOrDate() = exerciseHierarchy(requireInitialVisibility = false)

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0} width={1} font={2}")
        fun profiles() = listOf(false, true).flatMap { church ->
            listOf(320, 360).flatMap { width -> listOf(1f, 2f).map { arrayOf<Any>(church, width, it) } }
        }
    }
}

/** The original failing Main viewport has its own independently runnable red/green gate. */
@RunWith(AndroidJUnit4::class)
class PrimaryReadingActionReferenceTest : PrimaryReadingActionHarness(false, 411, 914, 1f) {
    @Test fun mainActionIsFullyVisibleWithoutScrolling() = exerciseHierarchy(requireInitialVisibility = true)
}

/** Semantics are exercised once per screen, rather than multiplied across every layout profile. */
@RunWith(Parameterized::class)
class PrimaryReadingActionContractTest(churchPage: Boolean) : PrimaryReadingActionHarness(churchPage, 360, 800, 1f) {
    @Test fun shortUnknownAndPersonalStartsKeepTheirOwnMeaningAndCallbacks() {
        val short = fixture.copy(service = fixture.service.copy(title = "Литургия"), inferredYear = false, personalTime = "10:30")
        fixture = short
        launch()
        reveal(primaryTag).assertIsEnabled()
        compose.onNodeWithTag(titleTag).assertTextEquals("Литургия")
        compose.onAllNodesWithText("Литургия").assertCountEquals(1)
        compose.onNodeWithTag(relationTag).assertTextEquals("Возможно, идет · по времени начала")
        compose.onNodeWithTag(if (churchPage) "church_service_time" else "today_service_time").assertTextEquals("07:45")
        assertTrue(bounds(titleTag).bottom <= bounds(primaryTag).top)
        assertTrue(bounds(relationTag).bottom <= bounds(primaryTag).top)
        compose.onNodeWithTag("schedule_inferred_year").assertDoesNotExist()
        compose.onNodeWithTag(primaryTag).performClick()
        assertOfficialOpened(short.service)
        val personalTag = if (churchPage) "personal_time_to_service" else "today_open_personal_liturgy"
        val personalCard = if (churchPage) "church_personal_card" else "today_personal_card"
        reveal(personalTag).assert(hasAnyAncestor(hasTestTag(personalCard)))
        compose.onNodeWithTag(if (churchPage) "personal_start_summary" else "today_personal_summary")
            .assertTextEquals(if (churchPage) "10:30 · Литургия" else "13 ноября · 10:30 · Литургия")
        if (churchPage) compose.onNodeWithTag("personal_start_source").assertTextContains("Начало указано вами", substring = true)
        else compose.onNodeWithTag("today_personal_source").assertTextEquals("Начало указано вами")
        compose.onNodeWithTag(personalTag).performClick()
        compose.runOnIdle {
            assertEquals(listOf(PrimaryBookRequest(short.date, WorshipKind.LITURGY)).let {
                if (churchPage) it else it + it
            }, calls.books)
            assertEquals(if (churchPage) listOf(short.service) else emptyList<ParishService>(), calls.services)
        }

        val unknown = short.copy(service = short.service.copy(title = "Молебен перед началом учения", kind = "service_block"), personalTime = null)
        show(unknown)
        val chooseTag = if (churchPage) "church_open_service" else "today_choose_unknown_service"
        reveal(chooseTag)
        compose.onNodeWithTag(titleTag).assertTextEquals(unknown.service.title)
        compose.onNodeWithTag(kindTag).assertDoesNotExist()
        assertTrue(bounds(titleTag).bottom <= bounds(chooseTag).top)
        compose.onNodeWithTag(chooseTag).assertTextEquals(if (churchPage) "Выбрать доступные тексты" else "Выбрать текст службы").performClick()
        if (churchPage) {
            compose.runOnIdle { assertEquals(listOf(unknown.service), calls.services); assertTrue(calls.books.isEmpty()) }
            // The real Main owns this chooser; ChurchScreen must only hand it the unchanged service.
            compose.onNodeWithTag("service_book_chooser").assertDoesNotExist()
        } else {
            compose.runOnIdle { assertTrue(calls.books.isEmpty()); assertTrue(calls.services.isEmpty()) }
            compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 13 ноября 2026")
            reveal("service_book_choose_vespers", "service_book_chooser").performClick()
            compose.runOnIdle { assertEquals(listOf(PrimaryBookRequest(unknown.date, WorshipKind.VESPERS)), calls.books) }
        }

        if (churchPage) {
            show(PrimaryReadingFixture(allowChurchOpen = false))
            reveal(titleTag).assertTextEquals(fixture.service.title)
            compose.onNodeWithTag("church_open_service").assertDoesNotExist()
            reveal("schedule_inferred_year").assertIsDisplayed()
            reveal(verifyTag).performClick()
            compose.runOnIdle { assertTrue(calls.books.isEmpty()); assertTrue(calls.services.isEmpty()); assertEquals(listOf(fixture.source), calls.sites) }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0}")
        fun screens() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}

data class PrimaryBookRequest(val date: LocalDate, val kind: WorshipKind, val evening: Boolean = false)
class PrimaryActionCalls {
    val books = mutableListOf<PrimaryBookRequest>()
    val services = mutableListOf<ParishService>()
    val sites = mutableListOf<String>()
    fun clear() { books.clear(); services.clear(); sites.clear() }
}

data class PrimaryReadingFixture(
    val service: ParishService = ParishService("2026-11-13", "07:45", "Литургия «апостолов от 70-ти Стахия, Урвана»", "liturgy"),
    val inferredYear: Boolean = true,
    // A recently started service proves that uncertain year suppresses the underway estimate.
    val now: Instant = Instant.parse("2026-11-13T05:15:00Z"),
    val personalTime: String? = null,
    val allowChurchOpen: Boolean = true,
) {
    val date: LocalDate get() = LocalDate.parse(service.date)
    val home = "https://prorok-iliya.cerkov.ru/"
    val source = home + "raspisanie-bogosluzhenij/"
    val parish: Parish get() = Parish("osm:way:239273353", "Церковь Илии Пророка", "Церковь Илии Пророка",
        "Воронеж", "Воронеж, улица Севастьяновский съезд, 26", "", home, "Europe/Moscow", source, "",
        service.date, service.date, listOf(service), "", "")
    val state: InternetScheduleState get() = InternetScheduleState(parish.id, InternetScheduleStatus.READY,
        website = home, sourceUrl = source, publication = SchedulePublication(parish.id, parish.timezone, home, source, now.toString(),
            listOf(InternetService(service.date, service.time, service.title, service.kind)), yearInferred = inferredYear))
}

/** Shared fixture/lifecycle only. No app preferences, book cache, network or global font override in Compose. */
abstract class PrimaryReadingActionHarness(protected val churchPage: Boolean,
    private val width: Int, private val height: Int, private val scale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected var fixture by mutableStateOf(PrimaryReadingFixture())
    protected val calls = PrimaryActionCalls()
    protected val contentTag get() = if (churchPage) "church_content" else "main_content"
    protected val titleTag get() = if (churchPage) "church_service_title" else "today_service_title"
    protected val kindTag get() = if (churchPage) "church_service_kind" else "today_service_kind"
    protected val relationTag get() = if (churchPage) "church_service_relation" else "today_service_relation"
    protected val primaryTag get() = if (churchPage) "church_open_service" else "open_service_book"
    protected val verifyTag get() = if (churchPage) "church_verify_schedule_source" else "today_verify_schedule_source"
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private var previousFont: String? = null
    private var previousSize: String? = null
    private var displayChanged = false

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before fun configureDisplay() {
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
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        } finally {
            if (displayChanged) try { shell("wm size ${previousSize ?: "reset"}") }
            finally { shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale") }
        }
    }

    protected fun show(next: PrimaryReadingFixture) = compose.runOnIdle { fixture = next; calls.clear() }

    protected fun launch() {
        compose.setContent { GuideTheme(false) {
            Box(Modifier.fillMaxSize().testTag("primary_action_host")) {
                if (mounted.value) key(fixture) {
                    val current = fixture
                    val parish = current.parish
                    val starts = current.personalTime?.let { listOf(UserServiceStart(parish.id, current.service.date, it)) }.orEmpty()
                    val bottom: @Composable () -> Unit = { GuideBottomBar(if (churchPage) "church" else "home", {}, {}, {}) }
                    if (churchPage) ChurchScreen(listOf(parish), parish.id, current.date,
                        onSelect = { _, _ -> ChurchSelectionResult.FAILED }, onOpenUrl = { calls.sites += it }, onBack = {},
                        showBack = false, bottomBar = bottom, searchQuery = "", onSearchQueryChange = {},
                        internetSchedule = current.state, now = current.now, userStarts = starts,
                        onReadLiturgy = { calls.books += PrimaryBookRequest(it, WorshipKind.LITURGY) },
                        onOpenService = if (current.allowChurchOpen) ({ service -> calls.services += service }) else null)
                    else TodayScreen(parish, current.now, current.state, null, null,
                        onFindChurch = {}, onChurchDetails = {}, onResumeBook = {}, onRefresh = {}, onResumeLegacy = null,
                        bottomBar = bottom, userStarts = starts,
                        onOpenBook = { date, kind, evening -> calls.books += PrimaryBookRequest(date, kind, evening) },
                        onOpenScheduleSource = { calls.sites += current.source })
                }
            }
        } }
        contentSet = true
        compose.onNodeWithTag("primary_action_host").assertWidthIsEqualTo(width.dp)
            .assertHeightIsEqualTo(height.dp)
    }

    protected fun exerciseHierarchy(requireInitialVisibility: Boolean) {
        val report = JSONObject().put("allPassed", false).put("churchPage", churchPage)
            .put("requestedWidthDp", width).put("requestedHeightDp", height).put("requestedFontScale", scale)
            .put("scope", "Actual native display and production screens/bottom bar, archived service row, isolated callbacks; no live schedule or full 20-second journey claim")
            .put("strictInitialVisibility", requireInitialVisibility)
        try {
            launch()
            compose.waitForIdle()
            report.put("actualRootDp", rectangle(bounds("primary_action_host")))
            val initiallyComposed = compose.onAllNodesWithTag(primaryTag).fetchSemanticsNodes().size == 1
            val viewport = compose.onNodeWithTag(contentTag).getBoundsInRoot()
            val initial = if (initiallyComposed) bounds(primaryTag) else null
            val visible = initial?.let { inside(it, viewport) } == true
            report.put("initialViewportDp", rectangle(viewport)).put("initialPrimaryDp", initial?.let(::rectangle) ?: JSONObject.NULL)
                .put("initiallyFullyVisible", visible)
            capture("initial")
            if (requireInitialVisibility) {
                // This gate must fail only for the original clipping defect, not a tag added by the fix.
                assertTrue("The original Main viewport must expose the WHOLE primary action without any scroll: $initial in $viewport", visible)
                compose.onNodeWithTag(primaryTag).assertHeightIsAtLeast(56.dp)
                report.put("swipesToPrimary", 0).put("allPassed", true)
                return
            }
            var swipes = 0
            val primary = reveal(primaryTag, onSwipe = { swipes++ }).assertHeightIsAtLeast(56.dp)
            report.put("swipesToPrimary", swipes).put("visiblePrimaryDp", rectangle(bounds(primaryTag)))
            val label = if (churchPage) "Открыть литургию" else "Открыть службу"
            primary.assertTextEquals(label)
            labelFits(primaryTag, label)
            compose.onNodeWithTag(relationTag).assertTextEquals("Время из расписания храма")
            compose.onNodeWithTag("schedule_inferred_year").assertTextEquals("Год не указан - проверьте дату в расписании")
            compose.onNodeWithTag(kindTag).assertTextEquals("Литургия")
            compose.onNodeWithTag(titleTag).assertTextEquals(fixture.service.title)
            compose.onAllNodesWithText(fixture.service.title).assertCountEquals(1)
            // All five bounds come from the same scroll position, including off-screen details.
            val warning = bounds("schedule_inferred_year")
            val action = bounds(primaryTag)
            val title = bounds(if (churchPage) "church_service_source" else "today_service_source")
            val verify = bounds(verifyTag)
            assertTrue(warning.bottom <= action.top)
            assertTrue(action.bottom <= title.top)
            assertTrue(title.bottom <= verify.top)
            assertTrue(verify.top - action.bottom >= 12.dp)
            report.put("warningDp", rectangle(warning)).put("sourceTitleDp", rectangle(title)).put("verifyDp", rectangle(verify))
            capture("primary-visible")
            primary.performClick()
            assertOfficialOpened(fixture.service)
            reveal(verifyTag).assertHeightIsAtLeast(48.dp)
            labelFits(verifyTag, "Проверить источник")
            capture("source-visible")
            compose.onNodeWithTag(verifyTag).performClick()
            compose.runOnIdle { assertEquals(listOf(fixture.source), calls.sites) }
            report.put("sourceTitleExactAndUnique", true).put("warningBeforePrimary", true)
                .put("primaryBeforeDetails", true).put("callbacksCorrect", true).put("allPassed", true)
        } finally { output()?.let { File(it, "receipt.json").writeText(report.toString(2)) } }
    }

    protected fun assertOfficialOpened(service: ParishService) = compose.runOnIdle {
        assertEquals(if (churchPage) listOf(service) else emptyList<ParishService>(), calls.services)
        assertEquals(if (churchPage) emptyList<PrimaryBookRequest>() else listOf(PrimaryBookRequest(LocalDate.parse(service.date), WorshipKind.LITURGY)), calls.books)
    }

    protected fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun inside(target: DpRect, viewport: DpRect) = target.right > target.left && target.bottom > target.top &&
        target.top >= viewport.top && target.bottom <= viewport.bottom &&
        target.left >= viewport.left && target.right <= viewport.right

    protected fun reveal(tag: String, containerTag: String = contentTag, onSwipe: () -> Unit = {}): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        repeat(35) {
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            val target = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            val viewport = container.getBoundsInRoot()
            val box = if (target != null) bounds(tag) else null
            if (box != null && inside(box, viewport)) return compose.onNodeWithTag(tag).assertIsDisplayed()
            val backward = box != null && box.top < viewport.top
            container.performTouchInput {
                val from = if (backward) .35f else .8f
                val to = if (backward) .65f else .5f
                swipe(Offset(centerX, height * from), Offset(centerX, height * to), durationMillis = 700)
            }
            onSwipe()
        }
        throw AssertionError("Could not fully reveal $tag by bounded touch in $containerTag")
    }

    private fun labelFits(buttonTag: String, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(buttonTag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertTextFitsLayout(layout, label)
        assertEquals(scale, layout.layoutInput.density.fontScale, .001f)
        Regex("[А-Яа-яЁё]+").findAll(label).forEach { word ->
            assertEquals("Keep action words whole: ${word.value}", layout.getLineForOffset(word.range.first), layout.getLineForOffset(word.range.last))
        }
    }

    private fun output(): File? {
        val run = InstrumentationRegistry.getArguments().getString("primaryActionRun") ?: return null
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("primary-reading-action"))
        return File(root, "$run/${if (churchPage) "church" else "main"}-${width}x${height}-font${(scale * 100).toInt()}")
            .also { check(it.mkdirs() || it.isDirectory) }
    }

    private fun capture(name: String) {
        val directory = output() ?: return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idle may still precede the committed frame or activity fade-in.
        // Wait for a real draw, then require two identical compositor screenshots.
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val view = compose.activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.invalidate()
            }
            assertTrue("Screenshot frame was not committed", committed.await(5, TimeUnit.SECONDS))
        }
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            for (attempt in 0 until 8) {
                SystemClock.sleep(100)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                stable = bitmap.sameAs(next)
                bitmap.recycle()
                bitmap = next
                if (stable) break
            }
            assertTrue("Screenshot still contains an animation", stable)
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        finally { bitmap.recycle() }
    }

    private fun rectangle(value: DpRect) = JSONObject().put("left", value.left.value).put("top", value.top.value)
        .put("right", value.right.value).put("bottom", value.bottom.value)
}
