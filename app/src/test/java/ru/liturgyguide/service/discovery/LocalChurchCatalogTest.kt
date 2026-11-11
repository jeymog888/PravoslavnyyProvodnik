package ru.liturgyguide.service.discovery

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class LocalChurchCatalogTest {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val dump = "2026-11-10T21:00:00Z"
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val center = GeoPoint(55.77, 37.63)
    private val gson = Gson()
    private fun record(id: String = "osm:way:1", name: String = "Храм Живоначальной Троицы в Листах",
        latitude: Double = center.latitude, longitude: Double = center.longitude, city: String = "Москва",
        aliases: List<String> = emptyList(), website: String? = "https://church.example/schedule") = mutableMapOf<String, Any?>(
        "id" to id, "name" to name, "center" to mapOf("latitude" to latitude, "longitude" to longitude),
        "address" to "Сретенка, 27", "city" to city, "region" to "", "country" to "RU", "aliases" to aliases,
        "website" to website, "isChapel" to false, "kind" to "CHURCH",
        "sourceUrl" to "https://www.openstreetmap.org/" + id.removePrefix("osm:").replace(':', '/'),
        "sourceTags" to mapOf("amenity" to "place_of_worship", "religion" to "christian", "denomination" to "orthodox", "name" to name))
    private fun body(vararg records: Map<String, Any?>): String = gson.toJson(mapOf("schemaVersion" to 1,
        "metadata" to mapOf("recordCount" to records.size, "source" to mapOf(
            "name" to "OpenStreetMap via Geofabrik", "url" to "https://download.geofabrik.de/russia-261110.osm.pbf",
            "fileName" to "russia-261110.osm.pbf", "sizeBytes" to 4_202_162_536L, "sha256" to "a".repeat(64),
            "dumpTimestamp" to dump, "timestampOrigin" to "osm_header", "license" to "ODbL-1.0",
            "licenseUrl" to "https://opendatacommons.org/licenses/odbl/1-0/", "attribution" to "© OpenStreetMap contributors",
            "copyrightUrl" to DefaultPlaceDiscovery.COPYRIGHT_URL)), "churches" to records.toList()))
    private fun source(json: String) = BundledChurchCatalog(open = { StringReader(json) }, clock = clock)
    private fun decode(json: String) = LocalChurchCatalog.decode(StringReader(json), now)
    private fun DiscoveryResult<DiscoveredChurch>.success() = this as DiscoveryResult.Success<DiscoveredChurch>

    private class Network : PlaceDiscovery {
        val names = mutableListOf<String>()
        val nearby = mutableListOf<Pair<GeoPoint, Int>>()
        val cities = mutableListOf<String>()
        var landmarks = 0
        override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> {
            names += query; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
        override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> {
            cities += query; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
        override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> {
            nearby += center to radiusMeters; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
        override suspend fun findChurchesNearLandmark(landmark: DiscoveryLandmark, churchQuery: String): DiscoveryResult<DiscoveredChurch> {
            landmarks++; return DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE)
        }
    }

    @Test fun nearbyWorksWithoutNetworkAndPreservesExactIdentityWebsiteDistanceAndDumpDate() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(record(id = "osm:way:2", latitude = 55.772), record(),
            record(id = "osm:way:3", latitude = 55.79))), network)
        val result = client.findChurches(center, 500).success()
        assertEquals(listOf("osm:way:1", "osm:way:2"), result.items.map { it.id })
        assertTrue(result.items.all { center.distanceMeters(it.center) <= 500 })
        assertEquals("https://church.example/schedule", result.items.first().website)
        assertEquals("https://www.openstreetmap.org/way/1", result.items.first().sourceUrl)
        assertEquals(DiscoveryProvider.LOCAL_CATALOG, result.provider)
        assertEquals(DiscoveryCacheStatus.LOCAL_SNAPSHOT, result.cacheStatus)
        assertEquals(Instant.parse(dump).toEpochMilli(), result.fetchedAtEpochMillis)
        assertEquals(center.searchAreaCenter(), result.queryCenter)
        assertTrue(network.nearby.isEmpty())
    }

    @Test fun namespacesAreNotMergedAndNoScheduleIsCreatedFromSourceTags() {
        val records = listOf("node", "way", "relation").map { kind -> record("osm:$kind:42").apply {
            this["sourceTags"] = mapOf("amenity" to "place_of_worship", "religion" to "christian", "denomination" to "orthodox",
                "service_times" to "Sunday 09:00")
        } }
        val result = decode(body(*records.toTypedArray())).nearby(center, 500) {}
        assertEquals(setOf("osm:node:42", "osm:way:42", "osm:relation:42"), result.map { it.id }.toSet())
        assertEquals(3, result.size)
    }

    @Test fun localNearbyKeepsChurchesBeyondTheNetworkLimitForSubsequentNameFiltering() = runBlocking {
        val network = Network()
        val records = (1..75).map { index -> record("osm:way:$index", name = "Храм $index",
            latitude = center.latitude + index * 0.0001) }
        val outside = record("osm:way:76", name = "За радиусом", latitude = center.latitude + 0.1)
        val client = CatalogPlaceDiscovery(source(body(*(records + outside).toTypedArray())), network)
        val result = client.findChurches(center, 3_000).success()
        assertEquals(75, result.items.size)
        assertEquals(listOf("osm:way:75"), result.items.filter { it.name.contains("75") }.map { it.id })
        assertTrue(result.items.none { it.id == "osm:way:76" })
        val distances = result.items.map { center.distanceMeters(it.center) }
        assertEquals(distances.sorted(), distances)
        assertTrue(distances.all { it <= 3_000 })
        assertTrue(network.nearby.isEmpty())
    }

    @Test fun namesAliasesRussianInflectionsAndCityMatchLocallyWithoutDroppingRequestedWords() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(record(aliases = listOf("Троицкая церковь на Рёвине")),
            record("osm:way:2", city = "Казань"), record("osm:way:3", city = "", name = "Храм Троицы Москвы"))), network)
        assertEquals(listOf("osm:way:1"), client.searchPlaces("храм Троицы Москва").success().items.map { it.id })
        assertEquals(listOf("osm:way:1"), client.searchPlaces("ТРОИЦКАЯ Ревине Москва").success().items.map { it.id })
        assertTrue(network.names.isEmpty())
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces("Троицы Москва неизвестное"))
        assertEquals(listOf("Троицы Москва неизвестное"), network.names)
    }

    @Test fun aKnownCityRequiresPublishedCityEvidenceRatherThanItsNameInsideAnotherChurch() = runBlocking {
        val client = CatalogPlaceDiscovery(source(body(record(city = "Москва"),
            record("osm:way:2", name = "Храм Троицы. Москва", city = "Казань"),
            record("osm:way:3", name = "Храм Троицы. Москва", city = ""))), Network())
        val found = client.searchPlaces("Троицы Москва").success()
        assertEquals(listOf("osm:way:1"), found.items.map { it.id })
    }

    @Test fun inflectedCityNamesCannotBeSuppliedByTheDedicationOfAChurchElsewhereOrWithUnknownCity() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(record(city = "Москва"),
            record("osm:way:2", name = "Храм Троицы Москвы", city = "Казань"),
            record("osm:way:3", name = "Храм Троицы Москвы", city = ""))), network)
        for (query in listOf("Троицы в Москве", "Троицы Москвы", "Троицы Москва")) {
            assertEquals(query, listOf("osm:way:1"), client.searchPlaces(query).success().items.map { it.id })
        }
        assertTrue(network.names.isEmpty())

        // The catalog knows this city, but no matching church has evidence of being there.
        val noLocalMatch = CatalogPlaceDiscovery(source(body(record(name = "Никольский храм", city = "Москва"),
            record("osm:way:3", name = "Храм Троицы Москвы", city = ""))), network)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), noLocalMatch.searchPlaces("Троицы в Москве"))
        assertEquals(listOf("Троицы в Москве"), network.names)
    }

    @Test fun fullMultiwordCityInflectionIsSupportedButAnAdjectiveDoesNotBecomeACityConstraint() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(
            record("osm:way:1", city = "Нижний Новгород"),
            record("osm:way:2", name = "Храм Троицы Нижнего Новгорода", city = "Москва"),
            record("osm:way:3", city = "Казань").apply { this["address"] = "Московская улица, 1" })), network)
        assertEquals(listOf("osm:way:1"), client.searchPlaces("Троицы в Нижнем Новгороде").success().items.map { it.id })
        assertEquals(listOf("osm:way:3"), client.searchPlaces("Троицы Московская").success().items.map { it.id })
        assertTrue(network.names.isEmpty())
    }

    @Test fun aDedicationDoesNotBecomeTheSimilarlyNamedVillage() = runBlocking {
        val client = CatalogPlaceDiscovery(source(body(
            record("osm:way:1", name = "Сретенский монастырь", city = ""),
            record("osm:way:2", name = "Храм Николая", city = "Сретенское"),
            record("osm:way:3", name = "Никольский храм", city = ""),
            record("osm:way:4", name = "Храм Троицы", city = "Никольское"))), Network())
        // Broad word search can still include the village as a secondary address match,
        // but it must not hide the monastery or rank that address above its exact name.
        assertEquals(listOf("osm:way:1", "osm:way:2"), client.searchPlaces("Сретенский").success().items.map { it.id })
        assertEquals(listOf("osm:way:3", "osm:way:4"), client.searchPlaces("Никольского храма").success().items.map { it.id }.sorted())
        assertEquals(listOf("osm:way:2"), client.searchPlaces("Николая в Сретенском").success().items.map { it.id })
    }

    @Test fun moreThanFortyValidAliasesRemainSearchableWithinTheImporterFormatBounds() = runBlocking {
        val aliases = (1..41).map { "Название$it" }
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(record(aliases = aliases))), network)
        assertEquals(listOf("osm:way:1"), client.searchPlaces("Название41 Москва").success().items.map { it.id })
        assertTrue(network.names.isEmpty())
        val excessiveCharacters = (1..55).map { "$it".padEnd(300, 'я') }
        assertNull(source(body(record(aliases = excessiveCharacters))).get())
        assertNull(source(body(record(aliases = (1..8_193).map(Int::toString)))).get())
    }

    @Test fun explicitMetroSearchNeverDropsTheStationOrOverridesItsNetworkOutcome() = runBlocking {
        val network = Network()
        var reads = 0
        val source = BundledChurchCatalog(open = { reads++; StringReader(body(record())) }, clock = clock)
        val client = CatalogPlaceDiscovery(source, network)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), client.searchPlaces("метро Сухаревская Троицы"))
        assertEquals(listOf("метро Сухаревская Троицы"), network.names)
        assertEquals(0, reads)
        client.searchCities("Москва")
        assertEquals(listOf("Москва"), network.cities)
    }

    @Test fun anAlreadySelectedStationCanUseRealNearbyCatalogPointsWithoutASecondNetworkRequest() = runBlocking {
        val network = Network()
        val client = CatalogPlaceDiscovery(source(body(record())), network)
        val station = DiscoveryLandmark("osm:node:8", "Сухаревская", center, "Москва", "", "RU")
        val found = client.findChurchesNearLandmark(station, "Троицы").success()
        assertEquals(station, found.landmark)
        assertEquals("Троицы", found.landmarkChurchQuery)
        assertEquals(listOf("osm:way:1"), found.items.map { it.id })
        assertEquals(0, network.landmarks)
    }

    @Test fun noLocalResultsOrMissingAssetUseTheExistingNetworkPathWithoutAFalseEmptySuccess() = runBlocking {
        val network = Network()
        val absent = CatalogPlaceDiscovery(BundledChurchCatalog(open = { throw IOException("Missing asset") }, clock = clock), network)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), absent.findChurches(center, 500))
        val local = CatalogPlaceDiscovery(source(body(record())), network)
        val other = GeoPoint(48.85, 2.35)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), local.findChurches(other, 500))
        assertEquals(listOf(center to 500, other to 500), network.nearby)
    }

    @Test fun malformedMetadataCountIdentityCoordinatesOrConfessionRejectTheWholeSnapshot() = runBlocking {
        val valid = body(record())
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("schemaVersion", 2) },
            { it.getAsJsonObject("metadata").addProperty("recordCount", 2) },
            { it.getAsJsonObject("metadata").addProperty("recordCount", 1.5) },
            { it.getAsJsonObject("metadata").getAsJsonObject("source").addProperty("sha256", "bad") },
            { it.getAsJsonObject("metadata").getAsJsonObject("source").addProperty("dumpTimestamp", "2030-01-01T00:00:00Z") },
            { it.getAsJsonObject("metadata").getAsJsonObject("source").addProperty("url", "https://other.example/russia.osm.pbf") },
            { it.getAsJsonArray("churches")[0].asJsonObject.addProperty("id", "map:way:1") },
            { it.getAsJsonArray("churches")[0].asJsonObject.addProperty("sourceUrl", "https://www.openstreetmap.org/node/1") },
            { it.getAsJsonArray("churches")[0].asJsonObject.getAsJsonObject("center").addProperty("latitude", 91) },
            { it.getAsJsonArray("churches")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("denomination", "catholic") },
            { it.getAsJsonArray("churches")[0].asJsonObject.getAsJsonObject("sourceTags").addProperty("religion", "muslim") }
        )
        for (change in changes) {
            val json = JsonParser.parseString(valid).asJsonObject.also(change).toString()
            val network = Network()
            assertEquals(DiscoveryResult.Failure(DiscoveryFailure.UNAVAILABLE), CatalogPlaceDiscovery(source(json), network).findChurches(center, 500))
            assertEquals(1, network.nearby.size)
        }
        assertNull(source(valid.dropLast(8)).get())
        assertNull(source(body(record(), record())).get())
    }

    @Test fun unconfirmedChristianPlaceIsRetainedOnlyWithItsHonestCategory() {
        val unknown = record().apply {
            this["kind"] = "WORSHIP_PLACE"
            this["sourceTags"] = mapOf("amenity" to "place_of_worship", "religion" to "christian")
        }
        assertEquals(DiscoveredPlaceKind.WORSHIP_PLACE, decode(body(unknown)).nearby(center, 500) {}.single().kind)
    }

    @Test fun unsafeWebsiteIsRemovedWithoutDroppingTheCorrectChurchAndPublishedHttpIsPreserved() {
        for (website in listOf("javascript:alert(1)", "https://127.0.0.1/", "https://church.local/", "https://user:pass@church.example/", "https://church.example:8443/")) {
            assertNull(decode(body(record(website = website))).nearby(center, 500) {}.single().website)
        }
        assertEquals("http://church.example/schedule", decode(body(record(website = "http://church.example/schedule")))
            .nearby(center, 500) {}.single().website)
    }

    @Test fun invalidQueriesNeverLoadTheCatalogOrSendCoordinatesToNetwork() = runBlocking {
        var reads = 0
        val network = Network()
        val client = CatalogPlaceDiscovery(BundledChurchCatalog(open = { reads++; StringReader(body(record())) }, clock = clock), network)
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.findChurches(GeoPoint(Double.NaN, 37.0), 500))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.findChurches(center, 6_000))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchPlaces("x"))
        assertEquals(DiscoveryResult.Failure(DiscoveryFailure.INVALID_QUERY), client.searchPlaces("храм\u0000"))
        assertEquals(0, reads); assertTrue(network.nearby.isEmpty()); assertTrue(network.names.isEmpty())
    }

    @Test fun lazyLoadingIsSharedAcrossConcurrentSearchesAndOccursAwayFromTheCallerThread() = runBlocking {
        val reads = AtomicInteger()
        val caller = Thread.currentThread()
        val source = BundledChurchCatalog(open = {
            assertNotSame(caller, Thread.currentThread())
            reads.incrementAndGet(); StringReader(body(record()))
        }, clock = clock)
        val client = CatalogPlaceDiscovery(source, Network())
        coroutineScope { (1..10).map { async { client.findChurches(center, 500).success() } }.awaitAll() }
        assertEquals(1, reads.get())
    }

    @Test fun cancellationIsNotCachedAsACompletelyMissingCatalog() = runBlocking {
        var opens = 0
        val source = BundledChurchCatalog(open = {
            opens++
            if (opens == 1) throw CancellationException("Cancelled first read")
            StringReader(body(record()))
        }, clock = clock)
        try { source.get(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertNotNull(source.get())
        assertEquals(2, opens)
    }
}
