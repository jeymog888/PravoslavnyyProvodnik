package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real AppNavigation/repository/providers with saved primary bytes; no live HTTP or OCR claim. */
@RunWith(Parameterized::class)
class ManualOtherSourceSearchFlowTest(private val dark: Boolean, private val churchPage: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val gson = Gson()
    private val runId = "manual-other-source-${UUID.randomUUID()}"
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val originalUserPreferences = listOf("guide_preferences", "service_book_reading", "internet_schedule_cache")
        .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    private val active = mutableStateOf<Case?>(null)
    private var contentSet = false
    private val cases = mutableListOf<Case>()
    private val replay by lazy { JsonParser.parseString(asset("manual-other-source-search/manifest.json").toString(Charsets.UTF_8)).asJsonObject }
    private val mapRows by lazy { replay["map"].asJsonArray.map { it.asJsonObject } }
    private val legacyRows by lazy { replay["legacy"].asJsonArray.map { it.asJsonObject } }
    private fun asset(path: String) = assets.open(path).use { it.readBytes() }
    private fun existing(name: String): ByteArray = asset("contact-wiring/$name").also {
        assertEquals(name, replay["existingAssetsSha256"].asJsonObject["contact-wiring/$name"].asString, ScheduleSourceCandidate.sha(it))
    }
    private fun redacted(url: String) = url.replace(Regex("([?&]token=)[^&]*"), "$1REDACTED")

    private inner class Case(val label: String) {
        val id = "$runId-$label-${UUID.randomUUID()}"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val owned = "$id-$name"
                preferenceNames += owned
                return context.getSharedPreferences(owned, mode)
            }
        }
        val preferences = isolatedContext.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
        private val cachePreferences = isolatedContext.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String): String? = cachePreferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(cachePreferences.edit().putString(parishId, value).commit()) }
            override fun writeSafety(parishId: String, value: String) {
                safetyWrites.incrementAndGet()
                write(parishId, value)
            }
        }
        val safetyWrites = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()
        val refreshCalls = AtomicInteger()
        val refreshCompleted = AtomicInteger()
        val searchCalls = AtomicInteger()
        val searchCompleted = AtomicInteger()
        val confirmations = CopyOnWriteArrayList<String>()
        val confirmed = CopyOnWriteArrayList<String>()
        val directPools = AtomicInteger()
        val websitePools = AtomicInteger()
        val scheduleStops = AtomicInteger()
        private val instant = AtomicReference(Instant.parse("2026-11-14T10:00:00Z"))
        val renderClock = mutableStateOf<Clock>(Clock.fixed(instant.get(), ZoneOffset.UTC))
        private val providerClock = object : Clock() {
            override fun instant(): Instant = instant.get()
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        }
        private val oracle = JsonParser.parseString(existing("selected-oracle.json").toString(Charsets.UTF_8)).asJsonObject["selected"].asJsonObject
        private val point = oracle["center"].asJsonObject
        val saved = SavedChurchRecord(oracle["id"].asString, oracle["name"].asString, "", "", "Europe/Moscow",
            point["latitude"].asDouble, point["longitude"].asDouble, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            sourceUrl = oracle["sourceUrl"].asString)
        val parish = saved.asParish()
        private val contactManifest = JsonParser.parseString(existing("manifest.json").toString(Charsets.UTF_8)).asJsonObject
        private val publishedInputs = contactManifest["inputs"].asJsonArray.map { it.asJsonObject }
        val contactUrl = publishedInputs.single { it["asset"].asString == "contacts.html" }["url"].asString
        val scheduleUrl = publishedInputs.single { it["asset"].asString == "schedule.html" }["url"].asString
        private val owners = ParishRepository(existing("curated.json").toString(Charsets.UTF_8)).directory.churches
        private val rawPools = BundledPublisherPoolFactories(
            BundledCuratedPublisherMetadata { existing("curated.json").inputStream() },
            BundledChurchCatalog({ error("The useful direct contact must not need the large catalog") }, providerClock))
        private val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { directPools.incrementAndGet(); return rawPools.direct(parish) }
            override suspend fun websites(parish: Parish): PublisherSeedPool { websitePools.incrementAndGet(); return rawPools.websites(parish) }
            override suspend fun curatedOwners(): List<Parish> = rawPools.curatedOwners()
        }
        private val network = ScheduleTransport { url ->
            requests += redacted(url)
            when {
                mapRows.any { redacted(it["requestedUrl"].asString) == redacted(url) } -> {
                    val row = mapRows.single { redacted(it["requestedUrl"].asString) == redacted(url) }
                    sealed(row, if (row["responseUrl"].asString.contains("token=REDACTED")) url else row["responseUrl"].asString)
                }
                legacyRows.any { it["requestedUrl"].asString == url || it["responseUrl"].asString == url } ->
                    sealed(legacyRows.first { it["requestedUrl"].asString == url || it["responseUrl"].asString == url })
                url == contactUrl -> {
                    val row = publishedInputs.single { it["asset"].asString == "contacts.html" }
                    val body = existing("contacts.html")
                    assertEquals(row["sha256"].asString, ScheduleSourceCandidate.sha(body))
                    ScheduleResponse(url, body, row["contentType"].asString)
                }
                url == scheduleUrl -> {
                    scheduleStops.incrementAndGet()
                    throw IOException("This UI test ends at the linked schedule request; actual OCR/book has its own native test")
                }
                else -> throw IOException("No replay response; live HTTP is disabled for this test")
            }
        }
        val repository = InternetScheduleRepository(network, cache,
            siteResolver = productionScheduleSiteResolver(network, MemoryOfficialDirectoryCacheStore(),
                moscowSearch = MoscowDirectorySearchTransport { throw IOException("Heavy search is outside this replay") },
                ownPools = pools, clock = providerClock), curatedPublishers = { owners })
        // Recording only: all three actions call the real repository unchanged.
        val schedules = object : InternetSchedules by repository {
            // This suite isolates the explicit manual entry. Automatic production entry
            // has its own frame/lifecycle/cancellation suite using the real hook below.
            override fun takeSourceContinuation(parish: Parish, now: Instant, delivered: InternetScheduleState,
                owner: Any): ScheduleSourceContinuation? = null
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
                refreshCalls.incrementAndGet()
                return repository.refresh(parish, now, force, sourceOverride).also { refreshCompleted.incrementAndGet() }
            }
            override suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState {
                searchCalls.incrementAndGet()
                return repository.searchOtherSources(parish, now).also { searchCompleted.incrementAndGet() }
            }
            override suspend fun confirmCandidate(parish: Parish, candidateId: String, now: Instant): InternetScheduleState {
                confirmations += candidateId
                return repository.confirmCandidate(parish, candidateId, now).also { confirmed += candidateId }
            }
        }
        val books = NavigationMigrationBooks()
        val container = AppContainer(isolatedContext, scheduleOverride = schedules, booksOverride = books)
        init {
            assertTrue(ChurchDirectoryStore(preferences).save(saved))
            assertTrue(preferences.edit().putString("church_id", parish.id).putBoolean("dark", dark).commit())
            cases += this
        }
        fun rollover() {
            instant.set(instant.get().plus(1, ChronoUnit.DAYS))
            renderClock.value = Clock.fixed(instant.get(), ZoneOffset.UTC)
        }
        fun state() = repository.cached(parish)
        fun contactsFetched() = requests.count { it == contactUrl }
        fun own() = state().sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(parish) }
        fun sealed(row: JsonObject, responseUrl: String = row["responseUrl"].asString): ScheduleResponse {
            val body = asset("manual-other-source-search/" + row["asset"].asString)
            assertEquals(row["sha256"].asString, ScheduleSourceCandidate.sha(body))
            assertEquals(row["bytes"].asInt, body.size)
            return ScheduleResponse(responseUrl, body, row["contentType"].asString)
        }
    }

    @After fun clean() {
        try {
            if (contentSet) { compose.runOnIdle { active.value = null }; compose.waitForIdle() }
            for (case in cases) report(case)
        } finally {
            preferenceNames.forEach { context.deleteSharedPreferences(it) }
            originalUserPreferences.forEach { (name, before) ->
                assertEquals("The user's $name must remain unchanged", before, context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            }
        }
    }

    @Test fun theExistingRefreshFindsASeparateOwnOfferAndOnlyExplicitConfirmationRechecksIt() {
        val case = Case("unconfirmed")
        mount(case)
        awaitInitialMap(case)
        val before = case.state()
        assertEquals(0, case.directPools.get())
        assertEquals(0, case.contactsFetched())
        openOtherSourceSearch()
        reveal("church_content", "find_other_schedule_sources").assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        awaitSearch(case)
        assertEquals(1, case.searchCalls.get())
        assertProtected(before, case.state())
        assertTrue(case.directPools.get() > 0)
        assertTrue(case.contactsFetched() > 0)
        assertFalse(legacyRows.any { row -> case.requests.contains(row["requestedUrl"].asString) })
        val own = case.own()
        openSources()
        reveal("church_content", "confirm_candidate_${own.id}").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        assertWholeWords("confirm_candidate_${own.id}", "Выбрать сайт")
        val open = compose.onNodeWithTag("open_candidate_${own.id}").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag("confirm_candidate_${own.id}").getUnclippedBoundsInRoot()
        assertTrue("Source actions need at least12dp", confirm.top - open.bottom >= 12.dp)
        capture(case, "own-offer")
        assertNull(case.state().siteBinding)
        assertNull(case.state().publication)
        val contactsBefore = case.contactsFetched()
        compose.onNodeWithTag("confirm_candidate_${own.id}").performTouchInput { click(center) }
        compose.waitUntil(15_000) { own.id in case.confirmed }
        assertEquals(listOf(own.id), case.confirmations.toList())
        assertTrue("Exact Own confirmation must read current contacts again", case.contactsFetched() > contactsBefore)
        assertEquals(1, case.scheduleStops.get())
        assertTrue(case.requests.lastIndexOf(case.contactUrl) < case.requests.indexOf(case.scheduleUrl))
        assertNull(case.state().publication)
        assertEquals(1, case.searchCalls.get())
        assertTrue(case.books.requests.isEmpty())
    }

    @Test fun failedMapSourcesStillExposeOwnAndManualIntentDoesNotReplayAfterLeavingRestoreOrRollover() {
        // Each run starts from a separate private cache. Both actual URLs are tested
        // in each entry/theme rather than relying on one site's successful result.
        var restoration: StateRestorationTester? = null
        for ((index, legacy) in legacyRows.withIndex()) {
            val case = Case("failed-$index")
            if (restoration == null) restoration = mount(case) else {
                compose.runOnIdle { active.value = null }; settle()
                compose.runOnIdle { active.value = case }; settle()
            }
            awaitInitialMap(case)
            openSources()
            val candidate = case.state().sourceCandidates.first { it.sourceUrl == legacy["requestedUrl"].asString && it.canConfirm(case.parish) }
            reveal("church_content", "confirm_candidate_${candidate.id}").performTouchInput { click(center) }
            compose.waitUntil(15_000) { candidate.id in case.confirmed }
            assertTrue(case.requests.contains(legacy["requestedUrl"].asString))
            assertNull(case.state().publication)
            val before = case.state()
            assertTrue(before.customSource.isBlank())
            openOtherSourceSearch()
            reveal("church_content", "find_other_schedule_sources").performTouchInput { click(center) }
            awaitSearch(case)
            assertEquals(1, case.searchCalls.get())
            val own = case.own()
            assertProtected(before, case.state())
            openSources()
            reveal("church_content", "confirm_candidate_${own.id}").assertIsDisplayed().assertIsEnabled()
            capture(case, "after-failed-map")
            openOtherSourceSearch()
            val held = requireNotNull(reveal("church_content", "find_other_schedule_sources").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_content").assertIsDisplayed()
            compose.runOnIdle { held() }; settle()
            assertEquals("A callback from the previous screen must be ignored", 1, case.searchCalls.get())
            requireNotNull(restoration).emulateSavedInstanceStateRestore(); settle()
            compose.onNodeWithTag("settings_content").assertIsDisplayed()
            compose.runOnIdle { held() }; settle()
            assertEquals("Saved-state restoration must not replay manual intent", 1, case.searchCalls.get())
            openOtherSourceSearch()
            compose.runOnIdle { held() }; settle()
            assertEquals("An old owner cannot become valid again after return", 1, case.searchCalls.get())
            val refreshes = case.refreshCompleted.get()
            compose.runOnIdle { case.rollover() }
            compose.waitUntil(15_000) { case.refreshCompleted.get() > refreshes }
            assertEquals("Rollover is ordinary refresh, never the consumed manual action", 1, case.searchCalls.get())
            assertTrue(case.books.requests.isEmpty())
        }
    }

    private val entryContent get() = if (churchPage) "church_content" else "main_content"
    private fun mount(case: Case): StateRestorationTester {
        active.value = case
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                active.value?.let { current -> key(current.id) {
                    val native = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides current.isolatedContext,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalDensity provides Density(native.density, 2f)) {
                        Box(Modifier.size(320.dp, 640.dp)) { AppNavigation(current.container, current.renderClock.value) }
                    }
                } }
            }
            contentSet = true
        }
    }
    private fun awaitInitialMap(case: Case) {
        compose.waitUntil(15_000) { case.refreshCompleted.get() > 0 && case.state().sourceCandidates.size == 3 }
        settle()
        assertEquals(setOf(OfficialMapCandidateProvider.PROVIDER, ElitsyMapCandidateProvider.PROVIDER), case.state().sourceCandidates.map { it.provider }.toSet())
        assertTrue(case.state().sourceCandidates.all { it.canConfirm(case.parish) && it.ownContact == null })
        assertNull(case.state().siteBinding); assertNull(case.state().publication)
        assertEquals(0, case.searchCalls.get()); assertEquals(0, case.directPools.get())
        assertEquals(7, case.requests.size)
    }
    private fun awaitSearch(case: Case) {
        compose.waitUntil(15_000) { case.searchCompleted.get() == 1 }
        settle()
        assertTrue(case.state().sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(case.parish) })
    }
    private fun assertProtected(before: InternetScheduleState, after: InternetScheduleState) {
        assertEquals("Discovery may only change candidate metadata and diagnostics", before, after.copy(
            sourceCandidates = before.sourceCandidates, candidateSearchIdentity = before.candidateSearchIdentity,
            candidateObservedIds = before.candidateObservedIds, candidateSearchIncomplete = before.candidateSearchIncomplete,
            candidateSearchUnavailable = before.candidateSearchUnavailable))
    }
    private fun openEntry() {
        compose.onNodeWithTag(if (churchPage) "nav_church" else "nav_home").performClick()
        compose.onNodeWithTag(entryContent).assertIsDisplayed()
    }
    private fun openOtherSourceSearch() {
        openEntry()
        if (!churchPage) reveal("main_content", "today_review_schedule_sources").performClick()
        reveal("church_content", "find_other_schedule_sources").assertIsDisplayed()
    }
    private fun openSources() {
        compose.onNodeWithTag("nav_church").performClick()
        reveal("church_content", "schedule_sources_heading").assertIsDisplayed()
    }
    private fun reveal(containerTag: String, tag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        container.performScrollToIndex(0)
        repeat(40) {
            settle()
            val target = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if (target != null) {
                val viewport = container.getBoundsInRoot()
                val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) return compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            container.performTouchInput { swipe(Offset(centerX, height * .82f), Offset(centerX, height * .48f), durationMillis = 150) }
        }
        throw AssertionError("Could not reveal $tag in $containerTag")
    }
    private fun assertWholeWords(buttonTag: String, text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        // Other source cards can carry the same label. Inspect only this
        // actual text node inside the chosen Own action, not a global text hit.
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(buttonTag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTextFitsLayout(layout, text)
        for (word in Regex("[^ ]+").findAll(text))
            assertEquals("A word must not break by letters", layout.getLineForOffset(word.range.first), layout.getLineForOffset(word.range.last))
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
    private fun output(case: Case) = File(context.getExternalFilesDir("manual-other-source-search"), case.id).apply { mkdirs() }
    private fun capture(case: Case, name: String) {
        File(output(case), "$name-dark${dark}-church${churchPage}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun report(case: Case) = File(output(case), "receipt.json").writeText(gson.toJson(mapOf(
        "scope" to "Controlled saved HTTP replay; production AppNavigation/repository/resolver/providers, no live HTTP/OCR/book claim",
        "dark" to dark, "fontScale" to 2f, "widthDp" to 320, "heightDp" to 640, "churchPage" to churchPage,
        "manualCalls" to case.searchCalls.get(), "manualCompleted" to case.searchCompleted.get(), "refreshCalls" to case.refreshCalls.get(),
        "confirmations" to case.confirmations.toList(), "confirmationCompleted" to case.confirmed.toList(), "requests" to case.requests.toList(),
        "directPools" to case.directPools.get(), "websitePools" to case.websitePools.get(), "scheduleStops" to case.scheduleStops.get(),
        "safetyWrites" to case.safetyWrites.get(), "finalCacheSha256" to case.cache.read(case.parish.id)?.let { ScheduleSourceCandidate.sha(it) })))
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},church={1}")
        fun configurations() = listOf(false, true).flatMap { dark -> listOf(false, true).map { arrayOf<Any>(dark, it) } }
    }
}
