package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real navigation and reader, immutable public source books, isolated disk/prefs. No live network. */
@RunWith(AndroidJUnit4::class)
class PolyeleosReadingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "polyeleos-flow-${UUID.randomUUID()}"
    private val guidePreferences = context.getSharedPreferences("$prefix-guide", Context.MODE_PRIVATE)
    private val rawReadingPreferences = context.getSharedPreferences("$prefix-reading", Context.MODE_PRIVATE)
    private val readingPreferences = RejectingPreferences(rawReadingPreferences)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "guide_preferences" -> guidePreferences
            "service_book_reading" -> readingPreferences
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private val store = BookReadingStore(readingPreferences)
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val instant = Instant.parse("2026-11-12T13:00:00Z") // 16:00 in the selected parish, before the 17:00 service.
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val parishId = "osm:way:424243"
    private val serviceTitle = "Вечерня. Полиелейная утреня"
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
    private val daily = vespers.copy(worship = WorshipKind.MATINS)
    private val matins = daily.copy(topLevel = "3")
    private val journey = BookJourneyContext(parishId, "2026-11-12", "17:00", serviceTitle, listOf(vespers, matins))
    private val cacheDirectory = File(context.cacheDir, prefix)
    private val books = ControlledBooks(cacheDirectory, ::fixture)
    private val openedUrls = CopyOnWriteArrayList<String>()
    private val legacyPositionTemplate by lazy { requireNotNull(BookReadingCodec.decode(asset("legacy-position.json"))) }
    private val legacyJourney by lazy { requireNotNull(legacyPositionTemplate.journeyContext) }
    private var previousSize: String? = null
    private var previousFont: String? = null
    private var previousDisplayQuery: String? = null
    private var displayChanged = false

    @After fun clean() {
        try {
            if (contentSet) {
                compose.runOnIdle { readingPreferences.rejectWrites = false; mounted.value = false; books.releaseForCleanup() }
                compose.waitForIdle()
            }
            context.deleteSharedPreferences("$prefix-guide")
            context.deleteSharedPreferences("$prefix-reading")
            cacheDirectory.deleteRecursively()
        } finally {
            if (displayChanged) try {
                // reset is used only after a checked snapshot proved there was no previous override
                shell("wm size ${previousSize ?: "reset"}")
            } finally {
                shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale")
                assertEquals("Restore the exact previous size override", previousDisplayQuery, shell("wm size"))
                assertEquals("Restore the exact previous font preference", previousFont ?: "null", shell("settings get system font_scale"))
                android.util.Log.i("PolyeleosFlowQA", "display_restored size=$previousDisplayQuery font=${previousFont ?: "null"}")
            }
        }
    }

    @Test fun legacyDailyResumeStaysDailyButFreshMainOpensThePublishedRankedNeighbour() = withFailureCapture("legacyDailyResumeStaysDailyButFreshMainOpensThePublishedRankedNeighbour") {
        assertNull(legacyPositionTemplate.key.topLevel)
        val old = seed(daily, .37f, BookReadingMode.RUSSIAN, 61, true, legacyJourney)
        val bytes = books.exactBytes(old.key, old.contentHash)
        launch()
        home("resume_book").performClick()
        waitForBook(daily)
        assertPosition(old, saved())
        assertEquals(legacyJourney, saved().journeyContext)
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_rank_note", "book_content")
            .assertTextEquals("Прежний вариант утрени - полиелейный порядок не проверен")
        compose.onNodeWithText("По календарю Азбуки веры").assertDoesNotExist()
        compose.onNodeWithTag("book_back").performClick()
        settle()
        val acceptedDaily = requireNotNull(store.positionFor(daily, parishId))
        openFromHome()
        assertEquals(listOf(vespers, matins), saved().journeyContext?.parts)
        assertEquals(acceptedDaily, store.positionFor(daily, parishId))
        choosePart(WorshipKind.MATINS)
        assertEquals(matins, saved().key)
        assertEquals(journey, saved().journeyContext)
        assertEquals(acceptedDaily, store.positionFor(daily, parishId))
        assertArrayEquals(bytes, books.exactBytes(daily, old.contentHash))
        assertEquals(listOf(vespers, matins), books.requests.map { it.key })
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_rank_note", "book_content").assertTextEquals("По календарю Азбуки веры")
    }

    @Test fun churchFlowUsesRealSavedBooksAndKeepsBothCursorsThroughMeaningRecreationAndColdResume() = withFailureCapture("churchFlowUsesRealSavedBooksAndKeepsBothCursorsThroughMeaningRecreationAndColdResume") {
        val oldV = seed(vespers, .31f, BookReadingMode.RUSSIAN, 61, true)
        val oldM = seed(matins, .26f, BookReadingMode.ORIGINAL, 113, false)
        val restoration = launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_service_title"))
        compose.onNodeWithTag("church_service_title").assertTextEquals(serviceTitle)
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_open_service"))
        compose.onNodeWithTag("church_open_service").performClick()
        waitForBook(vespers); assertPosition(oldV, saved())
        assertEquals(journey, saved().journeyContext)
        moveInsideRealBlock(vespers)
        val beforeV = saved()
        choosePart(WorshipKind.MATINS); assertPosition(oldM, saved())
        moveInsideRealBlock(matins)
        val beforeM = saved()
        assertEquals(113, beforeM.wordsPerMinute); assertFalse(beforeM.showAltar)
        choosePart(WorshipKind.VESPERS); assertPosition(beforeV, saved())
        choosePart(WorshipKind.MATINS); assertPosition(beforeM, saved())
        selectMode(BookReadingMode.MEANING)
        val meaning = saved()
        assertEquals(BookReadingMode.MEANING, meaning.mode)
        assertEquals(BookReadingMode.ORIGINAL, meaning.lastTextMode)
        choosePart(WorshipKind.VESPERS); assertPosition(beforeV, saved())
        choosePart(WorshipKind.MATINS); assertPosition(meaning, saved())
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Размер текста и тема").performScrollTo().performClick()
        compose.onNodeWithTag("settings_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        waitForBook(matins); assertPosition(meaning, saved())
        restoration.emulateSavedInstanceStateRestore()
        waitForBook(matins); assertPosition(meaning, saved())
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        books.reopenCache()
        compose.runOnIdle { mounted.value = true }
        home("resume_book").performClick()
        waitForBook(matins); assertPosition(meaning, saved())
        selectMode(BookReadingMode.ORIGINAL)
        assertPosition(beforeM, saved())
        assertTrue(books.requests.isEmpty()); assertTrue(books.fixtureDeliveries.isEmpty())
        assertEquals(fixture(matins), books.cachedVersion(matins, oldM.contentHash))
    }

    @Test fun refusedPositionCommitCannotLeaveVespersOrPublishEitherRankedPointer() = withFailureCapture("refusedPositionCommitCannotLeaveVespersOrPublishEitherRankedPointer") {
        seed(vespers, .36f, BookReadingMode.RUSSIAN)
        books.publish(fixture(matins))
        launch(); openFromHome()
        val accepted = saved()
        val before = rawReadingPreferences.all.toMap()
        compose.runOnIdle { readingPreferences.rejectWrites = true }
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        waitForTag("book_part_switch_error")
        assertPosition(accepted, saved()); assertEquals(before, rawReadingPreferences.all)
        assertTrue(readingPreferences.rejectedCommits > 0)
        assertNull(store.positionFor(matins, parishId))
        assertNull(store.positionFor(daily, parishId))
        assertTrue(books.requests.isEmpty())
        assertSelectedPart(WorshipKind.VESPERS)
    }

    @Test fun returnRequiresTheWholeRankedContextAndRetryKeepsItsRequestedRankAndDate() = withFailureCapture("returnRequiresTheWholeRankedContextAndRetryKeepsItsRequestedRankAndDate") {
        val accepted = seed(vespers, .38f, BookReadingMode.RUSSIAN, acceptedContext = legacyJourney)
        books.failures[matins] = BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи для загрузки утрени")
        launchScreen(BookRoute(matins, parishId, journey, vespers))
        waitForTag("book_load_error")
        compose.onNodeWithTag("book_journey_return").assertDoesNotExist()
        assertPosition(accepted, saved())
        // Same V key/hash, but its accepted occurrence must include the ranked neighbour too.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val confirmed = accepted.copy(journeyContext = journey)
        assertTrue(store.save(confirmed))
        compose.runOnIdle { mounted.value = true }
        waitForTag("book_load_error"); waitForTag("book_journey_return")
        compose.onNodeWithTag("book_journey_return").performClick()
        waitForBook(vespers); assertPosition(confirmed, saved())
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        waitForTag("book_load_error")
        books.failures.remove(matins)
        compose.onNodeWithText("Попробовать еще раз").performClick()
        waitForBook(matins)
        assertTrue(books.requests.all { it.key == matins })
        assertTrue(books.requests.last().force)
        assertEquals("2026-11-13", saved().key.date); assertEquals("3", saved().key.topLevel)
        assertNull(store.positionFor(daily, parishId))
    }

    @Test fun aLateRankedReplyAfterReturningCannotReplaceVespersOrCreateAMatinsCursor() = withFailureCapture("aLateRankedReplyAfterReturningCannotReplaceVespersOrCreateAMatinsCursor") {
        val accepted = seed(vespers, .32f, BookReadingMode.RUSSIAN)
        val pending = Request(matins, false)
        books.hold(pending)
        launch(); openFromHome()
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        compose.waitUntil(5_000) { books.isPending(pending) }
        compose.onNodeWithTag("book_loading").assertIsDisplayed()
        waitForTag("book_journey_return")
        compose.onNodeWithTag("book_journey_return").performClick()
        waitForBook(vespers); assertPosition(accepted, saved())
        val returned = saved()
        compose.runOnIdle { books.release(pending, fixture(matins)) }
        compose.waitUntil(5_000) { pending in books.delivered }
        settle()
        assertPosition(returned, saved())
        assertNull(store.positionFor(matins, parishId)); assertNull(store.positionFor(daily, parishId))
        assertSelectedPart(WorshipKind.VESPERS)
        assertEquals(listOf(matins), books.requests.map { it.key })
    }

    @Test fun recoveryRejectsADailyBookAndOnlyExplicitlyOpensAnAcceptedRankedOldChurchVariant() = withFailureCapture("recoveryRejectsADailyBookAndOnlyExplicitlyOpensAnAcceptedRankedOldChurchVariant") {
        val parish = testParish("Храм Николая Чудотворца и Святой Троицы")
        val request = matins.copy(churchType = "30")
        seed(daily, .22f, BookReadingMode.RUSSIAN, acceptedContext = null)
        books.failures[request] = BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи для нового варианта храма")
        launchScreen(BookRoute(request, parishId), parish)
        waitForTag("book_load_error")
        compose.onNodeWithTag("book_open_previous_variant").assertDoesNotExist()
        assertEquals(daily, saved().key)
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val acceptedRanked = seed(matins, .28f, BookReadingMode.ORIGINAL, acceptedContext = null)
        compose.runOnIdle { mounted.value = true }
        waitForTag("book_open_previous_variant")
        assertEquals(acceptedRanked, saved())
        compose.onNodeWithTag("book_open_previous_variant").performClick()
        waitForBook(matins); assertPosition(acceptedRanked, saved())
        assertNull(store.positionFor(request, parishId))
        assertNotNull(store.positionFor(daily, parishId))
        assertTrue(books.requests.all { it.key == request })
    }

    @Test fun aNewRankedEditionWaitsForAcceptanceAndKeepsTheRealPrayerCursor() = withFailureCapture("aNewRankedEditionWaitsForAcceptanceAndKeepsTheRealPrayerCursor") {
        val initial = seed(matins, .37f, BookReadingMode.RUSSIAN, 73)
        val old = fixture(matins)
        // Deliberately synthetic edition metadata; every prayer and source selection remains exact.
        val newer = fixture(matins, "Обновленная редакция - проверочная метка QA")
        assertNotEquals(old.contentHash, newer.contentHash)
        assertEquals(old.chapters, newer.chapters)
        books.publish(newer)
        val restoration = launch()
        home("resume_book").performClick()
        waitForBook(matins); assertPosition(initial, saved())
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(newer.contentHash, saved().contentHash) }
        settle()
        val accepted = saved()
        assertPosition(initial.copy(contentHash = newer.contentHash), accepted)
        assertEquals(old, books.cachedVersion(matins, old.contentHash))
        restoration.emulateSavedInstanceStateRestore()
        waitForBook(matins); assertPosition(accepted, saved())
        assertEquals(newer.rankSelection, books.cachedVersion(matins, accepted.contentHash)?.rankSelection)
        assertTrue(books.requests.isEmpty()); assertTrue(books.fixtureDeliveries.isEmpty())
    }

    @Test fun native320Dp200PercentShowsTheRankClearlyAndKeepsSourceAndLanguageActionsUsable() = withFailureCapture("native320Dp200PercentShowsTheRankClearlyAndKeepsSourceAndLanguageActionsUsable") {
        configureCompactDisplay()
        books.publish(fixture(matins)); books.publish(fixture(daily))
        val route = launchScreen(BookRoute(matins, parishId, journey))
        waitForBook(matins)
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_rank_note", "book_content").assertTextEquals("По календарю Азбуки веры")
        assertReadable("book_rank_note")
        captureScreen("320dp-font200-rank")
        compose.onNodeWithText("Полиелейная утреня").assertExists()
        selectMode(BookReadingMode.RUSSIAN)
        selectMode(BookReadingMode.MEANING)
        selectMode(BookReadingMode.ORIGINAL)
        val firstPrayer = fixture(matins).blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_block_${firstPrayer.id}"))
        compose.onNodeWithTag("book_block_${firstPrayer.id}").assertIsDisplayed()
        openSources()
        val sources = sourceList()
        sources.performScrollToNode(hasText(fixture(matins).sourceTitle))
        compose.onNodeWithText(fixture(matins).sourceTitle).performClick()
        assertEquals(listOf(fixture(matins).sourceUrl), openedUrls.toList())
        sources.performScrollToIndex(0)
        compose.onNodeWithText("Вариант дня").assertDoesNotExist()
        assertFalse("The ranked fixture has real options, so their absence exercises filtering", fixture(matins).mainEventOptions.size < 2)
        val lastOption = fixture(matins).mainEventOptions.last().title
        scrollSourcesToEnd()
        compose.onNodeWithText("Вариант дня").assertDoesNotExist()
        compose.onAllNodes(hasText(lastOption) and hasClickAction()).assertCountEquals(0)
        dismissSheet()
        compose.runOnIdle { route.value = BookRoute(daily, parishId, legacyJourney) }
        waitForBook(daily)
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_rank_note", "book_content")
            .assertTextEquals("Прежний вариант утрени - полиелейный порядок не проверен")
        assertReadable("book_rank_note")
        captureScreen("320dp-font200-legacy-rank")
        openSources()
        sourceList().performScrollToNode(hasText("Вариант дня"))
        compose.onNodeWithText("Вариант дня").assertIsDisplayed()
        assertTrue(fixture(daily).mainEventOptions.size > 1)
    }

    private fun launch(): StateRestorationTester {
        assertTrue(guidePreferences.edit().putBoolean("dark", false).putInt("text_size", 0).commit())
        assertTrue(ChurchDirectoryStore(guidePreferences).save(SavedChurchRecord(parishId, "Храм святителя Николая",
            "Москва", "Тестовая улица, 8", "Europe/Moscow", 55.75, 37.62, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            website = "https://parish.example", sourceUrl = "https://www.openstreetmap.org/way/424243")))
        assertTrue(guidePreferences.edit().putString("church_id", parishId).commit())
        val schedules = object : InternetSchedules {
            override fun cached(parish: Parish) = InternetScheduleState(parish.id, InternetScheduleStatus.READY,
                website = "https://parish.example", sourceUrl = "https://parish.example/schedule", checkedAt = instant.toString(),
                publication = SchedulePublication(parish.id, parish.timezone, "https://parish.example", "https://parish.example/schedule",
                    instant.toString(), listOf(InternetService("2026-11-12", "17:00", serviceTitle, "service"),
                        InternetService("2026-11-13", "09:00", "Литургия", "liturgy"))))
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?) = cached(parish)
        }
        val container = AppContainer(context, scheduleOverride = schedules, booksOverride = books)
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides localContext, LocalActivityResultRegistryOwner provides compose.activity) {
                    if (mounted.value) AppNavigation(container, clock, monotonicMillis = { 1_000L })
                }
            }
            contentSet = true
        }
    }

    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun openFromHome(expectSaved: Boolean = true) {
        home("today_service_date").assertTextEquals("12 ноября")
        home("today_service_time").assertTextEquals("17:00")
        home("today_service_kind").assertTextEquals("Вечерня и утреня")
        home("today_service_title").assertTextEquals(serviceTitle).assert(hasAnyAncestor(hasTestTag("today_service_source")))
        home("open_service_book").performClick()
        if (expectSaved) waitForBook(vespers) else { waitForTag("book_content"); settle() }
    }

    private fun assertIntro() {
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_journey_schedule", "book_content").assertTextContains("12 ноября", substring = true)
            .assertTextContains("17:00", substring = true)
        reveal("book_journey_date", "book_content").assertTextContains("13 ноября", substring = true)
    }

    private fun choosePart(kind: WorshipKind, expectLoaded: Boolean = true) {
        compose.onNodeWithTag("book_order").performClick()
        val tag = "book_part_${kind.name.lowercase()}"
        compose.onNodeWithTag("book_order_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsNotSelected().assertIsDisplayed().performClick()
        if (expectLoaded) waitForBook(if (kind == WorshipKind.VESPERS) vespers else matins)
    }

    private fun assertSelectedPart(kind: WorshipKind) {
        compose.onNodeWithTag("book_order").performClick()
        val selected = "book_part_${kind.name.lowercase()}"
        compose.onNodeWithTag("book_order_list").performScrollToNode(hasTestTag(selected))
        compose.onNodeWithTag(selected).assertIsSelected()
        dismissSheet()
    }

    private fun dismissSheet() {
        val dismiss = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        dismiss.assertCountEquals(1)
        dismiss[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        compose.waitForIdle()
    }

    private fun reveal(tag: String, containerTag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        repeat(16) {
            compose.waitForIdle()
            val exists = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
            val viewport = container.getBoundsInRoot()
            val bounds = if (exists) compose.onNodeWithTag(tag).getUnclippedBoundsInRoot() else null
            if (bounds != null && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                bounds.left >= viewport.left && bounds.right <= viewport.right) return compose.onNodeWithTag(tag).assertIsDisplayed()
            val backwards = bounds != null && bounds.top < viewport.top
            container.performTouchInput {
                swipe(Offset(centerX, height * if (backwards) .35f else .8f),
                    Offset(centerX, height * if (backwards) .65f else .5f), durationMillis = 500)
            }
        }
        throw AssertionError("Could not fully reveal $tag in $containerTag by bounded touch")
    }

    private fun moveInsideRealBlock(key: ServiceBookKey) {
        val anchor = anchor(fixture(key))
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_block_${anchor.second.id}"))
        compose.onNodeWithTag("book_content").performTouchInput { swipeUp(durationMillis = 400) }
        settle()
        val position = saved()
        assertEquals(anchor.second.id, position.blockId)
        assertEquals(BookAnchorKind.BLOCK, position.anchorKind)
        val density = compose.activity.resources.displayMetrics.density
        val viewportTopPx = compose.onNodeWithTag("book_content").getBoundsInRoot().top.value * density
        val bounds = compose.onNodeWithTag("book_block_${anchor.second.id}").getUnclippedBoundsInRoot()
        val topPx = bounds.top.value * density
        val bottomPx = bounds.bottom.value * density
        val heightPx = bottomPx - topPx
        // A long prayer can legitimately be read near its end. Prove the viewport starts
        // inside this measured block, away from its edges, rather than assuming a fraction range.
        assertTrue("Viewport $viewportTopPx must lie inside block [$topPx, $bottomPx] with a 4px edge margin",
            viewportTopPx > topPx + 4f && viewportTopPx < bottomPx - 4f)
        val measuredFraction = (viewportTopPx - topPx) / heightPx
        // Two physical pixels cover layout rounding; the tolerance scales with the actual height.
        assertEquals("Saved fraction must identify the line measured in the real prayer block",
            measuredFraction, position.fraction, 2f / heightPx + 0.00001f)
    }

    private fun assertPosition(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.copy(fraction = actual.fraction), actual)
        assertEquals("An in-block semantic cursor must survive the route", expected.fraction, actual.fraction, .001f)
    }

    private fun saved() = requireNotNull(store.last(parishId))
    private fun waitForTag(tag: String) {
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            captureFailure(failure, tag)
            throw failure
        }
    }
    private fun withFailureCapture(label: String, block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            captureFailure(failure, label)
            throw failure
        }
    }

    private fun captureFailure(failure: Throwable, tag: String) {
            // Capture before @After removes the isolated fixture state, without touching real user preferences.
            val directory = File(context.getExternalFilesDir("polyeleos-failure"), "$prefix-$tag").apply { mkdirs() }
            runCatching {
                File(directory, "state.txt").writeText(buildString {
                    appendLine("failure=$failure")
                    appendLine("guide=${guidePreferences.all}")
                    appendLine("reading=${rawReadingPreferences.all}")
                    appendLine("requests=${books.requests}")
                    appendLine("fixtureDeliveries=${books.fixtureDeliveries}")
                    appendLine("delivered=${books.delivered}")
                    listOf(vespers, daily, matins).forEach { key ->
                        val position = store.positionFor(key, parishId)
                        appendLine("position=$position")
                        appendLine("pinned=${position?.let { books.cachedVersion(key, it.contentHash)?.contentHash }}")
                    }
                })
            }
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            runCatching {
                var remaining = 600
                fun describe(node: AccessibilityNodeInfo?, depth: Int, out: StringBuilder) {
                    if (node == null || depth > 30 || remaining-- <= 0) return
                    out.appendLine("  ".repeat(depth) + "${node.className} text=${node.text} description=${node.contentDescription} " +
                        "visible=${node.isVisibleToUser} id=${node.viewIdResourceName}")
                    repeat(node.childCount.coerceAtMost(100)) { describe(node.getChild(it), depth + 1, out) }
                }
                File(directory, "accessibility.txt").writeText(buildString {
                    describe(instrumentation.uiAutomation.rootInActiveWindow, 0, this)
                })
            }
            runCatching {
                listOf(false, true).forEach { unmerged ->
                    val roots = compose.onAllNodes(isRoot(), useUnmergedTree = unmerged)
                    repeat(roots.fetchSemanticsNodes().size) { index ->
                        File(directory, "semantics-$unmerged-$index.txt").writeText(roots[index].printToString())
                    }
                }
            }
            android.util.Log.e("PolyeleosFlowQA", "failure_capture=${directory.absolutePath}", failure)
    }

    private fun waitForBook(key: ServiceBookKey) {
        waitForTag("book_content")
        settle()
        assertEquals(key, saved().key)
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun seed(key: ServiceBookKey, fraction: Float, mode: BookReadingMode,
        speed: Int = 85, altar: Boolean = false, acceptedContext: BookJourneyContext? = journey): BookReadingPosition {
        val book = fixture(key)
        books.publish(book)
        val (chapter, block) = anchor(book)
        val position = BookReadingPosition(key, book.contentHash, parishId, block.id, chapter.id,
            fraction = fraction, mode = mode, wordsPerMinute = speed, showAltar = altar,
            lastTextMode = mode.takeUnless { it == BookReadingMode.MEANING } ?: BookReadingMode.RUSSIAN,
            journeyContext = acceptedContext)
        assertTrue(store.save(position))
        return position
    }

    private fun anchor(book: ServiceBook): Pair<BookChapter, BookBlock> = book.chapters.flatMap { chapter ->
        chapter.blocks.filter { it.kind == BookBlockKind.PRAYER && !it.isAltarText }.map { chapter to it }
    }.maxBy { it.second.original.length }.also { check(it.second.original.length > 500) }

    private fun assertReadable(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$tag must stay inside the 320dp screen", bounds.left >= 0.dp && bounds.right <= 321.dp)
        val texts = compose.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        // performSemanticsAction dispatches the layout callback on the UI thread
        repeat(texts.fetchSemanticsNodes().size) { index ->
            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        }
        assertTrue("$tag must expose its visible text", results.isNotEmpty())
        results.forEach { layout ->
            assertTextFitsLayout(layout, layout.layoutInput.text.text)
            assertTrue("$tag must retain the native 200% font", layout.layoutInput.density.fontScale >= 1.99f)
        }
    }

    private fun configureCompactDisplay() {
        check(shell("getprop ro.kernel.qemu") == "1") { "Display mutation is allowed only on the test emulator" }
        previousFont = shell("settings get system font_scale").takeUnless { it == "null" }
        check(previousFont == null || previousFont!!.toFloatOrNull()?.let { it > 0f && it.isFinite() } == true)
        previousDisplayQuery = shell("wm size")
        check(Regex("Physical size: \\d+x\\d+").containsMatchIn(requireNotNull(previousDisplayQuery)))
        previousSize = Regex("Override size: (\\d+x\\d+)").find(requireNotNull(previousDisplayQuery))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        displayChanged = true
        shell("settings put system font_scale 2.0")
        shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = abs(it.resources.configuration.fontScale - 2f) < .001f &&
                    abs(it.resources.configuration.screenWidthDp - 320) <= 1 && it.window.decorView.hasWindowFocus()
            }
            ready
        }
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        android.util.Log.i("PolyeleosFlowQA", "display_profile widthDp=${compose.activity.resources.configuration.screenWidthDp} " +
            "fontScale=${compose.activity.resources.configuration.fontScale} wm=${shell("wm size")}")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }.also {
            check(!it.contains("Error", ignoreCase = true) && !it.contains("denied", ignoreCase = true) &&
                !it.contains("Exception")) { "Display command failed: $command -> $it" }
        }

    private data class Request(val key: ServiceBookKey, val force: Boolean)

    private fun launchScreen(initial: BookRoute, parish: Parish = testParish()): androidx.compose.runtime.MutableState<BookRoute> {
        val route = mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localContext, LocalActivityResultRegistryOwner provides compose.activity) {
                GuideTheme(false) {
                    if (mounted.value) ServiceBookScreen(route.value.activeKey, parishId, books, store, 0,
                        onBack = {}, onSettings = {}, onOpenUrl = { openedUrls += it }, parish = parish,
                        route = route.value, onRouteChanged = { route.value = it; true }, onOpenCommonLiturgy = {})
                }
            }
        }
        contentSet = true
        return route
    }

    private fun testParish(name: String = "Храм святителя Николая") = Parish(parishId, name, name, "Москва", "Тестовая улица, 8",
        "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")

    private fun selectMode(mode: BookReadingMode) {
        val menu = compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty()
        if (menu) compose.onNodeWithTag("book_mode_menu").assertHeightIsAtLeast(48.dp).performClick()
        val tag = "book_mode_${mode.name.lowercase()}"
        compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).performClick()
        settle()
        assertEquals(mode, saved().mode)
        if (menu) {
            compose.onNodeWithTag("book_mode_menu").performClick()
            // Verify the actual selected item, then close the menu with the same selection.
            compose.onNodeWithTag(tag).assertIsSelected().performClick()
            settle()
        } else compose.onNodeWithTag(tag).assertIsSelected()
    }

    private fun captureScreen(name: String) {
        val output = File(context.getExternalFilesDir("polyeleos-qa"), prefix).apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun openSources() {
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performClick()
    }

    private fun sourceList() = compose.onNode(hasScrollToIndexAction() and !hasTestTag("book_content"))

    private fun scrollSourcesToEnd() {
        repeat(16) {
            val node = sourceList()
            val before = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            node.performTouchInput { swipeUp(durationMillis = 400) }
            compose.waitForIdle()
            val after = sourceList().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            if (before == after) return
        }
        error("Sources did not reach their end in bounded scrolling")
    }

    private class ControlledBooks(directory: File, private val factory: (ServiceBookKey, String?) -> ServiceBook) : ServiceBooks {
        private val directory = directory
        private var cache = FileBookCache(directory)
        val requests = CopyOnWriteArrayList<Request>()
        val fixtureDeliveries = CopyOnWriteArrayList<Request>()
        val delivered = CopyOnWriteArrayList<Request>()
        val failures = ConcurrentHashMap<ServiceBookKey, BookLoadResult.Failed>()
        private val held = ConcurrentHashMap.newKeySet<Request>()
        private val callbacks = ConcurrentHashMap<Request, Continuation<BookLoadResult>>()
        fun hold(request: Request) { held += request }
        fun isPending(request: Request) = callbacks.containsKey(request)
        fun publish(book: ServiceBook) { check(ServiceBookIntegrity.valid(book)); check(cache.write(book)) }
        fun reopenCache() { cache = FileBookCache(directory) }
        fun exactBytes(key: ServiceBookKey, hash: String) = File(directory, "${BookText.sha256(key.canonical()).take(32)}-$hash.json").readBytes()
        override fun cached(key: ServiceBookKey): ServiceBook? = cache.read(key)
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = cache.readVersion(key, contentHash)
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            val request = Request(key, force)
            requests += request
            cached(key)?.takeUnless { force }?.let { return BookLoadResult.Ready(it, fromCache = true) }
            failures[key]?.let { return it }
            if (request in held) {
                // A non-cancellable callback is intentional: abandoning a route must still reject a late result.
                val result = suspendCoroutine<BookLoadResult> { callbacks[request] = it }
                delivered += request
                return result
            }
            fixtureDeliveries += request
            val book = factory(key, null)
            publish(book)
            return BookLoadResult.Ready(book, fromCache = false)
        }
        fun release(request: Request, book: ServiceBook) {
            publish(book)
            requireNotNull(callbacks.remove(request)).resume(BookLoadResult.Ready(book, fromCache = false))
        }
        fun releaseForCleanup() { callbacks.keys.toList().forEach { callbacks.remove(it)?.resume(BookLoadResult.Failed(BookFailure.NETWORK, "Test finished")) } }
    }

    private class RejectingPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        @Volatile var rejectWrites = false
        var rejectedCommits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (!rejectWrites) return committed
                    rejectedCommits++
                    return false // Exercise rollback after the real preferences changed in memory/disk.
                }
            }
        }
    }

    private fun asset(name: String) = instrumentation.context.assets.open("polyeleos-journey/$name")
        .bufferedReader().use { it.readText() }

    private val sourceBooks by lazy {
        val v = ServiceBookParser().parse(Jsoup.parse(asset("vespers-2026-11-13-church34.html")), vespers, instant.toEpochMilli())
        val m0 = ServiceBookParser().parse(Jsoup.parse(asset("daily-matins-2026-11-13-church34.html")), daily, instant.toEpochMilli())
        val m3 = Gson().fromJson(asset("ranked-matins-2026-11-13-church34.json"), ServiceBook::class.java)
        assertEquals(matins, m3.key)
        assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", m3.contentHash)
        assertEquals(BookRankSelection("0", "", "3"), m3.rankSelection)
        assertEquals("https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=34&main_event=0&top_level=3", m3.sourceUrl)
        assertEquals(831, m3.blocks.size)
        assertEquals(373, m3.blocks.count { it.russian != null })
        listOf(v, m0, m3).onEach { assertTrue(ServiceBookIntegrity.valid(it)) }.associateBy { it.key }
    }

    private fun fixture(key: ServiceBookKey, title: String? = null): ServiceBook {
        val source = requireNotNull(sourceBooks[key]) { "No real source fixture for $key" }
        return if (title == null) source else source.copy(title = title, contentHash = "").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
    }
}
