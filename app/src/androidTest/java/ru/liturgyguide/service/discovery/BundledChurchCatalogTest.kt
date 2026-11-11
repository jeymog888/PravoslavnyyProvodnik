package ru.liturgyguide.service.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.ui.toSavedChurch
import java.time.Instant

/** Exercises the shipped OSM snapshot, not a test map or a simulated network response. */
class BundledChurchCatalogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var networkCalls = 0
    private val offline = object : PlaceDiscovery {
        private fun forbidden(): Nothing { networkCalls++; error("The bundled catalog must answer without a network request") }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = forbidden()
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> = forbidden()
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = forbidden()
    }
    private fun newDiscovery() = CatalogPlaceDiscovery(BundledChurchCatalog(open = {
        context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8)
    }), offline)

    @Test fun actualSnapshotFindsThreeCitiesAndKeepsSelectedChurchAfterRecreation() = runBlocking {
        val discovery = newDiscovery()
        val report = mutableListOf<Map<String, Any>>()
        val cases = listOf(
            Triple("Москва", GeoPoint(55.77, 37.63), "osm:relation:19595736"),
            Triple("Казань", GeoPoint(55.80, 49.11), "osm:way:91769591"),
            Triple("Тотьма", GeoPoint(59.97, 42.76), "osm:way:181927025")
        )
        val preferences = context.getSharedPreferences("catalog-native-acceptance", 0)
        check(preferences.edit().clear().commit())
        try {
            for ((label, center, expectedId) in cases) {
                val started = System.nanoTime()
                val result = success(discovery.findChurches(center, 3_000))
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue("$label: expected real church missing", result.items.any { it.id == expectedId })
                assertEquals(result.items.size, result.items.distinctBy { it.id }.size)
                val distances = result.items.map { center.distanceMeters(it.center) }
                assertTrue(distances.all { it <= 3_000 })
                assertEquals(distances.sorted(), distances)
                val church = result.items.single { it.id == expectedId }
                val saved = requireNotNull(church.toSavedChurch())
                assertTrue(ChurchDirectoryStore(preferences).save(saved))
                assertEquals(saved, ChurchDirectoryStore(preferences).records().single { it.id == church.id })
                assertTrue(saved.asParish().schedule.isEmpty())
                report += mapOf("declaredCity" to label, "point" to center, "found" to result.items.size,
                    "expectedId" to expectedId, "distanceMeters" to center.distanceMeters(church.center), "elapsedMillis" to elapsed)
            }
            val http = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use { reader ->
                com.google.gson.JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("churches")
                    .first { it.asJsonObject.get("website")?.takeUnless { value -> value.isJsonNull }?.asString?.startsWith("http://") == true }
                    .asJsonObject
            }
            val point = http.getAsJsonObject("center").let { GeoPoint(it.get("latitude").asDouble, it.get("longitude").asDouble) }
            val actual = success(discovery.findChurches(point, 3_000)).items.single { it.id == http.get("id").asString }
            val record = requireNotNull(actual.toSavedChurch())
            assertEquals(actual.website, record.website)
            assertTrue(record.website.startsWith("http://"))
            assertEquals(java.net.URI(http.get("website").asString).host, java.net.URI(record.website).host)
            assertEquals(record, SavedChurchCodec.decode(SavedChurchCodec.encode(listOf(record))).single())
            assertTrue(ChurchDirectoryStore(preferences).save(record))
            assertEquals(record, ChurchDirectoryStore(preferences).records().single { it.id == record.id })
            assertEquals(0, networkCalls)
            context.filesDir.resolve("bundled-catalog-acceptance.json").writeText(Gson().toJson(mapOf(
                "cases" to report, "httpRecord" to record.id, "httpWebsite" to record.website,
                "networkRequests" to networkCalls, "javaHeapUsedBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })))
        } finally { check(preferences.edit().clear().commit()) }
    }

    @Test fun actualNamesWorkWithFreshCatalogInstancesAndDoNotMergeNamesakes() = runBlocking {
        val trinity = success(newDiscovery().searchPlaces("Троицы в Листах"))
        assertTrue(trinity.items.any { it.id == "osm:relation:19595736" })
        val monastery = success(newDiscovery().searchPlaces("Сретенский"))
        assertTrue(monastery.items.any { it.id == "osm:relation:3325777" })
        assertTrue(monastery.items.any { it.id == "osm:way:270126913" })
        assertEquals(0, networkCalls)
    }

    private fun success(result: DiscoveryResult<DiscoveredChurch>): DiscoveryResult.Success<DiscoveredChurch> {
        assertTrue("Real asset was not decoded: $result", result is DiscoveryResult.Success)
        return (result as DiscoveryResult.Success).also {
            assertEquals(DiscoveryProvider.LOCAL_CATALOG, it.provider)
            assertEquals(DiscoveryCacheStatus.LOCAL_SNAPSHOT, it.cacheStatus)
            assertEquals(Instant.parse("2026-11-10T21:21:39Z").toEpochMilli(), it.fetchedAtEpochMillis)
            assertTrue(it.items.isNotEmpty())
        }
    }
}
