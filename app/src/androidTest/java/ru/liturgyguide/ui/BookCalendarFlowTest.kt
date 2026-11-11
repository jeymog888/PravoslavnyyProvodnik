package ru.liturgyguide.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real disk cache and reader; offline is enforced by transports that refuse every request. */
@RunWith(AndroidJUnit4::class)
class BookCalendarFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runId = UUID.randomUUID().toString()
    private val preferenceName = "book-calendar-$runId"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "book-calendar-$runId")
    private val mounted = mutableStateOf(false)
    private val parish = "calendar-flow-parish"
    private val description = "Описание из календарного источника для проверки сохранения на телефоне"

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
        directory.deleteRecursively()
    }

    @Test fun savedCalendarOpensOfflineAt320DpAndLargeFontWithoutLosingThePrayerPosition() {
        val book = fixture()
        val day = requireNotNull(book.calendarDay)
        val target = book.blocks.first { it.original.length > 1_000 }
        assertTrue(ServiceBookIntegrity.valid(book))
        assertTrue(FileBookCache(directory).write(book))
        val networkRequests = AtomicInteger()
        val calendarRequests = AtomicInteger()
        fun offlineRepository() = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
            networkRequests.incrementAndGet()
            error("Network is unavailable in this scenario")
        }, calendar = CalendarSupplements {
            calendarRequests.incrementAndGet()
            error("Calendar network is unavailable in this scenario")
        })
        // A new repository must reconstruct the whole immutable edition from disk.
        val offline = runBlocking { offlineRepository().load(book.key) } as BookLoadResult.Ready
        assertTrue(offline.fromCache)
        assertEquals(book, offline.book)
        assertNotSame(book, offline.book)
        assertTrue(BookReadingStore(preferences).save(BookReadingPosition(book.key, book.contentHash, parish,
            target.id, book.chapters.first().id, fraction = .42f)))
        val originalText = target.original
        val openedSources = mutableListOf<String>()

        withCompactNativeDisplay {
            val books = offlineRepository()
            mounted.value = true
            compose.setContent {
                GuideTheme(false) {
                    if (mounted.value) ServiceBookScreen(book.key, parish, books, BookReadingStore(preferences), 0,
                        onBack = {}, onSettings = {}, onOpenUrl = { openedSources += it }, onOpenCommonLiturgy = {})
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
            settle()
            val before = saved()
            assertEquals(target.id, before.blockId)
            assertEquals(BookAnchorKind.BLOCK, before.anchorKind)
            assertTrue("The reading must reopen inside the prayer", before.fraction > .3f)
            val originalNode = compose.onNodeWithTag("book_text_${target.id}", useUnmergedTree = true).assertIsDisplayed()
            originalNode.assertTextEquals(originalText)
            val originalTop = originalNode.getUnclippedBoundsInRoot().top

            selectMode(BookReadingMode.MEANING)
            settle()
            val meaning = saved()
            assertEquals(BookReadingMode.MEANING, meaning.mode)
            assertSameAnchor(before, meaning)
            compose.onNodeWithTag("book_open_calendar").performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("book_calendar").assertIsDisplayed()
            calendarText("11 ноября 2026")
            calendarText(day.weekTitle)
            calendarText("Глас 6 - один из восьми напевов церковного круга")
            calendarText(day.commemorations.single().title)
            calendarText(description)
            val sourceButton = compose.onNodeWithTag("book_calendar").performScrollToNode(hasText("Открыть источник в Азбуке веры"))
            compose.onNodeWithText("Открыть источник в Азбуке веры").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(listOf(day.sourceUrl), openedSources) }
            sourceButton.captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.cacheDir, "book-calendar-320-150.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }

            pressBack()
            settle()
            compose.onNodeWithTag("book_calendar").assertDoesNotExist()
            assertEquals(BookReadingMode.MEANING, saved().mode)
            assertSameAnchor(before, saved())
            selectMode(BookReadingMode.ORIGINAL)
            settle()
            assertEquals(BookReadingMode.ORIGINAL, saved().mode)
            assertSameAnchor(before, saved())
            val returned = compose.onNodeWithTag("book_text_${target.id}", useUnmergedTree = true).assertIsDisplayed()
            returned.assertTextEquals(originalText)
            assertTrue("Closing calendar and Meaning must preserve the actual visible prayer line",
                abs((returned.getUnclippedBoundsInRoot().top - originalTop).value) <= 2f)

            // Dispose and reopen the route with the disk-backed position, not an in-memory cursor.
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
            settle()
            assertSameAnchor(before, saved())
            compose.onNodeWithTag("book_text_${target.id}", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(originalText)
            assertEquals(0, networkRequests.get())
            assertEquals(0, calendarRequests.get())
            assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        }
    }

    private fun selectMode(mode: BookReadingMode) {
        if (compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").performClick()
    }

    private fun calendarText(value: String) {
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasText(value))
        val node = compose.onNodeWithText(value, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(1.5f, layout.layoutInput.density.fontScale, .001f)
        assertTextFitsLayout(layout, value)
    }

    private fun saved() = requireNotNull(BookReadingStore(preferences).last(parish))
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun assertSameAnchor(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.key, actual.key)
        assertEquals(expected.contentHash, actual.contentHash)
        assertEquals(expected.chapterId, actual.chapterId)
        assertEquals(expected.blockId, actual.blockId)
        assertEquals(expected.anchorKind, actual.anchorKind)
        assertEquals(expected.fraction, actual.fraction, .001f)
    }

    private fun withCompactNativeDisplay(body: () -> Unit) {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val previousFont = shell("settings get system font_scale").toFloatOrNull()
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 1.5")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    ready = abs(it.resources.configuration.fontScale - 1.5f) < .001f &&
                        it.resources.configuration.screenWidthDp in 300..340 && it.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } finally {
            try {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            } finally {
                try { shell("wm size ${previousSize ?: "reset"}") }
                finally { shell(if (previousFont == null) "settings delete system font_scale" else "settings put system font_scale $previousFont") }
            }
        }
    }

    private fun fixture(): ServiceBook {
        // Reuse shipped prayer text. This fixture tests persistence/UI, not liturgical assembly.
        val assets = context.assets.open("liturgy_stages.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val prayers = assets.getAsJsonArray("stages").flatMap { stage ->
            stage.asJsonObject.getAsJsonArray("texts").mapNotNull { entry ->
                entry.asJsonObject.get("original")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
            }
        }.mapIndexed { index, original -> BookBlock("calendar-prayer-$index", BookBlockKind.PRAYER, null, original) }
        val date = "2026-11-11"
        val source = "https://azbyka.ru/days/$date"
        val key = ServiceBookKey(date)
        val fixture = ServiceBook(key = key, title = "Книга для проверки календаря", sourceUrl = key.sourceUrl(),
            sourceTitle = "Тест хранения опубликованных молитв", fetchedAtMillis = 1_794_348_000_000,
            selectedEventTitle = null, contentHash = "", chapters = listOf(BookChapter("calendar-reading", "Молитвы",
                "Проверяем, что пояснения дня не заменяют молитву и не перемещают место чтения", prayers)),
            calendarDay = BookCalendarDay(date, "Седмица 24-я по Пятидесятнице", 6,
                listOf(BookCommemoration("Память дня - тест сохранения", description, source)), source))
        return fixture.copy(contentHash = ServiceBookIntegrity.contentHash(fixture))
    }
}
