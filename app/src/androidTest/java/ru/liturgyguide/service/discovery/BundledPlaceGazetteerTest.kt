package ru.liturgyguide.service.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.ui.toSavedChurch
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Android acceptance of the packaged public map data, with every network entry point forbidden. */
class BundledPlaceGazetteerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val networkCalls = AtomicInteger()
    private val offline = object : PlaceDiscovery {
        private fun forbidden(): Nothing {
            networkCalls.incrementAndGet()
            error("This accepted city/metro search must use the packaged data without network")
        }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = forbidden()
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> = forbidden()
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = forbidden()
        override suspend fun findChurchesNearLandmark(landmark: DiscoveryLandmark, churchQuery: String): DiscoveryResult<DiscoveredChurch> = forbidden()
    }

    private class Probe(val discovery: PlaceDiscovery, val churchReads: AtomicInteger, val placeReads: AtomicInteger)

    private fun newDiscovery(): Probe {
        val churchReads = AtomicInteger()
        val placeReads = AtomicInteger()
        return Probe(CatalogPlaceDiscovery(BundledChurchCatalog(open = {
            churchReads.incrementAndGet()
            context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8)
        }), offline, BundledPlaceGazetteer(open = {
            placeReads.incrementAndGet()
            context.assets.open(BundledPlaceGazetteer.ASSET_PATH).bufferedReader(Charsets.UTF_8)
        })), churchReads, placeReads)
    }

    @Test fun actualCitiesMetroChoicesAndSelectedChurchSurviveFreshOfflineInstances() = runBlocking {
        val first = newDiscovery()
        val timings = linkedMapOf<String, Double>()
        suspend fun <T> measured(label: String, action: suspend () -> T): T {
            val started = System.nanoTime()
            return action().also { timings[label] = (System.nanoTime() - started) / 1_000_000.0 }
        }

        val moscow = success(measured("coldCitySearchMillis") { first.discovery.searchCities("Москва Россия") })
        assertEquals(listOf("osm:node:1686293227"), moscow.items.map { it.id })
        assertEquals("RU", moscow.items.single().country)
        val kazan = success(measured("warmCitySearchMillis") { first.discovery.searchCities("Казань Россия") })
        assertEquals(listOf("osm:node:27504067"), kazan.items.map { it.id })
        assertEquals("Татарстан", kazan.items.single().region)
        val october = success(first.discovery.searchCities("Октябрьский")).items.filter { it.name == "Октябрьский" }
        assertEquals(setOf("osm:node:1888848046", "osm:node:191901380", "osm:node:2598820341",
            "osm:node:2825826684", "osm:node:317470882", "osm:node:336140969", "osm:node:346277513",
            "osm:node:356772829", "osm:node:456118810"), october.map { it.id }.toSet())
        assertEquals(9, october.size)

        val ambiguous = success(first.discovery.searchPlaces("метро Кировская храм"))
        assertNull(ambiguous.landmark)
        assertTrue(ambiguous.items.isEmpty())
        assertEquals(2, ambiguous.landmarkChoices.size)
        assertEquals(mapOf("osm:node:442018472" to "Нижегородский метрополитен",
            "osm:node:6986625318" to "Самарский метрополитен"), ambiguous.landmarkChoices.associate { it.id to it.network })
        assertTrue(ambiguous.landmarkChoices.all { it.city.isEmpty() && it.region.isEmpty() && it.country.isEmpty() })
        assertEquals("Do not search churches before a namesake station is selected", 0, first.churchReads.get())

        val chosenResults = ambiguous.landmarkChoices.associate { station ->
            val result = success(measured("selectedStation:${station.id}") {
                first.discovery.findChurchesNearLandmark(station, ambiguous.landmarkChurchQuery)
            })
            assertEquals(station, result.landmark)
            assertEquals(DefaultPlaceDiscovery.LANDMARK_RADIUS_METERS, result.radiusMeters)
            assertTrue(result.items.isNotEmpty())
            assertTrue(result.items.all { station.center.distanceMeters(it.center) <= DefaultPlaceDiscovery.LANDMARK_RADIUS_METERS })
            assertEquals(result.items.size, result.items.distinctBy { it.id }.size)
            station.id to result
        }
        val nizhny = chosenResults.getValue("osm:node:442018472").items
        val samara = chosenResults.getValue("osm:node:6986625318").items
        assertTrue(nizhny.any { it.id == "osm:relation:2306920" })
        assertTrue(samara.any { it.id == "osm:way:425097599" })
        assertTrue(nizhny.map { it.id }.toSet().intersect(samara.map { it.id }.toSet()).isEmpty())

        val trinity = success(measured("warmMetroSearchMillis") { first.discovery.searchPlaces("метро Сухаревская Троицы") })
        val station = requireNotNull(trinity.landmark)
        assertEquals("osm:node:277499849", station.id)
        assertEquals("Московский метрополитен", station.network)
        assertTrue(station.city.isEmpty() && station.region.isEmpty() && station.country.isEmpty())
        assertTrue(trinity.landmarkChoices.isEmpty())
        assertEquals("osm:relation:19595736", trinity.items.first().id)
        assertEquals("", trinity.items.first().city)
        assertTrue(station.center.distanceMeters(trinity.items.first().center) in 140.0..150.0)
        assertTrue(trinity.items.all { station.center.distanceMeters(it.center) <= DefaultPlaceDiscovery.LANDMARK_RADIUS_METERS })
        assertEquals(1, first.placeReads.get())
        assertEquals(1, first.churchReads.get())

        val preferences = context.getSharedPreferences("bundled-place-native-acceptance", 0)
        check(preferences.edit().clear().commit())
        try {
            val selected = requireNotNull(trinity.items.first().toSavedChurch())
            assertTrue(ChurchDirectoryStore(preferences).save(selected))
            val recreated = newDiscovery()
            val replay = success(measured("freshMetroSearchMillis") { recreated.discovery.searchPlaces("метро Сухаревская Троицы") })
            assertEquals(trinity, replay)
            assertEquals(moscow, success(recreated.discovery.searchCities("Москва Россия")))
            assertEquals(ambiguous, success(recreated.discovery.searchPlaces("метро Кировская храм")))
            assertEquals(selected, ChurchDirectoryStore(preferences).records().single { it.id == selected.id })
            assertTrue("A map entry cannot invent a parish schedule", selected.asParish().schedule.isEmpty())
            assertEquals(1, recreated.placeReads.get())
            assertEquals(1, recreated.churchReads.get())
            assertEquals(0, networkCalls.get())
            context.filesDir.resolve("bundled-place-acceptance.json").writeText(Gson().toJson(mapOf(
                "timingsMillis" to timings, "networkRequests" to networkCalls.get(),
                "octoberNamesakeIds" to october.map { it.id },
                "stationChoices" to ambiguous.landmarkChoices,
                "selectedStationChurchIds" to chosenResults.mapValues { entry -> entry.value.items.map { it.id } },
                "sukharevskayaFirstChurch" to selected.id,
                "sukharevskayaFirstChurchDistanceMeters" to station.center.distanceMeters(trinity.items.first().center),
                "freshInstancesEqual" to true, "savedChurchRestored" to true)))
        } finally {
            check(preferences.edit().clear().commit())
        }
    }

    private fun <T> success(result: DiscoveryResult<T>): DiscoveryResult.Success<T> {
        assertTrue("Real bundled data did not answer locally: $result", result is DiscoveryResult.Success)
        return (result as DiscoveryResult.Success<T>).also {
            assertEquals(DiscoveryProvider.LOCAL_CATALOG, it.provider)
            assertEquals(DiscoveryCacheStatus.LOCAL_SNAPSHOT, it.cacheStatus)
            assertEquals(Instant.parse("2026-11-10T21:21:39Z").toEpochMilli(), it.fetchedAtEpochMillis)
        }
    }
}
