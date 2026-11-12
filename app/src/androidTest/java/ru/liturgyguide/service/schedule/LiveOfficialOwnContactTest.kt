package ru.liturgyguide.service.schedule

import android.content.Context
import android.util.Log
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
import ru.liturgyguide.ui.toSavedChurch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** One real discovery/import attempt; no configured site, fixture responses, app state or radio changes. */
@RunWith(AndroidJUnit4::class)
class LiveOfficialOwnContactTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val serviceNow = Instant.parse("2026-11-12T06:00:00Z")
    private val gson = Gson()
    private val home = "https://nikolskyhram.cerkov.ru/"
    private val contact = home + "kontakty/"
    private val schedule = home + "raspisanie-bogosluzhenij/"
    private val directory = "https://vob.cerkov.ru/xramy/"
    private val osm = "https://api.openstreetmap.org/api/0.6/way/77503842.json"

    @Test fun actualBundledBuildingDiscoversOwnContactImportsElevenServicesAndReopensPrivateCache() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicitly opt in to public live source discovery",
            args.getString("liveOfficialOwnContact") == "allow-public-network")
        val runLabel = args.getString("ownContactNativeRun") ?: "manual"
        require(runLabel.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val unique = UUID.randomUUID().toString()
        val output = File(requireNotNull(context.getExternalFilesDir("own-contact-native")), "$runLabel-$unique")
        check(!output.exists() && output.mkdirs()) { "Each live attempt requires a fresh output directory" }
        val preferenceName = "own-contact-native-$unique"
        val directoryFiles = File(output, "private-directory-cache")
        val calls = mutableListOf<ReturnedBody>()
        val requests = JSONArray()
        val resolutions = JSONArray()
        val offlineCalls = AtomicInteger()
        val report = JSONObject()
            .put("scope", "Actual bundled building -> OpenScheduleSiteResolver -> own-contact proof -> Android document extractor -> committed isolated cache")
            .put("runLabel", runLabel).put("outputDirectory", output.absolutePath)
            .put("startedAt", Instant.now().toString()).put("serviceClock", serviceNow.toString())
            .put("clockScope", "Only the service window and bundled-catalog decode use this fixed date; network responses and proof/directory timestamps are live")
            .put("publishedWeekPolicy", "The inspected 9-15 November 2026 publication and explicit eleven events must match; a changed week fails")
            .put("expectedEvents", JSONArray(gson.toJson(expectedEvents())))
            .put("expectedSource", "app/src/test/resources/schedule/official-own-contact/nikolsky-schedule.html")
            .put("expectedSourceSha256", "0195b428cd8468cacbb8736b4e61e1bdfd3d8436fcc6ab905d7cb83494f752c0")
            .put("expectationScope", "Explicit values transcribed from the saved public page and checked against OfficialOwnContactPipelineTest; live body hashes need not equal the old capture")
            .put("httpStatusScope", "ScheduleResponse does not expose HTTP status or rejected redirect targets; unavailable values are null, never inferred")
            .put("authentication", "No authentication, cookies or headers added by this test; unmodified public production transport")
            .put("offlineScope", "New repository/cache wrappers in the same process; no MainActivity, process death, radio control or human timing claim")
            .put("requests", requests).put("resolutions", resolutions).put("allPassed", false)
        fun writeReport() {
            report.put("offlineTransportCalls", offlineCalls.get())
            File(output, "receipt.json").writeText(report.toString(2), Charsets.UTF_8)
        }
        fun cache(): ScheduleCacheStore = object : ScheduleCacheStore {
            private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            override fun read(parishId: String): String? = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                require(value.length <= 200_000)
                check(preferences.edit().putString(parishId, value).commit())
            }
        }
        Log.i("LiveOfficialOwnContact", "Output: ${output.absolutePath}")
        writeReport()
        try {
            val parish = selectedParish(output, report)
            val selectedBefore = gson.toJson(parish)
            val liveCache = cache()
            assertNull("The schedule cache belongs only to this run", liveCache.read(parish.id))
            assertFalse("The directory cache must also start empty", directoryFiles.exists())
            val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)

            suspend fun fetch(url: String, maximumBytes: Int?): ScheduleResponse {
                val number = requests.length() + 1
                val attempt = JSONObject().put("number", number).put("requestedUrl", url)
                    .put("startedAt", Instant.now().toString()).put("maximumBytes", maximumBytes ?: JSONObject.NULL)
                    .put("finalUrl", JSONObject.NULL).put("httpStatus", JSONObject.NULL)
                    .put("bytes", JSONObject.NULL).put("sha256", JSONObject.NULL).put("error", JSONObject.NULL)
                requests.put(attempt)
                writeReport()
                val started = System.nanoTime()
                try {
                    // Both entry points delegate to the real transport. In particular, do not
                    // replace its streaming/decompression cap with the interface default.
                    val response = if (maximumBytes == null) network.fetch(url) else network.fetchBounded(url, maximumBytes)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val extension = when (response.contentType.substringBefore(';').trim().lowercase()) {
                        "text/html", "application/xhtml+xml" -> "html"
                        "application/json" -> "json"
                        else -> "bin"
                    }
                    val raw = File(output, "response-${number.toString().padStart(3, '0')}.$extension")
                    raw.writeBytes(response.bytes)
                    val hash = sha256(response.bytes)
                    calls += ReturnedBody(url, response.url, maximumBytes, response.contentType, hash, raw)
                    attempt.put("finalUrl", response.url).put("contentType", response.contentType)
                        .put("bytes", response.bytes.size).put("sha256", hash).put("rawFile", raw.name)
                        .put("elapsedMillis", elapsed)
                    return response
                } catch (failure: Throwable) {
                    attempt.put("errorType", failure.javaClass.name).put("error", failure.message?.take(1_000) ?: "")
                        .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    throw failure
                } finally {
                    attempt.put("finishedAt", Instant.now().toString())
                    writeReport()
                }
            }
            val transport = object : ScheduleTransport {
                override suspend fun fetch(url: String): ScheduleResponse = fetch(url, null)
                override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse = fetch(url, maximumBytes)
            }
            fun recordingResolver(delegate: ScheduleSiteResolver): ScheduleSiteResolver = object : ScheduleSiteResolver {
                override fun forRefresh(): ScheduleSiteResolver = recordingResolver(delegate.forRefresh())
                override suspend fun resolve(parish: Parish): ScheduleSite? = resolve(parish, emptySet())
                override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? {
                    val observation = JSONObject().put("number", resolutions.length() + 1)
                        .put("parishId", parish.id).put("excludedUrls", JSONArray(excludedUrls.sorted()))
                        .put("startedAt", Instant.now().toString())
                    resolutions.put(observation)
                    writeReport()
                    val started = System.nanoTime()
                    try {
                        return delegate.resolve(parish, excludedUrls).also { site ->
                            observation.put("site", site?.let { JSONObject(gson.toJson(it)) } ?: JSONObject.NULL)
                        }
                    } catch (failure: Throwable) {
                        observation.put("errorType", failure.javaClass.name).put("error", failure.message?.take(1_000) ?: "")
                        throw failure
                    } finally {
                        observation.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                        writeReport()
                    }
                }
            }
            val resolver = recordingResolver(OpenScheduleSiteResolver(transport, FileOfficialDirectoryCacheStore(directoryFiles)))
            val repository = InternetScheduleRepository(transport, liveCache, AndroidScheduleDocumentExtractor(context), resolver)
            val pipelineStarted = System.nanoTime()
            val ready = try {
                withTimeout(210_000) { repository.refresh(parish, serviceNow, force = true) }
            } finally {
                report.put("pipelineElapsedMillis", (System.nanoTime() - pipelineStarted) / 1_000_000)
                writeReport()
            }
            File(output, "result.json").writeText(gson.toJson(ready), Charsets.UTF_8)
            // Save the actual committed bytes before any assertions, including failed imports.
            val stored = liveCache.read(parish.id)
            stored?.let {
                File(output, "stored-cache.json").writeText(it, Charsets.UTF_8)
                report.put("cacheSha256", sha256(it.toByteArray(Charsets.UTF_8))).put("cacheCharacters", it.length)
            }
            report.put("status", ready.status.name).put("result", JSONObject(gson.toJson(ready)))
                .put("alternateLookupUsed", resolutions.length() == 2)
            writeReport()
            assertEquals("The initial lookup must use the real selected OSM object", osm, requests.getJSONObject(0).getString("requestedUrl"))
            assertTrue("Only the repository's initial lookup and optional alternative are allowed", resolutions.length() in 1..2)
            val requestedUrls = (0 until requests.length()).map { requests.getJSONObject(it).getString("requestedUrl") }
            if (resolutions.length() == 2) {
                val primaryUrl = resolutions.getJSONObject(0).getJSONObject("site").getString("url")
                val excluded = resolutions.getJSONObject(1).getJSONArray("excludedUrls")
                assertTrue("The rejected primary source must have been attempted", primaryUrl in requestedUrls)
                assertTrue("The real alternative lookup must exclude its failed primary source",
                    (0 until excluded.length()).any { excluded.getString(it) == primaryUrl })
            }
            assertEquals(InternetScheduleStatus.READY, ready.status)
            assertEquals(parish.id, ready.parishId)
            assertEquals("", ready.configuredWebsite)
            assertEquals("", ready.customSource)
            assertEquals(home, ready.website)
            assertEquals(contact, ready.siteEvidenceUrl)
            assertEquals(schedule, ready.sourceUrl)
            assertFalse(ready.sourceNeedsVerification)
            assertFalse(ready.publicationRejected)
            assertFalse(ready.sourceHasWithdrawals)
            val binding = requireNotNull(ready.siteBinding)
            val proof = requireNotNull(binding.officialContactProof)
            File(output, "generated-proof.json").writeText(gson.toJson(proof), Charsets.UTF_8)
            assertEquals(ScheduleBindingMethod.DIRECTORY_OWN_CONTACT, binding.method)
            assertTrue(binding.validFor(parish, ready.website, ready.siteEvidenceUrl))
            assertEquals(1, proof.proofVersion)
            assertEquals("voronezh-ortox", proof.publisherProfileId)
            assertEquals(directory, proof.directoryUrl)
            assertEquals("t36237", proof.cardId)
            assertEquals("36237", proof.publisherEntityId)
            assertEquals(OfficialContactLayout.CONTACT_PAGE, proof.layout)
            assertEquals("http://nikolskyhram.cerkov.ru/", proof.cardWebsite)
            assertEquals(proof.cardWebsite, proof.homeRequestedUrl)
            assertEquals(home, proof.homeFinalUrl)
            assertEquals(contact, proof.evidenceRequestedUrl)
            assertEquals(contact, proof.evidenceFinalUrl)
            assertEquals("Храм во имя святителя Николая Чудотворца", proof.ownName)
            assertEquals("Россия, г. Воронеж, ул.Таранченко д.19а", proof.rawAddress)
            assertEquals("воронеж", proof.city)
            assertEquals("улица", proof.streetKind)
            assertEquals("таранченко", proof.street)
            assertEquals("19а", proof.house)
            assertNull(proof.corpus)
            assertNull(proof.building)
            assertEquals(51.6596060, proof.latitude, 0.00000001)
            assertEquals(39.2126847, proof.longitude, 0.00000001)
            val directoryBody = calls.single { it.requested == directory }
            val ownHome = calls.single { it.requested == proof.homeRequestedUrl && it.maximumBytes != null }
            val ownContact = calls.single { it.requested == contact }
            for (url in listOf(directory, proof.homeRequestedUrl, contact, home, schedule)) {
                assertEquals("The single pipeline must not repeat this source: $url", 1, requestedUrls.count { it == url })
            }
            assertEquals(1_000_000, directoryBody.maximumBytes)
            assertEquals(1_000_000, ownHome.maximumBytes)
            assertEquals(1_000_000, ownContact.maximumBytes)
            assertEquals(proof.directorySha256, directoryBody.sha256)
            assertEquals(proof.homeSha256, ownHome.sha256)
            assertEquals(proof.evidenceSha256, ownContact.sha256)
            assertEquals(proof.homeFinalUrl, ownHome.finalUrl)
            assertEquals(proof.evidenceFinalUrl, ownContact.finalUrl)
            val card = requireNotNull(requireNotNull(directoryBody.response().html())
                .getElementById("t36237")?.closest(".site-info"))
            assertEquals("Proof must preserve the actual published card name", card.select("h1").single().text(), proof.cardName)
            val publishedAddress = card.select("dl").single { it.select(".ch,.ttl").text() == "Адрес:" }.select(".cnt").single().text()
            assertEquals(publishedAddress, proof.cardAddress)
            val contactDocument = requireNotNull(ownContact.response().html())
            assertEquals("Контакты", contactDocument.select("article#post-777 h1").single().text())
            assertEquals(proof.rawAddress, contactDocument.select("article#post-777 .entry-content dl.addr dd").single().text())
            assertEquals(proof.ownName, contactDocument.select("#site-title a[rel~=home]").single().text())
            val homeDocument = requireNotNull(ownHome.response().html())
            assertTrue("The fetched homepage must actually publish this contact link",
                homeDocument.select("nav#access a[href],nav#mobile-menu a[href]").any {
                    it.text().trim() == "Контакты" && it.absUrl("href") == contact
                })
            assertTrue("The schedule link must be published on the fetched homepage",
                homeDocument.select("a[href]").any { it.absUrl("href") == schedule })
            assertEquals(1, calls.count { it.requested == home && it.maximumBytes == null })
            val publicationBody = calls.single { it.requested == schedule }
            assertEquals(schedule, publicationBody.finalUrl)
            verifyPublishedWeek(publicationBody, report)
            val publication = requireNotNull(ready.publication)
            assertEquals(parish.id, publication.parishId)
            assertEquals("Europe/Moscow", publication.timezone)
            assertEquals(home, publication.website)
            assertEquals(home, publication.linkedFrom)
            assertEquals(schedule, publication.sourceUrl)
            assertEquals("html", publication.documentFormat)
            assertEquals(serviceNow.toString(), publication.fetchedAt)
            assertFalse("The real source explicitly prints 2026", publication.yearInferred)
            assertEquals("Exact dates, start times, titles and block semantics from the inspected publication",
                expectedEvents(), publication.events)
            assertEquals(selectedBefore, gson.toJson(parish))
            File(output, "selected-parish-after.json").writeText(gson.toJson(parish), Charsets.UTF_8)
            report.put("selectedSourceFieldsUnchanged", true)

            val persisted = requireNotNull(stored)
            assertTrue(persisted.length <= 200_000)
            assertFalse("Compact proof must not embed the fetched HTML", persisted.contains("<html", ignoreCase = true))
            val offlineTransport = object : ScheduleTransport {
                override suspend fun fetch(url: String): ScheduleResponse {
                    offlineCalls.incrementAndGet()
                    throw IOException("Private-cache acceptance forbids transport: $url")
                }
                override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse = fetch(url)
            }
            val offline = InternetScheduleRepository(offlineTransport, cache(), AndroidScheduleDocumentExtractor(context),
                directoryCache = FileOfficialDirectoryCacheStore(directoryFiles))
            val reopened = offline.cached(parish)
            File(output, "offline-cached.json").writeText(gson.toJson(reopened), Charsets.UTF_8)
            assertEquals("A new repository must restore the entire persisted state and proof", ready, reopened)
            val refreshed = offline.refresh(parish, serviceNow.plusSeconds(60), force = false)
            File(output, "offline-fresh-refresh.json").writeText(gson.toJson(refreshed), Charsets.UTF_8)
            assertEquals("A still-fresh refresh must preserve source, proof and all events", ready, refreshed)
            assertEquals(proof, reopened.siteBinding?.officialContactProof)
            assertEquals(proof, refreshed.siteBinding?.officialContactProof)
            assertEquals(0, offlineCalls.get())
            assertEquals("Offline reads must not rewrite persisted source fields", persisted, cache().read(parish.id))
            report.put("offlineCachedStateEqual", true).put("offlineFreshRefreshEqual", true)
                .put("persistedBytesUnchanged", true).put("allPassed", true)
        } catch (failure: Throwable) {
            report.put("failureType", failure.javaClass.name).put("failure", failure.message?.take(2_000) ?: "")
            File(output, "failure.txt").writeText(failure.stackTraceToString(), Charsets.UTF_8)
            throw failure
        } finally {
            // Delete only this test's UUID preference namespace. Public source receipts and
            // its private directory snapshot remain in output for root's inspection.
            val deleted = context.deleteSharedPreferences(preferenceName)
            report.put("privatePreferencesDeleted", deleted).put("finishedAt", Instant.now().toString())
            if (!deleted) report.put("allPassed", false)
            writeReport()
            assertTrue("Only the owned private preferences must be cleaned up", deleted)
        }
    }

    private suspend fun selectedParish(output: File, report: JSONObject): Parish {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(BundledChurchCatalog.ASSET_PATH).use { input ->
            val buffer = ByteArray(16 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        report.put("bundledAsset", BundledChurchCatalog.ASSET_PATH).put("bundledAssetSha256", hex(digest.digest()))
        val catalog = requireNotNull(BundledChurchCatalog(
            { context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8) },
            Clock.fixed(serviceNow, ZoneOffset.UTC)).get())
        val selected = catalog.nearby(GeoPoint(51.6596207, 39.2127643), 200) {}
            .single { it.id == "osm:way:77503842" }
        File(output, "selected-bundled-building.json").writeText(gson.toJson(selected), Charsets.UTF_8)
        assertEquals("Храм Николая Чудотворца (Воронеж)", selected.name)
        assertEquals("Воронеж", selected.city)
        assertEquals("Воронеж, улица Таранченко, 19А", selected.address)
        assertEquals("https://www.openstreetmap.org/way/77503842", selected.sourceUrl)
        assertEquals(51.6596207, selected.center.latitude, 0.00000001)
        assertEquals(39.2127643, selected.center.longitude, 0.00000001)
        assertTrue("The asset must not preconfigure a website", selected.website.isNullOrBlank())
        return requireNotNull(selected.toSavedChurch()).asParish().also {
            assertEquals("", it.website)
            assertEquals(selected.name, it.name)
            assertEquals(selected.address, it.address)
            assertEquals(selected.sourceUrl, it.sourceUrl)
            File(output, "selected-parish.json").writeText(gson.toJson(it), Charsets.UTF_8)
        }
    }

    private fun verifyPublishedWeek(body: ReturnedBody, report: JSONObject) {
        val document = requireNotNull(body.response().html())
        val content = document.select("article#post-781 .entry-content").single()
        val paragraphs = content.select("p").filter { it.select("em").isNotEmpty() }
        val days = paragraphs.map { it.select("em").single().text() }
        val clocks = paragraphs.map { paragraph -> Regex("(?<![0-9])[0-9]{2}\\.[0-9]{2}(?![0-9])")
            .findAll(paragraph.text()).map { it.value }.toList() }
        report.put("publishedDayLabels", JSONArray(days)).put("publishedClocks", JSONArray(clocks))
            .put("publicationRawFile", body.file.name).put("publicationLiveSha256", body.sha256)
        assertTrue("The source's own publication must still explicitly cover the inspected week",
            Regex("9\\s*[—–-]\\s*15 ноября 2026 года").containsMatchIn(content.text()))
        assertEquals(listOf("9 ноября (понедельник)", "10 ноября (вторник)", "11 ноября (среда)",
            "12 ноября (четверг)", "13 ноября (пятница)", "14 ноября (суббота)", "15 ноября (воскресенье)"), days)
        assertEquals(listOf(listOf("07.00", "17.00"), listOf("07.00", "17.00"), listOf("07.00", "17.00"),
            listOf("07.00", "17.00"), listOf("07.00", "17.00"), listOf("07.00", "17.00"),
            listOf("06.00", "08.00", "17.00")), clocks)
    }

    // Independent expectations from the saved, explicitly dated 9-15 November publication.
    // "Часы. Литургия" names a block start, not an exact liturgy-start promise.
    private fun expectedEvents() = listOf(
        InternetService("2026-11-11", "07:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-11", "17:00", "Утреня. Акафист святителю Николаю Чудотворцу. Водосвятный молебен", "service_block"),
        InternetService("2026-11-12", "07:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-12", "17:00", "Вечерня. Полиелейная утреня", "service_block"),
        InternetService("2026-11-13", "07:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-13", "17:00", "Вечерня. Утреня", "service_block"),
        InternetService("2026-11-14", "07:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-14", "17:00", "Всенощное бдение", "service_block"),
        InternetService("2026-11-15", "06:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-15", "08:00", "Часы. Литургия", "service_block"),
        InternetService("2026-11-15", "17:00", "Вечерня. Утреня", "service_block"),
    )

    private data class ReturnedBody(val requested: String, val finalUrl: String, val maximumBytes: Int?,
        val contentType: String, val sha256: String, val file: File) {
        fun response() = ScheduleResponse(finalUrl, file.readBytes(), contentType)
    }
    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
