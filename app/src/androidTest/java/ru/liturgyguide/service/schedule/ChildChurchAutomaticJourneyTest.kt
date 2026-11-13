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

/** Explicit native acceptance. A replay and a live run are labelled separately; neither seeds a source override. */
@RunWith(AndroidJUnit4::class)
class ChildChurchAutomaticJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val now = Instant.parse("2026-11-13T01:00:00Z")
    private val website = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
    private val about = "https://nskmonastyr.cerkov.ru/o-monastyre/"
    private val contact = "https://nskmonastyr.cerkov.ru/kontakty/"
    private val selected = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских",
        "Храм Новомучеников", "", "улица Немировича-Данченко, 120/1", "", "", "Asia/Novosibirsk",
        "https://www.openstreetmap.org/way/55228075", "", "", "", emptyList(), "", "", 54.97553165, 82.9086244)

    @Test fun recordedSourceAutomaticallySelectsActualEventAndReopensWholeDatedBookOffline() = runBlocking {
        assumeTrue("Opt in to deterministic native source/book replay", args.getString("childChurchRecorded") == "true")
        verify(live = false)
    }

    @Test fun liveSourceAutomaticallySelectsActualEventAndReopensWholeDatedBookOffline() = runBlocking {
        assumeTrue("Opt in to real public source, Palomnik and Azbyka requests", args.getString("childChurchLive") == "true")
        verify(live = true)
    }

    private fun fixture(name: String) = instrumentation.context.assets.open("child-church-own-site/$name").use { it.readBytes() }
    private fun fixtureResponse(url: String, name: String, html: Boolean = true) = ScheduleResponse(url, fixture(name),
        if (html) "text/html; charset=UTF-8" else "application/json")

    private suspend fun verify(live: Boolean) {
        val mode = if (live) "live" else "recorded"
        val run = args.getString("childChurchRun") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val output = File(requireNotNull(context.getExternalFilesDir("child-church-automatic")), "$run/$mode")
        check(!output.exists() && output.mkdirs()) { "Use a new childChurchRun for each invocation" }
        val preferencePrefix = "child-church-${UUID.randomUUID()}"
        val bookDirectory = File(context.cacheDir, preferencePrefix)
        val requests = CopyOnWriteArrayList<Map<String, Any?>>()
        val offlineCalls = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "run" to run, "mode" to mode,
            "selected" to selected, "clock" to now.toString(), "apkSha256" to shaFile(File(context.applicationInfo.sourceDir)),
            "requests" to requests, "sourceOverride" to null,
            "scope" to if (live) "Real current public HTTP responses through production transports and automatic resolver"
                else "Deterministic replay of recorded actual schedule/article and dated original book through production resolver/parser/repositories",
            "isolation" to "New UUID schedule/reading preferences and book directory; no application selection/preferences are changed",
            "offlineScope" to "New repository instances reading exact committed editions in the same Android process with a transport that throws on every call; radios and process restart are not covered")
        fun save() { synchronized(receipt) {
            receipt["offlineCalls"] = offlineCalls.get(); File(output, "receipt.json").writeText(gson.toJson(receipt))
        } }
        fun cache(): ScheduleCacheStore = object : ScheduleCacheStore {
            private val preferences = context.getSharedPreferences("$preferencePrefix-schedule", Context.MODE_PRIVATE)
            override fun read(parishId: String) = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                require(parishId == selected.id); check(preferences.edit().putString(parishId, value).commit())
            }
        }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
        val transport = object : ScheduleTransport {
            override suspend fun fetch(url: String) = fetchBounded(url, AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES)
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
                try {
                    val value = if (live) network.fetchBounded(url, maximumBytes) else when {
                        "api.openstreetmap.org" in url -> ScheduleResponse(url, "{\"elements\":[]}".toByteArray(), "application/json")
                        "prop=revisions" in url -> fixtureResponse(url, "nsk-palomnik-api.json", false)
                        "list=search" in url -> fixtureResponse(url,
                            if ("%22" in url) "novosibirsk-street.json" else "novosibirsk-name.json", false)
                        url == website -> fixtureResponse(url, "nsk-schedule.html")
                        url == about -> fixtureResponse(url, "nsk-about.html")
                        url == contact -> fixtureResponse(url, "nsk-contact.html")
                        else -> throw IOException("No recorded response for $url")
                    }
                    require(value.bytes.size <= maximumBytes)
                    synchronized(requests) {
                        requests += mapOf("requestedUrl" to url, "returnedUrl" to value.url, "status" to 200,
                            "bytes" to value.bytes.size, "sha256" to sha(value.bytes), "maximumBytes" to maximumBytes)
                    }
                    return value
                } catch (failure: Exception) {
                    synchronized(requests) { requests += mapOf("requestedUrl" to url, "failureType" to failure.javaClass.name) }
                    throw failure
                } finally { save() }
            }
        }
        save()
        try {
            assertEquals("", selected.website); assertEquals("", selected.city)
            assertNull(cache().read(selected.id))
            val schedules = InternetScheduleRepository(transport, cache(), AndroidScheduleDocumentExtractor(context),
                siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore()))
            val state = withTimeout(150_000) { schedules.refresh(selected, now, force = true) }
            File(output, "schedule.json").writeText(gson.toJson(state))
            assertEquals(InternetScheduleStatus.READY, state.status)
            assertEquals("", state.customSource); assertEquals("", state.configuredWebsite)
            assertFalse(state.sourceNeedsVerification); assertFalse(state.publicationRejected)
            assertEquals(ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE, state.siteBinding?.method)
            assertEquals(ScheduleEntityScope.MONASTERY, state.siteBinding?.matchedScope)
            assertTrue(requireNotNull(state.siteBinding).validFor(selected, state.website, state.siteEvidenceUrl))
            val publication = requireNotNull(state.publication)
            assertTrue(publication.yearInferred)
            val civilDate = if (live) LocalDate.of(2026, 11, 13) else LocalDate.of(2026, 11, 12)
            val events = publication.forDate(civilDate)
            assertEquals(listOf("08:30", "17:00"), events.map { it.time })
            val event = events.single { it.time == "08:30" }
            val service = ParishService(event.date, event.time, event.title, event.kind)
            val morning = requireNotNull(ServiceBookSelection.resolve(service, selected))
            assertEquals(WorshipKind.HOUR3, morning.activeKey.worship)
            val parts = requireNotNull(morning.journeyContext).parts
            assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), parts.map { it.worship })
            assertTrue(parts.all { it.date == civilDate.toString() })
            // Opening the explicitly offered liturgy is independent of either hour loading.
            val route = requireNotNull(morning.selectPart(parts.last(), savedCurrentPart = false))
            assertEquals(civilDate.toString(), route.activeKey.date)
            assertEquals(WorshipKind.LITURGY, route.activeKey.worship)
            assertEquals(selected.id, route.parishId)
            assertEquals("service_block", event.kind) // 08:30 is the published hours+liturgy block, not an invented liturgy clock.
            receipt["selectedEvent"] = event; receipt["bookKey"] = route.activeKey
            val bookTransport = if (live) transport else ScheduleTransport { url ->
                if (url != route.activeKey.sourceUrl()) throw IOException("Optional supplement is outside this original-book replay")
                fixtureResponse(url, "liturgy-2026-11-12.html")
            }
            val online = ServiceBookRepository(FileBookCache(bookDirectory), bookTransport,
                translations = BookTranslationIndex.fromFeoriaCatalog(), downloadRussian = live)
            val loaded = withTimeout(150_000) { online.load(route.activeKey) }
            assertTrue("Dated book: $loaded", loaded is BookLoadResult.Ready)
            val ready = loaded as BookLoadResult.Ready
            val book = ready.book
            assertTrue(ready.saved); assertFalse(ready.fromCache)
            assertEquals(route.activeKey, book.key); assertTrue(ServiceBookIntegrity.valid(book))
            assertTrue(book.blocks.size > 200)
            assertTrue(book.chapters.any { BookText.normalized(it.title) == "отпуст" })
            val chapter = book.chapters.first { it.blocks.isNotEmpty() }
            val reading = BookReadingPosition(book.key, book.contentHash, selected.id, chapter.blocks.first().id,
                chapter.id, .25f, BookReadingMode.ORIGINAL)
            val readingPreferences = context.getSharedPreferences("$preferencePrefix-reading", Context.MODE_PRIVATE)
            assertTrue(BookReadingStore(readingPreferences).save(reading))
            val offlineTransport = ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException("Offline transport") }
            val reopenedSchedule = InternetScheduleRepository(offlineTransport, cache()).cached(selected)
            assertEquals(state, reopenedSchedule)
            val offlineBooks = ServiceBookRepository(FileBookCache(bookDirectory), offlineTransport)
            val reopened = offlineBooks.load(book.key)
            assertTrue(reopened is BookLoadResult.Ready)
            assertTrue((reopened as BookLoadResult.Ready).fromCache)
            assertEquals(book, reopened.book)
            assertEquals(book, offlineBooks.cachedVersion(book.key, book.contentHash))
            assertEquals(reading, BookReadingStore(context.getSharedPreferences("$preferencePrefix-reading", Context.MODE_PRIVATE))
                .positionFor(book.key, selected.id))
            assertEquals(0, offlineCalls.get())
            receipt["bookContentHash"] = book.contentHash; receipt["bookBlocks"] = book.blocks.size
            receipt["originalTextSha256"] = sha(book.blocks.joinToString("\n") { it.original }.toByteArray())
            receipt["bookWarning"] = ready.warning; receipt["passed"] = true
        } finally {
            save()
            context.deleteSharedPreferences("$preferencePrefix-schedule")
            context.deleteSharedPreferences("$preferencePrefix-reading")
            bookDirectory.deleteRecursively()
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun shaFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
