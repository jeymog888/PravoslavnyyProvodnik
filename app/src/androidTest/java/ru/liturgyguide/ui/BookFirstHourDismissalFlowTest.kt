package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exact Native86 HOUR1/VIGIL archives plus a fresh Sunday source parsed with the own translation index.
 * Mounted cached-reader evidence does not claim an Android process restart. */
@RunWith(Parameterized::class)
class BookFirstHourDismissalFlowTest(
    private val date: String,
    private val worship: WorshipKind,
    private val dark: Boolean,
    private val textMode: BookReadingMode
) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "hour1-dismissal-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val output by lazy {
        File(requireNotNull(context.getExternalFilesDir("hour1-dismissal-qa")), name).apply { mkdirs() }
    }
    private val mounted = mutableStateOf(false)
    private var launched = false
    private val oracle = JsonParser.parseString(instrumentation.context.assets.open("first-hour-dismissal/oracle.json")
        .use { it.readBytes().toString(Charsets.UTF_8) }).asJsonObject["cases"].asJsonArray
        .single { it.asJsonObject["date"].asString == date && it.asJsonObject["worship"].asString == worship.name }.asJsonObject
    private val archivedBytes = oracle["archiveFile"]?.asString?.let { filename ->
        instrumentation.context.assets.open("first-hour-dismissal/$filename").use { it.readBytes() }
    }
    private val html = instrumentation.context.assets.open("first-hour-dismissal/$date-chas1.html")
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private val book = archivedBytes?.let { Gson().fromJson(it.toString(Charsets.UTF_8), ServiceBook::class.java) } ?: freshBook()
    private val chapter = book.chapters.last()
    private val target = chapter.blocks.first()
    private val parish = "controlled-hour1-dismissal"
    private val record = linkedMapOf<String, Any?>("status" to "NOT_COMPLETED",
        "scope" to oracle["scope"].asString, "date" to date, "worship" to worship.name,
        "dark" to dark, "textMode" to textMode.name)

    @After fun clean() {
        if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun wholeBookKeepsSelectedTextAndSpecificMeaningWithoutChangingItsCachedEdition() = withCompactWindow {
        var httpCalls = 0
        try {
            if (archivedBytes != null) {
                assertEquals(oracle["archiveBytes"].asInt, archivedBytes.size)
                assertEquals(oracle["archiveSha256"].asString, BookText.sha256(archivedBytes.toString(Charsets.UTF_8)))
                assertEquals(oracle["contentHash"].asString, book.contentHash)
                assertEquals(oracle["bodyId"].asString, target.id)
            } else assertEquals(oracle["sha256"].asString, BookText.sha256(html))
            assertEquals(oracle["originalSha256"].asString, BookText.sha256(target.original))
            val meaning = oracle["meaning"].asString
            assertEquals(oracle["meaningUtf8Sha256"].asString, BookText.sha256(meaning))
            assertNull(target.speaker)
            if (archivedBytes != null) {
                assertEquals(BookBlockKind.RUBRIC, target.kind)
                assertEquals(target.original, target.russian)
                assertNull(target.translationSource)
                assertEquals(target.original, target.meaning)
            } else {
                val russian = oracle["russian"].asString
                assertEquals(oracle["russianUtf8Sha256"].asString, BookText.sha256(russian))
                assertEquals(BookBlockKind.PRAYER, target.kind)
                assertEquals(russian, target.russian)
                assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, target.translationSource)
                assertTrue(book.sources.contains(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE))
                assertEquals(meaning, target.meaning)
            }
            assertEquals(2, chapter.blocks.size)
            assertTrue(ServiceBookIntegrity.valid(book))
            val serialized = Gson().toJson(book)
            val cache = FileBookCache(directory)
            assertTrue(cache.write(book))
            val cacheBefore = cacheSnapshot()
            val loaded = runBlocking { ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                httpCalls++; error("Controlled offline reader")
            }).load(book.key) as BookLoadResult.Ready }
            assertEquals(book, loaded.book)
            // A previously stored Meaning position opens the last actual language.
            val initialMode = if (date == "2026-11-22" || worship == WorshipKind.VIGIL) BookReadingMode.MEANING else textMode
            record["initialMode"] = initialMode.name
            assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parish, target.id, chapter.id,
                fraction = if (textMode == BookReadingMode.RUSSIAN) 0f else .26f,
                mode = initialMode, lastTextMode = textMode, wordsPerMinute = 100)))
            var activeBook = loaded.book
            var activeStore = store
            compose.setContent { GuideTheme(dark) {
                if (mounted.value) BookReaderScreen(activeBook, parish, activeStore, 0,
                    onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
            } }
            launched = true
            compose.runOnIdle { mounted.value = true }; settle()
            val before = saved()
            record["before"] = before
            assertEquals(book.contentHash, before.contentHash)
            assertEquals(chapter.id, before.chapterId)
            assertEquals(target.id, before.blockId)
            assertEquals(textMode, before.mode)
            assertEquals(textMode, before.lastTextMode)
            assertEquals(BookAnchorKind.BLOCK, before.anchorKind)
            assertEquals(100, before.wordsPerMinute)
            assertSelectedText()
            capture("text")
            val beforeTop = compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top
            compose.onNodeWithTag("book_mode_meaning").assertIsDisplayed().performClick(); settle()
            val title = compose.onNodeWithTag("book_meaning_title").performScrollTo().assertTextEquals("Отпуст")
            assertFullLayout(title, "Отпуст")
            if (compose.onAllNodesWithTag("book_meaning_more").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick(); settle()
            }
            val detail = compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextEquals(meaning)
            assertFullLayout(detail, meaning)
            capture("meaning")
            compose.onNodeWithTag("book_meaning_excerpt_toggle").performScrollTo().performClick(); settle()
            val excerpt = compose.onNodeWithTag("book_meaning_excerpt").performScrollTo().assertTextEquals(selectedText())
            assertFullLayout(excerpt, selectedText())
            val close = compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val closeBounds = close.getBoundsInRoot()
            assertEquals(close.getUnclippedBoundsInRoot(), closeBounds)
            assertTrue(compose.onNodeWithTag("book_meaning_content").getBoundsInRoot().bottom <= closeBounds.top)
            assertEquals(before, saved())
            close.performClick(); settle()
            compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
            assertSelectedText()
            assertEquals(before, saved())
            assertTrue(abs((compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top - beforeTop).value) <= 2f)
            // Close the composition, create a new cache/repository/store, and reopen without HTTP.
            compose.runOnIdle { mounted.value = false }; settle()
            val reopened = runBlocking { ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                httpCalls++; error("Controlled offline reopen")
            }).load(book.key) as BookLoadResult.Ready }
            assertEquals(book, reopened.book)
            val reopenedStore = BookReadingStore(preferences)
            assertEquals(before, reopenedStore.positionFor(book.key, parish))
            compose.runOnIdle {
                activeBook = reopened.book
                activeStore = reopenedStore
                mounted.value = true
            }; settle()
            assertSelectedText()
            assertEquals(before, saved())
            assertEquals(serialized, Gson().toJson(book))
            assertEquals(cacheBefore, cacheSnapshot())
            assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
            if (archivedBytes != null) assertArrayEquals(archivedBytes,
                instrumentation.context.assets.open("first-hour-dismissal/${oracle["archiveFile"].asString}").use { it.readBytes() })
            assertEquals(0, httpCalls)
            record["after"] = saved()
            record["status"] = if (archivedBytes != null) "PASS_UNCHANGED_NATIVE86_ARCHIVE_READER_UI_ONLY"
                else "PASS_FRESH_CANONICAL_OWN_RUSSIAN_READER_UI_ONLY"
        } catch (failure: Throwable) {
            record["status"] = "FAIL"
            record["failure"] = failure.toString()
            throw failure
        } finally {
            record["httpCalls"] = httpCalls
            record["display"] = mapOf("widthDp" to compose.activity.resources.configuration.screenWidthDp,
                "fontScale" to compose.activity.resources.configuration.fontScale)
            File(output, "receipt.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(record))
        }
    }

    private fun freshBook(): ServiceBook {
        require(date == "2026-11-22" && worship == WorshipKind.HOUR1)
        return ServiceBookParser().parse(Jsoup.parse(html), ServiceBookKey(date, worship), 1L,
            BookTranslationIndex.fromFeoriaCatalog())
    }
    private fun selectedText(): String = if (archivedBytes == null && textMode == BookReadingMode.RUSSIAN)
        oracle["russian"].asString else target.original
    private fun assertSelectedText() {
        val text = compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed().assertTextEquals(selectedText())
        if (archivedBytes == null) assertFullLayout(text, selectedText())
        val missing = compose.onNodeWithTag("book_missing_russian_${target.id}")
        if (archivedBytes != null && textMode == BookReadingMode.RUSSIAN)
            missing.assertIsDisplayed().assertTextEquals("Этот фрагмент пока в оригинале")
        else missing.assertDoesNotExist()
        val ownLabel = compose.onNodeWithTag("book_feoria_translation_${target.id}")
        if (archivedBytes == null && textMode == BookReadingMode.RUSSIAN)
            ownLabel.assertIsDisplayed().assertTextEquals("Перевод Феории")
        else ownLabel.assertDoesNotExist()
    }
    private fun saved() = requireNotNull(store.positionFor(book.key, parish))
    private fun cacheSnapshot() = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun assertFullLayout(node: SemanticsNodeInteraction, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTextFitsLayout(layouts.single(), expected)
    }
    private fun capture(label: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun withCompactWindow(body: () -> Unit) {
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone"))
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        check(shell("am get-current-user") == "0")
        val oldFont = shell("settings get system font_scale")
        check(oldFont == "null" || oldFont.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { activity ->
                    ready = abs(activity.resources.configuration.fontScale - 2f) < .001f &&
                        activity.resources.configuration.screenWidthDp in 300..340 && activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } finally {
            if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-dark={2}-{3}")
        fun cases(): List<Array<Any>> = listOf("2026-11-21" to WorshipKind.HOUR1,
            "2026-11-21" to WorshipKind.VIGIL, "2026-11-22" to WorshipKind.HOUR1).flatMap { (date, worship) ->
            listOf(false, true).flatMap { dark ->
                listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN).map { mode -> arrayOf<Any>(date, worship, dark, mode) }
            }
        }
    }
}
