package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.discovery.DefaultPlaceDiscovery
import ru.liturgyguide.service.schedule.InternetScheduleRepository
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.SchedulePublication
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Explicit live-provider smoke on an owned emulator, not part of the offline regression suite. */
@RunWith(AndroidJUnit4::class)
class LiveChurchDiscoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val cache get() = context.getSharedPreferences("place_discovery_cache", Context.MODE_PRIVATE)
    private val scheduleCache get() = context.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
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
            .put("mode", "Real MainActivity and production discovery; single-tap selection; no injected responses")
            .put("visualVerdict", "NOT_REVIEWED").toString(2))

        check(preferences.edit().clear().commit())
        check(cache.edit().clear().commit())
        assertTrue(preferences.all.isEmpty())
        assertTrue(cache.all.isEmpty())
        assertTrue((context.applicationContext as LiturgyGuideApplication).container.placeDiscovery is DefaultPlaceDiscovery)
        assertTrue((context.applicationContext as LiturgyGuideApplication).container.internetSchedules is InternetScheduleRepository)

        ActivityScenario.launch(MainActivity::class.java).use {
            withCiFailureDiagnostics("LiveChurchDiscoveryTest.realNamesCanBeFoundReviewedAndSavedInTwoCities") {
                compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("open_church"))
                compose.onNodeWithTag("open_church").performClick()
                // With clean preferences, Home opens the unified search directly.
                compose.onNodeWithTag("discovery_content").assertIsDisplayed()
                compose.onNodeWithTag("church_picker").assertDoesNotExist()
                searchAndSave("sretensky", "Сретенский монастырь Москва", "osm:relation:3325777", "Сретенский монастырь", "Москва",
                    returnsHome = true)

                compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
                compose.onNodeWithTag("change_church").performClick()
                compose.onNodeWithTag("discovery_content").assertIsDisplayed()
                compose.onNodeWithTag("church_picker").assertDoesNotExist()
                searchAndSave("petersburg", "Казанский собор Санкт-Петербург", "osm:relation:11763697", "Казанский собор", "Санкт-Петербург")

                compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
                compose.onNodeWithTag("change_church").performClick()
                compose.onNodeWithTag("discovery_content").assertIsDisplayed()
                compose.onNodeWithTag("church_picker").assertDoesNotExist()
                searchLandmarkAndSave()

                val records = ChurchDirectoryStore(preferences).records()
                assertEquals(setOf("osm:relation:3325777", "osm:relation:11763697", "osm:relation:19595736"), records.map { record -> record.id }.toSet())
                assertEquals("osm:relation:19595736", preferences.getString("church_id", null))
                assertFalse(records.any { record -> record.id == "osm:node:5053943230" })
                assertFalse(preferences.contains("stage_id"))
                assertEquals(apkHash, sha256(apk.readBytes()))
                write("completion.json", JSONObject().put("status", "PASS_FUNCTIONAL")
                    .put("finishedAtEpochMillis", System.currentTimeMillis()).put("apkSha256", apkHash)
                    .put("verifiedSearches", 3)
                    .put("verifiedAutomaticSchedule", "osm:relation:19595736")
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
        val previousSelectedId = preferences.getString("church_id", null)
        val previousRecords = preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null)
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_query"))
        compose.onNodeWithTag("discovery_query").assertIsDisplayed().performTextReplacement(query)
        closeSoftKeyboardWithCiDiagnostics()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains(query)
        assertEquals(previousSelectedId, preferences.getString("church_id", null))
        assertEquals(previousRecords, preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null))
        val previousCache = cache.getString("places", null)
        val requestedAt = System.currentTimeMillis()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_submit"))
        val requestedElapsed = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("discovery_submit").performClick()
        compose.waitUntil(timeoutMillis = 30_000L) {
            cache.getString("places", null) != previousCache ||
                compose.onAllNodesWithTag("discovery_message").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val searchElapsed = SystemClock.elapsedRealtime() - requestedElapsed
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
            .put("searchElapsedMillis", searchElapsed)
            .put("fetchedAtEpochMillis", fetchedAt).put("bodySha256", sha256(body.toByteArray(Charsets.UTF_8)))
            .put("provider", "Production Photon Android transport")
            .put("responseKind", "FRESH_NETWORK: empty cache initially, then distinct query")
            .put("selection", "One explicit tap on the verified result; timezone derived from coordinates")
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
        assertEquals("Searching must not select a parish", previousSelectedId, preferences.getString("church_id", null))
        assertEquals("Searching must not save a map result", previousRecords, preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null))
        capture("$label-results")
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("add_church_form").assertDoesNotExist()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        if (returnsHome) {
            compose.onNodeWithTag("main_content").assertIsDisplayed()
            compose.onNodeWithTag("church_content").assertDoesNotExist()
            compose.onNodeWithTag("main_content").performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertIsDisplayed()
            val readingEntry = hasTestTag("start_guide") or hasTestTag("open_service_book") or hasTestTag("today_choose_unknown_service")
            compose.onNodeWithTag("main_content").performScrollToNode(readingEntry)
            compose.onNode(readingEntry).assertIsDisplayed().assertIsEnabled()
            capture("$label-home")
            // First Home selection returns to services. Open the parish details intentionally
            // before checking the persisted record or starting the second city's search.
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

    private fun searchLandmarkAndSave() {
        val label = "sukharevskaya"
        val query = "метро сухаревская троицы"
        val id = "osm:relation:19595736"
        val hospitalId = "osm:node:5053943230"
        val nameFragment = "Троицы Живоначальной в Листах"
        val previousSelectedId = preferences.getString("church_id", null)
        val previousRecords = preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null)
        val previousPlaces = cache.getString("places", null)
        val previousLandmarks = cache.getString("landmarks", null)
        val previousNearby = cache.getString("churches", null)
        // Only this parish's old publication is removed. No site URL or schedule response is seeded.
        check(scheduleCache.edit().remove(id).commit())
        assertFalse(scheduleCache.contains(id))
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_query"))
        compose.onNodeWithTag("discovery_query").performTextReplacement(query)
        closeSoftKeyboardWithCiDiagnostics()
        compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
        compose.onNodeWithTag("discovery_query").assertTextContains(query)
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_submit"))
        val requestedAt = System.currentTimeMillis()
        val requestedElapsed = SystemClock.elapsedRealtime()
        var resultsElapsed: Long? = null
        var targetVisibleElapsed: Long? = null
        var stationChoiceTaps = 0
        var passed = false
        val scheduleWrite = AtomicReference<Pair<Long, Long>?>(null)
        val scheduleListener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key == id && store.contains(id)) {
                scheduleWrite.compareAndSet(null, System.currentTimeMillis() to SystemClock.elapsedRealtime())
            }
        }
        scheduleCache.registerOnSharedPreferenceChangeListener(scheduleListener)
        try {
            compose.onNodeWithTag("discovery_submit").performClick()
            waitForLandmarkSearch(previousSelectedId, previousRecords, previousPlaces)
            if (compose.onAllNodesWithTag("discovery_landmark_heading").fetchSemanticsNodes().isNotEmpty()) {
                // A public provider may return several stations. If so, review the observed Moscow station explicitly.
                val station = "discovery_landmark_osm:node:277499849"
                compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(station))
                compose.onNodeWithTag(station).assertTextContains("Сухаревская").assertTextContains("Москва").performClick()
                stationChoiceTaps++
                compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_submit"))
                waitForLandmarkSearch(previousSelectedId, previousRecords, previousPlaces)
            }
            resultsElapsed = SystemClock.elapsedRealtime() - requestedElapsed
            compose.onNodeWithTag("discovery_cache_notice").assertDoesNotExist()
            val targetTag = "discovery_church_$id"
            compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag(targetTag))
            compose.onNodeWithTag(targetTag).assertIsDisplayed().assertTextContains(nameFragment, substring = true)
                .assertTextContains("Сретенка", substring = true)
            targetVisibleElapsed = SystemClock.elapsedRealtime() - requestedElapsed
            assertEquals("Finding the church must not save or select it", previousSelectedId, preferences.getString("church_id", null))
            assertEquals(previousRecords, preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null))
            assertFalse(ChurchDirectoryStore(preferences).records().any { it.id == hospitalId })

            val observedAt = System.currentTimeMillis()
            val current = linkedMapOf("places" to previousPlaces, "landmarks" to previousLandmarks, "churches" to previousNearby)
                .mapValues { (slot, before) ->
                    val raw = requireNotNull(cache.getString(slot, null)) { "$slot must contain the actual provider response" }
                    assertNotEquals("The landmark scenario must use a new request for $slot", before, raw)
                    val envelope = JSONObject(raw)
                    assertTrue("No preseeded $slot response", envelope.getLong("fetchedAt") in requestedAt..observedAt)
                    write("$label-$slot-cache.json", raw)
                    write("$label-$slot-response.json", envelope.getString("body"))
                    JSONObject(envelope.getString("body"))
                }
            val nearby = requireNotNull(current["churches"])
            val feature: JSONObject
            val name: String
            val latitude: Double
            val longitude: Double
            val provider: String
            if (nearby.has("features")) {
                provider = "Photon named nearby search"
                val features = nearby.getJSONArray("features")
                feature = (0 until features.length()).map(features::getJSONObject).single {
                    val properties = it.getJSONObject("properties")
                    properties.optString("osm_type") == "R" && properties.optLong("osm_id") == 19_595_736L
                }
                name = feature.getJSONObject("properties").getString("name")
                val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
                latitude = coordinates.getDouble(1)
                longitude = coordinates.getDouble(0)
            } else {
                provider = "Overpass nearby Orthodox places"
                val elements = nearby.getJSONArray("elements")
                feature = (0 until elements.length()).map(elements::getJSONObject).single {
                    it.optString("type") == "relation" && it.optLong("id") == 19_595_736L
                }
                val tags = feature.getJSONObject("tags")
                name = tags.optString("name:ru").ifBlank { tags.getString("name") }
                val center = feature.getJSONObject("center")
                latitude = center.getDouble("lat")
                longitude = center.getDouble("lon")
            }
            assertTrue(name.contains(nameFragment))
            write("$label-request.json", JSONObject().put("query", query).put("expectedOsmId", id)
                .put("expectedAddressFragment", "Сретенка").put("excludedAutomaticSelection", hospitalId)
                .put("provider", provider).put("requestedAtEpochMillis", requestedAt).put("observedAtEpochMillis", observedAt)
                .put("stationChoiceTaps", stationChoiceTaps).put("selection", "One explicit tap after checking name and Sretenka address")
                .toString(2))
            capture("$label-results")
            assertFalse("Selection must start without this parish's internet cache", scheduleCache.contains(id))
            val selectedAt = System.currentTimeMillis()
            val selectedElapsed = SystemClock.elapsedRealtime()
            compose.onNodeWithTag(targetTag).performClick()
            compose.onNodeWithTag("discovery_content").assertDoesNotExist()
            compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
            compose.onNodeWithTag("add_church_form").assertDoesNotExist()
            compose.onNodeWithTag("church_content").assertIsDisplayed()
            compose.onNodeWithTag("church_content").performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertIsDisplayed()
            val record = ChurchDirectoryStore(preferences).records().single { it.id == id }
            assertEquals(name, record.name)
            assertEquals("Москва", record.city)
            assertTrue(record.address.contains("Сретенка"))
            assertEquals("Europe/Moscow", record.timezone)
            assertEquals(latitude, requireNotNull(record.latitude), .0000001)
            assertEquals(longitude, requireNotNull(record.longitude), .0000001)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, record.origin)
            assertEquals("https://www.openstreetmap.org/relation/19595736", record.sourceUrl)
            assertEquals("The official site must be discovered from the selected identity", "", record.website)
            assertTrue(record.asParish().schedule.isEmpty())
            assertEquals(id, preferences.getString("church_id", null))
            assertFalse(ChurchDirectoryStore(preferences).records().any { it.id == hospitalId })
            write("$label-saved-records.json", requireNotNull(preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null)))
            capture("$label-saved")
            verifyAutomaticListySchedule(label, record.asParish(), selectedAt, selectedElapsed, scheduleWrite)
            passed = true
        } finally {
            scheduleCache.unregisterOnSharedPreferenceChangeListener(scheduleListener)
            write("$label-timing.json", JSONObject().put("query", query).put("status", if (passed) "PASS_FUNCTIONAL" else "FAIL")
                .put("searchElapsedMillis", resultsElapsed ?: JSONObject.NULL)
                .put("targetVisibleElapsedMillis", targetVisibleElapsed ?: JSONObject.NULL)
                .put("scenarioElapsedMillis", SystemClock.elapsedRealtime() - requestedElapsed)
                .put("stationChoiceTaps", stationChoiceTaps)
                .put("timingScope", "Real emulator UI and public providers; no fixture latency or physical phone claim")
                .toString(2))
        }
    }

    private fun verifyAutomaticListySchedule(label: String, parish: Parish, selectedAt: Long, selectedElapsed: Long,
        scheduleWrite: AtomicReference<Pair<Long, Long>?>) {
        val repository = (context.applicationContext as LiturgyGuideApplication).container.internetSchedules
        val report = JSONObject().put("parishId", parish.id).put("selectedAtEpochMillis", selectedAt)
            .put("siteWasProvided", false).put("cacheWasEmptyAtSelection", true)
            .put("manualRefreshes", 0).put("sourceOverrides", 0)
            .put("mode", "MainActivity automatic refresh through production AppContainer")
        var phase = "awaiting_automatic_publication"
        var passed = false
        try {
            // Production has a 180 s overall import deadline. Do not replace it with a test request.
            compose.waitUntil(timeoutMillis = 190_000L) { scheduleCache.contains(parish.id) && scheduleWrite.get() != null }
            val observed = requireNotNull(scheduleWrite.get())
            val observedAt = observed.first
            report.put("publicationObservedAtEpochMillis", observedAt)
                .put("selectionToPublicationMillis", observed.second - selectedElapsed)
                .put("publicationTimingScope", "First production cache write notification after the selection tap; screenshots excluded")
            assertTrue("The publication was written after the explicit selection", observed.second >= selectedElapsed)
            val state = repository.cached(parish)
            phase = "validating_publication"
            report.put("repositoryStatus", state.status.name).put("sourceUrl", state.sourceUrl)
                .put("siteEvidenceUrl", state.siteEvidenceUrl).put("checkedAt", state.checkedAt)
            assertEquals("Automatic site discovery must finish successfully", InternetScheduleStatus.READY, state.status)
            assertEquals(parish.id, state.parishId)
            assertEquals("", state.configuredWebsite)
            assertEquals("", state.customSource)
            assertEquals("https://www.wikidata.org/wiki/Q210032", state.siteEvidenceUrl)
            // MainActivity snapshots its clock every 5 s. Empty cache + a new write proves network
            // freshness; the small clock allowance must not mistake that snapshot for stale data.
            val checkedAt = Instant.parse(state.checkedAt).toEpochMilli()
            assertTrue("The state belongs to this selection, not an earlier publication",
                checkedAt in (selectedAt - 10_000L)..observedAt)
            val publication = requireNotNull(state.publication) { "READY must include the downloaded schedule" }
            assertEquals(parish.id, publication.parishId)
            assertEquals(parish.timezone, publication.timezone)
            assertEquals(state.checkedAt, publication.fetchedAt)
            assertEquals(state.sourceUrl, publication.sourceUrl)
            val source = URI(publication.sourceUrl)
            assertEquals("https", source.scheme)
            assertEquals("hram-troicy.ru", source.host.lowercase(Locale.ROOT).removePrefix("www."))
            assertEquals("/raspisanie", source.path.trimEnd('/'))
            val checkedDays = JSONArray()
            report.put("verifiedDays", checkedDays).put("publishedEventCount", publication.events.size)
                .put("yearInferred", publication.yearInferred)
            for (isoDate in listOf("2026-11-11", "2026-11-12")) {
                val events = publication.forDate(LocalDate.parse(isoDate)).sortedBy { it.time }
                checkedDays.put(JSONObject().put("date", isoDate).put("events", JSONArray(events.map {
                    JSONObject().put("time", it.time).put("title", it.title).put("kind", it.kind)
                })))
                assertEquals("Both published starts must survive parsing on $isoDate", listOf("08:00", "17:00"), events.map { it.time })
                assertTrue(events[0].title.contains("Часы", ignoreCase = true) && events[0].title.contains("Литургия", ignoreCase = true))
                assertTrue(events[1].title.contains("Вечерня", ignoreCase = true) && events[1].title.contains("Утреня", ignoreCase = true))
            }
            phase = "validating_visible_days"
            val visibleDays = JSONArray()
            report.put("visibleDays", visibleDays)
            verifyVisibleScheduleDays(label, publication, visibleDays)
            assertEquals("Schedule loading must not replace the selected church", parish.id, preferences.getString("church_id", null))
            assertEquals("Site discovery must not rewrite the user's map record", "",
                ChurchDirectoryStore(preferences).records().single { it.id == parish.id }.website)
            phase = "complete"
            passed = true
        } finally {
            scheduleCache.getString(parish.id, null)?.let { write("$label-schedule-cache.json", it) }
            report.put("status", if (passed) "PASS_FUNCTIONAL" else "FAIL").put("phase", phase)
                .put("selectionToAcceptanceMillis", SystemClock.elapsedRealtime() - selectedElapsed)
                .put("visualVerdict", "NOT_REVIEWED")
            write("$label-schedule-result.json", report.toString(2))
        }
    }

    private fun verifyVisibleScheduleDays(label: String, publication: SchedulePublication, results: JSONArray) {
        val zone = ZoneId.of(publication.timezone)
        val deviceDate = LocalDate.now(zone)
        for ((offset, tag) in listOf(0L to "schedule_today", 1L to "schedule_tomorrow")) {
            val date = deviceDate.plusDays(offset)
            val events = publication.forDate(date)
            assertTrue("The real publication must cover the displayed date $date", events.isNotEmpty())
            compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick().assertIsSelected()
            compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_date"))
            // The repository writes its cache just before MainActivity receives the result.
            // Wait for that existing UI binding, without refreshing or supplying state ourselves.
            compose.waitUntil(timeoutMillis = 10_000L) {
                compose.onAllNodesWithTag("church_service_time").fetchSemanticsNodes().isNotEmpty()
            }
            val title = date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru")))
            compose.onNodeWithTag("schedule_date").assertIsDisplayed().assertTextEquals(title)
            // Check every real event in this card, including the non-featured service. The
            // featured item changes with real wall time, so do not assume it is always 08:00.
            for (event in events) {
                val withinCard = hasAnyAncestor(hasTestTag("church_schedule_card"))
                compose.onNode(hasText(event.time) and withinCard).performScrollTo().assertIsDisplayed()
                compose.onNode(hasText(event.title) and withinCard).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("church_service_time").performScrollTo().assertIsDisplayed()
            capture("$label-schedule-${if (offset == 0L) "today" else "tomorrow"}")
            results.put(JSONObject().put("date", date.toString()).put("dateTitle", title).put("visibleEventCount", events.size))
            assertEquals("A midnight rollover needs a fresh live run, not injected clock data", deviceDate, LocalDate.now(zone))
        }
    }

    private fun waitForLandmarkSearch(previousSelectedId: String?, previousRecords: String?, previousPlaces: String?) {
        // The places cache changes after the FIRST request. Wait for the whole station/nearby chain instead.
        compose.waitUntil(timeoutMillis = 45_000L) {
            assertEquals("Search must not automatically choose the hospital or any other church", previousSelectedId, preferences.getString("church_id", null))
            assertEquals("Search must not save unreviewed map results", previousRecords, preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null))
            val finished = compose.onAllNodes(hasTestTag("discovery_submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            cache.getString("places", null) != previousPlaces && finished ||
                compose.onAllNodesWithTag("discovery_message").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("discovery_message").fetchSemanticsNodes().isNotEmpty()) {
            capture("sukharevskaya-error")
            fail("Live landmark search failed; see sukharevskaya-error.semantics.txt. No retry was attempted")
        }
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
