package ru.liturgyguide.service.schedule

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.DiscoveredPlaceKind
import ru.liturgyguide.service.discovery.GeoPoint
import ru.liturgyguide.service.discovery.LocalChurchCatalog
import ru.liturgyguide.ui.toSavedChurch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real manual-source import and book publication on Android, without changing the user's selected church. */
@RunWith(AndroidJUnit4::class)
class LiveConfirmedScheduleReadingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-13T00:00:00Z")
    private val source = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
    private val auditSha = "e16504d5d07def554be6b230b204d576e32b7ff88b9405083c1b4e20c461dd9a"

    @Test fun aManuallyConfirmedNovosibirskSourceSelectsThePublishedFridayLiturgyAndReopensItOffline() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real Android network requires feoriaLiveNetwork=true", args.getString("feoriaLiveNetwork") == "true")
        val name = "confirmed-reading-${UUID.randomUUID()}"
        val run = args.getString("confirmedScheduleReadingRun") ?: name
        require(run.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "confirmed-schedule-reading/$run")
        check(!output.exists() && output.mkdirs()) { "Use a fresh confirmedScheduleReadingRun: $output" }
        val root = File(context.cacheDir, name).apply { check(mkdirs()) }
        val preferenceNames = CopyOnWriteArrayList<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getSharedPreferences(preferenceName: String, mode: Int): SharedPreferences {
                val privateName = "$name-$preferenceName"
                preferenceNames += privateName
                return context.getSharedPreferences(privateName, mode)
            }
        }
        val requestRecords = CopyOnWriteArrayList<JSONObject>()
        val offlineCalls = AtomicInteger()
        val reportLock = Any()
        val report = JSONObject().put("status", "running").put("clock", now.toString())
            .put("liveFetchClock", "Actual device/network time; service date is fixed and a changed published week must fail")
            .put("scope", "Production AppContainer with production repositories and Android transport/extractor; explicit sourceOverride; UUID preferences/files/cache; no UI or process-death claim")
            .put("manualSource", source).put("sourceAuditReferenceSha256", auditSha)
            .put("sourceIdentityScope", "Source supplied explicitly through the existing manual contract; automatic monastery-to-church binding is not asserted")
            .put("mainSelectionChanged", false).put("uiTested", false).put("coldProcessTested", false)
        fun writeReport() = synchronized(reportLock) {
            report.put("requests", JSONArray(requestRecords.toList())).put("offlineTransportCalls", offlineCalls.get())
            File(output, "receipt.json").writeText(report.toString(2))
        }
        fun recorded(label: String, delegate: ScheduleTransport): ScheduleTransport = ScheduleTransport { url ->
            val started = System.nanoTime()
            val record = JSONObject().put("phase", label).put("requested", url).put("startedAt", Instant.now().toString())
            requestRecords += record
            writeReport()
            try {
                delegate.fetch(url).also { response ->
                    synchronized(reportLock) {
                        record.put("returned", response.url).put("contentType", response.contentType)
                            .put("bytes", response.bytes.size).put("sha256", sha256(response.bytes))
                            .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    }
                    if (url == source) {
                        File(output, "live-schedule-source.html").writeBytes(response.bytes)
                        synchronized(reportLock) {
                            report.put("liveSourceSha256", sha256(response.bytes)).put("matchesAuditedSnapshot", sha256(response.bytes) == auditSha)
                        }
                        inspectActualWeek(response, report)
                    }
                    writeReport()
                }
            } catch (failure: Throwable) {
                synchronized(reportLock) { record.put("failureType", failure.javaClass.name).put("failure", failure.message?.take(500)) }
                writeReport()
                throw failure
            }
        }
        val schedulePreferences = isolated.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        fun scheduleCache(): ScheduleCacheStore = object : ScheduleCacheStore {
            override fun read(parishId: String): String? = schedulePreferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(schedulePreferences.edit().putString(parishId, value).commit()) }
        }
        try {
            val parish = selectedParish()
            report.put("selectedParish", JSONObject(Gson().toJson(parish))).put("parishLocalClock", now.atZone(ZoneId.of(parish.timezone)).toString())
            assertEquals("2026-11-13T07:00", now.atZone(ZoneId.of(parish.timezone)).toLocalDateTime().toString())
            val scheduleTransport = recorded("schedule", UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true))
            val directories = FileOfficialDirectoryCacheStore(File(isolated.cacheDir, "directories"))
            val schedules = InternetScheduleRepository(scheduleTransport, scheduleCache(), AndroidScheduleDocumentExtractor(isolated),
                productionScheduleSiteResolver(scheduleTransport, directories), directories)
            val bookDirectory = File(isolated.filesDir, "service-books-v2")
            val bookTransport = recorded("book", UrlConnectionScheduleTransport(timeoutMillis = 15_000))
            val calendarTransport = recorded("calendar-supplement", UrlConnectionScheduleTransport(timeoutMillis = 3_000,
                limitBytes = PublishedCalendarSupplements.MAX_BYTES))
            val onlineBooks = ServiceBookRepository(FileBookCache(bookDirectory), bookTransport,
                translations = BookTranslationIndex.fromFeoriaCatalog(), clock = Clock.fixed(now, ZoneOffset.UTC),
                calendar = PublishedCalendarSupplements(calendarTransport))
            val container = AppContainer(isolated, scheduleOverride = schedules, booksOverride = onlineBooks)
            assertNull(scheduleCache().read(parish.id))
            val imported = withTimeout(120_000) { container.internetSchedules.refresh(parish, now, force = true, sourceOverride = source) }
            File(output, "schedule-state.json").writeText(Gson().toJson(imported))
            report.put("scheduleStatus", imported.status.name)
            writeReport()
            assertEquals(InternetScheduleStatus.READY, imported.status)
            assertEquals(parish.id, imported.parishId)
            assertEquals(source, imported.customSource)
            assertEquals(source, imported.sourceUrl)
            assertFalse(imported.sourceNeedsVerification)
            assertFalse(imported.publicationRejected)
            val publication = requireNotNull(imported.publication)
            assertEquals(parish.id, publication.parishId)
            assertEquals("Asia/Novosibirsk", publication.timezone)
            assertEquals(now.toString(), publication.fetchedAt)
            assertTrue("The schedule table does not publish a year", publication.yearInferred)
            val friday = publication.events.filter { it.date == "2026-11-13" }
            assertEquals(listOf("08:30", "17:00"), friday.map { it.time })
            val morning = friday.single { it.time == "08:30" }
            val evening = friday.single { it.time == "17:00" }
            // The publication groups Hours and Liturgy into one timed event. Its
            // reading action is resolved from the actual title below, not this envelope kind.
            assertEquals("service_block", morning.kind)
            assertTrue(morning.title.contains("Часы") && morning.title.contains("Божественная Литургия"))
            assertTrue(evening.title.startsWith("Вечернее богослужение"))
            assertEquals("service_block", evening.kind)
            report.put("yearInferred", publication.yearInferred).put("fridayEvents", JSONArray(Gson().toJson(friday)))
            val publishedParish = parish.withInternetSchedule(imported, now)
            assertTrue(publishedParish.scheduleNote.contains("Год не указан"))
            val publishedEvent = publishedParish.schedule.single { it.date == morning.date && it.time == morning.time && it.title == morning.title }
            val selected = requireNotNull(ServiceBookSelection.resolve(publishedEvent, publishedParish))
            val key = ServiceBookKey("2026-11-13", WorshipKind.LITURGY, "30")
            assertEquals(parish.id, selected.parishId)
            assertEquals(key, selected.activeKey)
            assertNull(FileBookCache(bookDirectory).read(key))
            val fetched = withTimeout(240_000) { container.serviceBooks.load(selected.activeKey) }
            assertTrue("Actual dated service book: $fetched", fetched is BookLoadResult.Ready)
            val ready = fetched as BookLoadResult.Ready
            assertTrue(ready.saved)
            assertFalse("The UUID book cache starts empty", ready.fromCache)
            assertEquals(key, ready.book.key)
            assertEquals(now.toEpochMilli(), ready.book.fetchedAtMillis)
            assertTrue(ServiceBookIntegrity.valid(ready.book))
            assertTrue(ready.book.blocks.size > 200)
            assertEquals(ready.book, FileBookCache(bookDirectory).readVersion(key, ready.book.contentHash))
            val denied = ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException("Every network request is forbidden during offline reopen") }
            val offlineBooks = ServiceBookRepository(FileBookCache(bookDirectory), denied,
                calendar = PublishedCalendarSupplements(denied))
            val reopened = offlineBooks.load(key)
            assertTrue(reopened is BookLoadResult.Ready)
            val offlineReady = reopened as BookLoadResult.Ready
            assertTrue(offlineReady.fromCache)
            assertEquals(ready.book, offlineReady.book)
            assertNotSame(ready.book, offlineReady.book)
            val offlineSchedules = InternetScheduleRepository(denied, scheduleCache(), AndroidScheduleDocumentExtractor(isolated))
            assertEquals(imported, offlineSchedules.cached(parish))
            assertEquals(imported, offlineSchedules.refresh(parish, now.plusSeconds(60), force = false))
            assertEquals(0, offlineCalls.get())
            report.put("bookRoute", JSONObject(Gson().toJson(selected))).put("bookKey", key.canonical())
                .put("bookHash", ready.book.contentHash).put("bookBlocks", ready.book.blocks.size).put("bookSourceUrl", ready.book.sourceUrl)
                .put("bookSaved", true).put("bookInitiallyFromCache", false).put("offlineExactEditionEqual", true)
                .put("offlineScheduleEqual", true).put("offlineScope", "New repositories over isolated committed preferences/FileBookCache in the same process")
                .put("eveningScope", "17:00 verified as a real schedule event; the loaded book belongs to the 08:30 Liturgy")
                .put("status", "passed")
        } catch (failure: Throwable) { report.put("status", "failed").put("failureType", failure.javaClass.name).put("failure", failure.message?.take(1000)); throw failure }
        finally { writeReport(); preferenceNames.toSet().forEach { context.deleteSharedPreferences(it) }; root.deleteRecursively() }
    }

    private fun selectedParish(): Parish {
        val catalog = context.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader().use { LocalChurchCatalog.decode(it, now) }
        val selected = catalog.nearby(GeoPoint(54.97553165, 82.9086244), 200) {}.single { it.id == "osm:way:55228075" }
        assertEquals("Храм в честь Святых Новомучеников и Исповедников Российских", selected.name)
        assertEquals("улица Немировича-Данченко, 120/1", selected.address)
        assertEquals("", selected.city)
        assertEquals(DiscoveredPlaceKind.CHURCH, selected.kind)
        assertEquals("https://www.openstreetmap.org/way/55228075", selected.sourceUrl)
        assertEquals(54.97553165, selected.center.latitude, .00000001)
        assertEquals(82.9086244, selected.center.longitude, .00000001)
        assertTrue(selected.website.isNullOrBlank())
        return requireNotNull(selected.toSavedChurch()).asParish().also { assertEquals("Asia/Novosibirsk", it.timezone) }
    }
    private fun inspectActualWeek(response: ScheduleResponse, report: JSONObject) {
        assertEquals(source, response.url)
        val document = requireNotNull(response.html())
        val table = document.select("article .entry-content table").single { it.text().contains("13 ноября") }
        val dates = table.select("td[rowspan]").map { it.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }
        assertEquals((9..15).map { "$it ноября" }, dates.map { Regex("[0-9]{1,2} ноября").find(it)?.value })
        assertTrue(dates.single { it.startsWith("13 ноября") }.contains("пятница"))
        assertFalse("Unrelated footer years must not make the schedule year explicit", Regex("\\b2026\\b").containsMatchIn(table.text()))
        val day = table.select("tr").single { row -> row.selectFirst("td[rowspan]")?.text()?.trim()?.startsWith("13 ноября") == true }
        val morning = requireNotNull(day.nextElementSibling()).select("td").map { it.text().trim() }
        val evening = requireNotNull(day.nextElementSibling()?.nextElementSibling()).select("td").map { it.text().trim() }
        assertEquals("8:30", morning[0])
        assertEquals("Часы. Божественная Литургия.", morning[1])
        assertEquals("17:00", evening[0])
        assertEquals("Вечернее богослужение.", evening[1])
        report.put("rawSourceWeek", JSONArray(dates)).put("rawFridayMorning", JSONArray(morning)).put("rawFridayEvening", JSONArray(evening))
            .put("explicitYearInScheduleTable", false)
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
