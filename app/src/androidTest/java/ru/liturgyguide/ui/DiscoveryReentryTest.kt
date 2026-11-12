package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.*

/** The navigation saves list position, while a newly opened search has no old result set. */
@RunWith(AndroidJUnit4::class)
class DiscoveryReentryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningToSearchShowsItsQueryWithoutAnExtraSwipe() = checkReentry(recreate = false)
    @Test fun recreatingSearchShowsItsQueryWithoutStartingANetworkSearch() = checkReentry(recreate = true)

    private fun checkReentry(recreate: Boolean) {
        val visible = mutableStateOf(true)
        var requests = 0
        val discovery = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
                requests++; error("Reopening search must not send a query")
            }
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> {
                requests++; error("Reopening search must not search a city")
            }
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
                requests++; error("Reopening search must not request nearby places")
            }
        }
        val saved = (1..6).map {
            SavedChurchRecord.create("Храм Троицы $it", "Воронеж", "Улица, $it", "Europe/Moscow").asParish()
        }
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val holder = rememberSaveableStateHolder()
            GuideTheme(false) {
                if (visible.value) holder.SaveableStateProvider("DISCOVERY") {
                    ChurchDiscoveryScreen(discovery, onBack = { visible.value = false },
                        onSave = { _, _ -> error("No church selection is part of this journey") },
                        onOpenUrl = {}, query = "Троицы", onQueryChange = {},
                        mode = ChurchSearchMode.NAME, onModeChange = {}, savedChurches = saved,
                        onSelectSaved = { _, _ -> error("No saved church should be selected") })
                } else Text("Храм")
            }
        }
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().assertTextContains("Троицы")
        compose.onNodeWithTag("discovery_content").performScrollToIndex(3)
        compose.onNodeWithTag("discovery_query").assertIsNotDisplayed()
        if (recreate) restore.emulateSavedInstanceStateRestore()
        else {
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithTag("discovery_content").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
        }
        compose.waitForIdle()
        // No scroll action is allowed here: the actual navigation must expose the controls.
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().assertTextContains("Троицы")
        compose.onNodeWithTag("discovery_mode_name").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("discovery_nearby").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
    }
}
