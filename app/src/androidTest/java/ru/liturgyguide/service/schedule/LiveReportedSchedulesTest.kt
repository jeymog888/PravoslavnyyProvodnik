package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.GeoPoint
import ru.liturgyguide.service.discovery.LocalChurchCatalog
import ru.liturgyguide.ui.toSavedChurch
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Independent live diagnostics for the reported churches; user preferences are never changed. */
class LiveReportedSchedulesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val gson = Gson()
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val maryinaSchedule = "https://troitseparaskevo.ru/hram-ikony-bozhiej-materi-nechayannaya-radost-v-marinoj-roshhe/raspisanie/"

    @Test fun maryinaAutomaticSourceDiscovery() = runBlocking {
        val folder = output("maryina-automatic")
        val parish = maryina()
        val result = probe(parish, folder)
        ready(result, folder)
        assertTrue("Automatic discovery needs source evidence", result.siteEvidenceUrl.isNotBlank())
        assertEquals("", result.customSource)
        maryinaTimes(result)
    }

    @Test fun maryinaExplicitSourceImportsPublishedDocument() = runBlocking {
        val folder = output("maryina-explicit")
        // This is deliberately separate: an explicit source does not prove automatic discovery.
        val result = probe(maryina(), folder, sourceOverride = maryinaSchedule)
        ready(result, folder)
        assertEquals(maryinaSchedule, result.customSource)
        assertTrue("The published timetable is an attachment", result.publication!!.documentFormat in setOf("pdf", "image"))
        maryinaTimes(result)
    }

    private fun maryinaTimes(result: InternetScheduleState) {
        val publication = requireNotNull(result.publication)
        fun events(date: String) = publication.forDate(LocalDate.parse(date))
        assertEquals(listOf("08:00", "17:00"), events("2026-11-12").map { it.time })
        assertEquals(listOf("08:00", "17:00"), events("2026-11-13").map { it.time })
        assertTrue(events("2026-11-12").first().title.contains("Литургия", ignoreCase = true))
        assertTrue(events("2026-11-12").last().title.contains("Утреня с акафистом", ignoreCase = true))
        // The printed 08:00 on Mondays means "храм открыт", not a liturgy.
        assertEquals(listOf("17:00"), events("2026-11-16").map { it.time })
        assertEquals(listOf("17:00"), events("2026-11-30").map { it.time })
        assertFalse(publication.yearInferred)
    }

    @Test fun sretenskyBuildingsKeepOwnTwelveAndThirteenNovemberServices() = runBlocking {
        val folder = output("sretensky-buildings")
        val directory = ParishRepository(context.assets.open("churches.json").bufferedReader().use { it.readText() })
        val selected = listOf("sretensky_old", "sretensky_new").map { id ->
            directory.selected(id).copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "")
                .also { assertEquals(id, it.id) }
        }
        // Collect both buildings before checking their events so one missing timetable leaves evidence for both.
        val results = selected.associate { parish -> parish.id to probe(parish, File(folder, parish.id).apply { mkdirs() }) }
        selected.forEach { parish -> ready(results.getValue(parish.id), File(folder, parish.id)) }
        fun times(id: String, date: String) = results.getValue(id).publication!!.forDate(LocalDate.parse(date)).map { it.time }
        // Independently read from the actual per-building rows saved in sretensky-schedule.html.
        assertEquals(listOf("08:00", "18:00"), times("sretensky_old", "2026-11-12"))
        assertEquals(emptyList<String>(), times("sretensky_new", "2026-11-12"))
        assertEquals(listOf("18:00"), times("sretensky_new", "2026-11-13"))
    }

    private fun output(label: String): File {
        assumeTrue(arguments.getString("liveReportedSchedules") == "true")
        val run = arguments.getString("reportedScheduleRun") ?: "local-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9._-]{1,80}")) && run !in setOf(".", ".."))
        return context.filesDir.resolve("reported-schedules/$run/$label").apply { check(mkdirs() || isDirectory) }
    }

    private fun maryina(): Parish {
        val catalog = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use {
            LocalChurchCatalog.decode(it, now)
        }
        val selected = catalog.nearby(GeoPoint(55.8055899, 37.61505585), 200) {}
            .single { it.id == "osm:way:207742244" }
        return requireNotNull(selected.toSavedChurch()).asParish().also {
            assertEquals("", it.city)
            assertEquals("", it.website)
            assertEquals("Шереметьевская улица, 33 с1", it.address)
        }
    }

    private suspend fun probe(parish: Parish, folder: File, sourceOverride: String? = null): InternetScheduleState {
        fun write(name: String, value: Any?) { File(folder, name).writeText(gson.toJson(value)) }
        write("input.json", mapOf("parish" to parish, "now" to now.toString(), "sourceOverride" to sourceOverride))
        val values = mutableMapOf<String, String>()
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = values[parishId]
            override fun write(parishId: String, value: String) { values[parishId] = value }
        }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
        val calls = mutableListOf<MutableMap<String, Any?>>()
        val transport = ScheduleTransport { url ->
            val call = mutableMapOf<String, Any?>("requested" to url)
            calls += call
            val index = calls.size
            val started = System.nanoTime()
            try {
                network.fetch(url).also { response ->
                    call.putAll(mapOf("final" to response.url, "type" to response.contentType, "bytes" to response.bytes.size))
                    File(folder, "response-$index.body").writeBytes(response.bytes)
                    // Supplemental inspection must not turn a successful fetch into a transport failure.
                    runCatching {
                        response.html()?.let { html ->
                            // Repository handles HTML without calling the native extractor.
                            write("response-$index-html.json", mapOf("links" to ScheduleDocumentLinks.find(html),
                                "document" to HtmlScheduleDocumentExtractor.fromHtml(html)))
                        }
                    }.exceptionOrNull()?.let { call["inspectionError"] = it.toString() }
                }
            } catch (failure: Exception) {
                call["error"] = failure.toString()
                throw failure
            } finally {
                call["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000
                write("requests.json", calls)
            }
        }
        val native = AndroidScheduleDocumentExtractor(context)
        var documentNumber = 0
        val repository = InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response ->
            val index = ++documentNumber
            try {
                native.extract(response).also { document ->
                    write("document-$index.json", mapOf("sourceUrl" to response.url, "document" to document))
                }
            } catch (failure: Exception) {
                write("document-$index-error.json", mapOf("sourceUrl" to response.url, "error" to failure.toString()))
                throw failure
            }
        })
        val result = try { repository.refresh(parish, now, force = true, sourceOverride = sourceOverride) }
        catch (failure: Exception) {
            write("failure.json", mapOf("error" to failure.toString()))
            throw failure
        }
        write("result.json", result)
        if (result.status == InternetScheduleStatus.READY) {
            val offline = InternetScheduleRepository(ScheduleTransport { error("Offline: no request expected") }, cache)
            val reopened = offline.cached(parish)
            write("offline.json", reopened)
            assertEquals("Cache round-trip: $folder", result, reopened)
        }
        return result
    }

    private fun ready(result: InternetScheduleState, folder: File) {
        assertEquals("Inspect $folder/result.json and requests.json", InternetScheduleStatus.READY, result.status)
        assertFalse(result.publicationRejected)
        assertFalse(result.sourceNeedsVerification)
        assertTrue(requireNotNull(result.publication).events.isNotEmpty())
    }
}
