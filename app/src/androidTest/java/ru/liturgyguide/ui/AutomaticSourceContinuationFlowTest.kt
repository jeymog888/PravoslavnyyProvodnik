package ru.liturgyguide.ui

import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class AutomaticSourceContinuationFlowTest(private val dark: Boolean, private val churchPage: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val gson = Gson()
    private val runId = "automatic-other-source-${UUID.randomUUID()}"
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
        val externalStarts = CopyOnWriteArrayList<String>()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { externalStarts += intent.dataString.orEmpty() }
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
            override fun write(parishId: String, value: String) {
                check(cachePreferences.edit().putString(parishId, value).commit()); cacheWrites.incrementAndGet()
            }
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
        val refreshCancelled = AtomicInteger()
        val cacheWrites = AtomicInteger()
        var prepareUiWithoutRefresh = false
        var holdBeforeCommit = false
        val preCommitRequests = AtomicInteger()
        val httpByRefresh = ConcurrentHashMap<String, AtomicInteger>()
        val firstRequestEntered = CompletableDeferred<Unit>()
        val secondRequestEntered = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val releaseSecondRequest = CompletableDeferred<Unit>()
        val fastCommitted = CompletableDeferred<Unit>()
        val releaseFast = CompletableDeferred<Unit>()
        val ownEntered = CompletableDeferred<Unit>()
        val releaseOwn = CompletableDeferred<Unit>()
        val automaticCalls = AtomicInteger()
        val automaticCompleted = AtomicInteger()
        val automaticCancelled = AtomicInteger()
        val automaticFinished = AtomicInteger()
        val presentation = AtomicReference<AutomaticScheduleSourcePresentation?>(null)
        val frameProofs = CopyOnWriteArrayList<Map<String, Any?>>()
        val frameHandoff = CopyOnWriteArrayList<Map<String, Any?>>()
        var partialFailure = false
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
            override suspend fun websites(parish: Parish): PublisherSeedPool {
                websitePools.incrementAndGet()
                return if (partialFailure) LocalPublisherSeedPool(emptyList()) else rawPools.websites(parish)
            }
            override suspend fun curatedOwners(): List<Parish> = rawPools.curatedOwners()
        }
        private val network = ScheduleTransport { url ->
            requests += redacted(url)
            val origin = currentCoroutineContext()[CoroutineName]?.name.orEmpty()
            httpByRefresh.computeIfAbsent(origin) { AtomicInteger() }.incrementAndGet()
            // Hold a Map request inside its existing 30s envelope, not the 3s OSM prelude.
            val firstMapUrl = if (holdBeforeCommit) mapRows.first {
                !it["requestedUrl"].asString.startsWith("https://api.openstreetmap.org/")
            }["requestedUrl"].asString else ""
            if (holdBeforeCommit && redacted(url) == redacted(firstMapUrl)) {
                when (preCommitRequests.incrementAndGet()) {
                    1 -> { firstRequestEntered.complete(Unit); releaseFirstRequest.await() }
                    2 -> { secondRequestEntered.complete(Unit); releaseSecondRequest.await() }
                }
            }
            when {
                mapRows.any { redacted(it["requestedUrl"].asString) == redacted(url) } -> {
                    val row = mapRows.single { redacted(it["requestedUrl"].asString) == redacted(url) }
                    sealed(row, if (row["responseUrl"].asString.contains("token=REDACTED")) url else row["responseUrl"].asString)
                }
                legacyRows.any { it["requestedUrl"].asString == url || it["responseUrl"].asString == url } ->
                    sealed(legacyRows.first { it["requestedUrl"].asString == url || it["responseUrl"].asString == url })
                url == contactUrl -> {
                    // Only the automatic first contact is held; a later explicit
                    // confirmation obtains a genuinely separate current response.
                    if (contactsFetched() == 1) {
                        ownEntered.complete(Unit)
                        releaseOwn.await()
                        if (partialFailure) throw IOException("Controlled partial Own outage")
                    }
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
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
                val number = refreshCalls.incrementAndGet()
                if (prepareUiWithoutRefresh) return repository.cached(parish).also { refreshCompleted.incrementAndGet() }
                return try {
                    val result = withContext(CoroutineName("refresh-$number")) { repository.refresh(parish, now, force, sourceOverride) }
                    holdFirstDelivery(result).also { refreshCompleted.incrementAndGet() }
                } catch (cancelled: CancellationException) { refreshCancelled.incrementAndGet(); throw cancelled }
            }
            override fun takeSourceContinuation(parish: Parish, now: Instant, delivered: InternetScheduleState,
                owner: Any): ScheduleSourceContinuation? = repository.takeSourceContinuation(parish, now, delivered, owner).also {
                if (it != null) presentation.set(owner as AutomaticScheduleSourcePresentation)
            }
            override suspend fun continueSourceSearch(parish: Parish, now: Instant, ticket: ScheduleSourceContinuation,
                owner: Any): InternetScheduleState? {
                automaticCalls.incrementAndGet()
                val value = owner as AutomaticScheduleSourcePresentation
                frameProofs += mapOf("drawAcknowledged" to (value.witness != null), "frameAfterDraw" to value.frameAfterDraw,
                    "delivery" to System.identityHashCode(value.delivered), "presentation" to System.identityHashCode(value),
                    "screen" to value.view.screen, "day" to value.day, "sequence" to value.sequence,
                    "stripBoundsPx" to value.witness?.strip?.toString(), "actionBoundsPx" to value.witness?.action?.toString())
                // A real change of checkedAt, keeping the date and all published Map facts.
                instant.set(instant.get().plusSeconds(1))
                return try {
                    repository.continueSourceSearch(parish, now, ticket, owner).also { automaticCompleted.incrementAndGet() }
                } catch (cancelled: CancellationException) { automaticCancelled.incrementAndGet(); throw cancelled }
                finally { automaticFinished.incrementAndGet() }
            }
            override suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState {
                searchCalls.incrementAndGet()
                return holdFirstDelivery(repository.searchOtherSources(parish, now)).also { searchCompleted.incrementAndGet() }
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
        private suspend fun holdFirstDelivery(result: InternetScheduleState): InternetScheduleState {
            if (!fastCommitted.isCompleted) { fastCommitted.complete(Unit); releaseFast.await() }
            return result
        }
        fun rollover() {
            instant.set(instant.get().plus(1, ChronoUnit.DAYS))
            renderClock.value = Clock.fixed(instant.get(), ZoneOffset.UTC)
        }
        fun raw() = cache.read(parish.id)
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
            compose.mainClock.autoAdvance = true
            if (contentSet) { compose.runOnIdle { active.value = null }; compose.waitForIdle() }
            for (case in cases) {
                case.releaseFast.complete(Unit); case.releaseOwn.complete(Unit)
                case.releaseFirstRequest.complete(Unit); case.releaseSecondRequest.complete(Unit); report(case)
            }
        } finally {
            preferenceNames.forEach { context.deleteSharedPreferences(it) }
            originalUserPreferences.forEach { (name, before) ->
                assertEquals("The user's $name must remain unchanged", before, context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            }
        }
    }

    @Test fun theActualVisibleFastFrameStartsOneOwnAndTheStripDoesNotMoveOnSuccessOrPartialFailure() {
        val case = Case("frame-and-strip").also { it.partialFailure = churchPage }
        mount(case)
        enterBeforeDelivery(case)
        val fast = case.state()
        startAfterActualDraw(case)
        val strip = compose.onNodeWithTag("schedule_source_search_strip").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("source_search_review").getUnclippedBoundsInRoot()
        assertStripInsideWindow()
        if (!churchPage) compose.onNodeWithTag("today_review_schedule_sources").assertDoesNotExist()
        assertWholeWords(case, "source_search_review", "Проверить сайты")
        compose.onNodeWithTag("source_search_review").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("source_search_status").assertTextEquals("Ищем другие источники")
        capture(case, "searching")
        // Complete the background attempt while a real pointer is down.
        compose.onNodeWithTag("source_search_review").performTouchInput { down(center) }
        compose.runOnUiThread { case.releaseOwn.complete(Unit) }
        compose.waitUntil(15_000) { case.automaticCompleted.get() == 1 }; settle()
        assertEquals(strip, compose.onNodeWithTag("schedule_source_search_strip").getUnclippedBoundsInRoot())
        assertEquals(action, compose.onNodeWithTag("source_search_review").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("source_search_status").assertTextEquals(
            if (case.partialFailure) "Не все источники доступны" else "Поиск завершен")
        assertProtected(fast, case.state())
        assertNull(case.state().publication); assertNull(case.state().siteBinding)
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get())
        if (case.partialFailure) {
            assertTrue(case.state().candidateSearchUnavailable)
            assertTrue(case.state().sourceCandidates.map { it.id }.containsAll(fast.sourceCandidates.map { it.id }))
        } else case.own()
        capture(case, if (case.partialFailure) "partial-finished" else "finished")
        compose.onNodeWithTag("source_search_review").performTouchInput { up() }; settle()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed()
        assertEquals(1, case.refreshCalls.get()); assertEquals(1, case.automaticCalls.get())
        compose.onNodeWithTag("schedule_candidates_unavailable").assertDoesNotExist()
        assertTrue(case.confirmations.isEmpty()); assertTrue(case.books.requests.isEmpty())
    }

    @Test fun viewingSourcesKeepsTheAttemptAndTimestampOnlyCompletionKeepsTheMapPointerTarget() {
        val case = Case("view-and-map-pointer")
        mount(case); enterBeforeDelivery(case); startAfterActualDraw(case)
        val presentation = requireNotNull(case.presentation.get())
        compose.onNodeWithTag("source_search_review").performTouchInput { click(center) }; settle()
        assertSame(presentation, case.presentation.get())
        assertFalse(presentation.revoked)
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get()); assertEquals(1, case.refreshCalls.get())
        val firstGroup = scheduleCandidateGroups(case.parish, case.state()).first()
        val map = requireNotNull(firstGroup.confirmationCandidate)
        val target = reveal("church_content", "confirm_candidate_${map.id}")
        val node = target.fetchSemanticsNode().id
        val bounds = target.getUnclippedBoundsInRoot()
        target.performTouchInput { down(center) }
        compose.runOnUiThread { case.releaseOwn.complete(Unit) }
        compose.waitUntil(15_000) { case.automaticCompleted.get() == 1 }; settle()
        val fresh = case.state().sourceCandidates.single { it.id == map.id }
        assertNotEquals(map.checkedAt, fresh.checkedAt)
        assertTrue(Instant.parse(fresh.checkedAt).isAfter(Instant.parse(map.checkedAt)))
        assertEquals(map.copy(checkedAt = fresh.checkedAt), fresh)
        val retained = compose.onNodeWithTag("confirm_candidate_${map.id}")
        assertEquals(node, retained.fetchSemanticsNode().id)
        assertEquals(bounds, retained.getUnclippedBoundsInRoot())
        val projected = scheduleCandidateGroups(case.parish, case.state())
        assertEquals(OwnContactCandidateProvider.PROVIDER, projected.first().representative.provider)
        // Raw metadata is Own-first; the generation's UI still retains the old Map position.
        val own = case.own()
        assertTrue(compose.onNodeWithTag("schedule_source_group_${own.id}").getUnclippedBoundsInRoot().top > bounds.bottom)
        assertTrue(case.confirmations.isEmpty()); assertNull(case.state().siteBinding)
        capture(case, "timestamp-additive-own")
        retained.performTouchInput { up() }
        compose.waitUntil(15_000) { map.id in case.confirmed }; settle()
        assertEquals(listOf(map.id), case.confirmations.toList())
        assertTrue(presentation.revoked)
        assertEquals(1, case.automaticCalls.get()); assertEquals(0, case.scheduleStops.get())
        assertNull(case.state().publication)
        // The gesture selected a Map row, never the automatic Own witness.
        assertEquals(map.id, case.state().siteBinding?.candidateConfirmation?.candidate?.id)
        assertTrue(case.books.requests.isEmpty())
    }

    @Test fun eitherEarlyLegacyChoiceCancelsOwnAndTheExistingManualRetryStillWorksAfterRestore() {
        var restoration: StateRestorationTester? = null
        for ((index, legacy) in legacyRows.withIndex()) {
            val case = Case("early-map-$index")
            if (restoration == null) restoration = mount(case) else {
                compose.runOnIdle { active.value = null }; settle()
                compose.runOnIdle { active.value = case }; settle()
            }
            enterBeforeDelivery(case); startAfterActualDraw(case)
            compose.onNodeWithTag("source_search_review").performClick(); settle()
            val group = scheduleCandidateGroups(case.parish, case.state()).single { it.sourceUrl == legacy["requestedUrl"].asString }
            val map = requireNotNull(group.confirmationCandidate)
            reveal("church_content", "confirm_candidate_${map.id}").performTouchInput { click(center) }
            compose.waitUntil(15_000) { map.id in case.confirmed }; settle()
            assertEquals(1, case.automaticCancelled.get())
            assertEquals(0, case.automaticCompleted.get())
            assertNull(case.state().publication)
            assertFalse(case.state().sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER })
            assertTrue(case.requests.contains(legacy["requestedUrl"].asString))
            openOtherSourceSearch()
            reveal("church_content", "find_other_schedule_sources").performClick()
            compose.waitUntil(15_000) { case.searchCompleted.get() == 1 }; settle()
            case.own()
            val held = requireNotNull(reveal("church_content", "find_other_schedule_sources").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            requireNotNull(restoration).emulateSavedInstanceStateRestore(); settle()
            compose.runOnIdle { held() }; settle()
            assertEquals(1, case.searchCalls.get()); assertEquals(1, case.automaticCalls.get())
            assertEquals(2, case.directPools.get()); assertNull(case.state().publication)
        }
    }

    @Test fun settingsDayMidnightAndRestorationRevokeWithoutAnAutomaticReplay() {
        val case = Case("revocation")
        val restoration = mount(case); enterBeforeDelivery(case); startAfterActualDraw(case)
        val action = when {
            !dark && !churchPage -> "settings"
            !dark -> "chip"
            !churchPage -> "midnight"
            else -> "restore"
        }
        val raw = case.raw()
        when (action) {
            "settings" -> compose.onNodeWithTag("open_settings").performClick()
            "chip" -> reveal(entryContent, if (churchPage) "schedule_tomorrow" else "today_day_tomorrow").performClick()
            "midnight" -> compose.runOnIdle { case.rollover() }
            else -> restoration.emulateSavedInstanceStateRestore()
        }
        compose.waitUntil(15_000) { case.automaticCancelled.get() == 1 }; settle()
        if (action == "midnight" || action == "restore") compose.waitUntil(15_000) { case.refreshCompleted.get() >= 2 }
        compose.waitUntil(15_000) { case.automaticFinished.get() == 1 }
        assertFalse(case.releaseOwn.isCompleted)
        val stateBeforeRelease = case.state()
        if (action == "midnight") {
            // A new day legitimately performs a fresh normal lookup before the
            // canceled old-day contact is released. It must not be an auto replay.
            val freshOwn = case.own()
            assertEquals("2026-11-15", Instant.parse(freshOwn.checkedAt).atZone(ZoneOffset.UTC).toLocalDate().toString())
            assertEquals(2, case.refreshCalls.get()); assertEquals(2, case.directPools.get())
        } else {
            assertFalse(stateBeforeRelease.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER })
            assertEquals(1, case.directPools.get())
        }
        assertNull(stateBeforeRelease.publication); assertNull(stateBeforeRelease.siteBinding)
        assertEquals(1, case.automaticCalls.get()); assertEquals(0, case.automaticCompleted.get())
        assertEquals(1, case.automaticCancelled.get()); assertEquals(1, case.frameProofs.size)
        fun effects() = listOf(case.automaticCalls.get(), case.automaticCompleted.get(), case.automaticCancelled.get(),
            case.automaticFinished.get(), case.directPools.get(), case.websitePools.get(), case.refreshCalls.get(),
            case.refreshCompleted.get(), case.cacheWrites.get(), case.safetyWrites.get(), case.requests.size,
            case.frameProofs.size, case.searchCalls.get(), case.searchCompleted.get(), case.scheduleStops.get())
        val effectsBeforeRelease = effects()
        val afterAction = case.raw()
        compose.runOnIdle { case.releaseOwn.complete(Unit) }; settle()
        assertEquals(afterAction, case.raw())
        assertEquals(effectsBeforeRelease, effects())
        if (action != "midnight") assertEquals(raw, case.raw())
        assertNull(case.state().publication); assertNull(case.state().siteBinding)
        if (action == "settings") { compose.onNodeWithTag("nav_home").performClick(); settle() }
        assertEquals(1, case.automaticCalls.get())
        assertEquals(if (action == "midnight") 2 else 1, case.directPools.get())
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        assertTrue(case.confirmations.isEmpty())
    }

    @Test fun openingABookOrGenericBackLeavesTheViewingFamilyAndCannotCommitLateSuggestions() {
        val case = Case("book-or-back")
        mount(case); enterBeforeDelivery(case); startAfterActualDraw(case)
        val raw = case.raw()
        if (churchPage) androidx.test.espresso.Espresso.pressBack()
        else reveal("main_content", "start_guide").performClick()
        compose.waitUntil(15_000) { case.automaticCancelled.get() == 1 }; settle()
        compose.runOnIdle { case.releaseOwn.complete(Unit) }; settle()
        assertEquals(raw, case.raw())
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get())
        assertFalse(case.state().sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER })
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
    }


    @Test fun independentSavedDaysAndAScrolledReadingAnchorSurviveBothViewingDirections() {
        val case = Case("saved-days-and-anchor").also { it.prepareUiWithoutRefresh = true }
        mount(case)
        // Prepare independent saved UI dates without running the checked refresh.
        compose.waitUntil(15_000) { case.refreshCompleted.get() == 1 }
        assertNull(case.raw()); assertFalse(case.fastCommitted.isCompleted)
        compose.onNodeWithTag("nav_church").performClick(); settle()
        reveal("church_content", "schedule_tomorrow").performClick().assertIsSelected()
        compose.onNodeWithTag("nav_home").performClick(); settle()
        // Today remains Today; Church has its own retained Tomorrow.
        reveal("main_content", "today_day_today").assertIsSelected()
        if (churchPage) compose.onNodeWithTag("nav_church").performClick()
        val readingTag = if (churchPage) "church_reading_card" else "today_reading_card"
        compose.runOnIdle { case.prepareUiWithoutRefresh = false }
        // A new explicit cold request has a new pre-await epoch. The earlier chip
        // invalidated the preparation epoch and is not part of this launch.
        reveal(entryContent, refreshTag).performClick()
        compose.waitUntil(15_000) { case.fastCommitted.isCompleted }
        assertEquals(0, case.searchCalls.get()); assertEquals(0, case.directPools.get())
        assertEquals(7, case.requests.size)
        reveal(entryContent, readingTag)
        var fastBounds: DpRect? = null
        var fastScroll = 0f
        startAfterActualDraw(case) {
            fastBounds = compose.onNodeWithTag(readingTag).getUnclippedBoundsInRoot()
            fastScroll = compose.onNodeWithTag(entryContent).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        }
        assertEquals(fastBounds, compose.onNodeWithTag(readingTag).getUnclippedBoundsInRoot())
        assertEquals(fastScroll, compose.onNodeWithTag(entryContent).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        val presentation = requireNotNull(case.presentation.get())
        assertEquals(1, presentation.epoch.sequence)
        compose.onNodeWithTag("nav_home").performClick(); settle()
        reveal("main_content", "today_day_today").assertIsSelected()
        compose.onNodeWithTag("source_search_review").performClick(); settle()
        assertSame(presentation, case.presentation.get()); assertFalse(presentation.revoked)
        reveal("church_content", "schedule_tomorrow").assertIsSelected()
        reveal("church_content", "church_reading_card")
        val before = compose.onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot()
        val node = compose.onNodeWithTag("church_reading_card").fetchSemanticsNode().id
        val scroll = compose.onNodeWithTag("church_content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { case.releaseOwn.complete(Unit) }
        compose.waitUntil(15_000) { case.automaticCompleted.get() == 1 }; settle()
        assertEquals(node, compose.onNodeWithTag("church_reading_card").fetchSemanticsNode().id)
        assertEquals(before, compose.onNodeWithTag("church_reading_card").getUnclippedBoundsInRoot())
        assertEquals(scroll, compose.onNodeWithTag("church_content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        case.own()
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get()); assertEquals(2, case.refreshCalls.get())
        compose.onNodeWithTag("nav_home").performClick(); settle()
        reveal("main_content", "today_day_today").assertIsSelected()
        compose.onNodeWithTag("nav_church").performClick(); settle()
        reveal("church_content", "schedule_tomorrow").assertIsSelected()
        assertEquals(1, case.automaticCalls.get()); assertSame(presentation, case.presentation.get())
        assertNull(case.state().publication); assertNull(case.state().siteBinding)
    }


    @Test fun viewingBetweenDrawAndNextFrameRequiresTheNewSurfaceWitnessWithoutAnotherAttempt() {
        val case = Case("handoff-before-frame")
        mount(case); enterBeforeDelivery(case)
        var moved = false
        startAfterActualDraw(case) {
            val presentation = requireNotNull(case.presentation.get())
            val departed = presentation.view
            val old = departed.drawn.getCompleted()
            fun trace(stage: String) {
                case.frameHandoff += mapOf("stage" to stage, "clockTimeMs" to compose.mainClock.currentTime,
                    "automaticCalls" to case.automaticCalls.get(), "directPools" to case.directPools.get(),
                    "frameProofs" to case.frameProofs.toList(), "viewIdentity" to System.identityHashCode(presentation.view),
                    "screen" to presentation.view.screen, "destinationDrawn" to presentation.view.drawn.isCompleted)
            }
            val beforeFetch = compose.mainClock.currentTime
            trace("before-fetch")
            val tag = if (churchPage) "nav_home" else "source_search_review"
            val action = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            trace("after-fetch")
            assertEquals(beforeFetch, compose.mainClock.currentTime)
            assertEquals(0, case.automaticCalls.get()); assertEquals(0, case.directPools.get())
            assertTrue(case.frameProofs.isEmpty())
            compose.runOnUiThread {
                val beforeAction = compose.mainClock.currentTime
                trace("before-action")
                assertTrue(action())
                trace("after-action")
                assertEquals(beforeAction, compose.mainClock.currentTime)
                assertTrue(departed.left.isCompleted)
                assertNotSame(departed, presentation.view)
                departed.acknowledge(old.strip, old.action)
                assertFalse(presentation.view.drawn.isCompleted)
                assertEquals(0, case.automaticCalls.get()); assertEquals(0, case.directPools.get())
                assertTrue(case.frameProofs.isEmpty())
                moved = true
            }
        }
        assertTrue(moved)
        val presentation = requireNotNull(case.presentation.get())
        case.frameHandoff += mapOf("stage" to "after-destination-frame", "clockTimeMs" to compose.mainClock.currentTime,
            "automaticCalls" to case.automaticCalls.get(), "directPools" to case.directPools.get(),
            "frameProofs" to case.frameProofs.toList(), "viewIdentity" to System.identityHashCode(presentation.view),
            "screen" to presentation.view.screen, "destinationDrawn" to presentation.view.drawn.isCompleted)
        assertEquals(if (churchPage) "MAIN" else "CHURCH", presentation.view.screen)
        assertSame(presentation.view.drawn.getCompleted(), presentation.witness)
        assertEquals(1, case.refreshCalls.get()); assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get())
        compose.runOnIdle { case.releaseOwn.complete(Unit) }
        compose.waitUntil(15_000) { case.automaticCompleted.get() == 1 }; settle()
        case.own(); assertEquals(1, case.automaticCalls.get())
    }


    @Test fun aRevokedPendingDeliveryCannotRedeemAfterSettingsInEitherDeliveryOrder() {
        var mounted: StateRestorationTester? = null
        for (returnBeforeDelivery in listOf(false, true)) {
            val case = Case("pending-settings-$returnBeforeDelivery")
            if (mounted == null) mounted = mount(case) else {
                compose.runOnIdle { active.value = null }; settle()
                compose.runOnIdle { active.value = case }; settle()
            }
            enterBeforeDelivery(case)
            compose.onNodeWithTag("open_settings").performClick(); settle()
            if (returnBeforeDelivery) { compose.onNodeWithTag("nav_home").performClick(); settle() }
            compose.runOnIdle { case.releaseFast.complete(Unit) }
            compose.waitUntil(15_000) { case.refreshCompleted.get() > 0 }; settle()
            assertEquals(0, case.automaticCalls.get()); assertEquals(0, case.directPools.get())
            if (!returnBeforeDelivery) { compose.onNodeWithTag("nav_home").performClick(); settle() }
            assertRevokedDeliveryIsOrdinaryMap(case)
        }
    }

    @Test fun stopResumeAndChipBeforeDeliveryCannotRenewThePendingEpoch() {
        var mounted: StateRestorationTester? = null
        for (action in listOf("stop-resume", "chip")) {
            val case = Case("pending-$action")
            if (mounted == null) mounted = mount(case) else {
                compose.runOnIdle { active.value = null }; settle()
                compose.runOnIdle { active.value = case }; settle()
            }
            enterBeforeDelivery(case)
            if (action == "stop-resume") {
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            } else reveal(entryContent, if (churchPage) "schedule_tomorrow" else "today_day_tomorrow").performClick()
            compose.runOnIdle { case.releaseFast.complete(Unit) }
            compose.waitUntil(15_000) { case.refreshCompleted.get() > 0 }; settle()
            assertRevokedDeliveryIsOrdinaryMap(case)
            assertEquals(7, case.requests.size) // Resume itself did not perform a new HTTP discovery.
            if (action == "chip") reveal(entryContent, if (churchPage) "schedule_tomorrow" else "today_day_tomorrow").assertIsSelected()
        }
    }

    @Test fun coldRecreationCancelsTheOldPreCommitAttemptAndNeedsNewHttpAndItsOwnFrame() {
        val case = Case("cold-before-commit").also { it.holdBeforeCommit = true }
        val restoration = mount(case)
        compose.waitUntil(15_000) { case.firstRequestEntered.isCompleted }
        if (churchPage) compose.onNodeWithTag("nav_church").performClick()
        assertNull(case.raw()); assertEquals(0, case.cacheWrites.get()); assertFalse(case.fastCommitted.isCompleted)
        assertEquals(0, case.directPools.get()); assertEquals(0, case.automaticCalls.get())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15_000) { case.refreshCancelled.get() == 1 && case.secondRequestEntered.isCompleted }
        assertNull(case.raw()); assertEquals(0, case.cacheWrites.get()); assertFalse(case.fastCommitted.isCompleted)
        assertEquals(2, case.refreshCalls.get()); assertEquals(2, case.preCommitRequests.get())
        // The old request is canceled. Completing its old gate is not a delivery
        // or receipt; the second independent request is still held before commit.
        val oldHttpCount = requireNotNull(case.httpByRefresh["refresh-1"]).get()
        assertTrue(oldHttpCount >= 2)
        compose.runOnIdle { case.releaseFirstRequest.complete(Unit) }; settle()
        assertEquals(oldHttpCount, case.httpByRefresh["refresh-1"]?.get())
        assertNull(case.raw()); assertFalse(case.fastCommitted.isCompleted); assertEquals(0, case.directPools.get())
        compose.runOnIdle { case.releaseSecondRequest.complete(Unit) }
        compose.waitUntil(15_000) { case.fastCommitted.isCompleted }
        assertEquals(1, case.cacheWrites.get())
        assertEquals(7, case.httpByRefresh["refresh-2"]?.get())
        assertEquals(oldHttpCount + 7, case.requests.size)
        assertEquals(0, case.directPools.get()); assertEquals(0, case.automaticCalls.get())
        startAfterActualDraw(case)
        val fresh = requireNotNull(case.presentation.get())
        assertTrue(fresh.epoch.active); assertFalse(fresh.revoked)
        assertEquals(1, case.refreshCancelled.get()); assertEquals(1, case.automaticCalls.get())
        compose.runOnIdle { case.releaseOwn.complete(Unit) }
        compose.waitUntil(15_000) { case.automaticCompleted.get() == 1 }; settle()
        case.own()
        assertEquals(1, case.directPools.get()); assertEquals(2, case.cacheWrites.get())
        assertEquals(1, case.frameProofs.size)
    }

    @Test fun heldChurchUrlCannotCrossAViewingRoundTripBeforeDisposalAndCurrentUrlStillWorks() {
        val case = Case("held-url-same-turn")
        mount(case); enterBeforeDelivery(case); startAfterActualDraw(case)
        compose.onNodeWithTag("source_search_review").performClick(); settle()
        val presentation = requireNotNull(case.presentation.get())
        val group = scheduleCandidateGroups(case.parish, case.state()).first()
        val row = group.representative
        val oldUrl = requireNotNull(reveal("church_content", "open_candidate_${row.id}")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val home = requireNotNull(compose.onNodeWithTag("nav_home").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val church = requireNotNull(compose.onNodeWithTag("nav_church").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            // No idle, frame, recomposition or disposal between these calls.
            home(); oldUrl()
            assertTrue(case.externalStarts.isEmpty()); assertFalse(presentation.revoked)
            church(); oldUrl()
            assertTrue(case.externalStarts.isEmpty()); assertFalse(presentation.revoked)
        }
        settle()
        assertSame(presentation, case.presentation.get())
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get())
        reveal("church_content", "open_candidate_${row.id}").performClick()
        compose.waitUntil(15_000) { case.automaticCancelled.get() == 1 }; settle()
        assertEquals(listOf(group.sourceUrl), case.externalStarts.toList())
        assertTrue(presentation.revoked)
        val before = case.raw()
        compose.runOnIdle { case.releaseOwn.complete(Unit) }; settle()
        assertEquals(before, case.raw()); assertNull(case.state().publication); assertNull(case.state().siteBinding)
    }

    private fun assertRevokedDeliveryIsOrdinaryMap(case: Case) {
        assertEquals(0, case.automaticCalls.get()); assertEquals(0, case.directPools.get())
        assertEquals(3, case.state().sourceCandidates.size)
        assertTrue(case.state().sourceCandidates.all { it.canConfirm(case.parish) && it.ownContact == null })
        assertNull(case.state().publication); assertNull(case.state().siteBinding)
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        case.presentation.get()?.let { assertFalse(it.epoch.active); assertTrue(it.revoked) }
        assertEquals(1, case.cacheWrites.get())
    }

    private fun enterBeforeDelivery(case: Case) {
        compose.waitUntil(15_000) { case.fastCommitted.isCompleted }
        if (churchPage) compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag(entryContent).assertIsDisplayed()
        assertEquals(0, case.directPools.get()); assertEquals(0, case.automaticCalls.get())
        assertEquals(7, case.requests.size)
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
    }

    private fun startAfterActualDraw(case: Case, onFastFrame: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { case.releaseFast.complete(Unit) }
            compose.waitUntil(15_000) { case.presentation.get() != null }
            val presentation = requireNotNull(case.presentation.get())
            assertFalse(presentation.view.drawn.isCompleted)
            assertEquals(0, case.automaticCalls.get()); assertEquals(0, case.directPools.get())
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.waitUntil(10_000) { presentation.view.drawn.isCompleted }
            assertEquals("The actual draw alone does not yet permit a dispatch", 0, case.automaticCalls.get())
            assertEquals(0, case.directPools.get())
            onFastFrame()
            compose.mainClock.advanceTimeByFrame()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(15_000) { case.ownEntered.isCompleted }; settle()
        assertEquals(1, case.automaticCalls.get()); assertEquals(1, case.directPools.get())
        assertEquals(1, case.frameProofs.size)
        assertEquals(true, case.frameProofs.single()["drawAcknowledged"])
        assertNotNull(case.frameProofs.single()["frameAfterDraw"])
        assertNotNull(case.presentation.get()?.witness)
        val view = requireNotNull(case.presentation.get())
        assertSame(view.view.drawn.getCompleted(), view.witness)
        assertFalse(case.state().loading)
        assertNull(case.state().publication); assertNull(case.state().siteBinding)
    }

    private fun assertStripInsideWindow() {
        val window = compose.onNodeWithTag("auto_test_window").getBoundsInRoot()
        val strip = compose.onNodeWithTag("schedule_source_search_strip").getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag("source_search_review").getUnclippedBoundsInRoot()
        assertTrue(strip.top >= window.top && strip.bottom <= window.bottom)
        assertTrue(button.top >= strip.top && button.bottom <= strip.bottom)
        assertEquals(strip, compose.onNodeWithTag("schedule_source_search_strip").getBoundsInRoot())
        assertEquals(button, compose.onNodeWithTag("source_search_review").getBoundsInRoot())
    }

    private val entryContent get() = if (churchPage) "church_content" else "main_content"
    private val refreshTag get() = if (churchPage) "refresh_schedule" else "today_refresh_schedule"
    private fun mount(case: Case): StateRestorationTester {
        active.value = case
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                active.value?.let { current -> key(current.id) {
                    val native = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides current.isolatedContext,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalDensity provides Density(native.density, 2f)) {
                        Box(Modifier.size(320.dp, 640.dp).testTag("auto_test_window")) { AppNavigation(current.container, current.renderClock.value) }
                    }
                } }
            }
            contentSet = true
        }
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
        compose.onNodeWithTag(containerTag).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun assertWholeWords(case: Case, buttonTag: String, expected: String) {
        try {
            val node = compose.onNode(hasText(expected) and hasAnyAncestor(hasTestTag(buttonTag)), useUnmergedTree = true).assertIsDisplayed()
            val fullBounds = node.getUnclippedBoundsInRoot()
            val visibleBounds = node.getBoundsInRoot()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            val linesFit = (0 until result.lineCount).all { line ->
                val left = result.getLineLeft(line); val right = result.getLineRight(line)
                val top = result.getLineTop(line); val bottom = result.getLineBottom(line)
                listOf(left, right, top, bottom).all { it.isFinite() } &&
                    left <= right && top <= bottom && !result.isLineEllipsized(line) &&
                    left >= 0f && right <= result.size.width && top >= 0f && bottom <= result.size.height
            }
            val textMatches = result.layoutInput.text.text == expected
            val completeText = textMatches && result.lineCount > 0 &&
                result.getLineEnd(result.lineCount - 1) == expected.length &&
                result.getLineEnd(result.lineCount - 1, visibleEnd = true) == expected.length
            val wordsFit = textMatches && result.lineCount > 0 && Regex("\\S+").findAll(expected).all { word ->
                result.getLineForOffset(word.range.first) == result.getLineForOffset(word.range.last)
            }
            val fits = fullBounds == visibleBounds && !result.didOverflowHeight && linesFit && completeText && wordsFit
            val out = output(case)
            File(out, "layout.ndjson").appendText(Gson().toJson(mapOf(
                "tag" to buttonTag, "expected" to expected, "text" to result.layoutInput.text.text,
                "size" to listOf(result.size.width, result.size.height),
                "constraints" to result.layoutInput.constraints.toString(),
                "paragraphSize" to listOf(result.multiParagraph.width, result.multiParagraph.height),
                "fullBounds" to fullBounds.toString(), "visibleBounds" to visibleBounds.toString(),
                "linesFit" to linesFit, "completeText" to completeText, "wordsFit" to wordsFit, "fits" to fits,
                "fontScale" to context.resources.configuration.fontScale,
                "screenWidthDp" to context.resources.configuration.screenWidthDp,
                "maxLines" to result.layoutInput.maxLines, "lines" to result.lineCount,
                "overflowWidth" to result.didOverflowWidth, "overflowHeight" to result.didOverflowHeight,
                "lineMetrics" to (0 until result.lineCount).map { line -> listOf(
                    result.getLineStart(line), result.getLineEnd(line), result.getLineLeft(line),
                    result.getLineRight(line), result.getLineTop(line), result.getLineBottom(line),
                    result.isLineEllipsized(line), result.getLineEnd(line, visibleEnd = true)) }
            )) + "\n")
            assertEquals("The complete heading must remain inside the ancestor clip", fullBounds, visibleBounds)
            assertEquals(expected, result.layoutInput.text.text)
            assertFalse(result.didOverflowHeight)
            assertTrue("Every complete line must fit the measured text box without ellipsis", linesFit)
            assertTrue("The full expected text must be present in visible line ends", completeText)
            assertTrue("Words must not split across lines", wordsFit)
        } catch (failure: Throwable) {
            if (failure is AssertionError || failure is Exception) {
                try { capture(case, "failed-${buttonTag.take(90)}") }
                catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
            }
            throw failure
        }
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
    private fun output(case: Case) = File(context.getExternalFilesDir("automatic-source-search"), case.id).apply { mkdirs() }
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
        "refreshCancelled" to case.refreshCancelled.get(), "preCommitRequests" to case.preCommitRequests.get(),
        "cacheWrites" to case.cacheWrites.get(), "httpByRefresh" to case.httpByRefresh.mapValues { it.value.get() },
        "externalStarts" to case.externalStarts.toList(),
        "automaticCalls" to case.automaticCalls.get(), "automaticCompleted" to case.automaticCompleted.get(),
        "automaticCancelled" to case.automaticCancelled.get(), "automaticFinished" to case.automaticFinished.get(),
        "frameProofs" to case.frameProofs.toList(), "frameHandoff" to case.frameHandoff.toList(),
        "safetyWrites" to case.safetyWrites.get(), "finalCacheSha256" to case.cache.read(case.parish.id)?.let { ScheduleSourceCandidate.sha(it) })))
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0},church={1}")
        fun configurations() = listOf(false, true).flatMap { dark -> listOf(false, true).map { arrayOf<Any>(dark, it) } }
    }
}
