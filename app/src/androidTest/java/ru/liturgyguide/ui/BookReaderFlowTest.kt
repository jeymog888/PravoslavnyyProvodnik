package ru.liturgyguide.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real reader composables and disk-backed positions; no network or speech engine is invoked. */
@RunWith(AndroidJUnit4::class)
class BookReaderFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-reader-flow-${UUID.randomUUID()}"
    private val preferences by lazy { context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }
    private val store by lazy { BookReadingStore(preferences) }
    private val mounted = mutableStateOf(true)
    private val parishId = "book-reader-test-parish"

    @After fun clean() {
        // Dispose first: the reader saves its last cursor from onDispose.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun nearEndLongParagraphSurvivesMeaningAndReturnAtTheSamePlace() {
        val book = sampleBook()
        val target = book.chapters.last()
        seed(book, target.id, "near-end-long", fraction = .42f)
        reader(book)
        settle()

        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(target.title)
        val node = compose.onNodeWithTag("book_block_near-end-long").assertIsDisplayed()
        val beforeTop = node.getUnclippedBoundsInRoot().top
        val viewportTop = compose.onNodeWithTag("book_content").getBoundsInRoot().top
        assertTrue("The test must start inside a long paragraph, not at its heading", beforeTop < viewportTop - 40.dp)
        val before = saved()
        assertEquals(BookAnchorKind.BLOCK, before.anchorKind)
        assertEquals("near-end-long", before.blockId)

        repeat(5) { cycle ->
            compose.onNodeWithTag("book_mode_meaning").performClick()
            settle()
            compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(target.title)
            compose.onNodeWithText(target.meaning).assertIsDisplayed()
            val meaning = saved()
            assertEquals("Meaning mode, cycle $cycle: $meaning", BookReadingMode.MEANING, meaning.mode)
            assertEquals("Meaning block, cycle $cycle: $meaning", before.blockId, meaning.blockId)
            assertEquals(before.chapterId, meaning.chapterId)
            assertEquals(BookAnchorKind.BLOCK, meaning.anchorKind)
            assertEquals(before.fraction, meaning.fraction, .001f)

            compose.onNodeWithTag("book_mode_original").performClick()
            settle()
            compose.onNodeWithTag("book_mode_original").assertIsSelected()
            val restored = saved()
            assertEquals(BookReadingMode.ORIGINAL, restored.mode)
            assertEquals("Returned block, cycle $cycle: $restored", before.blockId, restored.blockId)
            assertEquals(before.chapterId, restored.chapterId)
            assertEquals(BookAnchorKind.BLOCK, restored.anchorKind)
            assertEquals(before.fraction, restored.fraction, .001f)
            val afterTop = compose.onNodeWithTag("book_block_near-end-long").assertIsDisplayed().getUnclippedBoundsInRoot().top
            assertTrue("Returning from Meaning must retain the visible line, cycle $cycle: before=$beforeTop after=$afterTop saved=$restored",
                abs((afterTop - beforeTop).value) <= 2f)
        }
    }

    @Test fun searchFromMeaningRevealsAndOpensTheExactHiddenAltarFragment() {
        val book = sampleBook()
        seed(book, "opening", "opening-1", mode = BookReadingMode.MEANING)
        reader(book)
        settle()
        assertFalse(saved().showAltar)
        compose.onNodeWithTag("book_mode_meaning").assertIsSelected()
        compose.onNodeWithTag("book_block_altar-target").assertDoesNotExist()

        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput("тайная молитва испытания")
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.scrollToBookHit("altar-target").assertIsDisplayed().performClick()
        settle()

        compose.onNodeWithTag("book_mode_original").assertIsSelected()
        compose.onNodeWithTag("book_block_altar-target").assertIsDisplayed()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Молитвы в алтаре")
        val accepted = saved()
        assertEquals(BookReadingMode.ORIGINAL, accepted.mode)
        assertEquals("altar-target", accepted.blockId)
        assertEquals("altar", accepted.chapterId)
        assertEquals(BookAnchorKind.BLOCK, accepted.anchorKind)
        assertTrue("Selecting an altar result must reveal it instead of falling back to a heading", accepted.showAltar)
        assertEquals(0f, accepted.fraction, .001f)
    }

    @Test fun hiddenOpeningPrayersDoNotLeaveAnEmptyHeadingBeforeThePublicText() {
        val original = sampleBook()
        val hidden = BookChapter("private-opening", "Приготовление в алтаре", "Подготовка священника",
            listOf(BookBlock("private-first", BookBlockKind.PRAYER, null, "Молитва перед началом", isAltarText = true)))
        val book = original.copy(chapters = listOf(hidden) + original.chapters).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        reader(book)
        settle()
        compose.onNodeWithTag("book_saved").assertIsDisplayed()
        compose.onNodeWithText(hidden.title).assertDoesNotExist()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Начальные молитвы")
        compose.onNodeWithTag("book_block_opening-1").assertIsDisplayed()
    }

    @Test fun compactReaderAtTwoHundredPercentCanScrollOptionsAndRefresh() {
        // ModalBottomSheet owns an Android window. A small parent Box/LocalDensity
        // cannot constrain it, so exercise and restore a real compact display profile.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val previousFont = shell("settings get system font_scale").toFloatOrNull()
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(480 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { activity ->
                    val configuration = activity.resources.configuration
                    ready = abs(configuration.fontScale - 2f) < .001f &&
                        configuration.screenWidthDp in 300..340 && configuration.screenHeightDp in 380..500 &&
                        activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            val refreshes = AtomicInteger()
            reader(sampleBook(), fontScale = 2f, height = 480, onRefresh = { refreshes.incrementAndGet() })
            compose.onNodeWithContentDescription("Настроить чтение").assertIsDisplayed().performClick()
            compose.waitForIdle()

            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Темп прокрутки: 85 слов/мин", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val actual = layouts.single()
            assertEquals("The dialog itself must render at native 200%", 2f, actual.layoutInput.density.fontScale, .001f)
            assertNativeTextMetrics(actual, Density(density, 2f))
            val options = compose.onNodeWithTag("book_options")
            val range = options.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("At 200% the compact dialog must overflow and remain scrollable", range.maxValue() > 0f)
            val before = range.value()
            compose.onNodeWithTag("book_refresh").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1, refreshes.get()) }
            val after = options.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Refresh must be reached by scrolling the options, not by a clipped click", after > before)
        } finally {
            try {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            } finally {
                try { shell("wm size ${previousSize ?: "reset"}") }
                finally {
                    shell(if (previousFont == null) "settings delete system font_scale" else "settings put system font_scale $previousFont")
                }
            }
        }
    }

    @Test fun reopeningPinnedBookShowsSavedSupplementFailureWithoutNetwork() {
        val book = sampleBook(BookEnrichment(BookSupplementStatus.RETRY_NEEDED, BookSupplementStatus.RETRY_NEEDED))
        seed(book, "opening", "opening-1", anchor = BookAnchorKind.HEADER)
        val loads = AtomicInteger()
        val pinnedLookups = AtomicInteger()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? = book.takeIf { it.key == key }
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
                pinnedLookups.incrementAndGet()
                return book.takeIf { it.key == key && it.contentHash == contentHash }
            }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                loads.incrementAndGet()
                error("A pinned book must open without contacting the network")
            }
        }
        content {
            ServiceBookScreen(book.key, parishId, books, BookReadingStore(preferences), 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Не удалось полностью обновить", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("русский перевод молитв", substring = true).assertExists()
        compose.onNodeWithText("русские чтения Писания", substring = true).assertExists()
        assertTrue(pinnedLookups.get() > 0)
        assertEquals("Pinned restore must not silently replace the accepted version", 0, loads.get())
    }

    @Test fun reopeningAnEarlierServiceKeepsItsAcceptedEditionAfterReadingAnotherDate() {
        val accepted = sampleBook()
        val latest = accepted.copy(title = "Литургия с обновленным текстом", contentHash = "")
            .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val anotherKey = accepted.key.copy(date = "2026-11-12")
        val another = accepted.copy(key = anotherKey, sourceUrl = anotherKey.sourceUrl(), contentHash = "")
            .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val chapter = accepted.chapters.last()
        seed(accepted, chapter.id, "near-end-long", fraction = .42f, mode = BookReadingMode.RUSSIAN)
        seed(another, "opening", "opening-1", anchor = BookAnchorKind.HEADER)
        assertEquals("Another date must really own the last-read shortcut", another.key, saved().key)
        val loads = AtomicInteger()
        val pinnedLookups = AtomicInteger()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? = latest.takeIf { it.key == key }
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
                pinnedLookups.incrementAndGet()
                return accepted.takeIf { it.key == key && it.contentHash == contentHash }
            }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                loads.incrementAndGet()
                return BookLoadResult.Ready(latest, fromCache = true)
            }
        }
        content {
            ServiceBookScreen(accepted.key, parishId, books, BookReadingStore(preferences), 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("Returning to a bookmarked date must not load/adopt the latest edition", 0, loads.get())
        assertTrue(pinnedLookups.get() > 0)
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(chapter.title)
        val restored = saved()
        assertEquals(accepted.key, restored.key)
        assertEquals(accepted.contentHash, restored.contentHash)
        assertEquals("near-end-long", restored.blockId)
        assertEquals(BookAnchorKind.BLOCK, restored.anchorKind)
        assertEquals(BookReadingMode.RUSSIAN, restored.mode)
        assertEquals(.42f, restored.fraction, .001f)
        val paragraphTop = compose.onNodeWithTag("book_block_near-end-long").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val viewportTop = compose.onNodeWithTag("book_content").getBoundsInRoot().top
        assertTrue("The accepted paragraph must reopen inside its text", paragraphTop < viewportTop - 40.dp)
    }

    private fun reader(book: ServiceBook, fontScale: Float = 1f, height: Int = 640, onRefresh: () -> Unit = {}) {
        content(fontScale, height) {
            BookReaderScreen(book, parishId, store, 0, onBack = {}, onOpenUrl = {},
                onChooseVersion = {}, onSettings = {}, onRefresh = onRefresh)
        }
        compose.onNodeWithTag("book_content").assertExists()
    }

    private fun content(fontScale: Float = 1f, height: Int = 640, body: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 320
                screenHeightDp = height
                this.fontScale = fontScale
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) {
                    if (mounted.value) Box(Modifier.size(320.dp, height.dp)) { body() }
                }
            }
        }
    }

    private fun seed(book: ServiceBook, chapter: String, block: String, fraction: Float = 0f,
        mode: BookReadingMode = BookReadingMode.ORIGINAL, anchor: BookAnchorKind = BookAnchorKind.BLOCK) {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId, block, chapter,
            fraction = fraction, mode = mode, showAltar = false, anchorKind = anchor)))
    }

    private fun saved(): BookReadingPosition = requireNotNull(BookReadingStore(preferences).last(parishId))

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun sampleBook(enrichment: BookEnrichment = BookEnrichment()): ServiceBook {
        fun prayer(id: String, text: String, altar: Boolean = false) = BookBlock(id, BookBlockKind.PRAYER,
            speaker = null, original = text, russian = "Русское изложение: $text", isAltarText = altar)
        fun ordinary(prefix: String) = (1..5).map { prayer("$prefix-$it", "Господи, помоги нам внимательно слушать и понимать эту молитву. ".repeat(3)) }
        val chapters = listOf(
            BookChapter("opening", "Начальные молитвы", "Собираемся для общей молитвы", ordinary("opening")),
            BookChapter("altar", "Молитвы в алтаре", "Священник молится о собравшихся", ordinary("altar") +
                prayer("altar-target", "Тайная молитва испытания. " + "Даруй нам мир и помощь в добрых делах. ".repeat(45), altar = true)),
            BookChapter("reading", "Чтение Писания", "Слушаем слова Евангелия", ordinary("reading")),
            BookChapter("ending", "Благодарение", "Благодарим Бога и сохраняем молитву в повседневной жизни", listOf(
                prayer("near-end-long", "Благодарим Тебя за помощь и просим сохранить мир и любовь в наших сердцах. ".repeat(70)),
                prayer("last-prayer", "Аминь")))
        )
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовое последование",
            fetchedAtMillis = 1_794_348_000_000, selectedEventTitle = null, chapters = chapters, contentHash = "", enrichment = enrichment)
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
