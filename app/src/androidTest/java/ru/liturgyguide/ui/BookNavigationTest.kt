package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import ru.liturgyguide.service.discovery.*
import ru.liturgyguide.service.schedule.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

/** Real app routes and real preference files; only network dependencies are controlled. */
@RunWith(AndroidJUnit4::class)
class BookNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "book-navigation-${UUID.randomUUID()}"
    private val guidePreferences = context.getSharedPreferences("$prefix-guide", Context.MODE_PRIVATE)
    private val bookPreferences = context.getSharedPreferences("$prefix-reading", Context.MODE_PRIVATE)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "guide_preferences" -> guidePreferences
            "service_book_reading" -> bookPreferences
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private val mounted = mutableStateOf(true)
    private val instant = Instant.parse("2026-11-14T13:00:00Z") // Saturday, 16:00 in Moscow.
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val church = DiscoveredChurch("osm:way:424242", "Храм святителя Николая", GeoPoint(55.75, 37.62),
        "улица Тестовая, 7", "Москва", "https://parish.example", false, "https://www.openstreetmap.org/way/424242")
    private val books = ControlledBooks(::fixtureBook)

    @After fun clean() {
        compose.runOnIdle {
            mounted.value = false
            books.releasePendingForCleanup()
        }
        compose.waitForIdle()
        context.deleteSharedPreferences("$prefix-guide")
        context.deleteSharedPreferences("$prefix-reading")
    }

    @Test fun selectingTempleOpensItsEveningBookOnTheNextChurchDateAndResumesIt() {
        launch()
        home("open_church").performClick()
        compose.onNodeWithTag("discovery_query").performTextInput("Храм святителя Николая Москва")
        compose.onNodeWithTag("discovery_query").performImeAction()
        val resultTag = "discovery_church_${church.id}"
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(resultTag))
        compose.onNodeWithTag(resultTag).assertIsDisplayed().performClick()

        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(church.id, guidePreferences.getString("church_id", null))
        home("today_service_date").assertTextEquals("14 ноября")
        home("today_service_time").assertTextEquals("17:00")
        home("today_service_title").assertTextEquals("Всенощное бдение")
        home("open_service_book").performClick()
        waitForBook()
        settle()
        val expected = ServiceBookKey("2026-11-15", WorshipKind.VIGIL, churchType = "34")
        assertEquals(listOf(Request(expected, false)), books.requests.toList())
        assertEquals(expected, saved().key)
        assertEquals(church.id, saved().parishId)
        compose.onNodeWithTag("book_saved").assertTextContains("15 ноября 2026", substring = true)

        compose.onNodeWithTag("book_back").performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        home("resume_book").performClick()
        waitForBook()
        settle()
        assertEquals(expected, saved().key)
        assertEquals("Resuming a saved evening book must not shift its date a second time", 1, books.requests.size)
        compose.onNodeWithTag("book_saved").assertTextContains("15 ноября 2026", substring = true)
    }

    @Test fun choosingTomorrowWhileTodayIsPendingCannotAcceptTodaysLateResponse() {
        selectSavedChurch()
        val today = ServiceBookKey("2026-11-14", churchType = "34")
        val tomorrow = today.copy(date = "2026-11-15")
        val pending = Request(today, false)
        books.hold(pending)
        launch()
        openLiturgyFromHome()
        waitForRequest(pending)
        compose.onNodeWithTag("book_loading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        home("today_day_tomorrow").performClick()
        openLiturgyFromHome()
        waitForBook()
        settle()
        val accepted = saved()
        assertEquals(tomorrow, accepted.key)

        compose.runOnIdle { books.release(pending, fixtureBook(today, "Запоздавшая книга за сегодня")) }
        compose.waitUntil(5_000) { pending in books.delivered }
        settle()
        assertEquals(accepted.key, saved().key)
        assertEquals(accepted.contentHash, saved().contentHash)
        compose.onNodeWithTag("book_saved").assertTextContains("15 ноября 2026", substring = true)
        compose.onNodeWithText("Запоздавшая книга за сегодня").assertDoesNotExist()
        assertNull("A response to an abandoned route must not create a reading", BookReadingStore(bookPreferences).restore(requireNotNull(books.cached(today)), church.id))
    }

    @Test fun choosingAnotherVariantWhileRefreshingCannotApplyTheOldVariantsReply() {
        selectSavedChurch()
        val original = ServiceBookKey("2026-11-14", churchType = "34")
        val selected = original.copy(mainEvent = "200")
        val refresh = Request(original, true)
        books.hold(refresh)
        launch()
        openLiturgyFromHome()
        waitForBook()
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
        waitForRequest(refresh)
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Состав книги")))
            .performScrollToNode(hasText("Память второго святого"))
        compose.onNodeWithText("Память второго святого").performClick()
        waitForRequest(Request(selected, false))
        waitForBook()
        settle()
        assertEquals(selected, saved().key)

        compose.runOnIdle { books.release(refresh, fixtureBook(original, "Запоздавшее обновление прежнего варианта")) }
        compose.waitUntil(5_000) { refresh in books.delivered }
        settle()
        assertEquals(selected, saved().key)
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        compose.onNodeWithText("Запоздавшее обновление прежнего варианта").assertDoesNotExist()
        compose.onNodeWithTag("book_back").performClick()
        home("resume_book").performClick()
        waitForBook()
        settle()
        assertEquals("Resume must retain the explicit event variant", selected, saved().key)
    }

    @Test fun newerCachedEditionWaitsForAcceptanceAndRestoresTheSameUnchangedFragment() {
        selectSavedChurch()
        val key = ServiceBookKey("2026-11-14", churchType = "34")
        val old = fixtureBook(key, "Сохраненная редакция")
        // The current original and Russian paragraph are identical in both editions.
        // Only the book-level metadata changes, so losing the paragraph offset is unjustified.
        val newer = old.copy(title = "Обновленная редакция", contentHash = "").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        books.publish(old)
        books.publish(newer)
        assertTrue(BookReadingStore(bookPreferences).save(BookReadingPosition(key, old.contentHash, church.id,
            "reading-long", "ending", fraction = .37f, mode = BookReadingMode.RUSSIAN)))
        val restoration = launch()
        home("resume_book").performClick()
        waitForBook()
        settle()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed()
        val before = saved()
        assertEquals("New cache content must not replace the accepted book by itself", old.contentHash, before.contentHash)
        assertEquals("reading-long", before.blockId)
        assertTrue("Start well inside the paragraph", before.fraction > .3f)
        assertTrue("Both editions were cached; no fetch is needed", books.requests.isEmpty())

        compose.onNodeWithTag("book_accept_update").performClick()
        compose.runOnIdle {
            assertEquals("Accept must persist the new version immediately; old onDispose must not overwrite it",
                newer.contentHash, saved().contentHash)
        }
        settle()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        val accepted = saved()
        assertEquals(newer.contentHash, accepted.contentHash)
        assertEquals(before.key, accepted.key)
        assertEquals(before.blockId, accepted.blockId)
        assertEquals(before.chapterId, accepted.chapterId)
        assertEquals(before.mode, accepted.mode)
        assertEquals(before.fraction, accepted.fraction, .001f)
        compose.onNodeWithTag("book_back").performClick()
        home("resume_book").performClick()
        waitForBook()
        settle()
        assertEquals("Reentry must pin the explicitly accepted edition", newer.contentHash, saved().contentHash)
        assertEquals(accepted.blockId, saved().blockId)
        assertEquals(accepted.mode, saved().mode)
        assertEquals(accepted.fraction, saved().fraction, .001f)
        val beforeRestore = compose.onNodeWithTag("book_block_reading-long").getUnclippedBoundsInRoot().top

        restoration.emulateSavedInstanceStateRestore()
        waitForBook()
        settle()
        val restored = saved()
        assertEquals(accepted.contentHash, restored.contentHash)
        assertEquals(accepted.blockId, restored.blockId)
        assertEquals(accepted.mode, restored.mode)
        assertEquals(accepted.fraction, restored.fraction, .001f)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        val afterRestore = compose.onNodeWithTag("book_block_reading-long").assertIsDisplayed().getUnclippedBoundsInRoot().top
        assertTrue("Saved instance restoration must retain the visible line", abs((afterRestore - beforeRestore).value) <= 2f)
        assertTrue(books.requests.isEmpty())
    }

    private fun launch(): StateRestorationTester {
        assertTrue(guidePreferences.edit().putBoolean("dark", false).commit())
        val discovery = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> =
                DiscoveryResult.Success(listOf(church), instant.toEpochMilli(), DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("This journey uses name search")
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("This journey does not request location")
        }
        val schedules = object : InternetSchedules {
            override fun cached(parish: Parish) = InternetScheduleState(parish.id, InternetScheduleStatus.READY,
                website = "https://parish.example", sourceUrl = "https://parish.example/schedule", checkedAt = instant.toString(),
                publication = SchedulePublication(parish.id, parish.timezone, "https://parish.example", "https://parish.example/schedule",
                    instant.toString(), listOf(InternetService("2026-11-14", "17:00", "Всенощное бдение", "service"),
                        InternetService("2026-11-15", "09:00", "Литургия", "liturgy"))))
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?) = cached(parish)
        }
        val container = AppContainer(context, discovery, scheduleOverride = schedules, booksOverride = books)
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides localContext,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    if (mounted.value) AppNavigation(container, clock, monotonicMillis = { 1_000L })
                }
            }
        }
    }

    private fun selectSavedChurch() {
        assertTrue(ChurchDirectoryStore(guidePreferences).save(SavedChurchRecord(church.id, church.name, church.city, church.address,
            "Europe/Moscow", church.center.latitude, church.center.longitude, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            website = church.website.orEmpty(), sourceUrl = church.sourceUrl)))
        assertTrue(guidePreferences.edit().putString("church_id", church.id).commit())
    }

    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun openLiturgyFromHome() {
        home("today_other_services").performClick()
        compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag("service_book_choose_liturgy"))
        compose.onNodeWithTag("service_book_choose_liturgy").performClick()
    }

    private fun waitForRequest(request: Request) = compose.waitUntil(5_000) { request in books.requests }
    private fun waitForBook() = compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
    private fun saved(): BookReadingPosition = requireNotNull(BookReadingStore(bookPreferences).last(church.id))
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }

    private data class Request(val key: ServiceBookKey, val force: Boolean)

    /** An ordinary continuation deliberately delivers even after cancellation, like a late callback. */
    private class ControlledBooks(private val factory: (ServiceBookKey, String?) -> ServiceBook) : ServiceBooks {
        val requests = CopyOnWriteArrayList<Request>()
        val delivered = CopyOnWriteArrayList<Request>()
        private val latest = ConcurrentHashMap<ServiceBookKey, ServiceBook>()
        private val versions = ConcurrentHashMap<String, ServiceBook>()
        private val held = ConcurrentHashMap<Request, Boolean>()
        private val callbacks = ConcurrentHashMap<Request, Continuation<BookLoadResult>>()
        fun hold(request: Request) { held[request] = true }
        fun publish(book: ServiceBook) { latest[book.key] = book; versions[book.contentHash] = book }
        override fun cached(key: ServiceBookKey): ServiceBook? = latest[key]
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = versions[contentHash]?.takeIf { it.key == key }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            val request = Request(key, force)
            requests += request
            if (held.containsKey(request)) {
                val result = suspendCoroutine<BookLoadResult> { callbacks[request] = it }
                delivered += request
                return result
            }
            val book = latest[key] ?: factory(key, null).also(::publish)
            return BookLoadResult.Ready(book, fromCache = false)
        }
        fun release(request: Request, book: ServiceBook) {
            publish(book)
            requireNotNull(callbacks.remove(request)) { "No pending request $request" }.resume(BookLoadResult.Ready(book, fromCache = false))
        }
        fun releasePendingForCleanup() {
            callbacks.keys.toList().forEach { request ->
                callbacks.remove(request)?.resume(BookLoadResult.Failed(BookFailure.NETWORK, "Test finished"))
            }
        }
    }

    private fun fixtureBook(key: ServiceBookKey, title: String? = null): ServiceBook {
        fun block(id: String, text: String) = BookBlock(id, BookBlockKind.PRAYER, null, text, "Русский текст: $text")
        val chapters = listOf(
            BookChapter("opening", "Начало", "Собираемся для общей молитвы", (1..8).map { block("opening-$it", "Господи, помоги нам внимательно слушать и понимать молитву. ".repeat(4)) }),
            BookChapter("ending", "Благодарение", "Благодарим Бога за помощь", (1..7).map { block("ending-$it", "Даруй нам мир и помощь в добрых делах. ".repeat(4)) } +
                block("reading-long", "Благодарим Тебя и просим сохранить мир и любовь в наших сердцах. ".repeat(85)))
        )
        val book = ServiceBook(key = key, title = title ?: "${key.worship.title} · ${key.mainEvent ?: "основной вариант"}",
            sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник", fetchedAtMillis = instant.toEpochMilli(),
            selectedEventTitle = null, chapters = chapters, contentHash = "",
            mainEventOptions = listOf(BookVariantOption("100", "Память первого святого"), BookVariantOption("200", "Память второго святого")))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
