package ru.liturgyguide.service.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in public-network delivery check. No API credential or old application cache is used. */
@RunWith(AndroidJUnit4::class)
class LiveCalendarBookTest {
    @Test fun publishedCalendarEnrichesRealDatedBooksAndSurvivesANewOfflineRepository() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("feoriaLiveCalendar") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = UUID.randomUUID().toString()
        val startedAt = Instant.now().toString()
        val directory = File(context.cacheDir, "live-calendar-book-$runId")
        val output = File(context.filesDir, "live-calendar-book-09.json")
        val rows = mutableListOf<MutableMap<String, Any?>>()
        val failures = mutableListOf<String>()
        fun saveReport() {
            output.writeText(Gson().toJson(mapOf("runId" to runId, "startedAt" to startedAt,
                "dates" to rows, "allPassed" to (rows.size == 3 && rows.all { it["status"] == "PASS" }))))
        }
        saveReport() // Replace an earlier run before the first network request.
        try {
            for (date in listOf("2026-11-11", "2026-11-12", "2026-11-15")) {
                val started = System.nanoTime()
                val key = ServiceBookKey(date)
                val row = linkedMapOf<String, Any?>("date" to date, "status" to "RUNNING", "phase" to "feed")
                rows += row
                saveReport()
                try {
                    val expectedFeedUrl = "${PublishedCalendarSupplements.BASE_URL}/$date.json"
                    val feedRequests = AtomicInteger()
                    val feedBytes = mutableListOf<Int>()
                    val feedNetwork = UrlConnectionScheduleTransport(timeoutMillis = 3_000,
                        limitBytes = PublishedCalendarSupplements.MAX_BYTES)
                    val calendar = PublishedCalendarSupplements(ScheduleTransport { url ->
                        assertEquals("Provider must request the exact selected date", expectedFeedUrl, url)
                        val response = feedNetwork.fetch(url)
                        assertEquals("A redirected or substituted feed must not be accepted", expectedFeedUrl, response.url)
                        assertTrue("Date JSON must be nonempty and bounded",
                            response.bytes.size in 1..PublishedCalendarSupplements.MAX_BYTES)
                        feedRequests.incrementAndGet()
                        feedBytes += response.bytes.size
                        response
                    })
                    // Verify the deployed package through the same native decoder used by the app.
                    val supplement = calendar.load(date)
                    assertNotNull("Published calendar must decode for $date", supplement)
                    val day = requireNotNull(supplement).day
                    assertEquals(date, day.date)
                    assertEquals("https://azbyka.ru/days/$date", day.sourceUrl)
                    assertTrue("Published week title must be present", day.weekTitle.isNotBlank())
                    assertTrue("The verified sample dates have commemorations", day.commemorations.isNotEmpty())
                    assertTrue("The verified sample dates have exact translation pairs", supplement.translations.size > 0)
                    row["feedUrl"] = expectedFeedUrl
                    row["contextCount"] = day.commemorations.size
                    row["feedTranslationCount"] = supplement.translations.size
                    row["phase"] = "book"
                    saveReport()

                    var originalResponse: ScheduleResponse? = null
                    val bookNetwork = UrlConnectionScheduleTransport(timeoutMillis = 15_000)
                    val transport = ScheduleTransport { url ->
                        bookNetwork.fetch(url).also { if (url == key.sourceUrl()) originalResponse = it }
                    }
                    val online = ServiceBookRepository(FileBookCache(directory), transport, calendar = calendar)
                    val result = online.load(key)
                    assertTrue("$date must load a dated book; result=${result.javaClass.simpleName}", result is BookLoadResult.Ready)
                    val ready = result as BookLoadResult.Ready
                    val book = ready.book
                    val scripture = book.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }
                    row["hash"] = book.contentHash
                    row["blocks"] = book.blocks.size
                    row["chapters"] = book.chapters.size
                    row["translatedCount"] = book.translatedPrayerCount
                    row["sourceUrl"] = book.sourceUrl
                    row["calendarSourceUrl"] = day.sourceUrl
                    row["scriptureCount"] = scripture.size
                    row["translatedScriptureCount"] = scripture.count { !it.russian.isNullOrBlank() }
                    row["phase"] = "book-integrity"
                    saveReport()
                    assertTrue("$date must be saved", ready.saved)
                    assertFalse("A fresh unique cache cannot already contain this date", ready.fromCache)
                    assertEquals(key, book.key)
                    assertEquals(key.sourceUrl(), book.sourceUrl)
                    assertEquals(day, book.calendarDay)
                    assertTrue("The complete book must pass its integrity checks", ServiceBookIntegrity.valid(book))
                    assertTrue("The full selected liturgy has more than 200 blocks", book.blocks.size > 200)
                    assertTrue("The final dismissal must be included",
                        book.chapters.any { BookText.normalized(it.title).contains("отпуст") })
                    assertTrue("The book must have published Russian prayer or Scripture text", book.translatedPrayerCount > 0)
                    row["phase"] = "book-translations"
                    assertTrue("All dated Scripture must remain available in Russian",
                        scripture.isNotEmpty() && scripture.all { !it.russian.isNullOrBlank() })
                    assertTrue("Published prayers cannot silently become editorial paraphrases", book.blocks.filter {
                        it.russian != null && it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE)
                    }.all { it.translationSource != null && it.translationSource != BookTranslationIndex.EDITORIAL_SOURCE })
                    assertTrue("Chapter explanations must be retained", book.chapters.all { it.meaning.isNotBlank() })

                    // Compare with the very same downloaded source, avoiding a second request or a changing website.
                    row["phase"] = "original-order"
                    val sourcePage = requireNotNull(originalResponse?.html())
                    val plain = ServiceBookParser().parse(sourcePage, key, book.fetchedAtMillis)
                    assertEquals("Calendar must not choose another service variant", plain.selectedEventTitle, book.selectedEventTitle)
                    assertEquals(plain.mainEventOptions, book.mainEventOptions)
                    assertEquals(plain.secondEventOptions, book.secondEventOptions)
                    assertEquals(plain.chapters.map { it.id }, book.chapters.map { it.id })
                    assertEquals("Calendar must not add, replace or reorder original service fragments", originalDigest(plain), originalDigest(book))
                    assertEquals("Repository must fetch its own calendar package", 2, feedRequests.get())

                    row["apiExactOriginalMatches"] = book.blocks.count { supplement.translations.find(it.original) != null }
                    row["feedBytes"] = feedBytes.toList()
                    row["phase"] = "offline"
                    saveReport()

                    val forbiddenRequests = AtomicInteger()
                    val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                        forbiddenRequests.incrementAndGet()
                        error("Offline book transport must not be called")
                    }, calendar = CalendarSupplements {
                        forbiddenRequests.incrementAndGet()
                        error("Offline calendar transport must not be called")
                    })
                    val restoredResult = offline.load(key)
                    assertTrue("Saved edition must reopen offline", restoredResult is BookLoadResult.Ready)
                    val restored = restoredResult as BookLoadResult.Ready
                    val equal = book == restored.book
                    row["offlineEqual"] = equal
                    assertTrue(restored.fromCache)
                    assertTrue("Offline equality includes all original/Russian text, explanations, sources and day context", equal)
                    assertNotSame(book, restored.book)
                    assertEquals(book.contentHash, restored.book.contentHash)
                    assertEquals(book.chapters.map { it.id }, restored.book.chapters.map { it.id })
                    assertTrue("Pinned accepted edition must also remain readable", book == offline.cachedVersion(key, book.contentHash))
                    assertEquals("Neither book nor calendar may contact the network on cached restore", 0, forbiddenRequests.get())
                    row["status"] = "PASS"
                    row["phase"] = "complete"
                } catch (cancelled: CancellationException) {
                    row["status"] = "CANCELLED"
                    throw cancelled
                } catch (failure: AssertionError) {
                    row["status"] = "FAIL"
                    row["failureType"] = failure.javaClass.simpleName
                    failures += "$date:${row["phase"]}:assertion"
                } catch (failure: Exception) {
                    row["status"] = "FAIL"
                    row["failureType"] = failure.javaClass.simpleName
                    failures += "$date:${row["phase"]}:${failure.javaClass.simpleName}"
                } finally {
                    row["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000
                    saveReport()
                }
            }
            assertTrue("Live calendar/book failures: $failures; inspect live-calendar-book-09.json", failures.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun originalDigest(book: ServiceBook): String = BookText.sha256(Gson().toJson(book.chapters.map { chapter ->
        mapOf("id" to chapter.id, "title" to chapter.title, "blocks" to chapter.blocks.map { block ->
            mapOf("id" to block.id, "kind" to block.kind.name, "speaker" to block.speaker,
                "original" to block.original, "altar" to block.isAltarText, "references" to block.scriptureReferences)
        })
    }))
}
