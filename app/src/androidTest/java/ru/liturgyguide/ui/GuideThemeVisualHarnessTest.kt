package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.book.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Visual theme witness, not a network or liturgical-content acceptance test.
 * Uses the real persisted public book when available; the manifest explicitly identifies fallback data.
 * Only this test's preferences and files/theme-10 artifacts are written. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // Frame-commit callbacks and public window inspection are available from Q.
class GuideThemeVisualHarnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferenceName = "theme-10-${UUID.randomUUID()}"
    private val store = BookReadingStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
    private val mounted = mutableStateOf(false)
    private val screen = mutableStateOf("today")
    private val parishId = "theme-10-parish"
    private lateinit var output: File
    private val captures = mutableListOf<String>()
    private val captureDetails = org.json.JSONArray()
    private var expectedDark = false

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun lightThemeAtNative320DpAnd200Percent() = exercise(false)
    @Test fun darkThemeAtNative320DpAnd200Percent() = exercise(true)

    private fun exercise(dark: Boolean) {
        expectedDark = dark
        val theme = if (dark) "dark" else "light"
        output = File(context.filesDir, "theme-10/$theme-320dp-200pct").apply { mkdirs() }
        val cached = FileBookCache(File(context.filesDir, "service-books-v2"))
            .read(ServiceBookKey("2026-11-12"))?.takeIf { book ->
                book.blocks.any { !it.isAltarText && (it.russian?.length ?: 0) > 500 && it.original.length > 500 }
            }
        val book = cached ?: fallbackBook()
        val date = LocalDate.parse(book.key.date)
        val morning = ParishService(date.toString(), "08:00",
            "Сщмч. Зиновия, еп. Егейского, и сестры его мц. Зиновии. Часы, Литургия", "service_block")
        // A fixed schedule fixture makes both themes directly comparable without network/time changes.
        val parish = Parish(parishId, "Храм Троицы Живоначальной в Листах", "Храм Троицы Живоначальной в Листах",
            "Москва", "улица Сретенка, 27 с3", "", "", "Europe/Moscow", "", "",
            date.toString(), date.toString(), listOf(morning), "", "")
        val now = date.atTime(7, 30).atZone(ZoneId.of(parish.timezone)).toInstant()
        val readingTarget = book.chapters.flatMap { chapter -> chapter.blocks.map { chapter to it } }
            .first { (_, block) -> !block.isAltarText && (block.russian?.length ?: 0) > 500 && block.original.length > 500 }
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId,
            readingTarget.second.id, readingTarget.first.id, fraction = .2f)))
        val report = JSONObject().put("theme", theme).put("requestedWidthDp", 320).put("requestedFontScale", 2.0)
            .put("bookOrigin", if (cached != null) "public_book_from_app_cache" else "shipped_legacy_UI_fixture_not_content_validation")
            .put("bookSourceUrl", book.sourceUrl).put("bookHash", book.contentHash)
            .put("scheduleOrigin", "fixed_visual_fixture_not_live_schedule_validation")
            .put("captureSource", "frame commit + active Window PixelCopy, then two identical native screenshots")
            .put("windowImageSuffix", "-window.png: current app/dialog pixels; primary .png also includes system bars and popups")
            .put("intentionalEllipsisExcludedFromTextCheck", "book_chapter: compact current-section summary")
        try {
            withNativeCompactDisplay {
                report.put("actualWidthDp", compose.activity.resources.configuration.screenWidthDp)
                    .put("actualFontScale", compose.activity.resources.configuration.fontScale)
                mounted.value = true
                compose.setContent { GuideTheme(dark) {
                    if (mounted.value) when (screen.value) {
                        "today" -> TodayScreen(parish, now, null, null, null,
                            onFindChurch = {}, onChurchDetails = { screen.value = "church" },
                            onOpenBook = { requestedDate, kind, _ ->
                                assertEquals(date, requestedDate); assertEquals(WorshipKind.LITURGY, kind)
                                screen.value = "reader"
                            }, onResumeBook = { screen.value = "reader" }, onRefresh = {}, onResumeLegacy = null,
                            bottomBar = { GuideBottomBar("home", { screen.value = "today" }, { screen.value = "church" }, {}) })
                        "church" -> ChurchScreen(listOf(parish), parish.id, date,
                            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
                            onOpenUrl = {}, onBack = { screen.value = "today" }, showBack = false,
                            bottomBar = { GuideBottomBar("church", { screen.value = "today" }, { screen.value = "church" }, {}) },
                            searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = { screen.value = "reader" },
                            onOpenService = { selected -> assertEquals(morning, selected); screen.value = "reader" }, now = now)
                        else -> BookReaderScreen(book, parishId, store, 0, onBack = { screen.value = "church" },
                            onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
                    }
                } }
                settle()
                capture("01-today-top")
                for (tag in listOf("nav_home", "nav_church", "open_settings")) target(tag)
                scroll("main_content", "change_church").assertHeightIsAtLeast(48.dp)
                scroll("main_content", "open_service_book").assertHeightIsAtLeast(48.dp)
                capture("02-today-schedule")
                target("nav_church").performClick()
                settle()
                capture("03-my-church-top")
                scroll("church_content", "change_church").assertHeightIsAtLeast(48.dp)
                scroll("church_content", "church_open_service").assertHeightIsAtLeast(48.dp)
                capture("04-my-church-schedule")
                compose.onNodeWithTag("church_open_service").performClick()
                settle()
                val before = saved()
                assertEquals(readingTarget.second.id, before.blockId)
                assertEquals(BookReadingMode.ORIGINAL, before.mode)
                for (tag in listOf("book_find", "book_play", "book_microphone")) target(tag)
                compose.onNodeWithTag("book_text_${readingTarget.second.id}", useUnmergedTree = true)
                    .assertTextEquals(readingTarget.second.original).assertIsDisplayed()
                assertVisibleMode(BookReadingMode.ORIGINAL)
                capture("05-reader-original")
                selectMode(BookReadingMode.RUSSIAN, captureMenu = true)
                assertAnchor(before, saved())
                compose.onNodeWithTag("book_text_${readingTarget.second.id}", useUnmergedTree = true)
                    .assertTextEquals(requireNotNull(readingTarget.second.russian)).assertIsDisplayed()
                capture("06-reader-russian")
                selectMode(BookReadingMode.MEANING)
                assertAnchor(before, saved())
                compose.onNodeWithTag("book_text_${readingTarget.second.id}", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText(readingTarget.first.meaning, useUnmergedTree = true).assertIsDisplayed()
                capture("07-reader-meaning")
                selectMode(BookReadingMode.ORIGINAL)
                assertAnchor(before, saved())

                // Opening the actual day sheet must preserve the visible place from which it opens.
                compose.onNodeWithTag("book_content").performScrollToIndex(0)
                scroll("book_content", "book_open_calendar").assertHeightIsAtLeast(48.dp)
                settle()
                val beforeCalendar = saved()
                compose.onNodeWithTag("book_open_calendar").performClick()
                settle()
                capture("08-day-and-service")
                scroll("book_calendar", "book_calendar_selected_event", required = !book.selectedEventTitle.isNullOrBlank())
                val calendar = book.calendarDay
                if (calendar != null && calendar.commemorations.isNotEmpty()) {
                    scroll("book_calendar", "book_calendar_commemorations").assertHeightIsAtLeast(48.dp).performClick()
                    val entry = calendar.commemorations.indexOfFirst { !it.description.isNullOrBlank() }
                    if (entry >= 0) {
                        scroll("book_calendar", "book_calendar_expand_$entry").assertHeightIsAtLeast(48.dp).performClick()
                        scroll("book_calendar", "book_calendar_description_$entry")
                        capture("09-calendar-description")
                    }
                    scroll("book_calendar", "book_calendar_commemorations").performClick()
                    compose.onNodeWithTag("book_calendar_commemoration_0").assertDoesNotExist()
                    capture("10-calendar-collapsed")
                }
                pressBack()
                settle()
                assertAnchor(beforeCalendar, saved())
                assertEquals(BookReadingMode.ORIGINAL, saved().mode)
                report.put("status", "PASS")
            }
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failure", failure.toString())
            throw failure
        } finally {
            report.put("captures", org.json.JSONArray(captures)).put("captureDetails", captureDetails)
            File(output, "manifest.json").writeText(report.toString(2))
        }
    }

    private fun target(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

    private fun scroll(list: String, tag: String, required: Boolean = true): SemanticsNodeInteraction {
        if (required) compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(if (required) tag else list).also { if (required) it.performScrollTo().assertIsDisplayed() }
    }

    private fun selectMode(mode: BookReadingMode, captureMenu: Boolean = false) {
        if (compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty()) {
            target("book_mode_menu").performClick()
            for (value in BookReadingMode.entries) checkText("book_mode_menu_label_${value.name.lowercase()}", value.title, singleLine = true)
            if (captureMenu) capture("reader-language-menu")
        } else {
            for (value in BookReadingMode.entries) checkText("book_mode_label_${value.name.lowercase()}", value.title, singleLine = true)
        }
        target("book_mode_${mode.name.lowercase()}").performClick()
        settle()
        assertEquals(mode, saved().mode)
        assertVisibleMode(mode)
    }

    private fun assertVisibleMode(mode: BookReadingMode) {
        if (compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").assertIsSelected()
        } else if (compose.onAllNodesWithTag("book_mode_menu_label", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("book_mode_menu_label", useUnmergedTree = true).assertTextEquals(mode.title).assertIsDisplayed()
        } else {
            compose.onNodeWithContentDescription("Режим чтения: ${mode.title}").assertIsDisplayed()
        }
    }

    private fun checkText(tag: String, expected: String, singleLine: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTextFitsLayout(layouts.single(), expected, singleLine)
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, .001f)
    }

    private fun capture(name: String) {
        settle()
        val appearance = captureRaw(name)
        assertEquals("$name: active ${appearance.getString("window")} status-bar appearance",
            !expectedDark, appearance.getBoolean("lightStatusBars"))
        assertEquals("$name: active ${appearance.getString("window")} navigation-bar appearance",
            !expectedDark, appearance.getBoolean("lightNavigationBars"))
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        for (i in nodes.fetchSemanticsNodes().indices) {
            val node = nodes[i]
            if (!node.isDisplayed()) continue
            if (node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.TestTag) { "" } == "book_chapter") continue
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            for (layout in layouts) {
                assertTextFitsLayout(layout, layout.layoutInput.text.text)
                assertEquals(2f, layout.layoutInput.density.fontScale, .001f)
            }
        }
    }

    /** Compose idle does not guarantee that SurfaceFlinger has received the new frame.
     * PixelCopy is an independent witness of the committed current window; the primary
     * native image additionally shows system chrome and transient dropdown windows. */
    private fun captureRaw(name: String): JSONObject {
        instrumentation.waitForIdleSync()
        val window = activeWindow()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue("Visual harness requires hardware acceleration", window.decorView.isHardwareAccelerated)
            window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            window.decorView.invalidate()
        }
        assertTrue("$name: render frame was not committed", committed.await(5, TimeUnit.SECONDS))
        val copied = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN
        val windowBitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, windowBitmap, { result -> copyResult = result; copied.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("$name: PixelCopy did not finish", copied.await(5, TimeUnit.SECONDS))
            assertEquals("$name: PixelCopy error", PixelCopy.SUCCESS, copyResult)
            writeBitmap("$name-window.png", windowBitmap)
        } finally { windowBitmap.recycle() }

        var native = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            for (attempt in 0 until 6) {
                SystemClock.sleep(100)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                stable = native.sameAs(next)
                native.recycle()
                native = next
                if (stable) break
            }
            writeBitmap("$name.png", native)
        } finally { native.recycle() }
        val appearance = JSONObject().put("name", name).put("stableSystemFrame", stable)
        instrumentation.runOnMainSync {
            val decor = window.decorView
            val lightStatus: Boolean
            val lightNavigation: Boolean
            if (Build.VERSION.SDK_INT >= 30) {
                val flags = requireNotNull(decor.windowInsetsController).systemBarsAppearance
                lightStatus = flags and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0
                lightNavigation = flags and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
            } else {
                @Suppress("DEPRECATION") val flags = decor.systemUiVisibility
                lightStatus = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
                lightNavigation = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0
            }
            appearance.put("window", if (window === compose.activity.window) "activity" else "dialog")
                .put("lightStatusBars", lightStatus).put("lightNavigationBars", lightNavigation)
        }
        captureDetails.put(appearance)
        assertTrue("$name: native display did not become stable; see captured frame", stable)
        return appearance
    }

    private fun writeBitmap(name: String, bitmap: Bitmap) {
        File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        captures += name
    }

    private fun activeWindow(): Window {
        fun provider(view: View): DialogWindowProvider? {
            if (view is DialogWindowProvider) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) provider(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var result: Window
        instrumentation.runOnMainSync {
            // Material sheets own a separate window. Small popup windows do not own
            // the status bar and must not hide the underlying full-screen owner.
            result = WindowInspector.getGlobalWindowViews().filter { it.isShown }
                .mapNotNull { provider(it)?.window }.lastOrNull() ?: compose.activity.window
        }
        return result
    }

    private fun saved() = requireNotNull(store.last(parishId))
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun assertAnchor(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.key, actual.key); assertEquals(expected.contentHash, actual.contentHash)
        assertEquals(expected.chapterId, actual.chapterId); assertEquals(expected.blockId, actual.blockId)
        assertEquals(expected.anchorKind, actual.anchorKind); assertEquals(expected.fraction, actual.fraction, .01f)
    }

    private fun withNativeCompactDisplay(body: () -> Unit) {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val previousFont = shell("settings get system font_scale").toFloatOrNull()
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    ready = abs(it.resources.configuration.fontScale - 2f) < .001f &&
                        it.resources.configuration.screenWidthDp in 300..340 && it.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } catch (failure: Throwable) {
            // Preserve the failing UI before the finally block unmounts and restores display settings.
            runCatching { captureRaw("failure") }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            try { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
            finally {
                try { shell("wm size ${previousSize ?: "reset"}") }
                finally { shell(if (previousFont == null) "settings delete system font_scale" else "settings put system font_scale $previousFont") }
            }
        }
    }

    private fun fallbackBook(): ServiceBook {
        val source = context.assets.open("liturgy_stages.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
        val chapters = source.getAsJsonArray("stages").mapNotNull { value ->
            val stage = value.asJsonObject
            val id = requireNotNull(stage.text("id"))
            val blocks = stage.getAsJsonArray("texts").mapIndexedNotNull { index, text ->
                val item = text.asJsonObject
                val original = item.text("original") ?: return@mapIndexedNotNull null
                BookBlock("theme-$id-$index", BookBlockKind.PRAYER, null, original,
                    russian = item.text("translation"), meaning = item.text("explanation"))
            }
            if (blocks.isEmpty()) null else BookChapter("theme-$id", requireNotNull(stage.text("name")),
                stage.text("meaning") ?: stage.text("description").orEmpty(), blocks)
        }
        val key = ServiceBookKey("2026-11-12")
        val calendarSource = "https://azbyka.ru/days/${key.date}"
        val book = ServiceBook(key = key, title = "Литургия Иоанна Златоуста", sourceUrl = key.sourceUrl(),
            sourceTitle = "Тест интерфейса на ресурсах приложения", fetchedAtMillis = 1,
            selectedEventTitle = "Священномученик Зиновий и мученица Зиновия", chapters = chapters, contentHash = "",
            calendarDay = BookCalendarDay(key.date, "Седмица 24-я по Пятидесятнице", 6,
                listOf(BookCommemoration("Тестовая память дня", "Описание для проверки раскрытия карточки и крупного текста", calendarSource)), calendarSource))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
}
