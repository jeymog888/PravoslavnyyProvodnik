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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** Controlled saved reader around the actual98-word prayer; no full-service/Main/network claim. */
@RunWith(AndroidJUnit4::class)
class BookHoursMeaningFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixture = JsonParser.parseString(instrumentation.context.assets.open("meaning-hours/prayer.json")
        .use { it.readBytes().toString(Charsets.UTF_8) }).asJsonObject
    private val expected = fixture["expected"].asJsonObject
    private val name = "hours-meaning-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val output by lazy { File(requireNotNull(context.getExternalFilesDir("meaning-hours-qa")), name).apply { mkdirs() } }
    private val mounted = mutableStateOf(false)
    private val opened = mutableListOf<String>()
    private var launched = false
    private val target = BookBlock(fixture["blockId"].asString, BookBlockKind.PRAYER, null,
        fixture["original"].asString, meaning = fixture["inheritedWrongMeaning"].asString)
    private val chapter = BookChapter(fixture["chapterId"].asString, "Кондак", fixture["inheritedWrongMeaning"].asString,
        listOf(BookBlock("preceding-response", BookBlockKind.PRAYER, null, "Господи, помилуй."), target,
            BookBlock("following-response", BookBlockKind.PRAYER, null, "Господи, помилуй.")))
    // Real supporting prayers only, appended after the target chapter; no prayer or local meaning is invented.
    private val support = BookChapter("supporting-hour3-slice", "Контрольный фрагмент сохраненного Третьего часа",
        "Контролируемая выборка для проверки чтения, не полное последование",
        fixture["supportBlocks"].asJsonArray.map { Gson().fromJson(it, BookBlock::class.java) })
    private val book = ServiceBook(key = ServiceBookKey("2026-11-15", WorshipKind.HOUR3, "20"), title = "Третий час",
        sourceUrl = ServiceBookKey("2026-11-15", WorshipKind.HOUR3, "20").sourceUrl(), sourceTitle = "Контролируемый тест чтения",
        fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(chapter, support), contentHash = "")
        .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
    private val record = linkedMapOf<String, Any?>("status" to "NOT_COMPLETED", "scope" to "CONTROLLED_CACHED_READER_UI_ONLY")

    @After fun clean() {
        if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
        context.deleteSharedPreferences(name); directory.deleteRecursively()
    }
    @Test fun originalLightShowsTheSpecificPrayerAndReturnsToItsSavedLine() = verify(false, false, BookReadingMode.ORIGINAL)
    @Test fun russianDarkShowsTheSpecificPrayerWithoutRequiringANewTranslation() = verify(true, false, BookReadingMode.RUSSIAN)
    @Test fun originalDarkAt320AndDoubleFontKeepsFullTitleAndFixedReturn() = verify(true, true, BookReadingMode.ORIGINAL)
    @Test fun russianLightAt320AndDoubleFontKeepsFullTitleAndFixedReturn() = verify(false, true, BookReadingMode.RUSSIAN)

    private fun verify(dark: Boolean, compact: Boolean, mode: BookReadingMode) = withWindow(compact) {
        var httpCalls = 0
        record["dark"] = dark; record["compact"] = compact; record["mode"] = mode
        try {
            assertEquals(fixture["actualOriginalSha256"].asString, BookText.sha256(target.original))
            assertEquals(fixture["normalizedOriginalSha256"].asString, BookText.sha256(BookText.normalized(target.original)))
            assertNull(target.russian)
            val serialized = Gson().toJson(book); val cache = FileBookCache(directory)
            assertTrue("Controlled native slice must satisfy unchanged persisted-book integrity", ServiceBookIntegrity.valid(book))
            assertTrue(cache.write(book))
            val disk = File(directory, "${BookText.sha256(book.key.canonical()).take(32)}-${book.contentHash}.json")
            val bytes = disk.readBytes()
            val reopened = runBlocking { ServiceBookRepository(FileBookCache(directory),
                ScheduleTransport { httpCalls++; error("Controlled offline reader") }).load(book.key) as BookLoadResult.Ready }
            assertEquals(book, reopened.book); assertEquals(0, httpCalls)
            assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, name, target.id, chapter.id,
                fraction = .26f, mode = mode, lastTextMode = mode)))
            compose.setContent { GuideTheme(dark) { if (mounted.value) BookReaderScreen(reopened.book, name, store, 0,
                onBack = {}, onOpenUrl = { opened += it }, onChooseVersion = {}, onSettings = {}) } }
            launched = true; compose.runOnIdle { mounted.value = true }; settle()
            val before = requireNotNull(store.last(name)); record["before"] = before
            assertEquals(target.id, before.blockId); assertEquals(BookAnchorKind.BLOCK, before.anchorKind)
            compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(expected["title"].asString)
            // This controlled slice has no verified dated Hours annotation. Meaning must
            // preserve its existing safety gate as well as its paused reading position.
            val review = BookHourSourceReview.of(book)
            assertEquals(BookHourReviewStatus.UNKNOWN, review.status); assertTrue(review.blocksPlayback)
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов")
            record["beforePlaybackGate"] = review.status.name
            val top = compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top
            compose.onNodeWithTag("book_mode_meaning").assertIsDisplayed().performClick(); settle()
            val title = compose.onNodeWithTag("book_meaning_title").assertTextEquals(expected["title"].asString)
            title.performScrollTo(); assertFullLayout(title, expected["title"].asString)
            assertEquals(title.getUnclippedBoundsInRoot(), title.getBoundsInRoot()); capture("overview")
            for ((tag, key) in listOf("book_meaning_what" to "whatHappens", "book_meaning_why" to "why")) {
                val text = compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(expected[key].asString)
                assertFullLayout(text, expected[key].asString)
            }
            compose.onNodeWithTag("book_meaning_cue").performScrollTo().assertTextEquals(expected["cue"].asJsonObject["text"].asString)
            compose.onNodeWithTag("book_meaning_excerpt").performScrollTo().assertTextEquals(target.original)
            compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick(); settle()
            val detail = compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextEquals(expected["detail"].asString)
            assertFullLayout(detail, expected["detail"].asString); capture("details")
            compose.onNodeWithTag("book_meaning_source").performScrollTo().performClick()
            assertEquals(listOf(BookTranslationIndex.HOUR3_SOURCE.url), opened)
            val close = compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = close.getBoundsInRoot(); assertEquals(close.getUnclippedBoundsInRoot(), bounds)
            assertTrue(compose.onNodeWithTag("book_meaning_content").getBoundsInRoot().bottom <= bounds.top)
            assertEquals(before, store.last(name)); close.performClick(); settle()
            compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов")
            compose.mainClock.advanceTimeBy(3_000); compose.waitForIdle()
            assertEquals(before, store.last(name)); record["after"] = store.last(name)
            assertTrue(abs((compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top - top).value) <= 2f)
            assertEquals(serialized, Gson().toJson(book)); assertArrayEquals(bytes, disk.readBytes())
            assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash)); assertEquals(0, httpCalls)
            record["status"] = "PASS_CONTROLLED_READER_UI_ONLY"
        } catch (failure: Throwable) { record["status"] = "FAIL"; record["failure"] = failure.toString(); throw failure }
        finally { record["httpCalls"] = httpCalls; record["opened"] = opened
            record["display"] = mapOf("widthDp" to compose.activity.resources.configuration.screenWidthDp,
                "fontScale" to compose.activity.resources.configuration.fontScale)
            File(output, "receipt.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(record)) }
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun assertFullLayout(node: SemanticsNodeInteraction, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTextFitsLayout(layouts.single(), expected)
    }
    private fun capture(label: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun withWindow(compact: Boolean, body: () -> Unit) {
        if (!compact) { body(); return }
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone"))
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        check(shell("am get-current-user") == "0")
        val oldFont = shell("settings get system font_scale")
        check(oldFont == "null" || oldFont.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0"); shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) { var ready = false; compose.activityRule.scenario.onActivity { activity ->
                ready = abs(activity.resources.configuration.fontScale - 2f) < .001f &&
                    activity.resources.configuration.screenWidthDp in 300..340 && activity.window.decorView.hasWindowFocus() }; ready }
            body()
        } finally {
            if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
}
