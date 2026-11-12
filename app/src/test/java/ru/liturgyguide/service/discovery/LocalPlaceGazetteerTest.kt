package ru.liturgyguide.service.discovery

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class LocalPlaceGazetteerTest {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val gson = Gson()
    private val point = GeoPoint(55.7729551, 37.6312038)
    private fun record(id: Long = 1, name: String = "Сухаревская", station: Boolean = true,
        region: String = "", network: String = if (station) "Московский метрополитен" else "",
        aliases: List<String> = emptyList()): MutableMap<String, Any> {
        val tags = linkedMapOf("name" to name)
        if (station) { tags["railway"] = "station"; tags["station"] = "subway" }
        else tags["place"] = "town"
        if (region.isNotBlank()) tags["addr:region"] = region
        if (network.isNotBlank()) tags["network"] = network
        if (aliases.isNotEmpty()) tags["alt_name"] = aliases.joinToString(";")
        return linkedMapOf("id" to "osm:node:$id", "name" to name, "aliases" to aliases,
            "center" to mapOf("latitude" to point.latitude, "longitude" to point.longitude),
            "city" to "", "region" to region, "country" to "", "network" to network, "contextLabel" to "",
            "sourceUrl" to "https://www.openstreetmap.org/node/$id", "sourceTags" to tags)
    }
    private fun body(cities: List<Map<String, Any>> = emptyList(), stations: List<Map<String, Any>> = listOf(record())) = gson.toJson(mapOf(
        "schemaVersion" to 1, "metadata" to mapOf("cityCount" to cities.size, "stationCount" to stations.size, "generatorVersion" to "test-1",
            "source" to mapOf("license" to "ODbL-1.0", "copyrightUrl" to DefaultPlaceDiscovery.COPYRIGHT_URL,
                "licenseUrl" to "https://opendatacommons.org/licenses/odbl/1-0/", "attribution" to "© OpenStreetMap contributors",
                "url" to "https://download.geofabrik.de/russia-261110.osm.pbf", "sizeBytes" to 4202162536L,
                "sha256" to "a".repeat(64), "dumpTimestamp" to "2026-11-10T21:21:39Z")), "cities" to cities, "stations" to stations))
    private fun decode(value: String) = LocalPlaceGazetteer.decode(StringReader(value), now)
    private fun source(value: String) = BundledPlaceGazetteer({ StringReader(value) }, clock)
    private fun asset(path: String): File = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path")).first { it.isFile }
    private fun realPlaces() = BundledPlaceGazetteer({ asset(BundledPlaceGazetteer.ASSET_PATH).bufferedReader() }, clock)
    private fun realChurches() = BundledChurchCatalog({ asset(BundledChurchCatalog.ASSET_PATH).bufferedReader() }, clock)
    private class Network : PlaceDiscovery {
        val calls = mutableListOf<String>()
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> { calls += "place:$query"; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE) }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> { calls += "city:$query"; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE) }
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> { calls += "nearby"; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE) }
        override suspend fun findChurchesNearLandmark(landmark: DiscoveryLandmark, churchQuery: String): DiscoveryResult<DiscoveredChurch> { calls += "station"; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE) }
    }
    private fun <T> DiscoveryResult<T>.success() = this as DiscoveryResult.Success<T>

    @Test fun shippedSnapshotResolvesSukharevskayaAndTrinityWithoutNetworkOrCityFabrication() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(realChurches(), network, realPlaces())
        val answer = client.searchPlaces("метро Сухаревская Троицы").success()
        val station = requireNotNull(answer.landmark)
        assertEquals("osm:node:277499849", station.id)
        assertEquals("Московский метрополитен", station.network)
        assertEquals("", station.city); assertEquals("", station.region); assertEquals("", station.country)
        assertTrue(station.label.contains(station.network))
        assertEquals("osm:relation:19595736", answer.items.first().id)
        assertEquals("", answer.items.first().city)
        assertTrue(station.center.distanceMeters(answer.items.first().center) in 140.0..150.0)
        assertTrue(answer.items.all { station.center.distanceMeters(it.center) <= 2_000 })
        assertEquals(answer.items.size, answer.items.distinctBy { it.id }.size)
        assertEquals(DiscoveryCacheStatus.LOCAL_SNAPSHOT, answer.cacheStatus)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun actualCityNamesAndAllNineExactNamesakesRemainAvailableOffline() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(realChurches(), network, realPlaces())
        for (name in listOf("Москва", "Казань", "Тотьма")) {
            val result = client.searchCities(name).success()
            assertTrue(name, result.items.any { it.name == name })
            assertEquals(DiscoveryProvider.LOCAL_CATALOG, result.provider)
        }
        for (name in listOf("Москва", "Казань")) {
            val result = client.searchCities("$name Россия").success()
            assertTrue(name, result.items.any { it.name == name && it.country == "RU" })
        }
        val all = client.searchCities("Октябрьский").success().items.filter { it.name == "Октябрьский" }
        assertEquals(9, all.size)
        assertEquals(9, all.distinctBy { it.id }.size)
        val kirov = client.searchCities("Киров").success().items.filter { it.name == "Киров" }
        assertEquals(setOf("Кировская область", "Калужская область"), kirov.map { it.region }.toSet())
        assertEquals(listOf("Калужская область"), client.searchCities("Киров Калужская").success().items.map { it.region })
        assertTrue(network.calls.isEmpty())
    }

    @Test fun actualMetroNamesakesHaveNetworkContextAndDoNotStartNearbyUntilChosen() = runBlocking {
        val network = Network()
        var churchReads = 0
        val churches = BundledChurchCatalog({ churchReads++; asset(BundledChurchCatalog.ASSET_PATH).bufferedReader() }, clock)
        val client = CatalogPlaceDiscovery(churches, network, realPlaces())
        for ((name, expected) in listOf("Кировская" to setOf("Нижегородский метрополитен", "Самарский метрополитен"),
            "Динамо" to setOf("Московский метрополитен", "Екатеринбургский метрополитен"))) {
            val result = client.searchPlaces("метро $name храм").success()
            assertNull(result.landmark)
            assertTrue(result.items.isEmpty())
            assertEquals(expected, result.landmarkChoices.map { it.network }.toSet())
            assertEquals(result.landmarkChoices.size, result.landmarkChoices.distinctBy { it.id }.size)
        }
        assertEquals(0, churchReads)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun aliasesPrefixesAndUnconfirmedGeographicWordsRequireAnExplicitStationChoice() = runBlocking {
        val network = Network()
        var churchReads = 0
        val client = CatalogPlaceDiscovery(BundledChurchCatalog({ churchReads++; error("Must choose station first") }, clock), network, realPlaces())
        for (query in listOf("метро Сухаревка Троицы", "метро Сухарев Троицы", "метро Сухаревская Казань храм Троицы",
            "Казань метро Сухаревская храм Троицы", "метро Сухаревская Троицы Казань",
            "Никольск метро Сухаревская храм", "Покров метро Сухаревская храм",
            "метро Сухаревская Троицы Никольск", "Никольский метро Сухаревская")) {
            val result = client.searchPlaces(query).success()
            assertNull(query, result.landmark)
            assertTrue(query, result.items.isEmpty())
            assertTrue(query, result.landmarkChoices.any { it.id == "osm:node:277499849" })
        }
        assertEquals(0, churchReads)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun separateNearbyStationsAreNeverCollapsedIntoAnInterchange() {
        val gazetteer = decode(body(stations = listOf(record(1), record(2))))
        assertEquals(listOf("osm:node:1", "osm:node:2"), gazetteer.stationChoices("Сухаревская").map { it.id })
        val choices = gazetteer.stationChoices("Сухаревская Москва")
        assertEquals(2, choices.size)
        assertFalse(gazetteer.isExactPrimary(choices.first(), "Сухаревская Москва"))
    }

    @Test fun actualArbatskayaStationsExposeTheirSourceMapColoursWithoutChangingIdentity() = runBlocking {
        val stations = requireNotNull(realPlaces().get()).stationChoices("Арбатская")
        assertEquals(mapOf("osm:node:242546476" to "blue", "osm:node:253043175" to "lightblue"),
            stations.associate { it.id to it.mapColour })
        assertTrue(stations.all { it.network == "Московский метрополитен" && it.city.isEmpty() && it.region.isEmpty() && it.country.isEmpty() })
    }

    @Test fun absentOrInvalidColourCannotInventAMapLineOrAdministrativeContext() {
        assertEquals("", decode(body()).stationChoices("Сухаревская").single().mapColour)
        val long = JsonParser.parseString(body()).asJsonObject
        long.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("colour", "b".repeat(301))
        assertEquals("", decode(long.toString()).stationChoices("Сухаревская").single().mapColour)
        for (invalid in listOf(com.google.gson.JsonPrimitive(42), com.google.gson.JsonPrimitive("blue\u0000"),
            com.google.gson.JsonPrimitive("b".repeat(2049)))) {
            val changed = JsonParser.parseString(body()).asJsonObject
            changed.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").add("colour", invalid)
            assertRejected(changed.toString())
        }
    }

    @Test fun emptyUnknownAndCorruptGazetteersKeepTheExistingNetworkFallback() = runBlocking {
        for (value in listOf("broken", body(cities = emptyList(), stations = emptyList()), body())) {
            val network = Network()
            val client = CatalogPlaceDiscovery(BundledChurchCatalog({ error("A station query must not become a church name") }, clock), network, source(value))
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchCities("Несуществующий"))
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces("метро Неизвестная храм"))
            assertEquals(listOf("city:Несуществующий", "place:метро Неизвестная храм"), network.calls)
        }
    }

    @Test fun snapshotFieldsAndSelectionCannotInventAdministrativeGeography() {
        val record = record(station = false, name = "Городок").apply {
            @Suppress("UNCHECKED_CAST") val tags = get("sourceTags") as MutableMap<String, String>
            tags["addr:district"] = "Примерный район"; tags["is_in"] = "Историческая местность"
            this["contextLabel"] = "Примерный район · Историческая местность"
        }
        val city = decode(body(cities = listOf(record), stations = emptyList())).searchCities("Городок").items.single()
        assertEquals("", city.region); assertEquals("", city.country)
        assertEquals("Примерный район · Историческая местность", city.contextLabel)
        assertTrue(decode(body(cities = listOf(record), stations = emptyList())).searchCities("Городок Россия").items.isEmpty())
        for (field in listOf("city", "region", "country", "network", "contextLabel", "name")) {
            val changed = record.toMutableMap().apply { this[field] = "Выдуманное значение" }
            assertRejected(body(cities = listOf(changed), stations = emptyList()))
        }
    }

    @Test fun malformedIdentityCoordinatesMetadataLifecycleAndAliasesFailClosed() {
        val valid = JsonParser.parseString(body()).asJsonObject
        val mutations = listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.addProperty("schemaVersion", 2) },
            { it.getAsJsonObject("metadata").addProperty("stationCount", 2) },
            { it.getAsJsonObject("metadata").getAsJsonObject("source").addProperty("license", "unknown") },
            { it.getAsJsonObject("metadata").getAsJsonObject("source").addProperty("dumpTimestamp", "2030-01-01T00:00:00Z") },
            { it.getAsJsonArray("stations")[0].asJsonObject.addProperty("id", "osm:way:1") },
            { it.getAsJsonArray("stations")[0].asJsonObject.addProperty("sourceUrl", "https://www.openstreetmap.org/node/2") },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("center").addProperty("latitude", 91) },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("railway", "subway_entrance") },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("place", "town") },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("proposed:station", "subway") },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("construction", "yes".repeat(101)) },
            { it.getAsJsonArray("stations")[0].asJsonObject.getAsJsonArray("aliases").add("Придуманное название") },
            { it.getAsJsonArray("stations").add(it.getAsJsonArray("stations")[0].deepCopy()); it.getAsJsonObject("metadata").addProperty("stationCount", 2) },
        )
        for (mutate in mutations) assertRejected(valid.deepCopy().also(mutate).toString())
        assertRejected(body().dropLast(1))
        assertRejected(body() + " trailing")
        assertRejected(body().replace("\"sourceTags\":{", "\"sourceTags\":{/* invalid JSON */"))
        assertRejected(body().replace("\"sourceTags\":{", "\"sourceTags\":{\"name\":\"Duplicate\","))
        assertRejected(body().replace("\"sourceTags\":{", "\"sourceTags\":{'invalid':'JSON',"))
        assertRejected(body().replace("\"sourceTags\":{", "\"sourceTags\":{\"nested\":" + "[".repeat(10) + "0" + "]".repeat(10) + ","))
        assertRejected(body().replace("\"name\":\"Сухаревская\"", "\"name\":\"${"a".repeat(4097)}\""))
        assertRejected(body().replaceFirst("{", "{\"unknown\":[],"))
        val wideTree = List(3) { "[" + List(10_000) { "0" }.joinToString(",") + "]" }.joinToString(",")
        assertRejected(body().replace("\"sourceTags\":{", "\"sourceTags\":{\"nested\":[$wideTree],"))
    }

    @Test fun invalidQueryDoesNotReadAssetsOrSendRequests() = runBlocking {
        val reads = AtomicInteger()
        val network = Network()
        val client = CatalogPlaceDiscovery(BundledChurchCatalog({ reads.incrementAndGet(); error("Unused") }, clock), network,
            BundledPlaceGazetteer({ reads.incrementAndGet(); error("Unused") }, clock))
        for (query in listOf("x", "\u0000Москва", "я".repeat(161))) {
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchCities(query))
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchPlaces(query))
        }
        assertEquals(0, reads.get()); assertTrue(network.calls.isEmpty())
    }

    @Test fun loadRunsOnceOnIoAndCancellationAllowsASecondAttempt() = runBlocking {
        var opens = 0
        val caller = Thread.currentThread()
        val source = BundledPlaceGazetteer({
            assertNotSame(caller, Thread.currentThread()); opens++
            if (opens == 1) throw CancellationException("Cancelled")
            StringReader(body())
        }, clock)
        try { source.get(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        coroutineScope { (1..10).map { async { assertNotNull(source.get()) } }.awaitAll() }
        assertEquals(2, opens)
        var checks = 0
        try {
            LocalPlaceGazetteer.decode(StringReader(body()), now) { checks++; throw CancellationException() }
            fail("Reader cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, checks) }
    }

    private fun assertRejected(value: String) {
        try { decode(value); fail("Malformed snapshot accepted") } catch (expected: IllegalArgumentException) { }
        catch (expected: IllegalStateException) { }
        catch (expected: com.google.gson.JsonParseException) { }
        catch (expected: java.io.IOException) { }
    }
}
