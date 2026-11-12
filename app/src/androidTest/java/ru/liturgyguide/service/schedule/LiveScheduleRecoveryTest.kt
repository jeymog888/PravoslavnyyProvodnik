package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.LocalChurchCatalog
import ru.liturgyguide.service.discovery.GeoPoint
import ru.liturgyguide.ui.toSavedChurch
import java.time.Instant

/** Sources checked independently in the 20-church audit. No discovery response or OCR is mocked. */
class LiveScheduleRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-12T03:00:00Z")

    @Test fun selectedNovosibirskMonasteryFindsItsGeneralTimetableAndReopensOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveScheduleRecovery") == "true")
        // Select the monastery entity explicitly, not the OSM object of its individual church.
        val parish = Parish("live:novosibirsk-monastery", "Мужской монастырь Новомучеников Российских", "Новомучеников",
            "Новосибирск", "улица Немировича-Данченко, 120/1", "", "http://neomartires.prihod.ru/", "Asia/Novosibirsk",
            "https://azbyka.ru/palomnik/index.php?curid=6081", "", "", "", emptyList(), "", "", 54.975263, 82.908625)
        val result = checkSource("novosibirsk", parish)
        assertEquals("https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/", result.website)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=6081", result.siteEvidenceUrl)
        assertTrue(requireNotNull(result.publication).yearInferred)
        for (day in listOf("2026-11-12", "2026-11-13")) {
            assertEquals(listOf("08:30", "17:00"), result.publication?.events?.filter { it.date == day }?.map { it.time })
        }
    }

    @Test fun tomskHomepageFindsItsCurrentScheduleImageInsteadOfTheOldCover() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveScheduleRecovery") == "true")
        // The official homepage is known here. This tests document discovery, not map-to-website matching.
        val parish = Parish("live:tomsk-peter-paul", "Петропавловский собор", "Петропавловский собор", "Томск", "Алтайская улица, 47", "",
            "https://xn--90azbbajd.xn--p1ai/", "Asia/Tomsk", "https://xn--90azbbajd.xn--p1ai/", "", "", "", emptyList(), "", "")
        val result = checkSource("tomsk", parish)
        assertFalse(requireNotNull(result.publication).yearInferred)
        for (day in listOf("2026-11-12", "2026-11-13")) {
            assertEquals(listOf("08:30", "16:45", "17:00"), result.publication?.events?.filter { it.date == day }?.map { it.time })
        }
    }

    @Test fun citylessCatalogChurchFindsItsPublishedScheduleAndReopensOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCitylessSchedule") == "true")
        val parish = catalogParish("osm:relation:19595736", GeoPoint(55.7716959, 37.63184935))
        assertEquals("", parish.city)
        assertEquals("", parish.website)
        // Exercise the new real fulltext path directly as well as the production resolver chain.
        val direct = PalomnikScheduleSiteResolver(UrlConnectionScheduleTransport(timeoutMillis = 20_000)).resolve(parish)
        assertEquals("https://hram-troicy.ru/raspisanie/", direct?.url)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=14059", direct?.evidenceUrl)
        val result = checkSource("cityless-listi", parish)
        assertTrue(result.website.startsWith("https://hram-troicy.ru/"))
        // Independently read from the church's public schedule, not copied from the parser output.
        for (day in listOf("2026-11-12", "2026-11-13")) {
            val services = requireNotNull(result.publication).events.filter { it.date == day }
            assertEquals(listOf("08:00", "17:00"), services.map { it.time })
            assertTrue(services.first().title.contains("Литургия"))
        }
        assertEquals("", parish.city)
    }

    @Test fun citylessMonasteryArticleDoesNotAssignItsUnlabelledScheduleToABuilding() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCitylessSchedule") == "true")
        val parish = catalogParish("osm:way:55228075", GeoPoint(54.97553165, 82.9086244))
        val requests = mutableListOf<String>()
        var verifiedRevision = false
        val network = UrlConnectionScheduleTransport(timeoutMillis = 20_000)
        val direct = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
            network.fetch(url).also { response ->
                requests += url
                if ("prop=revisions" in url) {
                    val page = com.google.gson.JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
                        .getAsJsonObject("query").getAsJsonObject("pages").getAsJsonObject("6081")
                    assertEquals("6081", page.get("pageid").asString)
                    assertEquals("Мужской монастырь Новомучеников Российских (Новосибирск)", page.get("title").asString)
                    val content = page.getAsJsonArray("revisions").single().asJsonObject
                        .getAsJsonObject("slots").getAsJsonObject("main").get("*").asString
                    assertTrue(content.contains("вид=Монастырь"))
                    assertTrue(content.contains("Церковь Серафима Саровского"))
                    verifiedRevision = true
                }
                val folder = context.filesDir.resolve("live-source-recovery/cityless-ambiguous").apply { mkdirs() }
                folder.resolve("response-${requests.size}.json").writeBytes(response.bytes)
                folder.resolve("requests.json").writeText(Gson().toJson(requests))
            }
        }).resolve(parish)
        assertNull("A shared monastery article is not building-specific schedule evidence", direct)
        assertTrue("Verify the actual complex article, not a 200 error or a transport failure", verifiedRevision)
    }

    private fun catalogParish(id: String, point: GeoPoint): Parish {
        val catalog = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use {
            LocalChurchCatalog.decode(it, now)
        }
        return requireNotNull(catalog.nearby(point, 200) {}.single { it.id == id }.toSavedChurch()).asParish()
    }

    private suspend fun checkSource(label: String, parish: Parish): InternetScheduleState {
        val folder = context.filesDir.resolve("live-source-recovery/$label").apply { mkdirs() }
        val preferences = context.getSharedPreferences("live-source-recovery-$label", 0)
        check(preferences.edit().clear().commit())
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(preferences.edit().putString(parishId, value).commit()) }
        }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 20_000, allowExplicitHttp = true)
        val extractor = AndroidScheduleDocumentExtractor(context)
        val requests = mutableListOf<Map<String, Any?>>()
        val transport = ScheduleTransport { url ->
            val started = System.nanoTime()
            try {
                network.fetch(url).also { response ->
                    requests += mapOf("requested" to url, "returned" to response.url, "type" to response.contentType,
                        "bytes" to response.bytes.size, "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
                    folder.resolve("requests.json").writeText(Gson().toJson(requests))
                }
            } catch (failure: Exception) {
                requests += mapOf("requested" to url, "error" to failure.javaClass.simpleName,
                    "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
                folder.resolve("requests.json").writeText(Gson().toJson(requests))
                throw failure
            }
        }
        val repository = InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response ->
            extractor.extract(response).also { document ->
                folder.resolve("document-${requests.size}.json").writeText(Gson().toJson(document))
            }
        })
        val result = repository.refresh(parish, now, force = true)
        folder.resolve("result.json").writeText(Gson().toJson(result))
        assertEquals("$label: inspect live-source-recovery evidence", InternetScheduleStatus.READY, result.status)
        assertNotNull(result.publication)
        val offline = InternetScheduleRepository(ScheduleTransport { error("Offline: no request allowed") }, cache)
        assertEquals(result, offline.cached(parish))
        return result
    }
}
