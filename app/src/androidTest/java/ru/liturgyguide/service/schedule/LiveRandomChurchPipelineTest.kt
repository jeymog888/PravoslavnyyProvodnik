package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.TodayServiceSelection
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.discovery.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reproducible sample selected before website inspection. Network failures remain visible in the report. */
class LiveRandomChurchPipelineTest {
    @Test fun nearbySearchUsesTheRealBoundedNetworkAndReopensItsActualProviderCache() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRandomChurches") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("live-nearby-discovery-08", 0)
        preferences.edit().clear().commit()
        val cache = object : DiscoveryCacheStore {
            override fun read(slot: String) = preferences.getString(slot, null)
            override fun write(slot: String, value: String) { check(preferences.edit().putString(slot, value).commit()) }
        }
        // A declared map point in Kazan, not a claim that emulator GPS or permission was tested here.
        val center = GeoPoint(55.796127, 49.106414)
        val started = System.nanoTime()
        val found = DefaultPlaceDiscovery(cache = cache).findChurches(center, 3_000)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        context.filesDir.resolve("live-nearby-discovery-08.json").writeText(Gson().toJson(mapOf(
            "declaredCenter" to center, "result" to found, "elapsedMillis" to elapsed)))
        assertTrue("Live nearby result: $found", found is DiscoveryResult.Success)
        val result = found as DiscoveryResult.Success
        assertTrue(result.items.isNotEmpty())
        assertEquals(DiscoveryCacheStatus.FRESH_NETWORK, result.cacheStatus)
        assertEquals(GeoPoint(55.80, 49.11), result.queryCenter)
        assertTrue(result.items.all { center.distanceMeters(it.center) <= 3_000 })
        assertTrue("Nearby has a bounded provider chain: $elapsed ms", elapsed < 25_000)
        val reopened = DefaultPlaceDiscovery(transport = DiscoveryTransport { error("Offline: no network call allowed") }, cache = cache)
            .findChurches(center, 3_000) as DiscoveryResult.Success
        assertEquals(DiscoveryCacheStatus.FRESH_CACHE, reopened.cacheStatus)
        assertEquals(result.provider, reopened.provider)
        assertEquals(result.items, reopened.items)
    }

    @Test fun fiveUncuratedChurchesUseTheRealAndroidPipeline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRandomChurches") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val sample = JsonParser.parseString(testContext.assets.open("discovery/random-churches-2026-11-11.json")
            .bufferedReader().use { it.readText() }).asJsonArray
        val container = AppContainer(context)
        val preferences = context.getSharedPreferences("live-random-church-selection", 0)
        val directory = ChurchDirectoryStore(preferences)
        val report = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<String>()
        val output = context.filesDir.resolve("live-random-churches-08.json")
        for (entry in sample) {
            val row = entry.asJsonObject
            val expected = Gson().fromJson(row.get("selected"), DiscoveredChurch::class.java)
            val query = row.get("query").asString
            val start = System.nanoTime()
            val discovery = container.placeDiscovery.searchPlaces(query)
            val found = (discovery as? DiscoveryResult.Success)?.items?.firstOrNull { it.id == expected.id }
            if (found == null) {
                failures += "${expected.city}: selected map object was not returned by $query"
                report += mapOf("selected" to expected, "query" to query, "discovery" to discovery, "outcome" to "DISCOVERY_FAILED")
                output.writeText(Gson().toJson(report))
                continue
            }
            val zone = requireNotNull(ChurchTimeZone.at(found.center))
            val saved = SavedChurchRecord(found.id, found.name, found.city, found.address, zone,
                found.center.latitude, found.center.longitude, origin = SavedChurchOrigin.OPEN_STREET_MAP,
                website = found.website.orEmpty(), sourceUrl = found.sourceUrl)
            assertTrue(directory.save(saved))
            assertTrue(ChurchDirectoryStore(preferences).records().any { it == saved })
            val parish = saved.asParish()
            val now = Instant.now()
            val schedule = container.internetSchedules.refresh(parish, now, force = true)
            val date = now.atZone(ZoneId.of(zone)).toLocalDate()
            val displayedParish = parish.withInternetSchedule(schedule, now)
            val event = TodayServiceSelection.pick(displayedParish, date, now, lookAhead = true)?.service
            val key = if (event == null) ServiceBookSelection.forCivilDate(date, WorshipKind.LITURGY, parish) else ServiceBookSelection.forCivilDate(
                LocalDate.parse(event.date), ServiceBookSelection.kindFor(event.title) ?: WorshipKind.LITURGY, parish,
                eveningMatins = event.time.substringBefore(':').toIntOrNull()?.let { it >= 12 } == true)
            val book = container.serviceBooks.load(key)
            val ready = book as? BookLoadResult.Ready
            if (ready == null || !ready.saved) failures += "${found.city}: selected book was not saved"
            if (ready != null) {
                assertEquals(key, ready.book.key)
                assertEquals(ready.book, AppContainer(context).serviceBooks.cachedVersion(key, ready.book.contentHash))
                assertTrue(ready.book.blocks.isNotEmpty())
            }
            assertEquals(schedule.publication, AppContainer(context).internetSchedules.cached(parish).publication)
            report += mapOf("selected" to found, "query" to query, "discoveryCache" to (discovery as DiscoveryResult.Success).cacheStatus,
                "schedule" to schedule, "bookKey" to key, "bookFromPublishedEvent" to (event != null),
                "bookSaved" to ready?.saved, "bookFromCache" to ready?.fromCache,
                "bookBlocks" to ready?.book?.blocks?.size, "bookWarning" to ready?.warning,
                "elapsedMillis" to (System.nanoTime() - start) / 1_000_000)
            output.writeText(Gson().toJson(report))
        }
        assertEquals(5, report.size)
        assertTrue("Functional failures: $failures; every schedule outcome is in live-random-churches-08.json", failures.isEmpty())
    }
}
