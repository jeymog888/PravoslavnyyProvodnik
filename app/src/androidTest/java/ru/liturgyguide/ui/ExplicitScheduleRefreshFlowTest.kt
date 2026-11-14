package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.schedule.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual AppNavigation callbacks, with separate recorded access methods; repository import has its own unit regression. */
@RunWith(Parameterized::class)
class ExplicitScheduleRefreshFlowTest(private val dark: Boolean, private val churchPage: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val protectedPreferences = listOf("guide_preferences", "service_book_reading", "internet_schedule_cache")
        .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    private val active = mutableStateOf<Case?>(null)
    private var restoration: StateRestorationTester? = null
    private val now = Instant.parse("2026-11-14T21:35:00Z")
    private data class RefreshCall(val fingerprint: String, val force: Boolean, val sourceOverride: String?)

    private inner class Case(retainedMap: Boolean) {
        val id = "explicit-refresh-${UUID.randomUUID()}"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val owned = "$id-$name"; preferenceNames += owned
                return context.getSharedPreferences(owned, mode)
            }
        }
        val preferences = isolatedContext.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        // Exact identity and completed state shapes captured by main-fresh-final/live-export.
        val saved = SavedChurchRecord("osm:way:155168069", "Архиерейское подворье. Храм в честь Вознесения Господня",
            "", "", "Europe/Moscow", 54.5305961, 36.3084198, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            sourceUrl = "https://www.openstreetmap.org/way/155168069")
        val parish = saved.asParish()
        val map = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(parish),
            provider = OfficialMapCandidateProvider.PROVIDER, providerLabel = "Официальная карта храмов", providerObjectId = "20385",
            name = "Храм Вознесения Господня г. Калуга", address = "Россия, г. Калуга, мкр. Тайфун, ул. Молодежная",
            sourceUrl = "http://kalujskoe-19.cerkov.ru/", evidenceUrl = "https://map.patriarchia.ru/?infoid=20385",
            evidenceSha256 = "806ab496719f2e184ae526a0feaf1ba36e91dc3caf3dc3bd70a96edc8fb7173c",
            checkedAt = "2026-11-14T21:32:36.392131Z", latitude = 54.5307148, longitude = 36.3086205,
            outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте, что на этом сайте опубликовано расписание выбранного храма")
        @Volatile var state = InternetScheduleState(parish.id, InternetScheduleStatus.UNAVAILABLE,
            checkedAt = if (retainedMap) "2026-11-14T21:32:34.916830Z" else "2026-11-14T21:32:11.335915Z",
            sourceCandidates = if (retainedMap) listOf(map) else emptyList(),
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish), candidateSearchUnavailable = true,
            candidateSearchIncomplete = true, candidateObservedIds = if (retainedMap) listOf("official_map:20385") else emptyList())
        val refreshes = CopyOnWriteArrayList<RefreshCall>()
        val searches = AtomicInteger()
        var holdNextRefresh: CompletableDeferred<Unit>? = null
        val heldRefreshEntered = CompletableDeferred<Unit>()
        val schedules = object : InternetSchedules {
            override fun cached(parish: Parish) = state.also { assertEquals(this@Case.parish.id, parish.id) }
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
                refreshes += RefreshCall(ScheduleEntityIdentity.fingerprint(parish), force, sourceOverride)
                val gate = holdNextRefresh; holdNextRefresh = null
                if (gate != null) { heldRefreshEntered.complete(Unit); gate.await() }
                return state
            }
            override suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState {
                assertEquals(this@Case.parish, parish); searches.incrementAndGet()
                state = state.copy(candidateSearchIncomplete = false)
                return state
            }
        }
        val books = NavigationMigrationBooks()
        val container = AppContainer(isolatedContext, scheduleOverride = schedules, booksOverride = books)
        init {
            assertTrue(map.canConfirm(parish))
            assertTrue(ChurchDirectoryStore(preferences).save(saved))
            assertTrue(preferences.edit().putString("church_id", parish.id).putBoolean("dark", dark).commit())
        }
    }

    @After fun cleanup() {
        try {
            if (restoration != null) { compose.runOnIdle { active.value = null }; compose.waitForIdle() }
        } finally {
            preferenceNames.forEach { context.deleteSharedPreferences(it) }
            protectedPreferences.forEach { (name, before) ->
                assertEquals("The user's $name must remain unchanged", before, context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            }
        }
    }

    @Test fun actualRefreshUsesNormalCallbackForCompletedEmptyAndRetainedMap() {
        for (retained in listOf(false, true)) {
            val case = Case(retained); mount(case); openEntry()
            if (!retained) {
                compose.onNodeWithTag("nav_church").performClick(); settle()
                compose.onNodeWithTag("find_other_schedule_sources").assertDoesNotExist()
                openEntry()
            }
            val before = case.state; val calls = case.refreshes.size
            reveal(entryContent, refreshTag).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { case.refreshes.size == calls + 1 }; settle()
            assertEquals(0, case.searches.get())
            assertEquals(RefreshCall(ScheduleEntityIdentity.fingerprint(case.parish), true, null), case.refreshes.last())
            assertEquals(before, case.state)
            assertNull(case.state.publication); assertNull(case.state.siteBinding)
            assertTrue(case.books.requests.isEmpty())
        }
    }

    @Test fun onlyTheSecondaryActionSearchesAndHeldRefreshAndSearchCannotCrossAnOwnerChange() {
        val case = Case(true); mount(case); openOtherSourceSearch()
        val search = reveal("church_content", "find_other_schedule_sources").assertHeightIsAtLeast(48.dp)
        val refreshBottom = compose.onNodeWithTag("refresh_schedule").getUnclippedBoundsInRoot().bottom
        assertTrue("Secondary search needs at least12dp separation", search.getUnclippedBoundsInRoot().top - refreshBottom >= 12.dp)
        val before = case.state; val initialCalls = case.refreshes.size
        search.performClick(); compose.waitUntil(5_000) { case.searches.get() == 1 }; settle()
        assertEquals(initialCalls, case.refreshes.size)
        assertEquals(before, case.state.copy(candidateSearchIncomplete = before.candidateSearchIncomplete))
        assertNull(case.state.publication); assertNull(case.state.siteBinding)
        val heldSearch = clickAction(reveal("church_content", "find_other_schedule_sources"))
        val heldRefresh = clickAction(compose.onNodeWithTag("refresh_schedule"))
        compose.onNodeWithTag("open_settings").performClick(); settle()
        var baseline = case.refreshes.size
        compose.runOnIdle { heldRefresh(); heldSearch() }; settle()
        assertEquals(baseline, case.refreshes.size); assertEquals(1, case.searches.get())
        requireNotNull(restoration).emulateSavedInstanceStateRestore(); settle()
        baseline = case.refreshes.size
        compose.runOnIdle { heldRefresh(); heldSearch() }; settle()
        assertEquals(baseline, case.refreshes.size); assertEquals(1, case.searches.get())
        compose.onNodeWithTag("nav_church").performClick(); settle()
        baseline = case.refreshes.size
        compose.runOnIdle { heldRefresh(); heldSearch() }; settle()
        assertEquals(baseline, case.refreshes.size); assertEquals(1, case.searches.get())
        val freshSearch = clickAction(reveal("church_content", "find_other_schedule_sources"))
        val freshRefresh = clickAction(compose.onNodeWithTag("refresh_schedule"))
        val release = CompletableDeferred<Unit>(); case.holdNextRefresh = release
        // The first click rotates the surface synchronously; the held search cannot win the next effect.
        compose.runOnIdle { freshRefresh(); freshSearch() }
        compose.waitUntil(5_000) { case.heldRefreshEntered.isCompleted }; settle()
        assertEquals(baseline + 1, case.refreshes.size); assertEquals(1, case.searches.get())
        reveal("church_content", "find_other_schedule_sources").assertIsNotEnabled()
        compose.onNodeWithTag("refresh_schedule").assertIsNotEnabled()
        compose.runOnIdle { release.complete(Unit) }; settle()
        assertTrue(case.books.requests.isEmpty())
    }

    @Test fun missingOptionalSearchHandlerHidesOnlySearchAndPendingNormalRefreshStillWorks() {
        val case = Case(true); val refreshCount = AtomicInteger()
        assertTrue(permitsOtherSourceSearch(case.parish, case.state))
        active.value = case
        restoration = StateRestorationTester(compose).also { it.setContent {
            active.value?.let { current ->
                val native = LocalDensity.current
                CompositionLocalProvider(LocalContext provides current.isolatedContext,
                    LocalActivityResultRegistryOwner provides compose.activity,
                    LocalDensity provides Density(native.density, 2f)) {
                    GuideTheme(dark) { Box(Modifier.size(320.dp, 640.dp)) {
                        ChurchScreen(listOf(current.parish), current.parish.id, LocalDate.of(2026, 11, 15),
                            onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED },
                            onOpenUrl = {}, onBack = {}, showBack = churchPage,
                            searchQuery = "", onSearchQueryChange = {}, internetSchedule = current.state,
                            sourceReviewParish = current.parish, now = now,
                            onConfirmScheduleCandidate = { fail("Refresh must not confirm a candidate") },
                            onRefreshSchedule = { refreshCount.incrementAndGet() })
                    } }
                }
            }
        } }
        // The pending panel is really mounted and eligible; null support alone must hide search.
        reveal("church_content", "schedule_sources_heading").assertIsDisplayed()
        val refresh = reveal("church_content", "refresh_schedule").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("church_sources_card").assertExists()
        compose.onNodeWithTag("find_other_schedule_sources").assertDoesNotExist()
        assertEquals(0, refreshCount.get())
        refresh.performClick(); settle()
        assertEquals(1, refreshCount.get())
        assertTrue(case.books.requests.isEmpty())
    }

    private val entryContent get() = if (churchPage) "church_content" else "main_content"
    private val refreshTag get() = if (churchPage) "refresh_schedule" else "today_refresh_schedule"
    private fun mount(case: Case) {
        if (restoration == null) {
            active.value = case
            restoration = StateRestorationTester(compose).also { it.setContent {
                active.value?.let { current -> key(current.id) {
                    val native = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides current.isolatedContext,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalDensity provides Density(native.density, 2f)) {
                        Box(Modifier.size(320.dp, 640.dp)) { AppNavigation(current.container, current.clock) }
                    }
                } }
            } }
        } else compose.runOnIdle { active.value = case }
        compose.waitUntil(5_000) { case.refreshes.isNotEmpty() }; settle()
    }
    private fun openEntry() {
        compose.onNodeWithTag(if (churchPage) "nav_church" else "nav_home").performClick(); settle()
        compose.onNodeWithTag(entryContent).assertIsDisplayed()
    }
    private fun openOtherSourceSearch() {
        openEntry()
        if (!churchPage) reveal("main_content", "today_review_schedule_sources").performClick()
        reveal("church_content", "find_other_schedule_sources").assertIsDisplayed()
    }
    private fun clickAction(node: SemanticsNodeInteraction) = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun reveal(containerTag: String, tag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag); container.performScrollToIndex(0)
        repeat(40) {
            settle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1) {
                val viewport = container.getBoundsInRoot(); val target = compose.onNodeWithTag(tag)
                val bounds = target.getUnclippedBoundsInRoot()
                if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) return target.assertIsDisplayed()
            }
            container.performTouchInput { swipe(Offset(centerX, height * .82f), Offset(centerX, height * .48f), durationMillis = 150) }
        }
        throw AssertionError("Could not reveal $tag in $containerTag")
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},church={1}")
        fun configurations() = listOf(false, true).flatMap { dark -> listOf(false, true).map { arrayOf<Any>(dark, it) } }
    }
}
