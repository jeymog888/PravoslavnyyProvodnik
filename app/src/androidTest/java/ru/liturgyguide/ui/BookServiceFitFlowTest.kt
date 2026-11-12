package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID

/** Fixture delivery controls only the source books; the real screen and durable reading store are used. */
@RunWith(AndroidJUnit4::class)
class BookServiceFitFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-service-fit-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val parishId = "service-fit-parish"
    private val key = ServiceBookKey("2026-11-10", WorshipKind.MATINS, "20")
    private val vespers = key.copy(worship = WorshipKind.VESPERS)
    private val journey = BookJourneyContext(parishId, "2026-11-09", "18:00", "Вечернее богослужение. Полиелей",
        listOf(vespers, key), partSet = BookPartSetKind.EVENING_TEXTS)
    private val route = mutableStateOf(BookRoute(key, parishId, journey))
    private val mounted = mutableStateOf(true)
    private val ordinary = fixture(key)
    private val alternate = fixture(key.copy(mainEvent = "5"), polyeleos = true)
    private val books = object : ServiceBooks {
        override fun cached(key: ServiceBookKey) = when (key) { ordinary.key -> ordinary; alternate.key -> alternate; else -> null }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
            cached(key)?.let { BookLoadResult.Ready(it, fromCache = true) }
                ?: BookLoadResult.Failed(BookFailure.INVALID_KEY, "Тестовый вариант отсутствует")
    }

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun mismatchAndRecreationDoNotAcceptTheBookUntilTheReaderExplicitlyChoosesIt() {
        val accepted = BookReadingPosition(key, ordinary.contentHash, parishId, "opening-long", "opening",
            fraction = .42f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 113, journeyContext = journey)
        assertTrue(store.save(accepted))
        val previous = preferences.all.toMap()
        val restoration = launch()
        waitFor("book_service_fit")
        settle()
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_service_fit_book_title").assertTextEquals("Славословная утреня")
        assertEquals(previous, preferences.all)

        restoration.emulateSavedInstanceStateRestore()
        waitFor("book_service_fit")
        settle()
        assertEquals(previous, preferences.all)
        compose.onNodeWithTag("book_service_fit_read_base").performScrollTo().performClick()
        waitFor("book_content")
        settle()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        val resumed = requireNotNull(store.last(parishId))
        assertEquals(accepted.contentHash, resumed.contentHash)
        assertEquals(accepted.blockId, resumed.blockId)
        assertEquals(accepted.mode, resumed.mode)
        assertEquals(accepted.fraction, resumed.fraction, .001f)
        assertEquals(113, resumed.wordsPerMinute)
        assertEquals(journey, resumed.journeyContext)

        restoration.emulateSavedInstanceStateRestore()
        waitFor("book_content")
        compose.onNodeWithTag("book_service_fit").assertDoesNotExist()
    }

    @Test fun choosingAnExistingMemoryOpensItsOrderAndKeepsThePublishedOccurrence() {
        launch()
        waitFor("book_service_fit")
        settle()
        assertTrue(preferences.all.isEmpty())
        compose.onNodeWithTag("book_service_fit_choose_memory").performScrollTo().performClick()
        compose.onNodeWithTag("book_service_fit_variant_5").performScrollTo().performClick()
        waitFor("book_content")
        settle()
        compose.onNodeWithTag("book_service_fit").assertDoesNotExist()
        val opened = requireNotNull(store.last(parishId))
        assertEquals(alternate.key, opened.key)
        assertEquals(alternate.contentHash, opened.contentHash)
        assertNull("The source chose its own natural order, without imposing a rank", opened.key.topLevel)
        assertEquals(parishId, opened.parishId)
        assertEquals("2026-11-09", opened.journeyContext?.civilDate)
        assertEquals("18:00", opened.journeyContext?.time)
        assertEquals("Вечернее богослужение. Полиелей", opened.journeyContext?.title)
        assertEquals(listOf(vespers, alternate.key), opened.journeyContext?.parts)
        assertNull("Merely inspecting the original candidate must not accept it", store.positionFor(key, parishId))
    }

    @Test fun rejectedVariantChangeLeavesTheGuardAndAllReadingPositionsUntouched() {
        launch(rejectChange = true)
        waitFor("book_service_fit")
        compose.onNodeWithTag("book_service_fit_choose_memory").performScrollTo().performClick()
        compose.onNodeWithTag("book_service_fit_variant_5").performScrollTo().performClick()
        settle()
        compose.onNodeWithTag("book_service_fit_error").assertExists()
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertEquals(key, route.value.activeKey)
        assertEquals(journey, route.value.journeyContext)
        assertTrue(preferences.all.isEmpty())
    }

    private fun launch(rejectChange: Boolean = false): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent {
            GuideTheme(false) {
                if (mounted.value) ServiceBookScreen(key, parishId, books, store, 0,
                    onBack = {}, onSettings = {}, onOpenUrl = {}, route = route.value,
                    onRouteChanged = { next -> if (rejectChange) false else { route.value = next; true } },
                    onOpenCommonLiturgy = {})
            }
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    /** Synthetic renderer fixture, not a captured or reconstructed church service. */
    private fun fixture(key: ServiceBookKey, polyeleos: Boolean = false): ServiceBook {
        fun prayer(id: String, text: String) = BookBlock(id, BookBlockKind.PRAYER, null, text, text)
        val chapters = buildList {
            add(BookChapter("opening", "Начальные молитвы", "Вместе начинаем молитву", listOf(
                prayer("opening-long", "Господи, помоги нам внимательно слушать и понимать эту молитву. ".repeat(100))) +
                (1..14).map { prayer("ordinary-$it", "Господи, помилуй") }))
            if (polyeleos) {
                add(BookChapter("polyeleos", "Полиелей", "Хвалебные псалмы", listOf(
                    prayer("psalms", "Хвалите имя Господне. Исповедайтеся Господеви"))))
                add(BookChapter("gospel", "Чтение Евангелия", "Слушаем Евангелие", listOf(
                    BookBlock("gospel-text", BookBlockKind.SCRIPTURE, null, "Во время оно", "В то время",
                        scriptureReferences = listOf("Jn.10:9-16")))))
                add(BookChapter("doxology", "Славословие великое", "Славим Бога", listOf(
                    prayer("doxology-text", "Слава в вышних Богу"))))
            }
        }
        val book = ServiceBook(key = key, title = if (polyeleos) "Полиелейная утреня" else "Славословная утреня",
            sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник", fetchedAtMillis = 12345,
            selectedEventTitle = if (polyeleos) "Святитель Димитрий Ростовский" else "Мученица Параскева",
            chapters = chapters, contentHash = "", mainEventOptions = listOf(
                BookVariantOption("0", "Мученица Параскева"), BookVariantOption("5", "Святитель Димитрий Ростовский")),
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
