package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/** Opt-in real archive journey. Reader/repository recreation is not an app-process restart. */
@RunWith(AndroidJUnit4::class)
class SavedBookReadingJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "saved-reading-journey-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val cacheDirectory = context.cacheDir.resolve(preferenceName)
    private val store = mutableStateOf(BookReadingStore(preferences))
    private val books = mutableStateOf<ServiceBooks?>(null)
    private val mounted = mutableStateOf(true)
    private val networkAttempts = AtomicInteger()
    private val parish = "isolated-saved-reading-journey"
    private var repositoryInstances = 0
    private var contentSet = false

    @After fun clean() {
        if (contentSet) {
            compose.runOnUiThread { mounted.value = false }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(preferenceName)
        cacheDirectory.deleteRecursively()
    }

    @Test fun savedBookAt320DpWithRequested200PercentDarkTheme() = journey(320, 2f, true)

    @Test fun savedBookAt360DpWithRequested100PercentLightTheme() = journey(360, 1f, false)

    private fun journey(width: Int, scale: Float, dark: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("savedReadingBookFile")
        assumeTrue("Supply an isolated copy of a genuine saved ServiceBook JSON", path != null)
        val archive = File(requireNotNull(path))
        assertTrue("Archive must be a bounded, existing book", archive.isFile && archive.length() in 1L..12_000_000L)
        val originalBytes = archive.readBytes()
        val book = Gson().fromJson(originalBytes.toString(Charsets.UTF_8), ServiceBook::class.java)
        assertTrue("Keep the original Android archive hash, without repairing it in the harness", ServiceBookIntegrity.valid(book))
        val samples = samples(book)
        val run = args.getString("savedReadingJourneyRun") ?: "local-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val profile = "${width}dp-font${(scale * 100).toInt()}-${if (dark) "dark" else "light"}"
        val output = File(requireNotNull(context.getExternalFilesDir("reading-journey")), "$run/$profile")
        check(output.mkdirs() || output.isDirectory)
        val transitions = JSONArray()
        val dragMeasurements = JSONArray()
        val screenshots = JSONArray()
        val report = JSONObject().put("profile", profile).put("archiveSha256", sha256(originalBytes))
            .put("bookHash", book.contentHash).put("date", book.key.date).put("churchType", book.key.churchType)
            .put("fontScaleMechanism", "Requested LocalDensity scale; not a changed Android system font setting")
            .put("reopenScope", "Unmount reader, create new repository/cache/store wrappers, remount; not process death")
            .put("networkScope", "Repository transport throws and counts every attempted request; system radios are not changed")
            .put("transitions", transitions).put("dragMeasurements", dragMeasurements)
            .put("screenshots", screenshots).put("allPassed", false)
        fun writeReport() {
            report.put("networkAttempts", networkAttempts.get()).put("repositoryInstances", repositoryInstances)
                .put("archiveUnchanged", originalBytes.contentEquals(archive.readBytes()))
            File(output, "receipt.json").writeText(report.toString(2))
        }
        writeReport()
        try {
            assertTrue(FileBookCache(cacheDirectory).write(book))
            assertEquals(book, FileBookCache(cacheDirectory).readVersion(book.key, book.contentHash))
            val first = samples.first()
            assertTrue(store.value.save(BookReadingPosition(book.key, book.contentHash, parish, first.block.id,
                first.chapter.id, fraction = .27f, mode = BookReadingMode.ORIGINAL)))
            books.value = newOfflineRepository()
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    GuideTheme(dark) {
                        Box(Modifier.size(width.dp, 640.dp).testTag("saved_reading_journey_host")) {
                            if (mounted.value) ServiceBookScreen(book.key, parish, requireNotNull(books.value), store.value, 0,
                                onBack = {}, onSettings = {}, onOpenUrl = { error("Journey must not open an external source") },
                                onOpenCommonLiturgy = { error("The saved dated book must open") })
                        }
                    }
                }
            }
            contentSet = true
            awaitReader()
            compose.onNodeWithTag("saved_reading_journey_host").assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(640.dp)
            compose.onNodeWithTag("book_content").assertHeightIsAtLeast(160.dp)
            assertTextPassage(first, BookReadingMode.ORIGINAL)
            assertEquals("Initial reentry must restore the seeded point within the prayer",
                .27f, saved().fraction, 2f / blockHeightPx(first.block.id) + .0001f)
            report.put("continuousScroll", crossChapterByTouch(book))
            writeReport()

            val sequence = listOf(BookReadingMode.RUSSIAN, BookReadingMode.MEANING, BookReadingMode.RUSSIAN,
                BookReadingMode.ORIGINAL, BookReadingMode.MEANING, BookReadingMode.ORIGINAL)
            for ((sampleIndex, sample) in samples.withIndex()) {
                // The real list scrolls to a source block; a real drag establishes a nonzero offset.
                compose.onNodeWithTag("book_content").performScrollToIndex(itemIndex(book, sample.block.id))
                settle()
                val beforeDrag = dragGeometry(sample.block.id)
                val measurement = JSONObject().put("sample", sampleIndex).put("chapterId", sample.chapter.id)
                    .put("blockId", sample.block.id).put("before", beforeDrag.json()).put("confirmed", false)
                dragMeasurements.put(measurement)
                writeReport()
                dragUp(.16f)
                settle()
                val afterDrag = dragGeometry(sample.block.id)
                val baseline = saved()
                val movedPx = beforeDrag.blockTopPx - afterDrag.blockTopPx
                measurement.put("after", afterDrag.json()).put("movementPx", movedPx.toDouble())
                writeReport() // Geometry remains available even if the following assertion fails.
                assertTextPassage(sample, BookReadingMode.ORIGINAL)
                assertEquals(sample.block.id, beforeDrag.position.blockId)
                assertEquals(sample.chapter.id, beforeDrag.position.chapterId)
                assertEquals(BookAnchorKind.BLOCK, beforeDrag.position.anchorKind)
                assertEquals(BookAnchorKind.BLOCK, baseline.anchorKind)
                assertTrue("A real drag must move this rendered block by at least four physical pixels", movedPx >= 4f)
                assertTrue("The same prayer must remain partially scrolled", baseline.fraction > 0f && baseline.fraction < .85f)
                assertEquals("A drag must not resize the prayer", beforeDrag.blockHeightPx, afterDrag.blockHeightPx, 1f)
                assertEquals("A drag must not move the viewport itself", beforeDrag.viewportTopPx, afterDrag.viewportTopPx, 1f)
                assertEquals("Speaker spacing belongs inside the block and must stay unchanged",
                    beforeDrag.textInsetPx, afterDrag.textInsetPx, 1f)
                assertEquals("Before drag: saved cursor must agree with rendered block geometry",
                    beforeDrag.clippedOffsetPx, beforeDrag.position.fraction * beforeDrag.blockHeightPx, 2f)
                assertEquals("After drag: saved cursor must agree with rendered block geometry",
                    afterDrag.clippedOffsetPx, baseline.fraction * afterDrag.blockHeightPx, 2f)
                assertEquals("Cursor movement must account for the actual pixel displacement", movedPx,
                    baseline.fraction * afterDrag.blockHeightPx - beforeDrag.position.fraction * beforeDrag.blockHeightPx, 3f)
                measurement.put("confirmed", true)
                writeReport()
                var minimumBlockHeightPx = afterDrag.blockHeightPx
                if (sampleIndex == 0) capture(output, "original", screenshots)
                for ((index, mode) in sequence.withIndex()) {
                    selectMode(mode)
                    if (mode == BookReadingMode.MEANING) assertMeaning(sample)
                    else {
                        assertTextPassage(sample, mode)
                        minimumBlockHeightPx = min(minimumBlockHeightPx, blockHeightPx(sample.block.id))
                    }
                    val current = saved()
                    assertEquals(baseline.contentHash, current.contentHash)
                    assertEquals(baseline.chapterId, current.chapterId)
                    assertEquals(baseline.blockId, current.blockId)
                    assertEquals(BookAnchorKind.BLOCK, current.anchorKind)
                    assertEquals(mode, current.mode)
                    // Each layout can round down by one physical pixel. Bound accumulated
                    // drift against the initial anchor, not only against the previous step.
                    val tolerance = (2f * (index + 1) + 1f) / minimumBlockHeightPx + .0001f
                    assertEquals("Mode changes must retain the initial fragment", baseline.fraction, current.fraction, tolerance)
                    assertStill(sample, mode)
                    transitions.put(JSONObject().put("sample", sampleIndex).put("step", index + 1)
                        .put("chapterId", sample.chapter.id).put("blockId", sample.block.id).put("mode", mode.name)
                        .put("initialFraction", baseline.fraction.toDouble()).put("fraction", current.fraction.toDouble())
                        .put("allowedFractionDrift", tolerance.toDouble()))
                    if (sampleIndex == 0 && index < 2) capture(output, mode.name.lowercase(), screenshots)
                    writeReport()
                }
            }
            assertEquals("Five real passages, six actual mode transitions each", 30, transitions.length())

            // A separate final selection keeps the offline acceptance in Russian, as in the user flow.
            selectMode(BookReadingMode.RUSSIAN)
            val last = samples.last()
            assertTextPassage(last, BookReadingMode.RUSSIAN)
            val before = saved()
            val beforeTop = paragraphTop(last.block.id)
            compose.runOnIdle { mounted.value = false }
            settle()
            assertEquals(before, saved())
            assertEquals(book, FileBookCache(cacheDirectory).readVersion(book.key, book.contentHash))
            compose.runOnIdle {
                store.value = BookReadingStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
                books.value = newOfflineRepository()
                mounted.value = true
            }
            awaitReader()
            assertTextPassage(last, BookReadingMode.RUSSIAN)
            val after = saved()
            val tolerance = 2f / blockHeightPx(last.block.id) + .0001f
            assertEquals(before.copy(fraction = after.fraction), after)
            assertEquals(before.fraction, after.fraction, tolerance)
            assertTrue("Reopened text must return to the same visible line",
                abs(paragraphTop(last.block.id) - beforeTop) <= 2f / physicalDensity())
            assertStill(last, BookReadingMode.RUSSIAN)
            capture(output, "reopened-russian", screenshots)
            assertEquals("Every attempted network request is a harness failure", 0, networkAttempts.get())
            assertArrayEquals("Input archive must remain unchanged", originalBytes, archive.readBytes())
            report.put("modeTransitions", transitions.length()).put("additionalFinalRussianSelection", 1)
                .put("reopenedPosition", JSONObject().put("blockId", after.blockId).put("chapterId", after.chapterId)
                    .put("fraction", after.fraction.toDouble()).put("mode", after.mode.name).put("contentHash", after.contentHash))
                .put("allPassed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.name).put("failure", failure.message?.take(500))
            throw failure
        } finally { writeReport() }
    }

    private data class Sample(val chapter: BookChapter, val block: BookBlock)

    private data class DragGeometry(val blockTopPx: Float, val blockHeightPx: Float,
        val textInsetPx: Float, val viewportTopPx: Float, val position: BookReadingPosition) {
        val clippedOffsetPx get() = (viewportTopPx - blockTopPx).coerceAtLeast(0f)
        fun json() = JSONObject().put("blockTopPx", blockTopPx.toDouble()).put("blockHeightPx", blockHeightPx.toDouble())
            .put("textInsetPx", textInsetPx.toDouble()).put("viewportTopPx", viewportTopPx.toDouble())
            .put("clippedOffsetPx", clippedOffsetPx.toDouble()).put("fraction", position.fraction.toDouble())
            .put("savedBlockId", position.blockId).put("savedChapterId", position.chapterId)
    }

    private fun dragGeometry(blockId: String): DragGeometry {
        val block = compose.onNodeWithTag("book_block_$blockId").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag("book_text_$blockId", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val density = physicalDensity()
        // Lazy item offsets refer to the entire block, including speaker/translation labels,
        // not the prayer Text's inset. The reading viewport already excludes Scaffold padding.
        return DragGeometry(block.top.value * density, (block.bottom - block.top).value * density,
            (text.top - block.top).value * density, viewport.top.value * density, saved())
    }

    private fun samples(book: ServiceBook): List<Sample> {
        val candidates = book.chapters.dropLast(1).mapNotNull { chapter ->
            chapter.blocks.firstOrNull { !it.isAltarText && it.kind == BookBlockKind.PRAYER && it.translationSource != null &&
                it.original.length >= 450 && (it.russian?.length ?: 0) >= 450 }?.let { Sample(chapter, it) }
        }
        assertTrue("The real archive needs five distinct chapters with long published parallel prayers", candidates.size >= 5)
        return (0..4).map { candidates[it * (candidates.size - 1) / 4] }.also {
            assertEquals(5, it.map { sample -> sample.chapter.id }.distinct().size)
        }
    }

    private fun newOfflineRepository(): ServiceBooks {
        repositoryInstances++
        return ServiceBookRepository(FileBookCache(cacheDirectory), ScheduleTransport {
            networkAttempts.incrementAndGet()
            error("A saved-book journey must not request the network")
        }, downloadRussian = false)
    }

    private fun crossChapterByTouch(book: ServiceBook): JSONObject {
        val visible = book.chapters.filter { chapter -> chapter.blocks.any { !it.isAltarText } }
        val (previous, next) = requireNotNull(visible.zipWithNext().firstOrNull { (before, after) ->
            before.blocks.last { !it.isAltarText }.original.length <= 100 &&
                after.blocks.first { !it.isAltarText }.let { it.kind == BookBlockKind.PRAYER && it.original.length >= 1000 }
        }) { "Archive needs a short-to-long adjacent chapter boundary for a genuine touch scroll" }
        val previousBlock = previous.blocks.last { !it.isAltarText }
        compose.onNodeWithTag("book_content").performScrollToIndex(itemIndex(book, previousBlock.id))
        settle()
        assertEquals(previous.id, saved().chapterId)
        var drags = 0
        while (saved().chapterId == previous.id && drags < 8) {
            dragUp(.25f)
            settle()
            drags++
        }
        assertTrue("Touch input must actually cross a section boundary", drags > 0)
        assertEquals("Scroll must move to the adjacent source chapter", next.id, saved().chapterId)
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(next.title)
        compose.onNodeWithTag("book_block_${next.blocks.first { !it.isAltarText }.id}").assertIsDisplayed()
        return JSONObject().put("fromChapter", previous.id).put("toChapter", next.id).put("touchDrags", drags)
    }

    private fun itemIndex(book: ServiceBook, blockId: String): Int {
        val ids = book.chapters.filter { chapter -> chapter.blocks.any { !it.isAltarText } }.flatMap { chapter ->
            listOf("chapter:${chapter.id}") + chapter.blocks.filter { !it.isAltarText }.map { it.id }
        }
        return ids.indexOf(blockId).also { require(it >= 0) }
    }

    private fun dragUp(fraction: Float) {
        compose.onNodeWithTag("book_content").performTouchInput {
            swipe(Offset(centerX, height * .8f), Offset(centerX, height * (.8f - fraction)), durationMillis = 800)
        }
    }

    private fun selectMode(mode: BookReadingMode) {
        assertNotEquals("Each counted selection must change the mode", mode, saved().mode)
        if (compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").assertIsDisplayed().performClick()
        settle()
        val menu = compose.onAllNodesWithTag("book_mode_menu_label", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val label = compose.onNodeWithTag(if (menu) "book_mode_menu_label" else "book_mode_label_${mode.name.lowercase()}",
            useUnmergedTree = true).assertIsDisplayed().assertTextEquals(mode.title)
        assertTextFitsLayout(layout(label), mode.title, singleLine = true)
        assertEquals(mode, saved().mode)
    }

    private fun assertTextPassage(sample: Sample, mode: BookReadingMode) {
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(sample.chapter.title)
        compose.onNodeWithTag("book_block_${sample.block.id}").assertIsDisplayed()
        val node = compose.onNodeWithTag("book_text_${sample.block.id}", useUnmergedTree = true).assertIsDisplayed()
        val expected = if (mode == BookReadingMode.RUSSIAN) requireNotNull(sample.block.russian) else sample.block.original
        assertEquals("Reader must display the archived text in the selected language",
            BookText.sha256(expected), BookText.sha256(layout(node).layoutInput.text.text))
        assertEquals(sample.block.id, saved().blockId)
        assertEquals(sample.chapter.id, saved().chapterId)
        assertEquals(mode, saved().mode)
        compose.onNodeWithTag("book_save_error").assertDoesNotExist()
    }

    private fun assertMeaning(sample: Sample) {
        compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(sample.chapter.title)
        val viewport = compose.onNodeWithTag("book_content").fetchSemanticsNode().boundsInRoot
        assertTrue("The corresponding explanation must intersect the visible reading viewport",
            compose.onAllNodesWithText(sample.chapter.meaning, useUnmergedTree = true).fetchSemanticsNodes().any {
                val intersection = it.boundsInRoot.intersect(viewport)
                intersection.width > 0f && intersection.height > 0f
            })
    }

    private fun assertStill(sample: Sample, mode: BookReadingMode) {
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals(
            if (mode == BookReadingMode.MEANING) "Вернуться к тексту" else "Прокручивать текст")
        val before = saved()
        val top = if (mode == BookReadingMode.MEANING) null else paragraphTop(sample.block.id)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("An unstarted player must not move the saved cursor", before, saved())
        if (top != null) assertTrue("An unstarted player must not move the rendered line",
            abs(paragraphTop(sample.block.id) - top) <= 1f / physicalDensity())
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(result)) }
        return result.single()
    }

    private fun capture(output: File, name: String, screenshots: JSONArray) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("saved_reading_journey_host").captureToImage().asAndroidBitmap()
        val destination = File(output, "$name.png")
        try {
            destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots.put(JSONObject().put("file", destination.name).put("sha256", sha256(destination.readBytes()))
                .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
                .put("mode", saved().mode.name).put("chapterId", saved().chapterId).put("blockId", saved().blockId)
                .put("source", "Compose captureToImage of the tagged constrained host"))
        } finally { bitmap.recycle() }
    }

    private fun paragraphTop(id: String) = compose.onNodeWithTag("book_text_$id", useUnmergedTree = true)
        .assertIsDisplayed().getUnclippedBoundsInRoot().top.value
    private fun blockHeightPx(id: String): Float {
        val bounds = compose.onNodeWithTag("book_block_$id").getUnclippedBoundsInRoot()
        return (bounds.bottom - bounds.top).value * physicalDensity()
    }
    private fun physicalDensity() = compose.activity.resources.displayMetrics.density
    private fun saved() = requireNotNull(store.value.last(parish))
    private fun awaitReader() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
