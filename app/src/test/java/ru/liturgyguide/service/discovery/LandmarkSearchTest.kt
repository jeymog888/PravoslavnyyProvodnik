package ru.liturgyguide.service.discovery

import com.google.gson.JsonParser
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

class LandmarkSearchTest {
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("discovery/$name.json"))
        .bufferedReader().use { it.readText() }
    private val direct get() = fixture("landmark-sukharevskaya-direct")
    private val stations get() = fixture("landmark-sukharevskaya-station")
    private val nearby get() = fixture("landmark-sukharevskaya-targeted-photon")
    private fun <T> DiscoveryResult<T>.success() = this as DiscoveryResult.Success<T>
    private fun query(request: DiscoveryRequest) = URLDecoder.decode(URI(request.url).rawQuery.orEmpty(), "UTF-8")
    private class Cache : DiscoveryCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(slot: String) = values[slot]
        override fun write(slot: String, value: String) { values[slot] = value }
    }
    private class TestClock(var value: Instant = Instant.parse("2026-11-11T09:00:00Z")) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test fun `explicit landmark parsing preserves multiword station and church name on either side`() {
        assertEquals(LandmarkQuery("сухаревская", "троицы"), LandmarkSearch.parse("метро сухаревская троицы"))
        assertEquals(LandmarkQuery("Сухаревская", "Храм Троицы у"), LandmarkSearch.parse("Храм Троицы у м. Сухаревская"))
        assertEquals(LandmarkQuery("Площадь Александра Невского", "храм"), LandmarkSearch.parse("метро Площадь Александра Невского храм"))
        assertEquals(LandmarkQuery("Троицкая", "храм Николая"), LandmarkSearch.parse("метро \"Троицкая\" храм Николая"))
        assertEquals(LandmarkQuery("Троицкая", "храм Николая"), LandmarkSearch.parse("метро Троицкая храм Николая"))
        assertNull(LandmarkSearch.parse("Сретенский монастырь Москва"))
        assertNull(LandmarkSearch.parse("Храм метро"))
        assertNull(LandmarkSearch.parse("метро Сухаревская метро Тургеневская"))
        assertTrue(requireNotNull(LandmarkSearch.parse("Казань метро Центральная храм Троицы")).requiresChoice)
    }

    @Test fun `real nonempty wrong direct result is extended with Trinity in Listy via named nearby fallback`() = runBlocking {
        val requests = mutableListOf<DiscoveryRequest>()
        val pauseCounts = mutableListOf<Int>()
        val transport = DiscoveryTransport { request ->
            requests += request
            when {
                request.postBody != null -> throw DiscoveryException(DiscoveryFailure.TIMEOUT)
                query(request).contains("bbox=") -> nearby
                query(request).contains("include=") -> direct
                else -> stations
            }
        }
        val result = DefaultPlaceDiscovery(transport, relatedRequestPause = { pauseCounts += requests.size })
            .searchPlaces("метро сухаревская троицы").success()
        assertEquals("osm:relation:19595736", result.items.first().id)
        assertTrue(result.items.first().address.contains("Сретенка"))
        assertTrue(result.items.any { it.id == "osm:node:5053943230" && it.address.contains("Сухаревская") })
        assertEquals(result.items.size, result.items.distinctBy { it.id }.size)
        assertTrue(result.items.all { requireNotNull(result.landmark).center.distanceMeters(it.center) <= 2_000 })
        assertEquals("Сухаревская", result.landmark?.name)
        assertEquals(listOf(1, 2), pauseCounts)
        assertEquals(4, requests.size)
        assertTrue(query(requests.last()).contains("q=троицы"))
        assertFalse("No GPS or global fallback", query(requests.last()).contains("q=храм"))
    }

    @Test fun `stations parser ignores cafes roads platforms and malformed identities`() {
        val result = DiscoveryJson.landmarks(stations)
        assertEquals(listOf("osm:node:277499849"), result.map { it.id })
        assertEquals("Москва", result.single().city)
        val altered = JsonParser.parseString(stations).asJsonObject
        altered.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("geometry")
            .getAsJsonArray("coordinates").set(0, com.google.gson.JsonPrimitive(300))
        assertTrue(DiscoveryJson.landmarks(altered.toString()).isEmpty())
    }

    @Test fun `a single station from another city must still require explicit selection`() = runBlocking {
        val wrongCity = JsonParser.parseString(stations).asJsonObject
        wrongCity.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("properties").apply {
            addProperty("name", "Центральная"); addProperty("city", "Воронеж")
        }
        var requests = 0
        val client = DefaultPlaceDiscovery(DiscoveryTransport {
            requests++
            if (requests == 1) direct else wrongCity.toString()
        }, relatedRequestPause = {})
        val result = client.searchPlaces("метро Центральная Казань храм Троицы").success()
        assertEquals(2, requests)
        assertNull(result.landmark)
        assertEquals("Воронеж", result.landmarkChoices.single().city)
        val station = result.landmarkChoices.single()
        assertFalse(LandmarkSearch.exactName(station, "Центральная Казань"))
        assertTrue(LandmarkSearch.exactName(station, "Центральная Воронеж"))
    }

    @Test fun `same station name in different cities requires a choice without nearby or phone location`() = runBlocking {
        val root = JsonParser.parseString(stations).asJsonObject
        val first = root.getAsJsonArray("features")[0].asJsonObject.deepCopy()
        first.getAsJsonObject("properties").apply { addProperty("name", "Центральная"); addProperty("city", "Казань") }
        val second = first.deepCopy()
        second.getAsJsonObject("properties").apply { addProperty("osm_id", 42); addProperty("city", "Воронеж") }
        second.getAsJsonObject("geometry").getAsJsonArray("coordinates").apply {
            set(0, com.google.gson.JsonPrimitive(39.2)); set(1, com.google.gson.JsonPrimitive(51.6))
        }
        root.add("features", com.google.gson.JsonArray().apply { add(first); add(second) })
        val requests = mutableListOf<DiscoveryRequest>()
        val client = DefaultPlaceDiscovery(DiscoveryTransport {
            requests += it
            if (requests.size == 1) direct else root.toString()
        }, relatedRequestPause = {})
        val result = client.searchPlaces("метро Центральная храм Троицы").success()
        assertEquals(listOf("Казань", "Воронеж"), result.landmarkChoices.map { it.city })
        assertNull(result.landmark)
        assertNull(result.queryCenter)
        assertEquals(2, requests.size)
        assertEquals("храм Троицы", result.landmarkChurchQuery)
    }

    @Test fun `rate limit and explicit cancellation never start a follow-up lookup`() = runBlocking {
        var count = 0
        val limited = DefaultPlaceDiscovery(DiscoveryTransport { count++; throw DiscoveryException(DiscoveryFailure.RATE_LIMITED) }, relatedRequestPause = {})
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.RATE_LIMITED), limited.searchPlaces("метро Сухаревская троицы"))
        assertEquals(1, count)
        count = 0
        val cancelled = DefaultPlaceDiscovery(DiscoveryTransport { count++; direct }, relatedRequestPause = { throw CancellationException() })
        try { cancelled.searchPlaces("метро Сухаревская троицы"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { assertEquals(1, count) }
    }

    @Test fun `fresh and offline landmark caches remain bounded and keep original dates`() = runBlocking {
        val cache = Cache()
        val clock = TestClock()
        var offline = false
        var count = 0
        var pauses = 0
        val client = DefaultPlaceDiscovery(DiscoveryTransport { request ->
            count++
            if (offline) throw DiscoveryException(DiscoveryFailure.UNAVAILABLE)
            when {
                request.postBody != null -> throw DiscoveryException(DiscoveryFailure.TIMEOUT)
                query(request).contains("bbox=") -> nearby
                query(request).contains("include=") -> direct
                else -> stations
            }
        }, cache, clock = clock, relatedRequestPause = { pauses++ })
        val original = client.searchPlaces("метро Сухаревская троицы").success()
        assertEquals(4, count)
        assertEquals(2, pauses)
        val fresh = client.searchPlaces("метро Сухаревская троицы").success()
        assertEquals(4, count)
        assertEquals("Cached search should be immediate", 2, pauses)
        assertEquals(DiscoveryCacheStatus.FRESH_CACHE, fresh.cacheStatus)
        offline = true
        clock.value = clock.value.plusSeconds(86_401)
        val stale = client.searchPlaces("метро Сухаревская троицы").success()
        assertEquals(original.items, stale.items)
        assertEquals(original.fetchedAtEpochMillis, stale.fetchedAtEpochMillis)
        assertEquals(DiscoveryCacheStatus.STALE_CACHE, stale.cacheStatus)
        assertEquals(setOf("places", "landmarks", "churches"), cache.values.keys)
    }

    @Test fun `landmark network failure preserves direct candidate and explains incomplete search`() = runBlocking {
        var count = 0
        val result = DefaultPlaceDiscovery(DiscoveryTransport {
            if (count++ == 0) direct else throw DiscoveryException(DiscoveryFailure.TIMEOUT)
        }, relatedRequestPause = {}).searchPlaces("метро Сухаревская троицы").success()
        assertEquals(listOf("osm:node:5053943230"), result.items.map { it.id })
        assertTrue(requireNotNull(result.notice).contains("Не удалось проверить станцию"))
        assertNull(result.landmark)
        assertEquals(2, count)
    }
}
