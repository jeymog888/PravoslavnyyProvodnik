package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Controlled directory output, actual unmodified schedule/book bytes, real Android persistence.
 * This is not a live provider/coverage test, a UI gesture test or a physical cold-process test. */
@RunWith(AndroidJUnit4::class)
class ScheduleFastDiscoveryNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open("child-church-own-site/$name")
        .bufferedReader().use { it.readText() }
    private fun response(url: String, value: String) = ScheduleResponse(url, value.toByteArray(), "text/html; charset=UTF-8")

    @Test fun coldProposalFinishesBeforeHeavyLookupThenFreshConfirmationOpensActualDatedBookAndRecreatesOffline() = runBlocking {
        val context = instrumentation.targetContext
        val name = "fast-discovery-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val parish = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских",
            "Новомучеников", "", "улица Немировича-Данченко, 120/1", "", "", "Asia/Novosibirsk",
            "https://www.openstreetmap.org/way/55228075", "", "", "", emptyList(), "", "", 54.97553165, 82.9086244)
        val source = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
        val osm = "https://api.openstreetmap.org/api/0.6/way/55228075.json"
        val evidence = "https://nskmonastyr.cerkov.ru/o-monastyre/"
        val now = Instant.parse("2026-11-13T12:00:00Z")
        val schedule = fixture("nsk-schedule.html")
        val original = fixture("liturgy-2026-11-12.html")
        val about = fixture("nsk-about.html")
        assertEquals("09a82c3bcb9a48aaeda668459bab7a4b2f17d5f360cf367b589c7b27423bc71d", BookText.sha256(schedule))
        assertEquals("51e30dfb845e7ca102d5725fda4cb350ec9d524bc6478b6b18be8c7b5a4753fe", BookText.sha256(original))
        assertEquals("3d683e58d4f78d9c44309ec46382f61912632eea35755e687e9b5d86ac9679c0", BookText.sha256(about))
        fun diskSchedules() = object : ScheduleCacheStore {
            override fun read(parishId: String) = preferences.getString("schedule:$parishId", null)
            override fun write(parishId: String, value: String) { check(preferences.edit().putString("schedule:$parishId", value).commit()) }
        }
        // A controlled result exercises the fast-stage contract. It does not assert that a real
        // public index supplied this geometry or that it can prove the source automatically.
        val suggestion = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(parish),
            provider = "controlled_index", providerLabel = "Контрольный результат справочника", providerObjectId = "nsk-test",
            name = parish.name, address = parish.address, sourceUrl = source, evidenceUrl = evidence,
            evidenceSha256 = BookText.sha256(about), checkedAt = now.toString(), latitude = parish.latitude!!,
            longitude = parish.longitude!!, outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте источник")
        val batches = AtomicInteger(); val calls = CopyOnWriteArrayList<String>()
        val http = ScheduleTransport { url ->
            calls += url
            when (url) {
                osm -> response(url, """{"elements":[{"type":"way","id":55228075,"tags":{}}]}""")
                source -> response(url, schedule)
                else -> throw IOException("No uncontrolled fixture request: $url")
            }
        }
        val resolver = OpenScheduleSiteResolver(http, candidateProvider = ScheduleCandidateProvider { _, _ ->
            batches.incrementAndGet(); ScheduleCandidateDiscovery(listOf(suggestion), listOf("nsk-test"))
        })
        try {
            val repository = InternetScheduleRepository(http, diskSchedules(), siteResolver = resolver)
            val pending = repository.refresh(parish, now)
            assertEquals(listOf(osm), calls.toList()); assertEquals(1, batches.get())
            assertFalse(pending.loading); assertTrue(pending.sourceCandidates.single().canConfirm(parish))
            assertNull(pending.publication); assertNull(pending.siteBinding)
            val ready = repository.confirmCandidate(parish, suggestion.id, now.plusSeconds(1))
            assertEquals(2, batches.get()); assertEquals(InternetScheduleStatus.READY, ready.status)
            assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
            val event = requireNotNull(ready.publication).events.single { it.date == "2026-11-12" && it.time == "08:30" }
            assertTrue(event.title.contains("Литургия", ignoreCase = true))
            val route = requireNotNull(ServiceBookSelection.resolve(ParishService(event.date, event.time, event.title, event.kind), parish))
            val key = requireNotNull(route.journeyContext).parts.single { it.worship == WorshipKind.LITURGY }
            assertEquals(ServiceBookKey("2026-11-12", WorshipKind.LITURGY, "30"), key)
            val bookCalls = CopyOnWriteArrayList<String>()
            val bookTransport = ScheduleTransport { url -> bookCalls += url
                if (url == key.sourceUrl()) response(url, original) else throw IOException("Supplement unavailable in this bounded replay")
            }
            val books = ServiceBookRepository(FileBookCache(directory), bookTransport, clock = Clock.fixed(now, ZoneOffset.UTC))
            val loaded = books.load(key) as BookLoadResult.Ready
            assertFalse(loaded.fromCache); assertTrue(loaded.saved); assertTrue(ServiceBookIntegrity.valid(loaded.book))
            assertEquals(key, loaded.book.key); assertTrue(bookCalls.contains(key.sourceUrl()))
            val chapter = loaded.book.chapters.first { it.blocks.any { b -> b.kind == BookBlockKind.PRAYER } }
            val block = chapter.blocks.first { it.kind == BookBlockKind.PRAYER }
            val position = BookReadingPosition(key, loaded.book.contentHash, parish.id, block.id, chapter.id, fraction = .31f,
                mode = BookReadingMode.ORIGINAL, journeyContext = route.journeyContext)
            assertTrue(BookReadingStore(preferences).save(position))
            val stored = preferences.all.toMap(); val acceptedRaw = FileBookCache(directory).readVersion(key, loaded.book.contentHash)
            val offlineCalls = AtomicInteger()
            val offline = ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException("Offline recreation") }
            val reopenedSchedules = InternetScheduleRepository(offline, diskSchedules()).cached(parish)
            assertEquals(ready.publication, reopenedSchedules.publication); assertEquals(ready.siteBinding, reopenedSchedules.siteBinding)
            val reopened = ServiceBookRepository(FileBookCache(directory), offline).load(key) as BookLoadResult.Ready
            assertTrue(reopened.fromCache); assertEquals(loaded.book, reopened.book); assertEquals(acceptedRaw, reopened.book)
            assertEquals(position, BookReadingStore(preferences).positionFor(key, parish.id)); assertEquals(stored, preferences.all)
            assertEquals(0, offlineCalls.get())
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
