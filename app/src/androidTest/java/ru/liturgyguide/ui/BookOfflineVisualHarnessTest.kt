package ru.liturgyguide.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** One opt-in profile per invocation, with native font configured externally; archives remain unchanged. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BookOfflineVisualHarnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mounted = mutableStateOf(false)
    private val current = mutableStateOf<Session?>(null)
    private val sessions = mutableListOf<Session>()
    private val parish = "offline-visual-test-parish"
    private var contentSet = false
    private lateinit var output: File

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            sessions.forEach { it.books.release.cancel() }
            compose.waitUntil(5_000) { sessions.all { it.books.inFlight.get() == 0 } }
        }
        sessions.forEach { context.deleteSharedPreferences(it.preferenceName); it.directory.deleteRecursively() }
    }

    @Test fun archivedEveningTextsAtSelectedNativeFontProfile() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with both offlineVisualVespersFile and offlineVisualMatinsFile",
            args.containsKey("offlineVisualVespersFile") && args.containsKey("offlineVisualMatinsFile"))
        val profile = when (requireNotNull(args.getString("offlineVisualProfile")) {
            "Required offlineVisualProfile must be light or dark"
        }) {
            "light" -> Profile("light-360x780-100", 360, 780, 1f, false)
            "dark" -> Profile("dark-320x640-200", 320, 640, 2f, true)
            else -> error("offlineVisualProfile must be light or dark")
        }
        val aFile = File(requireNotNull(args.getString("offlineVisualVespersFile")))
        val bFile = File(requireNotNull(args.getString("offlineVisualMatinsFile")))
        val active = readArchive(aFile, WorshipKind.VESPERS)
        val neighbour = readArchive(bFile, WorshipKind.MATINS)
        assertEquals(active.key.date, neighbour.key.date)
        assertEquals(active.key.churchType, neighbour.key.churchType)
        val run = (args.getString("offlineVisualRun") ?: UUID.randomUUID().toString())
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)
        require(run.isNotBlank())
        output = File(requireNotNull(context.getExternalFilesDir(null)), "offline-visual/$run")
        check(!output.exists()) { "Use a unique offlineVisualRun: $output" }
        check(output.mkdirs())
        val profiles = JSONArray()
        val receipt = JSONObject().put("run", run).put("status", "running").put("profiles", profiles)
            .put("scope", "Native font configured externally before instrumentation and verified by this test; LocalConfiguration/LocalDensity plus test-owned reader/dialog dimensions; no system setting writes")
            .put("selectedProfile", args.getString("offlineVisualProfile"))
            .put("origin", "Unchanged genuine ServiceBook archives; no synthetic service text or network transport")
            .put("vespers", archiveReceipt(aFile, active)).put("matins", archiveReceipt(bFile, neighbour))
            .put("captureSource", "Committed active activity/dialog window via PixelCopy; native pixels, no mockup")
        try {
            val nativeFont = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE)
            var activityFont = 0f
            instrumentation.runOnMainSync { activityFont = compose.activity.resources.configuration.fontScale }
            receipt.put("nativeSettingsFontScale", nativeFont).put("nativeActivityFontScale", activityFont)
            assertEquals("Configure the native font externally before instrumentation", profile.font, nativeFont, .001f)
            assertEquals("The raw activity must already use the selected native font", profile.font, activityFont, .001f)
            compose.setContent {
                current.value?.let { session ->
                    val profile = session.profile
                    val density = LocalDensity.current
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = profile.width; screenHeightDp = profile.height; fontScale = profile.font
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration,
                        LocalDensity provides Density(density.density, profile.font)) {
                        GuideTheme(profile.dark) {
                            if (mounted.value) Box(Modifier.size(profile.width.dp, profile.height.dp).testTag("offline_visual_scope")) {
                                ServiceBookScreen(session.route.value.activeKey, parish, session.books, session.store, 0,
                                    onBack = {}, onSettings = {}, onOpenUrl = {}, route = session.route.value,
                                    onRouteChanged = { session.route.value = it; true }, onOpenCommonLiturgy = {})
                            }
                        }
                    }
                }
            }
            contentSet = true
            exercise(newSession(profile, active, neighbour), profiles)
            receipt.put("status", "passed").put("externalNetworkRequests", 0)
        } catch (failure: Throwable) {
            receipt.put("status", "failed").put("failure", failure.toString())
            runCatching { current.value?.let { capture(it, "failure") } }
            throw failure
        } finally {
            File(output, "receipt.json").writeText(receipt.toString(2))
            android.util.Log.i("BookOfflineVisualQA", "output=${output.absolutePath}")
        }
    }

    private fun exercise(session: Session, profiles: JSONArray) {
        val details = JSONObject().put("profile", session.profile.name).put("fontScale", session.profile.font)
            .put("dark", session.profile.dark).put("requestedWidthDp", session.profile.width)
            .put("requestedHeightDp", session.profile.height).put("captures", session.captures)
        profiles.put(details)
        compose.runOnIdle { current.value = session; mounted.value = true }
        await("book_content")
        compose.waitUntil(5_000) { session.store.last(parish)?.contentHash == session.active.contentHash }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_open_calendar").fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.StateDescription) { "" } == "Сохранено 1 из 2"
        }
        settle()
        val bounds = compose.onNodeWithTag("offline_visual_scope").getBoundsInRoot()
        assertEquals(session.profile.width.toFloat(), bounds.width.value, 1f)
        details.put("readerWidthDp", bounds.width.value).put("readerHeightDp", (bounds.bottom - bounds.top).value)
        verifyVisibleCtas()
        capture(session, "01-opening")
        val openingButton = compose.onNodeWithTag("book_open_calendar")
        if (!fullyVisible("book_open_calendar", "book_content")) {
            compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_open_calendar"))
            openingButton.performScrollTo()
        }
        openingButton.assertIsDisplayed()
        openingButton.assertHeightIsAtLeast(48.dp)
        checkText("book_offline_summary", session.profile.font)
        settle()
        val before = requireNotNull(session.store.positionFor(session.active.key, parish))
        val preferencesBefore = session.preferences.all.toMap()
        openingButton.performClick()
        await("book_calendar")
        constrainDialog(session.profile)
        val modalBounds = compose.onNodeWithTag("book_calendar").getBoundsInRoot()
        details.put("modalWidthDp", modalBounds.width.value).put("modalViewportHeightDp", (modalBounds.bottom - modalBounds.top).value)
        awaitStatus("Сохранено 1 из 2")
        checkParts(session)
        checkButton(session, "Сохранить оба текста", enabled = true)
        details.put("prepareGapDp", measuredButtonGap())
        capture(session, "02-details-1-of-2")
        compose.onNodeWithTag("book_prepare_service").performClick()
        compose.waitUntil(5_000) { session.books.inFlight.get() == 1 }
        awaitStatus("Сохраняем тексты")
        constrainDialog(session.profile)
        checkParts(session)
        checkButton(session, "Сохраняем...", enabled = false)
        details.put("savingGapDp", measuredButtonGap())
        capture(session, "03-saving-disabled")
        assertEquals(preferencesBefore, session.preferences.all)
        session.books.release.complete(Unit)
        compose.waitUntil(5_000) { session.books.finished.get() == 1 && session.books.inFlight.get() == 0 }
        awaitStatus("Сохранено 2 из 2")
        constrainDialog(session.profile)
        checkParts(session)
        calendarNode("book_offline_complete").assertTextEquals("Оба текста доступны без сети")
        checkText("book_offline_complete", session.profile.font)
        capture(session, "04-complete-2-of-2")
        assertEquals(session.neighbour, FileBookCache(session.directory).readVersion(session.neighbour.key, session.neighbour.contentHash))
        assertEquals(preferencesBefore, session.preferences.all)
        assertNull(session.store.positionFor(session.neighbour.key, parish))
        assertEquals(listOf(session.neighbour.key), session.books.loads)
        assertEquals(0, session.books.cancelled.get())
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0]
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
        assertEquals(canonicalZeros(before), canonicalZeros(requireNotNull(session.store.positionFor(session.active.key, parish))))
        assertEquals(session.active.key, session.route.value.activeKey)
        details.put("cursorUnchanged", true).put("neighbourAccepted", false).put("archiveLoads", 1)
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
    }

    private fun checkParts(session: Session) {
        for (kind in listOf(WorshipKind.VESPERS, WorshipKind.MATINS)) {
            val tag = "book_offline_part_${kind.name}"
            calendarNode(tag)
            checkText(tag, session.profile.font)
        }
    }

    private fun checkButton(session: Session, label: String, enabled: Boolean) {
        val node = calendarNode("book_prepare_service").assertHeightIsAtLeast(48.dp).assertTextEquals(label)
        if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
        val labels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            hasAnyAncestor(hasTestTag("book_prepare_service")), useUnmergedTree = true)
        assertTrue("The actual CTA label must expose its text layout", labels.fetchSemanticsNodes().isNotEmpty())
        for (index in labels.fetchSemanticsNodes().indices) checkLayout(labels[index], session.profile.font)
        verifyVisibleCtas()
    }

    private fun verifyVisibleCtas() {
        val actions = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
        for (index in actions.fetchSemanticsNodes().indices) if (actions[index].isDisplayed()) actions[index].assertHeightIsAtLeast(48.dp)
    }

    private fun measuredButtonGap(): Float {
        val button = compose.onNodeWithTag("book_prepare_service").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_calendar").getBoundsInRoot()
        val labelIds = compose.onAllNodes(hasAnyAncestor(hasTestTag("book_prepare_service")), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.id }.toSet() + compose.onNodeWithTag("book_prepare_service").fetchSemanticsNode().id
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            hasAnyAncestor(hasTestTag("book_offline_state")), useUnmergedTree = true)
        var nearestBottom: Float? = null
        for (index in texts.fetchSemanticsNodes().indices) {
            val node = texts[index]
            if (node.fetchSemanticsNode().id in labelIds || !node.isDisplayed()) continue
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("Offline text must not overlap the CTA", bounds.bottom <= button.top || bounds.top >= button.bottom)
            if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.bottom <= button.top)
                nearestBottom = maxOf(nearestBottom ?: bounds.bottom.value, bounds.bottom.value)
        }
        return requireNotNull(nearestBottom) { "Show the CTA and its preceding text together before measuring the gap" }
            .let { bottom -> (button.top.value - bottom).also {
            assertTrue("CTA needs a 12 dp gap from its neighboring text (0.6 dp rounding tolerance): $it", it + .6f >= 12f)
        } }
    }

    private fun checkText(tag: String, font: Float) = checkLayout(compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(), font)
    private fun checkLayout(node: SemanticsNodeInteraction, font: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("A text-fit check requires an actual layout result", layouts.isNotEmpty())
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        assertTrue("The measured text must be fully visible, not clipped by its viewport",
            full.left.value + .6f >= visible.left.value && full.right.value <= visible.right.value + .6f &&
                full.top.value + .6f >= visible.top.value && full.bottom.value <= visible.bottom.value + .6f)
        for (layout in layouts) {
            assertTextFitsLayout(layout, layout.layoutInput.text.text)
            assertEquals(font, layout.layoutInput.density.fontScale, .001f)
        }
    }
    private fun calendarNode(tag: String): SemanticsNodeInteraction {
        if (!fullyVisible(tag, "book_calendar")) {
            compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
        }
        assertTrue("The complete $tag must fit inside the calendar viewport after scrolling", fullyVisible(tag, "book_calendar"))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun fullyVisible(tag: String, container: String) = runCatching {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag(container).getBoundsInRoot()
        bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right
    }.getOrDefault(false)
    private fun canonicalZeros(position: BookReadingPosition) = position.copy(
        fraction = if (position.fraction == 0f) 0f else position.fraction,
        followingGapFraction = if (position.followingGapFraction == 0f) 0f else position.followingGapFraction)
    private fun awaitStatus(value: String) {
        await("book_offline_state")
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_offline_state").fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.StateDescription) { "" } == value &&
                (value == "Сохраняем тексты" || compose.onAllNodesWithTag("book_offline_progress").fetchSemanticsNodes().isEmpty())
        }
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun constrainDialog(profile: Profile) {
        val window = activeWindow()
        assertNotSame("The details must own a real modal window", compose.activity.window, window)
        val density = compose.activity.resources.displayMetrics.density
        instrumentation.runOnMainSync { window.setLayout((profile.width * density).roundToInt(), (profile.height * density).roundToInt()) }
        settle()
        compose.waitUntil(5_000) { kotlin.math.abs(compose.onNodeWithTag("book_calendar").getBoundsInRoot().width.value - profile.width) <= 1f }
    }

    private fun capture(session: Session, state: String) {
        settle()
        instrumentation.waitForIdleSync()
        val window = activeWindow()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue(window.decorView.isHardwareAccelerated)
            window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            window.decorView.invalidate()
        }
        assertTrue("A render frame must commit before capture", committed.await(5, TimeUnit.SECONDS))
        var pixelWidth = 0
        var pixelHeight = 0
        instrumentation.runOnMainSync {
            pixelWidth = window.decorView.width
            pixelHeight = window.decorView.height
        }
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            val filename = "${session.profile.name}-$state.png"
            File(output, filename).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            session.captures.put(JSONObject().put("file", filename).put("state", state)
                .put("window", if (window === compose.activity.window) "activity" else "dialog")
                .put("pixelWidth", bitmap.width).put("pixelHeight", bitmap.height))
        } finally { bitmap.recycle() }
    }

    private fun activeWindow(): Window {
        fun provider(view: View): DialogWindowProvider? {
            if (view is DialogWindowProvider) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) provider(view.getChildAt(index))?.let { return it }
            return null
        }
        lateinit var result: Window
        instrumentation.runOnMainSync {
            result = WindowInspector.getGlobalWindowViews().filter { it.isShown }
                .mapNotNull { provider(it)?.window }.lastOrNull() ?: compose.activity.window
        }
        return result
    }

    private fun readArchive(file: File, kind: WorshipKind): ServiceBook {
        assertTrue("Genuine archive must exist: $file", file.isFile)
        return Gson().fromJson(file.readText(), ServiceBook::class.java).also {
            assertTrue(ServiceBookIntegrity.valid(it)); assertEquals(kind, it.key.worship)
        }
    }
    private fun archiveReceipt(file: File, book: ServiceBook) = JSONObject().put("input", file.absolutePath)
        .put("inputBytes", file.length()).put("inputSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        .put("date", book.key.date).put("churchType", book.key.churchType).put("contentHash", book.contentHash).put("sourceUrl", book.sourceUrl)

    private fun newSession(profile: Profile, active: ServiceBook, neighbour: ServiceBook): Session {
        val name = "offline-visual-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = BookReadingStore(preferences)
        val journey = BookJourneyContext(parish, LocalDate.parse(active.key.date).minusDays(1).toString(), "18:00",
            "Вечернее богослужение", listOf(active.key, neighbour.key), partSet = BookPartSetKind.EVENING_TEXTS)
        assertNotNull(journey.validatedFor(active.key, parish))
        assertTrue(FileBookCache(directory).write(active))
        val chapter = active.chapters.first { it.blocks.any { block -> !block.isAltarText } }
        val block = chapter.blocks.first { !it.isAltarText }
        assertTrue(store.save(BookReadingPosition(active.key, active.contentHash, parish, block.id, chapter.id,
            anchorKind = BookAnchorKind.HEADER, lastTextMode = BookReadingMode.ORIGINAL, journeyContext = journey)))
        return Session(profile, name, directory, preferences, store, active, neighbour, ArchiveBooks(directory, neighbour),
            mutableStateOf(BookRoute(active.key, parish, journey))).also { sessions += it }
    }
    private data class Profile(val name: String, val width: Int, val height: Int, val font: Float, val dark: Boolean)
    private class Session(val profile: Profile, val preferenceName: String, val directory: File,
        val preferences: android.content.SharedPreferences, val store: BookReadingStore, val active: ServiceBook,
        val neighbour: ServiceBook, val books: ArchiveBooks, val route: androidx.compose.runtime.MutableState<BookRoute>,
        val captures: JSONArray = JSONArray())
    private class ArchiveBooks(directory: File, private val neighbour: ServiceBook) : ServiceBooks {
        private val cache = FileBookCache(directory)
        val release = CompletableDeferred<Unit>()
        val loads = CopyOnWriteArrayList<ServiceBookKey>()
        val inFlight = AtomicInteger()
        val finished = AtomicInteger()
        val cancelled = AtomicInteger()
        override fun cached(key: ServiceBookKey) = cache.read(key)
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = cache.readVersion(key, contentHash)
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads += key
            check(key == neighbour.key && !force) { "Only the held missing archive may be loaded" }
            inFlight.incrementAndGet()
            try {
                release.await()
                check(cache.write(neighbour))
                return BookLoadResult.Ready(neighbour, fromCache = false)
            } catch (cancel: CancellationException) { cancelled.incrementAndGet(); throw cancel }
            finally { inFlight.decrementAndGet(); finished.incrementAndGet() }
        }
    }
}
