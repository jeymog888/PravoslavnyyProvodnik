package ru.liturgyguide.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.book.*
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual whole service, mounted reader and its modal; isolated storage only. */
@RunWith(AndroidJUnit4::class)
class BookAfterCommunionMeaningFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "after-communion-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val mounted = mutableStateOf(false)
    private var launched = false
    private val html = instrumentation.context.assets.open("russian-weekday-antiphons/liturgy-2026-11-13.html")
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private val book = ServiceBookParser().parse(Jsoup.parse(html), ServiceBookKey("2026-11-13"), 1L)
    private val chapter = book.chapters.last()
    private val target = chapter.blocks.single { BookText.normalized(it.original).startsWith("богородичен предстательство христиан") }
    private val parish = "isolated-after-communion"

    @After fun clean() {
        if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun originalLightKeepsTheActualPrayerAndReturnsToTheSameLine() = verify(false, false, BookReadingMode.ORIGINAL)
    @Test fun russianDarkKeepsTheActualPrayerAndReturnsToTheSameLine() = verify(true, false, BookReadingMode.RUSSIAN)
    @Test fun originalDarkAt320AndDoubleFontKeepsReadableHelpAndFixedReturn() = verify(true, true, BookReadingMode.ORIGINAL)
    @Test fun russianLightAt320AndDoubleFontKeepsReadableHelpAndFixedReturn() = verify(false, true, BookReadingMode.RUSSIAN)

    private fun verify(dark: Boolean, compact: Boolean, mode: BookReadingMode) = withWindow(compact) {
        assertEquals("9edee9dea1ba23d3c468b48ff8471b7641618d10e760f80f25817885f7145354", BookText.sha256(html))
        val serialized = Gson().toJson(book)
        val cache = FileBookCache(directory)
        assertTrue(cache.write(book))
        val disk = File(directory, "${BookText.sha256(book.key.canonical()).take(32)}-${book.contentHash}.json")
        val bytes = disk.readBytes()
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parish, target.id, chapter.id,
            fraction = .2f, mode = mode, lastTextMode = mode)))
        compose.setContent { GuideTheme(dark) {
            if (mounted.value) BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {},
                onChooseVersion = {}, onSettings = {})
        } }
        launched = true
        compose.runOnIdle { mounted.value = true }; settle()
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Молитва о заступничестве Богородицы")
        val before = requireNotNull(store.last(parish))
        val beforeTop = compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.onNodeWithTag("book_meaning_title").assertTextEquals("Молитва о заступничестве Богородицы")
        capture("${mode.name}-$dark-$compact-overview")
        compose.onNodeWithTag("book_meaning_what").performScrollTo()
            .assertTextEquals("Богородицу просят не оставить молитвы людей и поспешить им на помощь")
        compose.onNodeWithTag("book_meaning_cue").assertDoesNotExist()
        compose.onNodeWithTag("book_meaning_more").performScrollTo().performClick()
        compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextContains("после Причащения", substring = true)
        val close = compose.onNodeWithTag("book_meaning_close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val bounds = close.getBoundsInRoot()
        assertTrue(compose.onNodeWithTag("book_meaning_content").getBoundsInRoot().bottom <= bounds.top)
        capture("${mode.name}-$dark-$compact-details")
        assertEquals(before, store.last(parish))
        close.performClick(); settle()
        assertEquals(before, store.last(parish))
        val returnedTop = compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot().top
        assertTrue(abs((returnedTop - beforeTop).value) <= 2f)
        assertEquals(serialized, Gson().toJson(book))
        assertArrayEquals(bytes, disk.readBytes())
        compose.runOnIdle { mounted.value = false }; settle()
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        assertEquals(before, BookReadingStore(preferences).last(parish))
    }

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun capture(label: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("book_meaning_sheet").captureToImage()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val folder = File(context.filesDir, "meaning-tail-native/$name").apply { mkdirs() }
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stableFrames = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (stableFrames < 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(250)
            val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            stableFrames = if (bitmap.sameAs(next)) stableFrames + 1 else 0
            bitmap.recycle()
            bitmap = next
        }
        check(stableFrames >= 2) { "Native frame did not settle" }
        try { File(folder, "$label.png").outputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        } } finally { bitmap.recycle() }
    }
    private fun withWindow(compact: Boolean, body: () -> Unit) {
        if (!compact) { body(); return }
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
                compose.activityRule.scenario.onActivity { activity -> ready = abs(activity.resources.configuration.fontScale - 2f) < .001f &&
                    activity.resources.configuration.screenWidthDp in 300..340 && activity.window.decorView.hasWindowFocus() }
                ready
            }
            body()
        } finally {
            if (launched) { compose.runOnIdle { mounted.value = false }; settle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == null) "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
}
