package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.GeoPoint
import ru.liturgyguide.service.discovery.LocalChurchCatalog
import ru.liturgyguide.ui.toSavedChurch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Live configured-site import. The website is supplied explicitly; this is not discovery QA. */
@RunWith(AndroidJUnit4::class)
class LiveConfiguredVoronezhScheduleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val configuredHome = "http://prorok-iliya.cerkov.ru/"
    private val home = "https://prorok-iliya.cerkov.ru/"
    private val schedule = home + "raspisanie-bogosluzhenij/"

    @Test fun realPublishedHomepageImportsSevenDatedServicesAndReopensFromAnIsolatedCache() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to the current public parish website", args.getString("liveConfiguredVoronezhSchedule") == "true")
        val run = args.getString("scheduleHtmlNativeRun") ?: "local-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val output = File(requireNotNull(context.getExternalFilesDir("schedule-html-native")), "$run/configured-voronezh")
        check(output.mkdirs() || output.isDirectory)
        val preferenceName = "configured-voronezh-${UUID.randomUUID()}"
        val requests = JSONArray()
        val requestedUrls = mutableListOf<String>()
        val returnedUrls = mutableListOf<String>()
        val resolverCalls = AtomicInteger()
        val offlineCalls = AtomicInteger()
        val report = JSONObject().put("scope", "Configured homepage -> published schedule -> production HTML importer -> isolated persisted cache")
            .put("discoveryTested", false).put("clock", now.toString())
            .put("clockScope", "Fixed service date with genuinely live responses; a changed published week must fail these expectations")
            .put("configuredHomepage", configuredHome).put("expectedSchedule", schedule)
            .put("websiteEvidence", "https://vob.cerkov.ru/xramy/")
            .put("websiteEvidenceScope", "Previously checked official directory; not fetched or used to bind a site by this test")
            .put("requests", requests).put("allPassed", false)
        fun writeReport() {
            report.put("resolverCalls", resolverCalls.get()).put("offlineTransportCalls", offlineCalls.get())
            File(output, "receipt.json").writeText(report.toString(2))
        }
        fun cache(): ScheduleCacheStore = object : ScheduleCacheStore {
            private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            override fun read(parishId: String): String? = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                require(value.length <= 200_000)
                check(preferences.edit().putString(parishId, value).commit())
            }
        }
        writeReport()
        try {
            val parish = selectedParish()
            File(output, "selected-parish.json").writeText(Gson().toJson(parish))
            val liveCache = cache()
            assertNull("Each run must start with an empty private test cache", liveCache.read(parish.id))
            val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
            val transport = ScheduleTransport { url ->
                requestedUrls += url
                val attempt = JSONObject().put("requested", url).put("startedAt", Instant.now().toString())
                requests.put(attempt)
                writeReport()
                val started = System.nanoTime()
                try {
                    network.fetch(url).also { response ->
                        returnedUrls += response.url
                        attempt.put("returned", response.url).put("contentType", response.contentType)
                            .put("bytes", response.bytes.size).put("sha256", sha256(response.bytes))
                            .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                        writeReport()
                        val document = requireNotNull(response.html()) { "These two live sources must remain HTML" }
                        val identityHeadings = document.select("h1").map { it.text() }
                        attempt.put("identityHeadingPresent", "Ильинский храм" in identityHeadings)
                        assertTrue("The actual response must still identify the intended church", "Ильинский храм" in identityHeadings)
                        if (url == configuredHome) {
                            val links = document.select("a[href]").map { it.absUrl("href") }
                            attempt.put("publishedScheduleLinkPresent", schedule in links)
                            assertTrue("The real homepage must publish the followed schedule URL", schedule in links)
                        }
                        if (response.url == schedule) {
                            // Independent source facts: read the four visible date cells and raw
                            // clock strings. Expected values come from the inspected publication,
                            // not from a second invocation of the production schedule parser.
                            val rows = document.select("article .entry-content table tr").map { row ->
                                val cells = row.select("td")
                                require(cells.size >= 2) { "The inspected source table changed" }
                                cells[0].text() to Regex("[0-9]{1,2}:[0-9]{2}").findAll(cells[1].text()).map { it.value }.toList()
                            }
                            attempt.put("sourceRows", JSONArray(rows.map { (day, clocks) ->
                                JSONObject().put("day", day).put("clocks", JSONArray(clocks))
                            }))
                            attempt.put("discussionContainers", document.select("#comments,.commentlist").size)
                            writeReport()
                            assertEquals(listOf("11 Ноября, Среда", "13 Ноября, Пятница", "14 Ноября, Суббота", "15 Ноября, Воскресение"), rows.map { it.first })
                            assertEquals(listOf(listOf("7:45"), listOf("7:45", "16:00"), listOf("7:45", "16:00"), listOf("7:30", "8:00")), rows.map { it.second })
                        }
                        writeReport()
                    }
                } catch (failure: Throwable) {
                    attempt.put("failureType", failure.javaClass.name).put("failure", failure.message?.take(500))
                        .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    writeReport()
                    throw failure
                }
            }
            val repository = InternetScheduleRepository(transport, liveCache, AndroidScheduleDocumentExtractor(context),
                ScheduleSiteResolver {
                    resolverCalls.incrementAndGet()
                    error("Configured-site acceptance must not silently recover through discovery")
                })
            val result = withTimeout(90_000) { repository.refresh(parish, now, force = true) }
            File(output, "result.json").writeText(Gson().toJson(result))
            report.put("status", result.status.name)
            writeReport()
            assertEquals(InternetScheduleStatus.READY, result.status)
            assertEquals(listOf(configuredHome, schedule), requestedUrls)
            assertEquals(listOf(home, schedule), returnedUrls)
            assertEquals(0, resolverCalls.get())
            assertEquals(parish.id, result.parishId)
            assertEquals(configuredHome, result.configuredWebsite)
            assertEquals(home, result.website)
            assertEquals(schedule, result.sourceUrl)
            assertEquals("", result.customSource)
            assertEquals("", result.siteEvidenceUrl)
            assertNull(result.siteBinding)
            assertFalse(result.sourceNeedsVerification)
            assertFalse(result.publicationRejected)
            val publication = requireNotNull(result.publication)
            assertEquals(parish.id, publication.parishId)
            assertEquals("Europe/Moscow", publication.timezone)
            assertEquals(home, publication.website)
            assertEquals(home, publication.linkedFrom)
            assertEquals(schedule, publication.sourceUrl)
            assertEquals("html", publication.documentFormat)
            assertEquals(now.toString(), publication.fetchedAt)
            assertTrue("The page's weekday evidence infers the year; it is not an explicit year", publication.yearInferred)
            assertEquals(listOf("2026-11-11T07:45", "2026-11-13T07:45", "2026-11-13T16:00", "2026-11-14T07:45",
                "2026-11-14T16:00", "2026-11-15T07:30", "2026-11-15T08:00"), publication.events.map { "${it.date}T${it.time}" })
            assertEquals(listOf("liturgy", "liturgy", "service_block", "liturgy", "service_block", "service_block", "liturgy"),
                publication.events.map { it.kind })
            assertTrue(publication.events.filter { it.kind == "liturgy" }.all { it.title.contains("Литургия", ignoreCase = true) })
            val memorial = publication.events.single { it.date == "2026-11-13" && it.time == "16:00" }
            assertEquals("Заупокойное Богослужение", memorial.title)
            assertEquals("service_block", memorial.kind)
            assertTrue(publication.events.single { it.date == "2026-11-14" && it.time == "16:00" }.title.startsWith("Всенощное бдение", ignoreCase = true))
            assertTrue(publication.events.single { it.date == "2026-11-15" && it.time == "07:30" }.title.startsWith("Акафист Пророку Илии", ignoreCase = true))
            assertTrue("Missing November 12 must not become an invented service", publication.events.none { it.date == "2026-11-12" })

            val stored = requireNotNull(liveCache.read(parish.id))
            report.put("cacheSha256", sha256(stored.toByteArray())).put("events", JSONArray(Gson().toJson(publication.events)))
            val offline = InternetScheduleRepository(ScheduleTransport {
                offlineCalls.incrementAndGet()
                throw IOException("Offline acceptance forbids every transport request")
            }, cache(), AndroidScheduleDocumentExtractor(context))
            assertEquals("A new repository/cache wrapper must reconstruct the complete persisted state", result, offline.cached(parish))
            assertEquals("A fresh cached refresh must also remain usable without transport", result,
                offline.refresh(parish, now.plusSeconds(60), force = false))
            assertEquals(0, offlineCalls.get())
            assertEquals(stored, cache().read(parish.id))
            report.put("offlineCacheEqual", true).put("offlineFreshRefreshEqual", true)
                .put("offlineScope", "New repository/cache wrapper over synchronously committed test preferences; not process death or radio control")
                .put("allPassed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.name).put("failure", failure.message?.take(500))
            throw failure
        } finally {
            writeReport()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun selectedParish(): Parish {
        val catalog = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use { LocalChurchCatalog.decode(it, now) }
        val selected = catalog.nearby(GeoPoint(51.65438305, 39.20722645), 200) {}
            .single { it.id == "osm:way:239273353" }
        // These identity facts match test/resources/schedule/voronezh/selected-parish.json.
        assertEquals("Церковь Илии Пророка", selected.name)
        assertEquals("Воронеж", selected.city)
        assertEquals("Воронеж, улица Севастьяновский съезд, 26", selected.address)
        assertEquals("https://www.openstreetmap.org/way/239273353", selected.sourceUrl)
        assertEquals(51.65438305, selected.center.latitude, .00000001)
        assertEquals(39.20722645, selected.center.longitude, .00000001)
        assertTrue("Website is configured explicitly below, not supplied by bundled OSM", selected.website.isNullOrBlank())
        return requireNotNull(selected.toSavedChurch()).asParish().copy(website = configuredHome)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
