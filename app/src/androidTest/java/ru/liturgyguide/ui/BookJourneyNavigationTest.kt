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

/** Real AppNavigation and isolated durable preferences; fixture books only control delivery and cache. */
@RunWith(AndroidJUnit4::class)
class BookJourneyNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "book-journey-${UUID.randomUUID()}"
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
    private val serviceTitle = "Вечерня. Утреня"
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
    private val matins = vespers.copy(worship = WorshipKind.MATINS)
    private val journey = BookJourneyContext(parishId, "2026-11-12", "17:00", serviceTitle, listOf(vespers, matins))
    private val books = ControlledBooks(::fixture)
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
        } finally {
            if (displayChanged) try {
                // reset is used only after a checked snapshot proved there was no previous override
                shell("wm size ${previousSize ?: "reset"}")
            } finally {
                shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale")
                assertEquals("Restore the exact previous size override", previousDisplayQuery, shell("wm size"))
                assertEquals("Restore the exact previous font preference", previousFont ?: "null", shell("settings get system font_scale"))
                android.util.Log.i("BookJourneyQA", "display_restored size=$previousDisplayQuery font=${previousFont ?: "null"}")
            }
        }
    }

    @Test fun mainEntrySwitchesBothWaysWithIndependentPositionsModesAndPinnedVersionsOffline() {
        val oldV = seed(vespers, "a", .31f, BookReadingMode.RUSSIAN, 61, true)
        val oldM = seed(matins, "b", .26f, BookReadingMode.ORIGINAL, 113, false)
        books.publish(fixture(vespers, "Новая редакция вечерни"))
        books.publish(fixture(matins, "Новая редакция утрени"))
        launch()
        openFromHome()
        assertEquals(oldV.contentHash, saved().contentHash)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        assertIntro()
        moveInsideBlock("a")
        val beforeV = saved()
        assertInside(beforeV, "a")
        assertEquals(61, beforeV.wordsPerMinute)
        assertTrue(beforeV.showAltar)

        choosePart(WorshipKind.MATINS)
        assertPosition(oldM, saved())
        compose.onNodeWithTag("book_mode_original").assertIsSelected()
        assertIntro()
        moveInsideBlock("b")
        val beforeM = saved()
        assertInside(beforeM, "b")
        assertEquals(113, beforeM.wordsPerMinute)
        assertFalse(beforeM.showAltar)

        choosePart(WorshipKind.VESPERS)
        assertPosition(beforeV, saved())
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        choosePart(WorshipKind.MATINS)
        assertPosition(beforeM, saved())
        compose.onNodeWithTag("book_mode_original").assertIsSelected()
        assertPosition(beforeV, requireNotNull(store.positionFor(vespers, parishId)))
        compose.onNodeWithTag("book_mode_meaning").performClick()
        settle()
        val meaningM = saved()
        assertEquals(BookReadingMode.MEANING, meaningM.mode)
        assertEquals(BookReadingMode.ORIGINAL, meaningM.lastTextMode)
        choosePart(WorshipKind.VESPERS)
        assertPosition(beforeV, saved())
        choosePart(WorshipKind.MATINS)
        assertPosition(meaningM, saved())
        compose.onNodeWithTag("book_mode_meaning").assertIsSelected()
        assertTrue("Both accepted editions were cached; no load or network is necessary", books.requests.isEmpty())
        assertTrue(books.networkRequests.isEmpty())
    }

    @Test fun churchEntryAndEndActionUseTheSamePublishedOccurrenceWithoutAnAutomaticSwitch() {
        books.publish(fixture(vespers)); books.publish(fixture(matins))
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_service_time"))
        compose.onNodeWithTag("church_service_time").assertTextEquals("17:00")
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_service_kind"))
        compose.onNodeWithTag("church_service_kind").assertTextEquals("Вечерня и утреня")
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_service_title"))
        compose.onNodeWithTag("church_service_title").assertTextEquals(serviceTitle)
            .assert(hasAnyAncestor(hasTestTag("church_service_source")))
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_open_service"))
        compose.onNodeWithTag("church_open_service").performClick()
        waitForBook(vespers)
        assertIntro()
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_next_part"))
        settle()
        assertEquals(vespers, saved().key)
        assertNull(store.positionFor(matins, parishId))
        compose.onNodeWithTag("book_next_part").assertIsDisplayed().performClick()
        waitForBook(matins)
        assertIntro()
        assertEquals(listOf(vespers, matins), books.requests.map { it.key })
        assertTrue(books.networkRequests.isEmpty())
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_end"))
        compose.onNodeWithTag("book_next_part").assertDoesNotExist()
    }

    @Test fun settingsRecreationAndColdHomeResumeKeepTheExactMatinsContextAndCursor() {
        seed(vespers, "a", .33f, BookReadingMode.RUSSIAN)
        val initial = seed(matins, "b", .29f, BookReadingMode.RUSSIAN, 73)
        val restoration = launch()
        openFromHome()
        choosePart(WorshipKind.MATINS)
        assertPosition(initial, saved())
        val accepted = saved()
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Размер текста и тема").performScrollTo().performClick()
        compose.onNodeWithTag("settings_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        waitForBook(matins)
        assertPosition(accepted, saved())
        restoration.emulateSavedInstanceStateRestore()
        waitForBook(matins)
        assertPosition(accepted, saved())
        assertSelectedPart(WorshipKind.MATINS)
        compose.onNodeWithTag("book_back").performClick()
        home("resume_book").performClick()
        waitForBook(matins)
        assertPosition(accepted, saved())
        // Remove the entire real navigation owner: this next resume only has durable preferences.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        home("resume_book").performClick()
        waitForBook(matins)
        assertPosition(accepted, saved())
        assertEquals(journey, saved().journeyContext)
        assertTrue(books.requests.isEmpty())
    }

    @Test fun failedMatinsLoadCanReturnToTheAcceptedVespersAndRetryTheSameDate() {
        val accepted = seed(vespers, "a", .38f, BookReadingMode.RUSSIAN)
        books.failures[matins] = BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи для загрузки утрени")
        launch(); openFromHome()
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        waitForTag("book_load_error")
        compose.onNodeWithTag("book_load_error").assertTextContains("Нет связи", substring = true)
        assertPosition(accepted, saved())
        assertNull(store.positionFor(matins, parishId))
        compose.onNodeWithTag("book_journey_return").assertIsDisplayed().performClick()
        waitForBook(vespers)
        assertPosition(accepted, saved())
        books.failures.remove(matins)
        choosePart(WorshipKind.MATINS)
        assertEquals(journey, saved().journeyContext)
        assertEquals(listOf(matins, matins), books.requests.map { it.key })
        assertEquals("2026-11-13", saved().key.date)
    }

    @Test fun failedPositionCommitKeepsTheVisiblePartAndBothDurablePointers() {
        seed(vespers, "a", .36f, BookReadingMode.RUSSIAN)
        books.publish(fixture(matins))
        launch(); openFromHome()
        val accepted = saved()
        val before = rawReadingPreferences.all.toMap()
        compose.runOnIdle { readingPreferences.rejectWrites = true }
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        waitForTag("book_part_switch_error")
        compose.onNodeWithTag("book_part_switch_error").assertIsDisplayed()
        assertPosition(accepted, saved())
        assertEquals(before, rawReadingPreferences.all)
        assertTrue("The failed commit must be exercised", readingPreferences.rejectedCommits > 0)
        assertNull(store.positionFor(matins, parishId))
        assertTrue(books.requests.isEmpty())
        assertSelectedPart(WorshipKind.VESPERS)
    }

    @Test fun anUnsavedBookCannotPublishADanglingCursorOrOpenTheNextPart() {
        val oldKey = ServiceBookKey("2026-11-11", WorshipKind.LITURGY, "34")
        val old = seed(oldKey, "a", .34f, BookReadingMode.RUSSIAN, context = null)
        books.unsaved += vespers
        launch(); openFromHome(expectSaved = false)
        assertIntro()
        val before = rawReadingPreferences.all.toMap()
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        waitForTag("book_part_switch_error")
        compose.onNodeWithTag("book_part_switch_error").assertTextContains("пока не сохранен", substring = true)
        assertEquals(old, saved())
        assertEquals(before, rawReadingPreferences.all)
        assertNull(store.positionFor(vespers, parishId))
        assertNull(store.positionFor(matins, parishId))
        assertEquals(listOf(vespers), books.requests.map { it.key })
        assertNull(books.cachedVersion(vespers, fixture(vespers).contentHash))
    }

    @Test fun returningWhileMatinsIsPendingRejectsItsLateReplyAndLeavesNoMatinsCursor() {
        val accepted = seed(vespers, "a", .32f, BookReadingMode.RUSSIAN)
        val pending = Request(matins, false)
        books.hold(pending)
        launch(); openFromHome()
        choosePart(WorshipKind.MATINS, expectLoaded = false)
        compose.waitUntil(5_000) { books.isPending(pending) }
        compose.onNodeWithTag("book_loading").assertIsDisplayed()
        compose.onNodeWithTag("book_journey_return").assertIsDisplayed().performClick()
        waitForBook(vespers)
        val returned = saved()
        assertPosition(accepted, returned)
        compose.runOnIdle { books.release(pending, fixture(matins, "Запоздавшая утреня")) }
        compose.waitUntil(5_000) { pending in books.delivered }
        settle()
        assertPosition(returned, saved())
        assertNull(store.positionFor(matins, parishId))
        compose.onNodeWithText("Запоздавшая утреня").assertDoesNotExist()
        assertSelectedPart(WorshipKind.VESPERS)
        assertEquals(listOf(matins), books.requests.map { it.key })
    }

    @Test fun libraryOpeningTheSameMatinsKeyClearsTheOldOccurrenceAndDoesNotBorrowItsEvening() {
        seed(vespers, "a", .31f, BookReadingMode.RUSSIAN)
        val accepted = seed(matins, "b", .28f, BookReadingMode.ORIGINAL)
        launch()
        home("today_day_tomorrow").performClick()
        home("today_other_services").performClick()
        compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag("service_book_choose_matins"))
        compose.onNodeWithTag("service_book_choose_matins").performClick()
        waitForBook(matins)
        assertPosition(accepted.copy(journeyContext = null), saved())
        compose.onNodeWithTag("book_order").performClick()
        compose.onNodeWithTag("book_journey_parts").assertDoesNotExist()
        compose.onNodeWithTag("book_part_vespers").assertDoesNotExist()
        dismissSheet()
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        compose.onNodeWithTag("book_journey_schedule").assertDoesNotExist()
        compose.onNodeWithTag("book_journey_date").assertDoesNotExist()
        assertEquals(journey, store.positionFor(vespers, parishId)?.journeyContext)
        assertTrue(books.requests.isEmpty())
    }

    @Test fun variantSaveFailureStaysOnTheAcceptedBookAndLoadFailureReturnsItsExactPreviousVariant() {
        val accepted = seed(vespers, "a", .35f, BookReadingMode.RUSSIAN)
        val variant = vespers.copy(mainEvent = "200")
        books.failures[variant] = BookLoadResult.Failed(BookFailure.NETWORK, "Вариант вечерни недоступен")
        launch(); openFromHome()
        val before = rawReadingPreferences.all.toMap()
        compose.runOnIdle { readingPreferences.rejectWrites = true }
        chooseSecondVariant()
        waitForTag("book_part_switch_error")
        assertPosition(accepted, saved())
        assertEquals(before, rawReadingPreferences.all)
        assertTrue(books.requests.isEmpty())
        assertTrue(readingPreferences.rejectedCommits > 0)

        compose.runOnIdle { readingPreferences.rejectWrites = false }
        chooseSecondVariant()
        waitForTag("book_load_error")
        assertPosition(accepted, saved())
        assertNull(store.positionFor(variant, parishId))
        compose.onNodeWithTag("book_journey_return").assertIsDisplayed().performClick()
        waitForBook(vespers)
        assertPosition(accepted, saved())
        assertEquals(listOf(variant), books.requests.map { it.key })
        assertEquals(accepted.contentHash, saved().contentHash)
        assertSelectedPart(WorshipKind.VESPERS)
    }

    @Test fun native320DpAtTwoHundredPercentKeepsDatesAndBothPartControlsReadableAndReachable() {
        configureCompactDisplay()
        books.publish(fixture(vespers)); books.publish(fixture(matins))
        launch()
        openFromHome()
        assertIntro(leaveDetailsOpen = true)
        for (tag in listOf("book_journey_schedule", "book_journey_date")) {
            reveal(tag, "book_calendar")
            assertReadable(tag)
        }
        dismissSheet()
        compose.onNodeWithTag("book_order").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("book_order_list").performScrollToNode(hasTestTag("book_part_vespers"))
        compose.onNodeWithTag("book_part_vespers").assertIsSelected().assertHeightIsAtLeast(48.dp)
        assertReadable("book_part_vespers")
        compose.onNodeWithTag("book_order_list").performScrollToNode(hasTestTag("book_part_matins"))
        compose.onNodeWithTag("book_part_matins").assertIsNotSelected().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertReadable("book_part_matins")
        compose.onNodeWithTag("book_part_matins").performClick()
        waitForBook(matins)
        assertIntro()
        assertSelectedPart(WorshipKind.MATINS)
        choosePart(WorshipKind.VESPERS)
        assertEquals(journey, saved().journeyContext)
        assertTrue(books.networkRequests.isEmpty())
    }

    @Test fun genericEveningRequiresChoiceSurvivesRecreationAndOpensTheOtherTextWithoutLosingItsDate() {
        books.publish(fixture(vespers)); books.publish(fixture(matins))
        val restoration = launch("Вечернее богослужение")
        val before = rawReadingPreferences.all.toMap()
        home("today_choose_unknown_service").performClick()
        waitForTag("evening_service_choice")
        assertTrue("Opening the choice must not start a guessed book", books.requests.isEmpty())
        assertEquals(before, rawReadingPreferences.all)
        dismissSheet()
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertEquals(before, rawReadingPreferences.all)

        home("today_choose_unknown_service").performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitForTag("evening_service_choice")
        compose.onNodeWithTag("evening_service_choice").performScrollToNode(hasTestTag("evening_choose_matins"))
        compose.onNodeWithTag("evening_choose_matins").performClick()
        waitForBook(matins)
        assertTrue(saved().journeyContext!!.isEveningTexts)
        assertEquals("2026-11-12", saved().journeyContext?.civilDate)
        assertEquals("2026-11-13", saved().key.date)
        compose.onNodeWithTag("book_open_other_part").assertTextContains("Открыть вечерню").assertIsDisplayed()

        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextReplacement("несуществующая проверочная фраза")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Совпадений в обеих книгах нет", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText("Совпадений в обеих книгах нет", substring = true))
        compose.onNodeWithText("Совпадений в обеих книгах нет", substring = true).assertIsDisplayed()
        dismissSheet()
        compose.onNodeWithTag("book_open_other_part").assertTextContains("Открыть вечерню").performClick()
        waitForBook(vespers)
        val preservedMatins = requireNotNull(store.positionFor(matins, parishId))
        assertTrue(preservedMatins.journeyContext!!.isEveningTexts)
        compose.onNodeWithTag("book_open_other_part").assertTextContains("Открыть утреню").performClick()
        waitForBook(matins)
        assertEquals(preservedMatins.key, saved().key)
        assertEquals(preservedMatins.contentHash, saved().contentHash)
        assertEquals(preservedMatins.journeyContext, saved().journeyContext)
        assertEquals("2026-11-13", saved().key.date)
        assertTrue("Both published fixture books were already saved", books.networkRequests.isEmpty())
    }

    private fun launch(publishedTitle: String = serviceTitle): StateRestorationTester {
        assertTrue(guidePreferences.edit().putBoolean("dark", false).putInt("text_size", 0).commit())
        assertTrue(ChurchDirectoryStore(guidePreferences).save(SavedChurchRecord(parishId, "Храм святителя Николая",
            "Москва", "Тестовая улица, 8", "Europe/Moscow", 55.75, 37.62, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            website = "https://parish.example", sourceUrl = "https://www.openstreetmap.org/way/424243")))
        assertTrue(guidePreferences.edit().putString("church_id", parishId).commit())
        val schedules = object : InternetSchedules {
            override fun cached(parish: Parish) = InternetScheduleState(parish.id, InternetScheduleStatus.READY,
                website = "https://parish.example", sourceUrl = "https://parish.example/schedule", checkedAt = instant.toString(),
                publication = SchedulePublication(parish.id, parish.timezone, "https://parish.example", "https://parish.example/schedule",
                    instant.toString(), listOf(InternetService("2026-11-12", "17:00", publishedTitle, "service"),
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

    private fun assertIntro(leaveDetailsOpen: Boolean = false) {
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        reveal("book_open_calendar", "book_content").performClick()
        reveal("book_journey_schedule", "book_calendar").assertTextContains("12 ноября", substring = true)
            .assertTextContains("17:00", substring = true)
        reveal("book_journey_date", "book_calendar").assertTextContains("13 ноября", substring = true)
        if (!leaveDetailsOpen) dismissSheet()
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

    private fun chooseSecondVariant() {
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Состав книги")))
            .performScrollToNode(hasText("Второй вариант дня"))
        compose.onNodeWithText("Второй вариант дня").performClick()
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

    private fun moveInsideBlock(id: String) {
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_block_long-$id"))
        compose.onNodeWithTag("book_content").performTouchInput { swipeUp(durationMillis = 400) }
        settle()
    }

    private fun assertInside(position: BookReadingPosition, id: String) {
        assertEquals("long-$id", position.blockId)
        assertEquals(BookAnchorKind.BLOCK, position.anchorKind)
        assertTrue("Test precondition: anchor is inside text, not the known inter-block gap", position.fraction in .005f..0.85f)
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
            // Capture before @After removes the isolated fixture state, without touching real user preferences.
            val directory = File(context.getExternalFilesDir("journey-failure"), "$prefix-$tag").apply { mkdirs() }
            runCatching {
                File(directory, "state.txt").writeText(buildString {
                    appendLine("failure=$failure")
                    appendLine("guide=${guidePreferences.all}")
                    appendLine("reading=${rawReadingPreferences.all}")
                    appendLine("requests=${books.requests}")
                    appendLine("networkRequests=${books.networkRequests}")
                    appendLine("delivered=${books.delivered}")
                    listOf(vespers, matins).forEach { key ->
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
            android.util.Log.e("BookJourneyQA", "failure_capture=${directory.absolutePath}", failure)
            throw failure
        }
    }
    private fun waitForBook(key: ServiceBookKey) {
        waitForTag("book_content")
        settle()
        assertEquals(key, saved().key)
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun seed(key: ServiceBookKey, chapter: String, fraction: Float, mode: BookReadingMode,
        speed: Int = 85, altar: Boolean = false, context: BookJourneyContext? = journey): BookReadingPosition {
        val book = fixture(key)
        books.publish(book)
        val position = BookReadingPosition(key, book.contentHash, parishId, "long-$chapter", "chapter-$chapter",
            fraction = fraction, mode = mode, wordsPerMinute = speed, showAltar = altar,
            lastTextMode = mode.takeUnless { it == BookReadingMode.MEANING } ?: BookReadingMode.RUSSIAN,
            journeyContext = context)
        assertTrue(store.save(position))
        return position
    }

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
        android.util.Log.i("BookJourneyQA", "display_profile widthDp=${compose.activity.resources.configuration.screenWidthDp} " +
            "fontScale=${compose.activity.resources.configuration.fontScale} wm=${shell("wm size")}")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }.also {
            check(!it.contains("Error", ignoreCase = true) && !it.contains("denied", ignoreCase = true) &&
                !it.contains("Exception")) { "Display command failed: $command -> $it" }
        }

    private data class Request(val key: ServiceBookKey, val force: Boolean)

    private class ControlledBooks(private val factory: (ServiceBookKey, String?) -> ServiceBook) : ServiceBooks {
        val requests = CopyOnWriteArrayList<Request>()
        val networkRequests = CopyOnWriteArrayList<Request>()
        val delivered = CopyOnWriteArrayList<Request>()
        val failures = ConcurrentHashMap<ServiceBookKey, BookLoadResult.Failed>()
        val unsaved = ConcurrentHashMap.newKeySet<ServiceBookKey>()
        private val latest = ConcurrentHashMap<ServiceBookKey, ServiceBook>()
        private val versions = ConcurrentHashMap<String, ServiceBook>()
        private val held = ConcurrentHashMap.newKeySet<Request>()
        private val callbacks = ConcurrentHashMap<Request, Continuation<BookLoadResult>>()
        fun hold(request: Request) { held += request }
        fun isPending(request: Request) = callbacks.containsKey(request)
        fun publish(book: ServiceBook) { latest[book.key] = book; versions[book.contentHash] = book }
        override fun cached(key: ServiceBookKey): ServiceBook? = latest[key]
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = versions[contentHash]?.takeIf { it.key == key }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            val request = Request(key, force)
            requests += request
            latest[key]?.takeUnless { force }?.let { return BookLoadResult.Ready(it, fromCache = true) }
            networkRequests += request
            failures[key]?.let { return it }
            if (request in held) {
                // Deliberately non-cancellable to model a provider callback arriving after route abandonment.
                val result = suspendCoroutine<BookLoadResult> { callbacks[request] = it }
                delivered += request
                return result
            }
            val book = factory(key, null)
            if (key !in unsaved) publish(book)
            return BookLoadResult.Ready(book, fromCache = false, saved = key !in unsaved)
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
                    return false // The production atomic writer must restore the memory it just changed.
                }
            }
        }
    }

    /** Two chapters and long prose are a test fixture, not a representation of the published book. */
    private fun fixture(key: ServiceBookKey, title: String? = null): ServiceBook {
        val publicChapters = listOf("a", "b").map { id -> BookChapter("chapter-$id", "Раздел $id", "Молимся вместе и благодарим Бога",
            listOf(BookBlock("long-$id", BookBlockKind.PRAYER, "Хор",
                "Господи, помоги нам внимательно слушать молитву и сохранять мир в наших сердцах. ".repeat(120),
                "Просим Бога помочь нам внимательно слушать молитву и хранить мир в сердцах. ".repeat(120)),
                BookBlock("altar-$id", BookBlockKind.PRAYER, "Священник", "Молитва священника о мире. ".repeat(10),
                    "Просим о мире для всех людей. ".repeat(10), isAltarText = true))) }
        // Append only after the existing final block, so both measured chapter starts
        // keep their geometry even when a test explicitly shows altar prayers.
        val altarPadding = List(11) { index -> BookBlock("integrity-altar-padding-$index",
            BookBlockKind.PRAYER, "Священник", "Синтетический алтарный фрагмент $index для проверки целостности книги.",
            "Синтетический русский алтарный фрагмент $index для проверки целостности книги.", isAltarText = true) }
        val chapters = publicChapters.dropLast(1) + publicChapters.last().let { it.copy(blocks = it.blocks + altarPadding) }
        val book = ServiceBook(key = key, title = title ?: key.worship.title, sourceUrl = key.sourceUrl(),
            sourceTitle = "Тестовый источник", fetchedAtMillis = instant.toEpochMilli(), selectedEventTitle = null,
            chapters = chapters, contentHash = "", mainEventOptions = listOf(BookVariantOption("100", "Первый вариант дня"),
                BookVariantOption("200", "Второй вариант дня")),
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also {
            assertTrue("The synthetic journey fixture must satisfy the production book contract", ServiceBookIntegrity.valid(it))
        }
    }
}
