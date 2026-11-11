package ru.liturgyguide.ui

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
import ru.liturgyguide.service.schedule.OfflineInternetSchedules
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DiscoveryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val church = DiscoveredChurch("osm:way:42", "Новый храм", GeoPoint(55.79, 49.1),
        "улица Баумана, 5", "Казань", null, false, "https://www.openstreetmap.org/way/42")

    @Test fun directSearchKeepsItsQueryAcrossBackAndRecreationWithoutSearchingOrSelectingAutomatically() =
        withCiFailureDiagnostics("DiscoveryNavigationTest.queryBackAndRecreation") {
        reset()
        val before = preferences.all.toMap()
        val fake = FakeDiscovery(church)
        val restore = launch(fake)
        openHomeDiscovery()
        compose.onNodeWithTag("discovery_query").performTextInput("Сретенский монастырь Москва")
        waitForKeyboard(visible = true)
        closeSoftKeyboardWithCiDiagnostics()
        waitForKeyboard(visible = false)
        pressBack()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()); assertEquals(before, preferences.all) }

        openHomeDiscovery()
        compose.onNodeWithTag("discovery_query").assertTextContains("Сретенский монастырь Москва")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().assertTextContains("Сретенский монастырь Москва")
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()); assertEquals(before, preferences.all) }
        discoveryNode("discovery_submit").performClick()
        result(church.id).assertIsDisplayed().assertTextContains(church.name)
        compose.runOnIdle {
            assertEquals(listOf("Сретенский монастырь Москва"), fake.queries)
            assertEquals(before, preferences.all)
        }
        pressBack()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        openHomeDiscovery()
        compose.onNodeWithTag("discovery_query").assertTextContains("Сретенский монастырь Москва")
        compose.runOnIdle {
            assertEquals(listOf("Сретенский монастырь Москва"), fake.queries)
            assertEquals(before, preferences.all)
        }
    }

    @Test fun firstHomeSelectionNeedsOneTapAndRestoresTheSavedParishWithoutAForm() {
        reset()
        val before = preferences.all.toMap()
        val fake = FakeDiscovery(church)
        val restore = launch(fake)
        openHomeDiscovery()
        search("Новый храм Казань")
        result(church.id).assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        result(church.id).performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertDoesNotExist()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        homeNode(hasText(church.name)).assertIsDisplayed()
        homeNode(hasTestTag("start_guide")).assertIsEnabled().assertTextEquals("Открыть литургию")
        homeNode(hasTestTag("typical_schedule")).assertTextContains("типовой порядок", substring = true)
        compose.onNodeWithTag("open_service_book").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(church.id, preferences.getString("church_id", null))
            assertEquals(listOf("Новый храм Казань"), fake.queries)
            assertSavedChurch(church)
        }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        homeNode(hasText(church.name)).assertIsDisplayed()
        homeNode(hasTestTag("change_church")).assertIsDisplayed()
        compose.runOnIdle { assertSavedChurch(church); assertEquals(1, fake.queries.size) }
    }

    @Test fun aLegacyReadingStillRequiresConfirmationAndCancellationKeepsItUntouched() =
        withCiFailureDiagnostics("DiscoveryNavigationTest.nameSelection", additionalEvidence = { directory, deadline, phase ->
            if (phase == CiEvidencePhase.AFTER_SCREENSHOT) writeCiFailureEvidence(directory, "discovery-roots.txt", deadline) {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString(maxDepth = 100)
                }
            }
        }) {
        reset(legacyReading = true)
        val before = preferences.all.toMap()
        val fake = FakeDiscovery(church)
        val restore = launch(fake)
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.runOnIdle { assertTrue(fake.queries.isEmpty()); assertEquals(before, preferences.all) }
        search("Новый храм Казань")
        result(church.id).assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        result(church.id).performClick()
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        // The tapped map record may be cached, but the active parish and legacy reading are not changed yet.
        val beforeChoice = preferences.all.toMap()
        compose.runOnIdle {
            assertEquals(before, beforeChoice - ChurchDirectoryStore.RECORDS_KEY)
            assertEquals("creed", preferences.getString("stage_id", null))
            assertSavedChurch(church)
        }
        compose.onNodeWithTag("cancel_church_change").performClick()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(beforeChoice, preferences.all) }
        // The same selected map record can now appear in the unified saved-results section.
        result(church.id).performClick()
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(church.name).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(church.id, preferences.getString("church_id", null))
            assertFalse(preferences.contains("stage_id"))
            assertSavedChurch(church)
            assertEquals(1, fake.queries.size)
        }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(church.name).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fake.queries.size) }
        pressBack()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        homeNode(hasText(church.name)).assertIsDisplayed()
    }

    @Test fun aMappedTempleWithoutACityTagIsSelectedDirectlyWithItsKnownCoordinates() {
        reset()
        val unnamedCity = church.copy(city = "", address = "")
        val fake = FakeDiscovery(unnamedCity)
        launch(fake)
        openHomeDiscovery()
        search(unnamedCity.name)
        compose.runOnIdle { assertFalse(preferences.contains("church_id")); assertTrue(ChurchDirectoryStore(preferences).records().isEmpty()) }
        result(unnamedCity.id).performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.runOnIdle {
            val saved = assertSavedChurch(unnamedCity)
            assertEquals("", saved.city)
            assertEquals(unnamedCity.id, preferences.getString("church_id", null))
        }
    }

    private fun reset(legacyReading: Boolean = false) {
        val edit = preferences.edit().clear().putBoolean("dark", false)
        if (legacyReading) edit.putString("stage_id", "creed")
        check(edit.commit())
    }

    private fun launch(fake: PlaceDiscovery): StateRestorationTester = StateRestorationTester(compose).also { restore ->
        val container = AppContainer(context, fake, scheduleOverride = OfflineInternetSchedules)
        restore.setContent { AppNavigation(container, Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC)) }
    }

    private fun openHomeDiscovery() {
        homeNode(hasTestTag("open_church")).performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithTag("discovery_query").assertIsDisplayed()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
    }

    private fun search(query: String) {
        discoveryNode("discovery_query").performTextReplacement(query)
        waitForKeyboard(visible = true)
        compose.onNodeWithTag("discovery_query").performImeAction()
        waitForKeyboard(visible = false)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        compose.onNodeWithTag("discovery_query").assertIsNotFocused()
    }

    private fun discoveryNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun result(id: String): SemanticsNodeInteraction {
        val matcher = hasTestTag("discovery_church_$id") or hasTestTag("discovery_saved_$id")
        compose.onNodeWithTag("discovery_content").performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun homeNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun assertSavedChurch(expected: DiscoveredChurch): SavedChurchRecord {
        val saved = ChurchDirectoryStore(preferences).records().single { it.id == expected.id }
        assertEquals(expected.name, saved.name)
        assertEquals(expected.city, saved.city)
        assertEquals(expected.address, saved.address)
        assertEquals("Europe/Moscow", saved.timezone)
        assertEquals(expected.center.latitude, requireNotNull(saved.latitude), 0.0)
        assertEquals(expected.center.longitude, requireNotNull(saved.longitude), 0.0)
        assertEquals(expected.sourceUrl, saved.sourceUrl)
        assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, saved.origin)
        assertTrue(saved.asParish().schedule.isEmpty())
        return saved
    }

    private fun waitForKeyboard(visible: Boolean) {
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

    private class FakeDiscovery(private val church: DiscoveredChurch) : PlaceDiscovery {
        val queries = mutableListOf<String>()
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
            queries += query
            return DiscoveryResult.Success(listOf(church), 0, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON)
        }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("Name entry must not request a city")
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("Name entry must not request nearby places")
    }
}
