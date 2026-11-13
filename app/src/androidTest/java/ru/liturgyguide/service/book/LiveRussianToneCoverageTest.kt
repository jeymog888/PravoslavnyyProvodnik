package ru.liturgyguide.service.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in native public HTTP + real dated books and exact disk reopen; no UI or process restart. */
@RunWith(AndroidJUnit4::class)
class LiveRussianToneCoverageTest {
    @Test fun sevenPublishedToneFragmentsReachRealBooksAndReopenExactlyOffline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("feoriaRussianToneLive") == "true")
        val run = requireNotNull(args.getString("feoriaRussianToneRun")) { "A unique feoriaRussianToneRun is required" }
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "live-russian-tone-${UUID.randomUUID()}")
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "russian-tone-live/$run")
        check(!output.exists() && output.mkdirs()) { "The receipt directory must be new: $output" }
        val fixtureBytes = instrumentation.context.assets.open(FIXTURE).use { it.readBytes() }
        val fixture = JsonParser.parseString(fixtureBytes.toString(Charsets.UTF_8)).asJsonObject
        val pairs = fixture.getAsJsonArray("pairs").map { it.asJsonObject }
        val requests = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val rows = mutableListOf<Map<String, Any?>>()
        val sources = ConcurrentHashMap<String, ScheduleResponse>()
        val feedRequests = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>("run" to run, "startedAtUtc" to Instant.now().toString(),
            "scope" to "native public HTTP; isolated cache; new offline repositories in the same process; no UI",
            "apkSha256" to sha256(File(context.applicationInfo.sourceDir)), "fixtureSha256" to sha256(fixtureBytes),
            "fixtureProvenance" to fixture.getAsJsonObject("provenance"), "expectedPairs" to 4,
            "expectedFragments" to 7, "status" to "RUNNING")
        fun saveReceipt() {
            receipt["requests"] = synchronized(requests) { requests.toList() }
            receipt["books"] = rows.toList()
            File(output, "receipt.json").writeText(Gson().toJson(receipt))
        }
        fun recording(delegate: ScheduleTransport, feed: Boolean = false) = ScheduleTransport { url ->
            try {
                val response = delegate.fetch(url)
                // Production transport returns a response only for HTTP 200; it rejects all other final statuses.
                requests += mapOf("url" to url, "responseUrl" to response.url, "httpStatus" to 200,
                    "statusEvidence" to "UrlConnectionScheduleTransport success contract", "bytes" to response.bytes.size,
                    "sha256" to sha256(response.bytes))
                if (feed) {
                    assertEquals(FEED, url)
                    assertEquals(FEED, response.url)
                    feedRequests.incrementAndGet()
                    val actual = JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
                    assertEquals(DATE, actual.text("date"))
                    for (pair in pairs) {
                        val published = actual.getAsJsonArray("translations").map { it.asJsonObject }
                            .single { it.get("apiId").asInt == pair.get("apiId").asInt }
                        assertEquals("The whole published original must remain unchanged", pair.text("sourceOriginal"), published.text("original"))
                        assertEquals(pair.text("russian"), published.text("russian"))
                        assertEquals(pair.text("publicSourceUrl"), published.text("sourceUrl"))
                        assertEquals(pair.text("sourceTitle"), published.text("sourceTitle"))
                    }
                } else sources[url] = response
                response
            } catch (failure: Throwable) {
                requests += mapOf("url" to url, "status" to "FAILED", "failureType" to failure.javaClass.simpleName)
                throw failure
            }
        }
        saveReceipt()
        try {
            withTimeout(240_000) {
                assertFalse("Only a fresh UUID cache may be used", directory.exists())
                assertEquals(DATE, fixture.text("date"))
                assertEquals(4, pairs.size)
                assertEquals(4, fixture.get("uniquePairs").asInt)
                assertEquals(7, fixture.get("blocks").asInt)
                pairs.forEach { assertEquals(it.text("archivedOriginalSha256"), BookText.sha256(it.text("archivedOriginal"))) }
                val published = PublishedCalendarSupplements(recording(UrlConnectionScheduleTransport(
                    timeoutMillis = 3_000, limitBytes = PublishedCalendarSupplements.MAX_BYTES), feed = true))
                val calendar = CalendarSupplements { date ->
                    assertEquals(DATE, date)
                    val supplement = requireNotNull(published.load(date)) { "The real dated feed must decode" }
                    assertEquals("https://azbyka.ru/days/$DATE", supplement.day.sourceUrl)
                    for (pair in pairs) {
                        val translation = requireNotNull(supplement.translations.find(pair.text("archivedOriginal")))
                        assertEquals(pair.text("verifiedMetadataPrefix") + pair.text("russian"), translation.russian)
                        assertEquals(pair.text("publicSourceUrl"), translation.source.url)
                        assertEquals(pair.text("sourceTitle"), translation.source.title)
                    }
                    supplement
                }
                val online = ServiceBookRepository(FileBookCache(directory), recording(UrlConnectionScheduleTransport(timeoutMillis = 15_000)),
                    translations = BookTranslationIndex.fromFeoriaCatalog(), calendar = calendar)
                var delivered = 0
                for (kind in listOf(WorshipKind.VESPERS, WorshipKind.MATINS)) {
                    val key = ServiceBookKey(DATE, kind, "20")
                    val result = online.load(key)
                    assertTrue("Real $kind must load: $result", result is BookLoadResult.Ready)
                    val ready = result as BookLoadResult.Ready
                    val book = ready.book
                    assertTrue(ready.saved)
                    assertFalse(ready.fromCache)
                    assertEquals(key, book.key)
                    assertEquals(key.sourceUrl(), book.sourceUrl)
                    assertTrue(ServiceBookIntegrity.valid(book))
                    assertEquals(DATE, requireNotNull(book.calendarDay).date)
                    val response = requireNotNull(sources[key.sourceUrl()])
                    assertEquals(key.sourceUrl(), response.url)
                    val plain = ServiceBookParser().parse(requireNotNull(response.html()), key, book.fetchedAtMillis)
                    assertEquals(plain.selectedEventTitle, book.selectedEventTitle)
                    assertEquals(plain.mainEventOptions, book.mainEventOptions)
                    assertEquals(plain.secondEventOptions, book.secondEventOptions)
                    assertEquals("Original blocks must not be inserted, replaced or reordered", originalDigest(plain), originalDigest(book))
                    val matched = pairs.flatMap { pair ->
                        val blocks = book.blocks.filter { it.original == pair.text("archivedOriginal") }
                        assertEquals("Exact archived occurrence count: ${pair.text("missingId")} / $kind",
                            pair.getAsJsonObject("occurrencesByBook").get(kind.queryValue).asInt, blocks.size)
                        blocks.forEach { block ->
                            assertEquals(pair.text("verifiedMetadataPrefix") + pair.text("russian"), block.russian)
                            val source = requireNotNull(block.translationSource)
                            assertEquals(pair.text("publicSourceUrl"), source.url)
                            assertTrue("The source must be the real calendar HTML or feed, not an editorial fallback",
                                source.title in setOf(pair.text("sourceTitle"), "Азбука веры · календарь на $DATE"))
                        }
                        blocks
                    }
                    delivered += matched.size
                    assertEquals(book, FileBookCache(directory).readVersion(key, book.contentHash))
                    val denied = AtomicInteger()
                    val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                        denied.incrementAndGet(); error("Offline book transport called")
                    }, calendar = CalendarSupplements { denied.incrementAndGet(); error("Offline feed called") })
                    val reopened = offline.load(key)
                    assertTrue(reopened is BookLoadResult.Ready && reopened.fromCache && reopened.saved)
                    assertEquals(book, (reopened as BookLoadResult.Ready).book)
                    assertNotSame(book, reopened.book)
                    assertEquals(book, offline.cachedVersion(key, book.contentHash))
                    assertEquals(0, denied.get())
                    rows += mapOf("key" to key.canonical(), "sourceUrl" to book.sourceUrl, "contentHash" to book.contentHash,
                        "originalDigest" to originalDigest(book), "fragmentCount" to matched.size,
                        "fragments" to matched.map { mapOf("id" to it.id, "original" to it.original, "russian" to it.russian,
                            "translationSource" to it.translationSource) }, "offlineExact" to true, "status" to "PASS")
                    saveReceipt()
                }
                assertEquals(7, delivered)
                assertEquals("Each real book must fetch the production dated feed", 2, feedRequests.get())
                receipt["deliveredFragments"] = delivered
                receipt["feedRequests"] = feedRequests.get()
                receipt["status"] = "PASS"
            }
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"
            receipt["failureType"] = failure.javaClass.simpleName
            receipt["failure"] = failure.message?.take(1_000)
            throw failure
        } finally {
            saveReceipt()
            directory.deleteRecursively() // No app preference, shared cache or guide state is touched.
        }
    }

    private fun originalDigest(book: ServiceBook) = BookText.sha256(Gson().toJson(book.chapters.map { chapter ->
        listOf(chapter.id, chapter.title, chapter.blocks.map { listOf(it.id, it.kind, it.original,
            it.speaker, it.isAltarText, it.scriptureReferences) })
    }))
    private fun JsonObject.text(name: String): String = get(name).asString
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        const val DATE = "2026-11-13"
        const val FIXTURE = "russian-coverage-11/published-first-batch.json"
        const val FEED = "${PublishedCalendarSupplements.BASE_URL}/$DATE.json"
    }
}
