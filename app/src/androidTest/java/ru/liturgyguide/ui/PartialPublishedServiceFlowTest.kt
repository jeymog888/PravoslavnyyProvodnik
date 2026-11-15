package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recorded title/text fixtures in real native windows. No live venue, ASR or process-death claim. */
@RunWith(Parameterized::class)
class PartialPublishedServiceFlowTest(private val dark: Boolean, private val kind: WorshipKind) {
    private val evening get() = kind == WorshipKind.VESPERS
    private val vigil get() = kind == WorshipKind.VIGIL
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "partial-published-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, name)
    private val store = BookReadingStore(prefs)
    private val calls = CopyOnWriteArrayList<String>()
    private val mounted = mutableStateOf(true)
    private val page = mutableStateOf("today")
    private val encodedRoute = mutableStateOf<String?>(null)
    private val invalid = mutableStateOf<BookRoute?>(null)
    private val title get() = when (kind) {
        WorshipKind.VESPERS -> "Вечерня с акафистом великомученику и целителю Пантелеимону"
        WorshipKind.VIGIL -> "Всенощное бдение. Исповедь"
        else -> "Литургия. Панихида"
    }
    private val service get() = ParishService(when (kind) {
        WorshipKind.VESPERS -> "2026-11-12"
        WorshipKind.VIGIL -> "2026-11-11"
        else -> "2026-11-14"
    }, "17:00", title, "service_block")
    private val parish get() = Parish(id = "partial-fixture", name = if (evening) "Храм святителя Николая" else "Храм Новомучеников",
        shortName = "Храм", city = "Москва", address = "", phone = "", website = "https://example.org", timezone = "Europe/Moscow",
        sourceUrl = "https://example.org/schedule", scheduleSource = "Контролируемый пример для проверки интерфейса",
        scheduleValidFrom = service.date, scheduleValidUntil = service.date, schedule = listOf(service),
        scheduleCoverage = "Один день", scheduleNote = "")
    private val route get() = requireNotNull(ServiceBookSelection.resolve(service, parish))
    private val owner = mutableStateOf<ServiceBooks>(repository())
    private var contentSet = false
    private var captureIndex = 0
    private var cacheBefore = emptyMap<String, String>()
    private val restoration by lazy { StateRestorationTester(compose) }

    @After fun clean() {
        try {
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        } finally { context.deleteSharedPreferences(name); directory.deleteRecursively() }
    }

    @Test fun exactAvailableTextOpensFromBothEntriesKeepsScopeOnRestoreAndHasNoFictitiousNeighbour() = compactWindow {
        val book = seedBook()
        val chosen = route
        val target = book.chapters.first { c -> c.blocks.any { !it.isAltarText && it.kind == BookBlockKind.PRAYER } }
        val block = target.blocks.first { !it.isAltarText && it.kind == BookBlockKind.PRAYER }
        val position = BookReadingPosition(book.key, book.contentHash, parish.id, block.id, target.id,
            fraction = .23f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN,
            journeyContext = chosen.journeyContext)
        assertTrue(store.save(position)); cacheBefore = snapshot()
        launch()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_service_book"))
        compose.onNodeWithTag("open_service_book").assertTextEquals(ServiceBookSelection.readingAction(title)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("today_service_scope"))
        compose.onNodeWithTag("today_service_scope").assertTextEquals(ServiceBookSelection.readingScope(title)!!)
        capture("today")
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_service_book"))
        compose.onNodeWithTag("open_service_book").performClick(); waitReader()
        assertEquals(chosen, BookRouteCodec.decode(encodedRoute.value))
        val afterToday = requireNotNull(store.positionFor(book.key, parish.id))
        assertEquals(position.key, afterToday.key); assertEquals(position.contentHash, afterToday.contentHash)
        assertEquals(position.blockId, afterToday.blockId); assertEquals(position.mode, afterToday.mode)
        assertEquals(position.fraction, afterToday.fraction, .015f)
        assertEquals(chosen.journeyContext, afterToday.journeyContext)
        capture("today-opened-reader")
        compose.onNodeWithTag("book_back").performClick(); settle()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.runOnIdle { page.value = "church" }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_open_service"))
        compose.onNodeWithTag("church_open_service").assertTextEquals(ServiceBookSelection.readingAction(title)).performClick()
        waitReader()
        val accepted = requireNotNull(store.positionFor(book.key, parish.id))
        assertEquals(position.blockId, accepted.blockId); assertEquals(position.mode, accepted.mode)
        assertEquals(position.fraction, accepted.fraction, .015f)
        assertEquals(chosen.journeyContext, accepted.journeyContext)
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextReplacement("несуществующая проверочная фраза")
        compose.awaitBookSearch()
        val emptyText = "Совпадений в открытой книге нет"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText(emptyText, substring = true))
        compose.onNodeWithText(emptyText, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_scope"))
        compose.onNodeWithTag("book_search_scope").assertTextEquals("Текст: ${book.key.worship.title}")
        compose.onNodeWithTag("book_search_other_part").assertDoesNotExist()
        compose.onNodeWithTag("book_search_corpus_error").assertDoesNotExist()
        assertTrue(calls.isEmpty()); assertEquals(accepted, store.last(parish.id)); capture("search")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
        compose.onNodeWithTag("book_order").performClick()
        compose.onNodeWithTag("book_journey_parts").assertDoesNotExist()
        compose.onAllNodesWithText("1 из 1", substring = true).assertCountEquals(0)
        capture("order"); pressBack(); settle()
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_end"))
        compose.onNodeWithTag("book_end").assertIsDisplayed()
        compose.onNodeWithTag("book_next_part").assertDoesNotExist()
        assertCalendar(book)
        pressBack(); settle()
        val before = requireNotNull(store.last(parish.id))
        restoration.emulateSavedInstanceStateRestore(); waitReader()
        assertEquals(before, store.last(parish.id)); assertCalendar(book)
        pressBack(); settle()
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        val persisted = requireNotNull(BookReadingStore(context.getSharedPreferences(name, Context.MODE_PRIVATE)).last(parish.id))
        val reopened = runBlocking { repository().load(book.key) }
        assertTrue(reopened is BookLoadResult.Ready); assertEquals(book, (reopened as BookLoadResult.Ready).book)
        compose.runOnIdle {
            encodedRoute.value = BookRouteCodec.encode(BookRoute(persisted.key, persisted.parishId, persisted.journeyContext))
            owner.value = repository(); mounted.value = true
        }
        waitReader()
        assertEquals(persisted, store.last(parish.id)); assertCalendar(book)
        assertTrue(calls.isEmpty()); assertEquals(cacheBefore, snapshot())
    }

    @Test fun malformedManagedScopeStopsBeforeStandaloneOrPointerWriteAndFallbackCannotDropScope() = compactWindow {
        seedBook()
        val selected = route
        val brokenRoutes = listOf(
            selected.copy(journeyContext = selected.journeyContext!!.copy(schemaVersion = 1, partSet = null)),
            selected.copy(journeyContext = selected.journeyContext!!.copy(civilDate = "2026-11-20")),
            selected.copy(parishId = "foreign-owner"),
            selected.copy(activeKey = selected.activeKey.copy(date = "2026-11-20")))
        invalid.value = brokenRoutes.first(); page.value = "book"
        val before = prefs.all.toMap(); launch()
        for ((index, broken) in brokenRoutes.withIndex()) {
            compose.runOnIdle { invalid.value = broken }; settle()
            compose.onNodeWithTag("book_published_scope_unreadable").assertIsDisplayed()
            compose.onNodeWithTag("book_content").assertDoesNotExist()
            assertEquals(before, prefs.all); assertTrue(calls.isEmpty()); capture("invalid-scope-$index")
        }
        compose.runOnIdle { invalid.value = null; page.value = "fallback" }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_service_book"))
        compose.onNodeWithTag("open_service_book").assertTextEquals("Выбрать доступные тексты").performClick()
        // Date/kind-only entry opens the explicit library chooser, never a silently unscoped book.
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("service_book_chooser").assertIsDisplayed()
        assertNull(encodedRoute.value); assertEquals(before, prefs.all); assertTrue(calls.isEmpty())
        capture("fallback-chooser")
    }

    private fun launch() {
        restoration.setContent { GuideTheme(dark) { if (mounted.value) {
            val active = encodedRoute.value?.let(BookRouteCodec::decode)
            when (page.value) {
                "book" -> {
                    val input = invalid.value ?: requireNotNull(active)
                    ServiceBookScreen(input.activeKey, parish.id, owner.value, store, 0, onBack = { page.value = "today" },
                        onSettings = {}, onOpenUrl = {}, parish = parish, route = input,
                        onRouteChanged = { next -> encodedRoute.value = BookRouteCodec.encode(next); true },
                        onOpenCommonLiturgy = {})
                }
                "church" -> ChurchScreen(listOf(parish), parish.id, LocalDate.parse(service.date),
                    onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {},
                    searchQuery = "", onSearchQueryChange = {}, now = now(), onOpenService = ::open)
                else -> TodayScreen(parish, now(), null, null, null, onFindChurch = {}, onChurchDetails = {},
                    onOpenBook = { _, _, _ -> error("Mandatory partial scope cannot enter the date/kind fallback") },
                    onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {},
                    onOpenService = if (page.value == "fallback") null else ::open)
            }
        } } }
        contentSet = true; settle()
    }
    private fun open(service: ParishService) {
        val selected = requireNotNull(ServiceBookSelection.resolve(service, parish))
        encodedRoute.value = BookRouteCodec.encode(selected); page.value = "book"
    }
    private fun now() = Instant.parse(service.date + "T13:55:00Z")
    private fun seedBook(): ServiceBook {
        val key = route.activeKey
        val book = if (vigil) seedVigil(key) else {
            val path = if (evening) "polyeleos-journey/vespers-2026-11-13-church34.html" else "morning-hours/liturgy-2026-11-14-church30.html"
            val html = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }
            ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1)
        }
        assertTrue(ServiceBookIntegrity.valid(book)); assertTrue(FileBookCache(directory).write(book))
        return book
    }
    private fun seedVigil(key: ServiceBookKey): ServiceBook {
        val cache = FileBookCache(directory)
        val kinds = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)
        val expected = kinds.associateBy { key.copy(worship = it).sourceUrl() }
        val sourceCalls = mutableListOf<WorshipKind>()
        val seedRepository = ServiceBookRepository(cache, ScheduleTransport { url ->
            val part = expected[url]
            if (part != null) sourceCalls += part
            val name = when (part) {
                WorshipKind.VESPERS -> "vespers"
                WorshipKind.MATINS -> "matins"
                WorshipKind.HOUR1 -> "hour1"
                else -> null
            }
            val html = name?.let { instrumentation.context.assets.open("partial-vigil/$it-2026-11-12.html")
                .bufferedReader().use { reader -> reader.readText() } } ?: "<html></html>"
            ScheduleResponse(url, html.toByteArray(Charsets.UTF_8), "text/html; charset=UTF-8")
        }, downloadRussian = false)
        val ready = runBlocking { seedRepository.load(key) } as BookLoadResult.Ready
        assertTrue(ready.saved); assertEquals(kinds, sourceCalls)
        assertEquals(kinds.map { requireNotNull(cache.read(key.copy(worship = it))) }.flatMap { it.blocks }, ready.book.blocks)
        assertEquals(ready.book, cache.readVersion(key, ready.book.contentHash))
        assertTrue(ready.book.notices.any { "Переходы и сокращения" in it })
        return ready.book
    }
    private fun repository() = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
        calls += url; error("This accepted one-part book must reopen from disk")
    }, downloadRussian = false)
    private fun assertCalendar(book: ServiceBook) {
        compose.onNodeWithTag("book_reader_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("book_options_offline").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_calendar").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag("book_calendar_schedule_title"))
        compose.onNodeWithTag("book_calendar_schedule_title").assertTextEquals(title)
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag("book_partial_scope"))
        compose.onNodeWithTag("book_partial_scope").assertTextEquals(ServiceBookSelection.excludedScope(title)!!)
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag("book_partial_available"))
        compose.onNodeWithTag("book_partial_available").assertTextEquals("Доступный текст: " + when (kind) {
            WorshipKind.VESPERS -> "вечерня"
            WorshipKind.VIGIL -> "всенощное бдение"
            else -> "литургия"
        })
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag("book_journey_date"))
        compose.onNodeWithTag("book_journey_date").assertTextEquals(when (kind) {
            WorshipKind.VESPERS -> "Тексты на 13 ноября. Вечерняя служба относится к следующему церковному дню"
            WorshipKind.VIGIL -> "Тексты на 12 ноября. Вечерняя служба относится к следующему церковному дню"
            else -> "Тексты на 14 ноября"
        })
        assertEquals(book.key, store.last(parish.id)?.key); assertEquals(book.contentHash, store.last(parish.id)?.contentHash)
        capture("calendar")
    }
    private fun waitReader() = compose.waitUntil(15_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }.also { settle() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(900); compose.waitForIdle() }
    private fun snapshot() = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) }
    private fun capture(label: String) {
        compose.waitForIdle(); val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val out = File(context.getExternalFilesDir("partial-published-qa"), name).apply { mkdirs() }
            val file = File(out, "${++captureIndex}-$label-dark$dark-kind$kind.png")
            check(file.createNewFile())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun compactWindow(body: () -> Unit) {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val oldFont = shell("settings get system font_scale").toFloatOrNull()
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { ready = abs(it.resources.configuration.fontScale - 2f) < .001f &&
                    it.resources.configuration.screenWidthDp in 318..322 && it.window.decorView.hasWindowFocus() }
                ready
            }
            body()
        } finally {
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == null) "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},kind={1}")
        fun configurations() = listOf(false, true).flatMap { dark ->
            listOf(WorshipKind.LITURGY, WorshipKind.VESPERS, WorshipKind.VIGIL).map { kind -> arrayOf(dark, kind) }
        }
    }
}
