package ru.liturgyguide.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Delayed saved-edition updates must not move a target that the reader is about to tap. */
@RunWith(AndroidJUnit4::class)
class BookUpdateOfferStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = "update-offer-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(owner, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val cacheDirectory = File(context.cacheDir, owner)
    private val cache = FileBookCache(cacheDirectory)
    private val mounted = mutableStateOf(true)
    private val parish = "update-offer-test-parish"
    private val old = fixture("Прежнее объяснение общей молитвы")
    private val newer = fixture("Новое объяснение общей молитвы")

    @After fun clean() {
        compose.runOnUiThread { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(owner)
        cacheDirectory.deleteRecursively()
    }

    @Test fun lightNormalDelayedCandidateKeepsTheMeasuredRussianTarget() = delayedCandidate(1f, false)
    @Test fun darkNormalDelayedCandidateKeepsTheMeasuredRussianTarget() = delayedCandidate(1f, true)
    @Test fun lightLargeDelayedCandidateKeepsTheMeasuredLanguageMenuTarget() = delayedCandidate(2f, false)
    @Test fun darkLargeDelayedCandidateKeepsTheMeasuredLanguageMenuTarget() = delayedCandidate(2f, true)

    private fun delayedCandidate(fontScale: Float, dark: Boolean) {
        assertTrue(cache.write(old))
        saveOriginalPosition()
        val oldFile = cacheDirectory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }
        val originalBytes = oldFile.readBytes()
        val delivery = CompletableDeferred<BookLoadResult.Ready?>()
        val requests = AtomicInteger()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey) = cache.read(key)
            override fun cachedVersion(key: ServiceBookKey, contentHash: String) = cache.readVersion(key, contentHash)
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                requests.incrementAndGet()
                error("The accepted edition and its candidate are already on this phone")
            }
            override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? =
                if (accepted.contentHash == old.contentHash) delivery.await() else null
        }
        compose.setContent {
            val nativeDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(nativeDensity.density, fontScale)) {
                GuideTheme(dark) {
                    if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                        ServiceBookScreen(old.key, parish, books, store, 0,
                            onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
                    }
                }
            }
        }
        awaitReader()
        val languageTag = if (fontScale >= 1.5f) "book_mode_menu" else "book_mode_russian"
        val before = saved()
        assertEquals(BookReadingMode.ORIGINAL, before.mode)
        assertEquals(old.contentHash, before.contentHash)
        val prefsBefore = preferences.all.toMap()
        val boundsBefore = readingBounds(languageTag)
        val oldLanguageBounds = boundsBefore.getValue(languageTag)
        compose.onNodeWithTag("book_reader_settings").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()

        compose.runOnIdle {
            assertTrue(cache.write(newer))
            delivery.complete(BookLoadResult.Ready(newer, fromCache = true))
        }
        awaitBadge()
        settle()
        assertSameBounds(boundsBefore, readingBounds(languageTag))
        assertEquals("An offer is not permission to replace the accepted cursor", before, saved())
        assertEquals(prefsBefore, preferences.all)
        assertArrayEquals(originalBytes, oldFile.readBytes())
        assertEquals(old, cache.readVersion(old.key, old.contentHash))
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()

        // Inject at the point measured BEFORE delivery, not at a freshly located replacement.
        tapOldRootPoint(oldLanguageBounds)
        if (fontScale >= 1.5f) compose.onNodeWithTag("book_mode_russian").assertIsDisplayed().performClick()
        settle()
        compose.onNodeWithTag("book_order_list").assertDoesNotExist()
        assertEquals(BookReadingMode.RUSSIAN, saved().mode)
        assertEquals(BookReadingMode.RUSSIAN, saved().lastTextMode)
        assertAnchor(before, saved())
        assertEquals(old.contentHash, saved().contentHash)
        assertArrayEquals(originalBytes, oldFile.readBytes())

        val beforeOptions = saved()
        compose.openBookUpdateOptions()
        assertEquals("Opening settings still must not accept", beforeOptions, saved())
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed().performClick()
        settle()
        val accepted = saved()
        assertEquals(newer.contentHash, accepted.contentHash)
        assertEquals(BookReadingMode.RUSSIAN, accepted.mode)
        assertAnchor(beforeOptions, accepted)
        assertEquals(newer, cache.readVersion(newer.key, newer.contentHash))
        assertEquals(old, cache.readVersion(old.key, old.contentHash))
        assertArrayEquals(originalBytes, oldFile.readBytes())
        assertEquals("No download or network route was needed", 0, requests.get())
        compose.onNodeWithTag("book_options").assertDoesNotExist()
        compose.onNodeWithTag("book_update_available", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun availabilityAndWithdrawalDoNotMoveTheOpenReaderOrAcceptAnything() {
        saveOriginalPosition()
        val available = mutableStateOf(false)
        val accepts = AtomicInteger()
        val accept: (BookReadingPosition) -> Boolean = { accepts.incrementAndGet(); false }
        mountDirect { if (available.value) accept else null }
        awaitReader()
        val before = saved()
        val prefsBefore = preferences.all.toMap()
        val boundsBefore = readingBounds("book_mode_russian")
        compose.runOnIdle { available.value = true }
        awaitBadge(); settle()
        assertSameBounds(boundsBefore, readingBounds("book_mode_russian"))
        compose.runOnIdle { available.value = false }
        settle()
        compose.onNodeWithTag("book_update_available", useUnmergedTree = true).assertDoesNotExist()
        assertSameBounds(boundsBefore, readingBounds("book_mode_russian"))
        assertEquals(before, saved())
        assertEquals(prefsBefore, preferences.all)
        assertEquals(0, accepts.get())
    }

    @Test fun aLateOfferAppendsToOpenOptionsAndWithdrawalRetainsItsDisabledSpace() = lateOptions(false)

    @Test fun aRealTallDialogKeepsItsSheetAnchorAndOldClickPointDuringLateUpdates() =
        withTallNativeDisplay { lateOptions(true) }

    private fun lateOptions(tallNativeWindow: Boolean) {
        saveOriginalPosition()
        val available = mutableStateOf(false)
        val accepts = AtomicInteger()
        val refreshes = AtomicInteger()
        val accept: (BookReadingPosition) -> Boolean = { accepts.incrementAndGet(); false }
        mountDirect(onRefresh = { refreshes.incrementAndGet() }) { if (available.value) accept else null }
        awaitReader()
        if (tallNativeWindow) assertTrue("The separate dialog needs a genuinely tall native window",
            compose.activity.resources.configuration.screenHeightDp >= 1200)
        val before = saved()
        compose.onNodeWithTag("book_reader_settings").performClick()
        settle()
        compose.onNodeWithTag("book_refresh").performScrollTo().assertIsDisplayed()
        val oldRefresh = compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot()
        val oldDetails = compose.onNodeWithTag("book_options_offline").getUnclippedBoundsInRoot()
        val oldSheet = compose.onNodeWithTag("book_options_sheet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val oldViewport = compose.onNodeWithTag("book_options_viewport").getUnclippedBoundsInRoot()
        if (tallNativeWindow) assertTrue("The controls must fit with room for a whole late offer; a reader Box does not prove this",
            oldViewport.bottom - oldRefresh.bottom > 200.dp)
        compose.runOnIdle { available.value = true }
        compose.waitUntil(5_000) { exists("book_accept_update") }
        settle()
        assertSameBounds(mapOf("refresh" to oldRefresh, "details" to oldDetails, "sheet" to oldSheet,
            "viewport" to oldViewport), mapOf(
            "refresh" to compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot(),
            "details" to compose.onNodeWithTag("book_options_offline").getUnclippedBoundsInRoot(),
            "sheet" to compose.onNodeWithTag("book_options_sheet", useUnmergedTree = true).getUnclippedBoundsInRoot(),
            "viewport" to compose.onNodeWithTag("book_options_viewport").getUnclippedBoundsInRoot()))
        val refresh = compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot()
        val offer = compose.onNodeWithTag("book_update_offer").getUnclippedBoundsInRoot()
        assertTrue("A late offer belongs after the existing actions", offer.top > refresh.bottom)
        if (tallNativeWindow) compose.onNodeWithTag("book_accept_update").assertIsDisplayed()
        else compose.onNodeWithTag("book_accept_update").performScrollTo().assertIsDisplayed()
        val acceptBounds = compose.onNodeWithTag("book_accept_update").getUnclippedBoundsInRoot()
        val visibleRefresh = compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot()
        val sheetBeforeWithdrawal = compose.onNodeWithTag("book_options_sheet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewportBeforeWithdrawal = compose.onNodeWithTag("book_options_viewport").getUnclippedBoundsInRoot()
        compose.runOnIdle { available.value = false }
        settle()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        compose.onNodeWithTag("book_update_unavailable").assertIsNotEnabled().assertIsDisplayed()
        assertSameBounds(mapOf("accept" to acceptBounds, "refresh" to visibleRefresh,
            "sheet" to sheetBeforeWithdrawal, "viewport" to viewportBeforeWithdrawal), mapOf(
            "accept" to compose.onNodeWithTag("book_update_unavailable").getUnclippedBoundsInRoot(),
            "refresh" to compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot(),
            "sheet" to compose.onNodeWithTag("book_options_sheet", useUnmergedTree = true).getUnclippedBoundsInRoot(),
            "viewport" to compose.onNodeWithTag("book_options_viewport").getUnclippedBoundsInRoot()))
        // The same old point is now a disabled surface; it cannot call a retained candidate.
        tapOldRootPoint(acceptBounds, containerTag = "book_options")
        assertEquals(0, accepts.get())
        if (tallNativeWindow) {
            // No scroll compensation: hit the refresh point captured before the offer existed.
            tapOldRootPoint(oldRefresh, containerTag = "book_options")
            compose.runOnIdle { assertEquals(1, refreshes.get()) }
        }
        assertEquals(before, saved())
        compose.dismissBookUpdateOptions()
        compose.runOnIdle { available.value = true }
        awaitBadge()
        compose.openBookUpdateOptions()
        val nextOffer = compose.onNodeWithTag("book_update_offer").getUnclippedBoundsInRoot()
        val nextDetails = compose.onNodeWithTag("book_options_offline").getUnclippedBoundsInRoot()
        assertTrue("On the next opening the ready offer is near the heading", nextOffer.bottom < nextDetails.top)
        assertEquals(before, saved())
        assertEquals(0, accepts.get())
    }

    @Test fun anAlreadyOpenOfferUsesTheLatestCandidateCallback() {
        saveOriginalPosition()
        val revision = mutableStateOf(1)
        val accepted = mutableListOf<Int>()
        mountDirect {
            val captured = revision.value
            val callback: (BookReadingPosition) -> Boolean = { accepted += captured; false }
            callback
        }
        awaitReader(); awaitBadge()
        compose.openBookUpdateOptions()
        compose.runOnIdle { revision.value = 2 }
        settle()
        compose.onNodeWithTag("book_accept_update").performClick()
        compose.runOnIdle { assertEquals(listOf(2), accepted) }
        assertEquals(old.contentHash, saved().contentHash)
    }

    private fun mountDirect(onRefresh: () -> Unit = {},
        accept: @androidx.compose.runtime.Composable () -> ((BookReadingPosition) -> Boolean)?) {
        compose.setContent { GuideTheme(false) {
            if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                BookReaderScreen(old, parish, store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {},
                    onRefresh = onRefresh, onAcceptUpdate = accept())
            }
        } }
    }

    private fun saveOriginalPosition() {
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, parish, "prayer", "chapter",
            fraction = .37f, mode = BookReadingMode.ORIGINAL, lastTextMode = BookReadingMode.ORIGINAL)))
    }
    private fun readingBounds(language: String): Map<String, DpRect> =
        listOf("book_order", "book_chapter", language, "book_mode_meaning", "book_reader_settings", "book_content",
            "book_find", "book_play", "book_microphone", "book_text_prayer").associateWith { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        }

    private fun assertSameBounds(expected: Map<String, DpRect>, actual: Map<String, DpRect>) {
        assertEquals(expected.keys, actual.keys)
        val tolerance = 1f / compose.activity.resources.displayMetrics.density
        for ((tag, a) in expected) {
            val b = actual.getValue(tag)
            assertEquals("$tag left moved", a.left.value, b.left.value, tolerance)
            assertEquals("$tag top moved", a.top.value, b.top.value, tolerance)
            assertEquals("$tag right moved", a.right.value, b.right.value, tolerance)
            assertEquals("$tag bottom moved", a.bottom.value, b.bottom.value, tolerance)
        }
    }

    private fun tapOldRootPoint(target: DpRect, containerTag: String? = null) {
        val container = if (containerTag == null) compose.onRoot() else compose.onNodeWithTag(containerTag)
        val root = container.getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val point = Offset(((target.left + target.right) / 2 - root.left).value * density,
            ((target.top + target.bottom) / 2 - root.top).value * density)
        container.performTouchInput { click(point) }
    }

    private fun assertAnchor(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.key, actual.key)
        assertEquals(expected.parishId, actual.parishId)
        assertEquals(expected.blockId, actual.blockId)
        assertEquals(expected.chapterId, actual.chapterId)
        assertEquals(expected.anchorKind, actual.anchorKind)
        assertEquals(expected.fraction, actual.fraction, .003f)
        assertEquals(expected.followingGapFraction, actual.followingGapFraction, .003f)
    }

    private fun saved() = requireNotNull(store.last(parish))
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitBadge() {
        compose.waitUntil(5_000) { exists("book_update_available") }
        compose.onNodeWithTag("book_update_available", useUnmergedTree = true).assertHasNoClickAction()
    }
    private fun awaitReader() { compose.waitUntil(5_000) { exists("book_content") }; settle() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }

    private fun withTallNativeDisplay(body: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        val density = compose.activity.resources.displayMetrics.density
        val originalHeight = compose.activity.resources.configuration.screenHeightDp
        val originalWidth = compose.activity.resources.configuration.screenWidthDp
        val originalScale = compose.activity.resources.configuration.fontScale
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val previousFont = shell("settings get system font_scale").toFloatOrNull()
        try {
            shell("settings put system font_scale 1.0")
            shell("wm size ${(480 * density).roundToInt()}x${(1280 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    val configuration = it.resources.configuration
                    ready = configuration.screenHeightDp >= 1200 && configuration.screenWidthDp >= 440 &&
                        abs(configuration.fontScale - 1f) < .001f && it.window.decorView.hasWindowFocus()
                }
                ready
            }
            body()
        } finally {
            try {
                compose.activityRule.scenario.onActivity { mounted.value = false }
                compose.waitForIdle()
            } finally {
                try { shell("wm size ${previousSize ?: "reset"}") }
                finally { shell(if (previousFont == null) "settings delete system font_scale"
                    else "settings put system font_scale $previousFont") }
                compose.waitUntil(10_000) {
                    var restored = false
                    compose.activityRule.scenario.onActivity {
                        val configuration = it.resources.configuration
                        restored = abs(configuration.screenHeightDp - originalHeight) <= 20 &&
                            abs(configuration.screenWidthDp - originalWidth) <= 20 &&
                            abs(configuration.fontScale - originalScale) < .001f && it.window.decorView.hasWindowFocus()
                    }
                    restored
                }
            }
        }
    }

    private fun fixture(meaning: String): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val prayer = BookBlock("prayer", BookBlockKind.PRAYER, null,
            "Господи, помоги нам жить в мире. ".repeat(100),
            "Господь, помоги нам жить в мире. ".repeat(100))
        val hidden = List(14) { i -> BookBlock("altar-$i", BookBlockKind.PRAYER, null,
            "Синтетическая алтарная молитва $i для проверки целостности книги.", isAltarText = true) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(),
            sourceTitle = "Тестовый источник", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Общая молитва", meaning, listOf(prayer) + hidden)), contentHash = "",
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
}
