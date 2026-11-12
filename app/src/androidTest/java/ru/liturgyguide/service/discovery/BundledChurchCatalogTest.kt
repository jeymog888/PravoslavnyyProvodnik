package ru.liturgyguide.service.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.ui.toSavedChurch
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

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

    @Test fun linkedPublishedNamesRecoverTheActualBuildingWithoutMergingItsNeighbor() = runBlocking<Unit> {
        val nikolskyId = "osm:way:77503842"
        val tolgskyId = "osm:way:192294657"
        val officialPoint = GeoPoint(51.6596060, 39.2126847)
        val expectedCenter = GeoPoint(51.65962070, 39.21276430)
        val preferenceName = "linked-catalog-acceptance-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, 0)
        val queryReport = mutableListOf<Map<String, Any>>()
        val receipt = linkedMapOf<String, Any>(
            "passed" to false, "expectedId" to nikolskyId, "neighborId" to tolgskyId,
            "officialContactPoint" to officialPoint, "expectedOsmBboxCenter" to expectedCenter,
            "queries" to queryReport,
            "scope" to "Bundled asset, saved records and recreated repositories; no UI or process-death claim"
        )
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(BundledChurchCatalog.ASSET_PATH).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            receipt["assetSha256"] = digest.digest().joinToString("") { "%02x".format(it) }
            val discovery = newDiscovery()
            var selected: DiscoveredChurch? = null
            for (query in listOf("Никольский Воронеж", "Николая Воронеж", "Воронеж Таранченко 19а")) {
                val observation = linkedMapOf<String, Any>("query" to query)
                queryReport += observation
                val started = System.nanoTime()
                val result = try {
                    success(discovery.searchPlaces(query))
                } finally {
                    observation["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000
                }
                observation["resultIds"] = result.items.map { it.id }
                assertEquals("$query must identify the exact OSM building once", 1,
                    result.items.count { it.id == nikolskyId })
                val actual = result.items.single { it.id == nikolskyId }
                assertEquals("Воронеж", actual.city)
                assertEquals("Воронеж, улица Таранченко, 19А", actual.address)
                assertEquals("https://www.openstreetmap.org/way/77503842", actual.sourceUrl)
                assertEquals(DiscoveredPlaceKind.CHURCH, actual.kind)
                assertFalse(actual.isChapel)
                assertEquals(expectedCenter.latitude, actual.center.latitude, 0.00000001)
                assertEquals(expectedCenter.longitude, actual.center.longitude, 0.00000001)
                assertTrue("Name must come from the published linked entity", actual.name in setOf(
                    "Храм Николая Чудотворца (Воронеж)", "Никольская церковь (Воронеж)"))
                assertNull("A linked name does not verify the old Wikidata P856 website", actual.website)
                selected?.let { assertEquals(it, actual) }
                selected = actual
                observation["selected"] = actual
            }
            val nikolsky = requireNotNull(selected)
            val nearbyStarted = System.nanoTime()
            val nearby = success(newDiscovery().findChurches(officialPoint, 500))
            receipt["nearbyElapsedMillis"] = (System.nanoTime() - nearbyStarted) / 1_000_000
            receipt["nearby"] = nearby.items.map {
                mapOf("id" to it.id, "name" to it.name, "center" to it.center,
                    "distanceMeters" to officialPoint.distanceMeters(it.center))
            }
            assertEquals(nearby.items.size, nearby.items.distinctBy { it.id }.size)
            val distances = nearby.items.map { officialPoint.distanceMeters(it.center) }
            assertTrue(distances.all { it in 0.0..500.0 })
            assertEquals(distances.sorted(), distances)
            assertEquals(nikolsky, nearby.items.single { it.id == nikolskyId })
            val neighbor = nearby.items.single { it.id == tolgskyId }
            assertEquals("Церковь Иконы Божией Матери Толгская", neighbor.name)
            assertEquals("https://www.openstreetmap.org/way/192294657", neighbor.sourceUrl)
            assertEquals(51.65954445, neighbor.center.latitude, 0.00000001)
            assertEquals(39.21308765, neighbor.center.longitude, 0.00000001)
            assertEquals(5.72877, officialPoint.distanceMeters(nikolsky.center), 0.05)
            assertEquals(28.62485, officialPoint.distanceMeters(neighbor.center), 0.05)
            assertEquals(23.86115, nikolsky.center.distanceMeters(neighbor.center), 0.05)

            val selectedRecord = requireNotNull(nikolsky.toSavedChurch())
            val neighborRecord = requireNotNull(neighbor.toSavedChurch())
            val expectedRecords = listOf(selectedRecord, neighborRecord)
            assertEquals(expectedRecords, SavedChurchCodec.decode(SavedChurchCodec.encode(expectedRecords)))
            val store = ChurchDirectoryStore(preferences)
            assertTrue(store.save(selectedRecord))
            assertTrue(store.save(neighborRecord))
            val restored = ChurchDirectoryStore(context.getSharedPreferences(preferenceName, 0)).records()
            assertEquals(expectedRecords, restored)
            assertEquals(2, restored.map { it.id }.distinct().size)
            assertEquals("", restored.single { it.id == nikolskyId }.website)
            assertTrue(restored.all { it.asParish().schedule.isEmpty() })
            val reopened = success(newDiscovery().searchPlaces("Никольский Воронеж"))
            assertEquals(selectedRecord, reopened.items.single { it.id == nikolskyId }.toSavedChurch())
            receipt["savedRecords"] = restored
            receipt["reopenedIds"] = reopened.items.map { it.id }
            assertEquals(0, networkCalls)
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failureMessage"] = failure.message.orEmpty()
            throw failure
        } finally {
            receipt["networkRequests"] = networkCalls
            receipt["privatePreferencesRemoved"] = context.deleteSharedPreferences(preferenceName)
            context.filesDir.resolve("bundled-linked-church-acceptance.json").writeText(Gson().toJson(receipt))
        }
    }

    @Test fun everyLinkedNameKeepsItsOwnNearbyBuildingAndReportsTextSearchGaps() = runBlocking<Unit> {
        val cases = mutableListOf<Map<String, Any>>()
        val receipt = linkedMapOf<String, Any>("passed" to false, "cases" to cases,
            "scope" to "Every nameSource record in the installed asset; text search is diagnostic, not a city coverage claim")
        try {
            val assetBytes = context.assets.open(BundledChurchCatalog.ASSET_PATH).use { it.readBytes() }
            receipt["assetSha256"] = MessageDigest.getInstance("SHA-256").digest(assetBytes)
                .joinToString("") { "%02x".format(it) }
            val raw = com.google.gson.JsonParser.parseString(assetBytes.toString(Charsets.UTF_8)).asJsonObject
            val linked = raw.getAsJsonArray("churches").map { it.asJsonObject }
                .filter { it.has("nameSource") }
            assertTrue("The installed asset must contain linked names for this acceptance", linked.isNotEmpty())
            receipt["linkedRecordCount"] = linked.size
            val discovery = newDiscovery()
            val local = LocalChurchCatalog.decode(assetBytes.toString(Charsets.UTF_8).reader(), Instant.now())
            for (source in linked) {
                assertTrue(source.get("nameSource").isJsonObject)
                val id = source.get("id").asString
                val point = source.getAsJsonObject("center").let {
                    GeoPoint(it.get("latitude").asDouble, it.get("longitude").asDouble)
                }
                val observation = linkedMapOf<String, Any>("id" to id, "center" to point,
                    "nameSource" to source.getAsJsonObject("nameSource"))
                cases += observation
                val started = System.nanoTime()
                val nearby = success(discovery.findChurches(point, 500))
                observation["nearbyElapsedMillis"] = (System.nanoTime() - started) / 1_000_000
                observation["nearbyIds"] = nearby.items.map { it.id }
                assertEquals("$id must occur exactly once in its own radius", 1, nearby.items.count { it.id == id })
                assertEquals("Nearby records must keep distinct identities", nearby.items.size, nearby.items.distinctBy { it.id }.size)
                val actual = nearby.items.single { it.id == id }
                assertEquals(source.get("name").asString, actual.name)
                assertEquals(source.get("address").asString, actual.address)
                assertEquals(source.get("city").asString, actual.city)
                assertEquals(source.get("sourceUrl").asString, actual.sourceUrl)
                assertEquals(source.get("website")?.takeUnless { it.isJsonNull }?.asString, actual.website)
                assertEquals(point, actual.center)
                assertEquals(0.0, point.distanceMeters(actual.center), 0.000001)
                observation["actual"] = actual
                val phrases = (listOf(source.get("name").asString) +
                    source.getAsJsonArray("aliases").map { it.asString }).distinct()
                observation["textSearch"] = phrases.map { phrase ->
                    val queryStarted = System.nanoTime()
                    val matches = local.search(phrase) {}
                    mapOf("query" to phrase, "expectedIdFound" to matches.any { it.id == id },
                        "resultIds" to matches.map { it.id },
                        "elapsedMillis" to (System.nanoTime() - queryStarted) / 1_000_000)
                }
            }
            assertEquals(linked.size, cases.size)
            assertEquals(0, networkCalls)
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failureMessage"] = failure.message.orEmpty()
            throw failure
        } finally {
            receipt["networkRequests"] = networkCalls
            context.filesDir.resolve("bundled-linked-church-census.json").writeText(Gson().toJson(receipt))
        }
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
