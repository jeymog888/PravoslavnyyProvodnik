package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DiscoveryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val city = DiscoveryCity("osm:node:1", "Казань", "Татарстан", "Россия", GeoPoint(55.79, 49.1))
    private val church = DiscoveredChurch("osm:way:42", "Новый храм", city.center, "", "", null, false,
        "https://www.openstreetmap.org/way/42")

    @Test fun pickerNameSurvivesBackAndRecreationAndReentryAlwaysSearchesNames() =
        withCiFailureDiagnostics("DiscoveryNavigationTest.queryBackAndRecreation") {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        val before = preferences.all.toMap()
        val queries = mutableListOf<String>()
        val fake = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
                queries += query
                return DiscoveryResult.Success(listOf(church), 0, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
            }
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("Name entry must not request a city")
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("Name entry must not request nearby places")
        }
        val restore = StateRestorationTester(compose)
        val container = AppContainer(context, fake, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
        restore.setContent { AppNavigation(container, Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC)) }
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("church_search").performTextInput("Сретенский монастырь Москва")
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("discover_churches"))
        compose.onNodeWithTag("discover_churches").performClick()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains("Сретенский монастырь Москва")
        compose.runOnIdle { assertTrue(queries.isEmpty()) }
        compose.onNodeWithTag("discovery_mode_city").performClick()
        compose.onNodeWithTag("discovery_mode_city").assertIsSelected()
        // Back first dismisses the IME while the transferred input is still closing.
        // This assertion checks navigation, so dismiss the keyboard explicitly first.
        closeSoftKeyboardWithCiDiagnostics()
        pressBack()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("church_search").assertTextContains("Сретенский монастырь Москва")
        compose.onNodeWithTag("discover_churches").performClick()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains("Сретенский монастырь Москва")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains("Сретенский монастырь Москва")
        compose.runOnIdle { assertTrue(queries.isEmpty()); assertEquals(before, preferences.all) }
        // The query was transferred from the picker; submit the visible button.
        // Performing an IME action on this unfocused field first opens an artificial IME race.
        compose.onNodeWithTag("discovery_submit").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("Сретенский монастырь Москва"), queries) }
        pressBack()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_search"))
        compose.onNodeWithTag("church_search").assertTextContains("Сретенский монастырь Москва")
        compose.runOnIdle { assertEquals(before, preferences.all) }
    }

    @Test fun firstHomeImportSurvivesDiscoveryBackAndRecreationAndReturnsHomeAfterSaving() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        val before = preferences.all.toMap()
        val queries = mutableListOf<String>()
        val fake = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
                queries += query
                return DiscoveryResult.Success(listOf(church), 0, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
            }
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("Name entry must not request a city")
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("Name entry must not request nearby places")
        }
        val restore = StateRestorationTester(compose)
        val container = AppContainer(context, fake, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
        restore.setContent { AppNavigation(container, Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC)) }
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_search").performTextInput("Новый храм Казань")
        closeSoftKeyboardWithCiDiagnostics()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("discover_churches"))
        compose.onNodeWithTag("discover_churches").performClick()
        pressBack()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("discover_churches"))
        compose.onNodeWithTag("discover_churches").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discovery_query").assertTextContains("Новый храм Казань")
        compose.runOnIdle { assertEquals(before, preferences.all); assertTrue(queries.isEmpty()) }
        // This query was transferred from the picker and is unfocused after recreation.
        compose.onNodeWithTag("discovery_submit").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        compose.onNodeWithTag("confirm_import_timezone").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("church_content").assertDoesNotExist()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.onNodeWithTag("open_church").assertTextContains(church.name)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("schedule_unknown"))
        compose.onNodeWithTag("schedule_unknown").assertTextEquals("Литургия")
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("find_stage"))
        compose.onAllNodesWithTag("find_stage").assertCountEquals(1)
        compose.onNodeWithTag("find_stage").assertTextEquals("Найти текущий момент").assertIsDisplayed()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("set_service_time"))
        compose.onNodeWithTag("set_service_time").assertIsDisplayed()
        compose.onNodeWithTag("watch_current").assertDoesNotExist()
        compose.onNodeWithTag("watch_next").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(church.id, preferences.getString("church_id", null))
            assertEquals(listOf("Новый храм Казань"), queries)
            val saved = ChurchDirectoryStore(preferences).records().single()
            assertEquals(church.id, saved.id)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, saved.origin)
            assertTrue(saved.asParish().schedule.isEmpty())
        }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
        compose.onNodeWithTag("open_church").assertTextContains(church.name)
    }

    @Test fun nameDiscoveryReturnsToSavedPickerAndOnlyConfirmationReplacesTheParish() =
        withCiFailureDiagnostics("DiscoveryNavigationTest.nameSelection", additionalEvidence = { directory, deadline, phase ->
            if (phase == CiEvidencePhase.AFTER_SCREENSHOT) {
                writeCiFailureEvidence(directory, "discovery-roots.txt", deadline) {
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
                        compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString(maxDepth = 100)
                    }
                }
            }
        }) {
        check(preferences.edit().clear().putString("stage_id", "creed").putBoolean("dark", false).commit())
        val before = preferences.all.toMap()
        var requests = 0
        val fake = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
                requests++
                return DiscoveryResult.Success(listOf(church), 0, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
            }
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("A name result must not require a city")
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("A name result must not require nearby search")
        }
        val restore = StateRestorationTester(compose)
        val container = AppContainer(context, fake, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
        restore.setContent { AppNavigation(container, Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC)) }
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("discover_churches").performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(before, preferences.all) }
        compose.onNodeWithTag("discovery_manual").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("discover_churches").performClick()
        compose.onNodeWithTag("discovery_query").performTextInput("Новый храм Казань")
        waitForKeyboard(visible = true)
        compose.onNodeWithTag("discovery_query").performImeAction()
        waitForKeyboard(visible = false)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        compose.onNodeWithTag("discovery_query").assertIsNotFocused()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        compose.runOnIdle { assertEquals(before, preferences.all); assertEquals(1, requests) }
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        val beforeSave = preferences.all.toMap()
        compose.onNodeWithTag("confirm_import_timezone").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        val beforeChoice = preferences.all.toMap()
        assertEquals(beforeSave, beforeChoice - ChurchDirectoryStore.RECORDS_KEY)
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("new_church_error").assertDoesNotExist()
        compose.onNodeWithTag("cancel_church_change").performClick()
        compose.runOnIdle { assertEquals(beforeChoice, preferences.all) }
        compose.onNodeWithTag("add_church_form").assertIsDisplayed()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText("Новый храм").assertIsDisplayed()
        val saved = ChurchDirectoryStore(preferences).records().single()
        assertEquals(church.id, saved.id)
        assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, saved.origin)
        assertEquals(church.id, preferences.getString("church_id", null))
        assertFalse(preferences.contains("stage_id"))
        assertTrue(saved.asParish().schedule.isEmpty())
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Новый храм").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
        pressBack()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
    }

    @Test fun aMappedTempleWithoutACityTagCanBeSavedWithItsKnownCoordinates() {
        var saved: SavedChurchRecord? = null
        val record = SavedChurchRecord(church.id, church.name, "", "", "Europe/Moscow",
            church.center.latitude, church.center.longitude, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            sourceUrl = church.sourceUrl)
        compose.setContent { GuideTheme(false) { SavedChurchForm(onCancel = {}, onSave = { saved = it; ChurchSelectionResult.SELECTED }, initialRecord = record) } }
        compose.onNodeWithTag("edit_import_details").performScrollTo().performClick()
        compose.onNodeWithTag("new_church_city").assertTextContains("Город, если знаете")
        compose.onNodeWithTag("confirm_import_timezone").performScrollTo().performClick()
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("", saved!!.city)
            assertEquals(record.latitude, saved!!.latitude)
        }
    }

    private fun waitForKeyboard(visible: Boolean) {
        // IME show/hide is outside Compose's clock. Observe both boundaries before
        // touching a result whose position changes with the native viewport.
        compose.waitUntil(timeoutMillis = 10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
            }
            matches
        }
    }
}
