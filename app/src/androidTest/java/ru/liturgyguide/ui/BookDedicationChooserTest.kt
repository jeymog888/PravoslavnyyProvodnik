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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real catalog and resolver evidence; callback results exercise the chooser boundary, not book acceptance. */
@RunWith(AndroidJUnit4::class)
class BookDedicationChooserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "dedication-chooser-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val choices = PatronalChoiceStore(preferences)
    private val parish = "dedication-chooser-test-parish"
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val catalog by lazy { requireNotNull(PatronalCatalog.decode(context.assets.open(PatronalCatalog.ASSET_NAME)
        .bufferedReader().use { it.readText() })) }
    private val calendar = CalendarTransport(::fixtureBytes)
    private val catalogFailures = AtomicInteger()
    private val catalogLoads = AtomicInteger()
    private val services = BookDedicationServices(choices, PatronalCalendarResolver(choices, calendar)) {
        catalogLoads.incrementAndGet()
        if (catalogFailures.getAndUpdate { maxOf(0, it - 1) } > 0) null else catalog
    }
    private val opened = CopyOnWriteArrayList<PatronalEventProof>()
    private val openCompleted = AtomicInteger()
    private val openGate = AtomicReference<CompletableDeferred<BookDedicationStart>?>(null)
    private val dismissed = AtomicInteger()
    private val urls = CopyOnWriteArrayList<String>()

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        calendar.releaseAll()
        openGate.get()?.cancel()
        if (contentSet) compose.waitUntil(5_000) { calendar.inFlight.get() == 0 }
        context.deleteSharedPreferences(name)
    }

    @Test fun theRealLocalCatalogKeepsBothNicholasDatesMaryAndAnEmptySearchIndependentOfNetwork() {
        launch()
        val before = preferences.all.toMap()
        search("Николай Чудотворец")
        result(may).assertTextContains("22 мая 2026", substring = true)
        result(december).assertTextContains("19 декабря 2026", substring = true)
        assertNotEquals(may.minea, december.minea)
        search("Введение Богородицы")
        result(mary).assertTextContains("4 декабря 2026", substring = true)
        search("Несуществующийпраздникщщщ")
        reveal("book_dedication_results_status").assertTextContains("Ничего не найдено", substring = true)
        compose.onNodeWithTag("book_dedication_result_${mary.minea}").assertDoesNotExist()
        compose.onNodeWithTag("book_dedication_open").assertDoesNotExist()
        assertTrue(calendar.calls.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(before, preferences.all)
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
    }

    @Test fun bothNicholasFeastsAndMaryOpenOnlyWithTheirOwnPublishedCalendarTitle() {
        launch()
        for ((index, case) in cases.withIndex()) {
            search(case.query)
            result(case).performClick()
            confirmed(case)
            assertEquals(index, opened.size)
            reveal("book_dedication_day_source").performClick()
            assertEquals("https://azbyka.ru/days/${case.date}", urls.last())
            reveal("book_dedication_source_details").performClick()
            reveal("book_dedication_minea_source").performClick()
            assertEquals(catalog.entries.single { it.mineaId == case.minea }.sourceUrl, urls.last())
            reveal("book_dedication_open").assertTextEquals("Открыть текст").performClick()
            compose.waitUntil(5_000) { openCompleted.get() == index + 1 }
            val proof = opened.last()
            assertEquals(case.id, proof.id)
            assertEquals(case.minea, proof.mineaId)
            assertEquals(case.date, proof.calendarDate)
            assertEquals(publishedTitle(case), proof.title)
            assertTrue(proof.isValid())
            assertEquals(proof, choices.find(case.id))
            assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
        }
        assertEquals(cases.map { it.url }, calendar.calls)
        assertTrue(calendar.limits.all { it == PatronalCalendarResolver.MAX_BYTES })
    }

    @Test fun aCalendarNetworkFailureHasRetryAndOpeningWaitsForTheActualSuspendResult() {
        calendar.failures.set(1)
        launch()
        search(may.query)
        result(may).performClick()
        await("book_dedication_unavailable")
        reveal("book_dedication_unavailable").assertTextContains("Проверьте связь", substring = true)
        compose.onNodeWithTag("book_dedication_open").assertDoesNotExist()
        assertNull(choices.find(may.id))
        reveal("book_dedication_retry").assertTextEquals("Проверить еще раз").performClick()
        confirmed(may)
        assertEquals(listOf(may.url, may.url), calendar.calls)
        val pendingOpen = CompletableDeferred<BookDedicationStart>()
        openGate.set(pendingOpen)
        reveal("book_dedication_open").performClick()
        compose.waitUntil(5_000) { opened.size == 1 }
        reveal("book_dedication_open").assertIsNotEnabled().assertTextEquals("Открываем текст...")
        assertEquals(0, openCompleted.get())
        assertEquals(0, dismissed.get())
        pendingOpen.complete(BookDedicationStart.STORAGE_FAILED)
        await("book_dedication_open_error")
        reveal("book_dedication_open_error").assertTextContains("Не удалось сохранить место чтения", substring = true)
        reveal("book_dedication_open").assertIsEnabled().assertTextEquals("Открыть текст").performClick()
        compose.waitUntil(5_000) { openCompleted.get() == 2 }
        assertEquals(2, opened.size)
        assertEquals(opened.first(), opened.last())
        assertEquals(2, calendar.calls.size)
        compose.onNodeWithTag("book_dedication_open_error").assertDoesNotExist()
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
    }

    @Test fun queryChangesExplicitCancelAndDismissRejectLateCalendarResponses() {
        launch()
        search(may.query)
        val oldQuery = calendar.holdNext()
        result(may).performClick()
        await("book_dedication_resolving")
        search(mary.query)
        result(mary).performClick()
        confirmed(mary)
        oldQuery.complete(Unit)
        awaitLateDelivery(1)
        confirmed(mary)
        assertNull("A cancelled May request must not remember an event after returning late", choices.find(may.id))
        assertTrue(opened.isEmpty())

        search(may.query)
        val cancelled = calendar.holdNext()
        result(may).performClick()
        await("book_dedication_resolving")
        reveal("book_dedication_cancel").assertTextEquals("Отменить проверку").performClick()
        cancelled.complete(Unit)
        awaitLateDelivery(2)
        result(may).assertIsNotSelected()
        compose.onNodeWithTag("book_dedication_confirmed").assertDoesNotExist()
        compose.onNodeWithTag("book_dedication_open").assertDoesNotExist()

        search(mary.query)
        val afterDismiss = calendar.holdNext()
        result(mary).performClick()
        await("book_dedication_resolving")
        reveal("book_dedication_close").performClick()
        afterDismiss.complete(Unit)
        awaitLateDelivery(3)
        compose.onNodeWithTag("book_dedication_chooser").assertDoesNotExist()
        assertEquals(1, dismissed.get())
        assertTrue(opened.isEmpty())
        assertEquals(0, openCompleted.get())
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
    }

    @Test fun aLocalCatalogFailureOffersAnExplicitRetryWithoutCallingTheCalendar() {
        catalogFailures.set(1)
        launch(expectCatalog = false)
        await("book_dedication_catalog_retry")
        reveal("book_dedication_catalog_retry").assertIsEnabled().performClick()
        await("book_dedication_results_status")
        search(may.query)
        result(may).assertIsDisplayed()
        assertEquals(2, catalogLoads.get())
        assertTrue(calendar.calls.isEmpty())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun aScoped320DpChooserAt200PercentKeepsImeAndCompleteConfirmationControlsReachable() {
        // This is a single Activity composition profile, not a claim about system font or a separate modal window.
        launch(font = 2f, width = 320, height = 640)
        assertEquals(320f, compose.onNodeWithTag("book_dedication_chooser").getBoundsInRoot().width.value, 1f)
        val query = reveal("book_dedication_query").assertHeightIsAtLeast(56.dp)
        query.performClick().performTextReplacement(mary.query)
        awaitKeyboard(true)
        reveal("book_dedication_query")
        assertTrue("The full field must remain inside the chooser while the actual IME is visible", fullyVisible("book_dedication_query"))
        query.performImeAction()
        awaitKeyboard(false)
        result(mary).assertHeightIsAtLeast(48.dp).performClick()
        confirmed(mary)
        result(mary)
        checkButtonLayouts("book_dedication_result_${mary.minea}", 2f)
        reveal("book_dedication_confirmed_title")
        checkLayout(compose.onNodeWithTag("book_dedication_confirmed_title"), 2f)
        reveal("book_dedication_open").assertHeightIsAtLeast(48.dp)
        checkButtonLayouts("book_dedication_open", 2f)
        val date = compose.onNodeWithText("Праздник посвящения: 4 декабря 2026")
        date.assertIsDisplayed()
        checkLayout(date, 2f)
        val gap = compose.onNodeWithTag("book_dedication_open").getUnclippedBoundsInRoot().top - date.getUnclippedBoundsInRoot().bottom
        assertTrue("The confirmation CTA needs 12 dp from its date, allowing 0.6 dp rounding", gap.value + .6f >= 12f)
        for (tag in listOf("book_dedication_day_source", "book_dedication_source_details", "book_dedication_close")) {
            reveal(tag).assertHeightIsAtLeast(48.dp)
            checkButtonLayouts(tag, 2f)
        }
        assertTrue(opened.isEmpty())
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
    }

    private fun launch(font: Float = 1f, width: Int = 360, height: Int = 780, expectCatalog: Boolean = true) {
        assertEquals("Search must use the shipped complete catalog", 1100, catalog.entries.size)
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = width; screenHeightDp = height; fontScale = font
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, font)) {
                GuideTheme(false) { if (mounted.value) Box(Modifier.size(width.dp, height.dp)) {
                    BookDedicationChooser(services, parish, "Тестовый храм - посвящение еще не выбрано", 2026,
                        PatronalProfileLookup.Absent, onOpen = { proof, expected ->
                            assertEquals(PatronalProfileLookup.Absent, expected)
                            assertEquals(proof, choices.find(proof.id))
                            opened += proof
                            val answer = openGate.getAndSet(null)?.await() ?: BookDedicationStart.OPENED
                            openCompleted.incrementAndGet()
                            answer
                        }, onDismiss = { dismissed.incrementAndGet(); mounted.value = false }, onOpenUrl = { urls += it })
                } }
            }
        }
        contentSet = true
        await("book_dedication_query")
        if (expectCatalog) await("book_dedication_results_status")
    }

    private fun search(value: String) {
        val field = reveal("book_dedication_query")
        field.performTextReplacement(value)
        field.performImeAction()
        settle()
    }
    private fun result(case: CalendarCase) = reveal("book_dedication_result_${case.minea}")
    private fun confirmed(case: CalendarCase) {
        await("book_dedication_confirmed_title")
        reveal("book_dedication_confirmed_title").assertTextEquals(publishedTitle(case))
        assertEquals(case.id, choices.find(case.id)?.id)
        if (case == may) compose.onNodeWithTag("book_dedication_confirmed_title").assertTextContains("Перенесение мощей", substring = true)
    }
    private fun reveal(tag: String): SemanticsNodeInteraction {
        if (!fullyVisible(tag)) {
            compose.onNodeWithTag("book_dedication_chooser").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
        }
        assertTrue("The complete $tag must fit in the chooser after scrolling", fullyVisible(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun fullyVisible(tag: String) = runCatching {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_dedication_chooser").getBoundsInRoot()
        bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
    }.getOrDefault(false)
    private fun checkButtonLayouts(tag: String, font: Float) {
        val labels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        assertTrue(labels.fetchSemanticsNodes().isNotEmpty())
        for (index in labels.fetchSemanticsNodes().indices) checkLayout(labels[index], font)
    }
    private fun checkLayout(node: SemanticsNodeInteraction, font: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.isNotEmpty())
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        assertTrue("Text must be fully visible above viewport/IME clipping", full.top.value + .6f >= visible.top.value &&
            full.bottom.value <= visible.bottom.value + .6f && full.left.value + .6f >= visible.left.value && full.right.value <= visible.right.value + .6f)
        for (layout in layouts) { assertTextFitsLayout(layout, layout.layoutInput.text.text); assertEquals(font, layout.layoutInput.density.fontScale, .001f) }
    }
    private fun awaitKeyboard(visible: Boolean) {
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity {
                val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
            }
            matches
        }
        settle()
    }
    private fun awaitLateDelivery(count: Int) {
        compose.waitUntil(5_000) { calendar.lateDelivered.get() == count && calendar.inFlight.get() == 0 }
        settle()
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }

    private fun fixtureBytes(case: CalendarCase): ByteArray {
        val bytes = instrumentation.context.assets.open("dedication/${case.fixture}").use { it.readBytes() }
        val provenance = instrumentation.context.assets.open("dedication/provenance.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val record = provenance.getAsJsonArray("fixtures").single { it.asJsonObject["fixture"].asString == case.fixture }.asJsonObject
        assertEquals(record["fixtureSha256"].asString, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertEquals(record["fixtureBytes"].asInt, bytes.size)
        assertEquals(case.id, record["confirmedEventId"].asString)
        assertEquals(case.minea, record["mineaId"].asString)
        return bytes
    }
    private fun publishedTitle(case: CalendarCase) = JsonParser.parseString(fixtureBytes(case).toString(Charsets.UTF_8)).asJsonObject
        .getAsJsonArray("events").single { it.asJsonObject["id"].asString == case.id }.asJsonObject["title"].asString
    private data class CalendarCase(val minea: String, val id: String, val date: String, val query: String, val fixture: String) {
        val url get() = "${PatronalCalendarResolver.BASE_URL}/$date/"
    }
    private val may = CalendarCase("11183", "111300", "2026-05-22", "Николай Чудотворец", "calendar-nicholas-may-2026-05-22.json")
    private val december = CalendarCase("9906", "129540", "2026-12-19", "Николай Чудотворец", "calendar-nicholas-dec-2026-12-19.json")
    private val mary = CalendarCase("14581", "128390", "2026-12-04", "Введение Богородицы", "calendar-presentation-mary-2026-12-04.json")
    private val cases get() = listOf(may, december, mary)
    private inner class CalendarTransport(private val fixture: (CalendarCase) -> ByteArray) : ScheduleTransport {
        val calls = CopyOnWriteArrayList<String>()
        val limits = CopyOnWriteArrayList<Int>()
        val failures = AtomicInteger()
        val inFlight = AtomicInteger()
        val lateDelivered = AtomicInteger()
        private val next = AtomicReference<CompletableDeferred<Unit>?>(null)
        private val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        fun holdNext() = CompletableDeferred<Unit>().also { check(next.compareAndSet(null, it)); gates += it }
        fun releaseAll() { gates.forEach { it.complete(Unit) } }
        override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
            limits += maximumBytes
            return fetch(url).also { check(it.bytes.size <= maximumBytes) }
        }
        override suspend fun fetch(url: String): ScheduleResponse {
            calls += url
            val case = cases.single { it.url == url }
            inFlight.incrementAndGet()
            try {
                if (failures.getAndUpdate { maxOf(0, it - 1) } > 0) throw IOException("Controlled calendar network failure")
                next.getAndSet(null)?.let { gate ->
                    // Deliver real bytes even after caller cancellation, so the resolver/UI must reject the stale answer.
                    withContext(NonCancellable) { gate.await(); lateDelivered.incrementAndGet() }
                }
                return ScheduleResponse(url, fixture(case), "text/html; charset=UTF-8")
            } finally { inFlight.decrementAndGet() }
        }
    }
}
