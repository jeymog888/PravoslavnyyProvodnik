package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import android.graphics.Rect
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.*

@RunWith(AndroidJUnit4::class)
class ChurchDiscoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val kazan = DiscoveryCity("osm:relation:1", "Казань", "Татарстан", "Россия", GeoPoint(55.796, 49.106))
    private val namesake = DiscoveryCity("osm:node:2", "Казань", "Другая область", "Россия", GeoPoint(54.5, 45.0))
    private val church = DiscoveredChurch("osm:way:42", "Никольский храм", GeoPoint(55.797, 49.107),
        "улица Баумана, 5", "", "https://example.com/", false, "https://www.openstreetmap.org/way/42")
    private val fetchedAt = 1_794_096_000_000L

    @Test fun nameSearchSelectsAMonasteryInOneTapAndDerivesItsTimeZoneFromCoordinates() {
        val monastery = church.copy(id = "osm:relation:3325777", name = "Сретенский монастырь", city = "Москва",
            center = GeoPoint(55.766, 37.628), address = "улица Большая Лубянка", kind = DiscoveredPlaceKind.MONASTERY,
            sourceUrl = "https://www.openstreetmap.org/relation/3325777")
        val fake = FakeDiscovery().apply { nameResult = { success(listOf(monastery), DiscoveryProvider.PHOTON) } }
        var saved: SavedChurchRecord? = null
        launch(fake, onSave = { saved = it; true }, initialMode = ChurchSearchMode.NAME)
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().performTextInput("Сретенский монастырь Москва")
        compose.runOnIdle { assertTrue(fake.nameQueries.isEmpty()); assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty()) }
        waitForVisibleKeyboard()
        compose.onNodeWithTag("discovery_query").performImeAction()
        waitForHiddenKeyboard()
        scrollTo("discovery_church_${monastery.id}").assertIsDisplayed()
        compose.onNodeWithTag("discovery_area").assertDoesNotExist()
        scrollTo("discovery_name_notice").assertTextContains("название и адрес", substring = true)
        scrollTo("discovery_church_${monastery.id}").performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("Сретенский монастырь Москва"), fake.nameQueries)
            assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty())
            assertEquals(monastery.id, saved?.id)
            assertEquals("Europe/Moscow", saved?.timezone)
            assertEquals(monastery.sourceUrl, saved?.sourceUrl)
            assertTrue(requireNotNull(saved).asParish().schedule.isEmpty())
        }
    }

    @Test fun nearbyNameFallbackExplainsItsLimitsWithoutMislabelingPrimaryOrOrdinaryNameSearch() {
        val fake = FakeDiscovery()
        launch(fake)
        searchAndChooseKazan()
        scrollTo("discovery_church_${church.id}").assertIsDisplayed()
        compose.onNodeWithTag("discovery_nearby_notice").assertDoesNotExist()
        compose.runOnIdle {
            fake.churchResult = { DiscoveryResult.Success(listOf(church), fetchedAt, DiscoveryCacheStatus.FRESH_NETWORK,
                DiscoveryProvider.PHOTON, kazan.center.searchAreaCenter(), 3_000) }
        }
        searchAndChooseKazan()
        scrollTo("discovery_nearby_notice").assertTextContains("список может быть неполным", substring = true)
            .assertTextContains("православный храм", substring = true)
        compose.onNodeWithTag("discovery_name_notice").assertDoesNotExist()
        scrollTo("discovery_mode_name").performClick()
        search("Никольский храм Казань")
        scrollTo("discovery_name_notice").assertIsDisplayed()
        compose.onNodeWithTag("discovery_nearby_notice").assertDoesNotExist()
    }

    @Test fun serverFailureRetainsTheNameAndOnlyRetriesAfterAnExplicitAction() {
        var attempt = 0
        val fake = FakeDiscovery().apply { nameResult = {
            if (attempt++ == 0) DiscoveryResult.Failure(DiscoveryFailure.SERVER_UNAVAILABLE)
            else success(listOf(church), DiscoveryProvider.PHOTON)
        } }
        launch(fake, initialMode = ChurchSearchMode.NAME)
        search("Никольский храм Казань")
        scrollTo("discovery_message").assertTextContains("Сервис поиска временно недоступен", substring = true)
        compose.onNodeWithTag("discovery_empty").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fake.nameQueries.size) }
        scrollTo("discovery_retry").performClick()
        scrollTo("discovery_church_${church.id}").assertIsDisplayed()
        scrollTo("discovery_query").assertIsDisplayed()
        compose.onNodeWithTag("discovery_query").assertTextContains("Никольский храм Казань")
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf("Никольский храм Казань", "Никольский храм Казань"), fake.nameQueries)
            assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty())
        }
    }

    @Test fun cancelledNameSearchCannotReplaceANewerResult() {
        val old = CompletableDeferred<DiscoveryResult<DiscoveredChurch>>()
        val first = church.copy(id = "osm:way:1", name = "Первый храм")
        val fake = FakeDiscovery().apply { nameResult = { query ->
            if (query == "Первый") withContext(NonCancellable) { old.await() }
            else success(listOf(church), DiscoveryProvider.PHOTON)
        } }
        try {
            launch(fake, initialMode = ChurchSearchMode.NAME)
            search("Первый")
            scrollTo("discovery_cancel").performClick()
            search("Никольский храм")
            scrollTo("discovery_church_${church.id}").assertIsDisplayed()
            compose.runOnIdle { old.complete(success(listOf(first), DiscoveryProvider.PHOTON)) }
            compose.waitForIdle()
            compose.onNodeWithTag("discovery_church_${first.id}").assertDoesNotExist()
            scrollTo("discovery_church_${church.id}").assertIsDisplayed()
        } finally { old.cancel() }
    }

    @Test fun unavailableNearbySearchOffersNameSearchWithoutStartingAHiddenRequest() {
        val fake = FakeDiscovery().apply { churchResult = { DiscoveryResult.Failure(DiscoveryFailure.SERVER_UNAVAILABLE) } }
        launch(fake)
        searchAndChooseKazan()
        scrollTo("discovery_message").assertTextContains("Сервис поиска временно недоступен", substring = true)
        scrollTo("discovery_mode_name").performClick()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains("Казань")
        compose.runOnIdle {
            assertTrue(fake.nameQueries.isEmpty())
            assertEquals(listOf("Казань"), fake.queries)
            assertEquals(listOf(kazan.center), fake.centers)
        }
        search("Никольский храм Казань")
        scrollTo("discovery_church_${church.id}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("Никольский храм Казань"), fake.nameQueries)
            assertEquals(1, fake.queries.size); assertEquals(1, fake.centers.size)
        }
    }

    @Test fun citySearchKeepsNamesakesDistinctAndFailedSelectionCanBeRetriedWithoutAForm() {
        val fake = FakeDiscovery()
        fake.cityResult = { success(listOf(kazan, namesake), DiscoveryProvider.PHOTON) }
        val saved = mutableListOf<SavedChurchRecord>()
        var allowSave = false
        launch(fake, onSave = { saved += it; allowSave })
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty()) }
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().performTextInput("Казань")
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()) }
        waitForVisibleKeyboard()
        compose.onNodeWithTag("discovery_query").performImeAction()
        waitForHiddenKeyboard()
        returnToSearchWithoutRequest(fake)
        compose.onNodeWithTag("discovery_query").assertTextContains("Казань")
        scrollTo("discovery_city_osm:node:2").performClick()
        compose.runOnIdle { assertEquals(listOf(namesake.center), fake.centers); assertTrue(saved.isEmpty()) }
        scrollTo("discovery_church_osm:way:42").performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        scrollTo("discovery_message").assertTextContains("Не удалось открыть", substring = true)
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals("Europe/Moscow", saved.single().timezone)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, saved.single().origin)
            assertEquals(church.id, saved.single().id)
            assertEquals(church.sourceUrl, saved.single().sourceUrl)
            assertEquals(church.center.latitude, saved.single().latitude!!, 0.0)
            assertTrue(saved.single().asParish().schedule.isEmpty())
            allowSave = true
        }
        scrollTo("discovery_church_osm:way:42").performClick()
        compose.runOnIdle { assertEquals(2, saved.size) }
    }

    @Test
    fun cancellingARequestIgnoresItsLateResultAndLeavesManualNavigationAvailable() {
        val oldResult = CompletableDeferred<DiscoveryResult<DiscoveryCity>>()
        val fake = FakeDiscovery().apply {
            cityResult = { query -> if (query == "Первый") withContext(NonCancellable) { oldResult.await() }
                else success(listOf(kazan), DiscoveryProvider.PHOTON) }
        }
        var returned = false
        var phase = "launch"
        try {
            withCiFailureDiagnostics("discovery.cancelLateResult", additionalEvidence = { directory, deadline, evidencePhase ->
                when (evidencePhase) {
                    CiEvidencePhase.BEFORE_SCREENSHOT -> {
                        writeCiFailureEvidence(directory, "discovery-context.json", deadline) {
                            JSONObject().put("phase", phase).put("targetTag", "discovery_city_osm:relation:1")
                                .put("queries", JSONArray(fake.queries.toList()))
                                .put("centers", JSONArray(fake.centers.toList().map {
                                    JSONObject().put("latitude", it.latitude).put("longitude", it.longitude)
                                }))
                                .put("oldResultCompleted", oldResult.isCompleted).put("oldResultCancelled", oldResult.isCancelled)
                                .put("wallTimeMillis", System.currentTimeMillis()).put("uptimeMillis", SystemClock.uptimeMillis())
                                .toString(2)
                        }
                        writeCiFailureEvidence(directory, "discovery-platform.json", deadline) {
                            var sample: JSONObject? = null
                            compose.activityRule.scenario.onActivity { activity ->
                                val decor = activity.window.decorView
                                val insets = ViewCompat.getRootWindowInsets(decor)
                                val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
                                sample = JSONObject().put("wallTimeMillis", System.currentTimeMillis())
                                    .put("uptimeMillis", SystemClock.uptimeMillis()).put("windowFocus", decor.hasWindowFocus())
                                    .put("lifecycle", activity.lifecycle.currentState.name)
                                    .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
                                    .put("imeBottom", insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: JSONObject.NULL)
                                    .put("decorWidth", decor.width).put("decorHeight", decor.height)
                                    .put("visibleFrame", frame.toShortString())
                            }
                            requireNotNull(sample).toString(2)
                        }
                    }
                    CiEvidencePhase.AFTER_SCREENSHOT -> {
                        writeCiFailureEvidence(directory, "discovery-merged-tree.txt", deadline) {
                            "Post-failure tree; printToString may synchronize Compose before reading.\n" +
                                compose.onRoot().printToString()
                        }
                        writeCiFailureEvidence(directory, "discovery-unmerged-tree.txt", deadline) {
                            "Post-failure tree; printToString may synchronize Compose before reading.\n" +
                                compose.onRoot(useUnmergedTree = true).printToString()
                        }
                    }
                }
            }) {
                launch(fake, onBack = { returned = true })
                phase = "first-search"
                search("Первый")
                phase = "cancel-first-search"
                scrollTo("discovery_cancel").assertIsDisplayed().assertIsEnabled().performClick()
                compose.onNodeWithTag("discovery_message").assertTextEquals("Поиск остановлен. Можно попробовать снова или выбрать храм вручную")
                compose.onNodeWithTag("discovery_loading").assertDoesNotExist()
                phase = "second-search"
                search("Казань")
                phase = "first-kazan-check"
                scrollTo("discovery_city_osm:relation:1").assertIsDisplayed()
                phase = "complete-old-response"
                compose.runOnIdle { oldResult.complete(success(listOf(namesake), DiscoveryProvider.PHOTON)) }
                phase = "second-kazan-check"
                compose.waitForIdle()
                compose.onNodeWithTag("discovery_city_osm:node:2").assertDoesNotExist()
                scrollTo("discovery_city_osm:relation:1").assertIsDisplayed()
                phase = "manual-navigation"
                scrollTo("discovery_manual").performClick()
                compose.onNodeWithTag("add_church_form").assertExists()
                compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
                compose.onNodeWithTag("add_church_form").assertDoesNotExist()
                compose.runOnIdle { assertFalse(returned) }
            }
        } finally {
            oldResult.cancel()
        }
    }

    @Test fun longMapNamesAndAddressesReachStorageUnshortenedWithoutAnExtraForm() {
        val full = church.copy(name = "Храм " + "Н".repeat(210), city = "Г".repeat(150), address = "Улица " + "А".repeat(300))
        val fake = FakeDiscovery().apply { churchResult = { success(listOf(full), DiscoveryProvider.VK_MAPS) } }
        var saved: SavedChurchRecord? = null
        launch(fake, onSave = { saved = it; true })
        searchAndChooseKazan()
        scrollTo("discovery_church_${full.id}").performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(full.name, saved?.name)
            assertEquals(full.city, saved?.city)
            assertEquals(full.address, saved?.address)
        }
    }

    @Test
    fun timeoutEmptyResultsAndOldCachedResultsRemainDistinct() {
        val fake = FakeDiscovery()
        var attempt = 0
        fake.churchResult = {
            when (attempt++) {
                0 -> DiscoveryResult.Failure(DiscoveryFailure.TIMEOUT)
                1 -> success(emptyList(), DiscoveryProvider.VK_MAPS)
                else -> DiscoveryResult.Success(listOf(church), fetchedAt, DiscoveryCacheStatus.STALE_CACHE,
                    DiscoveryProvider.VK_MAPS, kazan.center.searchAreaCenter(), 3_000, DiscoveryFailure.UNAVAILABLE)
            }
        }
        launch(fake)
        searchAndChooseKazan()
        scrollTo("discovery_message").assertTextContains("не успел ответить", substring = true)
        compose.onNodeWithTag("discovery_empty").assertDoesNotExist()
        returnToSearchWithoutRequest(fake)
        scrollTo("discovery_message").assertTextContains("не успел ответить", substring = true)
        searchAndChooseKazan()
        scrollTo("discovery_empty").assertTextEquals("В этом районе храмы не найдены")
        compose.onNodeWithTag("discovery_message").assertDoesNotExist()
        returnToSearchWithoutRequest(fake)
        scrollTo("discovery_empty").assertTextEquals("В этом районе храмы не найдены")
        searchAndChooseKazan()
        scrollTo("discovery_cache_notice").assertTextContains("Не удалось обновить", substring = true)
        compose.onNodeWithTag("discovery_area").assertTextContains("3 км", substring = true)
        scrollTo("discovery_church_osm:way:42").assertIsDisplayed()
        scrollTo("discovery_attribution").assertIsDisplayed()
    }

    @Test
    fun leavingTheForegroundCancelsSearchAndReturningDoesNotRestartIt() {
        val fake = FakeDiscovery()
        var cancelled = false
        fake.cityResult = { try { awaitCancellation() } finally { cancelled = true } }
        launch(fake)
        search("Казань")
        scrollTo("discovery_loading").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { cancelled }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("discovery_loading").assertDoesNotExist()
        scrollTo("discovery_message").assertTextEquals("Поиск остановлен после выхода из приложения")
        compose.runOnIdle { assertEquals(listOf("Казань"), fake.queries) }
        scrollTo("discovery_manual").assertIsDisplayed()
    }

    @Test fun filteringAndCancellingManualEntryDoNotSaveAnythingOrStartAnotherNetworkSearch() {
        val fake = FakeDiscovery()
        var saved = false
        launch(fake, onSave = { saved = true; true })
        searchAndChooseKazan()
        scrollTo("discovery_name_filter").performTextInput("Несуществующий")
        scrollTo("discovery_empty").assertTextContains("Нет храма", substring = true)
        returnToSearchWithoutRequest(fake)
        scrollTo("discovery_name_filter").assertTextContains("Несуществующий")
        scrollTo("discovery_empty").assertTextContains("Нет храма", substring = true)
        scrollTo("discovery_manual").performClick()
        compose.onNodeWithTag("new_church_name").performTextInput("Мой храм")
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        scrollTo("discovery_manual").performClick()
        compose.onNodeWithText("Мой храм").assertDoesNotExist()
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(saved); assertEquals(1, fake.queries.size); assertEquals(1, fake.centers.size) }
    }

    @Test fun savedChurchesDoNotPushSearchOffScreenAndSelectWithoutNetworkOrAForm() {
        val saved = (1..6).map { index -> SavedChurchRecord.create("Сохраненный храм $index", "Москва", "Улица $index", "Europe/Moscow").asParish() }
        val fake = FakeDiscovery()
        val selected = mutableListOf<String>()
        launch(fake, initialMode = ChurchSearchMode.NAME, savedChurches = saved, onSelectSaved = { selected += it })
        compose.onNodeWithTag("discovery_query").assertIsDisplayed()
        scrollTo("discovery_saved_${saved.first().id}").performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(saved.first().id), selected)
            assertTrue(fake.nameQueries.isEmpty()); assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty())
        }
    }

    private fun launch(fake: FakeDiscovery, onBack: () -> Unit = {}, onSave: (SavedChurchRecord) -> Boolean = { true },
        initialMode: ChurchSearchMode = ChurchSearchMode.CITY, savedChurches: List<Parish> = emptyList(), onSelectSaved: (String) -> Unit = {}) {
        compose.setContent {
            var query by rememberSaveable { mutableStateOf("") }
            var mode by rememberSaveable { mutableStateOf(initialMode) }
            GuideTheme(dark = false) { ChurchDiscoveryScreen(fake, onBack, { record, selected ->
                if (onSave(record)) { selected(); ChurchSelectionResult.SELECTED } else ChurchSelectionResult.FAILED
            }, {}, query = query, onQueryChange = { query = it }, mode = mode, onModeChange = { mode = it },
                savedChurches = savedChurches, onSelectSaved = { id, selected -> onSelectSaved(id); selected(); ChurchSelectionResult.SELECTED }) }
        }
    }
    private fun search(query: String) {
        scrollTo("discovery_query").performTextReplacement(query)
        waitForVisibleKeyboard()
        compose.onNodeWithTag("discovery_query").performImeAction()
        waitForHiddenKeyboard()
    }
    private fun searchAndChooseKazan() {
        search("Казань")
        scrollTo("discovery_city_osm:relation:1").performClick()
    }
    private fun returnToSearchWithoutRequest(fake: FakeDiscovery) {
        val queries = fake.queries.toList()
        val centers = fake.centers.toList()
        scrollTo("discovery_query").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(queries, fake.queries)
            assertEquals(centers, fake.centers)
        }
    }
    private fun waitForVisibleKeyboard() = withCiFailureDiagnostics("discovery.waitForVisibleKeyboard") {
        // Text injection requests IME asynchronously. Observe its opening before submitting,
        // otherwise a "hidden" check may pass before a queued show changes the viewport.
        compose.waitUntil(timeoutMillis = 10_000) {
            var visible = false
            compose.activityRule.scenario.onActivity { activity ->
                visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
    }

    private fun waitForHiddenKeyboard() = withCiFailureDiagnostics("discovery.waitForHiddenKeyboard") {
        compose.waitUntil(timeoutMillis = 10_000) {
            var hidden = false
            compose.activityRule.scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                hidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
            }
            hidden
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        compose.onNodeWithTag("discovery_query").assertIsNotFocused()
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun <T> success(items: List<T>, provider: DiscoveryProvider): DiscoveryResult.Success<T> =
        DiscoveryResult.Success(items, fetchedAt, DiscoveryCacheStatus.FRESH_NETWORK, provider,
            if (provider == DiscoveryProvider.VK_MAPS) kazan.center.searchAreaCenter() else null,
            if (provider == DiscoveryProvider.VK_MAPS) 3_000 else null)

    private inner class FakeDiscovery : PlaceDiscovery {
        val queries = mutableListOf<String>()
        val nameQueries = mutableListOf<String>()
        val centers = mutableListOf<GeoPoint>()
        var cityResult: suspend (String) -> DiscoveryResult<DiscoveryCity> = { success(listOf(kazan), DiscoveryProvider.PHOTON) }
        var churchResult: suspend (GeoPoint) -> DiscoveryResult<DiscoveredChurch> = { success(listOf(church), DiscoveryProvider.VK_MAPS) }
        var nameResult: suspend (String) -> DiscoveryResult<DiscoveredChurch> = { success(listOf(church), DiscoveryProvider.PHOTON) }
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
            nameQueries += query
            return nameResult(query)
        }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> {
            queries += query
            return cityResult(query)
        }
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
            centers += center
            return churchResult(center)
        }
    }
}
