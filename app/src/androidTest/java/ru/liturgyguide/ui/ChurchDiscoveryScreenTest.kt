package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.ChurchLocationSource
import ru.liturgyguide.service.ForegroundLocationFix
import ru.liturgyguide.service.ForegroundLocationResult
import java.time.Instant
import ru.liturgyguide.service.discovery.*

@RunWith(AndroidJUnit4::class)
class ChurchDiscoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val kazan = DiscoveryCity("osm:relation:1", "Казань", "Татарстан", "Россия", GeoPoint(55.796, 49.106))
    private val namesake = DiscoveryCity("osm:node:2", "Казань", "Другая область", "Россия", GeoPoint(54.5, 45.0))
    private val church = DiscoveredChurch("osm:way:42", "Никольский храм", GeoPoint(55.797, 49.107),
        "улица Баумана, 5", "", "https://example.com/", false, "https://www.openstreetmap.org/way/42")
    private val fetchedAt = 1_794_096_000_000L

    @Test fun ambiguousStationRequiresExplicitChoiceBeforeNearbySearch() {
        val first = DiscoveryLandmark("osm:node:10", "Центральная", kazan.center, "Казань", "Татарстан", "Россия")
        val second = first.copy(id = "osm:node:11", center = GeoPoint(51.6, 39.2), city = "Воронеж", region = "Воронежская область")
        val fake = FakeDiscovery().apply { nameResult = { success(emptyList<DiscoveredChurch>(), DiscoveryProvider.PHOTON)
            .copy(landmarkChoices = listOf(first, second), landmarkChurchQuery = "Никольский") } }
        launch(fake, initialMode = ChurchSearchMode.NAME)
        search("метро Центральная Никольский храм")
        scrollTo("discovery_landmark_heading").assertIsDisplayed()
        scrollTo("discovery_landmark_${second.id}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(fake.centers.isEmpty()); assertTrue(fake.queries.isEmpty()) }
        scrollTo("discovery_landmark_${second.id}").performClick()
        scrollTo("discovery_church_${church.id}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(second.center), fake.centers)
            assertEquals(listOf("метро Центральная Никольский храм"), fake.nameQueries)
        }
        compose.onNodeWithTag("discovery_landmark_heading").assertDoesNotExist()
    }

    @Test fun resolvedLandmarkRetainsDistinctChurchAddressesAndDoesNotTriggerGps() {
        val station = DiscoveryLandmark("osm:node:277499849", "Сухаревская", GeoPoint(55.7729551, 37.6312038), "Москва", "Москва", "Россия")
        val listy = church.copy(id = "osm:relation:19595736", name = "Храм Троицы Живоначальной в Листах", address = "улица Сретенка, 27/29",
            city = "Москва", center = GeoPoint(55.772, 37.632), sourceUrl = "https://www.openstreetmap.org/relation/19595736")
        val other = listy.copy(id = "osm:node:5053943230", name = "Храм Троицы Живоначальной", address = "Большая Сухаревская площадь, 3")
        val fake = FakeDiscovery().apply { nameResult = {
            DiscoveryResult.Success(listOf(listy, other), fetchedAt, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON,
                station.center, 2_000, landmark = station)
        } }
        var selected: SavedChurchRecord? = null
        launch(fake, initialMode = ChurchSearchMode.NAME, onSave = { selected = it; true })
        search("метро сухаревская троицы")
        scrollTo("discovery_nearby_notice").assertTextContains("список может быть неполным", substring = true)
        compose.onNodeWithTag("discovery_saved_empty").assertDoesNotExist()
        compose.onNodeWithTag("discovery_name_filter").assertDoesNotExist()
        compose.onNodeWithTag("discovery_result_notice").assertDoesNotExist()
        scrollTo("discovery_church_${other.id}").assertIsDisplayed()
        scrollTo("discovery_church_${listy.id}").performClick()
        compose.runOnIdle {
            assertEquals(listy.id, selected?.id)
            assertEquals(listy.address, selected?.address)
            assertTrue(fake.centers.isEmpty()); assertTrue(fake.queries.isEmpty())
        }
    }

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
            .assertTextContains("православного храма", substring = true)
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
        val fake = FakeDiscovery().apply { churchResult = {
            success((1..6).map { index -> church.copy(id = "osm:way:$index", name = "Храм $index") }, DiscoveryProvider.VK_MAPS)
        } }
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

    @Test fun nearbyResultsReplaceTheSavedPreviewAndAreVisibleWithoutScrolling() {
        val saved = (1..6).map { index -> SavedChurchRecord.create(
            "Сохраненный храм Святой Живоначальной Троицы в большом городе $index", "Москва",
            "Длинная улица с подробным адресом, дом $index", "Europe/Moscow").asParish() }
        val answer = CompletableDeferred<DiscoveryResult<DiscoveredChurch>>()
        val fake = FakeDiscovery().apply { churchResult = { answer.await() } }
        val location = object : ChurchLocationSource {
            override fun hasPermission() = true
            override fun locateResult(maxAccuracyMeters: Float, maxLocationAgeMillis: Long,
                onResult: (ForegroundLocationResult) -> Unit) {
                onResult(ForegroundLocationResult.Located(ForegroundLocationFix(
                    kazan.center.latitude, kazan.center.longitude, 20f, SystemClock.elapsedRealtimeNanos())))
            }
            override fun stop() = Unit
        }
        compose.setContent {
            GuideTheme(dark = false) {
                ChurchDiscoveryScreen(fake, onBack = {}, onSave = { _, _ -> ChurchSelectionResult.FAILED }, onOpenUrl = {},
                    query = "", onQueryChange = {}, mode = ChurchSearchMode.NAME, onModeChange = {}, savedChurches = saved,
                    onSelectSaved = { _, _ -> ChurchSelectionResult.SELECTED }, locationSource = location)
            }
        }
        // No scroll helpers: this must work in the visible viewport (root acceptance uses 360 dp).
        compose.onNodeWithTag("discovery_nearby").assertIsDisplayed().performClick()
        compose.onNodeWithTag("discovery_loading").assertIsDisplayed()
        saved.forEach { compose.onNodeWithTag("discovery_saved_${it.id}").assertDoesNotExist() }
        compose.runOnIdle { answer.complete(DiscoveryResult.Success(
            listOf(church) + (100..252).map { church.copy(id = "osm:way:$it", name = "Другой храм $it") }, fetchedAt,
            DiscoveryCacheStatus.LOCAL_SNAPSHOT, DiscoveryProvider.LOCAL_CATALOG, kazan.center, 3_000)) }
        compose.waitForIdle()
        compose.onNodeWithTag("discovery_church_${church.id}").assertIsDisplayed()
        saved.forEach { compose.onNodeWithTag("discovery_saved_${it.id}").assertDoesNotExist() }
    }

    @Test fun emptyAndFailedSearchesRestoreSavedChoicesWithoutClaimingNoChurchExists() {
        val saved = SavedChurchRecord.create("Никольский храм", "Казань", "Улица, 1", "Europe/Moscow").asParish()
        val fake = FakeDiscovery().apply { nameResult = { success(emptyList(), DiscoveryProvider.PHOTON) } }
        launch(fake, initialMode = ChurchSearchMode.NAME, savedChurches = listOf(saved))
        search("Никольский")
        compose.onNodeWithTag("discovery_empty").assertTextEquals("Поиск не дал новых результатов").assertIsDisplayed()
        scrollTo("discovery_saved_${saved.id}").assertIsDisplayed()
        compose.runOnIdle { fake.nameResult = { DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE) } }
        search("Никольский храм")
        scrollTo("discovery_message").assertIsDisplayed()
        scrollTo("discovery_saved_${saved.id}").assertIsDisplayed()
        compose.onNodeWithTag("discovery_empty").assertDoesNotExist()
        compose.runOnIdle { fake.nameResult = { success<DiscoveredChurch>(emptyList(), DiscoveryProvider.PHOTON)
            .copy(notice = "Не удалось проверить станцию. Попробуйте еще раз") } }
        search("метро Сухаревская Троицы")
        compose.onNodeWithTag("discovery_empty").assertIsDisplayed()
        compose.onNodeWithTag("discovery_result_notice").assertTextEquals("Не удалось проверить станцию. Попробуйте еще раз").assertIsDisplayed()
    }

    @Test fun aSavedResultOutsideTheSixRowPreviewIsSelectedWithoutReplacingItsWebsiteOrCity() {
        val savedRecord = requireNotNull(church.copy(name = "Яковлевский храм", city = "Казань",
            website = "http://parish.example/raspisanie/").toSavedChurch())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("discovery-preserve-saved-test", 0)
        check(preferences.edit().clear().commit())
        val store = ChurchDirectoryStore(preferences)
        assertTrue(store.save(savedRecord))
        val saved = (1..6).map { index -> SavedChurchRecord.create("Храм $index", "Казань", "Улица $index", "Europe/Moscow").asParish() } +
            savedRecord.asParish()
        val fake = FakeDiscovery().apply { churchResult = {
            success(listOf(church.copy(website = null, city = "")), DiscoveryProvider.LOCAL_CATALOG)
        } }
        val selected = mutableListOf<String>()
        var saves = 0
        try {
            launch(fake, savedChurches = saved, onSelectSaved = { selected += it }, onSave = {
                saves++; store.save(it)
            })
            searchAndChooseKazan()
            compose.onNodeWithTag("discovery_saved_${savedRecord.id}").assertDoesNotExist()
            scrollTo("discovery_church_${savedRecord.id}").performClick()
            compose.runOnIdle {
                assertEquals(listOf(savedRecord.id), selected)
                assertEquals(0, saves)
                assertEquals(listOf(savedRecord), ChurchDirectoryStore(preferences).records())
            }
        } finally { check(preferences.edit().clear().commit()) }
    }

    @Test fun theVerifiedOpalikhaMapObjectSelectsItsPreparedParishWithoutCreatingAnEmptyDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prepared = context.assets.open("churches.json").bufferedReader().use { ParishRepository(it.readText()) }
            .directory.churches
        val place = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use {
            LocalChurchCatalog.decode(it, Instant.parse("2026-11-12T06:00:00Z"))
        }.nearby(GeoPoint(55.82553, 37.24345), 500) {}.single { it.id == "osm:way:186160039" }
        val fake = FakeDiscovery().apply { nameResult = {
            success(listOf(place), DiscoveryProvider.LOCAL_CATALOG).copy(cacheStatus = DiscoveryCacheStatus.LOCAL_SNAPSHOT)
        } }
        var saves = 0
        val selected = mutableListOf<String>()
        launch(fake, initialMode = ChurchSearchMode.NAME, savedChurches = prepared,
            onSelectSaved = { selected += it }, onSave = { saves++; true })
        search("Святой Преподобномученицы Елисаветы")
        scrollTo("discovery_church_${place.id}").performClick()
        compose.runOnIdle {
            assertEquals(listOf("elisavetinsky_opalikha"), selected)
            assertEquals(0, saves)
            assertTrue(prepared.single { it.id == selected.single() }.schedule.isNotEmpty())
        }
    }

    @Test fun filteringNearbyKeepsTheMatchingPreparedChurchVisibleUnderItsMapName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prepared = context.assets.open("churches.json").bufferedReader().use { ParishRepository(it.readText()) }
            .directory.churches
        val point = GeoPoint(55.82553, 37.24345)
        val places = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use {
            LocalChurchCatalog.decode(it, Instant.parse("2026-11-12T06:00:00Z"))
        }.nearby(point, 5_000) {}
        assertTrue("The real nearby list must expose its name filter", places.size > 5)
        val place = places.single { it.id == "osm:way:186160039" }
        val fake = FakeDiscovery().apply { churchResult = {
            success(places, DiscoveryProvider.LOCAL_CATALOG).copy(cacheStatus = DiscoveryCacheStatus.LOCAL_SNAPSHOT,
                queryCenter = point.searchAreaCenter(), radiusMeters = 5_000)
        } }
        // Declared test position: this regression exercises filtering and selection, not GPS accuracy.
        val location = object : ChurchLocationSource {
            override fun hasPermission() = true
            override fun locateResult(maxAccuracyMeters: Float, maxLocationAgeMillis: Long,
                onResult: (ForegroundLocationResult) -> Unit) {
                onResult(ForegroundLocationResult.Located(ForegroundLocationFix(
                    point.latitude, point.longitude, 4_500f, SystemClock.elapsedRealtimeNanos())))
            }
            override fun stop() = Unit
        }
        val query = mutableStateOf("")
        val selected = mutableListOf<String>()
        var saves = 0
        compose.setContent {
            GuideTheme(dark = false) {
                ChurchDiscoveryScreen(fake, onBack = {}, onSave = { _, _ -> saves++; ChurchSelectionResult.FAILED },
                    onOpenUrl = {}, query = query.value, onQueryChange = { query.value = it },
                    mode = ChurchSearchMode.NAME, onModeChange = {}, savedChurches = prepared,
                    onSelectSaved = { id, done -> selected += id; done(); ChurchSelectionResult.SELECTED },
                    locationSource = location)
            }
        }
        scrollTo("discovery_saved_elisavetinsky_opalikha").assertIsDisplayed()
        scrollTo("discovery_nearby").performClick()
        compose.onNodeWithTag("discovery_saved_elisavetinsky_opalikha").assertDoesNotExist()
        scrollTo("discovery_church_${place.id}").assertIsDisplayed()
        scrollTo("discovery_name_filter").performTextReplacement("Елисаветы")
        scrollTo("discovery_church_${place.id}").assertIsDisplayed()
        compose.onNodeWithTag("discovery_empty").assertDoesNotExist()
        compose.onNodeWithTag("discovery_church_${place.id}").performClick()
        compose.runOnIdle {
            assertEquals("", query.value)
            assertEquals(listOf(point), fake.centers)
            assertEquals(listOf("elisavetinsky_opalikha"), selected)
            assertEquals(0, saves)
        }
    }

    @Test fun citylessMetroNamesakesShowTheirNetworksAndRequireTheChosenStation() {
        val lower = DiscoveryLandmark("osm:node:442018472", "Кировская", GeoPoint(56.2477056, 43.8769644), "", "", "",
            network = "Нижегородский метрополитен")
        val samara = DiscoveryLandmark("osm:node:6986625318", "Кировская", GeoPoint(53.2112906, 50.2695324), "", "", "",
            network = "Самарский метрополитен")
        val fake = FakeDiscovery().apply { nameResult = { success<DiscoveredChurch>(emptyList(), DiscoveryProvider.LOCAL_CATALOG)
            .copy(landmarkChoices = listOf(lower, samara), landmarkChurchQuery = "Троицы",
                cacheStatus = DiscoveryCacheStatus.LOCAL_SNAPSHOT) } }
        launch(fake, initialMode = ChurchSearchMode.NAME)
        search("метро Кировская Троицы")
        scrollTo("discovery_landmark_${lower.id}").assertTextContains("Нижегородский метрополитен", substring = true)
        scrollTo("discovery_landmark_${samara.id}").assertTextContains("Самарский метрополитен", substring = true)
        compose.onNodeWithTag("discovery_catalog_notice").assertDoesNotExist()
        compose.onNodeWithTag("discovery_empty").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fake.centers.isEmpty()) }
        compose.onNodeWithTag("discovery_landmark_${samara.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(samara.center), fake.centers) }
    }

    @Test fun sameMetroNetworkNamesakesShowPublishedMapColours() {
        val blue = DiscoveryLandmark("osm:node:242546476", "Арбатская", GeoPoint(55.752095, 37.6069779), "", "", "",
            network = "Московский метрополитен", mapColour = "blue")
        val light = blue.copy(id = "osm:node:253043175", center = GeoPoint(55.7518523, 37.6006567), mapColour = "lightblue")
        val fake = FakeDiscovery().apply { nameResult = { success<DiscoveredChurch>(emptyList(), DiscoveryProvider.LOCAL_CATALOG)
            .copy(landmarkChoices = listOf(blue, light)) } }
        launch(fake, initialMode = ChurchSearchMode.NAME)
        search("метро Арбатская")
        scrollTo("discovery_landmark_${blue.id}").assertTextContains("На схеме: синий", substring = true)
        scrollTo("discovery_landmark_${light.id}").assertTextContains("На схеме: голубой", substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf(light.center), fake.centers) }
    }

    @Test fun identicalCitySubtitlesKeepBothChoicesAndShowTheirPublicCoordinates() {
        val first = kazan.copy(id = "osm:node:704304024", name = "Высокая Гора", center = GeoPoint(55.9101814, 49.3072048),
            contextLabel = "Высокогорский район")
        val second = first.copy(id = "osm:node:704304026", center = GeoPoint(55.9334601, 49.3074759))
        val fake = FakeDiscovery().apply { cityResult = { success(listOf(first, second), DiscoveryProvider.LOCAL_CATALOG) } }
        launch(fake)
        search("Высокая Гора")
        scrollTo("discovery_city_${first.id}").assertTextContains("55.91018, 49.30720", substring = true)
        scrollTo("discovery_city_${second.id}").assertTextContains("55.93346, 49.30748", substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf(second.center), fake.centers) }
    }

    @Test fun searchButtonUsesTheCurrentQueryAfterEditingAndSwitchingModesBothWays() {
        val fake = FakeDiscovery()
        launch(fake, initialMode = ChurchSearchMode.NAME)
        fun submit(value: String) {
            compose.onNodeWithTag("discovery_content").performScrollToIndex(0)
            scrollTo("discovery_query").performTextReplacement(value)
            waitForVisibleKeyboard()
            androidx.test.espresso.Espresso.pressBack()
            waitForHiddenKeyboard(requireUnfocused = false)
            scrollTo("discovery_submit").assertIsEnabled().assertIsDisplayed().performClick()
            waitForHiddenKeyboard()
        }
        submit("Никольский")
        submit("Троицы")
        compose.onNodeWithTag("discovery_content").performScrollToIndex(0)
        compose.onNodeWithTag("discovery_mode_city").assertIsDisplayed().performClick()
        submit("Казань")
        compose.onNodeWithTag("discovery_content").performScrollToIndex(0)
        compose.onNodeWithTag("discovery_mode_name").assertIsDisplayed().performClick()
        submit("Сретенский")
        compose.runOnIdle {
            assertEquals(listOf("Никольский", "Троицы", "Сретенский"), fake.nameQueries)
            assertEquals(listOf("Казань"), fake.queries)
        }
    }

    @Test fun largeTextKeepsSearchModeWordsWholeAndActionsSeparate() {
        val fake = FakeDiscovery()
        val saved = (1..6).map { SavedChurchRecord.create("Никольский храм $it", "Казань", "Улица, $it", "Europe/Moscow").asParish() }
        val mode = mutableStateOf(ChurchSearchMode.NAME)
        val query = mutableStateOf("")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GuideTheme(dark = false) {
                    Box(Modifier.requiredWidth(320.dp)) {
                        ChurchDiscoveryScreen(fake, onBack = {}, onSave = { _, _ -> ChurchSelectionResult.FAILED },
                            onOpenUrl = {}, query = query.value, onQueryChange = { query.value = it }, mode = mode.value,
                            onModeChange = { mode.value = it }, savedChurches = saved,
                            onSelectSaved = { _, _ -> ChurchSelectionResult.SELECTED })
                    }
                }
            }
        }
        for (tag in listOf("discovery_mode_name", "discovery_mode_city")) {
            scrollTo(tag).assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("${tag}_label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val expected = if (tag == "discovery_mode_name") "По названию" else "По городу"
            assertTextFitsLayout(layouts.single(), expected, singleLine = true)
        }
        val first = compose.onNodeWithTag("discovery_mode_name").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("discovery_mode_city").fetchSemanticsNode().boundsInRoot
        val spacing = with(compose.density) { 12.dp.toPx() }
        assertTrue("Large text requires separate full-width rows", second.top >= first.bottom + spacing - 1)
        scrollTo("discovery_mode_city").performClick()
        compose.runOnIdle { assertEquals(ChurchSearchMode.CITY, mode.value) }
        scrollTo("discovery_query").assertTextContains("Город или село")
        search("Казань")
        // Do not scroll to the answer: the form otherwise occupies the entire large-text viewport.
        compose.onNodeWithTag("discovery_city_osm:relation:1").assertIsDisplayed()
        // The large form is one lazy item taller than some viewports. Return to its start
        // before tapping, rather than injecting a tap into a node clipped by the fixed header.
        compose.onNodeWithTag("discovery_content").performScrollToIndex(0)
        compose.onNodeWithTag("discovery_mode_name").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(ChurchSearchMode.NAME, mode.value)
            fake.nameResult = { success(emptyList(), DiscoveryProvider.PHOTON) }
        }
        search("Никольский")
        compose.onNodeWithTag("discovery_empty").assertTextEquals("Поиск не дал новых результатов").assertIsDisplayed()
        scrollTo("discovery_saved_${saved.first().id}").assertIsDisplayed()
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

    private fun waitForHiddenKeyboard(requireUnfocused: Boolean = true) = withCiFailureDiagnostics("discovery.waitForHiddenKeyboard") {
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
        if (requireUnfocused) compose.onAllNodesWithTag("discovery_query").fetchSemanticsNodes().forEach {
            compose.onNodeWithTag("discovery_query").assertIsNotFocused()
        }
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
