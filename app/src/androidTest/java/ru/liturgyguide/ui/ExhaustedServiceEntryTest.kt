package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.*
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.OfflineInternetSchedules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A dated full book replaces speculative NEW_SERVICE entry; previously accepted readings remain recoverable. */
@RunWith(AndroidJUnit4::class)
class ExhaustedServiceEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "exhausted-book-entry-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences("$prefix-guide", Context.MODE_PRIVATE)
    private val bookPreferences = context.getSharedPreferences("$prefix-books", Context.MODE_PRIVATE)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "guide_preferences" -> preferences
            "service_book_reading" -> bookPreferences
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private val mounted = mutableStateOf(true)
    private val books = NavigationMigrationBooks()
    private val container = AppContainer(context, scheduleOverride = OfflineInternetSchedules, booksOverride = books)
    private val parish get() = container.parishes.getOrThrow().selected(null)
    private val afterMidnight = Instant.parse("2026-11-08T21:50:00Z")
    private val expected = ReadingContext("elisavetinsky_opalikha", "2026-11-08", "Europe/Moscow",
        ReadingContextSource.USER, Instant.parse("2026-11-08T20:00:00Z").toEpochMilli())

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences("$prefix-guide")
        context.deleteSharedPreferences("$prefix-books")
    }

    @Test fun aFailedBookForAnotherDateKeepsTheAcceptedReadingAndCanBeRetried() {
        seed()
        val old = savedBook()
        val oldBook = requireNotNull(books.cached(old.key))
        val before = preferences.all.toMap()
        books.fail = true
        launch(AdjustableClock(afterMidnight))
        home("today_service_date").assertTextEquals("9 ноября")
        home("open_service_book").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_load_error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(ServiceBookKey("2026-11-09")), books.requests.toList())
        assertEquals(old, savedBook())
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        // A failed load must not overwrite either the accepted full book or the separate legacy cursor.
        compose.runOnIdle { books.fail = false }
        compose.onNodeWithText("Попробовать еще раз").performScrollTo().performClick()
        awaitBook()
        assertEquals(listOf(ServiceBookKey("2026-11-09"), ServiceBookKey("2026-11-09")), books.requests.toList())
        assertEquals(ServiceBookKey("2026-11-09"), savedBook().key)
        assertEquals(BookAnchorKind.HEADER, savedBook().anchorKind)
        assertEquals(old, BookReadingStore(bookPreferences).restore(oldBook, parish.id))
        assertEquals(before, preferences.all)
    }

    @Test fun searchAfterMidnightAndRestorationUsesTheExplicitlyOpenedDaysBook() {
        seed()
        val key = ServiceBookKey("2026-11-08")
        val dated = datedSearchFixture(key)
        books.publish(dated)
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T20:50:00Z"))
        val restoration = launch(clock)
        home("today_open_personal_liturgy").performClick()
        awaitBook()
        assertEquals(key, savedBook().key)
        compose.runOnIdle { clock.current = afterMidnight }
        compose.mainClock.advanceTimeBy(6_000)
        restoration.emulateSavedInstanceStateRestore()
        awaitBook()
        compose.onNodeWithTag("book_saved").assertTextContains("8 ноября 2026", substring = true)
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput("Лазарь")
        closeSoftKeyboardWithCiDiagnostics()
        compose.onNodeWithTag("book_hit_dated-reading").performScrollTo().assertIsDisplayed().performClick()
        settle()
        compose.onNodeWithTag("book_block_dated-reading").assertIsDisplayed()
        assertEquals(key, savedBook().key)
        assertEquals("dated-reading", savedBook().blockId)
        assertEquals("scripture", savedBook().chapterId)
        assertEquals(BookAnchorKind.BLOCK, savedBook().anchorKind)
        assertTrue(books.requests.all { it == key })
        assertEquals(before, preferences.all)
    }

    @Test fun midnightWhileChoosingATextDoesNotChangeItsDateAndCancelKeepsTheOldReading() {
        seed()
        val old = savedBook()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T04:35:00Z"))
        val restoration = launch(clock)
        home("today_other_services").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 8 ноября 2026")
        compose.runOnIdle { clock.current = afterMidnight }
        compose.mainClock.advanceTimeBy(6_000)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 8 ноября 2026")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> assertTrue(dismiss()) }
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(old, savedBook())
        assertEquals(before, preferences.all)
        assertTrue("Opening and cancelling a selector does not load or accept any book", books.requests.isEmpty())
        home("resume_book").performClick()
        awaitBook()
        assertEquals(old.key, savedBook().key)
        assertEquals(old.blockId, savedBook().blockId)
        assertEquals(old.mode, savedBook().mode)
        compose.onNodeWithTag("book_content").performScrollToIndex(0)
        compose.onNodeWithTag("book_saved").assertTextContains("7 ноября 2026", substring = true)
        assertEquals(before, preferences.all)
    }

    @Test fun aLateTapOpensTheDisplayedOccurrenceWithoutInventingANewDateOrReadingPosition() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T04:35:00Z"))
        launch(clock)
        home("today_service_date").assertTextEquals("8 ноября")
        home("today_service_time").assertTextEquals("07:00")
        val openDisplayed = home("open_service_book").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Invoke the actual displayed card before a periodic tick can replace that card.
        compose.runOnIdle { clock.current = afterMidnight; assertTrue(openDisplayed()) }
        awaitBook()
        assertEquals(listOf(ServiceBookKey("2026-11-08")), books.requests.toList())
        assertEquals(ServiceBookKey("2026-11-08"), savedBook().key)
        assertEquals(BookAnchorKind.HEADER, savedBook().anchorKind)
        assertEquals(0f, savedBook().fraction, 0f)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertEquals(before, preferences.all)
    }

    @Test fun switchingChurchesKeepsIndependentBookBookmarksAndCanReturnToTheFirstOne() {
        seed()
        // This scenario owns full-book cursors only. Legacy confirmation is tested separately.
        check(preferences.edit().clear().putString("church_id", parish.id).putBoolean("dark", false).commit())
        val other = SavedChurchRecord.create("Храм Троицы", "Москва", "Тестовая улица, 2", "Europe/Moscow")
        check(ChurchDirectoryStore(preferences).save(other))
        val first = savedBook()
        val second = first.copy(parishId = other.id, blockId = "prayer-11", mode = BookReadingMode.ORIGINAL)
        assertTrue(BookReadingStore(bookPreferences).save(second))
        launch(AdjustableClock(afterMidnight))
        home("change_church").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_saved_${other.id}"))
        compose.onNodeWithTag("discovery_saved_${other.id}").performClick()
        assertEquals(other.id, preferences.getString("church_id", null))
        home("resume_book").performClick()
        awaitBook()
        assertEquals(second.key, savedBook(other.id).key)
        assertEquals(second.blockId, savedBook(other.id).blockId)
        assertEquals(second.mode, savedBook(other.id).mode)
        assertEquals(first, savedBook(parish.id))
        compose.onNodeWithTag("book_back").performClick()
        val secondAfterReading = savedBook(other.id)
        home("change_church").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_saved_${parish.id}"))
        compose.onNodeWithTag("discovery_saved_${parish.id}").performClick()
        home("resume_book").performClick()
        awaitBook()
        assertEquals(parish.id, preferences.getString("church_id", null))
        assertEquals(first.key, savedBook().key)
        assertEquals(first.blockId, savedBook().blockId)
        assertEquals(first.mode, savedBook().mode)
        assertEquals(secondAfterReading, savedBook(other.id))
        assertTrue("Both accepted books are already cached", books.requests.isEmpty())
    }

    @Test fun lostOrCorruptBookRequestsReturnHomeWithoutInventingADateOrReplacingTheSavedReading() {
        val registry = mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        mounted.value = false
        val clock = AdjustableClock(afterMidnight)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localContext,
                LocalActivityResultRegistryOwner provides compose.activity,
                LocalSaveableStateRegistry provides registry.value) {
                if (mounted.value) AppNavigation(container, clock, monotonicMillis = { 1_000L })
            }
        }
        val key = ServiceBookKey("2026-11-09")
        val invalidRequests = listOf(null, "broken", Gson().toJson(key.copy(date = "2026-02-30")),
            Gson().toJson(key.copy(date = "1800-11-09")))
        invalidRequests.forEach { invalid ->
            compose.runOnIdle {
                seed()
                registry.value = SaveableStateRegistry(restoredValues = null) { true }
                mounted.value = true
            }
            home("open_service_book").performClick()
            awaitBook()
            var savedState: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { savedState = registry.value.performSave(); mounted.value = false }
            compose.waitForIdle()
            val beforeLegacy = preferences.all.toMap()
            val beforeBooks = bookPreferences.all.toMap()
            val requested = books.requests.toList()
            val corrupted = corruptBookRequest(savedState, Gson().toJson(key), invalid)
            compose.runOnIdle {
                registry.value = SaveableStateRegistry(restoredValues = corrupted) { true }
                mounted.value = true
            }
            compose.onNodeWithTag("main_content").assertIsDisplayed()
            compose.onNodeWithTag("book_content").assertDoesNotExist()
            compose.onNodeWithTag("book_loading").assertDoesNotExist()
            assertEquals(beforeLegacy, preferences.all)
            assertEquals(beforeBooks, bookPreferences.all)
            assertEquals("Invalid route restoration must not request today's book as a substitute", requested, books.requests.toList())
            home("resume_book").performClick()
            awaitBook()
            assertEquals(key, savedBook().key)
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }

    @Test fun significantPublishedSecondsAreShownOnBothManualScreens() {
        val draft = ArrivalDraft(parish.id, parish.shortName, ArrivalMode.NEW_SERVICE, ArrivalSource.MANUAL,
            stageId = "creed", pendingServiceContext = expected.copy(source = ReadingContextSource.PUBLISHED,
                startEpochMillis = Instant.parse("2026-11-08T20:00:15.500Z").toEpochMilli()))
        val showingArrival = mutableStateOf(false)
        compose.setContent { GuideTheme(false) {
            if (showingArrival.value) ArrivalScreen(draft, container.getStageMatcher().getStageById("creed"),
                null, true, {}, {}, {})
            else ManualMomentScreen(draft, {}, {}, {})
        } }
        assertPending("23:00:15.5")
        compose.runOnIdle { showingArrival.value = true }
        assertPending("23:00:15.5")
    }

    private fun seed() {
        check(preferences.edit().clear().putString("church_id", parish.id).putBoolean("dark", false)
            .putBoolean("lyric_original", true).putInt("text_size", 0).commit())
        check(bookPreferences.edit().clear().commit())
        check(UserServiceStartStore(preferences).save(UserServiceStart(parish.id, "2026-11-08", "23:00"), parish))
        val previous = ReadingContext(parish.id, "2026-11-07", parish.timezone, ReadingContextSource.MANUAL)
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(previous.serviceDate),
            container.readings?.days?.firstOrNull { it.date == previous.serviceDate })
        val engine = ServicePlaybackEngine(plan)
        val state = engine.start("creed", PlaybackSpeed.SLOW).copy(elapsedQuarterMillis = 1_234L)
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, previous, engine.snapshot(state, 0L),
            ServiceSession("creed", Instant.parse("2026-11-07T07:00:00Z").toEpochMilli()), true,
            PlaybackBookmark.at(plan, state)))
        check(preferences.edit().putString("lyric_phrase_old_track", "old_phrase").commit())
        val key = ServiceBookKey("2026-11-07")
        val value = ServiceBook(key = key, title = "Сохраненная книга", sourceUrl = key.sourceUrl(),
            sourceTitle = "Тестовый источник", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("opening", "Начало", "Пояснение для проверки",
                (1..15).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, null,
                    "Благословено Царство. ".repeat(40), "Благословенно Царство. ".repeat(40)) })), contentHash = "")
        val book = value.copy(contentHash = ServiceBookIntegrity.contentHash(value))
        books.publish(book)
        assertTrue(BookReadingStore(bookPreferences).save(BookReadingPosition(key, book.contentHash, parish.id,
            "prayer-7", "opening", fraction = .25f, mode = BookReadingMode.RUSSIAN)))
    }

    private fun launch(clock: Clock) = StateRestorationTester(compose).also { restoration -> restoration.setContent {
        CompositionLocalProvider(LocalContext provides localContext, LocalActivityResultRegistryOwner provides compose.activity) {
            if (mounted.value) AppNavigation(container, clock, monotonicMillis = { 1_000L })
        }
    } }

    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun savedBook(id: String = parish.id): BookReadingPosition = requireNotNull(BookReadingStore(bookPreferences).last(id))
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun assertPending(time: String) = compose.onNodeWithTag("pending_service_context")
        .assertTextEquals("Литургия 8 ноября 2026 · начало в $time")

    private fun datedSearchFixture(key: ServiceBookKey): ServiceBook {
        // A unique fixture sentence verifies which book owns the search; it is not shipped liturgical content.
        val book = ServiceBook(key = key, title = "Текст выбранного дня", sourceUrl = key.sourceUrl(),
            sourceTitle = "Источник теста", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("scripture", "Чтение дня", "Пояснение для проверки",
                listOf(BookBlock("dated-reading", BookBlockKind.SCRIPTURE, null,
                    "Лазарь. Чтение выбранного дня. ".repeat(80))) +
                    (1..14).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, null,
                        "Благословено Царство. ".repeat(40)) })), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also {
            assertTrue("The navigation fixture must be acceptable to the real disk cache", ServiceBookIntegrity.valid(it))
        }
    }

    /** Replace only the actual BOOK key; retain the BOOK route and all unrelated saved state. */
    private fun corruptBookRequest(saved: Map<String, List<Any?>>, json: String, replacement: String?): Map<String, List<Any?>> {
        var replaced = 0
        fun visit(value: Any?): Any? = when (value) {
            is MutableState<*> -> {
                val original = value.value
                val transformed = visit(original)
                if (transformed == original) value else mutableStateOf(transformed)
            }
            is String -> if (value == json) { replaced++; replacement } else value
            is List<*> -> value.map(::visit)
            is Map<*, *> -> value.mapValues { (_, nested) -> visit(nested) }
            else -> value
        }
        val result = saved.mapValues { (_, values) -> values.map(::visit) }
        assertEquals("Exactly one actual BOOK request must be corrupted", 1, replaced)
        return result
    }

    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
