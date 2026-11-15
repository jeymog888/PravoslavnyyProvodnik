package ru.liturgyguide.service.schedule

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.TodayServiceSelection
import ru.liturgyguide.service.book.*
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Recorded known case, actual native extraction and disk repositories. No UI, live network or process restart. */
@RunWith(AndroidJUnit4::class)
class SharedVenueSourceToBookNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val gson = Gson()
    private fun bytes(name: String) = instrumentation.context.assets.open("shared-venue-case039-native/$name").use { it.readBytes() }
    private fun json(name: String) = JsonParser.parseString(bytes(name).toString(Charsets.UTF_8)).asJsonObject
    private fun sha(value: ByteArray) = ScheduleSourceCandidate.sha(value)
    private fun rows(value: JsonObject, name: String) = value.getAsJsonArray(name).map { it.asJsonObject }
    private fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to sha(it.readBytes())
    }
    private fun response(row: JsonObject): ScheduleResponse {
        val body = bytes(row["asset"].asString)
        assertEquals(row["bytes"].asInt, body.size)
        assertEquals(row["sha256"].asString, sha(body))
        return ScheduleResponse(row["finalUrl"].asString, body, row["contentType"].asString)
    }

    @Test fun recordedCase039SelectsJointNovember22AndReopensAllThreePartsOffline() = runBlocking<Unit> {
        val manifest = json("manifest.json")
        assertEquals("7d80d3c82a183dc8f59257c7ad552e56d10b65185b7924e51c83cd4429822b8d", sha(bytes("manifest.json")))
        rows(manifest, "fixtureFiles").forEach { row ->
            val body = bytes(row["asset"].asString)
            assertEquals(row["asset"].asString, row["bytes"].asInt, body.size)
            assertEquals(row["asset"].asString, row["sha256"].asString, sha(body))
        }
        val expected = json("EXPECTED-039.json")
        assertEquals("f1dd0cde0af9b2b7c5e240f855e90d5b55ce3084cf286eedc2fda26b3b25858d", sha(bytes("EXPECTED-039.json")))
        assertEquals("e541d18fd63542b56deef32a54b0be13418e0071b5db3e28935955919887ec34", sha(bytes("calls.json")))
        val selected = json("selected.json")
        val row = selected.getAsJsonObject("selected")
        val point = row.getAsJsonObject("center")
        val parish = Parish(row["id"].asString, row["name"].asString, "", row["city"].asString,
            row["address"].asString, "", row["website"].asString, selected["timezone"].asString,
            row["sourceUrl"].asString, "", "", "", emptyList(), "", "",
            point["latitude"].asDouble, point["longitude"].asDouble)
        assertEquals(expected["selectedId"].asString, parish.id)
        val now = Instant.parse(selected["clock"].asString)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val scheduleRows = rows(manifest, "scheduleResponses")
        val bookRows = rows(manifest, "bookResponses")
        val originalCalls = JsonParser.parseString(bytes("calls.json").toString(Charsets.UTF_8)).asJsonArray
            .map { it.asJsonObject }.filter { it["type"].asString == "GET_RESPONSE" }
        assertEquals(6, originalCalls.size)
        assertEquals(originalCalls.map { it["url"].asString }.toSet(), scheduleRows.map { it["requestedUrl"].asString }.toSet())
        originalCalls.forEach { call ->
            val saved = scheduleRows.single { it["requestedUrl"].asString == call["url"].asString }
            assertEquals(call["originalSha256"].asString, call["writtenSha256"].asString)
            assertEquals(call["writtenSha256"].asString, saved["sha256"].asString)
        }

        val name = "shared-venue-case039-${UUID.randomUUID()}"
        val owned = File(app.cacheDir, name).apply { check(!exists()) }
        val output = File(requireNotNull(app.getExternalFilesDir("shared-venue-case039-native")), name)
            .apply { check(!exists() && mkdirs()) }
        val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(owned, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(owned, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getSharedPreferences(preferenceName: String, mode: Int): SharedPreferences {
                val privateName = "$name-$preferenceName"
                preferenceNames += privateName
                return app.getSharedPreferences(privateName, mode)
            }
        }
        val scheduleRequests = CopyOnWriteArrayList<String>()
        val servedBooks = CopyOnWriteArrayList<String>()
        val unavailableBookExtras = CopyOnWriteArrayList<String>()
        val nativeSources = ConcurrentHashMap.newKeySet<String>()
        val offlineCalls = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "clock" to now.toString(), "selected" to row,
            "classification" to "KNOWN_DEVELOPMENT_CASE039_NOT_INDEPENDENT_SAMPLE",
            "bookInputRole" to "HOST_CANDIDATE_NOT_ANDROID_ROUTE_PROOF",
            "scope" to "Production repositories, actual native extractor, recorded bytes, all3 disk reopen in the same process",
            "notMeasured" to listOf("UI", "human20seconds", "physicalProcessRestart", "fullRussianTranslation", "performedLocalOrder", "publicationCompleteness"))
        try {
            check(owned.mkdirs())
            withTimeout(240_000) {
                val schedulePreferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
                fun cache() = object : ScheduleCacheStore {
                    override val coordinationLock: Any get() = schedulePreferences
                    override fun read(parishId: String) = schedulePreferences.getString(parishId, null)
                    override fun write(parishId: String, value: String) {
                        check(schedulePreferences.edit().putString(parishId, value).commit())
                    }
                }
                val scheduleTransport = ScheduleTransport { url ->
                    scheduleRequests += url
                    response(scheduleRows.singleOrNull { it["requestedUrl"].asString == url }
                        ?: throw IOException("No recorded case039 schedule response: $url"))
                }
                val extractor = AndroidScheduleDocumentExtractor(context) { nativeSources += it.sourceUrl }
                val schedules = InternetScheduleRepository(scheduleTransport, cache(), extractor)
                assertNull(cache().read(parish.id))
                val state = schedules.refresh(parish, now, force = true)
                receipt["scheduleState"] = state
                assertEquals(InternetScheduleStatus.READY, state.status)
                assertEquals(parish.id, state.parishId)
                assertEquals("", state.customSource)
                assertFalse(state.publicationRejected); assertFalse(state.sourceNeedsVerification)
                val publication = requireNotNull(state.publication)
                assertEquals(parish.id, publication.parishId)
                assertEquals(parish.timezone, publication.timezone)
                assertEquals("https://hram-obuhovo.ru/", publication.website)
                assertEquals("https://hram-obuhovo.ru/raspisanie/", publication.sourceUrl)
                assertEquals(now.toString(), publication.fetchedAt)
                assertFalse(publication.yearInferred)
                val expectedEvents = expected.getAsJsonArray("expectedEvents").map { gson.fromJson(it, InternetService::class.java) }
                assertEquals(15, expectedEvents.size)
                assertEquals("Exact root predeclared original12 plus joint3", expectedEvents, publication.events)
                expected.getAsJsonArray("forbiddenForeignOnlyStarts").forEach { forbidden ->
                    assertFalse(publication.events.any { it.date == forbidden.asJsonObject["date"].asString &&
                        it.time == forbidden.asJsonObject["time"].asString })
                }
                assertEquals(scheduleRows.map { it["requestedUrl"].asString }.sorted(), scheduleRequests.sorted())
                val imageUrls = scheduleRows.filter { it["contentType"].asString.startsWith("image/") }
                    .map { it["requestedUrl"].asString }.toSet()
                assertEquals("All three original poster responses passed actual native OCR", imageUrls, nativeSources.toSet())

                val publishedParish = parish.withInternetSchedule(state, now)
                val featured = requireNotNull(TodayServiceSelection.pick(publishedParish,
                    now.atZone(ZoneId.of(parish.timezone)).toLocalDate(), now, lookAhead = true))
                val first = expected.getAsJsonObject("firstUpcomingSourceRemains")
                assertEquals(first["date"].asString, featured.service.date)
                assertEquals(first["time"].asString, featured.service.time)
                assertEquals(first["title"].asString, featured.service.title)
                assertNull("Nearest source-default akathist stays unsupported", ServiceBookSelection.resolve(featured.service, publishedParish))
                receipt["sourceDefaultFeatured"] = featured.service
                receipt["sourceDefaultBookState"] = "PUBLISHED_SERVICE_KIND_NOT_SUPPORTED"

                val event = publication.forDate(LocalDate.parse("2026-11-22")).single { it.time == "09:00" }
                val service = ParishService(event.date, event.time, event.title, event.kind)
                val route = requireNotNull(ServiceBookSelection.resolve(service, parish))
                val journey = requireNotNull(route.journeyContext)
                val parts = journey.parts
                assertEquals(parish.id, route.parishId); assertEquals(parish.id, journey.parishId)
                assertEquals("2026-11-22", journey.civilDate); assertEquals(event.time, journey.time); assertEquals(event.title, journey.title)
                assertEquals("service_block", event.kind)
                assertEquals(BookPartSetKind.HOURS_LITURGY_TEXTS, journey.effectivePartSet)
                assertEquals(WorshipKind.HOUR3, route.activeKey.worship)
                assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), parts.map { it.worship })
                assertTrue(parts.all { it.date == "2026-11-22" && it.churchType == "20" })
                assertEquals(3, bookRows.size)
                assertEquals(parts.map { it.sourceUrl() }.toSet(), bookRows.map { it["requestedUrl"].asString }.toSet())
                bookRows.forEach { candidate ->
                    val key = parts.single { it.sourceUrl() == candidate["requestedUrl"].asString }
                    assertEquals(key.date, candidate["date"].asString)
                    assertEquals(key.worship.queryValue, candidate["worship"].asString)
                    assertEquals(key.churchType, candidate["churchType"].asString)
                    assertEquals(key.sourceUrl(), candidate["finalUrl"].asString)
                    assertEquals(200, candidate["status"].asInt)
                    assertEquals("HOST_CANDIDATE_NOT_ANDROID_ROUTE_PROOF", candidate["captureType"].asString)
                }
                receipt["actualResolvedRoute"] = route
                receipt["actualResolvedRouteCandidateUrlsEqual"] = true
                val bookDirectory = File(context.filesDir, "book-cache")
                val bookTransport = ScheduleTransport { url ->
                    val candidate = bookRows.singleOrNull { it["requestedUrl"].asString == url }
                    if (candidate == null) { unavailableBookExtras += url; throw IOException("Optional source unavailable in original-book replay: $url") }
                    servedBooks += url; response(candidate)
                }
                val books = ServiceBookRepository(FileBookCache(bookDirectory), bookTransport, clock = clock, downloadRussian = false)
                assertTrue(parts.all { FileBookCache(bookDirectory).read(it) == null })
                val loaded = books.load(route.activeKey)
                assertTrue("Actual selected third hour: $loaded", loaded is BookLoadResult.Ready)
                val active = loaded as BookLoadResult.Ready
                assertTrue(active.saved); assertFalse(active.fromCache); assertTrue(ServiceBookIntegrity.valid(active.book))
                val chapter = active.book.chapters.first { it.blocks.any { block -> block.kind == BookBlockKind.PRAYER } }
                val block = chapter.blocks.first { it.kind == BookBlockKind.PRAYER }
                val readingPreferences = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
                val store = BookReadingStore(readingPreferences)
                val position = BookReadingPosition(active.book.key, active.book.contentHash, parish.id, block.id, chapter.id,
                    fraction = .31f, mode = BookReadingMode.ORIGINAL, lastTextMode = BookReadingMode.ORIGINAL, journeyContext = journey)
                assertTrue(store.save(position))
                val prepared = BookOfflinePreparation.prepare(active.book, journey, parish.id, books, store)
                assertEquals(parts, prepared.parts.map { it.key }); assertEquals(3, prepared.storedCount)
                assertTrue(prepared.parts.all { it.status in setOf(BookOfflinePartStatus.READY, BookOfflinePartStatus.REVIEW_REQUIRED) })
                val editions = parts.associateWith { key ->
                    val ready = books.load(key) as BookLoadResult.Ready
                    assertTrue(ready.saved); assertTrue(ready.fromCache); assertEquals(key, ready.book.key)
                    assertTrue(ServiceBookIntegrity.valid(ready.book))
                    assertTrue(ready.book.sources.any { it.url == key.sourceUrl() })
                    assertEquals(ready.book, FileBookCache(bookDirectory).readVersion(key, ready.book.contentHash))
                    ready.book
                }
                assertEquals(parts.map { it.sourceUrl() }.sorted(), servedBooks.sorted())
                assertEquals(position, store.restore(active.book, parish.id))
                val diskBefore = hashes(bookDirectory)
                val scheduleBefore = schedulePreferences.all.toMap()
                val readingBefore = readingPreferences.all.toMap()
                val denied = ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException("Offline reopen forbids every request") }
                val coldSchedule = InternetScheduleRepository(denied, cache()).cached(parish)
                assertEquals(state, coldSchedule)
                val coldBooks = ServiceBookRepository(FileBookCache(bookDirectory), denied, clock = clock, downloadRussian = false)
                val coldStore = BookReadingStore(context.getSharedPreferences("reading", Context.MODE_PRIVATE))
                parts.forEach { key ->
                    val selectedPart = requireNotNull(route.selectPart(key, savedCurrentPart = false))
                    assertEquals(journey, selectedPart.journeyContext); assertEquals(parish.id, selectedPart.parishId)
                    val reopened = coldBooks.load(selectedPart.activeKey) as BookLoadResult.Ready
                    assertTrue(reopened.fromCache); assertTrue(reopened.saved)
                    assertEquals(editions.getValue(key), reopened.book)
                    assertNotSame(editions.getValue(key), reopened.book)
                    assertEquals(editions.getValue(key).sources, reopened.book.sources)
                }
                val reopenedActive = requireNotNull(coldBooks.cachedVersion(active.book.key, active.book.contentHash))
                assertEquals(position, coldStore.restore(reopenedActive, parish.id))
                assertEquals(prepared, BookOfflinePreparation.inspect(reopenedActive, journey, parish.id, coldBooks, coldStore))
                assertEquals(diskBefore, hashes(bookDirectory))
                assertEquals(scheduleBefore, schedulePreferences.all); assertEquals(readingBefore, readingPreferences.all)
                assertEquals(0, offlineCalls.get())
                receipt["bookEditions"] = parts.map { key -> val book = editions.getValue(key)
                    mapOf("key" to key.canonical(), "hash" to book.contentHash, "sources" to book.sources,
                        "originalTextSha256" to sha(book.blocks.joinToString("\n") { it.original }.toByteArray(Charsets.UTF_8)),
                        "hoursReview" to BookHourSourceReview.of(book, true).status, "enrichment" to book.enrichment) }
                receipt["offlinePreparationActualState"] = prepared
                receipt["savedPosition"] = position; receipt["bookCacheFiles"] = diskBefore
                receipt["allThreeExactEditionsReopened"] = true; receipt["passed"] = true
            }
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name; receipt["failureMessage"] = failure.message
            throw failure
        } finally {
            val cleanupErrors = mutableListOf<String>()
            preferenceNames.forEach { privateName ->
                runCatching { check(app.deleteSharedPreferences(privateName)) }.onFailure { cleanupErrors += "$privateName: ${it.javaClass.simpleName}" }
            }
            runCatching { check(owned.deleteRecursively()) }.onFailure { cleanupErrors += "ownedFiles: ${it.javaClass.simpleName}" }
            receipt["scheduleRequests"] = scheduleRequests.toList(); receipt["servedBookCandidateUrls"] = servedBooks.toList()
            receipt["unavailableOptionalBookUrls"] = unavailableBookExtras.toList(); receipt["nativeOcrSources"] = nativeSources.toList().sorted()
            receipt["offlineHttpCalls"] = offlineCalls.get(); receipt["cleanupErrors"] = cleanupErrors
            if (cleanupErrors.isNotEmpty()) receipt["passed"] = false
            File(output, "receipt.json").writeText(gson.toJson(receipt))
            if (!receipt.containsKey("failureType")) assertTrue("Owned storage cleanup: $cleanupErrors", cleanupErrors.isEmpty())
        }
    }
}
