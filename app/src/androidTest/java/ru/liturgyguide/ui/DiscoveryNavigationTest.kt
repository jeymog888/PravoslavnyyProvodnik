package ru.liturgyguide.ui

import ru.liturgyguide.service.ChurchSelectionResult

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val city = DiscoveryCity("osm:node:1", "Казань", "Татарстан", "Россия", GeoPoint(55.79, 49.1))
    private val church = DiscoveredChurch("osm:way:42", "Новый храм", city.center, "", "", null, false,
        "https://www.openstreetmap.org/way/42")

    @Test fun discoveryReturnsToSavedPickerAndOnlyConfirmationReplacesTheParish() {
        check(preferences.edit().clear().putString("stage_id", "creed").putBoolean("dark", false).commit())
        val before = preferences.all.toMap()
        var requests = 0
        val fake = object : PlaceDiscovery {
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> {
                requests++
                return DiscoveryResult.Success(listOf(city), 0, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
            }
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
                requests++
                return DiscoveryResult.Success(listOf(church), 0, DiscoveryCacheStatus.FRESH_NETWORK,
                    DiscoveryProvider.VK_MAPS, city.center, radiusMeters)
            }
        }
        val restore = StateRestorationTester(compose)
        val container = AppContainer(context, fake)
        restore.setContent { AppNavigation(container, Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC)) }
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("discover_churches").performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(before, preferences.all) }
        compose.onNodeWithTag("discovery_manual").performClick()
        compose.onNodeWithTag("church_picker").assertIsDisplayed()
        compose.onNodeWithTag("discover_churches").performClick()
        compose.onNodeWithTag("discovery_city_query").performTextInput("Казань")
        compose.onNodeWithTag("discovery_city_query").performImeAction()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_city_${city.id}"))
        compose.onNodeWithTag("discovery_city_${city.id}").performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        compose.runOnIdle { assertEquals(before, preferences.all); assertEquals(2, requests) }
        compose.onNodeWithTag("cancel_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_church_${church.id}"))
        compose.onNodeWithTag("discovery_church_${church.id}").performClick()
        val beforeSave = preferences.all.toMap()
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
        compose.runOnIdle { assertEquals(2, requests) }
        pressBack()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
    }

    @Test fun aMappedTempleWithoutACityTagCanBeSavedWithItsKnownCoordinates() {
        var saved: SavedChurchRecord? = null
        val record = SavedChurchRecord(church.id, church.name, "", "", "Europe/Moscow",
            church.center.latitude, church.center.longitude, origin = SavedChurchOrigin.OPEN_STREET_MAP,
            sourceUrl = church.sourceUrl)
        compose.setContent { GuideTheme(false) { SavedChurchForm(onCancel = {}, onSave = { saved = it; ChurchSelectionResult.SELECTED }, initialRecord = record) } }
        compose.onNodeWithTag("new_church_city").assertTextContains("Город, если знаете")
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("", saved!!.city)
            assertEquals(record.latitude, saved!!.latitude)
        }
    }
}
