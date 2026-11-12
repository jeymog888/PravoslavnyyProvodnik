package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Public directory -> actual parish source -> native importer -> isolated committed cache.
 * Manual records intentionally have no map ID or website, so the new production fallback is used.
 * No application reading/selection preferences are read or changed; there are no fake providers.
 */
@RunWith(AndroidJUnit4::class)
class LiveMoscowDirectorySchedulesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val now = Instant.parse("2026-11-12T06:00:00Z")

    @Test fun manualMaryinaRecordFindsItsOfficialCardAndPublishedMonthlyPdf() = runBlocking<Unit> {
        verify("maryina", Parish("custom:moscow-live-maryina",
            "Храм Иконы Божией Матери Нечаянная Радость в Марьиной Роще", "Нечаянная Радость", "",
            "Шереметьевская улица, 33 с1", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "",
            55.8055899, 37.61505585), "https://sprav.moseparh.ru/org/1032",
            "https://troitseparaskevo.ru/hram-ikony-bozhiej-materi-nechayannaya-radost-v-marinoj-roshhe/raspisanie/", "pdf")
    }

    @Test fun manualListyRecordFindsItsOwnCardAndPublishedHtml() = runBlocking<Unit> {
        verify("listy", Parish("custom:moscow-live-listy", "Храм Троицы Живоначальной в Листах", "Троицы в Листах", "",
            "улица Сретенка, 27 с3", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "",
            55.7716959, 37.63184935), "https://sprav.moseparh.ru/org/406", "https://hram-troicy.ru/raspisanie/", "html")
    }

    private suspend fun verify(label: String, parish: Parish, expectedEvidence: String, expectedWebsite: String, expectedFormat: String) {
        assumeTrue("Opt in to the current public Moscow directory and parish sources", args.getString("liveMoscowDirectory") == "true")
        val run = requireNotNull(args.getString("moscowDirectoryRun")) { "Use a unique evidence run identifier" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val output = File(requireNotNull(context.getExternalFilesDir("moscow-directory-live")), "$run/$label")
        check(!output.exists() && output.mkdirs()) { "Use a new moscowDirectoryRun for each invocation" }
        val preferenceName = "moscow-directory-live-${UUID.randomUUID()}"
        val requests = mutableListOf<MutableMap<String, Any?>>()
        val searches = mutableListOf<MutableMap<String, Any?>>()
        val documents = mutableListOf<Map<String, Any?>>()
        val requestedUrls = mutableListOf<String>()
        val offlineCalls = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "run" to run, "parish" to parish,
            "apkSha256" to sha256(File(context.applicationInfo.sourceDir)),
            "clock" to now.toString(), "clockScope" to "Fixed 12/13 November assertions against genuinely live publications",
            "expectedEvidence" to expectedEvidence, "expectedWebsite" to expectedWebsite, "expectedFormat" to expectedFormat,
            "cachePreferenceName" to preferenceName, "getRequests" to requests, "directorySearches" to searches, "documents" to documents,
            "statePolicy" to "New UUID test preferences; no main preferences, source override, seeded sources or fake provider",
            "directoryObservationScope" to "Search query arguments and returned search responses; bootstrap and headers are internal to the production transport",
            "statusScope" to "A returned response means HTTP 200 under the two production transport contracts; errors record only their type",
            "offlineScope" to "New repository and cache adapter in the same Android process; committed private preferences, not a cold-process or radio-disabled test")
        fun write(name: String, value: Any?) { File(output, name).writeText(gson.toJson(value)) }
        fun saveReceipt() {
            receipt["offlineCalls"] = offlineCalls.get()
            receipt["directoryInvocationCount"] = searches.size
            receipt["queryCount"] = searches.sumOf { (it["queries"] as? List<*>)?.size ?: 0 }
            write("receipt.json", receipt)
        }
        fun cache(): ScheduleCacheStore = object : ScheduleCacheStore {
            private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            override fun read(parishId: String): String? = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                require(parishId == parish.id && value.length <= 200_000)
                check(preferences.edit().putString(parishId, value).commit())
            }
        }
        saveReceipt()
        try {
            assertEquals("", parish.website); assertEquals("", parish.sourceUrl)
            assertTrue(parish.id.startsWith("custom:"))
            assertNull(cache().read(parish.id))
            val getNetwork = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
            val transport = ScheduleTransport { url ->
                requestedUrls += url
                val observation = linkedMapOf<String, Any?>("requestedUrl" to url, "method" to "GET")
                requests += observation
                val started = System.nanoTime()
                try {
                    getNetwork.fetch(url).also { response ->
                        observation.putAll(mapOf("status" to 200, "returnedUrl" to response.url,
                            "contentType" to response.contentType, "bytes" to response.bytes.size))
                    }
                } catch (failure: Exception) {
                    observation["failureType"] = failure.javaClass.name
                    throw failure
                } finally { observation["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000; saveReceipt() }
            }
            val postNetwork = UrlConnectionMoscowDirectorySearchTransport()
            val directory = MoscowDirectorySearchTransport { queries ->
                val observation = linkedMapOf<String, Any?>("endpoint" to UrlConnectionMoscowDirectorySearchTransport.ENDPOINT,
                    "method" to "POST", "queries" to queries.toList(), "queryCount" to queries.size)
                searches += observation
                val started = System.nanoTime()
                try {
                    postNetwork.search(queries).also { responses ->
                        observation["responses"] = responses.map { response -> mapOf("returnedUrl" to response.url,
                            "status" to 200, "contentType" to response.contentType, "bytes" to response.bytes.size) }
                    }
                } catch (failure: Exception) {
                    observation["failureType"] = failure.javaClass.name
                    throw failure
                } finally { observation["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000; saveReceipt() }
            }
            val native = AndroidScheduleDocumentExtractor(context)
            val extractor = ScheduleDocumentExtractor { response ->
                native.extract(response).also { document ->
                    documents += mapOf("sourceUrl" to response.url, "format" to document?.format, "pages" to document?.pages?.size)
                    saveReceipt()
                }
            }
            val repository = InternetScheduleRepository(transport, cache(), extractor,
                siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore(), directory))
            val result = withTimeout(120_000) { repository.refresh(parish, now, force = true) }
            write("result.json", result)
            receipt["status"] = result.status.name
            receipt["eventCount"] = result.publication?.events?.size
            receipt["evidenceUrl"] = result.siteEvidenceUrl
            receipt["documentFormat"] = result.publication?.documentFormat
            saveReceipt()
            assertEquals(InternetScheduleStatus.READY, result.status)
            assertEquals(parish.id, result.parishId)
            assertEquals("", result.configuredWebsite); assertEquals("", result.customSource)
            assertFalse(result.publicationRejected); assertFalse(result.sourceNeedsVerification)
            assertEquals(expectedEvidence, result.siteEvidenceUrl)
            assertEquals(expectedWebsite, result.website)
            assertTrue(requireNotNull(result.siteBinding).validFor(parish, result.website, result.siteEvidenceUrl))
            val publication = requireNotNull(result.publication)
            assertEquals(expectedFormat, publication.documentFormat)
            assertTrue(requestedUrls.contains(expectedEvidence))
            assertTrue(requestedUrls.contains(expectedWebsite))
            assertTrue("A manual record must use the directory, not another broad lookup", requestedUrls.none {
                it.contains("openstreetmap.org") || it.contains("wikipedia.org") || it.contains("wikidata.org") || it.contains("/palomnik/")
            })
            assertEquals(1, searches.size)
            assertTrue((searches.single()["queries"] as List<*>).size in 1..2)
            for (date in listOf("2026-11-12", "2026-11-13")) {
                val events = publication.forDate(LocalDate.parse(date)).sortedBy { it.time }
                assertEquals("Published starts on $date", listOf("08:00", "17:00"), events.map { it.time })
                assertTrue(events.first().title.contains("Литург", ignoreCase = true))
            }
            val serialized = requireNotNull(cache().read(parish.id))
            assertEquals(result, ScheduleCacheCodec.decode(serialized, parish))
            val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException() }, cache())
            val reopened = offline.cached(parish)
            write("offline-result.json", reopened)
            assertEquals(result, reopened)
            assertEquals(0, offlineCalls.get())
            receipt["codecRoundTrip"] = true
            receipt["freshRepositoryCachedReopen"] = true
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            throw failure
        } finally { saveReceipt() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
