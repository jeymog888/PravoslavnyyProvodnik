package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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

    @Test
    fun searchesOnlyAfterAnActionAndSavesOnlyAfterReviewingTheSelectedCityAndTimeZone() {
        val fake = FakeDiscovery()
        fake.cityResult = { success(listOf(kazan, namesake), DiscoveryProvider.PHOTON) }
        val saved = mutableListOf<SavedChurchRecord>()
        var allowSave = false
        launch(fake, onSave = { saved += it; allowSave })
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()); assertTrue(fake.centers.isEmpty()) }
        compose.onNodeWithTag("discovery_privacy").assertExists()
        compose.onNodeWithTag("discovery_city_query").performTextInput("Казань")
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()) }
        compose.onNodeWithTag("discovery_city_query").performImeAction()
        scrollTo("discovery_city_osm:node:2").performClick()
        compose.runOnIdle { assertEquals(listOf(namesake.center), fake.centers); assertTrue(saved.isEmpty()) }
        scrollTo("discovery_church_osm:way:42").performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("new_church_city").assertTextContains("Казань")
        compose.onNodeWithTag("pin_new_church").assertDoesNotExist()
        compose.onNodeWithTag("church_timezone_picker").performScrollTo().performClick()
        compose.onNodeWithTag("zone_Asia/Vladivostok").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("new_church_error").assertExists()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals("Asia/Vladivostok", saved.single().timezone)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, saved.single().origin)
            assertEquals(church.id, saved.single().id)
            assertEquals(church.sourceUrl, saved.single().sourceUrl)
            assertEquals(church.center.latitude, saved.single().latitude!!, 0.0)
            assertTrue(saved.single().asParish().schedule.isEmpty())
            allowSave = true
        }
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
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
        launch(fake, onBack = { returned = true })
        search("Первый")
        scrollTo("discovery_cancel").performClick()
        compose.onNodeWithTag("discovery_loading").assertDoesNotExist()
        search("Казань")
        scrollTo("discovery_city_osm:relation:1").assertIsDisplayed()
        compose.runOnIdle { oldResult.complete(success(listOf(namesake), DiscoveryProvider.PHOTON)) }
        compose.waitForIdle()
        compose.onNodeWithTag("discovery_city_osm:node:2").assertDoesNotExist()
        scrollTo("discovery_city_osm:relation:1").assertIsDisplayed()
        compose.onNodeWithTag("discovery_manual").performClick()
        compose.runOnIdle { assertTrue(returned) }
    }

    @Test
    fun longMapNamesAndAddressesReachTheFormAndStorageUnshortened() {
        val full = church.copy(name = "Храм " + "Н".repeat(210), city = "Г".repeat(150), address = "Улица " + "А".repeat(300))
        val fake = FakeDiscovery().apply { churchResult = { success(listOf(full), DiscoveryProvider.VK_MAPS) } }
        var saved: SavedChurchRecord? = null
        launch(fake, onSave = { saved = it; true })
        searchAndChooseKazan()
        scrollTo("discovery_church_${full.id}").performClick()
        compose.onNodeWithTag("new_church_name").assertTextContains(full.name)
        compose.onNodeWithTag("new_church_address").performScrollTo().assertTextContains(full.address)
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
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
        searchAndChooseKazan()
        scrollTo("discovery_empty").assertTextEquals("В этом районе храмы не найдены")
        compose.onNodeWithTag("discovery_message").assertDoesNotExist()
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
        compose.onNodeWithTag("discovery_manual").assertIsDisplayed()
    }

    @Test
    fun filteringAndCancellingImportDoNotSaveAnythingOrStartAnotherNetworkSearch() {
        val fake = FakeDiscovery()
        var saved = false
        launch(fake, onSave = { saved = true; true })
        searchAndChooseKazan()
        scrollTo("discovery_name_filter").performTextInput("Несуществующий")
        scrollTo("discovery_empty").assertTextContains("Нет храма", substring = true)
        scrollTo("discovery_name_filter").performTextClearance()
        scrollTo("discovery_church_osm:way:42").performClick()
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        scrollTo("discovery_church_osm:way:42").assertIsDisplayed()
        compose.runOnIdle { assertFalse(saved); assertEquals(1, fake.queries.size); assertEquals(1, fake.centers.size) }
    }

    private fun launch(fake: FakeDiscovery, onBack: () -> Unit = {}, onSave: (SavedChurchRecord) -> Boolean = { true }) {
        compose.setContent { GuideTheme(dark = false) { ChurchDiscoveryScreen(fake, onBack, onSave, {}) } }
    }
    private fun search(query: String) {
        scrollTo("discovery_city_query").performTextReplacement(query)
        compose.onNodeWithTag("discovery_city_query").performImeAction()
    }
    private fun searchAndChooseKazan() {
        search("Казань")
        scrollTo("discovery_city_osm:relation:1").performClick()
    }
    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun <T> success(items: List<T>, provider: DiscoveryProvider): DiscoveryResult.Success<T> =
        DiscoveryResult.Success(items, fetchedAt, DiscoveryCacheStatus.FRESH_NETWORK, provider,
            if (provider == DiscoveryProvider.VK_MAPS) kazan.center.searchAreaCenter() else null,
            if (provider == DiscoveryProvider.VK_MAPS) 3_000 else null)

    private inner class FakeDiscovery : PlaceDiscovery {
        val queries = mutableListOf<String>()
        val centers = mutableListOf<GeoPoint>()
        var cityResult: suspend (String) -> DiscoveryResult<DiscoveryCity> = { success(listOf(kazan), DiscoveryProvider.PHOTON) }
        var churchResult: suspend (GeoPoint) -> DiscoveryResult<DiscoveredChurch> = { success(listOf(church), DiscoveryProvider.VK_MAPS) }
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
