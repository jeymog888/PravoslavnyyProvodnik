package ru.liturgyguide.service.discovery

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NameSearchTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream("discovery/$name.json"))
        .bufferedReader().use { it.readText() }

    private fun <T> DiscoveryResult<T>.success(): DiscoveryResult.Success<T> = this as DiscoveryResult.Success<T>

    private class MemoryCache : DiscoveryCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(slot: String) = values[slot]
        override fun write(slot: String, value: String) { values[slot] = value }
    }

    private class TestClock(var now: Instant = Instant.parse("2026-11-09T08:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Transport(var body: String) : DiscoveryTransport {
        val requests = mutableListOf<DiscoveryRequest>()
        var failure: DiscoveryFailure? = null
        var cancel = false
        override suspend fun fetch(request: DiscoveryRequest): String {
            requests += request
            if (cancel) throw CancellationException("Explicit cancellation")
            failure?.let { throw DiscoveryException(it) }
            return body
        }
    }

    @Test fun `real direct Sretensky result preserves the monastery without inventing missing data`() = runBlocking {
        val transport = Transport(fixture("name-sretensky"))
        val result = DefaultPlaceDiscovery(transport).searchPlaces("Сретенский монастырь Москва").success()
        val monastery = result.items.single()
        assertEquals("osm:relation:3325777", monastery.id)
        assertEquals("Сретенский монастырь", monastery.name)
        assertEquals(DiscoveredPlaceKind.MONASTERY, monastery.kind)
        assertEquals("Москва", monastery.city)
        assertEquals("Москва", monastery.region)
        assertEquals("Россия", monastery.country)
        assertEquals("улица Большая Лубянка", monastery.address)
        assertNull("The map reply has no website", monastery.website)
        assertEquals("https://www.openstreetmap.org/relation/3325777", monastery.sourceUrl)
        assertEquals(55.7655784, monastery.center.latitude, 0.0000001)
        assertEquals(37.6294883, monastery.center.longitude, 0.0000001)
        assertEquals(DiscoveryProvider.PHOTON, result.provider)
        assertEquals(DiscoveryCacheStatus.FRESH_NETWORK, result.cacheStatus)
        assertNull("Name results are not constrained to an invented nearby area", result.queryCenter)
        assertNull(result.radiusMeters)
        assertEquals(1, transport.requests.size)
    }

    @Test fun `a different city and ambiguous names retain their geography`() {
        val petersburg = DiscoveryJson.places(fixture("name-petersburg")).single()
        assertEquals("osm:relation:11763697", petersburg.id)
        assertEquals("Казанский собор", petersburg.name)
        assertEquals("Санкт-Петербург", petersburg.city)
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, petersburg.kind)
        val namesakes = DiscoveryJson.places(fixture("name-namesakes"))
        assertEquals(10, namesakes.size)
        val moscow = namesakes.first { it.id == "osm:relation:3325777" }
        val buryatia = namesakes.first { it.id == "osm:way:270126913" }
        assertEquals(moscow.name, buryatia.name)
        assertNotEquals(moscow.center, buryatia.center)
        assertEquals("Бурятия, Прибайкальский район", buryatia.region)
        assertNotEquals(moscow.city, buryatia.city)
    }

    @Test fun `nearby buildings of one monastery keep separate identities and only true duplicates merge`() {
        val body = JsonParser.parseString(fixture("name-parser-monastery-complex")).asJsonObject
        val features = body.getAsJsonArray("features")
        val ids = setOf(3325777L, 51259798L, 484755695L)
        val selected = features.filter { it.asJsonObject.getAsJsonObject("properties")["osm_id"].asLong in ids }
        assertEquals(3, selected.size)
        body.add("features", JsonArray().apply { selected.forEach(::add); add(selected.first().deepCopy()) })
        val places = DiscoveryJson.places(body.toString())
        assertEquals(setOf("osm:relation:3325777", "osm:way:51259798", "osm:way:484755695"), places.map { it.id }.toSet())
        assertEquals(3, places.size)
        assertEquals(1, places.count { it.kind == DiscoveredPlaceKind.MONASTERY })
    }

    @Test fun `mixed map categories do not become claims that every result is an Orthodox church`() {
        // An unmodified reverse GeoJSON fixture tests the shared category parser only.
        // The product's nearby search still uses the separately filtered Overpass path.
        val places = DiscoveryJson.places(fixture("name-parser-mixed-kazan"))
        val mosque = places.first { it.id == "osm:way:230132591" }
        assertEquals("Кул-Шариф", mosque.name)
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, mosque.kind)
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, places.first { it.name.startsWith("Часовня") }.kind)
        assertTrue(places.none { it.kind == DiscoveredPlaceKind.CHURCH || it.kind == DiscoveredPlaceKind.CHAPEL })
    }

    @Test fun `the complete name is encoded once and is never forced through city search`() = runBlocking {
        val query = "Сретенский монастырь Москва &limit=999 # храм"
        val transport = Transport(fixture("name-sretensky"))
        DefaultPlaceDiscovery(transport).searchPlaces(query)
        val request = transport.requests.single()
        val parameters = URI(request.url).rawQuery.split('&').map { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        assertEquals(query, parameters.single { it.first == "q" }.second)
        assertEquals("10", parameters.single { it.first == "limit" }.second)
        assertEquals("default", parameters.single { it.first == "lang" }.second)
        assertEquals("osm.amenity.place_of_worship,osm.amenity.monastery", parameters.single { it.first == "include" }.second)
        assertFalse(parameters.any { it.first == "layer" })
        assertNull(URI(request.url).fragment)
        assertNull(request.postBody)
    }

    @Test fun `invalid name input never starts transport`() = runBlocking {
        val transport = Transport(fixture("name-sretensky"))
        val client = DefaultPlaceDiscovery(transport)
        listOf("", " ", "Я", "а".repeat(121), "Храм\u0000Москва").forEach { query ->
            assertEquals(query, DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchPlaces(query))
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `untrusted feature identity geometry and labels are rejected without discarding valid neighbours`() {
        val original = JsonParser.parseString(fixture("name-sretensky")).asJsonObject
            .getAsJsonArray("features").single().asJsonObject
        val mutations = listOf<Pair<String, (JsonObject) -> Unit>>(
            "unknown feature type" to { it.addProperty("type", "Anything") },
            "non point" to { it.getAsJsonObject("geometry").addProperty("type", "Polygon") },
            "missing coordinate" to { it.getAsJsonObject("geometry").getAsJsonArray("coordinates").remove(1) },
            "latitude range" to { it.getAsJsonObject("geometry").getAsJsonArray("coordinates").set(1, JsonPrimitive(91)) },
            "longitude range" to { it.getAsJsonObject("geometry").getAsJsonArray("coordinates").set(0, JsonPrimitive(181)) },
            "string coordinate" to { it.getAsJsonObject("geometry").getAsJsonArray("coordinates").set(1, JsonPrimitive("55.0")) },
            "unsafe osm type" to { it.getAsJsonObject("properties").addProperty("osm_type", "../../") },
            "unsafe osm id" to { it.getAsJsonObject("properties").addProperty("osm_id", "../3325777") },
            "overflow osm id" to { it.getAsJsonObject("properties").addProperty("osm_id", "9223372036854775808") },
            "zero osm id" to { it.getAsJsonObject("properties").addProperty("osm_id", 0) },
            "wrong category" to { it.getAsJsonObject("properties").addProperty("osm_value", "school") },
            "wrong category key" to { it.getAsJsonObject("properties").addProperty("osm_key", "building") },
            "blank name" to { it.getAsJsonObject("properties").addProperty("name", " ") },
            "oversized name" to { it.getAsJsonObject("properties").addProperty("name", "а".repeat(301)) },
            "control in name" to { it.getAsJsonObject("properties").addProperty("name", "Храм\u0000Москва") }
        )
        mutations.forEach { (label, mutate) ->
            val invalid = original.deepCopy().also(mutate)
            val valid = original.deepCopy().apply { getAsJsonObject("properties").addProperty("osm_id", 42) }
            val body = JsonObject().apply {
                addProperty("type", "FeatureCollection")
                add("features", JsonArray().apply { add(invalid); add(valid) })
            }
            assertEquals(label, listOf("osm:relation:42"), DiscoveryJson.places(body.toString()).map { it.id })
        }
    }

    @Test fun `missing address fields stay empty and untrusted links never replace the OSM source`() {
        val body = JsonParser.parseString(fixture("name-sretensky")).asJsonObject
        body.getAsJsonArray("features").single().asJsonObject.getAsJsonObject("properties").apply {
            listOf("street", "housenumber", "city", "state", "county", "country").forEach { remove(it) }
            addProperty("website", "javascript:alert(1)")
            addProperty("sourceUrl", "https://untrusted.invalid/")
            addProperty("service_times", "Every day at 08:00")
        }
        val result = DiscoveryJson.places(body.toString()).single()
        assertTrue(result.address.isEmpty() && result.city.isEmpty() && result.region.isEmpty() && result.country.isEmpty())
        assertNull(result.website)
        assertEquals("https://www.openstreetmap.org/relation/3325777", result.sourceUrl)
    }

    @Test fun `malformed responses fail while a valid empty collection succeeds`() = runBlocking {
        val transport = Transport("{broken")
        val client = DefaultPlaceDiscovery(transport)
        listOf("{broken", "null", "{}", "{\"type\":\"FeatureCollection\",\"features\":{}}",
            "{\"type\":\"WrongCollection\",\"features\":[]}").forEach { invalid ->
            transport.body = invalid
            assertEquals(invalid, DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.searchPlaces("Сретенский"))
        }
        transport.body = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        assertTrue(client.searchPlaces("Сретенский").success().items.isEmpty())
    }

    @Test fun `name city and nearby caches are separate bounded slots and only explicit requests fetch`() = runBlocking {
        val cache = MemoryCache()
        val transport = Transport(fixture("name-sretensky"))
        val client = DefaultPlaceDiscovery(transport, cache, clock = TestClock())
        assertTrue(transport.requests.isEmpty())
        val original = client.searchPlaces("Москва").success()
        transport.body = fixture("cities")
        client.searchCities("Москва")
        transport.body = fixture("kazan")
        client.findChurches(GeoPoint(55.796127, 49.106414))
        val cached = client.searchPlaces("Москва").success()
        assertEquals(3, transport.requests.size)
        assertEquals(DiscoveryCacheStatus.FRESH_CACHE, cached.cacheStatus)
        assertEquals(original.items, cached.items)
        assertEquals(setOf("places", "cities", "churches"), cache.values.keys)
        transport.body = fixture("name-petersburg")
        client.searchPlaces("Казанский собор Санкт-Петербург")
        assertEquals(4, transport.requests.size)
        assertEquals(setOf("places", "cities", "churches"), cache.values.keys)
    }

    @Test fun `old name results keep their date and server failure reason without answering another query`() = runBlocking {
        val cache = MemoryCache()
        val clock = TestClock()
        val transport = Transport(fixture("name-sretensky"))
        val client = DefaultPlaceDiscovery(transport, cache, clock = clock)
        val original = client.searchPlaces("Сретенский").success()
        clock.now = clock.now.plusSeconds(86_401)
        transport.failure = DiscoveryFailure.SERVER_UNAVAILABLE
        val stale = client.searchPlaces("Сретенский").success()
        assertEquals(original.items, stale.items)
        assertEquals(original.fetchedAtEpochMillis, stale.fetchedAtEpochMillis)
        assertEquals(DiscoveryCacheStatus.STALE_CACHE, stale.cacheStatus)
        assertEquals(DiscoveryFailure.SERVER_UNAVAILABLE, stale.fallbackReason)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.SERVER_UNAVAILABLE), client.searchPlaces("Казанский"))
        assertEquals(3, transport.requests.size)
    }

    @Test fun `cancellation never presents an old name result as a successful fallback`() = runBlocking {
        val clock = TestClock()
        val transport = Transport(fixture("name-sretensky"))
        val client = DefaultPlaceDiscovery(transport, MemoryCache(), clock = clock)
        client.searchPlaces("Сретенский")
        clock.now = clock.now.plusSeconds(86_401)
        transport.cancel = true
        try {
            client.searchPlaces("Сретенский")
            fail("Explicit cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2, transport.requests.size)
    }

    @Test fun `a changed name endpoint cannot reuse the previous provider result`() = runBlocking {
        val cache = MemoryCache()
        val transport = Transport(fixture("name-sretensky"))
        val clock = TestClock()
        DefaultPlaceDiscovery(transport, cache, clock = clock).searchPlaces("Сретенский")
        val client = DefaultPlaceDiscovery(transport, cache, DiscoveryEndpoints(cities = "https://example.com/names/"), clock)
        assertEquals(DiscoveryCacheStatus.FRESH_NETWORK, client.searchPlaces("Сретенский").success().cacheStatus)
        assertEquals("example.com", URI(transport.requests.last().url).host)
        assertEquals(2, transport.requests.size)
    }

    @Test fun `nearby monasteries require the same Orthodox tags and radius as churches`() {
        val body = """{"elements":[
            {"type":"relation","id":1,"center":{"lat":55,"lon":37},"tags":{"name":"Обитель","amenity":"monastery","religion":"christian","denomination":"russian_orthodox"}},
            {"type":"relation","id":2,"center":{"lat":55,"lon":37},"tags":{"name":"Без конфессии","amenity":"monastery","religion":"christian"}},
            {"type":"relation","id":3,"center":{"lat":55,"lon":37},"tags":{"name":"Иная традиция","amenity":"monastery","religion":"christian","denomination":"catholic"}},
            {"type":"relation","id":4,"center":{"lat":56,"lon":37},"tags":{"name":"Далеко","amenity":"monastery","religion":"christian","denomination":"orthodox"}},
            {"type":"node","id":5,"lat":55,"lon":37,"tags":{"name":"Храм","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}}
        ]}"""
        val places = DiscoveryJson.churches(body, GeoPoint(55.0, 37.0), 3_000)
        assertEquals(setOf("osm:relation:1", "osm:node:5"), places.map { it.id }.toSet())
        assertEquals(DiscoveredPlaceKind.MONASTERY, places.first { it.id == "osm:relation:1" }.kind)
        assertEquals(DiscoveredPlaceKind.CHURCH, places.first { it.id == "osm:node:5" }.kind)
        val query = DefaultPlaceDiscovery.churchQuery(GeoPoint(55.0, 37.0), 3_000)
        assertTrue(query.contains("place_of_worship|monastery"))
        assertTrue(query.contains("[\"religion\"=\"christian\"]"))
        assertTrue(query.contains("[\"denomination\""))
    }
}
