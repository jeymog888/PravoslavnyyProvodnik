package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.discovery.DefaultPlaceDiscovery
import java.io.File
import java.security.MessageDigest

/** Explicit live-provider smoke on an owned emulator, not part of the offline regression suite. */
@RunWith(AndroidJUnit4::class)
class LiveChurchDiscoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val cache get() = context.getSharedPreferences("place_discovery_cache", Context.MODE_PRIVATE)
    private lateinit var evidence: File

    @Test fun realNamesCanBeFoundReviewedAndSavedInTwoCities() {
        val arguments = InstrumentationRegistry.getArguments()
        // No Activity, preference write or provider request occurs in an ordinary test run.
        assumeTrue("Live network test requires explicit opt-in", arguments.getString("feoriaLiveDiscovery") == "true")
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) {
            "This destructive clean-state test is restricted to the owned Android emulator"
        }
        val run = requireNotNull(arguments.getString("feoriaLiveDiscoveryRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        evidence = File(requireNotNull(context.getExternalFilesDir("live-discovery")), run)
        check(!evidence.exists() && evidence.mkdirs()) { "Use a new evidence run ID" }
        val apk = File(context.applicationInfo.sourceDir)
        val apkHash = sha256(apk.readBytes())
        write("run.json", JSONObject().put("run", run).put("apkSha256", apkHash)
            .put("fingerprint", Build.FINGERPRINT).put("hardware", Build.HARDWARE)
            .put("startedAtEpochMillis", System.currentTimeMillis())
            .put("mode", "Real MainActivity and production discovery; no injected responses")
            .put("visualVerdict", "NOT_REVIEWED").toString(2))

        check(preferences.edit().clear().commit())
        check(cache.edit().clear().commit())
        assertTrue(preferences.all.isEmpty())
        assertTrue(cache.all.isEmpty())
        assertTrue((context.applicationContext as LiturgyGuideApplication).container.placeDiscovery is DefaultPlaceDiscovery)

        ActivityScenario.launch(MainActivity::class.java).use {
            withCiFailureDiagnostics("LiveChurchDiscoveryTest.realNamesCanBeFoundReviewedAndSavedInTwoCities") {
                compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
                compose.onNodeWithTag("open_church").performClick()
                // With clean preferences, the first Home entry opens the picker directly.
                compose.onNodeWithTag("church_picker").assertIsDisplayed()
                searchAndSave("sretensky", "Сретенский монастырь Москва", "osm:relation:3325777", "Сретенский монастырь", "Москва",
                    returnsHome = true)

                compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
                compose.onNodeWithTag("change_church").performClick()
                compose.onNodeWithTag("church_picker").assertIsDisplayed()
                searchAndSave("petersburg", "Казанский собор Санкт-Петербург", "osm:relation:11763697", "Казанский собор", "Санкт-Петербург")

                val records = ChurchDirectoryStore(preferences).records()
                assertEquals(setOf("osm:relation:3325777", "osm:relation:11763697"), records.map { record -> record.id }.toSet())
                assertEquals("osm:relation:11763697", preferences.getString("church_id", null))
                assertFalse(preferences.contains("stage_id"))
                assertEquals(apkHash, sha256(apk.readBytes()))
                write("completion.json", JSONObject().put("status", "PASS_FUNCTIONAL")
                    .put("finishedAtEpochMillis", System.currentTimeMillis()).put("apkSha256", apkHash)
                    .put("visualVerdict", "NOT_REVIEWED")
                    .put("limits", "Emulator transport only; offline cold launch, physical phone and user mobile network are not tested here")
                    .toString(2))
            }
        }
        // Closing ActivityScenario leaves both installed APK and saved app data in place.
        // The root agent runs this via am instrument, without connected-test uninstall cleanup.
    }

    private fun searchAndSave(label: String, query: String, id: String, name: String, city: String,
        returnsHome: Boolean = false) {
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_search"))
        compose.onNodeWithTag("church_search").performTextReplacement(query)
        closeSoftKeyboardWithCiDiagnostics()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("discover_churches"))
        compose.onNodeWithTag("discover_churches").performClick()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains(query)
        val previousCache = cache.getString("places", null)
        val requestedAt = System.currentTimeMillis()
        compose.onNodeWithTag("discovery_submit").performClick()
        compose.waitUntil(timeoutMillis = 30_000L) {
            cache.getString("places", null) != previousCache ||
                compose.onAllNodesWithTag("discovery_message").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("discovery_message").fetchSemanticsNodes().isNotEmpty()) {
            capture("$label-error")
            fail("Live provider failed; see $label-error.semantics.txt. No retry was attempted")
        }

        val raw = requireNotNull(cache.getString("places", null)) { "Live success must have a cache envelope" }
        assertNotEquals("A different explicit query must not reuse the previous response", previousCache, raw)
        val envelope = JSONObject(raw)
        val fetchedAt = envelope.getLong("fetchedAt")
        val observedAt = System.currentTimeMillis()
        assertTrue("Fresh Android response, not a preseeded cache", fetchedAt in requestedAt..observedAt)
        val body = envelope.getString("body")
        write("$label-cache.json", raw)
        write("$label-response.json", body)
        write("$label-request.json", JSONObject().put("query", query).put("expectedOsmId", id)
            .put("requestedAtEpochMillis", requestedAt).put("observedAtEpochMillis", observedAt)
            .put("fetchedAtEpochMillis", fetchedAt).put("bodySha256", sha256(body.toByteArray(Charsets.UTF_8)))
            .put("provider", "Production Photon Android transport")
            .put("responseKind", "FRESH_NETWORK: empty cache initially, then distinct query")
            .toString(2))

        val features = JSONObject(body).getJSONArray("features")
        val osmId = id.substringAfterLast(':').toLong()
        val feature = (0 until features.length()).map(features::getJSONObject).single { item ->
            val properties = item.getJSONObject("properties")
            properties.optString("osm_type") == "R" && properties.optLong("osm_id") == osmId
        }
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(name, feature.getJSONObject("properties").getString("name"))
        assertEquals(city, feature.getJSONObject("properties").getString("city"))

        val tag = "discovery_church_$id"
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertTextContains(name)
        compose.onNodeWithTag("discovery_cache_notice").assertDoesNotExist()
        capture("$label-results")
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("import_church_name").assertTextEquals(name)
        compose.onNodeWithTag("church_timezone_picker").performScrollTo().performClick()
        compose.onNodeWithTag("zone_Europe/Moscow").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("church_timezone_picker").assertTextContains("Москва")
        compose.onNodeWithTag("confirm_import_timezone").performScrollTo().assertIsOn()
        capture("$label-review")
        compose.onNodeWithTag("save_new_church").performScrollTo().performClick()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        if (returnsHome) {
            compose.onNodeWithTag("main_content").assertIsDisplayed()
            compose.onNodeWithTag("church_content").assertDoesNotExist()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
            compose.onNodeWithTag("open_church").assertTextContains(name)
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("find_stage"))
            compose.onAllNodesWithTag("find_stage").assertCountEquals(1)
            compose.onNodeWithTag("find_stage").assertIsDisplayed()
            capture("$label-home")
            // The first Home selection returns to the service entry. Open the directory
            // intentionally before checking the saved parish or starting another search.
            compose.onNodeWithTag("nav_church").performClick()
        }
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.onNodeWithText(name).assertIsDisplayed()
        val record = ChurchDirectoryStore(preferences).records().single { it.id == id }
        assertEquals(name, record.name)
        assertEquals(city, record.city)
        assertEquals("Europe/Moscow", record.timezone)
        assertEquals(coordinates.getDouble(1), requireNotNull(record.latitude), 0.0000001)
        assertEquals(coordinates.getDouble(0), requireNotNull(record.longitude), 0.0000001)
        assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, record.origin)
        assertEquals("https://www.openstreetmap.org/relation/$osmId", record.sourceUrl)
        assertTrue(record.asParish().schedule.isEmpty())
        assertEquals(id, preferences.getString("church_id", null))
        write("$label-saved-records.json", requireNotNull(preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null)))
        capture("$label-saved")
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        // Compose semantics may be committed before the native window presents its frame.
        // Bound the wait for Android accessibility/window quiet before taking a whole-screen PNG.
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500L, 5_000L)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        write("$label.semantics.txt", roots.fetchSemanticsNodes().indices.joinToString("\n\n") {
            roots[it].printToString(maxDepth = 100)
        })
        val destination = File(evidence, "$label.png")
        check(destination.createNewFile()) { "Evidence must not overwrite an earlier capture" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun write(name: String, value: String) {
        val destination = File(evidence, name)
        check(destination.createNewFile()) { "Evidence must not overwrite an earlier observation" }
        destination.writeText(value)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
