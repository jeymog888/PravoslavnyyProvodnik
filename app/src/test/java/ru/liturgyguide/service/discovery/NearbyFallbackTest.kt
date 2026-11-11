package ru.liturgyguide.service.discovery

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

class NearbyFallbackTest {
    private val center = GeoPoint(55.004, 37.004)
    private class Cache : DiscoveryCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(slot: String) = values[slot]
        override fun write(slot: String, value: String) { values[slot] = value }
    }
    private class TestClock(var now: Instant = Instant.parse("2026-11-11T10:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun <T> DiscoveryResult<T>.success() = this as DiscoveryResult.Success<T>
    private fun photon(vararg points: GeoPoint): String = """{"type":"FeatureCollection","features":[""" +
        points.mapIndexed { index, point -> """{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]},"properties":{"osm_type":"W","osm_id":${index + 1},"osm_key":"amenity","osm_value":"place_of_worship","name":"Храм $index","city":"Город"}}""" }.joinToString(",") + "]}"
    private fun orthodox(point: GeoPoint) = """{"elements":[{"type":"node","id":1,"lat":${point.latitude},"lon":${point.longitude},"tags":{"name":"Храм","amenity":"place_of_worship","religion":"christian","denomination":"orthodox"}}]}"""

    @Test fun `one failed nearby request uses Photon without inventing Orthodox provenance or sending the exact position`() = runBlocking {
        val requests = mutableListOf<DiscoveryRequest>()
        val client = DefaultPlaceDiscovery(DiscoveryTransport { request ->
            requests += request
            if (request.postBody != null) throw DiscoveryException(DiscoveryFailure.SERVER_UNAVAILABLE)
            photon(center, GeoPoint(55.2, 37.0))
        })
        val result = client.findChurches(center, 500).success()
        assertEquals(2, requests.size)
        assertEquals(8_000L, requests[0].timeoutMillis)
        assertEquals(10_000L, requests[1].timeoutMillis)
        assertEquals(DiscoveryProvider.PHOTON, result.provider)
        assertEquals(GeoPoint(55.0, 37.0), result.queryCenter)
        assertEquals(500, result.radiusMeters)
        assertEquals(listOf("osm:way:1"), result.items.map { it.id })
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, result.items.single().kind)
        assertEquals("https://www.openstreetmap.org/way/1", result.items.single().sourceUrl)
        assertNull(result.items.single().website)
        val url = URLDecoder.decode(requests[1].url, "UTF-8")
        assertTrue(url.contains("lat=55.00&lon=37.00&bbox="))
        assertTrue(url.contains("q=храм"))
        assertFalse(url.contains("55.004"))
        assertFalse(url.contains("37.004"))
    }

    @Test fun `real Photon response retains source identities filters the radius and makes no denomination claim`() = runBlocking {
        val body = requireNotNull(javaClass.classLoader?.getResourceAsStream("discovery/nearby-photon-kazan.json"))
            .bufferedReader().use { it.readText() }
        val result = DefaultPlaceDiscovery(DiscoveryTransport { request ->
            if (request.postBody != null) throw DiscoveryException(DiscoveryFailure.SERVER_UNAVAILABLE)
            body
        }).findChurches(GeoPoint(55.796127, 49.106414), 3_000).success()
        assertEquals(9, result.items.size)
        assertTrue(result.items.any { it.id == "osm:way:91769591" })
        assertFalse(result.items.any { it.id == "osm:way:1337264630" })
        // This real result is Roman Catholic in OSM; Photon omits that tag. Never upgrade its category to CHURCH.
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, result.items.single { it.id == "osm:way:104635635" }.kind)
        assertEquals(DiscoveryProvider.PHOTON, result.provider)
    }

    @Test fun `fresh fallback cache does not repeatedly contact broken primary and is filtered for the local point`() = runBlocking {
        val cache = Cache()
        var calls = 0
        val transport = DiscoveryTransport { request ->
            calls++
            if (request.postBody != null) throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            photon(GeoPoint(55.004, 37.004), GeoPoint(54.996, 36.996))
        }
        val client = DefaultPlaceDiscovery(transport, cache, clock = TestClock())
        assertEquals(listOf("osm:way:1"), client.findChurches(center, 500).success().items.map { it.id })
        val cached = client.findChurches(GeoPoint(54.996, 36.996), 500).success()
        assertEquals(DiscoveryCacheStatus.FRESH_CACHE, cached.cacheStatus)
        assertEquals(DiscoveryProvider.PHOTON, cached.provider)
        assertEquals(listOf("osm:way:2"), cached.items.map { it.id })
        assertEquals(2, calls)
        assertEquals(setOf("churches"), cache.values.keys)
        assertFalse(cache.values.getValue("churches").contains("queryCenter"))
    }

    @Test fun `expired fallback remains available offline without becoming a primary result`() = runBlocking {
        val clock = TestClock()
        var offline = false
        val client = DefaultPlaceDiscovery(DiscoveryTransport { request ->
            if (offline || request.postBody != null) throw DiscoveryException(DiscoveryFailure.UNAVAILABLE)
            photon(center)
        }, Cache(), clock = clock)
        val first = client.findChurches(center).success()
        clock.now = clock.now.plusSeconds(86_401)
        offline = true
        val cached = client.findChurches(center).success()
        assertEquals(DiscoveryCacheStatus.STALE_CACHE, cached.cacheStatus)
        assertEquals(DiscoveryProvider.PHOTON, cached.provider)
        assertEquals(first.fetchedAtEpochMillis, cached.fetchedAtEpochMillis)
        assertEquals(DiscoveryFailure.UNAVAILABLE, cached.fallbackReason)
    }

    @Test fun `fresh primary cache and a valid empty response never invoke fallback`() = runBlocking {
        for (body in listOf(orthodox(center), """{"elements":[]}""")) {
            var calls = 0
            val client = DefaultPlaceDiscovery(DiscoveryTransport { calls++; body }, Cache())
            val first = client.findChurches(center).success()
            val second = client.findChurches(center).success()
            assertEquals(DiscoveryProvider.VK_MAPS, first.provider)
            assertEquals(DiscoveryCacheStatus.FRESH_CACHE, second.cacheStatus)
            assertEquals(1, calls)
        }
    }

    @Test fun `primary and fallback both obey the exact radius including rounded cell edges`() = runBlocking {
        val inside = GeoPoint(55.007, 37.004) // inside 500 m of phone, outside 500 m of rounded center
        val outside = GeoPoint(54.999, 37.004) // inside 500 m of rounded center, outside phone radius
        val primary = DefaultPlaceDiscovery(DiscoveryTransport { orthodox(inside) }).findChurches(center, 500).success()
        assertEquals(1, primary.items.size)
        val rejected = DefaultPlaceDiscovery(DiscoveryTransport { orthodox(outside) }).findChurches(center, 500).success()
        assertTrue(rejected.items.isEmpty())
        val fallback = DefaultPlaceDiscovery(DiscoveryTransport { request ->
            if (request.postBody != null) throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            photon(inside, outside)
        }).findChurches(center, 500).success()
        assertEquals(listOf("osm:way:1"), fallback.items.map { it.id })
    }

    @Test fun `different endpoint or area cannot reuse fallback cache`() = runBlocking {
        val cache = Cache()
        val clock = TestClock()
        var calls = 0
        val transport = DiscoveryTransport { request ->
            calls++
            if (request.postBody != null) throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            photon(center)
        }
        DefaultPlaceDiscovery(transport, cache, clock = clock).findChurches(center)
        DefaultPlaceDiscovery(transport, cache, DiscoveryEndpoints(cities = "https://other.example/api/"), clock).findChurches(center)
        assertEquals(4, calls)
        DefaultPlaceDiscovery(transport, cache, clock = clock).findChurches(GeoPoint(56.0, 37.0))
        assertEquals(6, calls)
    }

    @Test fun `a primary cache is not parsed as Photon after both sources fail`() = runBlocking {
        val cache = Cache()
        val clock = TestClock()
        var offline = false
        val client = DefaultPlaceDiscovery(DiscoveryTransport {
            if (offline) throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            orthodox(center)
        }, cache, clock = clock)
        client.findChurches(center)
        offline = true
        clock.now = clock.now.plusSeconds(86_401)
        val result = client.findChurches(center).success()
        assertEquals(DiscoveryCacheStatus.STALE_CACHE, result.cacheStatus)
        assertEquals(DiscoveryProvider.VK_MAPS, result.provider)
        assertEquals("osm:node:1", result.items.single().id)
    }

    @Test fun `empty or out of radius Photon cannot erase useful stale primary places on this or next offline search`() = runBlocking {
        for (emptyFallback in listOf(photon(), photon(GeoPoint(56.0, 37.0)))) {
            val cache = Cache()
            val clock = TestClock()
            var phase = 0
            val client = DefaultPlaceDiscovery(DiscoveryTransport { request ->
                if (phase == 0) orthodox(center)
                else if (phase == 1 && request.postBody == null) emptyFallback
                else throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            }, cache, clock = clock)
            client.findChurches(center)
            val originalCache = cache.values.getValue("churches")
            clock.now = clock.now.plusSeconds(86_401)
            phase = 1
            val afterEmptyFallback = client.findChurches(center).success()
            assertEquals(DiscoveryProvider.VK_MAPS, afterEmptyFallback.provider)
            assertEquals(DiscoveryCacheStatus.STALE_CACHE, afterEmptyFallback.cacheStatus)
            assertEquals("osm:node:1", afterEmptyFallback.items.single().id)
            assertEquals(originalCache, cache.values.getValue("churches"))
            phase = 2
            val offline = client.findChurches(center).success()
            assertEquals(DiscoveryProvider.VK_MAPS, offline.provider)
            assertEquals("osm:node:1", offline.items.single().id)
        }
    }

    @Test fun `rate limits and busy are terminal and cancellation never starts a fallback`() = runBlocking {
        for (reason in listOf(DiscoveryFailure.RATE_LIMITED, DiscoveryFailure.BUSY, DiscoveryFailure.INVALID_QUERY)) {
            var calls = 0
            val result = DefaultPlaceDiscovery(DiscoveryTransport { calls++; throw DiscoveryException(reason) }).findChurches(center)
            assertEquals(DiscoveryResult.Failure(reason), result)
            assertEquals(1, calls)
        }
        var calls = 0
        try {
            DefaultPlaceDiscovery(DiscoveryTransport { calls++; throw CancellationException("cancel") }).findChurches(center)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `failed fallback ends after two calls and does not invent an empty success`() = runBlocking {
        var calls = 0
        val result = DefaultPlaceDiscovery(DiscoveryTransport {
            calls++
            if (it.postBody != null) throw DiscoveryException(DiscoveryFailure.TIMEOUT)
            "{}"
        }).findChurches(center)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_RESPONSE), result)
        assertEquals(2, calls)
    }

    @Test fun `Photon bbox is ordered and bounded at poles and antimeridian`() {
        for (point in listOf(GeoPoint(90.0, 180.0), GeoPoint(-90.0, -180.0), GeoPoint(0.0, 180.0), GeoPoint(55.0, 37.0))) {
            val uri = URI(DefaultPlaceDiscovery.nearbyPlacesUrl("https://example.com/", point, 5_800))
            val bbox = uri.query.substringAfter("bbox=").substringBefore('&').split(',').map(String::toDouble)
            assertTrue(bbox.all(Double::isFinite))
            assertTrue(bbox[0] >= -180 && bbox[2] <= 180 && bbox[0] < bbox[2])
            assertTrue(bbox[1] >= -90 && bbox[3] <= 90 && bbox[1] < bbox[3])
        }
    }
}
