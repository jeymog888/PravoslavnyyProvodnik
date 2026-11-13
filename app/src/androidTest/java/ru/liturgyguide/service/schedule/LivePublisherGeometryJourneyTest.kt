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
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real public HTTP pipeline. UUID repositories, no source override, UI or cold-process claim. */
@RunWith(AndroidJUnit4::class)
class LivePublisherGeometryJourneyTest {
    @Test fun automaticOfficialSourcesLoadTwoDatedLiturgiesAndReopenExactEditionsOffline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with publisherGeometryLive=true", args.getString("publisherGeometryLive") == "true")
        val run = requireNotNull(args.getString("publisherGeometryRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir("publisher-geometry-automatic")), run)
        check(!output.exists() && output.mkdirs()) { "Use a new publisherGeometryRun" }
        val prefix = "publisher-geometry-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, prefix)
        val schedulePreferences = context.getSharedPreferences("$prefix-schedule", Context.MODE_PRIVATE)
        val readingPreferences = context.getSharedPreferences("$prefix-reading", Context.MODE_PRIVATE)
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = schedulePreferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(schedulePreferences.edit().putString(parishId, value).commit()) }
        }
        val selected = Parish("osm:way:155168069", "Архиерейское подворье. Храм в честь Вознесения Господня",
            "Храм Вознесения Господня", "", "", "", "", "Europe/Moscow", "https://www.openstreetmap.org/way/155168069",
            "", "", "", emptyList(), "", "", 54.5305961, 36.3084198)
        val requests = CopyOnWriteArrayList<Map<String, Any?>>()
        val rows = mutableListOf<Map<String, Any?>>()
        val gson = GsonBuilder().setPrettyPrinting().create()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "run" to run, "selected" to selected,
            "startedAtUtc" to Instant.now().toString(), "apkSha256" to sha(File(context.applicationInfo.sourceDir)),
            "sourceOverride" to null, "scope" to "Real production automatic resolver, public HTTP responses, dated original books and exact disk editions",
            "isolation" to "New UUID preferences and cache; application guide selection and accepted editions are not modified",
            "offlineScope" to "New repositories in this instrumentation process with rejecting transports; radios and cold Main restart are not covered")
        fun save() {
            receipt["requests"] = requests.toList(); receipt["books"] = rows.toList()
            File(output, "receipt.json").writeText(gson.toJson(receipt))
        }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
        val transport = object : ScheduleTransport {
            override suspend fun fetch(url: String) = fetchBounded(url, AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES)
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse = try {
                network.fetchBounded(url, maximumBytes).also { response ->
                    requests += mapOf("requestedUrl" to url, "finalUrl" to response.url, "bytes" to response.bytes.size,
                        "sha256" to sha(response.bytes), "httpStatus" to 200,
                        "statusEvidence" to "Production transport returns a response only for final HTTP200")
                }
            } catch (failure: Exception) {
                requests += mapOf("requestedUrl" to url, "failureType" to failure.javaClass.name)
                throw failure
            } finally { save() }
        }
        save()
        try {
            withTimeout(240_000) {
                assertTrue(listOf(selected.city, selected.address, selected.website).all(String::isBlank))
                val schedules = InternetScheduleRepository(transport, cache, AndroidScheduleDocumentExtractor(context),
                    siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore()))
                val state = schedules.refresh(selected, Instant.parse("2026-11-13T04:00:00Z"), force = true)
                File(output, "schedule.json").writeText(gson.toJson(state))
                assertEquals(InternetScheduleStatus.READY, state.status)
                assertEquals("", state.customSource); assertEquals("", state.configuredWebsite)
                assertFalse(state.sourceNeedsVerification); assertFalse(state.publicationRejected)
                assertEquals("http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/", state.website)
                val binding = requireNotNull(state.siteBinding)
                assertEquals(ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN, binding.method)
                assertTrue(binding.validFor(selected, state.website, state.siteEvidenceUrl))
                val proof = requireNotNull(binding.publisherGeometryProof)
                assertEquals(setOf("t20385", "t21054", "t20703"), proof.passports.map { it.id }.toSet())
                val publication = requireNotNull(state.publication)
                assertEquals(listOf("08:00", "17:00"), publication.forDate(LocalDate.parse("2026-11-13")).map { it.time })
                assertEquals(listOf("07:00", "09:00", "17:00"), publication.forDate(LocalDate.parse("2026-11-15")).map { it.time })
                assertTrue(publication.yearInferred)
                receipt["sourceTransportCalls"] = requests.size
                receipt["sourceProofPages"] = proof.evidence.size
                val online = ServiceBookRepository(FileBookCache(directory), transport,
                    translations = BookTranslationIndex.fromFeoriaCatalog(), calendar = PublishedCalendarSupplements(transport))
                val store = BookReadingStore(readingPreferences)
                val forbidden = AtomicInteger()
                val offlineTransport = ScheduleTransport { forbidden.incrementAndGet(); throw IOException("Offline") }
                for ((date, time) in listOf("2026-11-13" to "08:00", "2026-11-15" to "09:00")) {
                    val event = publication.forDate(LocalDate.parse(date)).single { it.time == time }
                    val route = requireNotNull(ServiceBookSelection.resolve(ParishService(event.date, event.time, event.title, event.kind), selected))
                    // This publication names liturgy only. Do not invent an hours block or an extra start.
                    assertEquals(WorshipKind.LITURGY, route.activeKey.worship)
                    assertEquals(date, route.activeKey.date); assertNull(route.journeyContext)
                    val loaded = online.load(route.activeKey)
                    assertTrue("Dated original must load: $loaded", loaded is BookLoadResult.Ready)
                    val ready = loaded as BookLoadResult.Ready
                    val book = ready.book
                    assertTrue(ready.saved); assertFalse(ready.fromCache)
                    assertEquals(route.activeKey, book.key); assertTrue(ServiceBookIntegrity.valid(book))
                    assertTrue(book.blocks.size > 200)
                    assertTrue(book.chapters.any { BookText.normalized(it.title) == "отпуст" })
                    val chapter = book.chapters.first { it.blocks.isNotEmpty() }
                    val position = BookReadingPosition(book.key, book.contentHash, selected.id, chapter.blocks.first().id,
                        chapter.id, fraction = .25f, mode = BookReadingMode.ORIGINAL)
                    assertTrue(store.save(position))
                    val offline = ServiceBookRepository(FileBookCache(directory), offlineTransport,
                        calendar = CalendarSupplements { forbidden.incrementAndGet(); error("Offline calendar") })
                    val reopened = offline.load(book.key)
                    assertTrue(reopened is BookLoadResult.Ready && reopened.fromCache && reopened.saved)
                    assertEquals(book, (reopened as BookLoadResult.Ready).book)
                    assertEquals(book, offline.cachedVersion(book.key, book.contentHash))
                    assertEquals(position, BookReadingStore(readingPreferences).positionFor(book.key, selected.id))
                    rows += mapOf("date" to date, "publishedTime" to time, "eventTitle" to event.title,
                        "key" to book.key.canonical(), "contentHash" to book.contentHash, "blocks" to book.blocks.size,
                        "offlineExact" to true)
                    save()
                }
                assertEquals(state, InternetScheduleRepository(offlineTransport, cache).cached(selected))
                assertEquals(0, forbidden.get())
                receipt["offlineCalls"] = forbidden.get(); receipt["passed"] = true
            }
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name; receipt["failure"] = failure.message?.take(500)
            throw failure
        } finally {
            save()
            context.deleteSharedPreferences("$prefix-schedule"); context.deleteSharedPreferences("$prefix-reading")
            directory.deleteRecursively()
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
