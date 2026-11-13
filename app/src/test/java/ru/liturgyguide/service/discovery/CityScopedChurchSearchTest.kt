package ru.liturgyguide.service.discovery

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A bounded map supplement is evidence of proximity, never an invented addr:city. */
class CityScopedChurchSearchTest {
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val gson = Gson()
    private val moscow = GeoPoint(55.75, 37.61)
    private val kazan = GeoPoint(55.796127, 49.106414)
    private val vladimir = GeoPoint(56.1289, 40.4076)

    @Test fun theFiveGenuineSampleChurchesRemainFindableWithoutInventingTheirMissingCities() = runBlocking {
        val network = Network()
        val catalog = BundledChurchCatalog({ asset(BundledChurchCatalog.ASSET_PATH).bufferedReader() }, clock)
        val places = BundledPlaceGazetteer({ asset(BundledPlaceGazetteer.ASSET_PATH).bufferedReader() }, clock)
        val client = CatalogPlaceDiscovery(catalog, network, places)
        val raw = asset(BundledChurchCatalog.ASSET_PATH).bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
            .getAsJsonArray("churches")
        val missing = mutableListOf<String>()
        for ((query, id) in listOf(
            "храм Казань" to "osm:way:91769591",
            "храм Ярославль" to "osm:way:95943763",
            "храм Новосибирск" to "osm:way:55228075",
            "храм Калуга" to "osm:way:155168069",
            "церковь Зарайск" to "osm:way:321540158"
        )) {
            val published = gson.fromJson(raw.single { it.asJsonObject["id"].asString == id }, DiscoveredChurch::class.java)
            assertEquals("The genuine map record has no published city", "", published.city)
            val answer = client.searchPlaces(query).success()
            val church = answer.items.singleOrNull { it.id == id }
            if (church == null) missing += "$query: $id"
            else {
                assertEquals("Map identity, coordinates, address, website and empty city must stay unchanged", published, church)
                assertEquals(DiscoveryProvider.LOCAL_CATALOG, answer.provider)
                assertEquals(DiscoveryCacheStatus.LOCAL_SNAPSHOT, answer.cacheStatus)
                assertNull("A mixed result cannot claim every item is inside a radius", answer.queryCenter)
                assertNull(answer.radiusMeters)
                assertTrue(requireNotNull(answer.notice).contains("10 км"))
            }
        }
        assertTrue("The five actual omissions must be repaired: $missing", missing.isEmpty())
        assertTrue("Bundled city points and churches must not require a map request", network.calls.isEmpty())
    }

    @Test fun proximitySupplementsOnlyBlankCitiesAndKeepsEveryPublishedCityHitEvenOutsideTheCircle() = runBlocking {
        val near = church(2, "Храм Николая", at = moscow.copy(latitude = moscow.latitude + .02))
        val farKnown = church(1, "Храм Николая", city = "Москва", at = moscow.copy(latitude = moscow.latitude + .3))
        val knownOther = church(3, "Храм Николая", city = "Казань", at = moscow.copy(latitude = moscow.latitude + .01))
        val outside = church(4, "Храм Николая", at = moscow.copy(latitude = moscow.latitude + .11))
        val network = Network()
        val answer = client(listOf(farKnown, near, knownOther, outside), listOf(city(11, "Москва", moscow)), network)
            .searchPlaces("Николая Москва").success()
        assertEquals(setOf("osm:way:1", "osm:way:2"), answer.items.map { it.id }.toSet())
        assertEquals("", answer.items.single { it.id == "osm:way:2" }.city)
        assertTrue(moscow.distanceMeters(answer.items.single { it.id == "osm:way:1" }.center) > 10_000)
        assertNull(answer.queryCenter)
        assertNull(answer.radiusMeters)
        assertTrue(requireNotNull(answer.notice).contains("10 км"))
        assertTrue(network.calls.isEmpty())
    }

    @Test fun theRemainingNameAndAddressTermsStayMandatoryAfterRemovingOnlyTheCitySpan() = runBlocking {
        val network = Network()
        val churches = listOf(
            church(1, "Храм Петра", city = "Москва"),
            church(2, "Храм Николая", address = "Дорожная улица, 5"),
            church(3, "Храм Николая", address = "Дорожная улица, 6"),
            church(4, "Храм Петра", address = "Дорожная улица, 5"),
            church(5, "Храм Николая", address = "Дорожная улица, 5", at = kazan)
        )
        val answer = client(churches, listOf(city(11, "Москва", moscow)), network)
            .searchPlaces("Николая 5 в Москве").success()
        assertEquals(listOf("osm:way:2"), answer.items.map { it.id })
        assertEquals("Дорожная улица, 5", answer.items.single().address)
        assertEquals("", answer.items.single().city)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun aCityWithNoAddrCityChurchesCanStillUseItsUniquePublishedGazetteerPoint() = runBlocking {
        val network = Network()
        val answer = client(listOf(church(1, "Храм Николая"), church(2, "Храм Петра", at = kazan)),
            listOf(city(11, "Москва", moscow)), network).searchPlaces("храм Москва").success()
        assertEquals(listOf("osm:way:1"), answer.items.map { it.id })
        assertEquals("", answer.items.single().city)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun exactNamesakesAndTwoIndependentExplicitCitiesNeverChooseTheFirstCenter() = runBlocking {
        val network = Network()
        val sameName = client(listOf(church(1, "Храм Николая", city = "Москва"), church(2, "Храм Николая")),
            listOf(city(11, "Москва", moscow), city(12, "Москва", kazan)), network)
        val answer = sameName.searchPlaces("Николая Москва").success()
        assertEquals(listOf("osm:way:1"), answer.items.map { it.id })
        assertFalse(requireNotNull(answer.notice).contains("10 км"))
        assertTrue(network.calls.isEmpty())

        val contradictory = client(listOf(church(1, "Храм Николая", city = "Москва"),
            church(2, "Храм Николая", city = "Казань", at = kazan),
            church(3, "Храм Николая Казани"), church(4, "Храм Николая Москвы", at = kazan)),
            listOf(city(11, "Москва", moscow), city(12, "Казань", kazan)), network)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE),
            contradictory.searchPlaces("Николая в Москве в Казани"))
        assertEquals(listOf("Николая в Москве в Казани"), network.calls)
    }

    @Test fun personalNamesAndIconsAreNotLocationsAndTheExplicitCityKeepsTheSaintName() = runBlocking {
        val network = Network()
        val churches = listOf(
            church(1, "Храм Николая", city = "Владимир", at = vladimir),
            church(2, "Храм святого Владимира", at = vladimir),
            church(3, "Храм Иверской иконы", at = kazan),
            church(4, "Храм Казанской иконы", at = kazan),
            church(5, "Храм Владимира", at = kazan),
            church(6, "Храм Николая", city = "Казань", at = kazan)
        )
        val client = client(churches, listOf(city(11, "Владимир", vladimir), city(12, "Казань", kazan)), network)
        // Existing strict-name behavior remains; a saint's genitive must not enable a new map scope.
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces("храм святого Владимира"))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces("храм имени Владимир"))
        val icon = client.searchPlaces("храм Казанской иконы").success()
        assertEquals(listOf("osm:way:4"), icon.items.map { it.id })
        assertFalse(requireNotNull(icon.notice).contains("10 км"))
        val explicit = client.searchPlaces("храм Владимира в Казани").success()
        assertEquals(listOf("osm:way:5"), explicit.items.map { it.id })
        assertEquals("", explicit.items.single().city)
    }

    @Test fun anExplicitLocationAlsoKeepsTheSaintNameForPublishedCityRecordsWithoutARadiusClaim() = runBlocking {
        val network = Network()
        val published = church(2, "Храм Владимира", city = "Казань", at = kazan.copy(latitude = kazan.latitude + .2))
        val result = client(listOf(church(1, "Храм Николая", city = "Владимир", at = vladimir), published,
            church(3, "Храм Николая", city = "Казань", at = kazan),
            church(4, "Храм Владимира", city = "Москва", at = kazan)),
            listOf(city(11, "Владимир", vladimir), city(12, "Казань", kazan)), network)
            .searchPlaces("храм Владимира в Казани").success()
        assertEquals(listOf("osm:way:2"), result.items.map { it.id })
        assertEquals("Казань", result.items.single().city)
        assertTrue("Published city evidence is not limited to the supplemental map circle",
            kazan.distanceMeters(result.items.single().center) > 10_000)
        assertNull(result.queryCenter)
        assertNull(result.radiusMeters)
        assertFalse(requireNotNull(result.notice).contains("10 км"))
        assertTrue(network.calls.isEmpty())
    }

    @Test fun theLongestMultiwordCityAndItsExplicitLocativeStayOneLocation() = runBlocking {
        val network = Network()
        val novgorod = GeoPoint(58.52, 31.27)
        val nizhny = GeoPoint(56.32, 44.0)
        val churches = listOf(church(1, "Храм Петра", city = "Нижний Новгород", at = nizhny),
            church(2, "Храм Николая", at = nizhny), church(3, "Храм Николая", city = "Новгород", at = novgorod),
            church(4, "Храм Николая", at = novgorod))
        val client = client(churches, listOf(city(11, "Новгород", novgorod), city(12, "Нижний Новгород", nizhny)), network)
        assertEquals(setOf("osm:way:1", "osm:way:2"), client.searchPlaces("храм Нижний Новгород").success().items.map { it.id }.toSet())
        assertEquals(listOf("osm:way:2"), client.searchPlaces("Николая в Нижнем Новгороде").success().items.map { it.id })
        assertTrue(network.calls.isEmpty())
        // An unaccepted full-city form must not fall through to its shorter trailing city.
        for (query in listOf("Николая в Нижний Новгород", "Николая в Нижнем Новгород"))
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces(query))
    }

    @Test fun absentGazetteerAndNoAddedChurchPreserveTheOriginalResultAndFallback() = runBlocking {
        val known = church(1, "Храм Петра", city = "Москва")
        val missing = church(2, "Храм Николая")
        val network = Network()
        val noPlaces = CatalogPlaceDiscovery(churchSource(listOf(known, missing)), network)
        assertEquals(listOf("osm:way:1"), noPlaces.searchPlaces("Петра Москва").success().items.map { it.id })
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), noPlaces.searchPlaces("Николая Москва"))
        val noAddition = client(listOf(known), listOf(city(11, "Москва", moscow)), network)
            .searchPlaces("Петра Москва").success()
        assertFalse(requireNotNull(noAddition.notice).contains("10 км"))
        assertNull(noAddition.queryCenter)
        assertNull(noAddition.radiusMeters)
        assertEquals(listOf("Николая Москва"), network.calls)
    }

    @Test fun aSupplementCannotPushAnyExistingStrictHitOutOfThePreviouslyCappedResult() = runBlocking {
        val records = (1L..65L).map { church(it, "Храм Николая $it", city = "Москва",
            at = moscow.copy(latitude = moscow.latitude + .3)) } + church(1000, "Храм Николая")
        val strict = CatalogPlaceDiscovery(churchSource(records), Network()).searchPlaces("Николая Москва").success()
        assertEquals(DefaultPlaceDiscovery.MAX_CHURCHES, strict.items.size)
        val result = client(records, listOf(city(11, "Москва", moscow)), Network())
            .searchPlaces("Николая Москва").success()
        assertTrue(result.items.containsAll(strict.items))
        assertTrue(result.items.any { it.id == "osm:way:1000" })
        assertEquals(strict.items.size + 1, result.items.size)
        assertNull(result.queryCenter)
        assertNull(result.radiusMeters)
    }

    private fun church(id: Long, name: String, city: String = "", at: GeoPoint = moscow,
        address: String = ""): Map<String, Any?> = linkedMapOf(
        "id" to "osm:way:$id", "name" to name, "center" to mapOf("latitude" to at.latitude, "longitude" to at.longitude),
        "address" to address, "city" to city, "region" to "", "country" to "", "aliases" to emptyList<String>(),
        "website" to null, "isChapel" to false, "kind" to "CHURCH", "sourceUrl" to "https://www.openstreetmap.org/way/$id",
        "sourceTags" to mapOf("amenity" to "place_of_worship", "religion" to "christian", "denomination" to "orthodox", "name" to name)
    )
    private fun city(id: Long, name: String, at: GeoPoint): Map<String, Any?> = linkedMapOf(
        "id" to "osm:node:$id", "name" to name, "aliases" to emptyList<String>(),
        "center" to mapOf("latitude" to at.latitude, "longitude" to at.longitude), "city" to "", "region" to "", "country" to "",
        "network" to "", "contextLabel" to "", "sourceUrl" to "https://www.openstreetmap.org/node/$id",
        "sourceTags" to mapOf("name" to name, "place" to "city")
    )
    private fun metadata() = mapOf("url" to "https://download.geofabrik.de/russia-261110.osm.pbf", "sizeBytes" to 4_202_162_536L,
        "sha256" to "a".repeat(64), "dumpTimestamp" to "2026-11-10T21:00:00Z", "license" to "ODbL-1.0",
        "licenseUrl" to "https://opendatacommons.org/licenses/odbl/1-0/", "copyrightUrl" to DefaultPlaceDiscovery.COPYRIGHT_URL,
        "attribution" to "© OpenStreetMap contributors")
    private fun churchSource(records: List<Map<String, Any?>>) = BundledChurchCatalog({ StringReader(gson.toJson(mapOf(
        "schemaVersion" to 1, "metadata" to mapOf("recordCount" to records.size, "source" to metadata()), "churches" to records))) }, clock)
    private fun client(churches: List<Map<String, Any?>>, cities: List<Map<String, Any?>>, network: Network) = CatalogPlaceDiscovery(
        churchSource(churches), network, BundledPlaceGazetteer({ StringReader(gson.toJson(mapOf("schemaVersion" to 1,
            "metadata" to mapOf("cityCount" to cities.size, "stationCount" to 0, "generatorVersion" to "city-scope-test-1", "source" to metadata()),
            "cities" to cities, "stations" to emptyList<Any>()))) }, clock))
    private fun asset(path: String) = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path")).first { it.isFile }
    private fun DiscoveryResult<DiscoveredChurch>.success(): DiscoveryResult.Success<DiscoveredChurch> {
        assertTrue("Expected local discovery success, got $this", this is DiscoveryResult.Success)
        return this as DiscoveryResult.Success<DiscoveredChurch>
    }
    private class Network : PlaceDiscovery {
        val calls = mutableListOf<String>()
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
            calls += query; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("No network city query expected")
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("No nearby network query expected")
    }
}
