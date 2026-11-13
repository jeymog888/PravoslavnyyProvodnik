package ru.liturgyguide.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full synthetic books test the real reader, disk cache and reading store, not a reconstructed service. */
@RunWith(AndroidJUnit4::class)
class BookOfflineServiceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "offline-service-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val parish = "synthetic-offline-service-parish"
    private val evening = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "30")
    private val morning = evening.copy(worship = WorshipKind.MATINS)
    private val journey = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечернее богослужение",
        listOf(evening, morning), partSet = BookPartSetKind.EVENING_TEXTS)
    private val route = mutableStateOf(BookRoute(evening, parish, journey))
    private val books = mutableStateOf(DiskBooks(directory))
    private val mounted = mutableStateOf(true)
    private val owners = mutableListOf(books.value)
    private var atPartChange: BookReadingPosition? = null
    private var contentSet = false

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        owners.forEach { it.cancelPending() }
        if (contentSet) compose.waitUntil(5_000) { owners.all { it.inFlight.get() == 0 } }
        assertTrue("Every controlled load must finish or cancel before deleting its files", owners.all { it.inFlight.get() == 0 })
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun preparingTheMissingNeighbourPreservesTheOpeningPrayerAndReopensItFromDiskOffline() {
        val active = seedEvening()
        val neighbour = fixture(morning, "Синтетическая утреня B")
        launch()
        awaitReader(active)
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_open_calendar").fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.StateDescription) { "" } == "Сохранено 1 из 2"
        }
        val opening = prayerGeometry("prayer-0")
        assertTrue("At 360 dp and 100% font the untouched opening must show three real prayer lines", opening.visibleLines >= 3)
        assertEquals(BookAnchorKind.HEADER, saved(evening).anchorKind)
        val before = preferences.all.toMap()
        val position = saved(evening)

        compose.onNodeWithTag("book_open_calendar").assertIsDisplayed().performClick()
        awaitStatus("Сохранено 1 из 2")
        part(WorshipKind.VESPERS).assertTextContains("сохранена", substring = true)
        part(WorshipKind.MATINS).assertTextContains("не сохранена", substring = true)
        prepareAndHold()
        assertEquals(listOf(Request(morning, false)), books.value.loads)
        assertEquals(before, preferences.all)
        assertEquals(position, saved(evening))
        assertNull(store.positionFor(morning, parish))

        books.value.release(BookLoadResult.Ready(neighbour, fromCache = false))
        awaitFinished(1)
        awaitStatus("Сохранено 2 из 2")
        calendarNode("book_offline_complete").assertTextEquals("Оба текста доступны без сети")
        assertEquals(neighbour, FileBookCache(directory).readVersion(morning, neighbour.contentHash))
        assertEquals(before, preferences.all)
        assertNull("Preparation must not accept the neighbouring edition", store.positionFor(morning, parish))
        assertEquals(evening, route.value.activeKey)
        dismissSheet()
        val returned = prayerGeometry("prayer-0")
        assertEquals(opening.firstVisibleLine, returned.firstVisibleLine)
        assertEquals(opening.layoutSize, returned.layoutSize)
        assertEquals("Closing details must restore the same measured prayer line", opening.textTop, returned.textTop, 1f)
        assertEquals(position, saved(evening))

        // Same-process remount with a new wrapper: only the real disk cache survives this owner.
        val offline = DiskBooks(directory).also { it.denyNetwork = true; owners += it }
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { books.value = offline; mounted.value = true }
        awaitReader(active)
        compose.onNodeWithTag("book_open_other_part").assertTextEquals("Открыть утреню").performClick()
        awaitReader(neighbour)
        assertEquals(neighbour.contentHash, saved(morning).contentHash)
        assertEquals(journey, saved(morning).journeyContext)
        assertEquals(position, saved(evening))
        assertTrue("The reopened neighbour must require no network", offline.networkRequests.isEmpty())
        assertEquals(listOf(Request(morning, false)), offline.loads)
    }

    @Test fun aPartialNetworkFailureKeepsTheAcceptedReadingAndRetryDownloadsOnlyTheMissingText() {
        val active = seedEvening()
        val neighbour = fixture(morning, "Синтетическая утреня B")
        launch()
        awaitReader(active)
        openDetailsFromMiddle()
        awaitStatus("Сохранено 1 из 2")
        val before = preferences.all.toMap()
        val position = saved(evening)
        prepareAndHold()
        books.value.release(BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи для синтетической утрени"))
        awaitFinished(1)
        awaitStatus("Сохранено 1 из 2")
        calendarNode("book_offline_message").assertTextContains("Нет связи", substring = true)
        calendarNode("book_prepare_service").assertTextEquals("Попробовать еще раз").assertIsEnabled()
        assertEquals(before, preferences.all)
        assertEquals(active, cache.readVersion(evening, active.contentHash))
        assertEquals(position, saved(evening))
        assertEquals(evening, route.value.activeKey)
        assertNull(store.positionFor(morning, parish))

        prepareAndHold()
        books.value.release(BookLoadResult.Ready(neighbour, fromCache = false))
        awaitFinished(2)
        awaitStatus("Сохранено 2 из 2")
        compose.onNodeWithTag("book_offline_message").assertDoesNotExist()
        assertEquals(listOf(Request(morning, false), Request(morning, false)), books.value.loads)
        assertEquals(before, preferences.all)
        assertEquals(position, saved(evening))
        assertNull(store.positionFor(morning, parish))
        assertEquals(evening, route.value.activeKey)
    }

    @Test fun anAcceptedMissingNeighbourOffersRecoveryWithoutReadingOrAcceptingTheLatestEdition() {
        val active = seedEvening()
        val accepted = fixture(morning, "Отсутствующая синтетическая редакция B")
        val latest = fixture(morning, "Другая синтетическая редакция C")
        assertNotEquals(accepted.contentHash, latest.contentHash)
        val oldPosition = BookReadingPosition(morning, accepted.contentHash, parish, "prayer-7", "chapter",
            fraction = .43f, mode = BookReadingMode.RUSSIAN, journeyContext = journey)
        assertTrue(store.save(oldPosition))
        assertTrue(cache.write(latest))
        launch()
        awaitReader(active)
        openDetailsFromMiddle()
        awaitStatus("Сохранено 1 из 2")
        part(WorshipKind.MATINS).assertTextContains("нужно восстановить", substring = true)
        calendarNode("book_offline_message").assertTextContains("Сохраненная редакция недоступна", substring = true)
        compose.onNodeWithTag("book_prepare_service").assertDoesNotExist()
        val current = saved(evening)
        assertEquals(oldPosition, saved(morning))
        assertFalse("Accepted B must never be inspected as unaccepted latest C", morning in books.value.latestReads)
        assertTrue(books.value.loads.isEmpty())

        calendarNode("book_recover_part_MATINS").assertTextEquals("Открыть утреню").performClick()
        await("book_accepted_edition_missing")
        compose.waitUntil(5_000) { !exists("book_accepted_recovery_progress") }
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_accepted_edition_missing").assertIsDisplayed()
            .assertTextEquals("Сохраненная редакция недоступна")
        compose.onNodeWithTag("book_open_new_edition").assertDoesNotExist()
        assertEquals(morning, route.value.activeKey)
        assertEquals("Current A must be committed before the route callback", current, atPartChange)
        assertEquals(current, saved(evening))
        assertEquals(oldPosition, saved(morning))
        assertEquals(current, store.last(parish))
        assertFalse(morning in books.value.latestReads)
        assertTrue(books.value.loads.isEmpty())
        assertEquals(latest, cache.read(morning))
    }

    @Test fun aStorageReadFailureOffersAnExplicitLocalRecheckWithoutDownloading() {
        val active = seedEvening()
        books.value.failLatestRead = morning
        launch()
        awaitReader(active)
        compose.onNodeWithTag("book_open_calendar").performClick()
        awaitStatus("Проверьте загрузку")
        part(WorshipKind.MATINS).assertTextContains("не удалось проверить", substring = true)
        calendarNode("book_recheck_service").assertTextEquals("Проверить еще раз").assertIsEnabled()
        compose.onNodeWithTag("book_prepare_service").assertDoesNotExist()
        val before = preferences.all.toMap()
        books.value.failLatestRead = null
        calendarNode("book_recheck_service").performClick()
        awaitStatus("Сохранено 1 из 2")
        calendarNode("book_prepare_service").assertIsEnabled()
        assertEquals(before, preferences.all)
        assertEquals(evening, route.value.activeKey)
        assertTrue(books.value.loads.isEmpty())
    }

    @Test fun aLateEnrichmentOfferDoesNotCancelPreparationOrAcceptAnotherEdition() {
        val active = seedEvening()
        val updated = fixture(evening, "Синтетическая вечерня A2")
        val neighbour = fixture(morning, "Синтетическая утреня B")
        assertNotEquals(active.contentHash, updated.contentHash)
        books.value.holdEnrichment()
        launch()
        awaitReader(active)
        compose.waitUntil(5_000) { books.value.enrichmentStarted.get() == 1 }
        compose.onNodeWithTag("book_open_calendar").performClick()
        awaitStatus("Сохранено 1 из 2")
        val before = preferences.all.toMap()
        val position = saved(evening)
        prepareAndHold(checkBusyLabel = false)
        val heldLoad = requireNotNull(books.value.pending.get())

        books.value.releaseEnrichment(updated)
        compose.waitUntil(5_000) { books.value.enrichmentDelivered.get() == 1 }
        await("book_accept_update")
        settle()
        assertEquals("Offering A2 must not cancel the independently running B download", 0, books.value.cancelled.get())
        assertSame(heldLoad, books.value.pending.get())
        assertEquals(1, books.value.inFlight.get())
        assertEquals(0, books.value.finished.get())
        assertEquals(listOf(Request(morning, false)), books.value.loads)
        assertEquals(position, saved(evening))
        assertEquals(before, preferences.all)

        books.value.release(BookLoadResult.Ready(neighbour, fromCache = false))
        awaitFinished(1)
        awaitStatus("Сохранено 2 из 2")
        assertEquals(0, books.value.cancelled.get())
        assertEquals(position, saved(evening))
        assertEquals(before, preferences.all)
        assertNull(store.positionFor(morning, parish))
        assertEquals(updated, FileBookCache(directory).readVersion(evening, updated.contentHash))
        assertEquals(neighbour, FileBookCache(directory).readVersion(morning, neighbour.contentHash))
        dismissSheet()
        compose.onNodeWithText(active.title).assertIsDisplayed()
        compose.onNodeWithText(updated.title).assertDoesNotExist()
    }

    @Test fun reopeningAfterAnotherOwnerAcceptsAnEditionUsesThatExactEditionWithoutAForcedDownload() {
        val active = seedEvening()
        val accepted = fixture(evening, "Синтетическая вечерня B")
        val latest = fixture(evening, "Синтетическая вечерня C")
        assertNotEquals(accepted.contentHash, latest.contentHash)
        launch()
        awaitReader(active)
        assertTrue(cache.write(accepted))
        assertTrue(cache.write(latest))
        val acceptedPosition = BookReadingPosition(evening, accepted.contentHash, parish, "prayer-0", "chapter",
            anchorKind = BookAnchorKind.HEADER, lastTextMode = BookReadingMode.ORIGINAL, journeyContext = journey)
        assertTrue(BookReadingStore(preferences).save(acceptedPosition))
        // A wrong force refresh returns C immediately, making the defect an observable request rather than a timeout.
        books.value.forceResponse = BookLoadResult.Ready(latest, fromCache = false)
        compose.onNodeWithTag("book_open_calendar").performClick()
        awaitStatus("Сохранено 0 из 2")
        part(WorshipKind.VESPERS).assertTextContains("нужно восстановить", substring = true)
        assertEquals(acceptedPosition, saved(evening))
        calendarNode("book_recover_part_VESPERS").assertTextEquals("Открыть текст заново").performClick()
        compose.waitUntil(5_000) {
            books.value.loads.isNotEmpty() || compose.onAllNodesWithText(accepted.title).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Reopening must read accepted B from disk without forcing latest C", books.value.loads.isEmpty())
        awaitReader(accepted)
        compose.onNodeWithText(accepted.title).assertIsDisplayed()
        compose.onNodeWithText(active.title).assertDoesNotExist()
        compose.onNodeWithText(latest.title).assertDoesNotExist()
        // Layout may report -0f at the header origin; it names the exact same point as +0f.
        assertEquals(acceptedPosition, saved(evening).canonicalZero())
        assertEquals(acceptedPosition, requireNotNull(store.last(parish)).canonicalZero())
        assertTrue(books.value.networkRequests.isEmpty())
        assertEquals(latest, cache.read(evening))
    }

    private fun seedEvening(): ServiceBook = fixture(evening, "Вечерня A").also {
        assertTrue(cache.write(it))
        assertTrue(store.save(BookReadingPosition(evening, it.contentHash, parish, "prayer-0", "chapter",
            anchorKind = BookAnchorKind.HEADER, journeyContext = journey)))
    }

    private fun launch() {
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 360; screenHeightDp = 780; fontScale = 1f
            }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 1f)) {
                GuideTheme(false) { if (mounted.value) Box(Modifier.size(360.dp, 780.dp)) {
                    ServiceBookScreen(route.value.activeKey, parish, books.value, store, 0,
                        onBack = {}, onSettings = {}, onOpenUrl = {}, route = route.value,
                        onRouteChanged = { next ->
                            atPartChange = store.positionFor(route.value.activeKey, parish)
                            route.value = next
                            true
                        }, onOpenCommonLiturgy = {})
                } }
            }
        }
        contentSet = true
    }

    private fun openDetailsFromMiddle() {
        // One header precedes the 15 prayer items. Align the eighth prayer at the
        // viewport origin, rather than only bringing its bottom edge into view.
        compose.onNodeWithTag("book_content").performScrollToIndex(8)
        settle()
        compose.waitUntil(5_000) { saved(evening).blockId == "prayer-7" }
        assertEquals("prayer-7", saved(evening).blockId)
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_options_offline").performScrollTo().assertIsDisplayed().performClick()
    }

    private fun prepareAndHold(checkBusyLabel: Boolean = true) {
        calendarNode("book_prepare_service").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { books.value.pending.get() != null && books.value.inFlight.get() == 1 }
        awaitStatus("Сохраняем тексты", busy = true)
        calendarNode("book_offline_progress").assertIsDisplayed()
        if (checkBusyLabel) calendarNode("book_prepare_service").assertIsNotEnabled().assertTextEquals("Сохраняем...")
        assertEquals(1, books.value.inFlight.get())
    }

    private fun awaitFinished(count: Int) {
        compose.waitUntil(5_000) { books.value.finished.get() == count && books.value.inFlight.get() == 0 }
        compose.waitUntil(5_000) { !exists("book_offline_progress") }
        settle()
    }

    private fun awaitReader(book: ServiceBook) {
        await("book_content")
        compose.waitUntil(5_000) { store.last(parish)?.contentHash == book.contentHash }
        settle()
        assertEquals(book.key, route.value.activeKey)
    }

    private fun awaitStatus(value: String, busy: Boolean = false) {
        await("book_offline_state")
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_offline_state").fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.StateDescription) { "" } == value &&
                (busy || !exists("book_offline_progress"))
        }
        compose.onNodeWithTag("book_offline_state").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    private fun part(kind: WorshipKind) = calendarNode("book_offline_part_${kind.name}")
    private fun calendarNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("book_calendar").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { exists(tag) } }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun saved(key: ServiceBookKey) = requireNotNull(store.positionFor(key, parish))
    private fun BookReadingPosition.canonicalZero() = copy(fraction = if (fraction == 0f) 0f else fraction)
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun dismissSheet() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0]
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
    }

    private data class PrayerGeometry(val firstVisibleLine: Int, val visibleLines: Int, val textTop: Float, val layoutSize: String)
    private fun prayerGeometry(id: String): PrayerGeometry {
        val node = compose.onNodeWithTag("book_text_$id", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(1f, layout.layoutInput.density.fontScale, .001f)
        val density = layout.layoutInput.density.density
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        assertEquals(360f, viewport.width.value, 1f)
        val top = node.getUnclippedBoundsInRoot().top.value
        val visible = (0 until layout.lineCount).filter {
            top + layout.getLineTop(it) / density >= viewport.top.value &&
                top + layout.getLineBottom(it) / density <= viewport.bottom.value
        }
        assertTrue("The actual prayer must contain fully visible lines", visible.isNotEmpty())
        return PrayerGeometry(visible.first(), visible.size, top, layout.size.toString())
    }

    private fun fixture(key: ServiceBookKey, title: String): ServiceBook {
        val value = ServiceBook(key = key, title = title, sourceUrl = key.sourceUrl(),
            sourceTitle = "Синтетический источник для проверки хранения и интерфейса", fetchedAtMillis = 1,
            selectedEventTitle = null, contentHash = "", chapters = listOf(BookChapter("chapter", "Молитвы",
                "Проверочное пояснение", (0..14).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, null,
                    "Синтетический молитвенный текст $index для проверки чтения, сохранения и строк на экране. ".repeat(if (index == 0) 25 else 3)) })))
        return value.copy(contentHash = ServiceBookIntegrity.contentHash(value)).also {
            assertTrue(it.blocks.size >= 15)
            assertTrue(it.blocks.sumOf { block -> block.original.length } > 2_000)
            assertTrue(ServiceBookIntegrity.valid(it))
        }
    }

    private data class Request(val key: ServiceBookKey, val force: Boolean)
    private class DiskBooks(directory: File) : ServiceBooks {
        private val cache = FileBookCache(directory)
        val loads = CopyOnWriteArrayList<Request>()
        val networkRequests = CopyOnWriteArrayList<Request>()
        val latestReads = CopyOnWriteArrayList<ServiceBookKey>()
        val pending = AtomicReference<CompletableDeferred<BookLoadResult>?>(null)
        val inFlight = AtomicInteger()
        val finished = AtomicInteger()
        val cancelled = AtomicInteger()
        private val enrichment = AtomicReference<CompletableDeferred<BookLoadResult.Ready?>?>(null)
        val enrichmentStarted = AtomicInteger()
        val enrichmentDelivered = AtomicInteger()
        @Volatile var denyNetwork = false
        @Volatile var failLatestRead: ServiceBookKey? = null
        @Volatile var forceResponse: BookLoadResult? = null
        override fun cached(key: ServiceBookKey): ServiceBook? {
            latestReads += key
            check(key != failLatestRead) { "Synthetic cache read failure" }
            return cache.read(key)
        }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = cache.readVersion(key, contentHash)
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads += Request(key, force)
            if (!force) cache.read(key)?.let { return BookLoadResult.Ready(it, fromCache = true) }
            networkRequests += Request(key, force)
            if (force) forceResponse?.let { return it }
            if (denyNetwork) return BookLoadResult.Failed(BookFailure.NETWORK, "Network denied by this scenario")
            val answer = CompletableDeferred<BookLoadResult>()
            check(pending.compareAndSet(null, answer))
            inFlight.incrementAndGet()
            try {
                return answer.await().also { if (it is BookLoadResult.Ready && it.saved) check(cache.write(it.book)) }
            } catch (cancel: CancellationException) { cancelled.incrementAndGet(); throw cancel }
            finally { pending.compareAndSet(answer, null); inFlight.decrementAndGet(); finished.incrementAndGet() }
        }
        override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
            val answer = enrichment.get() ?: return null
            enrichmentStarted.incrementAndGet()
            return answer.await().also { enrichmentDelivered.incrementAndGet() }
        }
        fun holdEnrichment() { check(enrichment.compareAndSet(null, CompletableDeferred())) }
        fun releaseEnrichment(book: ServiceBook) {
            check(cache.write(book))
            check(requireNotNull(enrichment.get()).complete(BookLoadResult.Ready(book, fromCache = true)))
        }
        fun release(result: BookLoadResult) { check(requireNotNull(pending.get()).complete(result)) }
        fun cancelPending() { pending.get()?.cancel(); enrichment.get()?.cancel() }
    }
}
