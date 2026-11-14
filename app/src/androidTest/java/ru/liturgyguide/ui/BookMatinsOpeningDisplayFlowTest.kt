package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/** The unchanged actual Matins edition, real reader and search sheet in a native 320dp/200% window. */
@RunWith(Parameterized::class)
class BookMatinsOpeningDisplayFlowTest(private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "matins-opening-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val mounted = mutableStateOf(true)
    private val activeBook = mutableStateOf<ServiceBook?>(null)
    private val pending = mutableStateOf<BookLocatedSearchHit?>(null)
    private val selected = CopyOnWriteArrayList<BookLocatedSearchHit>()
    private val calls = CopyOnWriteArrayList<String>()
    private val parish = "opening-native-parish"
    private var contentSet = false
    private var cacheBefore = emptyMap<String, String>()
    private val raw by lazy { asset("polyeleos-journey/ranked-matins-2026-11-13-church34.json") }
    private val book by lazy { Gson().fromJson(raw.toString(Charsets.UTF_8), ServiceBook::class.java) }
    private val first get() = book.chapters.first()
    private val target get() = first.blocks[8]
    private fun asset(path: String) = instrumentation.context.assets.open(path).use { it.readBytes() }

    @After fun clean() {
        if (contentSet) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name); directory.deleteRecursively()
    }

    @Test fun openingHeaderOrderMeaningAndOfflineRestoreKeepTheUntouchedAcceptedBookAndPlace() = withNativeCompactFont {
        prepare()
        val initial = BookReadingPosition(book.key, book.contentHash, parish, target.id, first.id, fraction = .24f)
        assertTrue(store.save(initial))
        launch(book)
        val before = saved()
        assertEquals(initial.blockId, before.blockId); assertEquals(initial.fraction, before.fraction, .003f)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(TITLE)
        wholeWords("book_chapter", TITLE)
        compose.onNodeWithTag("book_order").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        capture("header")
        val beforeMeaning = preferences.all.toMap()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.onNodeWithTag("book_meaning_title").assertTextEquals(TITLE)
        wholeWords("book_meaning_title", TITLE)
        compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextEquals(DETAIL)
        compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("meaning")
        compose.onNodeWithTag("book_meaning_close").performClick(); settle()
        assertEquals(beforeMeaning, preferences.all); assertEquals(before, saved())
        selectMode(BookReadingMode.RUSSIAN)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(TITLE)
        assertEquals(target.id, saved().blockId)
        compose.onNodeWithTag("book_order").performClick(); settle()
        compose.onNodeWithTag("book_order_list").performScrollToNode(hasTestTag("book_order_item_${first.id}"))
        compose.onNodeWithTag("book_order_title_${first.id}", useUnmergedTree = true).assertTextEquals(TITLE)
        wholeWords("book_order_title_${first.id}", TITLE)
        capture("order")
        compose.onNodeWithTag("book_order_item_${first.id}").performClick(); settle()
        assertEquals(first.id, saved().chapterId)
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_section_title_${first.id}"))
        compose.onNodeWithTag("book_section_title_${first.id}").performScrollTo().assertTextEquals(TITLE)
        wholeWords("book_section_title_${first.id}", TITLE)
        capture("section")
        val beforeReopen = saved()
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        val reopened = runBlocking { repository().load(book.key) }
        assertTrue(reopened is BookLoadResult.Ready)
        val accepted = (reopened as BookLoadResult.Ready).book
        assertEquals(book, accepted)
        compose.runOnIdle { activeBook.value = accepted; mounted.value = true }; settle()
        assertEquals(beforeReopen, saved())
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(TITLE)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertTrue(calls.isEmpty()); unchanged()
    }

    @Test fun localAndCrossBookSearchShowTheDerivedLabelButJumpWithTheOriginalEditionProof() = withNativeCompactFont {
        prepare()
        val key = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
        val html = asset("polyeleos-journey/vespers-2026-11-13-church34.html").toString(Charsets.UTF_8)
        assertEquals("d81f4d9054cce231f28442482f2547b038e3f8ff3241f6004ef0c98c78cbdb66", BookText.sha256(html))
        val evening = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1)
        assertTrue(ServiceBookIntegrity.valid(evening)); assertTrue(cache.write(evening))
        cacheBefore = cacheSnapshot()
        val journey = BookJourneyContext(parish, "2026-11-12", "17:00", "Вечерня. Полиелейная утреня", listOf(evening.key, book.key))
        assertEquals(journey, journey.validatedFor(book.key, parish))
        assertTrue(store.save(BookReadingPosition(evening.key, evening.contentHash, parish,
            evening.blocks.first().id, evening.chapters.first().id)))
        val hit = BookCorpusSearch.find(listOf(book), QUERY, BookReadingMode.ORIGINAL).first { it.hit.blockId == target.id }
        assertEquals("Перед началом", hit.hit.chapterTitle); assertEquals(hit, hit.validatedFor(book))
        val proof = Gson().toJson(hit)
        launch(evening, journey, listOf(evening, book))
        val beforeSearch = preferences.all.toMap()
        search(QUERY)
        val crossTag = "book_cross_hit_${hit.identity}"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(crossTag))
        compose.onNodeWithTag("book_hit_title_${hit.identity}", useUnmergedTree = true).assertTextEquals(TITLE)
        wholeWords("book_hit_title_${hit.identity}", TITLE)
        assertEquals(beforeSearch, preferences.all)
        capture("cross-search")
        compose.onNodeWithTag(crossTag).performClick()
        compose.waitUntil(15_000) { activeBook.value?.key == book.key &&
            store.positionFor(book.key, parish)?.blockId == target.id && pending.value == null }
        waitFor("book_content"); settle()
        assertEquals(listOf(hit), selected.toList())
        assertEquals(book.key, saved().key); assertEquals(book.contentHash, saved().contentHash)
        assertEquals(target.id, saved().blockId); assertEquals(first.id, saved().chapterId)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(TITLE)
        assertEquals(proof, Gson().toJson(hit)); assertEquals(hit, hit.validatedFor(book)); assertNull(pending.value)
        val beforeLocal = preferences.all.toMap()
        search(QUERY)
        compose.scrollToBookHit(target.id)
        compose.onNodeWithTag("book_hit_title_${hit.identity}", useUnmergedTree = true).assertTextEquals(TITLE)
        assertEquals(beforeLocal, preferences.all)
        capture("local-search")
        compose.onNodeWithTag("book_hit_${target.id}").performClick(); settle()
        assertEquals(target.id, saved().blockId)
        assertEquals("Перед началом", hit.hit.chapterTitle)
        assertTrue(calls.isEmpty()); unchanged()
    }

    private fun prepare() {
        assertEquals("440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", BookText.sha256(raw.toString(Charsets.UTF_8)))
        assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", book.contentHash)
        assertTrue(ServiceBookIntegrity.valid(book)); assertTrue(cache.write(book)); cacheBefore = cacheSnapshot()
    }
    private fun repository() = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
        calls += url; error("The display-only opening must work offline")
    }, downloadRussian = false)
    private fun launch(initial: ServiceBook, journey: BookJourneyContext? = null, corpus: List<ServiceBook> = listOf(initial)) {
        activeBook.value = initial
        compose.setContent {
            GuideTheme(dark) { if (mounted.value) {
                val current = requireNotNull(activeBook.value)
                key(current.key, current.contentHash) {
                        BookReaderScreen(current, parish, store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {},
                            journeyContext = journey,
                            searchCorpusLoader = { BookSearchCorpus(listOf(current) + corpus.filter { it.key != current.key }) },
                            onChooseSearchHit = { located ->
                                val owner = corpus.singleOrNull { it.key == located.bookKey && it.contentHash == located.contentHash }
                                if (owner == null || located.validatedFor(owner) == null) false else {
                                    selected += located; pending.value = located; activeBook.value = owner; true
                                }
                            }, pendingSearchTarget = pending.value,
                            onPendingSearchHandled = { located ->
                                if (pending.value == located) pending.value = null
                                true
                            })
                }
            } }
        }
        contentSet = true
        waitFor("book_content"); settle()
    }
    private fun selectMode(mode: BookReadingMode) {
        val tag = "book_mode_${mode.name.lowercase()}"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag(tag).performClick(); settle()
    }
    private fun search(query: String) {
        compose.onNodeWithTag("book_find").performClick(); waitFor("book_search_query")
        compose.onNodeWithTag("book_search_query").performTextInput(query)
        closeSoftKeyboard(); compose.awaitBookSearch()
    }
    private fun saved() = requireNotNull(store.positionFor(requireNotNull(activeBook.value).key, parish))
    private fun cacheSnapshot() = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) }
    private fun unchanged() {
        assertArrayEquals(raw, asset("polyeleos-journey/ranked-matins-2026-11-13-church34.json"))
        assertEquals(cacheBefore, cacheSnapshot())
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        assertEquals("Перед началом", book.chapters.first().title)
    }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(900); compose.waitForIdle() }
    private fun wholeWords(tag: String, expected: String) {
        try {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val fullBounds = node.getUnclippedBoundsInRoot()
            val visibleBounds = node.getBoundsInRoot()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            val linesFit = (0 until result.lineCount).all { line ->
                val left = result.getLineLeft(line); val right = result.getLineRight(line)
                val top = result.getLineTop(line); val bottom = result.getLineBottom(line)
                listOf(left, right, top, bottom).all { it.isFinite() } &&
                    left <= right && top <= bottom && !result.isLineEllipsized(line) &&
                    left >= 0f && right <= result.size.width && top >= 0f && bottom <= result.size.height
            }
            val textMatches = result.layoutInput.text.text == expected
            val completeText = textMatches && result.lineCount > 0 &&
                result.getLineEnd(result.lineCount - 1) == expected.length &&
                result.getLineEnd(result.lineCount - 1, visibleEnd = true) == expected.length
            val wordsFit = textMatches && result.lineCount > 0 && Regex("\\S+").findAll(expected).all { word ->
                result.getLineForOffset(word.range.first) == result.getLineForOffset(word.range.last)
            }
            val fits = fullBounds == visibleBounds && !result.didOverflowHeight && linesFit && completeText && wordsFit
            val out = File(context.getExternalFilesDir("matins-opening-qa"), name).apply { mkdirs() }
            File(out, "layout.ndjson").appendText(Gson().toJson(mapOf(
                "tag" to tag, "expected" to expected, "text" to result.layoutInput.text.text,
                "size" to listOf(result.size.width, result.size.height),
                "constraints" to result.layoutInput.constraints.toString(),
                "paragraphSize" to listOf(result.multiParagraph.width, result.multiParagraph.height),
                "fullBounds" to fullBounds.toString(), "visibleBounds" to visibleBounds.toString(),
                "linesFit" to linesFit, "completeText" to completeText, "wordsFit" to wordsFit, "fits" to fits,
                "fontScale" to context.resources.configuration.fontScale,
                "screenWidthDp" to context.resources.configuration.screenWidthDp,
                "maxLines" to result.layoutInput.maxLines, "lines" to result.lineCount,
                "overflowWidth" to result.didOverflowWidth, "overflowHeight" to result.didOverflowHeight,
                "lineMetrics" to (0 until result.lineCount).map { line -> listOf(
                    result.getLineStart(line), result.getLineEnd(line), result.getLineLeft(line),
                    result.getLineRight(line), result.getLineTop(line), result.getLineBottom(line),
                    result.isLineEllipsized(line), result.getLineEnd(line, visibleEnd = true)) }
            )) + "\n")
            assertEquals("The complete heading must remain inside the ancestor clip", fullBounds, visibleBounds)
            assertEquals(expected, result.layoutInput.text.text)
            assertFalse(result.didOverflowHeight)
            assertTrue("Every complete line must fit the measured text box without ellipsis", linesFit)
            assertTrue("The full expected text must be present in visible line ends", completeText)
            assertTrue("Words must not split across lines", wordsFit)
        } catch (failure: Throwable) {
            if (failure is AssertionError || failure is Exception) {
                try { capture("failed-${tag.take(90)}") }
                catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
            }
            throw failure
        }
    }
    private fun capture(label: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val out = File(context.getExternalFilesDir("matins-opening-qa"), name).apply { mkdirs() }
            File(out, "$label-dark$dark.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    /** Same reversible native-window gate already exercised by BookMeaningSheetFlowTest. */
    private fun withNativeCompactFont(body: () -> Unit) {
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
                compose.activityRule.scenario.onActivity { activity ->
                    ready = abs(activity.resources.configuration.fontScale - 2f) < .001f &&
                        activity.resources.configuration.screenWidthDp in 318..322 && activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } finally {
            if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == null) "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
    private companion object {
        const val TITLE = "Начало утрени"
        const val DETAIL = "Это начало утрени: после благословения и приглашения к молитве читают псалмы 19 и 20, затем начальные молитвы и тропари. В тексте этого начала указано каждение храма. Далее следуют сугубая ектения и шестопсалмие"
        const val QUERY = "Услышит тя Господь в день печали"
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun configurations() = listOf(arrayOf(false), arrayOf(true))
    }
}
