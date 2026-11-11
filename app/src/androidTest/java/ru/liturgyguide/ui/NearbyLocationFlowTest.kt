package ru.liturgyguide.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.service.*
import ru.liturgyguide.service.discovery.*

/** Real Compose/lifecycle/permission-contract path; all positions below are declared synthetic points. */
@RunWith(AndroidJUnit4::class)
class NearbyLocationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val gateway = Gateway()
    private val source = ForegroundLocationRequest(gateway)
    private val mounted = mutableStateOf(true)
    private val searches = mutableListOf<Pair<GeoPoint, Int>>()
    private var permissionAnswer = emptyMap<String, Boolean>()
    private var permissionRequests = 0
    private var permissionInput = emptyList<String>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.RequestMultiplePermissions)
            permissionRequests++
            permissionInput = (input as Array<*>).map { it as String }
            Handler(Looper.getMainLooper()).post {
                gateway.coarse = permissionAnswer[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                gateway.fine = permissionAnswer[Manifest.permission.ACCESS_FINE_LOCATION] == true
                dispatchResult(requestCode, Activity.RESULT_OK, Intent()
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, permissionInput.toTypedArray())
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        permissionInput.map { if (permissionAnswer[it] == true) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }.toIntArray()))
            }
        }
    }
    private val discovery = object : PlaceDiscovery {
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("No city search expected")
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> = error("No name search expected")
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
            searches += center to radiusMeters
            return DiscoveryResult.Success(listOf(DiscoveredChurch("osm:way:42", "Тестовый храм", center,
                "Тестовый адрес", "Москва", null, false, "https://www.openstreetmap.org/way/42")),
                1_794_096_000_000L, DiscoveryCacheStatus.FRESH_NETWORK, DiscoveryProvider.PHOTON, center.searchAreaCenter(), radiusMeters)
        }
    }

    @After fun clean() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { mounted.value = false; source.stop() }
        compose.waitForIdle()
    }

    @Test fun approximatePermissionKeepsWaitingAfterBadFirstFixThenFindsNearbyWithoutSettings() {
        permissionAnswer = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true)
        launch()
        node("discovery_nearby").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), permissionInput.toSet())
            assertEquals(1, permissionRequests)
            assertEquals(listOf("gps"), gateway.listeners.map { it.provider })
            gateway.emit(gateway.fix(6_000f))
            assertTrue(searches.isEmpty())
        }
        node("discovery_loading").assertTextEquals("Определяем местоположение")
        compose.onNodeWithTag("discovery_location_settings").assertDoesNotExist()
        compose.runOnIdle { gateway.emit(gateway.fix(2_000f)) }
        node("discovery_church_osm:way:42").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(GeoPoint(55.77, 37.63) to 3_000), searches)
            gateway.assertStopped()
        }
    }

    @Test fun preciseGrantAndExistingPermissionBothReachTheSameSearchPath() {
        permissionAnswer = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true, Manifest.permission.ACCESS_FINE_LOCATION to true)
        launch()
        node("discovery_nearby").performClick()
        compose.runOnIdle { gateway.emit(gateway.fix(15f)) }
        node("discovery_church_osm:way:42").assertIsDisplayed()
        node("discovery_nearby").performClick()
        compose.runOnIdle { gateway.emit(gateway.fix(15f)) }
        node("discovery_church_osm:way:42").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, permissionRequests); assertEquals(2, searches.size); gateway.assertStopped() }
    }

    @Test fun deniedPermissionHasLocalRetryAndRetryCanAcceptApproximatePermission() {
        launch()
        node("discovery_nearby").performClick()
        node("discovery_message").assertTextContains("без геолокации", substring = true)
        compose.onNodeWithTag("discovery_location_settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(gateway.listeners.isEmpty()); assertTrue(searches.isEmpty())
            permissionAnswer = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true) }
        node("discovery_retry").performClick()
        compose.runOnIdle { gateway.emit(gateway.fix(1_000f)) }
        node("discovery_church_osm:way:42").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, permissionRequests); gateway.assertStopped() }
    }

    @Test fun disabledLocationOffersSettingsButProviderFailureDoesNotPretendLocationIsOff() {
        gateway.coarse = true; gateway.enabled = false
        launch()
        node("discovery_nearby").performClick()
        node("discovery_location_settings").assertIsDisplayed()
        node("discovery_retry").assertIsDisplayed()
        compose.runOnIdle { assertTrue(gateway.listeners.isEmpty()); gateway.enabled = true; gateway.available = emptyList() }
        node("discovery_retry").performClick()
        node("discovery_message").assertTextContains("Не удалось обратиться", substring = true)
        compose.onNodeWithTag("discovery_location_settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(searches.isEmpty()) }
    }

    @Test fun timeoutCanRetryAndOldCallbacksCannotSearchAfterCancelOrLeavingTheScreen() {
        gateway.coarse = true
        launch()
        node("discovery_nearby").performClick()
        compose.runOnIdle { requireNotNull(gateway.timeout).action() }
        node("discovery_message").assertTextContains("Не удалось определить", substring = true)
        node("discovery_retry").performClick()
        node("discovery_cancel").performClick()
        compose.runOnIdle { gateway.emit(gateway.fix()); assertTrue(searches.isEmpty()); gateway.assertStopped() }
        node("discovery_nearby").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { gateway.emit(gateway.fix()); assertTrue(searches.isEmpty()); gateway.assertStopped() }
        node("discovery_nearby").performClick()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { gateway.emit(gateway.fix()); assertTrue(searches.isEmpty()); gateway.assertStopped() }
    }

    private fun launch() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                GuideTheme(false) {
                    var query by remember { mutableStateOf("") }
                    var mode by remember { mutableStateOf(ChurchSearchMode.NAME) }
                    if (mounted.value) ChurchDiscoveryScreen(discovery, onBack = {}, onSave = { _, _ -> ChurchSelectionResult.FAILED },
                        onOpenUrl = {}, query = query, onQueryChange = { query = it }, mode = mode, onModeChange = { mode = it },
                        locationSource = source)
                }
            }
        }
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private class Gateway : ForegroundLocationGateway {
        var coarse = false; var fine = false; var enabled = true
        var available = listOf("gps")
        class Listener(val provider: String, val callback: (ForegroundLocationFix) -> Unit) { var cancelled = false }
        class Timer(val action: () -> Unit) { var cancelled = false }
        val listeners = mutableListOf<Listener>()
        var timeout: Timer? = null
        override fun hasPermission() = coarse || fine
        override fun isLocationEnabled() = enabled
        override fun providers() = available
        override fun isProviderEnabled(provider: String) = enabled
        override fun lastKnown(provider: String): ForegroundLocationFix? = null
        override fun subscribe(provider: String, onFix: (ForegroundLocationFix) -> Unit): LocationCancellation {
            val listener = Listener(provider, onFix).also { listeners += it }
            return LocationCancellation { listener.cancelled = true }
        }
        override fun elapsedRealtimeNanos() = 300_000_000_000L
        override fun after(delayMillis: Long, action: () -> Unit): LocationCancellation {
            assertEquals(20_000L, delayMillis)
            val timer = Timer(action).also { timeout = it }
            return LocationCancellation { timer.cancelled = true }
        }
        fun fix(accuracy: Float = 20f) = ForegroundLocationFix(55.77, 37.63, accuracy, elapsedRealtimeNanos())
        fun emit(fix: ForegroundLocationFix) = listeners.last().callback(fix)
        fun assertStopped() { assertTrue(listeners.all { it.cancelled }); assertTrue(timeout == null || timeout!!.cancelled) }
    }
}
