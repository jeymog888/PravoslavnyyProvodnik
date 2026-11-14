package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.discovery.*
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class PublisherPoolFactoriesTest {
    private val selected = Parish("osm:way:226509018", "Церковь иконы Божией Матери «Всецарица»", "", "", "", "", "",
        "Europe/Moscow", "https://www.openstreetmap.org/way/226509018", "", "", "", emptyList(), "", "", 55.8407748, 37.1731019)
    private val clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC)
    private val publisher = "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/"
    private val contact = publisher + "contacts"
    private val gson = Gson()
    private fun realBytes() = requireNotNull(javaClass.getResourceAsStream("/schedule/publisher-pools/curated-real.json")).use { it.readBytes() }
    private fun seeds(pool: PublisherSeedPool) = pool.seeds(selected, 10_000.0, 60)
    private fun record(id: String, website: String, latitude: Double = requireNotNull(selected.latitude),
        longitude: Double = requireNotNull(selected.longitude), reference: String? = null): JsonObject {
        val parish = selected.copy(id = id, name = "Храм святителя Николая $id", shortName = "Храм", city = "Москва",
            address = "Улица, 1", website = website, sourceUrl = website, latitude = latitude, longitude = longitude)
        return gson.toJsonTree(parish).asJsonObject.apply { reference?.let { addProperty("coordinateSourceUrl", it) } }
    }
    private fun curated(vararg records: JsonObject) = gson.toJson(mapOf("churches" to records.toList())).toByteArray()
    private fun catalog(vararg websites: String): String = gson.toJson(mapOf("schemaVersion" to 1,
        "metadata" to mapOf("recordCount" to websites.size, "source" to mapOf("name" to "OpenStreetMap via Geofabrik",
            "url" to "https://download.geofabrik.de/russia-261110.osm.pbf", "sizeBytes" to 1_000_000,
            "sha256" to "a".repeat(64), "dumpTimestamp" to "2026-11-10T21:00:00Z", "license" to "ODbL-1.0",
            "copyrightUrl" to DefaultPlaceDiscovery.COPYRIGHT_URL,
            "licenseUrl" to "https://opendatacommons.org/licenses/odbl/1-0/", "attribution" to "© OpenStreetMap contributors")),
        "churches" to websites.mapIndexed { index, website -> mapOf("id" to "osm:way:${index + 1}",
            "name" to "Храм святителя Николая ${index + 1}", "address" to "", "city" to "", "region" to "", "country" to "",
            "website" to website, "isChapel" to false, "kind" to "CHURCH", "aliases" to emptyList<String>(),
            "sourceTags" to mapOf("amenity" to "place_of_worship", "religion" to "christian", "denomination" to "orthodox"),
            "center" to mapOf("latitude" to requireNotNull(selected.latitude) + index * .0001, "longitude" to selected.longitude),
            "sourceUrl" to "https://www.openstreetmap.org/way/${index + 1}") }))
    private fun factories(body: ByteArray, big: String = catalog("https://osm.example/")) = BundledPublisherPoolFactories(
        BundledCuratedPublisherMetadata { ByteArrayInputStream(body) }, BundledChurchCatalog({ StringReader(big) }, clock))

    @Test fun realDirectReferencesReadOnlySmallAssetAndPreserveBothMetadataOwnersAndIndependentSelection() = runBlocking {
        val before = gson.toJson(selected)
        val smallReads = AtomicInteger(); val bigReads = AtomicInteger()
        val caller = Thread.currentThread()
        val factories = BundledPublisherPoolFactories(BundledCuratedPublisherMetadata {
            assertNotSame("Small metadata IO must not execute on its calling thread", caller, Thread.currentThread())
            smallReads.incrementAndGet(); ByteArrayInputStream(realBytes())
        }, BundledChurchCatalog({ bigReads.incrementAndGet(); error("Direct must not open the big asset") }, clock))
        val found = seeds(factories.direct(selected))
        assertEquals(2, found.size)
        assertEquals(setOf("elisavetinsky_opalikha", "vsetsaritsa_nakhabino"), found.map { it.provenance.recordId }.toSet())
        assertTrue(found.all { it.url == contact && it.publisherScopeUrl == publisher && it.role == PublisherSeedRole.CONTACT_REFERENCE })
        assertEquals(setOf("fb58eff6520fa9f99ac902b510e2719347af9f867191499dd991c0c3ad353b7e"), found.map { it.provenance.metadataSha256 }.toSet())
        assertTrue(found.all { it.provenance.metadataSource == "asset:churches.json" })
        assertEquals(4, factories.curatedOwners().size)
        assertEquals(2, factories.curatedOwners().count { it.latitude == null && it.longitude == null })
        assertEquals(1, smallReads.get()); assertEquals(0, bigReads.get())
        assertEquals(before, gson.toJson(selected))
    }

    @Test fun directWithUnknownOrAmbiguousPublisherKeepsOnlyItsExactEndpointScope() = runBlocking {
        val reference = "https://shared.example/parish-a/contacts"
        val child = record("child", "https://old-child.example/", reference = reference)
        val one = record("owner", "https://shared.example/parish-a/")
        val exact = seeds(factories(curated(child, one)).direct(selected)).single()
        assertEquals("https://shared.example/parish-a/", exact.publisherScopeUrl)
        val missing = seeds(factories(curated(child)).direct(selected)).single()
        assertEquals(reference, missing.publisherScopeUrl)
        val ambiguous = seeds(factories(curated(child, one, record("other-owner", one["website"].asString))).direct(selected)).single()
        assertEquals(reference, ambiguous.publisherScopeUrl)
        assertNotEquals("https://shared.example/", missing.publisherScopeUrl)
    }

    @Test fun publisherOwnershipUsesExactOriginAndSafeSlashBoundariesWithoutPromotingTheOldChildSite() = runBlocking {
        val owner = record("owner", "https://shared.example/parish-a/")
        for (reference in listOf("https://shared.example/parish-ab/contacts", "https://foreign.example/parish-a/contacts",
            "http://shared.example/parish-a/contacts")) {
            val child = record("child", "https://old-child.example/", reference = reference)
            assertEquals(reference, seeds(factories(curated(child, owner)).direct(selected)).single().publisherScopeUrl)
        }
        assertFalse(CuratedPublisherScope.owns(owner["website"].asString, "https://shared.example/parish-a/%2e%2e/contacts"))
        assertFalse(CuratedPublisherScope.owns(owner["website"].asString, "https://shared.example/parish-a/../other"))
        assertFalse(CuratedPublisherScope.owns(owner["website"].asString, "https://shared.example/parish-ab/contacts"))
    }

    @Test fun websiteTierKeepsCuratedPublisherAndReportsUpstreamDumpProvenanceInsteadOfAssetHash() = runBlocking {
        val big = catalog("https://nearby-one.example/", "https://nearby-two.example/", "https://shared.example/tenant-a/")
        val found = seeds(factories(realBytes(), big).websites(selected))
        assertEquals(listOf("vsetsaritsa_nakhabino", "elisavetinsky_opalikha"), found.take(2).map { it.provenance.recordId })
        assertTrue(found.any { it.url == publisher })
        val osm = found.filter { it.provenance.origin == PublisherSeedOrigin.OSM }
        assertEquals(3, osm.size)
        assertTrue(osm.all { it.provenance.metadataSource == "https://download.geofabrik.de/russia-261110.osm.pbf" &&
            it.provenance.metadataSha256 == "a".repeat(64) })
        assertNotEquals(ScheduleSourceCandidate.sha(big.toByteArray()), osm.first().provenance.metadataSha256)
        assertEquals("https://shared.example/tenant-a/", osm.last().publisherScopeUrl)
    }

    @Test fun concurrentPoolAndPlaceDiscoveryShareOneCatalogDecoderAndOneSmallMetadataRead() = runBlocking {
        val bigReads = AtomicInteger(); val smallReads = AtomicInteger()
        val local = BundledChurchCatalog({ bigReads.incrementAndGet(); StringReader(catalog("https://one.example/", "https://two.example/")) }, clock)
        val factories = BundledPublisherPoolFactories(BundledCuratedPublisherMetadata {
            smallReads.incrementAndGet(); ByteArrayInputStream(realBytes())
        }, local)
        val noNetwork = object : PlaceDiscovery {
            override suspend fun searchPlaces(query: String): DiscoveryResult<DiscoveredChurch> = error("Network must not run")
            override suspend fun searchCities(query: String): DiscoveryResult<DiscoveryCity> = error("Network must not run")
            override suspend fun findChurches(center: GeoPoint, radiusMeters: Int): DiscoveryResult<DiscoveredChurch> = error("Network must not run")
        }
        val discovery = CatalogPlaceDiscovery(local, noNetwork)
        (1..12).map { index -> async(Dispatchers.Default) {
            if (index % 2 == 0) assertTrue(seeds(factories.websites(selected)).isNotEmpty())
            else assertEquals(2, (discovery.findChurches(GeoPoint(requireNotNull(selected.latitude), requireNotNull(selected.longitude)), 5_000)
                as DiscoveryResult.Success<DiscoveredChurch>).items.size)
        } }.awaitAll()
        assertEquals(1, bigReads.get()); assertEquals(1, smallReads.get())
    }

    @Test fun cancellingSmallMetadataDuringReadDoesNotCommitLoadedAndTheNextCallReadsAgain() = runBlocking {
        val reads = AtomicInteger(); val entered = CompletableDeferred<Unit>()
        lateinit var loaderJob: Job
        val loader = BundledCuratedPublisherMetadata {
            val first = reads.incrementAndGet() == 1
            object : ByteArrayInputStream(realBytes()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) { loaderJob.cancel(); entered.complete(Unit) }
                    return super.read(buffer, offset, length)
                }
            }
        }
        loaderJob = launch(start = CoroutineStart.LAZY) { loader.get(); fail("Cancelled read must not produce a snapshot") }
        loaderJob.start(); entered.await(); loaderJob.join()
        assertTrue(loaderJob.isCancelled)
        assertEquals(4, requireNotNull(loader.get()).records.size)
        assertEquals(2, reads.get())
        assertEquals(4, requireNotNull(loader.get()).records.size)
        assertEquals(2, reads.get())
    }

    @Test fun cancellingCatalogDecodeDoesNotPoisonTheSharedPublisherOrPlaceConsumer() = runBlocking {
        val reads = AtomicInteger()
        val local = BundledChurchCatalog({
            if (reads.incrementAndGet() == 1) throw CancellationException("Cancel first asset read")
            StringReader(catalog("https://one.example/"))
        }, clock)
        try { local.publisherSeeds(GeoPoint(requireNotNull(selected.latitude), requireNotNull(selected.longitude)), 10_000, 60)
            fail("Parent cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, local.publisherSeeds(GeoPoint(requireNotNull(selected.latitude), requireNotNull(selected.longitude)), 10_000, 60).size)
        assertEquals(2, reads.get())
    }

    @Test fun boundedCatalogHintsKeepTheConcreteSitePathAndInvalidSelectionDoesNotOpenAnyAsset() = runBlocking {
        val reads = AtomicInteger()
        val metadata = BundledCuratedPublisherMetadata { reads.incrementAndGet(); ByteArrayInputStream(realBytes()) }
        val local = BundledChurchCatalog({ reads.incrementAndGet(); StringReader(catalog("https://shared.example/a/", "https://shared.example/ab/")) }, clock)
        val factories = BundledPublisherPoolFactories(metadata, local)
        assertTrue(factories.direct(selected.copy(latitude = null, longitude = null)).seeds(selected, 10_000.0, 60).isEmpty())
        assertTrue(factories.websites(selected.copy(latitude = Double.NaN)).seeds(selected, 10_000.0, 60).isEmpty())
        assertEquals(0, reads.get())
        val limited = local.publisherSeeds(GeoPoint(requireNotNull(selected.latitude), requireNotNull(selected.longitude)), 10_000, 1)
        assertEquals(1, limited.size); assertEquals("https://shared.example/a/", limited.single().publisherScopeUrl)
    }
}
