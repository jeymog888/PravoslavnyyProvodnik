package ru.liturgyguide.service.discovery

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class PlaceDiscoveryTest {
    private fun <T> DiscoveryResult<T>.success(): DiscoveryResult.Success<T> = this as DiscoveryResult.Success<T>
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("discovery/$name.json"))
        .bufferedReader().use { it.readText() }

    private class MemoryCache : DiscoveryCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(slot: String) = values[slot]
        override fun write(slot: String, value: String) { values[slot] = value }
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-11-08T08:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class FakeTransport(var body: String) : DiscoveryTransport {
        var failure: DiscoveryFailure? = null
        val requests = mutableListOf<DiscoveryRequest>()
        override suspend fun fetch(request: DiscoveryRequest): String {
            requests += request
            failure?.let { throw DiscoveryException(it) }
            return body
        }
    }

    @Test fun `real city response keeps ambiguous towns separate with their region`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val result = DefaultPlaceDiscovery(transport).searchCities("Казань").success()
        assertEquals(3, result.items.size)
        assertEquals("Казань", result.items[0].name)
        assertTrue(result.items[0].region.contains("Татарстан"))
        assertTrue(result.items[1].region.contains("Кировская"))
        assertNotEquals(result.items[0].id, result.items[1].id)
        assertEquals(49.1115022, result.items[0].center.longitude, 0.000001)
        assertEquals(DiscoveryCacheStatus.FRESH_NETWORK, result.cacheStatus)
        assertEquals(DiscoveryProvider.PHOTON, result.provider)
    }

    @Test fun `city query is encoded once without changing parameters`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        DefaultPlaceDiscovery(transport).searchCities("Казань &limit=999 # тест")
        val url = transport.requests.single().url
        assertTrue(url.contains("%26limit%3D999"))
        assertTrue(url.contains("%23"))
        assertEquals(1, Regex("&limit=").findAll(url).count())
        assertTrue(url.endsWith("&layer=city&limit=5&lang=default"))
        assertNull(transport.requests.single().postBody)
    }

    @Test fun `invalid query coordinates and radius never send requests`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val client = DefaultPlaceDiscovery(transport)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchCities(" "))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchCities("а".repeat(121)))
        listOf(GeoPoint(Double.NaN, 30.0), GeoPoint(91.0, 30.0), GeoPoint(55.0, Double.POSITIVE_INFINITY)).forEach {
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.findChurches(it))
        }
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.findChurches(GeoPoint(55.0, 30.0), 20_000))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `real churches use orthodox tags and stable source IDs without inferring schedule`() = runBlocking {
        val transport = FakeTransport(fixture("kazan"))
        val result = DefaultPlaceDiscovery(transport).findChurches(GeoPoint(55.796127, 49.106414)).success()
        assertTrue(result.items.size >= 10)
        assertTrue(result.items.any { it.name.contains("Введенская") })
        assertTrue(result.items.all { it.id.startsWith("osm:") && it.sourceUrl.startsWith("https://www.openstreetmap.org/") })
        assertEquals(result.items.size, result.items.map { it.id }.distinct().size)
        assertEquals(GeoPoint(55.80, 49.11), result.queryCenter)
        assertEquals(3_000, result.radiusMeters)
        val query = URLDecoder.decode(transport.requests.single().postBody!!.removePrefix("data="), "UTF-8")
        assertTrue(query.contains("around:3800,55.80,49.11"))
        assertFalse(query.contains("55.796127"))
        assertTrue(query.contains("out center tags 60"))
        assertFalse(query.contains(".*orthodox.*"))
        val distances = result.items.map { GeoPoint(55.796127, 49.106414).distanceMeters(it.center) }
        assertTrue(distances.all { it <= 3_000 })
        assertEquals(distances.sorted(), distances)
    }

    @Test fun `real Petersburg response removes unnamed objects and bell towers`() {
        val result = DiscoveryJson.churches(fixture("spb"), GeoPoint(59.94, 30.32), 3_000)
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.name.contains("Казанский", ignoreCase = true) })
        assertTrue(result.all { it.name.isNotBlank() })
        assertTrue(result.all { GeoPoint(59.94, 30.32).distanceMeters(it.center) <= 3_050 })
    }

    @Test fun `malformed and ineligible elements are rejected individually`() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":55,"lon":37,"tags":{"name":"Храм","amenity":"place_of_worship","religion":"christian","denomination":"orthodox","website":"javascript:alert(1)"}},
            {"type":"node","id":2,"lat":55,"lon":37,"tags":{"name":"Католический","amenity":"place_of_worship","religion":"christian","denomination":"catholic"}},
            {"type":"node","id":3,"lat":55,"lon":37,"tags":{"name":"Другой","amenity":"place_of_worship","religion":"christian","denomination":"orthodox_presbyterian_church"}},
            {"type":"node","id":4,"lat":55,"lon":37,"tags":{"name":"Коптский","amenity":"place_of_worship","religion":"christian","denomination":"coptic_orthodox"}},
            {"type":"node","id":5,"lat":55,"lon":37,"tags":{"name":"Колокольня","amenity":"place_of_worship","religion":"christian","denomination":"orthodox","tower:type":"bell_tower"}},
            {"type":"node","id":6,"lat":99,"lon":37,"tags":{"name":"Неверное место","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}},
            {"type":"node","id":7,"lat":55,"lon":37,"tags":{"name":"","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}},
            {"type":"way","id":8,"center":{"lat":55,"lon":37},"tags":{"name":"Часовня","amenity":"place_of_worship","religion":"christian","denomination":"russian_orthodox","website":"https://user:pass@example.com/","contact:website":"https://example.com/parish"}},
            {"type":"relation","id":9,"center":{"lat":55,"lon":37},"tags":{"name":"Далеко","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}},
            {"type":"node","id":1,"lat":55,"lon":37,"tags":{"name":"Дубликат","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}}
        ]}"""
        val result = DiscoveryJson.churches(json, GeoPoint(55.0, 37.0), 3_000)
        assertEquals(listOf("osm:node:1", "osm:way:8", "osm:relation:9"), result.map { it.id })
        assertNull(result[0].website)
        assertTrue(result[1].isChapel)
        assertEquals("https://example.com/parish", result[1].website)
    }

    @Test fun `partial Overpass response and invalid JSON are failures rather than empty success`() = runBlocking {
        val transport = FakeTransport("{\"remark\":\"runtime error: timeout\",\"elements\":[]}")
        val client = DefaultPlaceDiscovery(transport)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.findChurches(GeoPoint(55.0, 37.0)))
        transport.body = "{\"remark\":\"" + "timeout ".repeat(100) + "\",\"elements\":[]}"
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.findChurches(GeoPoint(55.0, 37.0)))
        transport.body = "{\"remark\":123,\"elements\":[]}"
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.findChurches(GeoPoint(55.0, 37.0)))
        transport.body = "{broken"
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.searchCities("Москва"))
        transport.body = "{}"
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), client.searchCities("Москва"))
    }

    @Test fun `malformed city geometry cannot become a selectable zero coordinate`() {
        val source = JsonParser.parseString(fixture("cities")).asJsonObject
        val features = source.getAsJsonArray("features")
        features[0].asJsonObject.getAsJsonObject("geometry").addProperty("type", "Polygon")
        features[1].asJsonObject.getAsJsonObject("geometry").getAsJsonArray("coordinates").set(1, com.google.gson.JsonPrimitive(99))
        features[2].asJsonObject.getAsJsonObject("properties").addProperty("osm_id", -1)
        assertTrue(DiscoveryJson.cities(source.toString()).isEmpty())
    }

    @Test fun `fresh cache avoids a second request but a different query cannot reuse it`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val cache = MemoryCache()
        val client = DefaultPlaceDiscovery(transport, cache)
        client.searchCities("Казань")
        val cached = client.searchCities("Казань").success()
        assertEquals(DiscoveryCacheStatus.FRESH_CACHE, cached.cacheStatus)
        assertEquals(1, transport.requests.size)
        client.searchCities("Москва")
        assertEquals(2, transport.requests.size)
        assertEquals(setOf("cities"), cache.values.keys)
    }

    @Test fun `expired cache survives network failure with its old date and an explicit reason`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val cache = MemoryCache()
        val clock = MutableClock()
        val client = DefaultPlaceDiscovery(transport, cache, clock = clock)
        val original = client.searchCities("Казань").success()
        clock.now = clock.now.plusSeconds(86_401)
        transport.failure = DiscoveryFailure.TIMEOUT
        val offline = client.searchCities("Казань").success()
        assertEquals(DiscoveryCacheStatus.STALE_CACHE, offline.cacheStatus)
        assertEquals(DiscoveryFailure.TIMEOUT, offline.fallbackReason)
        assertEquals(original.fetchedAtEpochMillis, offline.fetchedAtEpochMillis)
        assertEquals(original.items, offline.items)
        assertEquals(2, transport.requests.size)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.TIMEOUT), client.searchCities("Москва"))
    }

    @Test fun `cache older than seven days or from the future is never used`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val cache = MemoryCache()
        val clock = MutableClock()
        val client = DefaultPlaceDiscovery(transport, cache, clock = clock)
        client.searchCities("Казань")
        transport.failure = DiscoveryFailure.UNAVAILABLE
        clock.now = clock.now.minusSeconds(1)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchCities("Казань"))
        clock.now = clock.now.plusSeconds(8 * 86_400)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchCities("Казань"))
    }

    @Test fun `broken cache and failed cache writes cannot break a valid search`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val cache = object : DiscoveryCacheStore {
            override fun read(slot: String): String = "{broken"
            override fun write(slot: String, value: String) { error("Full storage") }
        }
        assertTrue(DefaultPlaceDiscovery(transport, cache).searchCities("Казань") is DiscoveryResult.Success)
    }

    @Test fun `large responses are rejected even with an injected transport`() = runBlocking {
        val transport = FakeTransport("а".repeat(DefaultPlaceDiscovery.MAX_RESPONSE_BYTES / 2 + 1))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.TOO_LARGE), DefaultPlaceDiscovery(transport).searchCities("Казань"))
    }

    @Test fun `cancellation is not converted to an offline result`() = runBlocking {
        val client = DefaultPlaceDiscovery(DiscoveryTransport { throw CancellationException("user cancelled") })
        try {
            client.searchCities("Казань")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `changed provider cannot reuse results from the previous endpoint`() = runBlocking {
        val transport = FakeTransport(fixture("cities"))
        val cache = MemoryCache()
        DefaultPlaceDiscovery(transport, cache).searchCities("Казань")
        val other = DefaultPlaceDiscovery(transport, cache, DiscoveryEndpoints(cities = "https://example.com/cities/"))
        val result = other.searchCities("Казань").success()
        assertEquals(DiscoveryCacheStatus.FRESH_NETWORK, result.cacheStatus)
        assertEquals(2, transport.requests.size)
    }

    @Test fun `query centers and haversine remain valid at map boundaries`() {
        assertEquals(GeoPoint(90.0, 180.0), GeoPoint(89.999999, 179.999999).searchAreaCenter())
        assertEquals(GeoPoint(-90.0, -180.0), GeoPoint(-89.999999, -179.999999).searchAreaCenter())
        assertEquals(0.0, GeoPoint(0.0, 0.0).distanceMeters(GeoPoint(0.0, 0.0)), 0.001)
        assertTrue(GeoPoint(0.0, 179.99).distanceMeters(GeoPoint(0.0, -179.99)) < 3_000)
        assertTrue(GeoPoint(0.0, 0.0).distanceMeters(GeoPoint(0.0, 180.0)).isFinite())
    }
}
